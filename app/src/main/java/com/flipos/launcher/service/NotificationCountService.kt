package com.flipos.launcher.service

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationCategorizer
import com.flipos.launcher.data.NotificationCounts
import com.flipos.launcher.data.NotificationKind
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.util.ReadAloud

/**
 * Tracks active notifications for two things Home/Notices need: per-category
 * counts for the Home summary badges, and the full list backing the custom
 * Notices screen (we skip the system shade entirely - see NoticesActivity -
 * since it isn't designed for a feature-phone-sized display).
 *
 * Requires the user to grant Notification Access in system settings; until
 * they do, both stay empty.
 */
class NotificationCountService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        ReadAloud.attach(this)
        recompute()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // The listener can drop out from under us (access revoked, low-memory
        // kill, framework restart). Clear the cached counts/list so Home badges
        // and Notices don't keep showing data we can no longer refresh, then ask
        // the framework to rebind so we recover automatically once possible.
        NotificationCounts.update(0, 0, 0, emptySet())
        NotificationStore.update(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, NotificationCountService::class.java))
            } catch (e: Exception) {
                // Best-effort; the framework rebinds on its own schedule anyway.
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = recompute()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = recompute()

    private fun recompute() {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            return
        }

        var calls = 0
        var messages = 0
        var other = 0
        val packages = HashSet<String>()
        val notices = ArrayList<NoticeItem>()
        for (sbn in active) {
            // Group summaries and our own posted-by-system rows aren't real, user-facing
            // notifications, so they're skipped to avoid inflating the "other" count.
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val kind = NotificationCategorizer.kindOf(sbn.notification.category)
            when (kind) {
                NotificationKind.CALL -> calls++
                NotificationKind.MESSAGE -> messages++
                NotificationKind.OTHER -> other++
            }
            packages.add(sbn.packageName)
            notices.add(toNoticeItem(sbn, kind))
        }
        NotificationCounts.update(calls, messages, other, packages)
        NotificationStore.update(notices.sortedByDescending { it.postTime })
    }

    private fun toNoticeItem(sbn: StatusBarNotification, kind: NotificationKind): NoticeItem {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        return NoticeItem(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title.ifEmpty { appLabel(sbn.packageName) },
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            postTime = sbn.postTime,
            icon = loadSmallIcon(sbn),
            kind = kind,
        )
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun loadSmallIcon(sbn: StatusBarNotification): Drawable? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sbn.notification.smallIcon?.loadDrawable(this)
        } else {
            packageManager.getApplicationIcon(sbn.packageName)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Opens [key]'s tap action, mirroring what tapping it in the system shade
     * would do. Returns `false` when the notification has no tap action (so the
     * caller doesn't wrongly treat it as opened and dismiss it).
     */
    fun openNotice(key: String): Boolean {
        val sbn = activeOrNull(key) ?: return false
        val contentIntent = sbn.notification.contentIntent ?: return false
        return try {
            contentIntent.send()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Whether [key] is user-dismissable (ongoing/foreground notices aren't). */
    fun isClearable(key: String): Boolean = activeOrNull(key)?.isClearable == true

    private fun activeOrNull(key: String): StatusBarNotification? = try {
        activeNotifications.firstOrNull { it.key == key }
    } catch (e: SecurityException) {
        null
    }

    fun dismiss(key: String) = cancelNotification(key)

    fun dismissAll() = cancelAllNotifications()

    companion object {
        /**
         * The system only ever runs one instance of a given listener service, so
         * a static reference is a simple way for [com.flipos.launcher.activities.NoticesActivity]
         * to reach it for dismiss/open actions without a bound-service round trip.
         */
        var instance: NotificationCountService? = null
            private set
    }
}
