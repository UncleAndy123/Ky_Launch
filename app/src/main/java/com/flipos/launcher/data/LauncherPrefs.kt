package com.flipos.launcher.data

import android.content.Context
import com.flipos.launcher.R

/**
 * Persists the launcher's user customization: hidden app keys, per-physical-key
 * app bindings (soft keys, MENU, BACK, D-pad, Camera), speed
 * dial numbers, and appearance/notification preferences.
 */
class LauncherPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Hidden

    fun getHiddenKeys(): MutableSet<String> =
        // Copy: the Set returned by getStringSet must not be mutated in place.
        HashSet(prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())

    fun isHidden(key: String): Boolean = getHiddenKeys().contains(key)

    fun setHidden(key: String, hidden: Boolean) {
        val set = getHiddenKeys()
        if (hidden) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    // ------------------------------------------------------- Back long-press

    /** App key launched on long-pressing Back, or null if unconfigured. */
    fun getBackLongPressApp(): String? = prefs.getString(KEY_BACK_LONGPRESS_APP, null)

    fun setBackLongPressApp(key: String?) {
        prefs.edit().putString(KEY_BACK_LONGPRESS_APP, key).apply()
    }

    // -------------------------------------------------------- Menu long-press

    /** App key launched on long-pressing MENU, or null if unconfigured. */
    fun getMenuKeyApp(): String? = prefs.getString(KEY_MENU_KEY_APP, null)

    fun setMenuKeyApp(key: String?) {
        prefs.edit().putString(KEY_MENU_KEY_APP, key).apply()
    }

    // --------------------------------------------- D-pad / Camera shortcuts

    /** App key launched by pressing D-pad Up on Home, or null if unconfigured. */
    fun getDpadUpApp(): String? = prefs.getString(KEY_DPAD_UP_APP, null)
    fun setDpadUpApp(key: String?) = prefs.edit().putString(KEY_DPAD_UP_APP, key).apply()

    /** App key launched by pressing D-pad Down on Home, or null if unconfigured. */
    fun getDpadDownApp(): String? = prefs.getString(KEY_DPAD_DOWN_APP, null)
    fun setDpadDownApp(key: String?) = prefs.edit().putString(KEY_DPAD_DOWN_APP, key).apply()

    /** App key launched by pressing D-pad Left on Home, or null if unconfigured. */
    fun getDpadLeftApp(): String? = prefs.getString(KEY_DPAD_LEFT_APP, null)
    fun setDpadLeftApp(key: String?) = prefs.edit().putString(KEY_DPAD_LEFT_APP, key).apply()

    /** App key launched by pressing D-pad Right on Home, or null if unconfigured. */
    fun getDpadRightApp(): String? = prefs.getString(KEY_DPAD_RIGHT_APP, null)
    fun setDpadRightApp(key: String?) = prefs.edit().putString(KEY_DPAD_RIGHT_APP, key).apply()

    /** App key launched by pressing the Camera button on Home, or null if unconfigured. */
    fun getCameraKeyApp(): String? = prefs.getString(KEY_CAMERA_KEY_APP, null)
    fun setCameraKeyApp(key: String?) = prefs.edit().putString(KEY_CAMERA_KEY_APP, key).apply()

    // ---------------------------------------------------------- Icon size

    /** App drawer icon size as a percentage of the size that exactly fills a
     * 3x3 grid with no scrolling on the current screen - not an absolute dp
     * value, so "Large" means the same relative thing on a tiny feature-phone
     * screen and a large tablet screen. Default is 100 (exactly fills 3x3). */
    fun getIconSizePercent(): Int = prefs.getInt(KEY_ICON_SIZE_PERCENT, DEFAULT_ICON_SIZE_PERCENT)

    fun setIconSizePercent(percent: Int) {
        val clamped = percent.coerceIn(MIN_ICON_SIZE_PERCENT, MAX_ICON_SIZE_PERCENT)
        prefs.edit().putInt(KEY_ICON_SIZE_PERCENT, clamped).apply()
    }

    // ----------------------------------------------------- Home right key

    /** App key launched by the right soft key on Home, or null for Contacts. */
    fun getRightKeyApp(): String? = prefs.getString(KEY_RIGHT_KEY_APP, null)

    fun setRightKeyApp(key: String?) {
        prefs.edit().putString(KEY_RIGHT_KEY_APP, key).apply()
    }

    // ------------------------------------------------------ Home left key

    /** App key launched by the left soft key on Home, or null for Notices. */
    fun getLeftKeyApp(): String? = prefs.getString(KEY_LEFT_KEY_APP, null)

    fun setLeftKeyApp(key: String?) {
        prefs.edit().putString(KEY_LEFT_KEY_APP, key).apply()
    }

    // ----------------------------------------------------------- App order

    /**
     * The user's custom app-grid ordering, as a list of component keys - apps
     * not in this list simply fall alphabetically after the ones that are
     * (see [AppRepository.getAllApps]). Empty until the app grid's one-time
     * seeding runs (or the user reorders manually), never afterward.
     */
    fun getAppOrder(): List<String> {
        val raw = prefs.getString(KEY_APP_ORDER, null) ?: return emptyList()
        return raw.split(APP_ORDER_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun setAppOrder(order: List<String>) {
        prefs.edit().putString(KEY_APP_ORDER, order.joinToString(APP_ORDER_SEPARATOR)).apply()
    }

    /** Whether the one-time app-order seeding ([AppRepository]) has already run. */
    fun isAppOrderSeeded(): Boolean = prefs.getBoolean(KEY_APP_ORDER_SEEDED, false)

    fun setAppOrderSeeded() {
        prefs.edit().putBoolean(KEY_APP_ORDER_SEEDED, true).apply()
    }

    /**
     * Whether the one-time correction that swaps our own Settings hub for the
     * real system Settings app in an already-seeded order ([AppRepository])
     * has already run.
     */
    fun isSettingsSeedFixed(): Boolean = prefs.getBoolean(KEY_SETTINGS_SEED_FIXED, false)

    fun setSettingsSeedFixed() {
        prefs.edit().putBoolean(KEY_SETTINGS_SEED_FIXED, true).apply()
    }

    /**
     * Whether the one-time pass that applies our own colorful built-in
     * icons ([BuiltInIcons]) to a handful of common apps ([AppRepository])
     * has already run.
     */
    fun isBuiltInIconsApplied(): Boolean = prefs.getBoolean(KEY_BUILT_IN_ICONS_APPLIED, false)

    fun setBuiltInIconsApplied() {
        prefs.edit().putBoolean(KEY_BUILT_IN_ICONS_APPLIED, true).apply()
    }

    /**
     * Whether the one-time pass that hides every app not on a fixed
     * whitelist ([AppRepository]) has already run.
     */
    fun isUnlistedAppsHidden(): Boolean = prefs.getBoolean(KEY_UNLISTED_APPS_HIDDEN, false)

    fun setUnlistedAppsHidden() {
        prefs.edit().putBoolean(KEY_UNLISTED_APPS_HIDDEN, true).apply()
    }

    // ----------------------------------------------------- App drawer layout

    /** Whether the app drawer shows a single-column list instead of an icon grid. */
    fun isDrawerListViewEnabled(): Boolean = prefs.getBoolean(KEY_DRAWER_LIST_VIEW, true)

    fun setDrawerListViewEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DRAWER_LIST_VIEW, enabled).apply()

    // --------------------------------------------------- Notification badges

    fun isCallBadgeEnabled(): Boolean = prefs.getBoolean(KEY_BADGE_CALLS, true)
    fun setCallBadgeEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BADGE_CALLS, enabled).apply()

    fun isMessageBadgeEnabled(): Boolean = prefs.getBoolean(KEY_BADGE_MESSAGES, true)
    fun setMessageBadgeEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BADGE_MESSAGES, enabled).apply()

    fun isOtherBadgeEnabled(): Boolean = prefs.getBoolean(KEY_BADGE_OTHER, false)
    fun setOtherBadgeEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BADGE_OTHER, enabled).apply()

    /** Whether app icons in the drawer/Home show a small notification dot. */
    fun isIconNotificationDotEnabled(): Boolean = prefs.getBoolean(KEY_BADGE_ICON_DOT, true)
    fun setIconNotificationDotEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BADGE_ICON_DOT, enabled).apply()

    /** Whether the Home notification banner hides message text, keeping only the app name. */
    fun isNotificationTextHidden(): Boolean = prefs.getBoolean(KEY_NOTIF_TEXT_HIDDEN, false)
    fun setNotificationTextHidden(hidden: Boolean) = prefs.edit().putBoolean(KEY_NOTIF_TEXT_HIDDEN, hidden).apply()

    /** Whether notifications of [kind] appear in the Home banner - shared by the banner and [com.flipos.launcher.util.ReadAloud]. */
    fun isShownOnHome(kind: NotificationKind): Boolean = when (kind) {
        NotificationKind.CALL -> isCallBadgeEnabled()
        NotificationKind.MESSAGE -> isMessageBadgeEnabled()
        NotificationKind.OTHER -> isOtherBadgeEnabled()
    }

    // ------------------------------------------------------------- Read aloud

    /** When the Home banner's message is spoken aloud: one of [READ_ALOUD_NEVER]/[READ_ALOUD_ALWAYS]/[READ_ALOUD_BLUETOOTH]. */
    fun getReadAloudMode(): String = prefs.getString(KEY_READ_ALOUD_MODE, READ_ALOUD_NEVER) ?: READ_ALOUD_NEVER
    fun setReadAloudMode(mode: String) = prefs.edit().putString(KEY_READ_ALOUD_MODE, mode).apply()

    /** The TTS engine voice name to read with, or null for the engine's default. */
    fun getReadAloudVoice(): String? = prefs.getString(KEY_READ_ALOUD_VOICE, null)
    fun setReadAloudVoice(name: String?) = prefs.edit().putString(KEY_READ_ALOUD_VOICE, name).apply()

    fun getReadAloudRate(): Float = prefs.getFloat(KEY_READ_ALOUD_RATE, 1.0f)
    fun setReadAloudRate(rate: Float) = prefs.edit().putFloat(KEY_READ_ALOUD_RATE, rate).apply()

    // ------------------------------------------------------------- Icon packs

    /** Icon pack package applied launcher-wide, or null for default icons. */
    fun getActiveIconPack(): String? = prefs.getString(KEY_ACTIVE_ICON_PACK, null)

    fun setActiveIconPack(packageName: String?) {
        prefs.edit().putString(KEY_ACTIVE_ICON_PACK, packageName).apply()
        AppRepository.invalidateIconCaches()
    }

    /** Per-app icon override as (icon pack package, drawable name), or null if unset. */
    fun getIconOverride(appKey: String): Pair<String, String>? {
        val raw = prefs.getString(iconOverrideKey(appKey), null) ?: return null
        val parts = raw.split(ICON_OVERRIDE_SEPARATOR, limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    fun setIconOverride(appKey: String, packageName: String, drawableName: String) {
        prefs.edit()
            .putString(iconOverrideKey(appKey), "$packageName$ICON_OVERRIDE_SEPARATOR$drawableName")
            .apply()
        AppRepository.invalidateIconCaches()
    }

    fun clearIconOverride(appKey: String) {
        prefs.edit().remove(iconOverrideKey(appKey)).apply()
        AppRepository.invalidateIconCaches()
    }

    /**
     * Removes any `icon_override_*` entries whose component belongs to
     * [packageName], so overrides don't accumulate for uninstalled apps. Called
     * when a package is removed.
     */
    fun pruneIconOverridesForPackage(packageName: String) {
        // Override keys are "icon_override_<pkg>/<class>"; match the package prefix.
        val prefix = "$KEY_ICON_OVERRIDE_PREFIX$packageName/"
        val stale = prefs.all.keys.filter { it.startsWith(prefix) }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        AppRepository.invalidateIconCaches()
    }

    private fun iconOverrideKey(appKey: String) = "$KEY_ICON_OVERRIDE_PREFIX$appKey"

    // -------------------------------------------------------- Accent color

    /** The user's chosen accent color, or [AccentColor.DEFAULT] (the original cyan) if unset. */
    fun getAccentColor(): AccentColor {
        val stored = prefs.getString(KEY_ACCENT_COLOR, null) ?: return AccentColor.DEFAULT
        return AccentColor.entries.find { it.key == stored } ?: AccentColor.DEFAULT
    }

    fun setAccentColor(color: AccentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, color.key).apply()
    }

    // --------------------------------------------------------------- Theme

    /** The user's chosen theme mode, or [ThemeMode.DARK] (the original KaiOS look) if unset. */
    fun getThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.DARK
        return ThemeMode.entries.find { it.key == stored } ?: ThemeMode.DARK
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
    }

    // --------------------------------------------------------- Icon wrapping

    /** The shape every wrapped icon is masked into, launcher-wide. */
    fun getIconShape(): IconShape {
        val stored = prefs.getString(KEY_ICON_SHAPE, null) ?: return IconShape.CIRCLE
        return IconShape.entries.find { it.key == stored } ?: IconShape.CIRCLE
    }

    fun setIconShape(shape: IconShape) {
        prefs.edit().putString(KEY_ICON_SHAPE, shape.key).apply()
        AppRepository.invalidateIconCaches()
    }

    /** Whether non-adaptive icons get a pale color-matched background, or sit on a transparent one. */
    fun isLegacyIconBackgroundEnabled(): Boolean = prefs.getBoolean(KEY_LEGACY_ICON_BG, false)

    fun setLegacyIconBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEGACY_ICON_BG, enabled).apply()
        AppRepository.invalidateIconCaches()
    }

    // ------------------------------------------------------------- Animations

    /**
     * Whether UI animations (fast activity fades, list item animations, page
     * indicator crossfades) are enabled. On by default; users on the slowest
     * hardware can turn it off for snappier, animation-free navigation.
     */
    fun isAnimationsEnabled(): Boolean = prefs.getBoolean(KEY_ANIMATIONS, true)

    fun setAnimationsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ANIMATIONS, enabled).apply()

    /** Per-app opt-out: whether [appKey]'s icon gets shape-masked at all. Defaults to on. */
    fun isIconWrapEnabled(appKey: String): Boolean = !getWrapDisabledKeys().contains(appKey)

    fun setIconWrapEnabled(appKey: String, enabled: Boolean) {
        val set = getWrapDisabledKeys()
        if (enabled) set.remove(appKey) else set.add(appKey)
        prefs.edit().putStringSet(KEY_WRAP_DISABLED, set).apply()
        AppRepository.invalidateIconCaches()
    }

    private fun getWrapDisabledKeys(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_WRAP_DISABLED, emptySet()) ?: emptySet())

    /** The mask shape applied to every wrapped icon. */
    enum class IconShape(val key: String, val labelRes: Int) {
        SQUIRCLE("squircle", R.string.icon_shape_squircle),
        SQUARE("square", R.string.icon_shape_square),
        CIRCLE("circle", R.string.icon_shape_circle),
        ROUNDED_SQUARE("rounded_square", R.string.icon_shape_rounded_square),
        /** No masking at all — every icon shows exactly as its app (or adaptive layers) draws it. */
        NONE("none", R.string.icon_shape_none),
    }

    /** A rainbow of accent color presets, plus the launcher's original cyan as the default. */
    enum class AccentColor(val key: String, val labelRes: Int, val themeOverlayRes: Int) {
        DEFAULT("default", R.string.accent_color_default, 0),
        RED("red", R.string.accent_color_red, R.style.ThemeOverlay_FlipLauncher_Accent_Red),
        ORANGE("orange", R.string.accent_color_orange, R.style.ThemeOverlay_FlipLauncher_Accent_Orange),
        YELLOW("yellow", R.string.accent_color_yellow, R.style.ThemeOverlay_FlipLauncher_Accent_Yellow),
        GREEN("green", R.string.accent_color_green, R.style.ThemeOverlay_FlipLauncher_Accent_Green),
        BLUE("blue", R.string.accent_color_blue, R.style.ThemeOverlay_FlipLauncher_Accent_Blue),
        INDIGO("indigo", R.string.accent_color_indigo, R.style.ThemeOverlay_FlipLauncher_Accent_Indigo),
        VIOLET("violet", R.string.accent_color_violet, R.style.ThemeOverlay_FlipLauncher_Accent_Violet),
    }

    /**
     * Light vs dark base theme for the "flat" list/settings screens (see
     * [com.flipos.launcher.activities.BaseListActivity]) - Home and the App
     * Drawer are unaffected, always dark, since their wallpaper scrim is
     * about legibility over an arbitrary photo, not a light/dark choice.
     * [themeRes] is a full theme (via `Activity.setTheme()`), not a runtime
     * overlay like [AccentColor.themeOverlayRes] - a real AlertDialog needs
     * the AppCompat.Light family itself to render its own chrome light too.
     */
    enum class ThemeMode(val key: String, val labelRes: Int, val themeRes: Int) {
        DARK("dark", R.string.theme_mode_dark, 0),
        LIGHT("light", R.string.theme_mode_light, R.style.Theme_FlipLauncher_Light),
    }

    companion object {
        /**
         * This device's actual Camera button reports this keyCode instead of the
         * standard [android.view.KeyEvent.KEYCODE_CAMERA] (27) - treated as the
         * same logical key everywhere Camera is handled.
         */
        const val KEYCODE_CAMERA_ALT = 133

        /**
         * The E4610's Camera button reports this keyCode instead - a second,
         * device-specific alternate alongside [KEYCODE_CAMERA_ALT].
         */
        const val KEYCODE_CAMERA_ALT2 = 288

        const val READ_ALOUD_NEVER = "never"
        const val READ_ALOUD_ALWAYS = "always"
        const val READ_ALOUD_BLUETOOTH = "bluetooth"

        /** Default icon size: exactly fills a 3x3 grid with no scrolling. */
        const val DEFAULT_ICON_SIZE_PERCENT = 100

        /** Bounds for [setIconSizePercent], guarding against out-of-range values. */
        const val MIN_ICON_SIZE_PERCENT = 50
        const val MAX_ICON_SIZE_PERCENT = 200

        private const val PREFS_NAME = "flip_launcher_prefs"
        private const val KEY_HIDDEN = "hidden_apps"
        private const val KEY_BACK_LONGPRESS_APP = "back_longpress_app"
        private const val KEY_MENU_KEY_APP = "menu_key_app"
        private const val KEY_DPAD_UP_APP = "dpad_up_app"
        private const val KEY_DPAD_DOWN_APP = "dpad_down_app"
        private const val KEY_DPAD_LEFT_APP = "dpad_left_app"
        private const val KEY_DPAD_RIGHT_APP = "dpad_right_app"
        private const val KEY_CAMERA_KEY_APP = "camera_key_app"
        private const val KEY_ICON_SIZE_PERCENT = "icon_size_percent"
        private const val KEY_RIGHT_KEY_APP = "right_key_app"
        private const val KEY_LEFT_KEY_APP = "left_key_app"
        private const val KEY_BADGE_CALLS = "badge_calls"
        private const val KEY_BADGE_MESSAGES = "badge_messages"
        private const val KEY_BADGE_OTHER = "badge_other"
        private const val KEY_BADGE_ICON_DOT = "badge_icon_dot"
        private const val KEY_NOTIF_TEXT_HIDDEN = "notif_text_hidden"
        private const val KEY_READ_ALOUD_MODE = "read_aloud_mode"
        private const val KEY_READ_ALOUD_VOICE = "read_aloud_voice"
        private const val KEY_READ_ALOUD_RATE = "read_aloud_rate"
        private const val KEY_ACTIVE_ICON_PACK = "active_icon_pack"
        private const val KEY_DRAWER_LIST_VIEW = "drawer_list_view"
        private const val KEY_ICON_OVERRIDE_PREFIX = "icon_override_"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val ICON_OVERRIDE_SEPARATOR = "::"
        private const val KEY_ICON_SHAPE = "icon_shape"
        private const val KEY_LEGACY_ICON_BG = "legacy_icon_background"
        private const val KEY_WRAP_DISABLED = "wrap_disabled_apps"
        private const val KEY_ANIMATIONS = "animations_enabled"
        private const val KEY_APP_ORDER = "app_order"
        private const val KEY_APP_ORDER_SEEDED = "app_order_seeded"
        private const val KEY_SETTINGS_SEED_FIXED = "app_order_settings_seed_fixed"
        private const val KEY_BUILT_IN_ICONS_APPLIED = "built_in_icons_applied"
        private const val KEY_UNLISTED_APPS_HIDDEN = "unlisted_apps_hidden"
        private const val APP_ORDER_SEPARATOR = "\n"
    }
}
