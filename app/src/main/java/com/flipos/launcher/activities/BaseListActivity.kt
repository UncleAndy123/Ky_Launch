package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.SoftKeyBar
import com.flipos.launcher.ui.listItemAnimator

/**
 * Shared scaffolding for the vertical list screens (Options, Hide Apps,
 * Shortcuts, App Picker): a title bar, a focusable [RecyclerView] and the bottom
 * [SoftKeyBar]. Subclasses populate the adapter and wire up the soft keys.
 */
abstract class BaseListActivity : AppCompatActivity() {

    protected lateinit var titleView: TextView
    protected lateinit var listView: RecyclerView
    protected lateinit var softKeys: SoftKeyBar

    /** The accent color applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedAccentColor: LauncherPrefs.AccentColor? = null

    /** The theme mode applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedThemeMode: LauncherPrefs.ThemeMode? = null

    /**
     * True once [onResume] has triggered a [recreate] for an accent or theme
     * mode change. Subclasses overriding [onResume] should `return` early
     * when this is set so they don't do resume work (reloads, focus) on the
     * dying instance.
     */
    protected var isRecreatingForAccent = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = LauncherPrefs(this)
        // Must happen before super.onCreate(): unlike the accent-color
        // overlay below (which merges one attribute onto whatever theme is
        // already resolved), a real light/dark switch needs the
        // AppCompat.Light family itself active before AppCompatActivity's
        // own onCreate resolves it - so AlertDialog's own default chrome
        // (background, buttons), not just this app's own content, renders
        // light too.
        val themeMode = prefs.getThemeMode()
        appliedThemeMode = themeMode
        if (themeMode.themeRes != 0) setTheme(themeMode.themeRes)
        super.onCreate(savedInstanceState)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        if (!prefs.isAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        }
        setContentView(R.layout.activity_list)
        titleView = findViewById(R.id.title)
        listView = findViewById(R.id.list)
        softKeys = findViewById(R.id.soft_keys)
        listView.layoutManager = LinearLayoutManager(this)
        // Short insert/remove/move animations when enabled; no change cross-fade
        // (rows rebind often on refresh and it would flicker). Null = instant.
        listView.itemAnimator = if (prefs.isAnimationsEnabled()) listItemAnimator() else null
    }

    override fun onResume() {
        super.onResume()
        // The accent color or theme mode may have changed in Settings while
        // this activity was backgrounded; both only apply at onCreate, so
        // recreate to pick up either.
        val prefs = LauncherPrefs(this)
        if (prefs.getAccentColor() != appliedAccentColor || prefs.getThemeMode() != appliedThemeMode) {
            isRecreatingForAccent = true
            recreate()
        }
    }

    /**
     * Adapter position of the focused row, or [RecyclerView.NO_POSITION] (-1)
     * when nothing is focused. Callers must guard against -1 so a soft-key
     * action after focus loss doesn't fall back to row 0.
     */
    protected fun focusedPosition(): Int {
        val child = listView.focusedChild ?: return RecyclerView.NO_POSITION
        return listView.getChildAdapterPosition(child)
    }

    protected fun focusFirst() {
        listView.post {
            if (listView.focusedChild == null) {
                val lm = listView.layoutManager ?: return@post
                // Skip non-focusable rows (e.g. section headers) so focus lands on
                // the first real item rather than silently failing on a header.
                for (i in 0 until lm.itemCount) {
                    val view = lm.findViewByPosition(i)
                    if (view != null && view.isFocusable && view.requestFocus()) return@post
                }
                listView.requestFocus()
            }
        }
    }
}
