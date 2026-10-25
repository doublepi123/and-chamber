package io.andchamber.app.phone.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.phone.core.PhoneAdmissionGuard
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.github.muntashirakon.adb.AdbPairingException
import io.github.muntashirakon.adb.AdbPairingFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real App-side P0: a fresh app identity pairs through the same transport the production driver
 * uses, a wrong code fails, the correct code succeeds, the connection must be TLS, and an identity
 * without a local pairing receipt is refused before it can reach adbd.
 *
 * Arguments (never committed, never logged):
 *   pairPort, pairCode, connectPort, optional pairHost (default 127.0.0.1)
 *
 * The pairing dialog must already be open on the dedicated device. This test does not open it and
 * does not accept any other dialog.
 */
@RunWith(AndroidJUnit4::class)
class AdbPairingP0InstrumentationTest {
    @Test
    fun wrongCodeFailsCorrectCodePairsAndTlsConnectWorks() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("pairHost") ?: "127.0.0.1"
        val pairPort = arguments.getString("pairPort")?.toIntOrNull()
        val connectPort = arguments.getString("connectPort")?.toIntOrNull()
        val code = arguments.getString("pairCode")?.trim()
        assumeTrue(
            "pairing instrumentation arguments are required for the device P0",
            pairPort != null &&
                connectPort != null &&
                code != null &&
                code.length == CODE_LENGTH &&
                code.all { it in '0'..'9' },
        )
        val targetPairPort = requireNotNull(pairPort)
        val targetConnectPort = requireNotNull(connectPort)
        val targetCode = requireNotNull(code)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val created = TestAdbIdentity.create(context)
        val service = AdbPhoneDriverService(
            identity = created.identity,
            runtimeId = "runtime-probe",
        )
        try {
            val wrongCode = wrongCode(targetCode)
            service.configurePairing(host, targetPairPort)
            val wrongFailure = runCatching {
                service.pair(wrongCode, PAIRING_DEADLINE_MS)
            }.exceptionOrNull()
            check(wrongFailure is AdbPairingException &&
                wrongFailure.failure == AdbPairingFailure.WRONG_CODE
            ) {
                "wrong pairing code was not rejected as WRONG_CODE: " +
                    (wrongFailure as? AdbPairingException)?.failure?.name
            }
            check(!service.hasPairingReceipt(host)) { "a failed pairing recorded a receipt" }
            println("check=wrong_code result=pass")

            // The device keeps the pairing dialog open after a wrong code; the same code and port
            // are reused for the real attempt, as the host adb probe did.
            Thread.sleep(300L)

            service.pair(targetCode.toCharArray(), PAIRING_DEADLINE_MS)
            check(service.hasPairingReceipt(host)) { "successful pairing did not record a receipt" }
            println("check=correct_code result=pass")

            service.configure(host, targetConnectPort)
            val driver = service
            val connection = runBlocking { driver.connect(CONNECT_DEADLINE_MS) }
            check(connection.runtimeId == "runtime-probe") { "connection runtime id" }
            check(service.transportSecurity == AdbTransportSecurity.TLS) {
                "connection was not verified as TLS"
            }
            println("check=tls_connect result=pass")

            val second = TestAdbIdentity.create(context)
            try {
                val unpaired = AdbPhoneDriverService(
                    identity = second.identity,
                    runtimeId = "runtime-probe-2",
                )
                unpaired.configure(host, targetConnectPort)
                val unpairedFailure = runCatching {
                    runBlocking { unpaired.connect(5_000L) }
                }.exceptionOrNull()
                check(unpairedFailure is PhoneDriverException &&
                    unpairedFailure.failure == PhoneDriverFailure.POLICY_DENIED
                ) {
                    "an unpaired identity was not refused: " +
                        (unpairedFailure as? PhoneDriverException)?.failure?.name
                }
                println("check=unpaired_gate result=pass")

                // Raw adbd diagnostic: on a ro.adb.secure=0 image adbd accepts an unpaired key.
                // This is only meaningful when the daemon enforces authorization, so the property
                // is a hard precondition and an accepted key is a hard failure, never a println.
                val secure = readRoAdbSecure()
                check(secure == "1") {
                    "ro.adb.secure must be 1 for the unpaired adbd check; got ${secure ?: "unreadable"}"
                }
                println("check=secure1 result=pass")

                var rawConnected = false
                val rawAttempt = runCatching {
                    io.github.muntashirakon.adb.AndChamberAdbTls.prepareContext(
                        second.identity.privateKey,
                        second.identity.certificate,
                    )
                    val raw = io.github.muntashirakon.adb.AdbConnection.Builder(
                        host,
                        targetConnectPort,
                    )
                        .setApi(android.os.Build.VERSION.SDK_INT)
                        .setPrivateKey(second.identity.privateKey)
                        .setCertificate(second.identity.certificate)
                        .setDeviceName(second.identity.deviceName)
                        .build()
                    try {
                        rawConnected = raw.connect(
                            5_000L,
                            java.util.concurrent.TimeUnit.MILLISECONDS,
                            true,
                        ) && io.github.muntashirakon.adb.AndChamberAdbTls.hasTls(raw)
                    } finally {
                        runCatching { raw.close() }
                    }
                }
                check(!rawConnected) {
                    "adbd accepted an unpaired fresh key; the secure precondition is not effective"
                }
                println(
                    "check=raw_adbd_unpaired result=pass attempt=" +
                        (rawAttempt.exceptionOrNull()?.javaClass?.simpleName ?: "connection_refused"),
                )
            } finally {
                TestAdbIdentity.delete(second.alias)
            }

            // Wiring smoke: the same connection observes and captures the real foreground window.
            val snapshot = runBlocking { driver.observe(CONNECT_DEADLINE_MS) }
            check(snapshot.packageName.isNotBlank()) { "observe returned no package" }
            check(snapshot.nodes.size <= 500) { "observe exceeded its node cap" }
            check(snapshot.refs.keys == snapshot.nodes.map { it.ref }.toSet()) {
                "observe refs are not bound to the nodes"
            }
            check(AdbWindowWitness.parse(snapshot.windowId) != null) {
                "observe windowId is not a window+content witness"
            }
            println("check=observe result=pass")
            println("check=window_identity result=pass")

            val binding = PhoneObservationBinding(
                observationId = "probe",
                generation = 1L,
                packageName = snapshot.packageName,
                windowId = snapshot.windowId,
                rotation = snapshot.geometry.rotation,
                geometry = snapshot.geometry,
            )
            val image = runBlocking { driver.capture(binding, CONNECT_DEADLINE_MS) }
            check(image.mimeType == "image/png") { "capture mime type" }
            check(image.width == snapshot.geometry.displayWidth &&
                image.height == snapshot.geometry.displayHeight
            ) {
                "capture geometry does not match the observation"
            }
            println("check=capture result=pass")

            // Real production-driver guard path: the live preflight passes, then a controller-like
            // deny guard prevents the injection. Nothing is touched, so this is NotSent.
            val denied = runBlocking {
                driver.dispatch(
                    PhoneDispatchRequest(
                        binding = binding,
                        action = PhoneDriverAction.Tap(io.andchamber.app.phone.core.PhonePoint(1, 1)),
                        guard = PhoneAdmissionGuard {
                            io.andchamber.app.phone.core.PhoneErrorCode.CANCELLED
                        },
                    ),
                    CONNECT_DEADLINE_MS,
                )
            }
            check(denied is PhoneDispatchReceipt.NotSent &&
                denied.failure == PhoneDriverFailure.ADMISSION_REFUSED
            ) {
                "guard refusal was not NotSent(ADMISSION_REFUSED): $denied"
            }
            println("check=guard_denied_before_inject result=pass")

            // Real dispatch through the fixed schema: one Back key event on the observed window.
            // This validates the OPEN-only injection path; it is the only mutation in the P0.
            val receipt = runBlocking {
                driver.dispatch(
                    io.andchamber.app.phone.core.PhoneDispatchRequest(
                        binding = binding,
                        action = io.andchamber.app.phone.core.PhoneDriverAction.Back,
                        // The probe bypasses the controller, so it supplies the allow guard the
                        // controller would otherwise issue. The refusal path is unit-tested.
                        guard = PhoneAdmissionGuard { null },
                    ),
                    CONNECT_DEADLINE_MS,
                )
            }
            check(receipt is io.andchamber.app.phone.core.PhoneDispatchReceipt.Sent) {
                "dispatch was not sent: $receipt"
            }
            println("check=dispatch_back result=pass")
        } finally {
            runBlocking { runCatching { service.disconnect(CONNECT_DEADLINE_MS) } }
            TestAdbIdentity.delete(created.alias)
        }
    }

    private fun readRoAdbSecure(): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.adb.secure"))
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        value
    }.getOrNull()

    private fun wrongCode(code: String): CharArray = CharArray(code.length) { index ->
        val digit = code[index] - '0'
        ('0' + ((digit + 1) % 10))
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val PAIRING_DEADLINE_MS = 15_000L
        const val CONNECT_DEADLINE_MS = 15_000L
    }
}
