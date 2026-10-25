package io.andchamber.app.phone.adb

import android.content.Context
import android.security.KeyPairGeneratorSpec
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/**
 * Instrumentation-only identity. It uses the platform AndroidKeyStore legacy generator to obtain a
 * self-signed RSA certificate without shipping a private key in the repository and without adding a
 * certificate-builder dependency.
 *
 * This is a test identity. It is not a hardware-backed attestation and the app does not claim that
 * the device paired a hardware ADB identity. The alias is unique per run and deleted afterwards.
 */
object TestAdbIdentity {
    class CreatedIdentity(
        val alias: String,
        val identity: AdbIdentity,
    )

    @Suppress("DEPRECATION")
    fun create(context: Context): CreatedIdentity {
        val alias = "andchamber-phone-probe-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(alias)
            .setKeyType("RSA")
            .setKeySize(2048)
            .setSubject(X500Principal("CN=AndChamber Phone Probe"))
            .setSerialNumber(BigInteger.valueOf(now))
            .setStartDate(Date(now - 60_000L))
            .setEndDate(Date(now + 86_400_000L))
            .build()
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        generator.initialize(spec)
        generator.generateKeyPair()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("AndroidKeyStore did not return a private key entry")
        val identity = AdbIdentity(
            privateKey = entry.privateKey,
            certificate = entry.certificate as X509Certificate,
            deviceName = "AndChamberPhoneProbe",
        )
        check(identity.isConsistent()) { "generated identity cannot sign" }
        return CreatedIdentity(alias, identity)
    }

    fun delete(alias: String) {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }
}
