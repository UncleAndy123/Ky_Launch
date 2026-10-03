package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Best-effort "app that handles X on this device" resolvers, used both to
 * seed the app grid's one-time default order and as fallbacks for Home's
 * physical-key defaults. Every function returns a component key (see
 * [com.flipos.launcher.data.AppInfo.key]) or null if nothing resolves on
 * this device - never throws.
 */
@Suppress("DEPRECATION") // int-flags resolveActivity() overload kept for minSdk 21 compatibility
object CategoryApps {

    /** The default phone/dialer app's main launch component, or null. */
    fun dialerKey(context: Context): String? {
        val packageName = if (Build.VERSION.SDK_INT >= 23) {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } else {
            null
        } ?: resolvePackage(context, Intent(Intent.ACTION_DIAL))
        return packageName?.let { launchKeyFor(context, it) }
    }

    /** The default SMS app's main launch component, or null. */
    fun smsKey(context: Context): String? =
        Telephony.Sms.getDefaultSmsPackage(context)?.let { launchKeyFor(context, it) }

    fun contactsKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_CONTACTS)
    fun galleryKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_GALLERY)
    fun calendarKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_CALENDAR)
    fun emailKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_EMAIL)
    fun musicKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_MUSIC)

    /** The default camera app's main launch component, or null. */
    fun cameraKey(context: Context): String? =
        resolvePackage(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let { launchKeyFor(context, it) }

    /**
     * The real Android Settings app's package, or null. Not a launch
     * component key like the other resolvers - callers that need one should
     * look it up by package in their own app list, since this can resolve
     * to a deep settings activity rather than the app's main launcher
     * entry. Tries this hardware's own Kyocera-branded Settings app first
     * (`jp.kyocera.settings.nfp` - confirmed via logcat as the actual
     * launcher-visible "Settings" entry on this device;
     * `Intent(Settings.ACTION_SETTINGS)` alone resolves ambiguously here
     * since both it and `com.android.settings` can handle that action, and
     * `com.android.settings` isn't itself launcher-visible on this build),
     * falling back to the standard resolution for any other device.
     */
    fun systemSettingsPackage(context: Context): String? {
        if (isPackageVisible(context, KYOCERA_SETTINGS_PACKAGE)) return KYOCERA_SETTINGS_PACKAGE
        return resolvePackage(context, Intent(Settings.ACTION_SETTINGS))
    }

    private const val KYOCERA_SETTINGS_PACKAGE = "jp.kyocera.settings.nfp"

    private fun isPackageVisible(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** File manager, only resolvable from API 29 (when CATEGORY_APP_FILES was added). */
    fun filesKey(context: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return categoryKey(context, Intent.CATEGORY_APP_FILES)
    }

    private fun categoryKey(context: Context, category: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val ai = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        return ComponentName(ai.packageName, ai.name).flattenToString()
    }

    private fun resolvePackage(context: Context, intent: Intent): String? =
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName

    private fun launchKeyFor(context: Context, packageName: String): String? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
}
