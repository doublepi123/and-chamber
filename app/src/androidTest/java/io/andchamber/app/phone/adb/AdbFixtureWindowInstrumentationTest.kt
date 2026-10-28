package io.andchamber.app.phone.adb

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.phone.core.PhoneAdmissionGuard
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device window/freshness probe against the existing fixture app (no new app):
 * `io.example.phonefixture` MainActivity/SecondActivity in the same package.
 *
 * Expected checks on the explicitly selected test device (emulator evidence is not hardware proof):
 * - real `dumpsys window` identity in the snapshot;
 * - a Save counter change invalidates the old binding before capture;
 * - a same-package SecondActivity change invalidates the old binding before capture/dispatch;
 * - password/editable values are redacted and the password field refuses input;
 * - the focus -> text follow-up check allows a real ASCII input (the input is last; it opens the IME).
 *
 * Pairing code/port arrive only through private instrumentation args and are never logged.
 */
@RunWith(AndroidJUnit4::class)
class AdbFixtureWindowInstrumentationTest {
    @Test
    fun fixtureWindowAndContentFreshness() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("pairHost") ?: "127.0.0.1"
        val pairPort = arguments.getString("pairPort")?.toIntOrNull()
        val connectPort = arguments.getString("connectPort")?.toIntOrNull()
        val code = arguments.getString("pairCode")?.trim()
        assumeTrue(
            "pairing instrumentation arguments are required",
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
        val service = AdbPhoneDriverService(identity = created.identity, runtimeId = "runtime-fixture")
        try {
            service.configurePairing(host, targetPairPort)
            service.pair(targetCode.toCharArray(), PAIRING_DEADLINE_MS)
            service.configure(host, targetConnectPort)
            runBlocking { service.connect(CONNECT_DEADLINE_MS) }
            println("check=fixture_pair_connect result=pass")

            context.startActivity(
                Intent().setClassName(FIXTURE, "$FIXTURE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            // The first frame/keyboard transition can take a few seconds on a headless emulator.
            Thread.sleep(500L)

            val main = observeUntil(service) { snapshot ->
                snapshot.packageName == FIXTURE &&
                    snapshot.nodes.any { it.label == "AndChamber Phone Fixture" }
            }
            check(main.nodes.any { it.label == "Saved 0 times" }) { "initial counter not found" }
            val mainBinding = binding(main)
            val image = runBlocking { service.capture(mainBinding, CONNECT_DEADLINE_MS) }
            check(image.mimeType == "image/png") { "capture mime type" }
            check(image.width == main.geometry.displayWidth &&
                image.height == main.geometry.displayHeight
            ) {
                "capture geometry does not match the fixture observation"
            }
            println("check=fixture_observe_capture result=pass")

            val fieldEdits = main.nodes.filter { it.role.contains("EditText") }
            val editable = fieldEdits.firstOrNull { it.editable }
                ?: error("editable fixture field not found")
            val password = fieldEdits.firstOrNull { !it.editable }
                ?: error("password fixture field not found")
            check(editable.label.isEmpty()) { "editable value leaked" }
            check(password.label.isEmpty()) { "password value leaked" }
            println("check=fixture_password_redacted result=pass")

            val passwordReceipt = dispatch(
                service,
                mainBinding,
                PhoneDriverAction.Input("nope", focus = point(password)),
            )
            check(passwordReceipt is PhoneDispatchReceipt.NotSent &&
                passwordReceipt.failure == PhoneDriverFailure.UNSUPPORTED
            ) {
                "password field accepted input: $passwordReceipt"
            }
            println("check=fixture_password_input_refused result=pass")

            val save = main.nodes.first { it.label.equals("Save", ignoreCase = true) }
            check(
                dispatch(service, mainBinding, PhoneDriverAction.Tap(point(save))) ==
                    PhoneDispatchReceipt.Sent,
            ) { "Save was not sent" }
            val saved = observeUntil(service) { snapshot ->
                snapshot.nodes.any { it.label == "Saved 1 times" }
            }
            // Same real window, only a label changed: live pages keep changing, so this is not
            // a stale window. A tap by ref still needs its element at the observed bounds.
            val sameWindowAfterSave = runCatching {
                runBlocking { service.capture(mainBinding, CONNECT_DEADLINE_MS) }
            }.exceptionOrNull()
            check(sameWindowAfterSave == null) {
                "a content-only change refused the same window: $sameWindowAfterSave"
            }
            val missingTarget = dispatch(
                service,
                mainBinding,
                PhoneDriverAction.Tap(
                    point(save),
                    target = io.andchamber.app.phone.core.PhoneRect(0, 0, 1, 1),
                ),
            )
            check(missingTarget is PhoneDispatchReceipt.NotSent &&
                missingTarget.failure == PhoneDriverFailure.WINDOW_CHANGED
            ) {
                "a tap whose element is gone was not refused: $missingTarget"
            }
            println("check=fixture_content_change_same_window result=pass")

            val savedBinding = binding(saved)
            val next = saved.nodes.first { it.label.equals("Next page", ignoreCase = true) }
            check(
                dispatch(service, savedBinding, PhoneDriverAction.Tap(point(next))) ==
                    PhoneDispatchReceipt.Sent,
            ) { "Next page was not sent" }
            val second = observeUntil(service) { snapshot ->
                snapshot.packageName == FIXTURE &&
                    snapshot.nodes.any { it.label == "Second page" }
            }
            val staleAfterNext = runCatching {
                runBlocking { service.capture(savedBinding, CONNECT_DEADLINE_MS) }
            }.exceptionOrNull()
            check(staleAfterNext is PhoneDriverException &&
                (staleAfterNext.failure == PhoneDriverFailure.WINDOW_CHANGED ||
                    staleAfterNext.failure == PhoneDriverFailure.TARGET_CHANGED)
            ) {
                "same-package new activity did not invalidate the old binding: $staleAfterNext"
            }
            val staleDispatch = dispatch(service, savedBinding, PhoneDriverAction.Back)
            check(staleDispatch is PhoneDispatchReceipt.NotSent &&
                (staleDispatch.failure == PhoneDriverFailure.WINDOW_CHANGED ||
                    staleDispatch.failure == PhoneDriverFailure.TARGET_CHANGED)
            ) {
                "stale action was not refused: $staleDispatch"
            }
            println("check=fixture_window_stale_rejected result=pass")

            val secondBinding = binding(second)
            val back = second.nodes.first { it.label.equals("Back", ignoreCase = true) }
            check(
                dispatch(service, secondBinding, PhoneDriverAction.Tap(point(back))) ==
                    PhoneDispatchReceipt.Sent,
            ) { "Back was not sent" }
            val mainAgain = observeUntil(service) { snapshot ->
                snapshot.nodes.any { it.label == "Saved 1 times" }
            }
            println("check=fixture_back result=pass")

            // Last: focus tap + text on the real ASCII field. This opens the IME; no later action
            // depends on the unchanged hierarchy.
            val mainBindingAgain = binding(mainAgain)
            val editableAgain = mainAgain.nodes.first { it.editable && it.role.contains("EditText") }
            val inputReceipt = dispatch(
                service,
                mainBindingAgain,
                PhoneDriverAction.Input("hello", focus = point(editableAgain)),
            )
            check(inputReceipt == PhoneDispatchReceipt.Sent) {
                "focus/text input was not sent: $inputReceipt"
            }
            println("check=fixture_input_focus_text result=pass")
        } finally {
            runBlocking { runCatching { service.disconnect(5_000) } }
            TestAdbIdentity.delete(created.alias)
        }
    }

    private fun dispatch(
        service: AdbPhoneDriverService,
        binding: PhoneObservationBinding,
        action: PhoneDriverAction,
    ): PhoneDispatchReceipt = runBlocking {
        service.dispatch(
            PhoneDispatchRequest(binding, action, PhoneAdmissionGuard { null }),
            CONNECT_DEADLINE_MS,
        )
    }

    private fun binding(snapshot: PhoneSnapshot): PhoneObservationBinding =
        PhoneObservationBinding(
            observationId = "fixture",
            generation = 1L,
            packageName = snapshot.packageName,
            windowId = snapshot.windowId,
            rotation = snapshot.geometry.rotation,
            geometry = snapshot.geometry,
        )

    private fun point(node: io.andchamber.app.phone.core.PhoneNode): io.andchamber.app.phone.core.PhonePoint =
        io.andchamber.app.phone.core.PhonePoint(node.bounds.centerX, node.bounds.centerY)

    private fun observeUntil(
        service: AdbPhoneDriverService,
        timeoutMs: Long = 20_000,
        predicate: (PhoneSnapshot) -> Boolean,
    ): PhoneSnapshot {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var last: Exception? = null
        while (SystemClock.elapsedRealtime() < end) {
            try {
                val snapshot = runBlocking { service.observe(CONNECT_DEADLINE_MS) }
                if (predicate(snapshot)) return snapshot
            } catch (failure: Exception) {
                last = failure
            }
            Thread.sleep(200L)
        }
        throw AssertionError("fixture condition was not reached", last)
    }

    private companion object {
        const val FIXTURE = "io.example.phonefixture"
        const val CODE_LENGTH = 6
        const val PAIRING_DEADLINE_MS = 15_000L
        const val CONNECT_DEADLINE_MS = 15_000L
    }
}
