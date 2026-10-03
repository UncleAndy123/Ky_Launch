package com.flipos.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.flipos.launcher.util.CategoryApps

/**
 * Reads launchable apps and individual activities from [PackageManager] and
 * resolves the launch intents the launcher fires. Stateless: callers re-query so
 * the list is always fresh after installs/uninstalls.
 */
object AppRepository {

    private const val SETTINGS_ACTIVITY = "com.flipos.launcher.activities.SettingsActivity"
    private const val NOTICES_ACTIVITY = "com.flipos.launcher.activities.NoticesActivity"

    /**
     * Memoizes the shaped/wrapped icon bitmap per (component + override + pack +
     * shape + background + density), so a re-query doesn't re-run the whole
     * Palette + multi-bitmap render pipeline for every app. Stores bitmaps (not
     * Drawables) and re-wraps a fresh [BitmapDrawable] per request so callers get
     * independent bounds. Cleared via [invalidateIconCaches] on icon/pack/package
     * changes.
     */
    private const val ICON_CACHE_SIZE = 400
    private val renderedIconCache = LruCache<String, android.graphics.Bitmap>(ICON_CACHE_SIZE)

    /**
     * Clears every icon-related cache. Call after any change that alters how
     * icons look (icon shape/pack/override/background prefs) or which apps exist
     * (package add/remove/replace).
     */
    fun invalidateIconCaches() {
        renderedIconCache.evictAll()
        NotificationDotColor.clear()
        IconPackRepository.clearCaches()
    }

