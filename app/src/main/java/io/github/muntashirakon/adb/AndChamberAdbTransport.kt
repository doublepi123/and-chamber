// SPDX-License-Identifier: Apache-2.0
//
// AndChamber app-local ADB helpers. This file deliberately lives in the LibADB Android package:
// it reuses the package-private pairing cipher (PairingAuthCtx), the Android public-key encoder
// (AndroidPubkey), the ADB packet builder (AdbProtocol) and the TLS key manager (SslUtils) of
// LibADB Android 3.1.1. The SPAKE2 implementation and the pairing packet formats are NOT
// re-implemented here.
//
// Upstream (used under the Apache-2.0 option of the dual GPL-3.0-or-later OR Apache-2.0 license):
//   https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java
//   https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/SslUtils.java
//   https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AdbConnection.java
//
// Modifications (AndChamber, 2026-10), kept intentionally small and reviewable:
//   * PairingConnectionCtx keeps its socket only as a local variable, has no connect/read timeout,
//     and its close() NPEs before the streams exist and cannot interrupt the handshake. This
//     transport keeps the socket as a field with bounded connect/SO_TIMEOUT, an overall deadline,
//     and a cancel()/close() that shuts the socket down so a blocked handshake or read ends.
//   * Pairing failures are typed (wrong code vs protocol vs deadline vs unreachable) so callers do
//     not have to infer the outcome from a message.
//   * The encrypted peer-info exchange and the 6-byte packet header are the upstream ones, except
//     that both peer-info types (ADB_RSA_PUB_KEY and ADB_DEVICE_GUID) are accepted because Android
//     15 adbd sends the device GUID type.
//   * SslUtils caches one SSLContext (and therefore one client key) in a static field. That is
//     wrong when the identity changes inside one process, so prepareContext() clears the cache.
//     If the pinned library changes that field, the reset is best effort; production uses one
//     identity per process.
//   * TLS is never assumed: probeStls() requires the daemon's A_STLS, and hasTls() reads the
//     pinned mIsTls field after connect so a plaintext transport is refused.
//   * The app bundles Conscrypt 2.5.3 (Apache-2.0), which LibADB selects when present, because the
//     platform Conscrypt exportKeyingMaterial hidden API is denied to reflection on API 35.

package io.github.muntashirakon.adb

import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Fixed pairing failures. No failure carries the code, a host, a serial or a raw packet. */
enum class AdbPairingFailure {
    INVALID_INPUT,
    POLICY,
    UNREACHABLE,
    TLS_UNAVAILABLE,
    TLS_HANDSHAKE,
    PROTOCOL,
    WRONG_CODE,
    DEADLINE,
    CANCELLED,
}

class AdbPairingException(
    val failure: AdbPairingFailure,
    cause: Throwable? = null,
) : IOException("ADB pairing failed (${failure.name})", cause)

/**
 * One bounded, cancellable ADB pairing handshake.
 *
 * The caller owns the identity and the code. [pair] uses the upstream SPAKE2 client, the TLS 1.3
 * exported keying material and the upstream encrypted peer-info exchange. It never falls back to a
 * plaintext transport and never retries.
 */
