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
    fun aTapByRefNeedsItsElementAtTheObservedBounds() = runBlocking {
        val moved = editableHierarchy.replace(
            "bounds=\"[100,100][900,200]\"",
            "bounds=\"[100,120][900,220]\"",
        )
        val device = FakeDevice(moved.toByteArray())
        assertNotSent(
            PhoneDriverFailure.WINDOW_CHANGED,
            driver(device).dispatch(
                request(
                    PhoneDriverAction.Tap(PhonePoint(500, 150), target = PhoneRect(100, 100, 900, 200)),
                ),
                5_000,
            ),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun ordinaryContentChangesInTheSameWindowDoNotBlockATap() = runBlocking {
        // A live page: a countdown label changed and an unrelated element moved, same real window.
        val live = editableHierarchy
            .replace("content-desc=\"Send\"", "content-desc=\"Send 00:59\"")
            .replace("bounds=\"[100,300][900,400]\"", "bounds=\"[100,320][900,420]\"")
        val device = FakeDevice(live.toByteArray())
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver(device).dispatch(
                request(
                    PhoneDriverAction.Tap(PhonePoint(200, 550), target = PhoneRect(100, 500, 300, 600)),
                ),
                5_000,
            ),
        )
        assertEquals(listOf(AdbInputCommand.Tap(200, 550)), device.injected)
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
        // Observe keeps the capsule visible: it is never the focused window, so its nodes cannot be
        // in the focused app's dump. Hiding for every snapshot only made it flicker.
        assertEquals(0, overlay.hiddenCalls)
        assertEquals(0, overlay.restoreCalls)

        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        // Hitting the visible capsule refuses only this action; it is not an overlay loss. The fake
        // manager does not move, so the make-way attempt ends in the same refusal.
        assertNotSent(
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertNotSent(
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
            driver.dispatch(
                request(
                    PhoneDriverAction.Swipe(PhonePoint(0, 480), PhonePoint(500, 480), 200),
                    binding,
                ),
                5_000,
            ),
        )
        assertEquals(listOf(AdbInputCommand.Tap(50, 50)), device.injected)
        assertEquals(2, overlay.makeWayCalls)
    }

    @Test
    fun aCosmeticOverlayRevisionDoesNotStopTheTask() = runBlocking {
        // A chat notice, expand/collapse or drag bumps the display revision. The real frame is
        // re-read for every action, so an outside tap still goes through.
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        overlay.revision += 1
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertEquals(listOf(AdbInputCommand.Tap(50, 50)), device.injected)
    }

    @Test
    fun aNewOverlayOwnerStillInvalidatesTheOldObservation() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        overlay.generation += 1
        assertNotSent(
            PhoneDriverFailure.POLICY_DENIED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(50, 50)), binding), 5_000),
        )
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun `a host webview page observes on the notification path`() = runBlocking {
        // Real probe evidence: AndChamber's own WebView chat page exposes a small, valid hierarchy
        // (8 nodes) with the host package, while the host target runs on the notification-only path.
        val host = "io.andchamber.app.phoneprobe"
        val webViewPage =
            buildString {
                append("""<hierarchy rotation="0">""")
                append("""<node class="android.webkit.WebView" package="$host" bounds="[0,0][1080,2400]">""")
                (1..7).forEach { i ->
                    append(
                        """<node class="android.view.View" package="$host" """ +
                            """content-desc="row $i" bounds="[0,${i * 100}][1080,${i * 100 + 80}]" />""",
                    )
                }
                append("</node></hierarchy>")
            }
        val overlay = FakeOverlay()
        overlay.required = false
        val device =
            FakeDevice(
                webViewPage.toByteArray(),
                windowStateProvider = {
                    windowState(pkg = host, activity = "io.andchamber.app.MainActivity")
                },
            )
        val snapshot = overlayDriver(device, overlay).observe(5_000)

        assertEquals(host, snapshot.packageName)
        assertEquals(8, snapshot.nodes.size)
        assertEquals(8, snapshot.refs.size)
        assertEquals(snapshot.nodes.map { it.ref }.toSet(), snapshot.refs.keys)
        assertTrue(snapshot.nodes.all { it.bounds.isWithin(snapshot.geometry) })
        assertEquals("notification mode never hides the overlay", 0, overlay.hiddenCalls)
    }

    @Test
    fun aTargetThatForceHidesOverlaysUsesTheNotificationStopEntry() = runBlocking {
        val overlay = FakeOverlay()
        // The manager still claims VISIBLE (it attached its surface) but the real dump is policy-hidden
        // by the focused target app.
        overlay.visible = false
        overlay.managerClaimsVisible = true
        overlay.forceHiddenBySystem = true
        overlay.liveTarget = "com.example.app"
        overlay.fallbackAllowed = true
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)

        val snapshot = driver.observe(5_000)
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals("observe must not hide a window that is not drawn", 0, overlay.hiddenCalls)
        assertEquals(0, overlay.makeWayCalls)

        val binding = bindingFor(snapshot)
        // This point is inside the capsule's own bounds; it is Sent only because no capsule is drawn.
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertEquals(listOf(AdbInputCommand.Tap(150, 450)), device.injected)
        assertEquals("no point-in-overlay check and no make-way", 0, overlay.makeWayCalls)

        val image = driver.capture(binding, 5_000)
        assertEquals(1080, image.width)
        assertEquals("capture must not hide an already-hidden window", 0, overlay.hiddenCalls)
        assertEquals(0, overlay.restoreCalls)
    }

    @Test
    fun aForceHiddenOverlayWithoutAVerifiedNotificationEntryIsRefused() = runBlocking {
        val overlay = FakeOverlay()
        overlay.visible = false
        overlay.managerClaimsVisible = true
        overlay.forceHiddenBySystem = true
        overlay.liveTarget = "com.example.app"
        overlay.fallbackAllowed = false
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
        assertTrue(device.injected.isEmpty())
        assertEquals(0, overlay.hiddenCalls)
    }

    @Test
    fun aDifferentFocusedAppThatForceHidesOverlaysIsRefused() = runBlocking {
        val overlay = FakeOverlay()
        overlay.visible = false
        overlay.managerClaimsVisible = true
        overlay.forceHiddenBySystem = true
        // The live task targets another package; the focused app that hides overlays is not it.
        overlay.liveTarget = "com.example.other"
        overlay.fallbackAllowed = true
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
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun anInvisibleOverlayWithoutTheForceHideFlagStaysRefused() = runBlocking {
        val overlay = FakeOverlay()
        overlay.visible = false
        overlay.managerClaimsVisible = true
        // Our own detach/permission loss/unknown state: no target force-hide proof.
        overlay.attachedButInvisible = true
        overlay.forceHiddenBySystem = false
        overlay.liveTarget = "com.example.app"
        overlay.fallbackAllowed = true
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
    fun aFrozenNotificationFallbackIsRefusedWhenTheTargetStopsHidingOverlays() = runBlocking {
        val overlay = FakeOverlay()
        overlay.visible = false
        overlay.managerClaimsVisible = true
        overlay.forceHiddenBySystem = true
        overlay.liveTarget = "com.example.app"
        overlay.fallbackAllowed = true
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))

        // The proof disappears before the injection: the step is re-proved and nothing is sent.
        overlay.forceHiddenBySystem = false
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
        assertEquals("a hidden overlay is never asked to move", 0, overlay.makeWayCalls)
    }

    @Test
    fun observeRequiresTheOverlayToStayVisibleAcrossTheRead() = runBlocking {
        val overlay = FakeOverlay()
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        // The pre-read witness already returned its frame; the capsule vanishes before the post check.
        device.onWindowDetail = { overlay.visible = false }
        val failure = runCatching { driver.observe(5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
        assertEquals(0, overlay.hiddenCalls)
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
        // Capture still hides: the screenshot must not contain the capsule's summary/notices.
        assertTrue(overlay.hiddenCalls >= 1)

        overlay.honest = false
        val failure = runCatching { driver.capture(binding, 5_000) }.exceptionOrNull()
        assertEquals(
            PhoneDriverFailure.POLICY_DENIED,
            (failure as PhoneDriverException).failure,
        )
    }

    @Test
    fun captureHidesByMakingTheAttachedWindowInvisibleInsteadOfRemovingIt() = runBlocking {
        val overlay = FakeOverlay()
        var hiddenDump: String? = null
        overlay.onHidden = { hiddenDump = overlay.windowState() }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        driver.capture(binding, 5_000)

        val dump = requireNotNull(hiddenDump) { "capture must really hide the window" }
        // Same window/token/surface: only the root view became INVISIBLE.
        assertTrue("the window must stay attached", dump.contains("Sys2038:${overlay.key}"))
        assertTrue("the real surface must stay attached", dump.contains("mHasSurface=true"))
        assertTrue("the hide must be the INVISIBLE root view", dump.contains("mViewVisibility=0x4"))
        assertEquals("the same window must be restored, not re-added", 1, overlay.restoreCalls)
    }

    @Test
    fun anObstructedTapAsksTheOverlayToMoveAndThenInjectsExactlyOnce() = runBlocking {
        val overlay = FakeOverlay()
        overlay.makeWayResult = true
        overlay.onMakeWay = { _, _ -> overlay.bounds = PhoneRect(0, 900, 160, 1060) }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))

        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertEquals(1, overlay.makeWayCalls)
        assertEquals(PhoneRect(100, 400, 260, 560), overlay.makeWayFrame)
        // The avoid rect is a small square around the tap point.
        val avoid = overlay.makeWayAvoid.single()
        assertTrue(avoid.left < 150 && 150 < avoid.right && avoid.top < 450 && 450 < avoid.bottom)
        assertEquals("nothing is injected before the move", 1, device.injected.size)
        assertEquals(listOf(AdbInputCommand.Tap(150, 450)), device.injected)
        assertEquals("observe kept the capsule visible", 0, overlay.hiddenCalls)
    }

    @Test
    fun aMakeWayThatDoesNotMoveTheCapsuleLeavesTheTapNotSent() = runBlocking {
        val overlay = FakeOverlay()
        overlay.makeWayResult = false
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertNotSent(
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertEquals(1, overlay.makeWayCalls)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun aCapsuleThatStillCoversThePointAfterMovingKeepsTheTapNotSent() = runBlocking {
        val overlay = FakeOverlay()
        overlay.makeWayResult = true
        // It moved, but the new frame still covers the tap point.
        overlay.onMakeWay = { _, _ -> overlay.bounds = PhoneRect(140, 440, 700, 700) }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertNotSent(
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
            driver.dispatch(request(PhoneDriverAction.Tap(PhonePoint(150, 450)), binding), 5_000),
        )
        assertEquals("exactly one make-way attempt per dispatch", 1, overlay.makeWayCalls)
        assertTrue(device.injected.isEmpty())
    }

    @Test
    fun anObstructedSwipeAsksTheOverlayToMoveItsWholeSegmentClear() = runBlocking {
        val overlay = FakeOverlay()
        overlay.makeWayResult = true
        overlay.onMakeWay = { _, _ -> overlay.bounds = PhoneRect(0, 1000, 160, 1160) }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(
                request(
                    PhoneDriverAction.Swipe(PhonePoint(0, 480), PhonePoint(500, 480), 200),
                    binding,
                ),
                5_000,
            ),
        )
        // The avoid rect covers the whole segment, both endpoints included.
        val avoid = overlay.makeWayAvoid.single()
        assertTrue(avoid.left <= 0 && avoid.right >= 500)
        assertTrue(avoid.top < 480 && 480 < avoid.bottom)
        assertEquals(listOf(AdbInputCommand.Swipe(0, 480, 500, 480, 200)), device.injected)
    }

    @Test
    fun anObstructedInputFocusClearsTheFocusPointBeforeTheTextStep() = runBlocking {
        val overlay = FakeOverlay(bounds = PhoneRect(100, 100, 300, 200))
        overlay.makeWayResult = true
        overlay.onMakeWay = { _, _ -> overlay.bounds = PhoneRect(0, 900, 160, 1060) }
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(
                request(PhoneDriverAction.Input("hello", focus = PhonePoint(150, 150)), binding),
                5_000,
            ),
        )
        assertEquals(1, overlay.makeWayCalls)
        val avoid = overlay.makeWayAvoid.single()
        assertTrue(avoid.left < 150 && 150 < avoid.right && avoid.top < 150 && 150 < avoid.bottom)
        assertEquals(
            listOf(AdbInputCommand.Tap(150, 150), AdbInputCommand.Text("hello")),
            device.injected,
        )
    }

    @Test
    fun aFocusLessInputAndBackNeverAskTheOverlayToMove() = runBlocking {
        val overlay = FakeOverlay(bounds = PhoneRect(100, 400, 260, 560))
        val device = FakeDevice(
            editableHierarchy.toByteArray(),
            windowStateProvider = { overlay.windowState() },
            windowDetailProvider = { overlay.detailState() },
        )
        val driver = overlayDriver(device, overlay)
        val binding = bindingFor(driver.observe(5_000))
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Input("hello"), binding), 5_000),
        )
        assertEquals(
            PhoneDispatchReceipt.Sent,
            driver.dispatch(request(PhoneDriverAction.Back, binding), 5_000),
        )
        assertEquals(0, overlay.makeWayCalls)
        assertEquals(
            listOf(AdbInputCommand.Text("hello"), AdbInputCommand.Back),
            device.injected,
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
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
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
        assertEquals("notification mode never asks the overlay to move", 0, overlay.makeWayCalls)
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
        // Observation no longer hides, so there is no restore either; the mode-change discard is
        // still required.
        assertEquals(0, overlay.hiddenCalls)
        assertEquals(0, overlay.restoreCalls)
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
        // The make-way probe validated the old frame; the injection-time witness now sees the point
        // under the capsule, so this is the ordinary obstruction refusal and nothing is injected.
        assertNotSent(
            PhoneDriverFailure.OVERLAY_OBSTRUCTED,
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
        var moved = false
        device.onWindowDetail = {
            if (!moved && device.injected.isNotEmpty()) {
                // The text step re-validated against the old frame; the overlay now revises.
                moved = true
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

        /**
         * The target app set `mForceHideNonSystemOverlayWindow=true`: our surface stays attached but
         * WindowManager hides every non-system overlay.
         */
        var forceHiddenBySystem: Boolean = false

        /** Manager-side claim, independent of the real dump. Null keeps the legacy [visible] tie. */
        var managerClaimsVisible: Boolean? = null

        /** Window exists with a surface but is invisible for a reason other than the target's flag. */
        var attachedButInvisible: Boolean = false

        /** Live task target for the target-hides-overlay fallback, and the verified stop entry. */
        var liveTarget: String? = null
        var fallbackAllowed: Boolean = false

        /**
         * Production capture hide keeps the window attached and only makes its root view INVISIBLE.
         * [hiddenForRead] models that state: the window section is still present with a surface and
         * `isVisible=true`, but `mViewVisibility=0x4`. [visible] means the window is really absent.
         */
        var hiddenForRead: Boolean = false
        var required: Boolean = true
        var requiredThrows: Boolean = false
        var expectationCalls: Int = 0
        var requiredCalls: Int = 0
        var onRequired: ((Int) -> Unit)? = null

        /** Automatic make-way requests the driver really made for this fake. */
        var makeWayCalls: Int = 0
        var makeWayAvoid: List<PhoneRect> = emptyList()
        var makeWayFrame: PhoneRect? = null
        var makeWayResult: Boolean = false
        var onMakeWay: ((List<PhoneRect>, PhoneRect) -> Unit)? = null
        /** Runs inside the hide, after the fake's visibility state was applied. */
        var onHidden: (() -> Unit)? = null

        override suspend fun makeWay(
            avoid: List<PhoneRect>,
            currentFrame: PhoneRect,
            deadlineMs: Long,
        ): Boolean {
            makeWayCalls += 1
            makeWayAvoid = avoid
            makeWayFrame = currentFrame
            onMakeWay?.invoke(avoid, currentFrame)
            return makeWayResult
        }

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
                phase = if (managerClaimsVisible ?: visible) OverlayPhase.VISIBLE else OverlayPhase.UNAVAILABLE,
                windowToken = key,
                freshnessDeadlineMs = deadlineMs + 60_000L,
            )
        }

        override fun liveTargetPackage(): String? = liveTarget

        override fun notificationFallbackAllowed(): Boolean = fallbackAllowed

        override suspend fun <T> withContentHidden(
            owner: String,
            generation: Long,
            revision: Long,
            phase: OverlayPhase,
            deadlineMs: Long,
            block: suspend () -> T,
        ): T {
            hiddenCalls += 1
            val previous = hiddenForRead
            if (honest) hiddenForRead = true
            onHidden?.invoke()
            try {
                return block()
            } finally {
                hiddenForRead = previous
                restoreCalls += 1
            }
        }

        /** Full `dumpsys window` list: real API 35 has no frame here. */
        fun windowState(): String = buildString {
            append("mCurrentFocus=Window{7068ee4 u0 com.example.app/MainActivity}\n")
            append("mFocusedApp=ActivityRecord{312191b u0 com.example.app/MainActivity t16}\n")
            if (visible || forceHiddenBySystem || attachedButInvisible) append(windowSection(detail = false))
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
            // A capture-hide keeps the same window: INVISIBLE root view -> mViewVisibility=0x4,
            // while the policy and surface still say the window exists.
            append("    mViewVisibility=0x${if (hiddenForRead) "4" else "0"} mHaveFrame=true mObscured=false\n")
            // A target that force-hides non-system overlays keeps the surface but the policy hides
            // the window and it is not on screen; the focused app set the force-hide flag.
            append("    mPolicyVisibility=${if (forceHiddenBySystem || attachedButInvisible) "false" else "true"}\n")
            append("    isOnScreen=${if (forceHiddenBySystem || attachedButInvisible) "false" else "true"}\n")
            append("    isVisible=${if (forceHiddenBySystem || attachedButInvisible || !visible) "false" else "true"}\n")
            if (forceHiddenBySystem) append("    mForceHideNonSystemOverlayWindow=true\n")
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
