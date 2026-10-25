package io.andchamber.app.phone.android

import android.content.Context
import android.content.SharedPreferences
import io.andchamber.app.phone.core.PhoneLimits

/**
 * Persistence the phone-control integration is allowed to keep.
 *
 * Stored: the person's explicit opt-in, and a minimal task record (target package, the task summary
 * the person already saw, and whether the result still needs a human check). Never stored: grants,
 * epochs, leases, the ADB identity, pairing codes, ports, coordinates, screenshots or page text.
 */
interface PhoneIntegrationStore {
    fun enabled(): Boolean

    fun setEnabled(value: Boolean)

    /** The minimal record of the last task, or null. No authority is derived from it. */
    fun task(): PhoneTaskRecord?

    fun setTask(record: PhoneTaskRecord?)

    fun clearTask()
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
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TASK_PACKAGE = "task.package"
        const val KEY_TASK_SUMMARY = "task.summary"
        const val KEY_TASK_NEEDS_CHECK = "task.needsCheck"
        const val KEY_TASK_HALT = "task.halt"
        const val KEY_TASK_ACK = "task.acknowledged"

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
    }
}

/** In-memory store for tests and previews. */
class MemoryPhoneIntegrationStore(
    enabled: Boolean = false,
    task: PhoneTaskRecord? = null,
) : PhoneIntegrationStore {
    private var enabledValue = enabled
    private var taskValue = task

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
