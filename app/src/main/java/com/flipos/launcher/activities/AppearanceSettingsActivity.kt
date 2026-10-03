package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.flipos.launcher.data.IconPackRepository
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/** Appearance settings: wallpaper, accent color, and icon look. */
class AppearanceSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_appearance)

        actions[ID_WALLPAPER] = { startActivity(Intent(this, WallpaperPickerActivity::class.java)) }
        actions[ID_ACCENT] = { chooseAccentColor() }
        actions[ID_THEME] = { chooseTheme() }
        actions[ID_SHAPE] = { chooseIconShape() }
        actions[ID_PACK] = { startActivity(Intent(this, IconPackActivity::class.java)) }
        actions[ID_SIZE] = { chooseIconSize() }
        actions[ID_BACKGROUND] = {
            prefs.setLegacyIconBackgroundEnabled(!prefs.isLegacyIconBackgroundEnabled())
            refreshRows()
        }
        actions[ID_ANIMATIONS] = {
            prefs.setAnimationsEnabled(!prefs.isAnimationsEnabled())
            refreshRows()
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
        refreshRows()
    }

    private fun refreshRows() {
        val activePack = prefs.getActiveIconPack()
        val packLabel = activePack?.let { pkg ->
            IconPackRepository.getInstalledIconPacks(this).find { it.packageName == pkg }?.label
        } ?: getString(R.string.icon_pack_default)

        val iconPercent = prefs.getIconSizePercent()
        val iconSizeLabel = when {
            iconPercent <= 80 -> getString(R.string.settings_icon_small)
            iconPercent >= 120 -> getString(R.string.settings_icon_large)
            else -> getString(R.string.settings_icon_medium)
        }

        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_wallpaper_color)),
                Row(
                    id = ID_WALLPAPER,
                    title = getString(R.string.opt_set_wallpaper),
                    subtitle = getString(R.string.settings_wallpaper_sub),
                    chevron = true,
                ),
                Row(
                    id = ID_ACCENT,
                    title = getString(R.string.settings_accent_color),
                    trailing = getString(prefs.getAccentColor().labelRes),
                    chevron = true,
                ),
                Row(
                    id = ID_THEME,
                    title = getString(R.string.settings_theme),
                    trailing = getString(prefs.getThemeMode().labelRes),
                    chevron = true,
                ),
                Row.section(getString(R.string.sec_icons)),
                Row(
                    id = ID_SHAPE,
                    title = getString(R.string.settings_icon_shape),
                    trailing = getString(prefs.getIconShape().labelRes),
                    chevron = true,
                ),
                Row(
                    id = ID_PACK,
                    title = getString(R.string.settings_icon_pack),
                    trailing = packLabel,
                    chevron = true,
                ),
                Row(
                    id = ID_SIZE,
                    title = getString(R.string.settings_icon_size),
                    trailing = getString(R.string.settings_icon_current, iconSizeLabel, iconPercent),
                    chevron = true,
                ),
                Row(
                    id = ID_BACKGROUND,
                    title = getString(R.string.settings_icon_background),
                    toggle = prefs.isLegacyIconBackgroundEnabled(),
                ),
                Row.section(getString(R.string.sec_motion)),
                Row(
                    id = ID_ANIMATIONS,
                    title = getString(R.string.settings_animations),
                    subtitle = getString(R.string.settings_animations_sub),
                    toggle = prefs.isAnimationsEnabled(),
                ),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun chooseIconSize() {
        val current = prefs.getIconSizePercent()
        val labels = arrayOf(
            getString(R.string.settings_icon_small),
            getString(R.string.settings_icon_medium),
            getString(R.string.settings_icon_large),
        )
        val values = listOf(80, 100, 120)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_icon_size)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.setIconSizePercent(values[which])
                Toast.makeText(this, getString(R.string.settings_icon_size_set, labels[which]), Toast.LENGTH_SHORT).show()
                refreshRows()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseAccentColor() {
        val options = LauncherPrefs.AccentColor.entries.toTypedArray()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val checked = options.indexOf(prefs.getAccentColor()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_accent_color)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.setAccentColor(options[which])
                Toast.makeText(this, getString(R.string.settings_accent_color_set, labels[which]), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun chooseTheme() {
        val options = LauncherPrefs.ThemeMode.entries.toTypedArray()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val checked = options.indexOf(prefs.getThemeMode()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.setThemeMode(options[which])
                Toast.makeText(this, getString(R.string.settings_theme_set, labels[which]), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun chooseIconShape() {
        val options = LauncherPrefs.IconShape.entries.toTypedArray()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val checked = options.indexOf(prefs.getIconShape()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_icon_shape)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.setIconShape(options[which])
                Toast.makeText(this, getString(R.string.settings_icon_shape_set, labels[which]), Toast.LENGTH_SHORT).show()
                refreshRows()
                dialog.dismiss()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ID_WALLPAPER = "wallpaper"
        private const val ID_ACCENT = "accent"
        private const val ID_THEME = "theme"
        private const val ID_SHAPE = "shape"
        private const val ID_PACK = "pack"
        private const val ID_SIZE = "size"
        private const val ID_BACKGROUND = "background"
        private const val ID_ANIMATIONS = "animations"
    }
}
