package io.andchamber.app.runtime.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** SharedPreferences-backed switch. The only persisted state of the feature. */
class PrefsSharedStoragePrefs(
    private val prefs: SharedPreferences,
) : SharedStoragePrefs {
    override fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        const val FILE = "andchamber_shared_storage"
        private const val KEY_ENABLED = "enabled"

        fun open(context: Context): PrefsSharedStoragePrefs =
            PrefsSharedStoragePrefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/**
 * The all-files access facts and the request intents the UI needs. API 30+ uses the special
 * all-files permission; API 26–29 use the runtime storage permissions (see [runtimePermissions]).
 */
object SharedStorageAccess {
    /** `/storage/emulated/0/AndChamber`, the public root. */
    fun defaultHostRoot(): File = File(Environment.getExternalStorageDirectory(), "AndChamber")

    /** True when the app may use shared storage on this release. */
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            runtimePermissions().all {
                context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

    /** Runtime permissions to request on API 26–29. Empty on API 30+, which uses [settingsIntent]. */
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /**
     * Intent for the all-files access screen on API 30+. Falls back to the generic list when the
     * per-app screen is missing on a device.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

    fun settingsFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
