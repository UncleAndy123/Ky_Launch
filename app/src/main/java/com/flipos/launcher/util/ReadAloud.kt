package com.flipos.launcher.util

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationKind
import com.flipos.launcher.data.NotificationStore
import java.util.Locale

/**
 * Speaks the Home notification banner's message aloud as it arrives - the
 * same item [com.flipos.launcher.activities.MainActivity]'s banner shows
 * (see [LauncherPrefs.isShownOnHome]), worded like TurboText's read-aloud
 * ("Message from <name>. <body>"). Observes [NotificationStore] process-wide
 * rather than from Home itself, so it still reads while the flip is closed
 * or another app is in front.
 */
object ReadAloud {

    private var appContext: Context? = null
    private var attachedAt = 0L

    /** Notification key + text pairs already spoken, so updates/re-posts with the same text stay quiet. */
    private val spoken = LinkedHashSet<String>()

    private val storeListener: () -> Unit = { onStoreChanged() }

    /** Starts observing [NotificationStore]; idempotent, safe to call from every entry point. */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        attachedAt = System.currentTimeMillis()
        NotificationStore.addListener(storeListener)
    }

    private fun onStoreChanged() {
        val context = appContext ?: return
        val prefs = LauncherPrefs(context)
        val mode = prefs.getReadAloudMode()
        if (mode == LauncherPrefs.READ_ALOUD_NEVER) return
        val item = NotificationStore.items.firstOrNull { prefs.isShownOnHome(it.kind) } ?: return
        // Anything already posted before we started listening isn't a new
        // arrival - don't read out a backlog after boot or a listener rebind.
        if (item.postTime < attachedAt - STARTUP_GRACE_MS) return
        val id = "${item.key}\u0000${item.text}"
        if (!spoken.add(id)) return
        if (spoken.size > MAX_SPOKEN) spoken.remove(spoken.first())
        if (mode == LauncherPrefs.READ_ALOUD_BLUETOOTH && !isBluetoothAudioConnected(context)) return
        ReadAloudSpeaker.speak(context, phrase(context, prefs, item))
    }

    private fun phrase(context: Context, prefs: LauncherPrefs, item: NoticeItem): String {
        // Mirror the banner: with message text hidden it shows only the app name.
        if (prefs.isNotificationTextHidden()) return appLabel(context, item.packageName)
        val title = if (item.kind == NotificationKind.MESSAGE) "Message from ${item.title}" else item.title
        return if (item.text.isBlank()) title else "$title. ${item.text}"
    }

    private fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * An actively connected Bluetooth *audio* output (headset/speaker), not
     * just Bluetooth being switched on.
     */
    private fun isBluetoothAudioConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Exception) {
            false
        }
    }

    private const val STARTUP_GRACE_MS = 2_000L
    private const val MAX_SPOKEN = 50
}

/**
 * On-device text-to-speech through the phone's default TTS engine, ported
 * from TurboText's NativeTtsHelper. While speaking it holds transient audio
 * focus and an (invisible) [MediaSession]: losing focus is how an incoming
 * or ongoing call shows up, and the session is where a Bluetooth headset's
 * pause button lands - either one stops the readout.
 */
object ReadAloudSpeaker {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    private val pending = ArrayList<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var inFlight = 0

    private var audioManager: AudioManager? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { handleFocusChange(it) }

    private fun ensureInitialized(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status != TextToSpeech.SUCCESS) {
                    failed = true
                    pending.clear()
                    return@post
                }
                tts?.language = Locale.getDefault()
                applyPreferences(appContext)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = onUtteranceFinished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onUtteranceFinished()
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceFinished()
                })
                ready = true
                pending.forEach { speakNow(appContext, it) }
                pending.clear()
            }
        }
    }

    /** A fresh engine always starts at its own defaults, so the saved voice/rate are reapplied on every init. */
    private fun applyPreferences(context: Context) {
        val prefs = LauncherPrefs(context)
        prefs.getReadAloudVoice()?.let { name -> tts?.voices?.find { it.name == name }?.let { tts?.voice = it } }
        tts?.setSpeechRate(prefs.getReadAloudRate())
    }

    /** Queues [text] for speaking; must be called on the main thread. Skipped entirely during a call. */
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        ensureInitialized(context)
        if (isOnCall()) return
        when {
            ready -> speakNow(context.applicationContext, text)
            failed -> Unit
            else -> pending.add(text) // flushed once the engine is ready
        }
    }

    private fun speakNow(context: Context, text: String) {
        inFlight++
        acquireFocusAndSession(context)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "fl_${System.nanoTime()}")
    }

    private fun onUtteranceFinished() {
        mainHandler.post {
            if (--inFlight <= 0) {
                inFlight = 0
                releaseFocusAndSession()
            }
        }
    }

    /**
     * AudioManager's mode leaves MODE_NORMAL while a call rings or is
     * active - checked instead of TelephonyManager.getCallState(), which
     * needs READ_PHONE_STATE on newer Android versions.
     */
    private fun isOnCall(): Boolean = audioManager?.mode?.let { it != AudioManager.MODE_NORMAL } ?: false

    private fun acquireFocusAndSession(context: Context) {
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "FlipLauncherReadAloud").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPause() = stop()
                    override fun onStop() = stop()
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build(),
                )
                isActive = true
            }
        }
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { handleFocusChange(it) }
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseFocusAndSession() {
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(legacyFocusListener)
        }
    }

    private fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stop()
        }
    }

    fun stop() {
        mainHandler.post {
            tts?.stop()
            pending.clear()
            inFlight = 0
            releaseFocusAndSession()
        }
    }

    /**
     * Every offline voice the engine reports, this locale's language first,
     * then best quality first. Delivered on the main thread once the engine
     * is up (empty if it failed or offers none).
     */
    fun listVoices(context: Context, onResult: (List<Voice>) -> Unit) {
        ensureInitialized(context)
        fun deliver() {
            val language = Locale.getDefault().language
            val voices = (if (ready) tts?.voices?.toList() else null).orEmpty()
                .filter { !it.isNetworkConnectionRequired }
                .sortedWith(
                    compareByDescending<Voice> { it.locale.language == language }.thenByDescending { it.quality },
                )
            onResult(voices)
        }
        if (ready || failed) deliver() else mainHandler.postDelayed({ deliver() }, ENGINE_WAIT_MS)
    }

    fun setVoice(context: Context, name: String) {
        LauncherPrefs(context).setReadAloudVoice(name)
        ensureInitialized(context)
        tts?.voices?.find { it.name == name }?.let { tts?.voice = it }
    }

    fun setSpeechRate(context: Context, rate: Float) {
        LauncherPrefs(context).setReadAloudRate(rate)
        ensureInitialized(context)
        tts?.setSpeechRate(rate)
    }

    private const val ENGINE_WAIT_MS = 800L
}
