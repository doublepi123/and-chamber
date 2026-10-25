package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneAdmissionGuard
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhonePoint
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPhoneDriverTest {
    private val geometry = PhoneGeometry(1080, 2400, 0)
    private val focus = AdbFocusedWindow(
        currentFocusToken = "7068ee4",
        currentFocusPackage = "com.example.app",
        currentFocusActivity = "MainActivity",
        focusedAppToken = "312191b",
        focusedAppPackage = "com.example.app",
        focusedAppActivity = "MainActivity",
        focusedAppTask = "16",
    )
    private val defaultWindowState = windowState()

    private val editableHierarchy = """
        <hierarchy rotation="0">
          <node class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1080,2400]">
            <node class="android.widget.EditText" package="com.example.app" text="Name" bounds="[100,100][900,200]" focused="true" clickable="true" />
            <node class="android.widget.EditText" package="com.example.app" text="PIN" bounds="[100,300][900,400]" password="true" />
            <node class="android.widget.Button" package="com.example.app" content-desc="Send" bounds="[100,500][300,600]" clickable="true" />
          </node>
        </hierarchy>
    """.trimIndent()

    private val binding by lazy {
        val snapshot = AdbSnapshotBuilder.build(
            AdbHierarchyParser.parse(editableHierarchy.toByteArray()),
            geometry,
            focus,
        )
        PhoneObservationBinding(
            observationId = "obs",
            generation = 1,
            packageName = snapshot.packageName,
            windowId = snapshot.windowId,
            rotation = 0,
            geometry = geometry,
        )
    }
    private val allowGuard = PhoneAdmissionGuard { null }

    @Test
    fun observeReturnsBoundedSnapshotWithGeometryAndWindowId() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val driver = driver(device)
        val snapshot = driver.observe(5_000)
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals(geometry, snapshot.geometry)
        assertEquals(binding.windowId, snapshot.windowId)
        assertEquals(4, snapshot.nodes.size)
        assertEquals(snapshot.nodes.map { it.ref }.toSet(), snapshot.refs.keys)
    }

    @Test
    fun observeRejectsAWindowSwitchDuringTheRead() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                defaultWindowState,
                windowState(focusToken = "9999999"),
            ),
        )
        val failure = runCatching { driver(device).observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun tapSwipeAndBackUseTheFixedSchema() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val driver = driver(device)

        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 150))), 5_000),
        )
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(
                request(
                    PhoneDriverAction.Swipe(PhonePoint(10, 10), PhonePoint(10, 500), 250),
                ),
                5_000,
            ),
        )
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Back), 5_000),
        )
        assertEquals(
            listOf(
                AdbInputCommand.Tap(150, 150),
                AdbInputCommand.Swipe(10, 10, 10, 500, 250),
                AdbInputCommand.Back,
            ),
            device.injected,
        )
    }

    @Test
    fun changedPackageOrGeometryIsRefusedBeforeInjection() = runBlocking {
        val otherPackage = FakeDevice(
            editableHierarchy.replace("com.example.app", "com.other.app").toByteArray(),
            windowStates = listOf(windowState(pkg = "com.other.app")),
        )
        assertNotSent(
            PhoneDriverFailure.TARGET_CHANGED,
            driver(otherPackage).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertTrue(otherPackage.injected.isEmpty())

        val otherSize = FakeDevice(editableHierarchy.toByteArray(), size = "Physical size: 1080x2000")
        assertNotSent(
            PhoneDriverFailure.GEOMETRY_CHANGED,
            driver(otherSize).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertTrue(otherSize.injected.isEmpty())
    }

    @Test
    fun staleWindowIdIsRefused() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val stale = binding.copy(windowId = "other-window")
        assertNotSent(
            PhoneDriverFailure.WINDOW_CHANGED,
            driver(device).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150)), stale),
                5_000,
            ),
        )
    }

    @Test
    fun samePackageNewWindowIsRefused() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(windowState(focusToken = "9999999")),
        )
        assertNotSent(
            PhoneDriverFailure.WINDOW_CHANGED,
            driver(device).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun samePackageMovedElementInvalidatesTheBinding() = runBlocking {
        val moved = editableHierarchy.replace(
            "bounds=\"[100,100][900,200]\"",
            "bounds=\"[100,120][900,220]\"",
        )
        val device = FakeDevice(moved.toByteArray())
        assertNotSent(
            PhoneDriverFailure.WINDOW_CHANGED,
            driver(device).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
    }

    @Test
    fun captureRefusesADifferentGeometry() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            capture = png(1080, 2000),
        )
        val failure = runCatching { driver(device).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.GEOMETRY_CHANGED,
            (failure as PhoneDriverException).failure,
        )

        val good = FakeDevice(editableHierarchy.toByteArray(), capture = png(1080, 2400))
        val image = driver(good).capture(binding, 5_000)
        assertEquals(1080, image.width)
        assertEquals(2400, image.height)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun captureDiscardsAnImageWhenTheWindowChangesDuringTheCapture() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                defaultWindowState,
                defaultWindowState,
                windowState(focusToken = "9999999"),
                windowState(focusToken = "9999999"),
            ),
        )
        val failure = runCatching { driver(device).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun textRefusesUnicodeAndPercentInsteadOfFakingSuccess() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val driver = driver(device)
        assertNotSent(
            PhoneDriverFailure.UNSUPPORTED,
            driver.dispatch(request(PhoneDriverAction.Input("中文")), 5_000),
        )
        assertNotSent(
            PhoneDriverFailure.UNSUPPORTED,
            driver.dispatch(request(PhoneDriverAction.Input("100%")), 5_000),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun inputFocusMustBeAnEditableNonPasswordField() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val driver = driver(device)

        assertNotSent(
            PhoneDriverFailure.UNSUPPORTED,
            driver.dispatch(
                request(PhoneDriverAction.Input("secret", focus = PhonePoint(150, 350))),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())

        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(
                request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertEquals(
            listOf(AdbInputCommand.Tap(150, 150), AdbInputCommand.Text("hello")),
            device.injected,
        )
    }

    @Test
    fun inputFollowupRejectsAFieldThatBecameUnsafe() = runBlocking {
        val noFocus = editableHierarchy.replace("focused=\"true\"", "focused=\"false\"")
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            hierarchies = listOf(
                editableHierarchy.toByteArray(),
                noFocus.toByteArray(),
            ),
        )
        val receipt = driver(device).dispatch(
            request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150))),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.UNSUPPORTED, receipt)
        assertEquals(listOf(AdbInputCommand.Tap(150, 150)), device.injected)
    }

    @Test
    fun inputFollowupRejectsAWindowChangeAfterTheFocusTap() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                defaultWindowState,
                defaultWindowState,
                windowState(focusToken = "9999999"),
                windowState(focusToken = "9999999"),
            ),
        )
        val receipt = driver(device).dispatch(
            request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150))),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.WINDOW_CHANGED, receipt)
        assertEquals(listOf(AdbInputCommand.Tap(150, 150)), device.injected)
    }

    @Test
    fun inputWithoutFocusRequiresAFocusedEditableNode() = runBlocking {
        val noFocus = editableHierarchy.replace("focused=\"true\"", "focused=\"false\"")
        val device = FakeDevice(noFocus.toByteArray())
        val noFocusSnapshot = AdbSnapshotBuilder.build(
            AdbHierarchyParser.parse(noFocus.toByteArray()),
            geometry,
            focus,
        )
        assertNotSent(
            PhoneDriverFailure.UNSUPPORTED,
            driver(device).dispatch(
                PhoneDispatchRequest(
                    binding.copy(windowId = noFocusSnapshot.windowId),
                    PhoneDriverAction.Input("hello"),
                    allowGuard,
                ),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun admissionGuardDeniesBeforeTheFirstInjection() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val receipt = driver(device).dispatch(
            request(
                PhoneDriverAction.Tap(PhonePoint(150, 150)),
                guard = PhoneAdmissionGuard { PhoneErrorCode.CANCELLED },
            ),
            5_000,
        )
        assertNotSent(PhoneDriverFailure.ADMISSION_REFUSED, receipt)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun admissionGuardIsNotConsultedWhenTheLivePreflightRefuses() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.replace("com.example.app", "com.other.app").toByteArray(),
            windowStates = listOf(windowState(pkg = "com.other.app")),
        )
        var checks = 0
        val guard = PhoneAdmissionGuard {
            checks += 1
            null
        }
        assertNotSent(
            PhoneDriverFailure.TARGET_CHANGED,
            driver(device).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150)), guard = guard),
                5_000,
            ),
        )
        assertEquals(0, checks)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun defaultDenyGuardRefusesWhenNoGuardIsSupplied() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val receipt = driver(device).dispatch(
            PhoneDispatchRequest(binding, PhoneDriverAction.Tap(PhonePoint(150, 150))),
            5_000,
        )
        assertNotSent(PhoneDriverFailure.ADMISSION_REFUSED, receipt)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun guardExceptionFailsClosed() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val receipt = driver(device).dispatch(
            request(
                PhoneDriverAction.Tap(PhonePoint(150, 150)),
                guard = PhoneAdmissionGuard { error("guard failed") },
            ),
            5_000,
        )
        assertNotSent(PhoneDriverFailure.ADMISSION_REFUSED, receipt)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun admissionGuardAfterTheFocusTapIsUnknown() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        var checks = 0
        val guard = PhoneAdmissionGuard {
            checks += 1
            if (checks == 1) null else PhoneErrorCode.PAUSED
        }
        val receipt = driver(device).dispatch(
            request(
                PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150)),
                guard = guard,
            ),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.ADMISSION_REFUSED, receipt)
        assertEquals(listOf(AdbInputCommand.Tap(150, 150)), device.injected)
        assertEquals(2, checks)
    }

    @Test
    fun failureBeforeTheWriteIsNotSentAndAfterTheWriteIsUnknown() = runBlocking {
        val before = FakeDevice(
            editableHierarchy.toByteArray(),
            injectFailure = AdbDeviceException(AdbDeviceFailure.DRIVER, afterWrite = false),
        )
        assertNotSent(
            PhoneDriverFailure.DRIVER,
            driver(before).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )

        val after = FakeDevice(
            editableHierarchy.toByteArray(),
            injectFailure = AdbDeviceException(AdbDeviceFailure.DRIVER, afterWrite = true),
        )
        assertUnknown(
            PhoneDriverFailure.DRIVER,
            driver(after).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )

        val secondFails = FakeDevice(
            editableHierarchy.toByteArray(),
            injectFailure = AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite = true),
            failOnInjection = 2,
        )
        val receipt = driver(secondFails).dispatch(
            request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150))),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.TIMEOUT, receipt)
    }

    @Test
    fun connectionLossAfterOpenIsUnknownOutcomeNotSent() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            injectFailure = AdbDeviceException(
                AdbDeviceFailure.OUTCOME_UNKNOWN,
                afterWrite = true,
            ),
        )
        val receipt = driver(device).dispatch(
            request(PhoneDriverAction.Back),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.OUTCOME_UNKNOWN, receipt)
    }

    @Test
    fun expiredCallDeadlineNeverInjects() = runBlocking {
        val device = FakeDevice(editableHierarchy.toByteArray())
        var calls = 0
        val advancingClock = {
            // The first read is the call start; every later read has already passed the budget.
            if (calls++ == 0) 0L else 6_000L
        }
        val driver = AdbPhoneDriver(
            device = device,
            runtimeId = "runtime-1",
            clock = advancingClock,
            platformSupported = true,
        )
        assertNotSent(
            PhoneDriverFailure.TIMEOUT,
            driver.dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun connectRequiresAConfirmedTlsTransport() = runBlocking {
        val tlsDevice = FakeDevice(editableHierarchy.toByteArray())
        val connection: PhoneConnection = driver(tlsDevice).connect(5_000)
        assertEquals("runtime-1", connection.runtimeId)

        val plaintext = FakeDevice(
            editableHierarchy.toByteArray(),
            security = AdbTransportSecurity.PLAINTEXT,
        )
        val failure = runCatching { driver(plaintext).connect(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun supportedCapabilitiesAreExplicitAndUnicodeIsFalse() {
        val driver = driver(FakeDevice(editableHierarchy.toByteArray()))
        val caps = driver.capabilities()
        assertTrue(caps.observe)
        assertTrue(caps.capture)
        assertTrue(caps.tap)
        assertTrue(caps.swipe)
        assertTrue(caps.input)
        assertTrue(caps.back)
        assertFalse(caps.unicodeInput)
        assertFalse(caps.supportsUnicodeInput())

        val kind = driver.capabilityReport().windowIdKind
        assertTrue(kind.contains("window"))
        assertTrue(kind.contains("content"))
        assertFalse(kind.contains("package+rotation+display"))
    }

    @Test
    fun unsupportedPlatformReportsNoCapabilities() {
        val device = FakeDevice(editableHierarchy.toByteArray())
        val driver = AdbPhoneDriver(
            device = device,
            runtimeId = "runtime-1",
            platformSupported = false,
        )
        val report = driver.capabilityReport()
        assertTrue(!report.phone.tap)
        assertTrue(!report.phone.input)
        assertTrue(!report.phone.observe)
        assertTrue(!report.phone.capture)
        assertTrue(report.inputAsciiOnly)
        assertTrue(!report.unicodeInputSupported)
        assertTrue(!report.submitSupported)
        assertEquals(AdbCommandSchema.MAX_INPUT_CHARS, report.inputMaxChars)
    }

    private fun driver(device: FakeDevice): AdbPhoneDriver = AdbPhoneDriver(
        device = device,
        runtimeId = "runtime-1",
        platformSupported = true,
    )

    private fun request(
        action: PhoneDriverAction,
        binding: PhoneObservationBinding = this.binding,
        guard: PhoneAdmissionGuard = allowGuard,
    ): PhoneDispatchRequest = PhoneDispatchRequest(binding, action, guard)

    private fun windowState(
        pkg: String = "com.example.app",
        activity: String = "MainActivity",
        focusToken: String = "7068ee4",
        appToken: String = "312191b",
    ): String = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=Window{$focusToken u0 $pkg/$activity}
          mFocusedApp=ActivityRecord{$appToken u0 $pkg/$activity t16}
    """.trimIndent()

    private fun assertNotSent(expected: PhoneDriverFailure, receipt: PhoneDispatchReceipt) {
        assertTrue("expected NotSent but was $receipt", receipt is PhoneDispatchReceipt.NotSent)
        assertEquals(expected, (receipt as PhoneDispatchReceipt.NotSent).failure)
    }

    private fun assertUnknown(expected: PhoneDriverFailure, receipt: PhoneDispatchReceipt) {
        assertTrue("expected Unknown but was $receipt", receipt is PhoneDispatchReceipt.Unknown)
        assertEquals(expected, (receipt as PhoneDispatchReceipt.Unknown).failure)
    }

    private class FakeDevice(
        private val hierarchy: ByteArray,
        private val size: String = "Physical size: 1080x2400",
        private val capture: ByteArray = AdbPhoneDriverTest.png(1080, 2400),
        security: AdbTransportSecurity = AdbTransportSecurity.TLS,
        private val injectFailure: AdbDeviceException? = null,
        private val failOnInjection: Int = 0,
        private val windowStates: List<String>? = null,
        private val hierarchies: List<ByteArray>? = null,
    ) : AdbDevice {
        override var transportSecurity: AdbTransportSecurity = security
            private set

        val injected = mutableListOf<AdbInputCommand>()
        private var injectionCount = 0
        private var windowStateCount = 0
        private var hierarchyCount = 0

        override fun connect(deadlineMs: Long) = Unit

        override fun disconnect() = Unit

        override fun isConnected(): Boolean = true

        override fun hierarchyXml(deadlineMs: Long, maxBytes: Int): ByteArray {
            val values = hierarchies ?: listOf(hierarchy)
            return values[minOf(hierarchyCount++, values.lastIndex)]
        }

        override fun windowStateText(deadlineMs: Long, maxBytes: Int): String {
            val values = windowStates ?: listOf(defaultWindowState)
            return values[minOf(windowStateCount++, values.lastIndex)]
        }

        override fun displaySizeText(deadlineMs: Long, maxBytes: Int): String = size

        override fun capturePng(deadlineMs: Long, maxBytes: Int): ByteArray = capture

        override fun inject(command: AdbInputCommand, deadlineMs: Long): AdbInjection {
            injectionCount += 1
            if (injectFailure != null && (failOnInjection == 0 || failOnInjection == injectionCount)) {
                throw injectFailure
            }
            injected.add(command)
            return AdbInjection(written = true)
        }

        private val defaultWindowState: String
            get() = """
                WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
                  mCurrentFocus=Window{7068ee4 u0 com.example.app/MainActivity}
                  mFocusedApp=ActivityRecord{312191b u0 com.example.app/MainActivity t16}
            """.trimIndent()
    }

    private companion object {
        fun png(width: Int, height: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                ),
            )
            out.write(intBytes(13))
            out.write("IHDR".toByteArray())
            out.write(intBytes(width))
            out.write(intBytes(height))
            out.write(byteArrayOf(8, 6, 0, 0, 0))
            out.write(intBytes(0))
            out.write(intBytes(0))
            out.write("IEND".toByteArray())
            out.write(byteArrayOf(0xae.toByte(), 0x42, 0x60, 0x82.toByte()))
            return out.toByteArray()
        }

        fun intBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }
}
