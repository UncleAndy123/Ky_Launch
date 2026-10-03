package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.IconShapeRenderer
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.NotificationKind
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.CategoryApps
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.ReadAloud
import com.flipos.launcher.util.accentColorAlpha
import com.flipos.launcher.util.launchAppByKey
import com.flipos.launcher.util.placeCall
import com.flipos.launcher.util.systemSpeedDial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The KaiOS-style home screen:
 *  - a large clock + date, and
 *  - soft keys along the bottom (Contacts on the left, the default messaging
 *    app on the right, unless overridden in Home Shortcuts settings), with a
 *    D-pad/Camera shortcut icon pod nested between the two labels and the
 *    App-Drawer/OK button sitting in its center.
 *
 * Typing a digit - or `*` / `#` - anywhere on Home opens the phone dialer
 * prefilled with it. The center button opens All Apps; long-pressing it (via
 * touch) opens Settings.
 *
 * Every physical key (digits 0/2-9, MENU, BACK, the soft keys, D-pad
 * Up/Down/Left/Right, Camera) also carries a long-press action and a
 * 5-second-hold "assign" menu - see the Key handling section below. Digits
 * are the exception: their long-press (speed dial / voicemail) fires the
 * instant it's detected, and assignment for them is Settings-only. The
 * phone's outer buttons (SOS, outer END/Speaker, PTT) are left entirely to
 * the system's own key-assignment settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var appMenuButton: ImageView
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var weekday: TextView
    private lateinit var dateLine: TextView
    private lateinit var notifBanner: View
    private lateinit var notifBannerIcon: ImageView
    private lateinit var notifBannerApp: TextView
    private lateinit var notifBannerText: TextView
    private lateinit var dpadIconUp: ImageView
    private lateinit var dpadIconDown: ImageView
    private lateinit var dpadIconLeft: ImageView
    private lateinit var dpadIconRight: ImageView

    private val ampmFmt = SimpleDateFormat("a", Locale.getDefault())
    private val time12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val time24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val weekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    /** Physical key awaiting an app from [assignAppLauncher] (0 = none). */
    private var pendingAssignAppKey = 0

    /** The accent color applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedAccentColor: LauncherPrefs.AccentColor? = null

    // ---------------------------------------------------- Key hold tracking
    //
    // Every assignable physical key (digits 0/2-9, MENU, BACK, the soft keys)
    // shares one small state machine: a short tap performs the key's default
    // action, holding past the framework's long-press threshold (~500ms)
    // performs its bound action instead (dial a speed-dial number / launch a
    // bound app), and holding for a full 5 seconds opens that key's assign
    // menu. See onKeyDown/onKeyUp below.

    private val assignHandler = Handler(Looper.getMainLooper())

    /** Auto-hides a low-priority ("Other") banner item after a short delay - see [updateNotifBanner]. */
    private val notifBannerHandler = Handler(Looper.getMainLooper())
    private val hideNotifBannerRunnable = Runnable { notifBanner.visibility = View.GONE }

    /** Scheduled 5-second assign runnables, keyed by keyCode, so a release can cancel them. */
    private val assignRunnables = HashMap<Int, Runnable>()

    /** Scheduled digit-long-press (speed dial / voicemail) runnables, keyed by keyCode. */
    private val digitHoldRunnables = HashMap<Int, Runnable>()

    /** Scheduled digit short-tap dial runnables (debounced against a same-key re-press), keyed by keyCode. */
    private val digitTapRunnables = HashMap<Int, Runnable>()

    /**
     * [KeyEvent.getDownTime] of the digit press session already resolved
     * (hold fired, or a tap already scheduled), keyed by keyCode. Some
     * hardware delivers a spurious extra ACTION_UP mid-hold (with a repeat
     * ACTION_DOWN in between, same downTime) for what is really one
     * continuous press - this lets a second UP for a downTime we've already
     * handled be ignored instead of re-triggering the tap action.
     */
    private val digitHandledDownTime = HashMap<Int, Long>()

    /** Keycodes whose 5-second assign menu already fired for the current press. */
    private val assignFired = HashSet<Int>()

    /** Keycodes that have crossed the framework long-press threshold for the current press. */
    private val longPressFired = HashSet<Int>()

    private val callPermission = PermissionGate(this, Manifest.permission.CALL_PHONE)
    private val contactsPermission = PermissionGate(this, Manifest.permission.READ_CONTACTS)

    /** App picker for MENU/BACK/soft-key/D-pad/Camera assignment, writing back based on [pendingAssignAppKey]. */
    private val assignAppLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val key = result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)
        val target = pendingAssignAppKey
        pendingAssignAppKey = 0
        if (result.resultCode == RESULT_OK && key != null) {
            when (target) {
                KeyEvent.KEYCODE_MENU -> prefs.setMenuKeyApp(key)
                KeyEvent.KEYCODE_BACK -> prefs.setBackLongPressApp(key)
                KeyEvent.KEYCODE_SOFT_LEFT -> prefs.setLeftKeyApp(key)
                KeyEvent.KEYCODE_SOFT_RIGHT -> prefs.setRightKeyApp(key)
                KeyEvent.KEYCODE_DPAD_UP -> prefs.setDpadUpApp(key)
                KeyEvent.KEYCODE_DPAD_DOWN -> prefs.setDpadDownApp(key)
                KeyEvent.KEYCODE_DPAD_LEFT -> prefs.setDpadLeftApp(key)
                KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.setDpadRightApp(key)
                KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> prefs.setCameraKeyApp(key)
            }
            AppRepository.resolveComponent(this, key)?.label?.let {
                Toast.makeText(this, getString(R.string.key_assigned_toast, it), Toast.LENGTH_SHORT).show()
            }
            refreshLeftKeyLabel()
            refreshRightKeyLabel()
            refreshDirectionalPod()
        }
    }

    private val loader = BackgroundLoader()

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    /** Keeps cached icons and the D-pad pod fresh when apps are installed/removed/updated. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // On a genuine uninstall (not an update's remove-then-add), drop any
            // per-app icon overrides for the departed package so they don't leak.
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false).not()
            ) {
                intent.data?.schemeSpecificPart?.let { prefs.pruneIconOverridesForPackage(it) }
            }
            AppRepository.invalidateIconCaches()
            refreshDirectionalPod()
        }
    }

    private val notifListener: () -> Unit = {
        runOnUiThread { updateNotifBanner() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        ReadAloud.attach(this)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        if (!prefs.isAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        }
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        ampm = findViewById(R.id.ampm)
        weekday = findViewById(R.id.weekday)
        dateLine = findViewById(R.id.date_line)
        notifBanner = findViewById<View>(R.id.notif_banner).apply {
            backgroundTintList = ColorStateList.valueOf(accentColorAlpha(0xE6))
        }
        notifBannerIcon = findViewById(R.id.notif_banner_icon)
        notifBannerApp = findViewById(R.id.notif_banner_app)
        notifBannerText = findViewById(R.id.notif_banner_text)
        dpadIconUp = findViewById(R.id.dpad_icon_up)
        dpadIconDown = findViewById(R.id.dpad_icon_down)
        dpadIconLeft = findViewById(R.id.dpad_icon_left)
        dpadIconRight = findViewById(R.id.dpad_icon_right)

        findViewById<TextView>(R.id.softkey_left).setOnClickListener { openLeftKeyApp() }
        findViewById<TextView>(R.id.softkey_right).setOnClickListener { openRightKeyApp() }
        appMenuButton = findViewById<ImageView>(R.id.softkey_center).apply {
            // bg_rail_focus is white so it can be tinted to the user's accent.
            backgroundTintList = ColorStateList.valueOf(accentColorAlpha(0x4D))
            setOnClickListener { openAppDrawer() }
            setOnLongClickListener { openOptions(); true }
        }

        // Back opens the app drawer; long-pressing it launches the configured app instead
        // (see onKeyDown/onKeyUp, which suppress this callback when a long-press fires).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openAppDrawer()
        })

        updateClock()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateClock()
        refreshLeftKeyLabel()
        refreshRightKeyLabel()
        refreshDirectionalPod()
        focusAppMenu()
        NotificationStore.addListener(notifListener)
        updateNotifBanner()
        maybePromptDefaultLauncher()
        // The accent color may have changed in Settings while Home was backgrounded;
        // theme overlays only apply at onCreate, so recreate to pick it up. Done last
        // (after registering the receiver/listener above) so onPause's matching
        // unregister calls below still have something to unregister.
        if (prefs.getAccentColor() != appliedAccentColor) recreate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        NotificationStore.removeListener(notifListener)
        notifBannerHandler.removeCallbacksAndMessages(null)
        // A key hold that's interrupted mid-press (screen off, app switch) may
        // never deliver a matching key-up; drop any scheduled assign timers so
        // they don't fire into the background.
        assignHandler.removeCallbacksAndMessages(null)
        assignRunnables.clear()
        digitHoldRunnables.clear()
        digitTapRunnables.clear()
        // digitHandledDownTime deliberately survives a pause: placing a
        // speed-dial call (from a hold firing) backgrounds this Activity via
        // the system InCallActivity, triggering this very onPause() while
        // more events for the same physical press are still arriving -
        // clearing it here reopens the double-dial window it exists to
        // close. Each new press already clears its own entry in onKeyDown.
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- Data / clock

    private fun updateClock() {
        val now = Date()
        if (android.text.format.DateFormat.is24HourFormat(this)) {
            ampm.visibility = TextView.GONE
            clock.text = time24.format(now)
        } else {
            ampm.visibility = TextView.VISIBLE
            ampm.text = ampmFmt.format(now)
            clock.text = time12.format(now)
        }
        weekday.text = weekdayFmt.format(now)
        dateLine.text = dateFmt.format(now)
    }

    // --------------------------------------------------------------- Actions

    /** Open the phone dialer, prefilled with the pressed digit (or * / #). */
    private fun startDial(digit: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digit, null)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDrawer() = startActivity(Intent(this, AppDrawerActivity::class.java))

    /** Highlight the "open app menu" button by default whenever Home is shown. */
    private fun focusAppMenu() = appMenuButton.post { appMenuButton.requestFocus() }

    private fun openLeftKeyApp() {
        val key = prefs.getLeftKeyApp()
        if (key != null) {
            launchAppByKey(key)
            return
        }
        val fallback = CategoryApps.contactsKey(this)
        if (fallback != null) {
            launchAppByKey(fallback)
        } else {
            Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeKeysSettingsActivity::class.java))
        }
    }

    private fun openOptions() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openRightKeyApp() {
        val key = prefs.getRightKeyApp()
        if (key != null) {
            launchAppByKey(key)
            return
        }
        val fallback = CategoryApps.smsKey(this)
        if (fallback != null) {
            launchAppByKey(fallback)
        } else {
            Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeKeysSettingsActivity::class.java))
        }
    }

    /**
     * Shows the single most recent active notification (across the enabled
     * categories) as a big, hard-to-miss bar. A low-priority ("Other")
     * notification auto-hides itself after [OTHER_NOTIF_AUTO_HIDE_MS] - calls
     * and messages persist until something else replaces or clears them.
     */
    private fun updateNotifBanner() {
        notifBannerHandler.removeCallbacks(hideNotifBannerRunnable)
        val item = NotificationStore.items.firstOrNull { prefs.isShownOnHome(it.kind) }
        if (item == null) {
            notifBanner.visibility = View.GONE
            return
        }
        val (iconRes, cdRes) = when (item.kind) {
            NotificationKind.CALL -> R.drawable.ic_call to R.string.cd_notif_calls
            NotificationKind.MESSAGE -> R.drawable.ic_message to R.string.cd_notif_messages
            NotificationKind.OTHER -> R.drawable.ic_notification to R.string.cd_notif_other
        }
        val hideText = prefs.isNotificationTextHidden()
        val appName = if (hideText) appLabel(item.packageName) else item.title
        notifBanner.visibility = View.VISIBLE
        notifBannerIcon.setImageResource(iconRes)
        notifBannerApp.text = appName
        if (!hideText && item.text.isNotEmpty()) {
            notifBannerText.text = item.text
            notifBannerText.visibility = View.VISIBLE
        } else {
            notifBannerText.visibility = View.GONE
        }
        notifBanner.contentDescription = if (notifBannerText.visibility == View.VISIBLE) {
            "$appName: ${item.text}"
        } else {
            "$appName, ${getString(cdRes)}"
        }
        if (item.kind == NotificationKind.OTHER) {
            notifBannerHandler.postDelayed(hideNotifBannerRunnable, OTHER_NOTIF_AUTO_HIDE_MS)
        }
    }

    /**
     * Resolves [packageName]'s own installed label - deliberately not
     * NoticeItem.title, which is often a sender's name rather than the
     * app's, so the "hide message text" privacy toggle's promise to keep
     * only the app name actually holds.
     */
    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun refreshRightKeyLabel() {
        val key = prefs.getRightKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_messages)
        findViewById<TextView>(R.id.softkey_right).text = label
        bindKeyIcon(findViewById(R.id.softkey_right_icon), key)
    }

    private fun refreshLeftKeyLabel() {
        val key = prefs.getLeftKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_contacts)
        findViewById<TextView>(R.id.softkey_left).text = label
        bindKeyIcon(findViewById(R.id.softkey_left_icon), key)
    }

    /**
     * The KaiOS "Recent Calls" action for the Send/Call key. Tries this
     * hardware's own call log screen first (confirmed via logcat -
     * `com.android.dialer/.app.calllog.CallLogActivityKc`, the same Kyocera
     * "Kc" pattern as the notification screen), falling back to our own
     * [CallLogActivity] if that component isn't present (any other device).
     */
    private fun openCallLog() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName("com.android.dialer", "com.android.dialer.app.calllog.CallLogActivityKc"),
                ),
            )
        } catch (e: Exception) {
            startActivity(Intent(this, CallLogActivity::class.java))
        }
    }

    // ----------------------------------------------------------- Key handling
    //
    // Digits 1-9/0 fire their long-press action (dial a speed-dial number,
    // dial voicemail) the instant the framework's long-press threshold
    // crosses, like a real feature phone - see the digit branches below.
    // Digit assignment is handled entirely by the phone's own Speed Dial
    // settings (see dialSpeedDial), not this app; there's no in-place hold
    // for them.
    //
    // Every other assignable key (MENU, BACK, the soft keys, D-pad, Camera)
    // keeps the original model: swallowed on key-down, resolved on key-up -
    // launching anything on key-down leaves the matching key-up to be
    // delivered to whatever gets focused as a result, which on some devices
    // re-enters the same input. Key-down starts long-press tracking and the
    // 5-second assign timer; key-up decides between a tap, a long-press
    // action, or (if the timer already fired) nothing further.
    //
    // KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT are exactly the keys Android's default
    // focus-search machinery intercepts at the currently-focused View, before
    // an unconsumed event would ever reach onKeyDown/onKeyUp below - so they
    // (and Camera, for uniformity) are captured a level higher, in
    // dispatchKeyEvent, before the view hierarchy gets a look at them.
    //
    // The phone's outer buttons (see SYSTEM_KEY_SCAN_CODES) belong to the
    // system's own key-assignment settings, not this launcher: they're
    // reported unhandled so the system's binding (or default) applies, and
    // never reach onKeyDown, where they'd trip the unrecognized-key toast.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.scanCode in SYSTEM_KEY_SCAN_CODES) return false
        if (event.keyCode in DIRECTIONAL_KEYS) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                else -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                // TEMP diagnostic logging for the digit-double-dial hardware investigation.
                Log.d(
                    TAG,
                    "DOWN key=$keyCode repeat=${event.repeatCount} down=${event.downTime} time=${event.eventTime} scan=${event.scanCode}",
                )
                if (event.repeatCount == 0) {
                    cancelDigitTap(keyCode)
                    longPressFired.remove(keyCode)
                    digitHandledDownTime.remove(keyCode)
                    scheduleDigitHold(keyCode)
                }
                return true
            }
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND -> return true
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                trackLongPress(keyCode, event)
                return true
            }
            KeyEvent.KEYCODE_CALL -> { openCallLog(); return true }
            KeyEvent.KEYCODE_BACK -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                trackLongPress(keyCode, event)
            }
            else -> {
                // Diagnostic aid for identifying vendor-specific physical buttons
                // (e.g. on Kyocera-style hardware) that don't map to a keycode
                // this app already recognizes above.
                if (event.repeatCount == 0 && keyCode !in SILENT_UNKNOWN_KEYS) {
                    Toast.makeText(
                        this,
                        getString(R.string.unrecognized_key_toast, keyCode, event.scanCode),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                // TEMP diagnostic logging for the digit-double-dial hardware investigation.
                Log.d(
                    TAG,
                    "UP key=$keyCode repeat=${event.repeatCount} down=${event.downTime} time=${event.eventTime} scan=${event.scanCode}",
                )
                cancelDigitHold(keyCode)
                // The long-press action (if any) already fired from the scheduled runnable.
                if (longPressFired.remove(keyCode)) {
                    digitHandledDownTime[keyCode] = event.downTime
                    return true
                }
                // Some hardware delivers a spurious extra ACTION_UP mid-hold (see
                // digitHandledDownTime's doc) - a second UP sharing a downTime we've
                // already resolved is that spurious event, not a real new release.
                if (digitHandledDownTime[keyCode] == event.downTime) return true
                digitHandledDownTime[keyCode] = event.downTime
                scheduleDigitTap(keyCode)
                return true
            }
            KeyEvent.KEYCODE_STAR -> { startDial("*"); return true }
            KeyEvent.KEYCODE_POUND -> { startDial("#"); return true }
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                openLeftKeyApp()
                return true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                openRightKeyApp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                launchDirectionalKeyApp(keyCode)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                if (longPressFired.remove(keyCode)) launchMenuKeyApp() else openAppDrawer()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                if (longPressFired.remove(keyCode)) {
                    launchBackLongPressApp()
                    return true
                }
                // Short tap: fall through to super, whose default BACK handling
                // fires onBackPressedDispatcher (the OnBackPressedCallback above
                // opens the app drawer), exactly as before.
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Starts long-press tracking on the initial down and clears any stale hold state from a prior, interrupted press. */
    private fun beginPressTracking(keyCode: Int, event: KeyEvent) {
        if (event.repeatCount != 0) return
        event.startTracking()
        assignFired.remove(keyCode)
        longPressFired.remove(keyCode)
    }

    /** Records that [keyCode] has crossed the framework long-press threshold, unless its 5-second assign already fired. */
    private fun trackLongPress(keyCode: Int, event: KeyEvent) {
        if (event.isLongPress && keyCode !in assignFired) longPressFired.add(keyCode)
    }

    /** Schedules [keyCode]'s assign menu to open after a 5-second hold; cancelled by [cancelAssign] on release. */
    private fun scheduleAssign(keyCode: Int) {
        cancelAssign(keyCode)
        val runnable = Runnable {
            assignFired.add(keyCode)
            openAssignMenu(keyCode)
        }
        assignRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, ASSIGN_HOLD_MS)
    }

    private fun cancelAssign(keyCode: Int) {
        assignRunnables.remove(keyCode)?.let { assignHandler.removeCallbacks(it) }
    }

    /**
     * Schedules [keyCode]'s speed-dial/voicemail action to fire after a
     * long-press hold; cancelled by [cancelDigitHold] on release. Timed
     * ourselves (rather than trusting [KeyEvent.isLongPress]) since that
     * flag's delivery has proven unreliable on some hardware, firing both
     * the short-tap dial and the long-press action for the same press.
     */
    private fun scheduleDigitHold(keyCode: Int) {
        cancelDigitHold(keyCode)
        val runnable = Runnable {
            Log.d(TAG, "HOLD FIRED key=$keyCode") // TEMP diagnostic logging.
            longPressFired.add(keyCode)
            if (keyCode == KeyEvent.KEYCODE_1) callVoicemail() else dialSpeedDial(keyCode - KeyEvent.KEYCODE_0)
        }
        digitHoldRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, DIGIT_HOLD_MS)
    }

    private fun cancelDigitHold(keyCode: Int) {
        digitHoldRunnables.remove(keyCode)?.let {
            Log.d(TAG, "HOLD CANCELLED key=$keyCode") // TEMP diagnostic logging.
            assignHandler.removeCallbacks(it)
        }
    }

    /**
     * Schedules [keyCode]'s short-tap dial to fire after a brief debounce
     * window, cancelled by [cancelDigitTap] if a new press for the same key
     * arrives first - this hardware appears to deliver one long hold as two
     * separate press/release cycles, so an immediate release-fires-dial
     * would double-dial (tap action + the real long-press action both firing).
     */
    private fun scheduleDigitTap(keyCode: Int) {
        cancelDigitTap(keyCode)
        val runnable = Runnable {
            Log.d(TAG, "TAP FIRED key=$keyCode") // TEMP diagnostic logging.
            startDial((keyCode - KeyEvent.KEYCODE_0).toString())
        }
        digitTapRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, DIGIT_TAP_DEBOUNCE_MS)
    }

    private fun cancelDigitTap(keyCode: Int) {
        digitTapRunnables.remove(keyCode)?.let {
            Log.d(TAG, "TAP CANCELLED key=$keyCode") // TEMP diagnostic logging.
            assignHandler.removeCallbacks(it)
        }
    }

    /** Opens the app picker to assign [keyCode] (MENU/BACK/soft-key/D-pad/Camera - digits are handled by the phone's own Speed Dial settings, see [dialSpeedDial]). */
    private fun openAssignMenu(keyCode: Int) {
        pendingAssignAppKey = keyCode
        assignAppLauncher.launch(Intent(this, AppPickerActivity::class.java))
    }

    /** Dials [digit]'s speed-dial number from the phone's own dialer data, or - if unset - opens the phone's own Speed Dial settings. */
    private fun dialSpeedDial(digit: Int) {
        contactsPermission.run(onDenied = { openSystemSpeedDial() }) {
            val entry = systemSpeedDial(this, digit)
            if (entry == null) {
                Toast.makeText(this, getString(R.string.speed_dial_unset_shortcut_toast, digit), Toast.LENGTH_SHORT).show()
                openSystemSpeedDial()
            } else {
                placeCall(callPermission, entry.number)
            }
        }
    }

    /**
     * Opens the phone's own Speed Dial settings screen, mirroring
     * [openCallLog]'s exact component-targeting/fallback pattern for this
     * same Kyocera/AOSP dialer.
     */
    private fun openSystemSpeedDial() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName("com.android.dialer", "com.android.dialer.app.speeddial.SpeedDialActivity"),
                ),
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches [keyCode]'s bound app immediately, or - if unset - falls back to
     * the default camera app for Camera, or toasts and jumps to Home
     * Shortcuts settings for every other key.
     */
    private fun launchDirectionalKeyApp(keyCode: Int) {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> prefs.getDpadUpApp()
            KeyEvent.KEYCODE_DPAD_DOWN -> prefs.getDpadDownApp()
            KeyEvent.KEYCODE_DPAD_LEFT -> prefs.getDpadLeftApp()
            KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.getDpadRightApp()
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> prefs.getCameraKeyApp()
            else -> null
        }
        if (key != null) {
            launchAppByKey(key)
            return
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == LauncherPrefs.KEYCODE_CAMERA_ALT || keyCode == LauncherPrefs.KEYCODE_CAMERA_ALT2) {
            openDefaultCamera()
            return
        }
        Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, HomeKeysSettingsActivity::class.java))
    }

    private fun openDefaultCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** Refreshes the D-pad shortcut pod's four icons; the pod itself always stays visible (it also houses the OK button). */
    private fun refreshDirectionalPod() {
        bindKeyIcon(dpadIconUp, prefs.getDpadUpApp())
        bindKeyIcon(dpadIconDown, prefs.getDpadDownApp())
        bindKeyIcon(dpadIconLeft, prefs.getDpadLeftApp())
        bindKeyIcon(dpadIconRight, prefs.getDpadRightApp())
    }

    /** Binds [key]'s squircle-masked icon into [view] and shows it, or hides [view] when [key] is null. Returns whether it was bound. */
    private fun bindKeyIcon(view: ImageView, key: String?): Boolean {
        val icon = key?.let { AppRepository.resolveRawIcon(this, it) }
        if (icon == null) {
            view.visibility = View.GONE
            return false
        }
        view.setImageDrawable(
            IconShapeRenderer.render(
                context = this,
                source = icon,
                shape = LauncherPrefs.IconShape.SQUIRCLE,
                wrapEnabled = true,
                legacyBackgroundEnabled = prefs.isLegacyIconBackgroundEnabled(),
            ),
        )
        view.visibility = View.VISIBLE
        return true
    }

    private fun callVoicemail() {
        callPermission.run(onDenied = {
            Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
        }) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("voicemail:")))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMenuKeyApp() {
        val key = prefs.getMenuKeyApp()
        if (key == null) {
            Toast.makeText(this, R.string.menu_key_unset_toast, Toast.LENGTH_SHORT).show()
        } else {
            launchAppByKey(key)
        }
    }

    private fun launchBackLongPressApp() {
        val key = prefs.getBackLongPressApp()
        if (key == null) {
            Toast.makeText(this, R.string.back_longpress_unset_toast, Toast.LENGTH_SHORT).show()
        } else {
            launchAppByKey(key)
        }
    }

    // ------------------------------------------------------ Default launcher

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun maybePromptDefaultLauncher() {
        // Gently hint, but never yank the user away — they opt in deliberately via
        // Options → "Set as Default Launcher".
        if (defaultPromptShown || isDefaultLauncher()) return
        defaultPromptShown = true
        Toast.makeText(this, R.string.choose_home_app, Toast.LENGTH_LONG).show()
    }

    companion object {
        // Shown at most once per process so we don't nag on every resume.
        private var defaultPromptShown = false

        /** TEMP: diagnostic logging tag for the digit-double-dial hardware investigation. */
        private const val TAG = "FlipDigitKey"

        /** How long an assignable key must be held to open its assign menu. */
        private const val ASSIGN_HOLD_MS = 5000L

        /** How long a low-priority ("Other") notification banner stays up before auto-hiding itself. */
        private const val OTHER_NOTIF_AUTO_HIDE_MS = 6000L

        /**
         * How long a digit must be held before its long-press action (speed
         * dial / voicemail) fires - mirrors the framework's own long-press
         * threshold, just timed by us (see [scheduleDigitHold]).
         */
        private val DIGIT_HOLD_MS = ViewConfiguration.getLongPressTimeout().toLong()

        /**
         * Grace window after a digit's release before its short-tap dial
         * actually fires; long enough to bridge the gap between this
         * hardware's two synthetic press/release cycles for one long hold
         * (see [scheduleDigitTap]), short enough to be imperceptible for a
         * genuine single tap.
         */
        private const val DIGIT_TAP_DEBOUNCE_MS = 200L

        /** Captured in [dispatchKeyEvent], before default focus-search can consume them. */
        private val DIRECTIONAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA,
            LauncherPrefs.KEYCODE_CAMERA_ALT,
            LauncherPrefs.KEYCODE_CAMERA_ALT2,
        )

        /**
         * The phone's outer buttons, identified by raw scan code since they
         * have no reliable KeyEvent.KEYCODE_* of their own: SOS (763), outer
         * END (764 on the E4810, 172 on the E4610), outer Speaker (765/213)
         * and PTT (766/231). Left to the system's key-assignment settings -
         * see [dispatchKeyEvent].
         */
        private val SYSTEM_KEY_SCAN_CODES = setOf(763, 764, 765, 766, 172, 213, 231)

        /** Keys that should never trigger the unrecognized-key diagnostic toast. */
        private val SILENT_UNKNOWN_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
    }
}
