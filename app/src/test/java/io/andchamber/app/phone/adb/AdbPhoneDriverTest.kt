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
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import io.andchamber.app.phone.overlay.safety.OverlayExpectation
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
    fun overlayVisibleAllowsOutsideActionAndRefusesOwnRects() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val snapshot = driver.observe(5_000)
        val binding = bindingFor(snapshot)
        assertTrue(overlay.hiddenCalls >= 1)
        assertTrue(overlay.restoreCalls >= 1)

        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(
                request(
                    PhoneDriverAction.Swipe(PhonePoint(0, 480), PhonePoint(500, 480), 200),
                    binding,
                ),
                5_000,
            ),
        )
        assertEquals(listOf(AdbInputCommand.Tap(50, 50)), device.injected)
    }

    @Test
    fun overlayRevisionChangeInvalidatesTheOldObservation() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        overlay.revision += 1
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun overlayThatBecomesHiddenIsRefusedForObserveAndDispatch() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        overlay.visible = false
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        val failure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun overlayCaptureHidesTheWindowAndALyingBarrierIsRefused() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        val image = driver.capture(binding, 5_000)
        assertEquals(1080, image.width)
        assertEquals(2400, image.height)
        assertTrue(overlay.hiddenCalls >= 2)

        overlay.honest = false
        val failure = runCatching { driver.capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun overlayInputFocusInsideItsRectIsRefusedBeforeInjection() = runBlocking {
        val overlay = FakeOverlay(bounds = PhoneRect(100, 100, 300, 200))
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(
                request(
                    PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150)),
                    binding,
                ),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun notificationModeKeepsTheOriginalPathEvenWhenTheOverlayIsUnavailable() = runBlocking {
        val overlay = FakeOverlay().apply {
            required = false
            visible = false
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val snapshot = driver.observe(5_000)
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals(0, overlay.hiddenCalls)
        assertEquals(0, overlay.restoreCalls)

        val binding = bindingFor(snapshot)
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertEquals(listOf(AdbInputCommand.Tap(50, 50)), device.injected)
        assertEquals(1080, driver.capture(binding, 5_000).width)
        assertEquals(0, overlay.hiddenCalls)
        assertEquals("notification mode must not read the overlay claim", 0, overlay.expectationCalls)
    }

    @Test
    fun overlayModeWithAnUnavailableOverlayRefusesWithoutFallback() = runBlocking {
        val overlay = FakeOverlay().apply {
            required = true
            visible = false
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val observeFailure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (observeFailure as PhoneDriverException).failure,
        )
        // A dispatch under overlay mode has no stored observation: refuse, never fall back.
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun modeFlipBeforeInjectionFailsClosedAndDoesInjectsNothing() = runBlocking {
        val overlay = FakeOverlay().apply {
            required = false
            visible = false
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        // Dispatch samples the mode once (false), then the pre-injection re-check flips it (true).
        val flipAt = overlay.requiredCalls + 2
        overlay.onRequired = { call -> if (call == flipAt) overlay.required = true }
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue("no injection may happen after a mode change", device.injected.isEmpty())
    }

    @Test
    fun anObservationFromOverlayModeTaintsNotificationDispatchUntilFreshObserve() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        device.injected.clear()

        overlay.required = false
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue(device.injected.isEmpty())

        // A fresh notification-mode read clears the overlay binding for that window.
        driver.observe(5_000)
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
    }

    @Test
    fun anOverlayReadThatLosesTheModeDiscardsTheSnapshot() = runBlocking {
        val overlay = FakeOverlay().apply {
            required = true
            onRequired = { call -> if (call == 2) required = false }
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val failure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
        assertTrue(overlay.restoreCalls >= 1)
    }

    @Test
    fun aThrowingModeGateIsTreatedAsOverlayRequired() = runBlocking {
        val overlay = FakeOverlay().apply {
            requiredThrows = true
            visible = false
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val failure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun aModeFlipWhileReadingDiscardsTheContent() = runBlocking {
        val overlay = FakeOverlay().apply {
            required = false
            visible = false
            onRequired = { call -> if (call == 2) required = true }
        }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val observeFailure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (observeFailure as PhoneDriverException).failure,
        )

        val captureFailure = runCatching { driver.capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (captureFailure as PhoneDriverException).failure,
        )
    }

    @Test
    fun aRevisionAndMoveAfterTheWitnessReadSendsNothing() = runBlocking {
        val overlay = FakeOverlay(bounds = PhoneRect(10, 10, 20, 20))
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        var fired = false
        device.onWindowDetail = {
            if (!fired) {
                fired = true
                // The detail dump already returned the old frame; the manager now moves the overlay
                // over the tap point and bumps its revision.
                overlay.revision += 1
                overlay.bounds = PhoneRect(0, 0, 1080, 2400)
            }
        }
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue("the old WM witness must not authorize an injection", device.injected.isEmpty())
    }

    @Test
    fun aGenerationChangeAfterTheWitnessReadSendsNothing() = runBlocking {
        val overlay = FakeOverlay(bounds = PhoneRect(10, 10, 20, 20))
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        var fired = false
        device.onWindowDetail = {
            if (!fired) {
                fired = true
                overlay.generation += 1
            }
        }
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun aRevisionChangeAfterTheFocusTapMakesTheTextStepUnknown() = runBlocking {
        // Start away from the focus point so the focus tap itself is allowed; the overlay only
        // moves over the field after that tap, before the text step's final guard.
        val overlay = FakeOverlay(bounds = PhoneRect(10, 10, 20, 20))
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        var detailCalls = 0
        device.onWindowDetail = {
            detailCalls += 1
            if (detailCalls == 2) {
                // The text step re-validated against the old frame; the overlay now revises.
                overlay.revision += 1
                overlay.bounds = PhoneRect(0, 0, 1080, 2400)
            }
        }
        val receipt = driver.dispatch(
            request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150)), binding),
            5_000,
        )
        assertTrue("expected Unknown after the focus tap, was $receipt", receipt is PhoneDispatchReceipt.Unknown)
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (receipt as PhoneDispatchReceipt.Unknown).failure,
        )
        assertEquals(listOf(AdbInputCommand.Tap(150, 150)), device.injected)
    }

    private fun bindingFor(snapshot: io.andchamber.app.phone.core.PhoneSnapshot) =
        PhoneObservationBinding(
            observationId = "obs-overlay",
            generation = 1,
            packageName = snapshot.packageName,
            windowId = snapshot.windowId,
            rotation = snapshot.geometry.rotation,
            geometry = snapshot.geometry,
        )

    private fun overlayDriver(device: FakeDevice, overlay: FakeOverlay) = AdbPhoneDriver(
        device = device,
        runtimeId = "runtime-1",
        clock = { 0L },
        platformSupported = true,
        overlayBarrier = overlay,
    )

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
    fun hierarchyIdleFailureIsTypedAndReturnsNoSnapshot() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            hierarchyFailure = AdbDeviceException(AdbDeviceFailure.HIERARCHY_NOT_IDLE),
        )
        val failure = runCatching { driver(device).observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.HIERARCHY_NOT_IDLE,
            (failure as PhoneDriverException).failure,
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun aReadTimeoutOnObserveOrCaptureReturnsNoPartialResult() = runBlocking {
        val observeDevice = FakeDevice(
            editableHierarchy.toByteArray(),
            hierarchyFailure = AdbDeviceException(AdbDeviceFailure.READ_TIMEOUT),
        )
        val observeFailure =
            runCatching { driver(observeDevice).observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.READ_TIMEOUT,
            (observeFailure as PhoneDriverException).failure,
        )

        val captureDevice = FakeDevice(
            editableHierarchy.toByteArray(),
            captureFailure = AdbDeviceException(AdbDeviceFailure.READ_TIMEOUT),
        )
        val captureFailure =
            runCatching { driver(captureDevice).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.READ_TIMEOUT,
            (captureFailure as PhoneDriverException).failure,
        )
    }

    @Test
    fun anUnconfirmableCaptureIsNotReturnedAsAnImage() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            captureFailure = AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN),
        )
        val failure =
            runCatching { driver(device).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.OUTCOME_UNKNOWN,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun aReadTimeoutBeforeTheWriteSendsNothing() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            hierarchyFailure = AdbDeviceException(AdbDeviceFailure.READ_TIMEOUT),
        )
        assertNotSent(
            PhoneDriverFailure.READ_TIMEOUT,
            driver(device).dispatch(
                request(PhoneDriverAction.Tap(PhonePoint(150, 150))),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun aReadTimeoutAfterTheFocusTapIsUnknownNotSent() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            hierarchyFailure = AdbDeviceException(AdbDeviceFailure.READ_TIMEOUT),
            failOnHierarchy = 2,
        )
        val receipt = driver(device).dispatch(
            request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150))),
            5_000,
        )
        assertUnknown(PhoneDriverFailure.READ_TIMEOUT, receipt)
        assertEquals(listOf(AdbInputCommand.Tap(150, 150)), device.injected)
    }

    @Test
    fun anInjectDeadlineAfterTheWriteStaysUnknownTimeout() = runBlocking {
        // Mutation semantics stay unchanged by the new read error: a deadline after a possible
        // write is still UNKNOWN(DEADLINE), never READ_TIMEOUT.
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            injectFailure = AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite = true),
        )
        assertUnknown(
            PhoneDriverFailure.TIMEOUT,
            driver(device).dispatch(request(PhoneDriverAction.Back), 5_000),
        )
    }

    @Test
    fun nullFocusDuringStartupIsWindowChangedWithoutASnapshot() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(nullFocusWindowState()),
        )
        val failure = runCatching { driver(device).observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (failure as PhoneDriverException).failure,
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun nullFocusedAppDuringStartupIsWindowChangedWithoutASnapshot() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(nullAppWindowState()),
        )
        val failure = runCatching { driver(device).observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun nullFocusBeforeInjectionIsNotSentWindowChanged() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(nullFocusWindowState()),
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
    fun nullFocusAfterTheFocusTapIsUnknownNotSent() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                defaultWindowState,
                defaultWindowState,
                nullFocusWindowState(),
                nullFocusWindowState(),
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
    fun nullFocusBeforeOrAfterPixelsAreCopiedDiscardsTheCapture() = runBlocking {
        val before = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(nullFocusWindowState()),
        )
        val beforeFailure =
            runCatching { driver(before).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (beforeFailure as PhoneDriverException).failure,
        )

        val after = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                defaultWindowState,
                defaultWindowState,
                nullFocusWindowState(),
                nullFocusWindowState(),
            ),
        )
        val afterFailure =
            runCatching { driver(after).capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (afterFailure as PhoneDriverException).failure,
        )
    }

    @Test
    fun aFreshWindowAfterANullTransitionObservesNormally() = runBlocking {
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStates = listOf(
                nullFocusWindowState(),
                defaultWindowState,
                defaultWindowState,
                defaultWindowState,
            ),
        )
        val driver = driver(device)
        val first = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.WINDOW_CHANGED,
            (first as PhoneDriverException).failure,
        )

        val snapshot = driver.observe(5_000)
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals(binding.windowId, snapshot.windowId)
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

    /** The real API 35 startup transition: focus is explicitly null, the app is still present. */
    private fun nullFocusWindowState(): String = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=null
          mFocusedApp=ActivityRecord{312191b u0 com.example.app/MainActivity t16}
    """.trimIndent()

    private fun nullAppWindowState(): String = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=Window{7068ee4 u0 com.example.app/MainActivity}
          mFocusedApp=null
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
        private val windowStateProvider: (() -> String)? = null,
        private val windowDetailProvider: ((String) -> String)? = null,
        private val hierarchyFailure: AdbDeviceException? = null,
        private val failOnHierarchy: Int = 0,
        private val captureFailure: AdbDeviceException? = null,
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
            val call = hierarchyCount + 1
            hierarchyCount = call
            if (hierarchyFailure != null && (failOnHierarchy == 0 || failOnHierarchy == call)) {
                throw hierarchyFailure
            }
            val values = hierarchies ?: listOf(hierarchy)
            return values[minOf(call - 1, values.lastIndex)]
        }

        override fun windowStateText(deadlineMs: Long, maxBytes: Int): String {
            windowStateProvider?.let { return it() }
            val values = windowStates ?: listOf(defaultWindowState)
            return values[minOf(windowStateCount++, values.lastIndex)]
        }

        var onWindowDetail: ((String) -> Unit)? = null
        var detailCalls: Int = 0

        override fun windowDetailText(windowToken: String, deadlineMs: Long, maxBytes: Int): String {
            val value = windowDetailProvider?.invoke(windowToken)
                ?: windowStateProvider?.invoke()
                ?: (windowStates ?: listOf(defaultWindowState)).last()
            detailCalls += 1
            onWindowDetail?.invoke(windowToken)
            return value
        }

        override fun displaySizeText(deadlineMs: Long, maxBytes: Int): String = size

        override fun capturePng(deadlineMs: Long, maxBytes: Int): ByteArray {
            captureFailure?.let { throw it }
            return capture
        }

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

    private class FakeOverlay(
        var owner: String = "owner-a",
        var generation: Long = 3,
        var revision: Long = 7,
        var visible: Boolean = true,
        var honest: Boolean = true,
        // Production-style manager key: a stable LayoutParams.title, not a binder token.
        val key: String = "andchamber-phone-overlay:owner-a:3",
        var bounds: PhoneRect = PhoneRect(100, 400, 260, 560),
    ) : PhoneOverlayBarrier {
        var hiddenCalls = 0
        var restoreCalls = 0
        var required: Boolean = true
        var requiredThrows: Boolean = false
        var expectationCalls: Int = 0
        var requiredCalls: Int = 0
        var onRequired: ((Int) -> Unit)? = null

        override fun required(deadlineMs: Long): Boolean {
            requiredCalls += 1
            onRequired?.invoke(requiredCalls)
            if (requiredThrows) throw IllegalStateException("mode unavailable")
            return required
        }

        override fun expectation(deadlineMs: Long): OverlayExpectation {
            expectationCalls += 1
            return OverlayExpectation(
                owner = owner,
                generation = generation,
                revision = revision,
                phase = if (visible) OverlayPhase.VISIBLE else OverlayPhase.UNAVAILABLE,
                windowToken = key,
                freshnessDeadlineMs = deadlineMs + 60_000L,
            )
        }

        override suspend fun <T> withContentHidden(
            owner: String,
            generation: Long,
            revision: Long,
            phase: OverlayPhase,
            deadlineMs: Long,
            block: suspend () -> T,
        ): T {
            hiddenCalls += 1
            val previous = visible
            if (honest) visible = false
            try {
                return block()
            } finally {
                visible = previous
                restoreCalls += 1
            }
        }

        /** Full `dumpsys window` list: real API 35 has no frame here. */
        fun windowState(): String = buildString {
            append("mCurrentFocus=Window{7068ee4 u0 com.example.app/MainActivity}\n")
            append("mFocusedApp=ActivityRecord{312191b u0 com.example.app/MainActivity t16}\n")
            if (visible) append(windowSection(detail = false))
        }

        /** Targeted `dumpsys window <token>`: carries the real frame. */
        fun detailState(): String = buildString {
            append("WINDOW MANAGER WINDOWS (dumpsys window windows)\n")
            if (visible) append(windowSection(detail = true))
        }

        private fun windowSection(detail: Boolean): String = buildString {
            append("  Window #6 Window{abc123 u0 Sys2038:$key}:\n")
            append("    mDisplayId=0\n")
            append("    mOwnerUid=10210 showForAllUsers=false package=io.example.overlay appop=SYSTEM_ALERT_WINDOW\n")
            append("    mAttrs={(0,0)(160x160) gr=TOP START ty=APPLICATION_OVERLAY fmt=TRANSLUCENT\n")
            append("    Requested w=160 h=160 mLayoutSeq=1\n")
            append("    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false\n")
            if (detail) {
                append(
                    "    Frames: parent=[0,0][1080,2400] display=[0,0][1080,2400] " +
                        "frame=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
                        "last=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\n",
                )
            }
            append("    mViewVisibility=0x0 mHaveFrame=true mObscured=false\n")
            append("    isOnScreen=true\n")
            append("    isVisible=true\n")
        }
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
