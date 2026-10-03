package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.data.AppInfo
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.NotificationCounts
import com.flipos.launcher.data.NotificationDotColor
import com.flipos.launcher.ui.AppGridAdapter
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.PageIndicatorView
import com.flipos.launcher.ui.Row
import com.flipos.launcher.ui.SoftKeyBar
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.launchAppByKey
import kotlin.math.ceil
import kotlin.math.min

/**
 * The "Menu": every non-hidden app, shown as either a 3x3 icon grid or a
 * single-column list (toggled in Settings).
 *
 * The grid pages nine apps at a time: D-pad navigates within a page, row by
 * row, and pressing down off the bottom row (or up off the top row) flips to
 * the next/previous page, tracked by a column of dots to the right. Number
 * keys 1-9 launch the matching position within the current page, echoing the
 * classic feature-phone menu. The list, by contrast, is one continuous,
 * ordinary scroll - no pages, no dots, no number-key shortcuts - since
 * chopping a list into same-sized chunks doesn't carry the same meaning a
 * grid page does and only made scrolling feel choppy.
 *
 * Center/OK opens the focused app. The Options soft key (or long-press) lets
 * the user hide it, change its icon, or uninstall it.
 */
class AppDrawerActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var grid: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var gridAdapter: AppGridAdapter
    private lateinit var listAdapter: ListRowAdapter
    private lateinit var softKeys: SoftKeyBar
    private lateinit var pageIndicator: PageIndicatorView

    private var allApps: List<AppInfo> = emptyList()
    private var currentPageItems: List<AppInfo> = emptyList()
    private var currentPage = 0
    private var listMode = false
    private var gridColumns = GRID_COLUMNS
    private var pageSize = PAGE_SIZE

    /** Component key of the app currently picked up by the Move gesture (see [enterMoveMode]), or null. */
    private var movingKey: String? = null

    /** The accent color applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedAccentColor: LauncherPrefs.AccentColor? = null

    private val loader = BackgroundLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        if (!prefs.isAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        }
        setContentView(R.layout.activity_app_drawer)

        grid = findViewById(R.id.apps_grid)
        titleView = findViewById(R.id.title)
        softKeys = findViewById(R.id.soft_keys)
        pageIndicator = findViewById(R.id.page_indicator)
        pageIndicator.animateChanges = prefs.isAnimationsEnabled()

        // The window shows the wallpaper through a translucent overlay (see
        // Theme.FlipLauncher.Drawer); these two would otherwise paint over it
        // with their own solid bar, breaking the edge-to-edge KaiOS look.
        titleView.setBackgroundColor(Color.TRANSPARENT)
        softKeys.setBackgroundColor(Color.TRANSPARENT)

        // RecyclerView defaults to focusable so it has somewhere to park focus
        // when it's empty. We always have items and manage focus ourselves, and
        // leaving this on means the view itself can get "rescued" into holding
        // focus for a frame whenever a page swap detaches the previously
        // focused child - visible as the whole row/grid highlighting.
        grid.isFocusable = false

        gridAdapter = AppGridAdapter(
            onClick = { launchAppByKey(it.key) },
            onLongClick = { showContextMenu(it) },
            // Labels are gone from the grid; mirror the focused app's name
            // where "All Apps" normally sits so it's still identifiable.
            onFocusChanged = { titleView.text = it.label },
            iconSizePercent = prefs.getIconSizePercent(),
            hasNotification = ::hasNotification,
            isMoving = { it.key == movingKey },
        )
        listAdapter = ListRowAdapter(
            onClick = { pos -> currentPageItems.getOrNull(pos)?.let { launchAppByKey(it.key) } },
            onLongClick = { pos -> currentPageItems.getOrNull(pos)?.let { showContextMenu(it) } },
            onFocusChanged = { pos -> currentPageItems.getOrNull(pos)?.let { titleView.text = it.label } },
        )
        grid.itemAnimator = null
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val oldPageSize = pageSize
            updatePageSize()
            if (oldPageSize != pageSize) refresh()
        }

        softKeys.setLabels(null, null, getString(R.string.softkey_options))
        softKeys.setCenterPlainLabel(getString(R.string.softkey_select).uppercase())
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { openFocused() }
        softKeys.setOnRightClick { optionsForFocused() }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getAccentColor() != appliedAccentColor) {
            recreate()
            return
        }
        // Icon size may have changed in Settings while backgrounded; push it to
        // the grid adapter and recompute page metrics before rebinding.
        gridAdapter.setIconSizePercent(prefs.getIconSizePercent())
        applyViewMode()
        updatePageSize()
        refresh()
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    /** Swap layout manager/adapter to match the current Launcher Settings choice. */
    private fun applyViewMode() {
        val wantList = prefs.isDrawerListViewEnabled()
        if (wantList == listMode && grid.adapter != null) return
        listMode = wantList
        // Page metrics differ between grid and list; start from the top so focus
        // and the dot indicator don't land on a now-nonexistent page.
        currentPage = 0
        updatePageSize()
        grid.layoutManager = if (listMode) LinearLayoutManager(this) else GridLayoutManager(this, gridColumns)
        grid.adapter = if (listMode) listAdapter else gridAdapter
    }

    private fun updatePageSize() {
        if (listMode) {
            pageSize = PAGE_SIZE
            return
        }
        val gridWidth = grid.width
        val gridHeight = grid.height
        if (gridWidth <= 0 || gridHeight <= 0) return

        gridColumns = GRID_COLUMNS
        val contentWidth = gridWidth - grid.paddingStart - grid.paddingEnd
        val contentHeight = gridHeight - grid.paddingTop - grid.paddingBottom
        val density = resources.displayMetrics.density
        val fitIconPx = AppGridAdapter.baseFitIconPx(contentWidth, contentHeight, gridColumns, density)
        val desiredIconPx = fitIconPx * prefs.getIconSizePercent() / 100
        val itemHeightPx = desiredIconPx + (AppGridAdapter.ITEM_OVERHEAD_DP * density).toInt()
        val rows = (contentHeight / itemHeightPx.coerceAtLeast(1)).coerceAtLeast(3)
        pageSize = gridColumns * rows
        // Below the floor of 3 rows, the icon-size clamp (not this page-size
        // math) is what actually keeps the grid from overflowing - it shrinks
        // icons to fit instead of forcing fewer, undersized rows.
        gridAdapter.setGridMetrics(contentWidth, contentHeight, gridColumns, rows)
    }

    private fun refresh() {
        loader.load(
            produce = { AppRepository.getVisibleApps(this, prefs) },
            consume = { apps ->
                if (isDestroyed) return@load
                allApps = apps
                if (listMode) {
                    bindList()
                } else {
                    val maxPage = (totalPages() - 1).coerceAtLeast(0)
                    bindPage(currentPage.coerceIn(0, maxPage))
                }
            },
        )
    }

    private fun hasNotification(app: AppInfo): Boolean =
        prefs.isIconNotificationDotEnabled() && NotificationCounts.packagesWithNotifications.contains(app.packageName)

    private fun totalPages(): Int =
        if (allApps.isEmpty()) 1 else ceil(allApps.size / pageSize.toDouble()).toInt()

    /**
     * Bind the full app list in one go - no pages, no dots, just a normal
     * scroll. [forceRebind] forces every visible row to rebind even when its
     * [AppInfo] content didn't change - needed during an in-progress Move,
     * where two rows swap position but not content, which the diff-based
     * [ListRowAdapter.submit] would otherwise treat as a no-op move and skip
     * re-binding (only [isMoving] actually changed, and that's carried in
     * each [Row] already so this only matters if that ever stops being true).
     */
    private fun bindList(focusPosition: Int? = null, forceRebind: Boolean = false) {
        currentPageItems = allApps
        listAdapter.submit(
            currentPageItems.map {
                Row(
                    title = it.label,
                    icon = it.icon,
                    keepWhiteTitle = true,
                    badgeColor = if (hasNotification(it)) NotificationDotColor.forIcon(it.key, it.icon) else null,
                    isMoving = it.key == movingKey,
                )
            },
        )
        if (forceRebind) listAdapter.notifyDataSetChanged()
        pageIndicator.visibility = View.GONE
        grid.post {
            if (currentPageItems.isEmpty()) return@post
            focusItemAt(focusPosition?.coerceIn(0, currentPageItems.size - 1) ?: 0)
        }
    }

    /**
     * Swap the grid's contents to [page] and re-sync the dot indicator.
     * [forceRebind] forces every visible icon to rebind even when its
     * [AppInfo] content didn't change - needed during an in-progress Move,
     * where [AppGridAdapter]'s [isMoving] flag lives outside [AppInfo] itself
     * (unlike [ListRowAdapter]'s [Row.isMoving]), so the diff can't see it
     * changed and would otherwise skip the rebind that shows/moves the
     * highlight.
     */
    private fun bindPage(page: Int, focusPosition: Int? = null, forceRebind: Boolean = false) {
        currentPage = page
        val start = page * pageSize
        val end = min(start + pageSize, allApps.size)
        currentPageItems = if (start < end) allApps.subList(start, end) else emptyList()
        gridAdapter.submit(currentPageItems)
        if (forceRebind) gridAdapter.notifyDataSetChanged()

        val pages = totalPages()
        pageIndicator.visibility = if (pages > 1) View.VISIBLE else View.GONE
        pageIndicator.setPageCount(pages)
        pageIndicator.setCurrentPage(page)

        grid.post {
            if (currentPageItems.isEmpty()) return@post
            val target = focusPosition?.coerceAtMost(currentPageItems.size - 1) ?: 0
            focusItemAt(target)
        }
    }

    /**
     * Focuses the row/icon at [target], waiting for it to attach first if
     * needed. A page swap can land on a row far enough down that the layout
     * pass hasn't created its view yet - findViewByPosition would silently
     * return null right after [bindPage], the requestFocus() would no-op, and
     * Android would "rescue" focus onto whatever view happens to be visible
     * (the top row) instead of leaving the request pending.
     */
    private fun focusItemAt(target: Int) {
        val lm = grid.layoutManager ?: return
        lm.findViewByPosition(target)?.let {
            it.requestFocus()
            return
        }
        grid.scrollToPosition(target)
        grid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (grid.getChildAdapterPosition(view) != target) return
                grid.removeOnChildAttachStateChangeListener(this)
                view.requestFocus()
            }
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    /** Flip to [page], keeping the focused column and landing on the matching row. */
    private fun goToPage(page: Int, landOnLastRow: Boolean) {
        if (page < 0 || page >= totalPages()) return
        val column = focusedPosition() % gridColumns
        bindPage(page, focusPosition = if (landOnLastRow) lastRowStartForPage(page) + column else column)
    }

    private fun lastRowStartForPage(page: Int): Int {
        val count = min(pageSize, allApps.size - page * pageSize).coerceAtLeast(1)
        return ((count - 1) / gridColumns) * gridColumns
    }

    /**
     * Moves focus up/down by [rowDelta] rows (sign = direction, magnitude grows
     * while a key is held) within the current grid page, landing on the same
     * column. Only flips to the next/previous page once the step would carry
     * focus past the bottom/top row, instead of every press.
     */
    private fun moveFocusByRow(rowDelta: Int) {
        val itemCount = currentPageItems.size
        if (itemCount == 0) return
        val position = focusedPosition()
        val row = position / gridColumns
        val column = position % gridColumns
        val lastRow = (itemCount - 1) / gridColumns
        val targetRow = row + rowDelta
        when {
            targetRow < 0 -> goToPage(currentPage - 1, landOnLastRow = true)
            targetRow > lastRow -> goToPage(currentPage + 1, landOnLastRow = false)
            else -> {
                val target = (targetRow * gridColumns + column).coerceAtMost(itemCount - 1)
                focusItemAt(target)
            }
        }
    }

    /**
     * Moves focus left/right by [delta] cells (sign = direction, magnitude grows
     * while a key is held) within the grid, treating the page as a single
     * sequence so that moving right off the end of a row lands on the next row
     * (and left off the start lands on the previous one). Moving past the page's
     * first/last cell flips to the adjacent page.
     */
    private fun moveFocusByColumn(delta: Int) {
        val itemCount = currentPageItems.size
        if (itemCount == 0) return
        val target = focusedPosition() + delta
        when {
            target < 0 -> {
                val prev = currentPage - 1
                if (prev < 0) return
                val prevCount = min(pageSize, allApps.size - prev * pageSize)
                bindPage(prev, focusPosition = prevCount - 1)
            }
            target >= itemCount -> {
                if (currentPage + 1 < totalPages()) bindPage(currentPage + 1, focusPosition = 0)
            }
            else -> focusItemAt(target)
        }
    }

    /** Moves focus up/down by [delta] rows (sign = direction, magnitude grows
     * while a key is held) in the (unpaged) list view, clamped to the ends. */
    private fun moveFocusLinear(delta: Int) {
        val itemCount = currentPageItems.size
        if (itemCount == 0) return
        val target = (focusedPosition() + delta).coerceIn(0, itemCount - 1)
        focusItemAt(target)
    }

    // --------------------------------------------------------------- Actions

    private fun focusedPosition(): Int {
        val child = grid.focusedChild ?: return 0
        val pos = grid.getChildAdapterPosition(child)
        return if (pos == RecyclerView.NO_POSITION) 0 else pos
    }

    private fun openFocused() {
        currentPageItems.getOrNull(focusedPosition())?.let { launchAppByKey(it.key) }
    }

    private fun optionsForFocused() {
        currentPageItems.getOrNull(focusedPosition())?.let { showContextMenu(it) }
    }

    private data class ContextItem(val label: String, val action: () -> Unit)

    private fun showContextMenu(app: AppInfo) {
        if (isFinishing || isDestroyed) return
        val items = mutableListOf(
            ContextItem(getString(R.string.ctx_open)) { launchAppByKey(app.key) },
            ContextItem(getString(R.string.ctx_move)) { enterMoveMode(app) },
            ContextItem(getString(R.string.ctx_hide)) { hideApp(app) },
            ContextItem(getString(R.string.ctx_change_icon)) { changeIcon(app) },
        )
        if (prefs.getIconOverride(app.key) != null) {
            items.add(ContextItem(getString(R.string.ctx_reset_icon)) { resetIcon(app) })
        }
        val wrapEnabled = prefs.isIconWrapEnabled(app.key)
        items.add(
            ContextItem(getString(if (wrapEnabled) R.string.ctx_disable_wrap else R.string.ctx_enable_wrap)) {
                toggleIconWrap(app, !wrapEnabled)
            },
        )
        items.add(ContextItem(getString(R.string.ctx_uninstall)) { uninstallApp(app) })
        items.add(ContextItem(getString(R.string.ctx_settings)) { startActivity(Intent(this, SettingsActivity::class.java)) })

        val titleView = layoutInflater.inflate(R.layout.dialog_app_context_title, null).apply {
            findViewById<TextView>(R.id.dialog_title_label).text = app.label
            findViewById<TextView>(R.id.dialog_title_package).text = app.packageName
        }
        AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() }
            .show()
    }

    private fun changeIcon(app: AppInfo) {
        startActivity(Intent(this, IconPickerActivity::class.java).putExtra(IconPickerActivity.EXTRA_APP_KEY, app.key))
    }

    private fun resetIcon(app: AppInfo) {
        prefs.clearIconOverride(app.key)
        Toast.makeText(this, R.string.icon_picker_reset, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun toggleIconWrap(app: AppInfo, enabled: Boolean) {
        prefs.setIconWrapEnabled(app.key, enabled)
        refresh()
    }

    private fun hideApp(app: AppInfo) {
        prefs.setHidden(app.key, true)
        Toast.makeText(this, getString(R.string.toast_app_hidden, app.label), Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun uninstallApp(app: AppInfo) {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_DELETE,
                    Uri.fromParts("package", app.packageName, null),
                ),
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_uninstall_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------- Move

    /**
     * Picks up [app] for the D-pad Move gesture: subsequent D-pad presses
     * swap it with a neighbor (see [moveMovingItem]) instead of moving focus,
     * until Center/OK commits the new position ([commitMove]) or Back/soft-
     * left cancels ([cancelMove]) - see [onMoveKeyDown].
     */
    private fun enterMoveMode(app: AppInfo) {
        movingKey = app.key
        val focus = focusedPosition()
        if (listMode) bindList(focusPosition = focus, forceRebind = true) else bindPage(currentPage, focusPosition = focus, forceRebind = true)
    }

    private fun exitMoveMode() {
        movingKey = null
    }

    /** Persists the in-memory order every swap this move made already built, then exits move mode. */
    private fun commitMove() {
        exitMoveMode()
        prefs.setAppOrder(allApps.map { it.key })
        val focus = focusedPosition()
        if (listMode) bindList(focusPosition = focus, forceRebind = true) else bindPage(currentPage, focusPosition = focus, forceRebind = true)
    }

    /** Nothing was persisted during the move, so a plain [refresh] (re-querying [AppRepository]) discards every in-memory swap. */
    private fun cancelMove() {
        exitMoveMode()
        refresh()
    }

    /**
     * Swaps the moving app's position with whichever neighbor D-pad
     * navigation would normally move focus to - live, so the moving icon's
     * slot follows the D-pad, crossing into an adjacent grid page (see
     * [rowMoveTargetIndex]/[columnMoveTargetIndex]) exactly like plain
     * navigation already does. List mode is unbounded within the full list,
     * which has no pages to cross.
     */
    private fun moveMovingItem(rowDelta: Int, colDelta: Int) {
        val key = movingKey ?: return
        if (listMode) {
            if (colDelta != 0) return
            val index = allApps.indexOfFirst { it.key == key }
            if (index < 0) return
            val target = (index + rowDelta).coerceIn(0, allApps.size - 1)
            if (target == index) return
            swapAllApps(index, target)
            bindList(focusPosition = target, forceRebind = true)
            return
        }
        val localIndex = currentPageItems.indexOfFirst { it.key == key }
        if (localIndex < 0) return
        val globalCurrent = currentPage * pageSize + localIndex
        val globalTarget = (if (rowDelta != 0) rowMoveTargetIndex(rowDelta) else columnMoveTargetIndex(colDelta))
            ?.takeIf { it != globalCurrent } ?: return
        swapAllApps(globalCurrent, globalTarget)
        bindPage(globalTarget / pageSize, focusPosition = globalTarget % pageSize, forceRebind = true)
    }

    /**
     * Global (allApps-indexed) swap destination for a Move row step - same
     * column on the previous page's last row when [rowDelta] carries the
     * moving item up past the current page's top row, same column on the
     * next page's first row when it carries past the bottom row, otherwise
     * just the target row on the current page. Mirrors [moveFocusByRow]'s
     * page-crossing behavior, but returns a swap destination rather than a
     * focus target. Null at the very first/last page (nothing to move into).
     */
    private fun rowMoveTargetIndex(rowDelta: Int): Int? {
        val localIndex = currentPageItems.indexOfFirst { it.key == movingKey }
        if (localIndex < 0) return null
        val row = localIndex / gridColumns
        val column = localIndex % gridColumns
        val lastRow = (currentPageItems.size - 1) / gridColumns
        val targetRow = row + rowDelta
        return when {
            targetRow < 0 -> {
                val prevPage = currentPage - 1
                if (prevPage < 0) return null
                val prevCount = min(pageSize, allApps.size - prevPage * pageSize)
                if (prevCount <= 0) return null
                prevPage * pageSize + (lastRowStartForPage(prevPage) + column).coerceAtMost(prevCount - 1)
            }
            targetRow > lastRow -> {
                val nextPage = currentPage + 1
                if (nextPage >= totalPages()) return null
                val nextStart = nextPage * pageSize
                val nextCount = min(pageSize, allApps.size - nextStart)
                if (nextCount <= 0) return null
                nextStart + column.coerceAtMost(nextCount - 1)
            }
            else -> currentPage * pageSize + (targetRow * gridColumns + column).coerceAtMost(currentPageItems.size - 1)
        }
    }

    /**
     * Global (allApps-indexed) swap destination for a Move column step - the
     * previous page's last item when [delta] carries the moving item left
     * off the current page's start, the next page's first item when it
     * carries right off the end, otherwise just the target cell on the
     * current page. Mirrors [moveFocusByColumn]'s page-crossing behavior,
     * but returns a swap destination rather than a focus target. Null at the
     * very first/last item overall.
     */
    private fun columnMoveTargetIndex(delta: Int): Int? {
        val localIndex = currentPageItems.indexOfFirst { it.key == movingKey }
        if (localIndex < 0) return null
        val target = localIndex + delta
        return when {
            target < 0 -> {
                val prevPage = currentPage - 1
                if (prevPage < 0) return null
                val prevCount = min(pageSize, allApps.size - prevPage * pageSize)
                if (prevCount <= 0) return null
                prevPage * pageSize + (prevCount - 1)
            }
            target >= currentPageItems.size -> {
                val nextPage = currentPage + 1
                if (nextPage >= totalPages()) return null
                val nextStart = nextPage * pageSize
                if (min(pageSize, allApps.size - nextStart) <= 0) return null
                nextStart
            }
            else -> currentPage * pageSize + target
        }
    }

    private fun swapAllApps(indexA: Int, indexB: Int) {
        val mutable = allApps.toMutableList()
        val tmp = mutable[indexA]
        mutable[indexA] = mutable[indexB]
        mutable[indexB] = tmp
        allApps = mutable
    }

    // ----------------------------------------------------------- Key handling

    /**
     * A focused, clickable item view's own default [View.onKeyDown] consumes
     * KEYCODE_DPAD_CENTER/ENTER itself (to drive its own performClick()) before
     * the event would ever reach [onKeyDown] below - unlike the D-pad
     * direction keys, which this view hierarchy leaves unconsumed and which
     * do reach [onKeyDown] normally. During an in-progress Move that would
     * launch the focused app instead of committing the move, so Center/Enter
     * is intercepted here first, exactly like [MainActivity] already does for
     * its own directional keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingKey != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            return onMoveKeyDown(event.keyCode, event)
        }
        return super.dispatchKeyEvent(event)
    }

    /** Intercepts D-pad/Center/Back for the in-progress Move instead of their normal navigation/open/finish behavior. */
    private fun onMoveKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> moveMovingItem(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveMovingItem(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveMovingItem(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveMovingItem(0, -1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> commitMove()
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_BACK -> cancelMove()
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (movingKey != null) return onMoveKeyDown(keyCode, event)
        // Guard auto-repeat only for keys that must act once per press (launching
        // an app, soft-key actions). Movement keys intentionally honor auto-repeat
        // so holding the D-pad rolls through the grid/list and picks up speed.
        if (event.repeatCount > 0 && isRepeatGuardedKey(keyCode)) return true
        val step = repeatStep(event)
        when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                // Only the grid has a fixed nine-per-page shape for this
                // feature-phone shortcut to map onto; the list just scrolls.
                if (!listMode) {
                    currentPageItems.getOrNull(keyCode - KeyEvent.KEYCODE_1)?.let { launchAppByKey(it.key) }
                }
                return true
            }
            // Handled explicitly (rather than left to view focus search) so a
            // page flip only happens once focus is already on the bottom/top row.
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (listMode) moveFocusLinear(step) else moveFocusByRow(step); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (listMode) moveFocusLinear(-step) else moveFocusByRow(-step); return true }
            // In the grid, left/right wrap across rows (and pages) instead of
            // stopping at a row edge; the list has no columns to move between.
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!listMode) { moveFocusByColumn(step); return true } }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (!listMode) { moveFocusByColumn(-step); return true } }
            KeyEvent.KEYCODE_SOFT_LEFT -> { finish(); return true }
            KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU -> { optionsForFocused(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isRepeatGuardedKey(keyCode: Int): Boolean = when (keyCode) {
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9,
        KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU -> true
        else -> false
    }

    /**
     * Grows the per-press movement step the longer a D-pad key is held, so the
     * grid/list rolls and accelerates instead of creeping one cell per tick.
     * The first few auto-repeats stay at one step for precise short holds, then
     * ramp up for long presses. [KeyEvent.repeatCount] is 0 on the initial press
     * and increments on each auto-repeat.
     */
    private fun repeatStep(event: KeyEvent): Int = when {
        event.repeatCount >= 16 -> 4
        event.repeatCount >= 9 -> 3
        event.repeatCount >= 4 -> 2
        else -> 1
    }

    companion object {
        private const val GRID_COLUMNS = 3
        private const val PAGE_SIZE = 9
    }
}
