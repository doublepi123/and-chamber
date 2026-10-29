package io.andchamber.app.phone.android

import android.content.Context
import android.content.SharedPreferences
import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Persistence the phone-control integration is allowed to keep.
 *
 * Stored: the person's explicit opt-in, a minimal task record (target package, the task summary the
 * person already saw, and whether the result still needs a human check), and the person-confirmed
 * standing scope (apps or the explicit all-apps flag, actions, window, per-task duration; never a
 * grant or lease).
 * Never stored: grants, epochs, leases, the ADB identity, pairing codes, ports, coordinates,
 * screenshots or page text.
 */
interface PhoneIntegrationStore {
    fun enabled(): Boolean

    fun setEnabled(value: Boolean)

    /** The minimal record of the last task, or null. No authority is derived from it. */
    fun task(): PhoneTaskRecord?

    fun setTask(record: PhoneTaskRecord?)

    fun clearTask()

    /** The person-confirmed standing configuration, or null. It is not a lease and grants nothing. */
    fun standing(): PhoneStandingConfig?

    fun setStanding(config: PhoneStandingConfig?)
}

/**
 * A task record, not a grant. [needsCheck] is set whenever the app cannot prove the task's business
 * result; a cold start with a record shows "needs check" and never reconnects, re-grants or replays.
 */
data class PhoneTaskRecord(
    val targetPackage: String,
    val summary: String,
    val needsCheck: Boolean,
    /** A fixed halt-reason name (for example `HOST_RETURNED`), never page text. */
    val haltReason: String? = null,
    /** True once a person marked an uncertain last action as checked. */
    val acknowledged: Boolean = false,
    /**
     * The agent's own finish while it held a clean live grant (no halt, nothing in flight). Still
     * unverified; it only lets the screen show a light note instead of the full check step.
     */
    val agentReported: Boolean = false,
)

