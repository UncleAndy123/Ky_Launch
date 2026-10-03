package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.service.NotificationCountService
import com.flipos.launcher.ui.NoticeRowAdapter

/**
 * A KaiOS-style "Notices" list standing in for the system notification shade,
 * which isn't laid out for a screen this small. Backed by
 * [NotificationCountService] via [NotificationStore].
 */
class NoticesActivity : BaseListActivity() {

    private lateinit var adapter: NoticeRowAdapter
    private lateinit var emptyView: TextView

    private val storeListener: () -> Unit = { runOnUiThread { if (!isDestroyed) refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Some Kyocera builds report notification access as "granted" but the
        // listener never actually binds; on those, fall back to this
        // hardware's own notification screen (built for keypad/flip devices,
        // unlike the generic touch-driven system shade) instead of showing an
        // empty/non-functional list.
        if (NotificationCountService.instance == null && tryOpenKyoceraNotificationScreen()) {
            finish()
            return
        }

        adapter = NoticeRowAdapter(onClick = { openNotice(it) })
        listView.adapter = adapter
        emptyView = findViewById(R.id.empty_view)

        softKeys.setLabels(
            getString(R.string.softkey_dismiss),
            getString(R.string.softkey_select),
            getString(R.string.softkey_dismiss_all),
        )
        softKeys.setOnLeftClick { dismissFocused() }
        softKeys.setOnCenterClick { openFocused() }
        softKeys.setOnRightClick { dismissAll() }
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        NotificationStore.addListener(storeListener)
        if (!isNotificationAccessGranted()) {
            Toast.makeText(this, R.string.notices_access_required, Toast.LENGTH_LONG).show()
        } else {
            requestRebindIfStale()
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NotificationStore.removeListener(storeListener)
    }

    private fun refresh() {
        val items = NotificationStore.items
        titleView.text = getString(R.string.title_notices, items.size)
        adapter.submit(items)
        if (items.isEmpty()) {
            emptyView.text = getString(
                if (isNotificationAccessGranted()) R.string.notices_empty else R.string.notices_access_required,
            )
            emptyView.visibility = TextView.VISIBLE
        } else {
            emptyView.visibility = TextView.GONE
            focusFirst()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        // Match on the flattened ComponentName's package rather than a raw
        // substring, so an unrelated app whose name merely contains ours can't
        // read as "granted".
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    /**
     * The OS doesn't always redeliver onListenerConnected() after an app
     * update/reinstall, even though access was already granted -
     * isNotificationAccessGranted() still reads true (it only checks the
     * Settings.Secure string) but NotificationCountService.instance stays
     * null forever, silently keeping this list empty. Detect that state here
     * and proactively ask the framework to rebind, exactly like
     * NotificationCountService.onListenerDisconnected() already does for a
     * mid-session drop; [storeListener] picks up the result once it lands.
     */
    private fun requestRebindIfStale() {
        if (NotificationCountService.instance != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, NotificationCountService::class.java))
            } catch (e: Exception) {
                // Best-effort; the framework rebinds on its own schedule anyway.
            }
        }
    }

    private fun openFocused() {
        // With no access there are no notices to open; make Select a shortcut to
        // the system screen where the user grants it.
        if (!isNotificationAccessGranted()) {
            openNotificationAccessSettings()
            return
        }
        adapter.itemAt(focusedPosition())?.let { openNotice(it) }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns whether the Kyocera notification screen was actually launched. */
    private fun tryOpenKyoceraNotificationScreen(): Boolean = try {
        startActivity(
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.android.systemui", "com.android.systemui.kc.notification.NotificationActivity"),
            ),
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun openNotice(item: NoticeItem) {
        val service = NotificationCountService.instance
        val opened = service?.openNotice(item.key) == true
        if (!opened) {
            Toast.makeText(this, R.string.notices_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Only auto-dismiss notices the user could swipe away themselves; leave
        // ongoing/foreground ones (music, calls, downloads) in place.
        if (service?.isClearable(item.key) == true) service.dismiss(item.key)
        finish()
    }

    private fun dismissFocused() {
        val item = adapter.itemAt(focusedPosition()) ?: return
        NotificationCountService.instance?.dismiss(item.key)
    }

    private fun dismissAll() {
        if (NotificationStore.items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.notices_dismiss_all_title)
            .setPositiveButton(R.string.notices_dismiss_all_confirm) { _, _ ->
                NotificationCountService.instance?.dismissAll()
            }
            .setNegativeButton(R.string.notices_dismiss_all_cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            dismissFocused()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
