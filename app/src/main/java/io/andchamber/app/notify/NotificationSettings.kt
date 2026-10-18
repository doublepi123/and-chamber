package io.andchamber.app.notify

import io.andchamber.app.data.LocalApiClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Persists the one-time bootstrap flag. SharedPreferences in production, memory in tests. */
interface NotificationBootstrapStore {
    fun bootstrapped(): Boolean

    fun markBootstrapped()
}

/**
 * Turns on OpenChamber's `nativeNotificationsEnabled` once, on the first start after this update.
 *
 * Rule: only when the key is undefined in the settings (never set by the user). A value the user has
 * chosen, true or false, is never touched, and the flag is never re-run after it has been marked.
 */
class NotificationSettingsBootstrap(
    private val api: LocalApiClient,
    private val store: NotificationBootstrapStore,
) {
    /** Returns true when the setting was turned on by this call. Safe to call on every start. */
    suspend fun ensureEnabled(): Boolean {
        if (store.bootstrapped()) return false
        val settings = api.getJson(SETTINGS_PATH) ?: return false
        // A user (or an earlier version) already decided: respect it and never turn it on again.
        if (decide(settings) == Decision.Leave) {
            store.markBootstrapped()
            return false
        }
        val body = buildJsonObject { put(KEY, true) }
        val result = api.send(ApiRequest("PUT", SETTINGS_PATH, body))
        if (!result.ok) return false
        store.markBootstrapped()
        return true
    }

    enum class Decision { Enable, Leave }

    companion object {
        const val KEY = "nativeNotificationsEnabled"
        const val SETTINGS_PATH = "/api/config/settings"

        /** Pure rule over the settings object returned by GET /api/config/settings. */
        fun decide(settings: JsonObject): Decision {
            val value = settings[KEY]
            return if (value == null || value is JsonNull) Decision.Enable else Decision.Leave
        }
    }
}
