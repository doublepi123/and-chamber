package io.andchamber.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.ServerTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Remote OpenChamber servers, persisted as one JSON list in EncryptedSharedPreferences. The UI
 * password is stored only here, encrypted at rest with an AES-GCM master key.
 */
class EncryptedRemoteServerStore(
    private val prefs: android.content.SharedPreferences,
) : RemoteServerStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(read())
    override val servers: StateFlow<List<ServerTarget.Remote>> = mutable.asStateFlow()

    @Synchronized
    override fun upsert(server: ServerTarget.Remote) {
        val next = mutable.value.filterNot { it.id == server.id } + server
        write(next)
    }

    @Synchronized
    override fun remove(id: String) {
        write(mutable.value.filterNot { it.id == id })
    }

    private fun read(): List<ServerTarget.Remote> {
        val payload = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredServer.serializer()), payload)
                .map { it.toTarget() }
        }.getOrDefault(emptyList())
    }

    private fun write(servers: List<ServerTarget.Remote>) {
        val payload = json.encodeToString(ListSerializer(StoredServer.serializer()), servers.map(StoredServer::from))
        prefs.edit().putString(KEY_SERVERS, payload).apply()
        mutable.value = servers
    }

    @Serializable
    private data class StoredServer(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val uiPassword: String? = null,
    ) {
        fun toTarget() = ServerTarget.Remote(id, displayName, baseUrl, uiPassword)

        companion object {
            fun from(target: ServerTarget.Remote) =
                StoredServer(target.id, target.displayName, target.baseUrl, target.uiPassword)
        }
    }

    companion object {
        private const val FILE_NAME = "andchamber_remote_servers"
        private const val KEY_SERVERS = "servers"

        fun create(context: Context): EncryptedRemoteServerStore {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            return EncryptedRemoteServerStore(prefs)
        }
    }
}
