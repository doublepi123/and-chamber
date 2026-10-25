package io.andchamber.app.phone.android

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import io.andchamber.app.ui.phone.PhoneTarget

/** One launcher entry as the platform reports it, before any policy is applied. */
internal data class RawLauncherTarget(
    val label: String,
    val packageName: String,
    val system: Boolean,
)

/**
 * The apps a person may pick from: launcher activities only.
 *
 * The list is built from `ACTION_MAIN` + `CATEGORY_LAUNCHER` through the manifest `<queries>` entry,
 * never from `QUERY_ALL_PACKAGES` or `getInstalledPackages`. It stays in native memory and is never
 * handed to the model, the tool protocol or a log. The controller's [PhoneTargetPolicy] still decides
 * what may be granted; this catalogue only fills the picker.
 */
internal class PhoneTargetCatalog(
    private val hostPackage: String,
    private val limit: Int = MAX_TARGETS,
    private val query: () -> List<RawLauncherTarget>,
) {
    fun load(): List<PhoneTarget> = entries(query(), limit)

    companion object {
        const val MAX_TARGETS = 400

        /**
         * Deduplicates by package, keeps the host package visible (the screen explains why it is
         * blocked), sorts by label and bounds the result.
         */
        fun entries(raw: List<RawLauncherTarget>, limit: Int = MAX_TARGETS): List<PhoneTarget> =
            raw.asSequence()
                .filter { it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
                .take(limit.coerceIn(1, MAX_TARGETS))
                .map { PhoneTarget(label = it.label.ifBlank { it.packageName }, packageName = it.packageName, system = it.system) }
                .toList()

        /** Reads launcher activities from the platform. Never called for the model. */
        fun androidQuery(
            context: Context,
            hostPackage: String,
        ): List<RawLauncherTarget> {
            val manager = context.packageManager
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved: List<ResolveInfo> =
                // Launcher filters need CATEGORY_LAUNCHER, not CATEGORY_DEFAULT.
                runCatching { manager.queryIntentActivities(intent, 0) }
                    .getOrDefault(emptyList())
            return resolved.mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val application = info.activityInfo?.applicationInfo
                RawLauncherTarget(
                    label = runCatching { info.loadLabel(manager)?.toString().orEmpty() }.getOrDefault(""),
                    packageName = packageName,
                    system = application?.let(::isSystem) ?: false,
                )
            }.distinctBy { it.packageName }
        }

        private fun isSystem(application: ApplicationInfo): Boolean {
            val flags = application.flags
            return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        }
    }
}