/** SharedPreferences-backed production store. One small file, no secrets. */
class PrefsPhoneIntegrationStore(
    private val prefs: SharedPreferences,
) : PhoneIntegrationStore {
    override fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    override fun task(): PhoneTaskRecord? {
        val packageName = prefs.getString(KEY_TASK_PACKAGE, null) ?: return null
        val summary = prefs.getString(KEY_TASK_SUMMARY, null) ?: return null
        val target = sanitizePackage(packageName) ?: return null
        val bounded = sanitizeSummary(summary) ?: return null
        return PhoneTaskRecord(
            targetPackage = target,
            summary = bounded,
            needsCheck = prefs.getBoolean(KEY_TASK_NEEDS_CHECK, true),
            haltReason = prefs.getString(KEY_TASK_HALT, null),
            acknowledged = prefs.getBoolean(KEY_TASK_ACK, false),
            agentReported = prefs.getBoolean(KEY_TASK_AGENT_REPORTED, false),
        )
    }

    override fun setTask(record: PhoneTaskRecord?) {
        if (record == null) {
            clearTask()
            return
        }
        val target = sanitizePackage(record.targetPackage) ?: return
        val summary = sanitizeSummary(record.summary) ?: return
        prefs
            .edit()
            .putString(KEY_TASK_PACKAGE, target)
            .putString(KEY_TASK_SUMMARY, summary)
            .putBoolean(KEY_TASK_NEEDS_CHECK, record.needsCheck)
            .putString(KEY_TASK_HALT, record.haltReason)
            .putBoolean(KEY_TASK_ACK, record.acknowledged)
            .putBoolean(KEY_TASK_AGENT_REPORTED, record.agentReported)
            .apply()
    }

    override fun clearTask() {
        prefs
            .edit()
            .remove(KEY_TASK_PACKAGE)
            .remove(KEY_TASK_SUMMARY)
            .remove(KEY_TASK_NEEDS_CHECK)
            .remove(KEY_TASK_HALT)
            .remove(KEY_TASK_ACK)
            .remove(KEY_TASK_AGENT_REPORTED)
            .apply()
    }

    override fun standing(): PhoneStandingConfig? =
        prefs.getString(KEY_STANDING, null)?.let(::decodeStanding)

    override fun setStanding(config: PhoneStandingConfig?) {
        if (config == null) {
            prefs.edit().remove(KEY_STANDING).apply()
            return
        }
        prefs.edit().putString(KEY_STANDING, encodeStanding(config)).apply()
    }

    internal companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TASK_PACKAGE = "task.package"
        const val KEY_TASK_SUMMARY = "task.summary"
        const val KEY_TASK_NEEDS_CHECK = "task.needsCheck"
        const val KEY_TASK_HALT = "task.halt"
        const val KEY_TASK_ACK = "task.acknowledged"
        const val KEY_TASK_AGENT_REPORTED = "task.agentReported"
        const val KEY_STANDING = "standing.v1"

        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun sanitizePackage(value: String): String? =
            value.takeIf { it.length <= PhoneLimits.MAX_PACKAGE_CHARS }?.takeIf(PACKAGE_PATTERN::matches)

        /** The summary is the person's own task text; bound it and drop control characters. */
        fun sanitizeSummary(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > PhoneLimits.MAX_NODE_LABEL_CHARS) return null
            if (trimmed.any(Char::isISOControl)) return null
            return trimmed
        }

        fun sanitizeLabel(value: String, packageName: String): String {
            val bounded = value.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS)
            return if (bounded.isEmpty() || bounded.any(Char::isISOControl)) packageName else bounded
        }

        internal fun encodeStanding(config: PhoneStandingConfig): String =
            buildJsonObject {
                put("version", 1)
                put("windowEndsAtWallMs", config.windowEndsAtWallMs)
                put("perTaskMs", config.perTaskMs)
                put("revoked", config.revoked)
                put("allApps", config.allApps)
                put("actions", JsonArray(config.actions.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
                put(
                    "apps",
                    JsonArray(
                        config.apps.map { app ->
                            buildJsonObject {
                                put("label", app.label)
                                put("packageName", app.packageName)
                            }
                        },
                    ),
                )
            }.toString()

        internal fun decodeStanding(
            raw: String,
            nowWallMs: Long = System.currentTimeMillis(),
        ): PhoneStandingConfig? =
            runCatching {
                val root = Json.parseToJsonElement(raw) as JsonObject
                if ((root["version"]?.jsonPrimitive?.content ?: "") != "1") return null
                val windowEndsAtWallMs = root["windowEndsAtWallMs"]?.jsonPrimitive?.longOrNull ?: return null
                val perTaskMs = root["perTaskMs"]?.jsonPrimitive?.longOrNull ?: return null
                val revoked = root["revoked"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                // A document written before all-apps existed has no field and means explicit mode.
                // Anything present that is not a JSON boolean (a string, a number, null) is corrupt.
                val allApps =
                    root["allApps"]?.let { element ->
                        val primitive = element as? JsonPrimitive ?: return null
                        if (primitive.isString) return null
                        primitive.content.toBooleanStrictOrNull() ?: return null
                    } ?: false
                val actions =
                    root["actions"]
                        ?.jsonArray
                        .orEmpty()
                        .mapNotNull { item -> runCatching { PhoneActionKind.valueOf(item.jsonPrimitive.content) }.getOrNull() }
                        .toSet()
                val apps =
                    root["apps"]
                        ?.jsonArray
                        .orEmpty()
                        .mapNotNull { item ->
                            val entry = item.jsonObject
                            val packageName = entry["packageName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val safePackage = sanitizePackage(packageName) ?: return@mapNotNull null
                            PhoneStandingApp(
                                label = sanitizeLabel(entry["label"]?.jsonPrimitive?.content.orEmpty(), safePackage),
                                packageName = safePackage,
                            )
                        }
                        .distinctBy { it.packageName }
                        .take(PhoneStandingPolicy.MAX_PACKAGES)
                if (actions.isEmpty()) return null
                // Exactly one shape: an all-apps scope has no list; an explicit scope needs one.
                if (allApps && apps.isNotEmpty()) return null
                if (!allApps && apps.isEmpty()) return null
                // A finite end is at most the configured window ahead of the save. A far-future value
                // that is not the exact sentinel is corrupt, not permanent.
                if (!PhoneTimeLimits.isPermanent(windowEndsAtWallMs) &&
                    (windowEndsAtWallMs <= 0L ||
                        windowEndsAtWallMs - nowWallMs > STANDING_MAX_WINDOW_MS)
                ) {
                    return null
                }
                if (!PhoneTimeLimits.isPermanent(perTaskMs) &&
                    perTaskMs !in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS
                ) {
                    return null
                }
                PhoneStandingConfig(
                    apps = apps,
                    actions = actions,
                    windowEndsAtWallMs = windowEndsAtWallMs,
                    perTaskMs = perTaskMs,
                    revoked = revoked,
                    allApps = allApps,
                )
            }.getOrNull()
    }
}

/** In-memory store for tests and previews. */
class MemoryPhoneIntegrationStore(
    enabled: Boolean = false,
    task: PhoneTaskRecord? = null,
) : PhoneIntegrationStore {
    private var enabledValue = enabled
    private var taskValue = task
    private var standingValue: PhoneStandingConfig? = null

    override fun enabled(): Boolean = enabledValue

    override fun setEnabled(value: Boolean) {
        enabledValue = value
    }

    override fun task(): PhoneTaskRecord? = taskValue

    override fun setTask(record: PhoneTaskRecord?) {
        taskValue = record
    }

    override fun clearTask() {
        taskValue = null
    }

    override fun standing(): PhoneStandingConfig? = standingValue

    override fun setStanding(config: PhoneStandingConfig?) {
        standingValue = config
    }
}

/**
 * The live phone-plugin switch a launch samples once at its start.
 *
 * The lambda reads the person's current opt-in every time it is called; the runtime samples it at
 * each launch, so toggling never rebuilds the server and never touches a running launch. The
 * constructor Boolean of the server stays only as the default when no lambda is supplied.
 */
internal fun phonePluginEnabledNow(store: PhoneIntegrationStore): () -> Boolean = { store.enabled() }

/** Opens the production file. Android hands out one instance per file name in a process. */
fun phoneIntegrationStore(context: Context): PhoneIntegrationStore =
    PrefsPhoneIntegrationStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

const val PHONE_INTEGRATION_PREFS_FILE = "andchamber_phone"

private const val FILE = PHONE_INTEGRATION_PREFS_FILE