class AndChamberPairingTransport(
    private val host: String,
    private val port: Int,
    pairingCode: CharArray,
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
    private val deviceName: String,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {
    private val codeBytes: ByteArray

    @Volatile
    private var rawSocket: Socket? = null

    @Volatile
    private var sslSocket: SSLSocket? = null

    @Volatile
    private var pairingAuth: PairingAuthCtx? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var closed = false

    private var deadlineAtMs = 0L

    init {
        require(host.isNotBlank()) { "host" }
        require(port in 1..65535) { "port" }
        require(pairingCode.size == CODE_LENGTH && pairingCode.all { it in '0'..'9' }) {
            "pairing code must be six decimal digits"
        }
        require(readTimeoutMs in 1..MAX_READ_TIMEOUT_MS) { "read timeout" }
        codeBytes = String(pairingCode).toByteArray(Charsets.US_ASCII)
    }

    /**
     * Performs exactly one pairing exchange. On success the device has stored [certificate]'s
     * public key. The caller records the pairing receipt; a returned success does not prove that
     * a later connection will be authorized.
     */
    fun pair(deadlineMs: Long) {
        if (cancelled || closed) throw AdbPairingException(AdbPairingFailure.CANCELLED)
        if (deadlineMs <= 0L) throw AdbPairingException(AdbPairingFailure.DEADLINE)
        deadlineAtMs = System.nanoTime() / 1_000_000L + deadlineMs
        try {
            val raw = Socket()
            rawSocket = raw
            raw.tcpNoDelay = true
            raw.soTimeout = readTimeout()
            raw.connect(InetSocketAddress(host, port), connectTimeout())

            val tls = startTls(raw)
            sslSocket = tls
            tls.soTimeout = readTimeout()
            val input = DataInputStream(tls.inputStream)
            val output = DataOutputStream(tls.outputStream)

            val keyMaterial = exportKeyingMaterial(tls)
            val password = ByteArray(codeBytes.size + keyMaterial.size)
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.size)
            System.arraycopy(keyMaterial, 0, password, codeBytes.size, keyMaterial.size)
            val auth = try {
                PairingAuthCtx.createAlice(password)
            } finally {
                password.fill(0)
                keyMaterial.fill(0)
            } ?: throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE)
            pairingAuth = auth

            exchangeSpake2Message(auth, input, output)
            exchangePeerInfo(auth, input, output)
        } catch (failure: AdbPairingException) {
            throw failure
        } catch (timeout: SocketTimeoutException) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.DEADLINE,
                timeout,
            )
        } catch (eof: EOFException) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.PROTOCOL,
                eof,
            )
        } catch (tls: SSLException) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.TLS_HANDSHAKE,
                tls,
            )
        } catch (connect: ConnectException) {
            throw AdbPairingException(AdbPairingFailure.UNREACHABLE, connect)
        } catch (route: NoRouteToHostException) {
            throw AdbPairingException(AdbPairingFailure.UNREACHABLE, route)
        } catch (socket: SocketException) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.UNREACHABLE,
                socket,
            )
        } catch (io: IOException) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.PROTOCOL,
                io,
            )
        } catch (unexpected: Throwable) {
            throw AdbPairingException(AdbPairingFailure.PROTOCOL, unexpected)
        } finally {
            close()
        }
    }

    /** Ends a blocked handshake or read. Safe to call from a watchdog thread. */
    fun cancel() {
        cancelled = true
        close()
    }

    override fun close() {
        val tls: SSLSocket?
        val raw: Socket?
        val auth: PairingAuthCtx?
        synchronized(this) {
            if (closed) return
            closed = true
            tls = sslSocket
            sslSocket = null
            raw = rawSocket
            rawSocket = null
            auth = pairingAuth
            pairingAuth = null
        }
        runCatching { tls?.close() }
        runCatching { raw?.close() }
        runCatching { auth?.destroy() }
        codeBytes.fill(0)
    }

    private fun startTls(raw: Socket): SSLSocket {
        val context = try {
            AndChamberAdbTls.prepareContext(privateKey, certificate)
        } catch (failure: AdbPairingException) {
            throw failure
        } catch (failure: Throwable) {
            throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE, failure)
        }
        val tls = try {
            context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        } catch (failure: Throwable) {
            throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE, failure)
        }
        tls.soTimeout = readTimeout()
        try {
            tls.startHandshake()
        } catch (failure: Throwable) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.TLS_HANDSHAKE,
                failure,
            )
        }
        return tls
    }

    private fun exchangeSpake2Message(
        auth: PairingAuthCtx,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        checkDeadline()
        val message = auth.msg
        if (message.isEmpty() || message.size > MAX_PAIRING_PAYLOAD) {
            throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        }
        writePacket(output, TYPE_SPAKE2, message)

        val (type, size) = readHeader(input)
        if (type != TYPE_SPAKE2) throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        val peerMessage = ByteArray(size)
        readFully(input, peerMessage)
        val accepted = try {
            auth.initCipher(peerMessage)
        } catch (failure: Throwable) {
            throw AdbPairingException(AdbPairingFailure.PROTOCOL, failure)
        }
        if (!accepted) throw AdbPairingException(AdbPairingFailure.PROTOCOL)
    }

    private fun exchangePeerInfo(
        auth: PairingAuthCtx,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        checkDeadline()
        val publicKey = certificate.publicKey as? RSAPublicKey
            ?: throw AdbPairingException(AdbPairingFailure.INVALID_INPUT)
        val encoded = try {
            AndroidPubkey.encodeWithName(publicKey, deviceName)
        } catch (failure: Throwable) {
            throw AdbPairingException(AdbPairingFailure.INVALID_INPUT, failure)
        }
        if (encoded.size > PEER_INFO_MAX - 1) {
            throw AdbPairingException(AdbPairingFailure.INVALID_INPUT)
        }
        val peerInfo = ByteArray(PEER_INFO_MAX)
        peerInfo[0] = PEER_INFO_ADB_RSA_PUB_KEY
        System.arraycopy(encoded, 0, peerInfo, 1, encoded.size)
        val encrypted = try {
            auth.encrypt(peerInfo)
        } finally {
            peerInfo.fill(0)
        } ?: throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        writePacket(output, TYPE_PEER_INFO, encrypted)

        val (type, size) = readHeader(input)
        if (type != TYPE_PEER_INFO) throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        val peerCiphertext = ByteArray(size)
        readFully(input, peerCiphertext)
        val decrypted = auth.decrypt(peerCiphertext)
            ?: throw AdbPairingException(AdbPairingFailure.WRONG_CODE)
        // Android 15 adbd sends ADB_DEVICE_GUID (1) as the peer-info type; older peers and the
        // upstream client use ADB_RSA_PUB_KEY (0). The payload is already GCM-authenticated and is
        // not used as key material here, so both documented types are accepted.
        if (decrypted.size != PEER_INFO_MAX ||
            (decrypted[0] != PEER_INFO_ADB_RSA_PUB_KEY &&
                decrypted[0] != PEER_INFO_ADB_DEVICE_GUID)
        ) {
            throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        }
    }

    private fun writePacket(output: DataOutputStream, type: Byte, payload: ByteArray) {
        checkDeadline()
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readHeader(input: DataInputStream): Pair<Byte, Int> {
        checkDeadline()
        sslSocket?.soTimeout = readTimeout()
        val bytes = ByteArray(HEADER_SIZE)
        readFully(input, bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val size = buffer.int
        if (version != HEADER_VERSION ||
            (type != TYPE_SPAKE2 && type != TYPE_PEER_INFO) ||
            size !in 1..MAX_PAIRING_PAYLOAD
        ) {
            throw AdbPairingException(AdbPairingFailure.PROTOCOL)
        }
        return type to size
    }

    private fun readFully(input: DataInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            checkDeadline()
            sslSocket?.soTimeout = readTimeout()
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    private fun exportKeyingMaterial(tls: SSLSocket): ByteArray {
        val className = when {
            SslUtils.isCustomConscrypt() -> "org.conscrypt.Conscrypt"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "com.android.org.conscrypt.Conscrypt"
            else -> throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE)
        }
        val method = try {
            Class.forName(className).getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Integer.TYPE,
            )
        } catch (failure: Throwable) {
            throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE, failure)
        }
        return try {
            method.invoke(null, tls, EXPORTED_KEY_LABEL, null, EXPORT_KEY_SIZE) as ByteArray
        } catch (failure: Throwable) {
            throw AdbPairingException(
                if (cancelled) AdbPairingFailure.CANCELLED else AdbPairingFailure.TLS_UNAVAILABLE,
                failure,
            )
        }
    }

    private fun checkDeadline() {
        if (cancelled) throw AdbPairingException(AdbPairingFailure.CANCELLED)
        if (closed) throw AdbPairingException(AdbPairingFailure.CANCELLED)
        if (remainingMs() <= 0L) throw AdbPairingException(AdbPairingFailure.DEADLINE)
    }

    private fun remainingMs(): Long = deadlineAtMs - System.nanoTime() / 1_000_000L

    private fun readTimeout(): Int =
        minOf(readTimeoutMs.toLong(), remainingMs()).coerceIn(1L, readTimeoutMs.toLong()).toInt()

    private fun connectTimeout(): Int =
        minOf(CONNECT_TIMEOUT_MS.toLong(), remainingMs()).coerceIn(1L, CONNECT_TIMEOUT_MS.toLong()).toInt()

    private companion object {
        const val CODE_LENGTH = 6
        const val HEADER_SIZE = 6
        const val HEADER_VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_INFO_MAX = 1 shl 13
        const val PEER_INFO_ADB_RSA_PUB_KEY: Byte = 0
        const val PEER_INFO_ADB_DEVICE_GUID: Byte = 1
        const val MAX_PAIRING_PAYLOAD = 2 * PEER_INFO_MAX
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORT_KEY_SIZE = 64
        const val CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
        const val MAX_READ_TIMEOUT_MS = 15_000
    }
}

