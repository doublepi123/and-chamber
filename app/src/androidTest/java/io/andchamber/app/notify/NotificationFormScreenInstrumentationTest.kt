package io.andchamber.app.notify

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.R
import io.andchamber.app.testhost.WebViewRegressionActivity
import io.andchamber.app.ui.theme.AndChamberTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap

private const val LOG_TAG = "NotifyFormImeTest"

/**
 * The real [NotificationFormScreen] and [NotificationFormModel] in a real window, with a fake
 * load/submit instead of a server:
 *
 *  - three fields are typed with real key events (q0=41234, q1=123456, q2=39899);
 *  - a blank required field sends nothing, is flagged and gets focus;
 *  - Send submits the exact answer map once, even when tapped twice while sending, and an accepted
 *    answer removes the notification and closes the screen;
 *  - a refused answer keeps what was typed and shows the status; the notification is kept, not removed;
 *  - with the soft keyboard really up (root IME insets visible, bottom > 0), Send is whole and above it;
 *  - answers belong to one form identity (stateKey): another identity with the same field keys starts
 *    from its own defaults, in place and after a saved-state recreation, and the first comes back.
 *
 * Nodes are found by their test tags (exposed as resource ids); taps are ACTION_CLICK. No notification
 * shade, no network, no runtime.
 */
