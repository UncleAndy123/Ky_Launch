package com.flipos.launcher.data

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** One active notification, as shown in the custom Notices screen. */
data class NoticeItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val icon: Drawable?,
    val kind: NotificationKind,
)

/**
 * Live list of active notifications, fed by
 * [com.flipos.launcher.service.NotificationCountService], backing the custom
 * Notices screen (we don't use the system shade - see NoticesActivity).
 *
 * [update] runs on the listener's binder thread while the UI reads on the main
 * thread, so state is [Volatile], the listener set is copy-on-write, and
 * callbacks are marshalled to the main thread.
 */
object NotificationStore {

    @Volatile
    var items: List<NoticeItem> = emptyList()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(items: List<NoticeItem>) {
        this.items = items
        notifyListeners()
    }

    /**
     * Drops any pending items for [packageName] - called by
     * [com.flipos.launcher.service.NotificationAccessibilityService] as it
     * tracks which app is currently in the foreground, both when leaving the
     * previous one and entering the next.
     */
    fun removeItemsForPackage(packageName: String) {
        val filtered = items.filterNot { it.packageName == packageName }
        if (filtered.size != items.size) update(filtered)
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it() }
        } else {
            mainHandler.post { listeners.forEach { it() } }
        }
    }
}
