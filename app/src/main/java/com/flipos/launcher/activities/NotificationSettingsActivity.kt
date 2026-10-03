package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.speech.tts.Voice
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.service.NotificationAccessibilityService
import com.flipos.launcher.service.NotificationCountService
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.ReadAloudSpeaker
import com.flipos.launcher.util.openAppPermissionSettings

/** Notification settings: access grant plus which badges appear on Home/icons. */
class NotificationSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    private val readCallLogPermission = PermissionGate(this, Manifest.permission.READ_CALL_LOG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_notifications)

        actions[ID_ACCESS] = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        actions[ID_CALL_LOG_ACCESS] = { requestCallLogAccess() }
        actions[ID_ACCESSIBILITY_ACCESS] = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        actions[ID_CALLS] = {
            prefs.setCallBadgeEnabled(!prefs.isCallBadgeEnabled()); refreshRows()
        }
        actions[ID_MESSAGES] = {
            prefs.setMessageBadgeEnabled(!prefs.isMessageBadgeEnabled()); refreshRows()
        }
        actions[ID_OTHER] = {
            prefs.setOtherBadgeEnabled(!prefs.isOtherBadgeEnabled()); refreshRows()
        }
        actions[ID_HIDE_TEXT] = {
            prefs.setNotificationTextHidden(!prefs.isNotificationTextHidden()); refreshRows()
        }
        actions[ID_READ_ALOUD] = { chooseReadAloudMode() }
        actions[ID_READ_ALOUD_VOICE] = { chooseReadAloudVoice() }
        actions[ID_READ_ALOUD_RATE] = { chooseReadAloudRate() }
        actions[ID_DOTS] = {
            prefs.setIconNotificationDotEnabled(!prefs.isIconNotificationDotEnabled()); refreshRows()
        }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_select),
            null,
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { focusedPosition().takeIf { it >= 0 }?.let { dispatch(it) } }
        refreshRows()
        focusFirst()
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        // Access is granted from a separate system screen, so re-check on return.
        refreshRows()
        requestRebindIfStale()
    }

    /**
     * The OS doesn't always redeliver onListenerConnected() after an app
     * update/reinstall, even though access was already granted - the
     * Settings.Secure string still lists us (isNotificationAccessGranted
     * reads true) but NotificationCountService.instance stays null forever,
     * silently keeping Notices and the Home badges empty. Detect that state
     * here and proactively ask the framework to rebind, exactly like
     * NotificationCountService.onListenerDisconnected() already does for a
     * mid-session drop.
     */
    private fun requestRebindIfStale() {
        if (!isNotificationAccessGranted() || NotificationCountService.instance != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, NotificationCountService::class.java))
            } catch (e: Exception) {
                // Best-effort; the framework rebinds on its own schedule anyway.
            }
        }
    }

    private fun refreshRows() {
        val accessTrailing = if (isNotificationAccessGranted()) {
            getString(R.string.settings_notif_access_granted)
        } else {
            getString(R.string.settings_notif_access_denied)
        }
        val callLogTrailing = if (readCallLogPermission.isGranted()) {
            getString(R.string.settings_notif_access_granted)
        } else {
            getString(R.string.settings_notif_access_denied)
        }
        val accessibilityTrailing = if (isAccessibilityFallbackGranted()) {
            getString(R.string.settings_notif_access_granted)
        } else {
            getString(R.string.settings_notif_access_denied)
        }
        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_notif_access)),
                Row(
                    id = ID_ACCESS,
                    title = getString(R.string.settings_notif_access),
                    subtitle = getString(R.string.settings_notif_access_sub),
                    trailing = accessTrailing,
                    chevron = true,
                ),
                Row(
                    id = ID_CALL_LOG_ACCESS,
                    title = getString(R.string.settings_calllog_access),
                    subtitle = getString(R.string.settings_calllog_access_sub),
                    trailing = callLogTrailing,
                    chevron = true,
                ),
                Row(
                    id = ID_ACCESSIBILITY_ACCESS,
                    title = getString(R.string.settings_notif_accessibility_access),
                    subtitle = getString(R.string.settings_notif_accessibility_access_sub),
                    trailing = accessibilityTrailing,
                    chevron = true,
                ),
                Row.section(getString(R.string.sec_notif_home)),
                Row(id = ID_CALLS, title = getString(R.string.settings_notif_calls), toggle = prefs.isCallBadgeEnabled()),
                Row(id = ID_MESSAGES, title = getString(R.string.settings_notif_messages), toggle = prefs.isMessageBadgeEnabled()),
                Row(id = ID_OTHER, title = getString(R.string.settings_notif_other), toggle = prefs.isOtherBadgeEnabled()),
                Row(
                    id = ID_HIDE_TEXT,
                    title = getString(R.string.settings_notif_hide_text),
                    subtitle = getString(R.string.settings_notif_hide_text_sub),
                    toggle = prefs.isNotificationTextHidden(),
                ),
                Row.section(getString(R.string.sec_read_aloud)),
                Row(
                    id = ID_READ_ALOUD,
                    title = getString(R.string.settings_read_aloud),
                    subtitle = getString(R.string.settings_read_aloud_sub),
                    trailing = getString(readAloudModeLabel(prefs.getReadAloudMode())),
                    chevron = true,
                ),
                Row(
                    id = ID_READ_ALOUD_VOICE,
                    title = getString(R.string.settings_read_aloud_voice),
                    trailing = prefs.getReadAloudVoice() ?: getString(R.string.settings_read_aloud_voice_default),
                    chevron = true,
                ),
                Row(
                    id = ID_READ_ALOUD_RATE,
                    title = getString(R.string.settings_read_aloud_rate),
                    trailing = getString(readAloudRateLabel(prefs.getReadAloudRate())),
                    chevron = true,
                ),
                Row.section(getString(R.string.sec_icons)),
                Row(id = ID_DOTS, title = getString(R.string.settings_notif_icon_dots), toggle = prefs.isIconNotificationDotEnabled()),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun readAloudModeLabel(mode: String): Int =
        READ_ALOUD_MODE_LABELS[READ_ALOUD_MODES.indexOf(mode).coerceAtLeast(0)]

    /** Labels the saved rate, treating anything off the three presets as Normal. */
    private fun readAloudRateLabel(rate: Float): Int =
        READ_ALOUD_RATE_LABELS[READ_ALOUD_RATES.indexOf(rate).takeIf { it >= 0 } ?: 1]

    private fun chooseReadAloudMode() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_read_aloud)
            .setItems(READ_ALOUD_MODE_LABELS.map { getString(it) }.toTypedArray()) { _, which ->
                prefs.setReadAloudMode(READ_ALOUD_MODES[which])
                refreshRows()
            }
            .show()
    }

    private fun chooseReadAloudVoice() {
        ReadAloudSpeaker.listVoices(this) { voices ->
            if (isFinishing) return@listVoices
            if (voices.isEmpty()) {
                Toast.makeText(this, R.string.settings_read_aloud_no_voices, Toast.LENGTH_LONG).show()
                return@listVoices
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_read_aloud_voice)
                .setItems(voices.map { voiceLabel(it) }.toTypedArray()) { _, which ->
                    ReadAloudSpeaker.setVoice(this, voices[which].name)
                    ReadAloudSpeaker.speak(this, getString(R.string.settings_read_aloud_voice_preview))
                    refreshRows()
                }
                .show()
        }
    }

    private fun voiceLabel(voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> R.string.voice_quality_very_high
            voice.quality >= Voice.QUALITY_HIGH -> R.string.voice_quality_high
            voice.quality >= Voice.QUALITY_NORMAL -> R.string.voice_quality_normal
            else -> R.string.voice_quality_low
        }
        return "${voice.name} (${getString(quality)})"
    }

    private fun chooseReadAloudRate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_read_aloud_rate)
            .setItems(READ_ALOUD_RATE_LABELS.map { getString(it) }.toTypedArray()) { _, which ->
                ReadAloudSpeaker.setSpeechRate(this, READ_ALOUD_RATES[which])
                ReadAloudSpeaker.speak(this, getString(R.string.settings_read_aloud_rate_preview))
                refreshRows()
            }
            .show()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    private fun isAccessibilityFallbackGranted(): Boolean {
        val target = ComponentName(this, NotificationAccessibilityService::class.java)
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun requestCallLogAccess() {
        readCallLogPermission.run(
            onDenied = {
                // shouldShowRequestPermissionRationale is false both before the
                // first ask and after a permanent denial; inside this callback
                // we've just been denied, so false here means permanent - send
                // the user to the app's permission settings page instead of a
                // dead end (same reasoning as CallLogActivity's own request).
                if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
                    openAppPermissionSettings()
                }
            },
            action = { refreshRows() },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ID_ACCESS = "access"
        private const val ID_CALL_LOG_ACCESS = "call_log_access"
        private const val ID_ACCESSIBILITY_ACCESS = "accessibility_access"
        private const val ID_CALLS = "calls"
        private const val ID_MESSAGES = "messages"
        private const val ID_OTHER = "other"
        private const val ID_HIDE_TEXT = "hide_text"
        private const val ID_DOTS = "dots"
        private const val ID_READ_ALOUD = "read_aloud"
        private const val ID_READ_ALOUD_VOICE = "read_aloud_voice"
        private const val ID_READ_ALOUD_RATE = "read_aloud_rate"

        private val READ_ALOUD_MODES = listOf(
            LauncherPrefs.READ_ALOUD_NEVER,
            LauncherPrefs.READ_ALOUD_ALWAYS,
            LauncherPrefs.READ_ALOUD_BLUETOOTH,
        )
        private val READ_ALOUD_MODE_LABELS = listOf(
            R.string.read_aloud_never,
            R.string.read_aloud_always,
            R.string.read_aloud_bluetooth,
        )
        private val READ_ALOUD_RATES = listOf(0.75f, 1.0f, 1.25f)
        private val READ_ALOUD_RATE_LABELS = listOf(
            R.string.read_aloud_rate_slower,
            R.string.read_aloud_rate_normal,
            R.string.read_aloud_rate_faster,
        )
    }
}
