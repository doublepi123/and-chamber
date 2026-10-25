package io.andchamber.app.phone.android

import android.content.Context
import android.security.KeyPairGeneratorSpec
import androidx.annotation.RequiresApi
import io.andchamber.app.phone.adb.AdbIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The app's own ADB identity: one RSA key in AndroidKeyStore with its self-signed certificate.
 *
 * The private key never leaves the keystore and is never written to a file, a log or state. The
 * certificate is what the device pairs and authorizes; dropping the keystore alias is the only way
 * to forget the identity. Generating it is deferred until the phone-control driver is first used, so
 * app start does no keystore work.
 */
class PhoneIdentityStore(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS,
) {
    /** Creates the key on first use. Throws only when the platform keystore refuses the request. */
    @Synchronized
    fun identity(deviceName: String = DEFAULT_DEVICE_NAME): AdbIdentity {
        val entry = loadEntry() ?: createEntry()
        val certificate = entry.certificate as X509Certificate
        certificate.checkValidity()
        val identity = AdbIdentity(entry.privateKey, certificate, deviceName)
        check(identity.isConsistent()) { "phone identity cannot sign" }
        return identity
    }

    /** True when an identity already exists; reading it does not create one. */
    @Synchronized
    fun hasIdentity(): Boolean = runCatching { loadEntry() != null }.getOrDefault(false)

    /** Removes the identity. The device keeps its authorization until the person revokes debugging. */
    @Synchronized
    fun forget() {
        runCatching {
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }

    private fun loadEntry(): KeyStore.PrivateKeyEntry? {
        val store = keyStore()
        if (!store.containsAlias(alias)) return null
        return store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
    }

    @RequiresApi(18)
    private fun createEntry(): KeyStore.PrivateKeyEntry {
        val store = keyStore()
        // A stale or unreadable entry is replaced rather than reused.
        runCatching { if (store.containsAlias(alias)) store.deleteEntry(alias) }
        val now = System.currentTimeMillis()
        val spec =
            KeyPairGeneratorSpec.Builder(context)
                .setAlias(alias)
                .setKeyType("RSA")
                .setKeySize(2048)
                .setSubject(X500Principal("CN=AndChamber Phone Control"))
                .setSerialNumber(BigInteger.valueOf(now))
                .setStartDate(Date(now - DAY_MS))
                .setEndDate(Date(now + TEN_YEARS_MS))
                .build()
        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        generator.initialize(spec)
        generator.generateKeyPair()
        return store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("AndroidKeyStore did not return the phone identity")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "andchamber.phone.adb"
        const val DEFAULT_DEVICE_NAME = "AndChamber"

        private const val DAY_MS = 86_400_000L
        private const val TEN_YEARS_MS = 10L * 365L * DAY_MS
    }
}