    /**
     * Every launchable app except this launcher itself, alphabetical order
     * broken by the user's custom app-grid position (see
     * [LauncherPrefs.getAppOrder]) where one is set - positioned apps sort by
     * that position, everything else falls alphabetically after them.
     */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    fun getAllApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = context.packageName
        // Exclude our own activities except Settings and Notices, which are
        // deliberately exported with a LAUNCHER category so they show up
        // here like a regular app.
        val resolveInfos = pm.queryIntentActivities(intent, 0).filter { ri ->
            val ai = ri.activityInfo ?: return@filter false
            ai.packageName != self || ai.name == SETTINGS_ACTIVITY || ai.name == NOTICES_ACTIVITY
        }
        // Must run before icons are resolved below, not after (unlike
        // applyAppOrder, which only re-sorts an already-built list) - an
        // icon override written after the fact wouldn't show up until the
        // next query, leaving the very first drawer render on a fresh
        // install with the old icon.
        if (!prefs.isBuiltInIconsApplied()) {
            applyBuiltInIconDefaults(context, prefs, resolveInfos)
            prefs.setBuiltInIconsApplied()
        }
        if (!prefs.isUnlistedAppsHidden()) {
            applyDefaultHiddenApps(context, prefs, resolveInfos)
            prefs.setUnlistedAppsHidden()
        }
        val apps = resolveInfos.asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val key = ComponentName(ai.packageName, ai.name).flattenToString()
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = resolveIcon(context, prefs, key, ri.loadIcon(pm)),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        return applyAppOrder(context, prefs, apps)
    }

    /**
     * One-time (see [LauncherPrefs.isBuiltInIconsApplied]) best-effort pass
     * that applies our own colorful [BuiltInIcons] to a handful of common
     * apps whose own stock icons render flat/monochrome on some hardware
     * (the E4610), wherever they can be resolved with confidence via
     * standard Android category intents - the same [CategoryApps] resolvers
     * already used for Home key defaults and app-grid seeding. Never touches
     * an app the user already picked a custom icon for. Anything
     * unresolvable is simply skipped, not left as a gap - this only covers
     * the categories Android exposes a reliable intent for; the rest of the
     * built-in set stays available for manual Change Icon selection.
     */
    private fun applyBuiltInIconDefaults(context: Context, prefs: LauncherPrefs, resolveInfos: List<ResolveInfo>) {
        fun keyForPackage(packageName: String?): String? {
            val pkg = packageName ?: return null
            val ai = resolveInfos.firstOrNull { it.activityInfo?.packageName == pkg }?.activityInfo ?: return null
            return ComponentName(ai.packageName, ai.name).flattenToString()
        }
        fun applyDefault(componentKey: String?, iconName: String) {
            val packageName = componentKey?.let { ComponentName.unflattenFromString(it)?.packageName }
            val key = keyForPackage(packageName) ?: return
            if (prefs.getIconOverride(key) != null) return
            prefs.setIconOverride(key, BuiltInIcons.PACK_ID, iconName)
        }

        applyDefault(CategoryApps.dialerKey(context), "call_log_112")
        applyDefault(CategoryApps.smsKey(context), "sms_112")
        applyDefault(CategoryApps.contactsKey(context), "contact_112")
        applyDefault(CategoryApps.galleryKey(context), "gallery_84")
        applyDefault(CategoryApps.calendarKey(context), "calendar_112")
        applyDefault(CategoryApps.cameraKey(context), "camera_112")
        applyDefault(CategoryApps.emailKey(context), "email_112")
        applyDefault(CategoryApps.musicKey(context), "music_112")
    }

    /**
     * Fixed whitelist of built-in/OEM app packages that stay visible by
     * default (see [applyDefaultHiddenApps]) - ordered exactly as the user
     * confirmed them, one at a time, from a logcat of themselves opening
     * every app on the device.
     */
    private val DEFAULT_VISIBLE_PACKAGES = setOf(
        "jp.kyocera.settings.nfp", "com.android.calendar", "jp.kyocera.filemanager.launcher",
        "jp.kyocera.gallery.launcher", "com.kyocera.calculator2", "com.android.dialer",
        "com.android.contacts", "com.flipweather.app", "jp.kyocera.camera", "com.kyocera.alarm",
        "com.kyocera.musicplayer", "jp.kyocera.memo", "com.kyocera.stopwatch", "com.kyocera.timer",
        "jp.kyocera.kc_soundrecorder", "com.kyocera.flashlight", "com.turbotranslate.app",
        "com.turbotext.app", "org.matchat.client", "com.kyocera.worldclock",
    )

    /**
     * One-time (see [LauncherPrefs.isUnlistedAppsHidden]) default that hides
     * every app not on [DEFAULT_VISIBLE_PACKAGES], not the device's own
     * dialer/SMS/contacts apps, and not user-installed (i.e. not a system
     * app) - a direct request to declutter the drawer down to a known-good
     * set on an already-set-up device. Purely a one-time write into the same
     * [LauncherPrefs.setHidden] store the manual Hide Apps screen already
     * uses, so it never re-runs and never overrides anything the user
     * un-hides afterward.
     */
    private fun applyDefaultHiddenApps(context: Context, prefs: LauncherPrefs, resolveInfos: List<ResolveInfo>) {
        val alwaysVisible = DEFAULT_VISIBLE_PACKAGES + context.packageName +
            listOfNotNull(CategoryApps.dialerKey(context), CategoryApps.smsKey(context), CategoryApps.contactsKey(context))
                .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
        for (ri in resolveInfos) {
            val ai = ri.activityInfo ?: continue
            val packageName = ai.packageName
            if (packageName in alwaysVisible) continue
            val appFlags = ai.applicationInfo.flags
            val isSystemApp = appFlags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (!isSystemApp) continue // user-installed - always stays visible
            prefs.setHidden(ComponentName(packageName, ai.name).flattenToString(), true)
        }
    }

    /**
     * Applies (and, on the very first call ever, seeds) the user's custom
     * app-grid ordering on top of [apps]' alphabetical order. A stable sort
     * keyed by stored position keeps unpositioned apps in their existing
     * (alphabetical) relative order, appended after every positioned one.
     */
    private fun applyAppOrder(context: Context, prefs: LauncherPrefs, apps: List<AppInfo>): List<AppInfo> {
        if (!prefs.isAppOrderSeeded()) {
            prefs.setAppOrder(seedAppOrder(context, apps))
            prefs.setAppOrderSeeded()
            prefs.setSettingsSeedFixed()
        } else if (!prefs.isSettingsSeedFixed()) {
            fixSettingsSeed(context, prefs, apps)
        }
        val order = prefs.getAppOrder()
        if (order.isEmpty()) return apps
        val position = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, key -> position[key] = index }
        return apps.sortedBy { position[it.key] ?: Int.MAX_VALUE }
    }

    /**
     * One-time starting order (see [LauncherPrefs.isAppOrderSeeded]): call
     * history, default SMS app, contacts, gallery, file manager, calendar,
     * notices, the real Settings app - in that order, first on the grid;
     * everything else stays alphabetical after them. A category with no
     * resolvable app on this device (or "media center"/notepad, which have
     * no reliable automatic detection at all) is simply skipped, not left as
     * a gap. This only ever runs once - afterward the order is just
     * whatever's stored, fully freeform.
     */
    private fun seedAppOrder(context: Context, apps: List<AppInfo>): List<String> {
        fun activityKey(activityName: String): String? = apps.firstOrNull { it.activityName == activityName }?.key
        fun componentPackageKey(componentKey: String?): String? =
            packageNameKey(apps, componentKey?.let { ComponentName.unflattenFromString(it)?.packageName })

        return listOfNotNull(
            componentPackageKey(CategoryApps.dialerKey(context)),
            componentPackageKey(CategoryApps.smsKey(context)),
            componentPackageKey(CategoryApps.contactsKey(context)),
            componentPackageKey(CategoryApps.galleryKey(context)),
            componentPackageKey(CategoryApps.filesKey(context)),
            componentPackageKey(CategoryApps.calendarKey(context)),
            activityKey(NOTICES_ACTIVITY),
            packageNameKey(apps, CategoryApps.systemSettingsPackage(context)),
        ).distinct()
    }

    /** Matches [packageName] against [apps] by package, resolving a category resolver's result to a real entry's component key. */
    private fun packageNameKey(apps: List<AppInfo>, packageName: String?): String? =
        packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }

    /**
     * One-time correction for a device that already seeded (see
     * [LauncherPrefs.isSettingsSeedFixed]) before the app grid's seeded slot
     * switched from our own Settings hub to the real system Settings app:
     * swaps that one stored entry in place (same position), preserving any
     * manual reordering the user has done since, rather than re-seeding
     * everything from scratch.
     *
     * [LauncherPrefs.setSettingsSeedFixed] is only persisted once a
     * correction genuinely happens, or once it's confirmed there's
     * genuinely nothing to fix (our own Settings key isn't in the stored
     * order at all) - every other early return (our own Settings activity
     * isn't in the current app list; [CategoryApps.systemSettingsPackage]
     * can't resolve on this device yet) leaves the flag unset so a future
     * call retries automatically, instead of a resolution failure
     * permanently giving up after a single attempt.
     */
    private fun fixSettingsSeed(context: Context, prefs: LauncherPrefs, apps: List<AppInfo>) {
        val ownSettingsKey = apps.firstOrNull { it.activityName == SETTINGS_ACTIVITY }?.key ?: return
        val order = prefs.getAppOrder()
        val position = order.indexOf(ownSettingsKey)
        if (position < 0) {
            prefs.setSettingsSeedFixed()
            return
        }
        val systemSettingsKey = packageNameKey(apps, CategoryApps.systemSettingsPackage(context)) ?: return
        if (systemSettingsKey in order) return
        prefs.setAppOrder(order.toMutableList().apply { set(position, systemSettingsKey) })
        prefs.setSettingsSeedFixed()
    }

    /** Apps shown to the user (hidden ones removed). */
    fun getVisibleApps(context: Context, prefs: LauncherPrefs): List<AppInfo> {
        val hidden = prefs.getHiddenKeys()
        return getAllApps(context).filter { it.key !in hidden }
    }

    /**
     * Every launchable, exported activity declared by [packageName] — the source
     * for the activity picker, so a shortcut can target a deep screen rather than
     * only an app's main entry point.
     */
    fun getActivities(context: Context, packageName: String): List<AppInfo> {
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            (info.activities ?: emptyArray()).asSequence()
                .filter { it.exported && it.enabled }
                .map { ai ->
                    val key = ComponentName(packageName, ai.name).flattenToString()
                    AppInfo(
                        label = ai.loadLabel(pm).toString(),
                        packageName = packageName,
                        activityName = ai.name,
                        icon = resolveIcon(context, prefs, key, ai.loadIcon(pm)),
                    )
                }
                .sortedBy { it.activityName }
                .toList()
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }

    /**
     * Resolve a stored shortcut [key] (any activity component) to a displayable
     * [AppInfo], or null if it no longer exists.
     */
    fun resolveComponent(context: Context, key: String): AppInfo? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val ai = pm.getActivityInfo(component, 0)
            AppInfo(
                label = ai.loadLabel(pm).toString(),
                packageName = component.packageName,
                activityName = component.className,
                icon = resolveIcon(context, prefs, key, ai.loadIcon(pm)),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Resolves [key] to its icon exactly like [resolveComponent] (per-app
     * override / active icon pack, falling back to the app's own icon), but
     * skips the final shape-masking step — for callers that need to apply a
     * *different* shape than the user's global [LauncherPrefs.IconShape]
     * (e.g. the Home D-pad shortcut pod, always squircle regardless of the
     * global setting).
     */
    fun resolveRawIcon(context: Context, key: String): Drawable? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val ai = pm.getActivityInfo(component, 0)
            rawIcon(context, prefs, key, ai.loadIcon(pm))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Swaps in a per-app icon override if one is set, else the active icon
     * pack's mapping for [componentKey] if it has one, else [fallback] — then
     * masks the result into the user's chosen icon shape. The shaped result is
     * memoized in [renderedIconCache]; the raw/unshaped path isn't cached since
     * those drawables come straight from PM per query anyway.
     */
    private fun resolveIcon(context: Context, prefs: LauncherPrefs, componentKey: String, fallback: Drawable): Drawable {
        val shape = prefs.getIconShape()
        val wrapEnabled = prefs.isIconWrapEnabled(componentKey)
        if (!wrapEnabled || shape == LauncherPrefs.IconShape.NONE) {
            return rawIcon(context, prefs, componentKey, fallback)
        }
        val legacyBg = prefs.isLegacyIconBackgroundEnabled()
        val cacheKey = iconCacheKey(context, prefs, componentKey, shape, legacyBg)
        renderedIconCache.get(cacheKey)?.let { return BitmapDrawable(context.resources, it) }

        val raw = rawIcon(context, prefs, componentKey, fallback)
        val rendered = IconShapeRenderer.render(
            context = context,
            source = raw,
            shape = shape,
            wrapEnabled = true,
            legacyBackgroundEnabled = legacyBg,
        )
        (rendered as? BitmapDrawable)?.bitmap?.let { renderedIconCache.put(cacheKey, it) }
        return rendered
    }

    private fun iconCacheKey(
        context: Context,
        prefs: LauncherPrefs,
        componentKey: String,
        shape: LauncherPrefs.IconShape,
        legacyBg: Boolean,
    ): String {
        val override = prefs.getIconOverride(componentKey)
        val pack = prefs.getActiveIconPack()
        val dpi = context.resources.displayMetrics.densityDpi
        return "$componentKey|ovr=${override?.first}:${override?.second}|pack=$pack|shape=$shape|bg=$legacyBg|dpi=$dpi"
    }

    private fun rawIcon(context: Context, prefs: LauncherPrefs, componentKey: String, fallback: Drawable): Drawable {
        val override = prefs.getIconOverride(componentKey) ?: packageIconOverride(context, prefs, componentKey)
        override?.let { (pack, name) ->
            val drawable = if (pack == BuiltInIcons.PACK_ID) {
                BuiltInIcons.loadIcon(context, name)
            } else {
                IconPackRepository.loadIcon(context, pack, name)
            }
            drawable?.let { return it }
        }
        val activePack = prefs.getActiveIconPack() ?: return fallback
        val name = IconPackRepository.iconNameFor(context, activePack, componentKey) ?: return fallback
        return IconPackRepository.loadIcon(context, activePack, name) ?: fallback
    }

    /**
     * Falls back to the icon override set on the app's main entry point, so an
     * activity picked via [ActivityPickerActivity] (a deep screen, not the app's
     * main icon) still picks up a custom icon when used as a Home shortcut.
     */
    private fun packageIconOverride(context: Context, prefs: LauncherPrefs, componentKey: String): Pair<String, String>? {
        val packageName = ComponentName.unflattenFromString(componentKey)?.packageName ?: return null
        val mainKey = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: return null
        if (mainKey == componentKey) return null
        return prefs.getIconOverride(mainKey)
    }

    /**
     * Intent that launches the activity identified by [key]. Uses a bare explicit
     * component (no MAIN/LAUNCHER category) so it works for any exported activity,
     * not just an app's home-screen entry.
     */
    fun launchIntentFor(key: String): Intent? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        // RESET_TASK_IF_NEEDED used to ride along with NEW_TASK here, but combined
        // with our own same-affinity activities (e.g. Launcher Settings, which is
        // exported so it can appear in the drawer) it makes the system swallow the
        // launch instead of pushing the activity onto the current task.
        return Intent()
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