@RunWith(AndroidJUnit4::class)
class NotificationFormScreenInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<WebViewRegressionActivity>
    private lateinit var activity: WebViewRegressionActivity
    private lateinit var scope: CoroutineScope

    private val form =
        FormAnswer.Fields(
            formId = "frm_pair",
            sessionId = "ses_1",
            title = "Pair this device",
            fields =
                listOf(
                    FormTextField("q0", "First code", "Shown on the other device.", true, "5 digits", null),
                    FormTextField("q1", "Second code", null, true, null, null),
                    FormTextField("q2", "Third code", null, false, null, null),
                ),
        )

    /** Records what the screen asked of the notification. */
    private class RecordingEffects : NotificationFormEffects {
        val events = Collections.synchronizedList(mutableListOf<String>())

        override fun answered() {
            events += "answered"
        }

        override fun gone() {
            events += "gone"
        }

        override fun keep(
            step: ActionStep,
            reason: String?,
        ) {
            events += "keep:$step:$reason"
        }
    }

    /** A submit that waits until the test releases it, so the in-flight state can be checked. */
    private class FakeSubmit {
        val calls = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val release = CompletableDeferred<NotificationFormSubmission>()

        suspend fun submit(
            form: FormAnswer.Fields,
            values: Map<String, String>,
        ): NotificationFormSubmission {
            calls += values
            return release.await()
        }
    }

    private class HostOwner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    @Before fun setUp() {
        enableWindowRetrieval()
        scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java)
        scenario.onActivity { activity = it }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun tearDown() {
        scope.cancel()
        scenario.close()
    }

    private fun show(
        submit: FakeSubmit,
        effects: RecordingEffects,
    ): NotificationFormModel {
        lateinit var model: NotificationFormModel
        main {
            model =
                NotificationFormModel(
                    load = { NotificationFormLoad.Ready(form) },
                    submit = submit::submit,
                    effects = effects,
                    loadScope = scope,
                )
            val owner = HostOwner()
            activity.container.setViewTreeLifecycleOwner(owner)
            activity.container.setViewTreeSavedStateRegistryOwner(owner)
            activity.container.addView(
                ComposeView(activity).apply {
                    setContent {
                        AndChamberTheme {
                            androidx.compose.runtime.LaunchedEffect(model) { model.start() }
                            NotificationFormScreen(model = model, onClose = {}, onOpenSession = {})
                        }
                    }
                },
            )
        }
        awaitTag(NotificationFormTags.field("q2"))
        return model
    }

    @Test fun threeTypedFieldsAreSubmittedOnceAndCloseTheScreen() {
        val submit = FakeSubmit()
        val effects = RecordingEffects()
        val model = show(submit, effects)

        type("q0", "41234")
        type("q1", "123456")
        type("q2", "39899")
        assertEquals("41234", textOf("q0"))
        assertEquals("123456", textOf("q1"))
        assertEquals("39899", textOf("q2"))

        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("sending") { if (readMain { model.submitting }) true else null }
        // A second tap while sending is not a second submit: the button is off and the model ignores it.
        click(findTag(NotificationFormTags.SEND))
        assertFalse("send is off while sending", awaitTag(NotificationFormTags.SEND).isEnabled)
        assertEquals(SendAttempt.Ignored, readMain { model.send(mapOf("q0" to "41234", "q1" to "123456", "q2" to "39899")) })
        assertEquals(context.getString(R.string.form_sending), labelOf(NotificationFormTags.SEND))

        main { submit.release.complete(NotificationFormSubmission(ActionStep.Cancel, null)) }
        await("closed") { if (readMain { model.closed }) true else null }

        assertEquals(1, submit.calls.size)
        assertEquals(mapOf("q0" to "41234", "q1" to "123456", "q2" to "39899"), submit.calls.single())
        assertEquals(listOf("answered"), effects.events.toList())
    }

    @Test fun aBlankRequiredFieldSendsNothingAndIsFocused() {
        val submit = FakeSubmit()
        val effects = RecordingEffects()
        show(submit, effects)

        type("q0", "41234")
        type("q2", "39899")
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))

        assertNotNull("the blank required field is flagged", awaitTag(NotificationFormTags.fieldError("q1")))
        await("q1 focused") { findTag(NotificationFormTags.field("q1"))?.takeIf { it.isFocused } }
        assertTrue("a flagged field is still enabled", awaitTag(NotificationFormTags.SEND).isEnabled)
        assertEquals(0, submit.calls.size)
        assertTrue(effects.events.isEmpty())
        // What was typed stays.
        assertEquals("41234", textOf("q0"))
        assertEquals("39899", textOf("q2"))

        // Typing clears the flag; the next tap sends.
        type("q1", "123456")
        await("flag cleared") { if (findTag(NotificationFormTags.fieldError("q1")) == null) true else null }
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("submitted") { if (submit.calls.size == 1) true else null }
        assertEquals(mapOf("q0" to "41234", "q1" to "123456", "q2" to "39899"), submit.calls.single())
        main { submit.release.complete(NotificationFormSubmission(ActionStep.Cancel, null)) }
    }

    @Test fun aRefusedAnswerKeepsTheInputAndShowsTheStatus() {
        val submit = FakeSubmit()
        val effects = RecordingEffects()
        val model = show(submit, effects)

        type("q0", "41234")
        type("q1", "123456")
        type("q2", "39899")
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("sending") { if (readMain { model.submitting }) true else null }
        main { submit.release.complete(NotificationFormSubmission(ActionStep.KeepSendFailed, "invalid code")) }

        val expected = context.getString(R.string.reply_failed, "invalid code")
        await("status") { findTag(NotificationFormTags.STATUS)?.takeIf { it.text?.toString() == expected } }
        await("send back on") { findTag(NotificationFormTags.SEND)?.takeIf { it.isEnabled } }
        assertEquals("41234", textOf("q0"))
        assertEquals("123456", textOf("q1"))
        assertEquals("39899", textOf("q2"))
        assertFalse(readMain { model.closed })
        assertEquals(1, submit.calls.size)
        assertEquals(listOf("keep:${ActionStep.KeepSendFailed}:invalid code"), effects.events.toList())
    }

    /**
     * A real soft keyboard: the last field is focused, the IME window is up (root insets say so), and
     * Send is whole and above the keyboard. Mirrors the production window: ADJUST_RESIZE and drawn
     * edge to edge (enableEdgeToEdge), so the screen lifts the bottom bar with its own IME padding.
     * A screenshot with the keyboard open is saved for review.
     */
    @Test fun sendStaysWholeAboveTheSoftKeyboard() {
        main {
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        val model = show(FakeSubmit(), RecordingEffects())
        val composeView = readMain { activity.container.getChildAt(0) as ComposeView }
        try {
            type("q0", "41234")
            type("q1", "123456")
            type("q2", "39899")
            await("q2 still focused") { findTag(NotificationFormTags.field("q2"))?.takeIf { it.isFocused } }

            // The keyboard must be shown by focusing the field. Only if it stays down is it asked for
            // once more (some emulators hold it back for a moment); that is recorded.
            var forcedShow = false
            val shown = awaitOrNull(5_000L) { imeInsets(composeView)?.takeIf { it.first && it.second > 0 } }
            if (shown == null) {
                forcedShow = true
                main { WindowCompat.getInsetsController(activity.window, composeView).show(WindowInsetsCompat.Type.ime()) }
            }
            val (visible, imeBottom) = await("ime visible with bottom > 0") { imeInsets(composeView)?.takeIf { it.first && it.second > 0 } }
            // Let the IME animation and the layout that follows settle: the IME bottom and Send stay put.
            val settled = awaitStable { Pair(imeInsets(composeView), sendGeometry(composeView)) }
            val send = settled.second ?: throw AssertionError("send button not in semantics")

            val displayHeight = context.resources.displayMetrics.heightPixels
            val keyboardTop = displayHeight - imeBottom
            val windowInfo =
                readMain {
                    val loc = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
                    val bounds = activity.windowManager.currentWindowMetrics.bounds
                    "decorTopOnScreen=${loc[1]} decorHeight=${activity.window.decorView.height} windowMetrics=$bounds"
                }
            val diagnostics =
                "ime(visible=$visible bottom=$imeBottom) displayHeight=$displayHeight keyboardTop=$keyboardTop " +
                    "send(screen=${send.screen} nodeHeight=${send.nodeHeight} visible=${send.visible} a11y=${send.a11y}) " +
                    "$windowInfo forcedShow=$forcedShow"
            Log.i(LOG_TAG, diagnostics)

            // Whole: the part on screen is as tall as the button itself, by the view and by accessibility.
            assertTrue("send is not on screen: $diagnostics", send.visible != null)
            assertEquals("send is clipped: $diagnostics", send.nodeHeight, send.visible!!.height())
            assertEquals("send a11y bounds clipped: $diagnostics", send.nodeHeight, send.a11y.height())
            // Above the keyboard.
            assertTrue("send overlaps the keyboard: $diagnostics", send.visible.bottom <= keyboardTop)
            assertTrue("send ends below its own visible part: $diagnostics", send.screen.bottom <= keyboardTop)
            assertTrue("send is on but enabled state lost: $diagnostics", awaitTag(NotificationFormTags.SEND).isEnabled)
            assertFalse(readMain { model.closed })

            saveScreenshot("ime-form.png", diagnostics)
        } finally {
            main {
                composeView.clearFocus()
                WindowCompat.getInsetsController(activity.window, composeView).hide(WindowInsetsCompat.Type.ime())
            }
            awaitOrNull(5_000L) { imeInsets(composeView)?.takeIf { !it.first } }
        }
    }

    /** IME visibility and bottom inset (px) from the root insets; null before the view is attached. */
    private fun imeInsets(view: View): Pair<Boolean, Int>? =
        readMain {
            ViewCompat.getRootWindowInsets(view)?.let {
                it.isVisible(WindowInsetsCompat.Type.ime()) to it.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        }

    /** Send on screen: its full bounds, its height, the part the window shows, and accessibility bounds. */
    private data class SendGeometry(
        val screen: Rect,
        val nodeHeight: Int,
        val visible: Rect?,
        val a11y: Rect,
    )

    private fun sendGeometry(composeView: ComposeView): SendGeometry? {
        val a11y = findTag(NotificationFormTags.SEND)?.let { Rect().also(it::getBoundsInScreen) } ?: return null
        return readMain {
            val root = composeView.getChildAt(0) as ViewRootForTest
            val node =
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                    .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == NotificationFormTags.SEND }
                    ?: return@readMain null
            val pos = node.positionOnScreen
            val screen = Rect(pos.x.toInt(), pos.y.toInt(), pos.x.toInt() + node.size.width, pos.y.toInt() + node.size.height)
            // The window's visible part of the compose view, in screen coordinates, cut to the button.
            val viewVisible = Rect()
            val shown = composeView.getGlobalVisibleRect(viewVisible)
            val windowLoc = IntArray(2).also { composeView.rootView.getLocationOnScreen(it) }
            viewVisible.offset(windowLoc[0], windowLoc[1])
            val visible = Rect(screen).takeIf { shown && it.intersect(viewVisible) }
            SendGeometry(screen, node.size.height, visible, a11y)
        }
    }

    /** Saves the whole screen (keyboard included) under the app's external files, for adb pull. */
    private fun saveScreenshot(
        name: String,
        diagnostics: String,
    ) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("screenshot failed: $diagnostics")
        val dir = File(context.getExternalFilesDir(null), "notification-form").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(LOG_TAG, "screenshot ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    /** Waits until [read] returns the same value twice, 300 ms apart. */
    private fun <T> awaitStable(read: () -> T): T {
        var last = read()
        await("stable layout") {
            SystemClock.sleep(300L)
            val next = read()
            if (next == last) next else null.also { last = next }
        }
        return last
    }

    private fun <T> awaitOrNull(
        timeoutMs: Long,
        read: () -> T?,
    ): T? = runCatching { await("optional", timeoutMs, read) }.getOrNull()

    // --- form identity (stateKey) ---

    /** Two forms with the same field keys; only B has a default (q2), so prefill is checked too. */
    private val formA =
        FormAnswer.Fields(
            formId = "frm_A",
            sessionId = "ses_1",
            title = "Form A",
            fields =
                listOf(
                    FormTextField("q0", "First code", null, true, "5 digits", null),
                    FormTextField("q1", "Second code", null, true, null, null),
                    FormTextField("q2", "Third code", null, false, null, null),
                ),
        )
    private val formB =
        FormAnswer.Fields(
            formId = "frm_B",
            sessionId = "ses_1",
            title = "Form B",
            fields =
                listOf(
                    FormTextField("q0", "First code", null, true, "5 digits", null),
                    FormTextField("q1", "Second code", null, true, null, null),
                    FormTextField("q2", "Third code", null, false, null, "default-b"),
                ),
        )
    private val keyA = "ses_1:frm_A:1"
    private val keyB = "ses_1:frm_B:1"

    /** What the screen shows: a form and its identity. Changed in place by the test. */
    private class Shown(
        form: FormAnswer.Fields,
        key: String,
    ) {
        var form by mutableStateOf(form)
        var key by mutableStateOf(key)
    }

    /** Records each Send's values and answers like the model would (missing required → Missing). */
    private class SendRecorder(
        private val shown: Shown,
    ) {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())

        fun send(values: Map<String, String>): SendAttempt {
            calls += shown.key to values
            val missing = PendingForms.missingRequiredFields(shown.form, values)
            return if (missing.isEmpty()) SendAttempt.Ignored else SendAttempt.Missing(missing)
        }
    }

    /**
     * Hosts [NotificationFormContent] for [shown] under a host whose saved state can be captured and
     * restored, like an activity recreation. Returns the new ComposeView.
     */
    private fun host(
        shown: Shown,
        recorder: SendRecorder,
        restored: android.os.Bundle? = null,
    ): Pair<ComposeView, SavedStateRegistryController> {
        lateinit var view: ComposeView
        lateinit var controller: SavedStateRegistryController
        main {
            val owner =
                object : LifecycleOwner, SavedStateRegistryOwner {
                    val registry = LifecycleRegistry(this)
                    val saved = SavedStateRegistryController.create(this)
                    override val lifecycle: Lifecycle get() = registry
                    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
                }
            owner.saved.performAttach()
            // A restore consumes keys from the bundle it is given, so each host gets its own parcelled
            // copy (as a recreated activity would), and one saved state can be restored twice.
            owner.saved.performRestore(restored?.let(::parcelled))
            owner.registry.currentState = Lifecycle.State.RESUMED
            controller = owner.saved
            activity.container.removeAllViews()
            activity.container.setViewTreeLifecycleOwner(owner)
            activity.container.setViewTreeSavedStateRegistryOwner(owner)
            view =
                ComposeView(activity).apply {
                    setContent {
                        AndChamberTheme {
                            NotificationFormContent(
                                phase = FormPhase.Ready(shown.form),
                                status = null,
                                submitting = false,
                                canSend = true,
                                onSend = recorder::send,
                                onRetryLoad = {},
                                onClose = {},
                                onOpenSession = {},
                                stateKey = shown.key,
                            )
                        }
                    }
                }
            activity.container.addView(view)
        }
        awaitTag(NotificationFormTags.field("q2"))
        return view to controller
    }

    /** Switching A → B in place shows B's own defaults; B's required checks still apply; back to A restores A. */
    @Test fun anotherFormIdentityNeverShowsTheFirstFormsAnswers() {
        val shown = Shown(formA, keyA)
        val recorder = SendRecorder(shown)
        val (view, _) = host(shown, recorder)

        type("q0", "41234")
        type("q1", "123456")
        type("q2", "39899")
        assertEquals(listOf("41234", "123456", "39899"), fieldTexts(view))

        main {
            view.clearFocus()
            shown.form = formB
            shown.key = keyB
        }
        await("form B shown") { fieldTexts(view).takeIf { it == listOf("", "", "default-b") } }

        // B's required fields are still required: Send sends nothing and flags both.
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("q0 flagged") { findTag(NotificationFormTags.fieldError("q0")) }
        assertNotNull("q1 flagged", findTag(NotificationFormTags.fieldError("q1")))
        assertEquals(keyB to mapOf("q0" to "", "q1" to "", "q2" to "default-b"), recorder.calls.last())

        // Back to A in the same screen: A's answers return, without B's flags.
        main {
            view.clearFocus()
            shown.form = formA
            shown.key = keyA
        }
        await("form A back") { fieldTexts(view).takeIf { it == listOf("41234", "123456", "39899") } }
        assertEquals(null, findTag(NotificationFormTags.fieldError("q0")))
        assertEquals(null, findTag(NotificationFormTags.fieldError("q1")))
    }

    /**
     * The blocker case: the screen is recreated from saved state (as Activity.recreate does) for
     * another identity. B must start fresh; recreated again for A, A's answers and flags come back.
     */
    @Test fun aRecreatedScreenForAnotherIdentityStartsFresh() {
        val shown = Shown(formA, keyA)
        val recorder = SendRecorder(shown)
        var (view, controller) = host(shown, recorder)

        type("q0", "41234")
        type("q2", "39899")
        // q1 left blank: Send flags it, so A's saved state holds answers and a flag.
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("q1 flagged in A") { findTag(NotificationFormTags.fieldError("q1")) }
        assertEquals(listOf("41234", "", "39899"), fieldTexts(view))

        val savedA = readMain { android.os.Bundle().also { controller.performSave(it) } }

        // Recreated for B from A's saved state.
        main {
            view.clearFocus()
            shown.form = formB
            shown.key = keyB
        }
        host(shown, recorder, restored = savedA).let { (v, c) -> view = v; controller = c }
        await("form B fresh after recreation") { fieldTexts(view).takeIf { it == listOf("", "", "default-b") } }
        // A's flag on q1 is not carried over either.
        assertEquals(null, findTag(NotificationFormTags.fieldError("q1")))
        assertEquals(null, findTag(NotificationFormTags.fieldError("q0")))
        assertTrue("send tap", click(awaitTag(NotificationFormTags.SEND)))
        await("B q0 flagged") { findTag(NotificationFormTags.fieldError("q0")) }
        assertEquals(keyB to mapOf("q0" to "", "q1" to "", "q2" to "default-b"), recorder.calls.last())

        // Recreated for A from the original saved state: same identity, answers restored.
        main {
            view.clearFocus()
            shown.form = formA
            shown.key = keyA
        }
        host(shown, recorder, restored = savedA).let { (v, _) -> view = v }
        await("form A restored after recreation") { fieldTexts(view).takeIf { it == listOf("41234", "", "39899") } }
        assertNotNull("A's q1 flag restored", awaitTag(NotificationFormTags.fieldError("q1")))
        assertEquals(null, findTag(NotificationFormTags.fieldError("q0")))
    }

    /** A deep copy through a Parcel, as saved state crosses an activity recreation. */
    private fun parcelled(bundle: android.os.Bundle): android.os.Bundle {
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    /** The editable text of q0, q1, q2, read from the composition's own semantics (no placeholder). */
    private fun fieldTexts(view: ComposeView): List<String> =
        readMain {
            val nodes = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            listOf("q0", "q1", "q2").map { key ->
                nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == NotificationFormTags.field(key) }
                    ?.config?.getOrNull(SemanticsProperties.EditableText)?.text
                    ?: "<missing $key>"
            }
        }

    // --- helpers ---

    /** Focuses the field with a tap, then types with real key events. */
    private fun type(
        key: String,
        text: String,
    ) {
        val tag = NotificationFormTags.field(key)
        assertTrue("tap $key", click(awaitTag(tag)))
        await("$key focused") { findTag(tag)?.takeIf { it.isFocused } }
        instrumentation.sendStringSync(text)
        instrumentation.waitForIdleSync()
        await("$key = $text") { findTag(tag)?.takeIf { it.text?.toString()?.endsWith(text) == true } }
    }

    private fun textOf(key: String): String? = awaitTag(NotificationFormTags.field(key)).text?.toString()

    /** The visible label inside a node (the button's text). */
    private fun labelOf(tag: String): String? {
        val node = awaitTag(tag)
        fun walk(n: AccessibilityNodeInfo): String? {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        return walk(node)
    }

    private fun enableWindowRetrieval() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 34) runCatching { instrumentation.uiAutomation.clearCache() }
        val list = mutableListOf<AccessibilityNodeInfo>()
        runCatching { instrumentation.uiAutomation.rootInActiveWindow }.getOrNull()?.let { list += it }
        runCatching { instrumentation.uiAutomation.windows }.getOrNull()?.forEach { w -> w.root?.let { list += it } }
        return list
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? {
        val pkg = context.packageName
        val seen = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        val queue = ArrayDeque(roots())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            if (node.packageName?.toString() == pkg && node.viewIdResourceName == tag) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun awaitTag(tag: String): AccessibilityNodeInfo = await("tag $tag") { findTag(tag) }

    private fun <T> await(
        what: String,
        timeoutMs: Long = 10_000L,
        read: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) return current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> readMain(block: () -> T): T {
        var out: Result<T>? = null
        main { out = runCatching(block) }
        return out!!.getOrThrow()
    }
}