/** Fixed TLS guard failures. */
enum class AdbTlsGuardFailure {
    UNREACHABLE,
    DEADLINE,
    TLS_REQUIRED,
    PROTOCOL,
    CANCELLED,
}

class AdbTlsGuardException(
    val failure: AdbTlsGuardFailure,
    cause: Throwable? = null,
) : IOException("ADB TLS guard refused (${failure.name})", cause)

/**
 * TLS-only connection checks.
 *
 * A plaintext ADB daemon (for example a legacy port 5555) answers CNXN or AUTH without A_STLS.
 * [probeStls] refuses that before the app sends any identity material. [hasTls] checks the pinned
 * connection that is actually used, so a daemon that changes behaviour between connections is not
 * silently accepted either.
 */
object AndChamberAdbTls {
    /**
     * Sends one ADB CNXN to [port] and requires the first daemon packet to be A_STLS. The probe is
     * read-only and closes its socket. It does not replace [hasTls] on the real connection.
     */
    fun probeStls(
        host: String,
        port: Int,
        api: Int,
        timeoutMs: Int,
    ) {
        require(port in 1..65535) { "port" }
        val bounded = timeoutMs.coerceIn(1, 15_000)
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = bounded
            socket.connect(InetSocketAddress(host, port), bounded)
            val output = socket.getOutputStream()
            output.write(AdbProtocol.generateConnect(api))
            output.flush()

            val header = ByteArray(24)
            val input = socket.getInputStream()
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) throw AdbTlsGuardException(AdbTlsGuardFailure.PROTOCOL)
                offset += read
            }
            val command = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            when (command) {
                AdbProtocol.A_STLS -> Unit
                AdbProtocol.A_CNXN, AdbProtocol.A_AUTH ->
                    throw AdbTlsGuardException(AdbTlsGuardFailure.TLS_REQUIRED)
                else -> throw AdbTlsGuardException(AdbTlsGuardFailure.PROTOCOL)
            }
        } catch (failure: AdbTlsGuardException) {
            throw failure
        } catch (timeout: SocketTimeoutException) {
            throw AdbTlsGuardException(AdbTlsGuardFailure.DEADLINE, timeout)
        } catch (connect: ConnectException) {
            throw AdbTlsGuardException(AdbTlsGuardFailure.UNREACHABLE, connect)
        } catch (route: NoRouteToHostException) {
            throw AdbTlsGuardException(AdbTlsGuardFailure.UNREACHABLE, route)
        } catch (socketFailure: SocketException) {
            throw AdbTlsGuardException(AdbTlsGuardFailure.PROTOCOL, socketFailure)
        } catch (io: IOException) {
            throw AdbTlsGuardException(AdbTlsGuardFailure.PROTOCOL, io)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Whether the pinned [AdbConnection] upgraded to TLS before its CNXN completed. Fails closed:
     * if the field is missing in a future library version, the connection is refused.
     */
    fun hasTls(connection: AdbConnection): Boolean = runCatching {
        val field = AdbConnection::class.java.getDeclaredField("mIsTls")
        field.isAccessible = true
        field.getBoolean(connection)
    }.getOrDefault(false)

    /**
     * Clears LibADB's process-wide SSLContext cache and builds it for [privateKey]/[certificate].
     * The upstream cache keeps the first identity's client key, which would make a later identity
     * authenticate as the earlier one. Returns the prepared context.
     */
    fun prepareContext(
        privateKey: PrivateKey,
        certificate: Certificate,
    ): javax.net.ssl.SSLContext {
        runCatching {
            val field = SslUtils::class.java.getDeclaredField("sslContext")
            field.isAccessible = true
            field.set(null, null)
        }
        return SslUtils.getSslContext(KeyPair(privateKey, certificate))
    }
}
