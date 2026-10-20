package io.andchamber.app.runtime.process

import java.security.SecureRandom

/**
 * One native browser-provider credential for one local runtime launch. The secret lives only in
 * this object and in the OpenChamber server environment. It is not persisted, logged, placed in
 * shared config, or copied into the OpenCode child environment. The server patch must delete it
 * from its own process environment before spawning children.
 *
 * Phase 1 is local runtime only. A remote console does not receive this session.
 */
class BrowserProviderSession(
    private val random: SecureRandom = SecureRandom(),
    private val sink: BrowserProviderSink = BrowserProviderSink.None,
) {
    @Volatile
    private var current: Issued? = null

    /**
     * Issues a token for a launch that has not started. The sink is not told yet: a failed spawn
     * must revoke this attempt before any provider connects.
     */
    fun issue(launchId: Long): Issued {
        val issued = Issued(launchId = launchId, token = token())
        current = issued
        return issued
    }

    /**
     * Connects a token that was issued for [launchId] and whose server is verified ready, and only
     * while a trusted local chat host is in the foreground. A missing host or a replaced token
     * revokes instead of connecting.
     */
    fun connect(launchId: Long, origin: String, trustedHostAttached: Boolean) {
        val active = current
        if (active == null || active.launchId != launchId || !trustedHostAttached) {
            revoke(launchId)
            return
        }
        sink.onLocalLaunch(origin, active.token, launchId)
    }

    /** Issues a new token and drops the previous one. Safe to call before each local launch. */
    fun rotate(launchId: Long, origin: String): Issued {
        val issued = issue(launchId)
        sink.onLocalLaunch(origin, issued.token, launchId)
        return issued
    }

    /** Drops the token for [launchId]. A newer launch is left alone. */
    fun revoke(launchId: Long) {
        val active = current
        if (active == null || active.launchId != launchId) return
        current = null
        sink.onLocalStop(launchId)
    }

    fun currentToken(): String? = current?.token

    fun trustedHostAttached(): Boolean = sink.trustedHostAttached()

    private fun token(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class Issued(
        val launchId: Long,
        val token: String,
    ) {
        init {
            require(token.length == TOKEN_BYTES * 2) { "provider token must be 32 bytes" }
            require(token.all { it in '0'..'9' || it in 'a'..'f' }) { "provider token must be hex" }
        }
    }

    companion object {
        const val TOKEN_BYTES = 32
        const val MODE_ENV = "OPENCHAMBER_BROWSER_PROVIDER"
        const val TOKEN_ENV = "ANDCHAMBER_BROWSER_PROVIDER_TOKEN"
        const val MODE_ANDROID = "android"
    }
}

/** Android provider binding. The browser lane supplies the production sink; runtime never logs it. */
interface BrowserProviderSink {
    /** True only while the trusted local chat page is in the foreground. Remote consoles are never attached. */
    fun trustedHostAttached(): Boolean = false

    /** Local loopback launch only. Implementations must ignore non-loopback origins and must not log [token]. */
    fun onLocalLaunch(origin: String, token: String, launchId: Long)

    fun onLocalStop(launchId: Long)

    object None : BrowserProviderSink {
        override fun onLocalLaunch(origin: String, token: String, launchId: Long) = Unit

        override fun onLocalStop(launchId: Long) = Unit
    }
}
