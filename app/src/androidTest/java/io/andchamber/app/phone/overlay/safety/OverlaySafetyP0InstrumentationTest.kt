package io.andchamber.app.phone.overlay.safety

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.phone.adb.AdbPairingReceiptStore
import io.andchamber.app.phone.adb.AdbPhoneDriverService
import io.andchamber.app.phone.adb.LibAdbDevice
import io.andchamber.app.phone.adb.TestAdbIdentity
import io.andchamber.app.phone.core.PhoneAdmissionGuard
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhonePoint
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.core.PhoneSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real overlay-safety P0 against the dedicated emulator and the test-only overlay probe APK.
 *
 * It requires a real TYPE_APPLICATION_OVERLAY owned by the androidTest package, a paired ADB
 * connection, and a SYSTEM_ALERT_WINDOW appop grant to that test package. The fixture target has a
 * separate OverlayHiddenActivity that asks WindowManager to hide overlays. No production UI is used.
 */
@RunWith(AndroidJUnit4::class)
class OverlaySafetyP0InstrumentationTest {
    @Test
    fun realOverlayWitnessCaptureAndTargetHide() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("pairHost") ?: "127.0.0.1"
        val pairPort = arguments.getString("pairPort")?.toIntOrNull()
        val connectPort = arguments.getString("connectPort")?.toIntOrNull()
        val code = arguments.getString("pairCode")?.trim()
        assumeTrue("pairing args required", pairPort != null && connectPort != null && code?.length == 6)
        val targetPairPort = requireNotNull(pairPort)
        val targetConnectPort = requireNotNull(connectPort)
        val targetCode = requireNotNull(code)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val overlayPackage = testContext.packageName
        // Diagnostics only: `Settings.canDrawOverlays` is not visibility evidence, and it resolves
        // the app op through the context's op package, which differs between the instrumentation
        // context and the activity that really adds the window. The P0 fails later on the real
        // `dumpsys window` witness if the overlay was not created.
        println(
            "diag=overlay_permission pkg=$overlayPackage op_pkg=${testContext.opPackageName} " +
                "app_uid=${testContext.applicationInfo.uid} my_uid=${android.os.Process.myUid()} " +
                "can_draw=${Settings.canDrawOverlays(testContext)}",
        )

