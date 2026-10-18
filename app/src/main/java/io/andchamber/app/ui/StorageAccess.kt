package io.andchamber.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** Requested on Android 8–10. Android 11+ grants all-files access from system settings instead. */
private val legacyStoragePermissions =
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

/**
 * Returns an action that asks for the access shared storage needs: the "All files access" screen
 * in system settings on Android 11+, the storage permission dialog before that. [onResult] runs
 * when the dialog closes; coming back from system settings is picked up by the ON_RESUME refresh.
 */
@Composable
internal fun rememberStorageAccessRequest(onResult: () -> Unit): () -> Unit {
    if (LocalInspectionMode.current) return remember { {} }
    val context = LocalContext.current
    // Set once the system stops showing the dialog ("Don't ask again"); then only app settings help.
    // A dismissed first dialog looks the same (no rationale), so only judge after a second denial.
    val blocked = rememberSaveable { mutableStateOf(false) }
    val denials = rememberSaveable { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            onResult()
            val activity = context.findActivity() ?: return@rememberLauncherForActivityResult
            val denied = results.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) return@rememberLauncherForActivityResult
            denials.intValue += 1
            blocked.value = denials.intValue >= 2 && denied.none { activity.shouldShowRequestPermissionRationale(it) }
        }
    return remember(context, launcher) {
        {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.openFirst(allFilesAccessIntents(context))
                blocked.value -> context.openFirst(listOf(appDetailsIntent(context)))
                else -> launcher.launch(legacyStoragePermissions)
            }
        }
    }
}

/** Whether the app currently holds the access shared storage needs. */
internal fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        legacyStoragePermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

/** The per-app screen, then the list of apps (some devices lack the first), then app info. */
@RequiresApi(Build.VERSION_CODES.R)
private fun allFilesAccessIntents(context: Context): List<Intent> =
    listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        appDetailsIntent(context),
    )

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** Starts the first intent this device can handle. */
private fun Context.openFirst(candidates: List<Intent>) {
    val outsideActivity = findActivity() == null
    for (intent in candidates) {
        if (outsideActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        } catch (_: SecurityException) {
            // Try the next one.
        }
    }
    Toast.makeText(this, Strings.cantOpenSettings, Toast.LENGTH_SHORT).show()
}