        val created = TestAdbIdentity.create(targetContext)
        val receipts = AdbPairingReceiptStore()
        val barrier = TestOverlayBarrier(testContext)
        val service = AdbPhoneDriverService(
            identity = created.identity,
            runtimeId = "runtime-overlay-p0",
            pairingReceipts = receipts,
            overlayBarrier = barrier,
        )
        var windowDevice: LibAdbDevice? = null
        try {
            service.configurePairing(host, targetPairPort)
            service.pair(targetCode.toCharArray(), 15_000L)
            service.configure(host, targetConnectPort)
            runBlocking { service.connect(15_000L) }

            val second = LibAdbDevice(
                identity = created.identity,
                host = host,
                port = targetConnectPort,
                pairingReceipts = receipts,
            )
            second.connect(15_000L)
            windowDevice = second
            barrier.attach(second)

            // Start activities directly from the test app: this test must not connect UiAutomation
            // (`executeShellCommand`), because the driver's real `uiautomator dump` needs the
            // accessibility connection that an active instrumentation UiAutomation occupies.
            startActivity(targetContext, "$FIXTURE/.MainActivity")
            Thread.sleep(700L)
            startActivity(testContext, "$overlayPackage/${TestOverlayActivity::class.java.name}")
            runCatching { barrier.awaitVisible(timeoutMs = 10_000L) }.onFailure { failure ->
                error(
                    "real overlay window did not appear: ${failure.message}; " +
                        "activity_error=${TestOverlayActivity.lastError} " +
                        "activity_can_draw=${TestOverlayActivity.canDrawOverlaysAtStart}",
                )
            }
            println(
                "check=real_overlay_visible result=pass hash=${barrier.lastWindow()?.windowHash} " +
                    "bounds=${barrier.lastWindow()?.bounds}",
            )

            val snapshot = runBlocking { service.observe(15_000L) }
            assertEquals(FIXTURE, snapshot.packageName)
            val binding = binding(snapshot)
            assertTrue(barrier.hiddenCalls >= 1)
            assertTrue(barrier.restoreCalls >= 1)
            println("check=overlay_observe_hidden result=pass")

            val overlay = barrier.lastWindow() ?: error("overlay window missing")
            val bounds = overlay.bounds ?: error("overlay frame missing")
            val rawBytes = second.capturePng(15_000L)
            val hiddenImage = runBlocking { service.capture(binding, 15_000L) }
            val rawMarker = markerPixels(rawBytes, bounds)
            val hiddenMarker = markerPixels(hiddenImage.bytes, bounds)
            assertTrue("raw screenshot must contain the overlay marker ($rawMarker)", rawMarker > 50)
            assertEquals("hidden capture must not contain the overlay marker", 0, hiddenMarker)
            // This instrumentation shares the target probe app's process/uid, so its own test-package
            // data dir is not writable. Keep the real PNGs in the target probe app's internal files
            // and pull them with `run-as io.andchamber.app.overlayprobe` after the run.
            val evidenceDir = File(targetContext.filesDir, "overlay-evidence")
            if (!evidenceDir.isDirectory && !evidenceDir.mkdirs()) {
                error("evidence dir unavailable: ${evidenceDir.absolutePath}")
            }
            File(evidenceDir, "overlay-visible.png").writeBytes(rawBytes)
            File(evidenceDir, "overlay-hidden.png").writeBytes(hiddenImage.bytes)
            println(
                "check=overlay_capture_self_exclusion result=pass " +
                    "raw_marker=$rawMarker hidden_marker=$hiddenMarker dir=${evidenceDir.absolutePath}",
            )

            val center = PhonePoint(bounds.centerX, bounds.centerY)
            assertNotSent(
                PhoneDriverFailure.OVERLAY_OBSTRUCTED,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            binding,
                            PhoneDriverAction.Tap(center),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            assertEquals(
                PhoneDispatchReceipt.Sent,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            binding,
                            PhoneDriverAction.Tap(PhonePoint(50, 1000)),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            assertNotSent(
                PhoneDriverFailure.OVERLAY_OBSTRUCTED,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            binding,
                            PhoneDriverAction.Swipe(
                                PhonePoint(0, bounds.centerY),
                                PhonePoint(bounds.right + 200, bounds.centerY),
                                200,
                            ),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            println("check=overlay_endpoint_exclusion result=pass")

            // Moving/expanding the capsule bumps its display revision. That alone no longer stops
            // control: every action re-reads the real frame. The old point is now under the moved
            // capsule and is refused as an obstruction; a point outside the new frame still goes.
            barrier.move(x = 0, y = 900, width = 200, height = 200)
            barrier.awaitVisible(timeoutMs = 5_000L)
            val moved = barrier.lastWindow()?.bounds ?: error("moved overlay frame missing")
            println("diag=overlay_moved bounds=$moved")
            assertNotSent(
                PhoneDriverFailure.OVERLAY_OBSTRUCTED,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            binding,
                            PhoneDriverAction.Tap(PhonePoint(moved.centerX, moved.centerY)),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            assertEquals(
                PhoneDispatchReceipt.Sent,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            binding,
                            PhoneDriverAction.Tap(PhonePoint(moved.right + 300, moved.centerY)),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            println("check=overlay_revision_rechecked_against_fresh_frame result=pass")

            // Put the overlay exactly over the fixture's editable text field
            // ([42,360][1038,478] in screen coordinates; overlay y is still app-window space).
            // The password field is deliberately not an accepted input target.
            barrier.move(x = 100, y = 224, width = 160, height = 118)
            barrier.awaitVisible(timeoutMs = 5_000L)
            println(
                "diag=overlay_over_input bounds=${barrier.lastWindow()?.bounds} " +
                    "hash=${barrier.lastWindow()?.windowHash}",
            )
            val fresh = runBlocking { service.observe(15_000L) }
            val freshBinding = binding(fresh)
            assertNotSent(
                PhoneDriverFailure.OVERLAY_OBSTRUCTED,
                runBlocking {
                    service.dispatch(
                        PhoneDispatchRequest(
                            freshBinding,
                            PhoneDriverAction.Input("hello", focus = PhonePoint(150, 400)),
                            PhoneAdmissionGuard { null },
                        ),
                        15_000L,
                    )
                },
            )
            // The focus point is a real editable field, so the refusal comes from the overlay rect,
            // not from an unsupported input target.
            println("check=overlay_input_focus_excluded result=pass focus=150,400")

            // Target asks WindowManager to hide non-system overlays. If the real screenshot no
            // longer contains the marker, the driver must refuse. If the marker remains, this build
            // cannot demonstrate the target hide and the P0 is unsupported, not a pass.
            startActivity(targetContext, "$FIXTURE/.OverlayHiddenActivity")
            Thread.sleep(1_500L)
            val targetRaw = second.capturePng(15_000L)
            val targetWindow = barrier.scanNow()
            val targetBounds = targetWindow?.bounds ?: bounds
            val targetMarker = markerPixels(targetRaw, targetBounds)
            File(evidenceDir, "overlay-target-hidden.png").writeBytes(targetRaw)
            val targetFailure = runCatching { runBlocking { service.observe(15_000L) } }.exceptionOrNull()
            println(
                "check=target_hide_probe marker=$targetMarker " +
                    "window=$targetWindow observe=$targetFailure",
            )
            assertTrue(
                "OverlayHiddenActivity did not hide the overlay (marker=$targetMarker); " +
                    "this is unsupported/blocker evidence, not a P0 pass",
                targetMarker == 0,
            )
            assertTrue(
                "hidden target overlay was not refused: $targetFailure",
                targetFailure is PhoneDriverException &&
                    targetFailure.failure == PhoneDriverFailure.POLICY_DENIED,
            )
            println("check=target_hide_refused result=pass")
        } finally {
            runBlocking { runCatching { service.disconnect(5_000L) } }
            runCatching { windowDevice?.disconnect() }
            barrier.stopOverlay()
            TestAdbIdentity.delete(created.alias)
        }
    }

    private fun binding(snapshot: PhoneSnapshot): PhoneObservationBinding =
        PhoneObservationBinding(
            observationId = "p0-overlay",
            generation = 1L,
            packageName = snapshot.packageName,
            windowId = snapshot.windowId,
            rotation = snapshot.geometry.rotation,
            geometry = snapshot.geometry,
        )

    private fun assertNotSent(expected: PhoneDriverFailure, receipt: PhoneDispatchReceipt) {
        assertTrue("expected NotSent but was $receipt", receipt is PhoneDispatchReceipt.NotSent)
        assertEquals(expected, (receipt as PhoneDispatchReceipt.NotSent).failure)
    }

    private fun markerPixels(png: ByteArray, bounds: PhoneRect): Int {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("screenshot is not decodable PNG")
        var count = 0
        var x = bounds.left + 4
        while (x < bounds.right && x < bitmap.width) {
            var y = bounds.top + 4
            while (y < bounds.bottom && y < bitmap.height) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) > 200 && Color.blue(color) > 200 && Color.green(color) < 80) {
                    count += 1
                }
                y += 4
            }
            x += 4
        }
        bitmap.recycle()
        return count
    }

    private fun startActivity(context: Context, component: String) {
        val name = ComponentName.unflattenFromString(component)
            ?: error("invalid component $component")
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private class TestOverlayBarrier(
        private val testContext: Context,
    ) : PhoneOverlayBarrier {
        private var device: LibAdbDevice? = null
        private var generation = 1L
        private var revision = 1L
        private var window: OverlayWindowState? = null
        var hiddenCalls = 0
        var restoreCalls = 0

        fun attach(value: LibAdbDevice) {
            device = value
        }

        fun lastWindow(): OverlayWindowState? = window

        fun scanNow(): OverlayWindowState? = scan()

        override fun expectation(deadlineMs: Long): OverlayExpectation {
            window = scan()
            val visible = window?.isSelfVisible ?: false
            return OverlayExpectation(
                owner = testContext.packageName,
                generation = generation,
                revision = revision,
                phase = if (visible) OverlayPhase.VISIBLE else OverlayPhase.UNAVAILABLE,
                // Exactly the production contract: a stable LayoutParams.title, not a binder token.
                windowToken = if (visible) TestOverlayActivity.WINDOW_TITLE else null,
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
            send(TestOverlayActivity.COMMAND_HIDE)
            awaitVisible(wantVisible = false, timeoutMs = 5_000L)
            try {
                return block()
            } finally {
                send(TestOverlayActivity.COMMAND_SHOW)
                awaitVisible(wantVisible = true, timeoutMs = 5_000L)
                restoreCalls += 1
            }
        }

        fun move(x: Int, y: Int, width: Int, height: Int) {
            val move = Intent(TestOverlayActivity.ACTION_CONTROL)
                .setPackage(testContext.packageName)
                .putExtra(TestOverlayActivity.EXTRA_COMMAND, TestOverlayActivity.COMMAND_MOVE)
                .putExtra(TestOverlayActivity.EXTRA_X, x)
                .putExtra(TestOverlayActivity.EXTRA_Y, y)
            testContext.sendBroadcast(move)
            val expand = Intent(TestOverlayActivity.ACTION_CONTROL)
                .setPackage(testContext.packageName)
                .putExtra(TestOverlayActivity.EXTRA_COMMAND, TestOverlayActivity.COMMAND_EXPAND)
                .putExtra(TestOverlayActivity.EXTRA_WIDTH, width)
                .putExtra(TestOverlayActivity.EXTRA_HEIGHT, height)
            testContext.sendBroadcast(expand)
            revision += 1
        }

        fun stopOverlay() {
            send(TestOverlayActivity.COMMAND_STOP)
        }

        fun awaitVisible(timeoutMs: Long, wantVisible: Boolean = true) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                window = scan()
                val visible = window?.isSelfVisible ?: false
                if (visible == wantVisible) return
                Thread.sleep(100L)
            }
            error("overlay visible=$wantVisible not reached")
        }

        private fun scan(): OverlayWindowState? {
            val reader = device ?: error("window reader not attached")
            val full = OverlayWindowStateParser.parse(reader.windowStateText(5_000L))
            // Manager side knows only its own stable title; the binder token is discovered by the
            // driver (here: by the same test-side reader used to prove visibility).
            val candidate = full.windows.firstOrNull { it.titleKey == TestOverlayActivity.WINDOW_TITLE }
                ?: return null
            if (!candidate.hasSurface || !candidate.visible) return candidate
            // The full list has no frame on API 35; read the targeted window for the real rectangle.
            val detail = OverlayWindowStateParser.parse(
                reader.windowDetailText(candidate.windowHash, 5_000L),
            )
            return detail.windows.firstOrNull { it.windowHash == candidate.windowHash } ?: candidate
        }

        private fun send(command: String) {
            testContext.sendBroadcast(
                Intent(TestOverlayActivity.ACTION_CONTROL)
                    .setPackage(testContext.packageName)
                    .putExtra(TestOverlayActivity.EXTRA_COMMAND, command),
            )
        }
    }

    private companion object {
        const val FIXTURE = "io.example.phonefixture"
    }
}
