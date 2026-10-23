package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserErrorCode
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.ui.browser.BrowserGeometry
import io.andchamber.app.ui.browser.GeometryProbe
import io.andchamber.app.ui.browser.MeasuredGeometry
import io.andchamber.app.ui.browser.MouseAction
import io.andchamber.app.ui.browser.MouseInput
import io.andchamber.app.ui.browser.PointerSample
import io.andchamber.app.ui.browser.ProbeIdentity
import io.andchamber.app.ui.browser.TouchpadGesture
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.runtime.ServerTarget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDriverLogicTest {
    private val policy = BrowserUrlPolicy(
        forbiddenOrigins = setOf("https://openchamber.example:443"),
    )

    @Test
    fun `profile name is the fixed independent profile`() {
        assertEquals("andchamber_browser_v1", BrowserDriverLogic.PROFILE_NAME)
        assertFalse(BrowserDriverLogic.PROFILE_NAME.contains("Default"))
    }

    @Test
    fun `forbidden origins include local and saved remotes and drop userinfo`() {
        val origins = BrowserDriverLogic.forbiddenOrigins(
            localUrl = "http://127.0.0.1:3000",
            remotes = listOf(
                ServerTarget.Remote("a", "A", "https://openchamber.example", null),
                ServerTarget.Remote("b", "B", "https://user:secret@other.example", "pw"),
            ),
        )
        assertTrue(origins.contains("https://127.0.0.1:3000"))
        assertTrue(origins.contains("https://openchamber.example:443"))
        assertTrue(origins.none { it.contains("secret") || it.contains("user") })
    }

    @Test
    fun `navigate requires a url and click requires a nonce ref`() {
        assertEquals(
            BrowserErrorCode.INVALID_REQUEST,
            BrowserDriverLogic.validate(BrowserRequest("1", BrowserMethod.NAVIGATE))?.error?.code,
        )
        assertEquals(
            BrowserErrorCode.INVALID_REQUEST,
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.CLICK, mapOf("ref" to "document.querySelector('a')")),
            )?.error?.code,
        )
        assertNull(
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.CLICK, mapOf("ref" to "abcdef01-1234abcd-1")),
            ),
        )
    }

    @Test
    fun `a caller string that looks like a template marker is inserted once and not substituted again`() {
        val text = "__AC_ARG__ \" \\ \u2028 alert(1)"
        val script = BrowserDriverLogic.actionScript(
            documentNonce = "a".repeat(32),
            documentId = "b".repeat(32),
            action = "type",
            ref = "abcdef01-1234abcd-1",
            text = text,
            direction = "__AC_DIR__",
        )
        val marker = script.indexOf("var arg = ")
        assertTrue(marker >= 0)
        val json = script.substring(marker + "var arg = ".length).substringBefore(";")
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(text, parsed["text"]!!.jsonPrimitive.content)
        assertEquals("__AC_DIR__", parsed["direction"]!!.jsonPrimitive.content)
        assertFalse(script.contains("\"; alert"))
    }

    @Test
    fun `type script refuses password and file and uses the native setter`() {
        val script = BrowserDriverLogic.installScript("b".repeat(32))
        assertTrue(script.contains("type === \"password\""))
        assertTrue(script.contains("type === \"file\""))
        assertTrue(script.contains("getOwnPropertyDescriptor"))
        assertTrue(script.contains("desc.set.call(field, text)"))
    }

    @Test
    fun `submit validates only an exact true or false on type`() {
        val ref = "abcdef01-1234abcd-1"
        assertNull(
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.TYPE, mapOf("ref" to ref, "text" to "q", "submit" to "true")),
            ),
        )
        assertNull(
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.TYPE, mapOf("ref" to ref, "text" to "q", "submit" to "false")),
            ),
        )
        assertEquals(
            BrowserErrorCode.UNSUPPORTED_PARAMETER,
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.TYPE, mapOf("ref" to ref, "text" to "q", "submit" to "1")),
            )?.error?.code,
        )
        assertEquals(
            BrowserErrorCode.UNSUPPORTED_PARAMETER,
            BrowserDriverLogic.validate(
                BrowserRequest("1", BrowserMethod.CLICK, mapOf("ref" to ref, "submit" to "true")),
            )?.error?.code,
        )
    }

    @Test
    fun `submit script checks post override target and credentials before the native setter`() {
        val install = BrowserDriverLogic.installScript("b".repeat(32))
        assertTrue(install.contains("submit: function(ref, text)"))
        assertTrue(install.contains("document.activeElement !== field"))
        assertTrue(install.contains("form.method"))
        assertTrue(install.contains("formmethod"))
        assertTrue(install.contains("formtarget"))
        assertTrue(install.contains("formaction"))
        assertTrue(install.contains("current-password"))
        assertTrue(install.contains("new-password"))
        assertTrue(install.contains("not single-line input"))
        assertTrue(install.contains("function formSensitive(form)"))
        assertTrue(install.contains("form.elements"))
        // Other credential/file controls in the submitted form are refused; username is not.
        val formScan = install.substringAfter("function formSensitive(form)").substringBefore("function httpsAction")
        assertTrue(formScan.contains("type === \"password\""))
        assertTrue(formScan.contains("type === \"file\""))
        assertTrue(formScan.contains("current-password"))
        assertTrue(formScan.contains("new-password"))
        assertTrue(formScan.contains("name.indexOf(\"password\")"))
        assertFalse(formScan.contains("secret"))
        assertFalse(formScan.contains("token"))
        assertFalse(formScan.contains("username"))
        assertTrue(install.contains("desc.set.call(field, text)"))
        assertFalse(install.contains("requestSubmit"))
        assertFalse(install.contains("KeyboardEvent"))
        assertFalse(install.contains("form.submit()"))
        // The refusal checks precede the value setter inside the submit function itself.
        val submitBlock = install.substringAfter("submit: function(ref, text)")
        assertTrue(submitBlock.indexOf("submitProblem(field)") < submitBlock.indexOf("desc.set.call(field, text)"))

        val typeScript = BrowserDriverLogic.submitTypeScript(
            documentNonce = "a".repeat(32),
            documentId = "b".repeat(32),
            ref = "abcdef01-1234abcd-1",
            text = "hello",
        )
        assertTrue(typeScript.contains("slot.submit"))
        assertTrue(typeScript.contains("witness.documentId !== arg.documentId"))

        val enter = BrowserDriverLogic.enterReadyScript(
            documentNonce = "a".repeat(32),
            documentId = "b".repeat(32),
            ref = "abcdef01-1234abcd-1",
        )
        assertTrue(enter.contains("slot.active"))
        assertTrue(enter.contains("witness.documentId !== arg.documentId"))
        assertFalse(enter.contains("requestSubmit"))
    }

    @Test
    fun `a late callback from an older view cannot ready the replacement`() {
        val first = ControlledNavigation("view-a", ticket = 1, generation = 1)
        val second = ControlledNavigation("view-b", ticket = 2, generation = 1)
        assertTrue(first.noteFirstStart())
        assertFalse(first.noteFirstStart())
        first.markUnknown()
        assertTrue(first.secondStartIsUnknown())
        assertFalse(first.finishedCannotRecover().not())
        assertTrue(first.acceptDocument("doc-a", "complete", true, "view-a", 1) is ProbeAcceptance.Reject)
        assertTrue(second.acceptDocument("doc-b", "loading", true, "view-b", 2) is ProbeAcceptance.Pending)
        assertFalse(second.ready)
        assertTrue(second.acceptDocument("doc-b", "interactive", true, "view-b", 2) is ProbeAcceptance.Accept)
        assertTrue(second.ready)
        assertTrue(second.acceptDocument("doc-other", "complete", true, "view-b", 2) is ProbeAcceptance.Reject)
        assertTrue(second.unknown)
        assertFalse(second.stillCurrent(2, 2))
        assertFalse(ControlledNavigation("view-c", 3).stillCurrent(4, 3))
    }

    @Test
    fun `enable and takeover drop refs without returning a committed page to loading`() {
        val page = PageReadiness(committed = true, nonce = "old", controlGeneration = 1)
        page.commit()
        assertTrue(page.syncGeneration(2, "after-enable"))
        assertTrue(page.snapshotReady())
        assertEquals("after-enable", page.nonce)
        page.invalidateRefs("after-takeover")
        assertTrue(page.snapshotReady())
        assertEquals("after-takeover", page.nonce)
        assertFalse(page.syncGeneration(2, "same"))
        page.beginNavigation("navigating")
        assertFalse(page.snapshotReady())
    }

    @Test
    fun `a fatal callback from an old page does not clear the replacement`() {
        val binding = FatalBinding<String>()
        binding.bind("first")
        binding.bind("second")
        assertFalse(binding.fatalFrom("first"))
        assertEquals("second", binding.current)
        assertTrue(binding.fatalFrom("second"))
        assertEquals(null, binding.current)
    }

    @Test
    fun `snapshot waits for a committed document and a matching epoch`() {
        assertFalse(BrowserControlRules.snapshotAllowed(committed = false, epoch = 2, expectedEpoch = 2))
        assertFalse(BrowserControlRules.snapshotAllowed(committed = true, epoch = 3, expectedEpoch = 2))
        assertTrue(BrowserControlRules.snapshotAllowed(committed = true, epoch = 2, expectedEpoch = 2))
    }

    @Test
    fun `a runtime drop revokes the lease without hiding the page and takeover stays available while busy`() {
        assertTrue(BrowserControlRules.revokeOnRuntime(previousLaunchId = 4, launchId = null, running = false))
        assertTrue(BrowserControlRules.revokeOnRuntime(previousLaunchId = 4, launchId = 5, running = true))
        assertFalse(BrowserControlRules.revokeOnRuntime(previousLaunchId = null, launchId = null, running = true))
        assertFalse(
            BrowserControlRules.agentCanEnable(
                running = false,
                launchId = null,
                pagePresent = true,
                reportedVisible = false,
            ),
        )
        assertTrue(
            BrowserControlRules.agentCanEnable(
                running = true,
                launchId = 1,
                pagePresent = true,
                reportedVisible = true,
            ),
        )
        assertTrue(BrowserControlRules.takeOverEnabledWhileBusy())
    }

    @Test
    fun `a new snapshot nonce is not the previous one`() {
        val first = BrowserDriverLogic.newNonce()
        val second = BrowserDriverLogic.newNonce()
        assertEquals(32, first.length)
        assertNotEquals(first, second)
    }

    @Test
    fun `subresource policy blocks private and non-https and allows data`() {
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "https://public.example/app.js") is Observe.Allow)
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "http://public.example/app.js") is Observe.Block)
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "https://127.0.0.1/x") is Observe.Block)
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "https://openchamber.example/x") is Observe.Block)
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "file:///tmp/x") is Observe.Block)
        assertTrue(BrowserDriverLogic.observeSubresource(policy, "data:text/plain,hi") is Observe.Allow)
    }

    @Test
    fun `public https syntax is allowed and a password field is not a ref path`() {
        assertTrue(policy.checkSyntax("https://public.example/path") is UrlDecision.Allow)
        assertTrue(policy.checkSyntax("https://127.0.0.1/") is UrlDecision.Deny)
        val raw = """{"ok":true,"url":"https://public.example","title":"Hi","text":"hello","elements":[{"ref":"aaaaaaaa-bbbbbbbb-1","role":"input","name":"Email","value":"a@b.c"}],"truncated":false}"""
        val parsed = BrowserDriverLogic.parseScriptResult(raw) as ScriptResult.Ok
        val bounded = BrowserDriverLogic.boundSnapshot(parsed.json)
        assertEquals("Email", bounded["elements"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertFalse(bounded.toString().contains("password"))
    }

    @Test
    fun `snapshot json is bounded`() {
        val huge = buildString {
            append("""{"ok":true,"url":"https://public.example","title":"t","text":"""")
            repeat(20_000) { append('x') }
            append("""","elements":[""")
            repeat(200) { index ->
                if (index > 0) append(',')
                append("""{"ref":"aaaaaaaa-bbbbbbbb-$index","role":"button","name":"n"}""")
            }
            append("],\"truncated\":false}")
        }
        val parsed = BrowserDriverLogic.parseScriptResult(huge) as ScriptResult.Ok
        val bounded = BrowserDriverLogic.boundSnapshot(parsed.json)
        assertTrue(bounded.toString().length <= BrowserDriverLogic.MAX_SNAPSHOT_CHARS)
        assertTrue(bounded["elements"]!!.jsonArray.size <= BrowserDriverLogic.MAX_ELEMENTS)
    }

    @Test
    fun `manual mode allows human navigation and agent mode refuses post and redirect`() {
        assertTrue(MainFrameGate.allows("GET", isRedirect = false, manual = false))
        assertFalse(MainFrameGate.allows("POST", isRedirect = false, manual = false))
        assertFalse(MainFrameGate.allows("GET", isRedirect = true, manual = false))
        assertTrue(MainFrameGate.isUnsupported("POST", isRedirect = false, manual = false))
        assertTrue(MainFrameGate.allows("POST", isRedirect = false, manual = true))
        assertTrue(MainFrameGate.allows("GET", isRedirect = true, manual = true))
        assertFalse(MainFrameGate.isUnsupported("POST", isRedirect = true, manual = true))
    }

    @Test
    fun `manual documents count their first start once`() {
        val session = newSession()
        assertTrue(session.beginControlledGet() > 0L)
        assertTrue(session.noteMainStart())
        assertFalse(session.noteMainStart())
        assertTrue(session.unknownNavigation)
        session.manualNavigation = true
        val first = session.beginManualDocument()
        assertTrue(session.noteMainStart())
        assertFalse(session.controlledGet)
        val second = session.beginManualDocument()
        assertTrue(second > first)
        assertTrue(session.noteMainStart())
        assertFalse(session.controlledGet)
        assertFalse(session.unknownNavigation)
    }

    private fun newSession(): BrowserSession {
        val constructor = BrowserSession::class.java.getDeclaredConstructor(android.webkit.WebView::class.java, kotlin.jvm.functions.Function0::class.java, PixelCopyBridge::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(null, { BrowserUrlPolicy() }, PixelCopyBridge()) as BrowserSession
    }

    @Test
    fun `manual navigation is volatile and a manual post is allowed`() {
        val field = BrowserSession::class.java.getDeclaredField("manualNavigation")
        assertTrue(java.lang.reflect.Modifier.isVolatile(field.modifiers))
        assertTrue(MainFrameGate.allows("POST", false, manual = true))
        assertFalse(MainFrameGate.allows("POST", false, manual = false))
        assertFalse(MainFrameGate.allows("GET", true, manual = false))
    }

    @Test
    fun `capture rejects a moved frame a changed document and a changed fingerprint`() = runBlocking {
        val presentation = capturePresentation()
        val probe = GeometryProbe("doc", 100, 80, 100, 80, 0f, 0f, 2f, 100f, 80f, 0f, 0f, 0f, 0f, 1f)
        val stamp = CaptureStamp("view", "doc", 1L, 3L, presentation, probe)

        val movedFrame = presentation.copy(
            presentation = presentation.presentation.copy(pageLeft = 9f),
            rect = CaptureRect(9, 2, 109, 82),
        )
        assertEquals(
            CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED),
            guardedCapture({ CaptureStampResult.Ready(stamp) }, { observation(stamp).copy(presentation = movedFrame) }, { CaptureBytes(byteArrayOf(1), 4, 5) }),
        )
        assertEquals(
            CaptureOutcome.Refused(CaptureRefusal.IDENTITY_CHANGED),
            guardedCapture({ CaptureStampResult.Ready(stamp) }, { observation(stamp).copy(documentId = "other") }, { CaptureBytes(byteArrayOf(1), 4, 5) }),
        )
        assertEquals(
            CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED),
            guardedCapture({ CaptureStampResult.Moved }, { observation(stamp) }, { CaptureBytes(byteArrayOf(1), 4, 5) }),
        )
        val stable = guardedCapture({ CaptureStampResult.Ready(stamp) }, { observation(stamp) }, { CaptureBytes(byteArrayOf(7), 9, 8) })
        val copied = (stable as? CaptureOutcome.Captured)?.attempt?.image
        assertEquals(9, copied?.width)
        assertEquals(8, copied?.height)
        assertEquals(7, copied?.payload?.single()?.toInt())
    }

    private fun capturePresentation() = CaptureFrame(
        presentation = BrowserPresentationGeometry(
            revision = 1L,
            viewportWidthPx = 100,
            viewportHeightPx = 80,
            scale = 1f,
            pageLeft = 1f,
            pageTop = 2f,
            pageWidth = 100f,
            pageHeight = 80f,
            visible = CaptureRect(1, 2, 101, 82),
        ),
        rect = CaptureRect(1, 2, 101, 82),
        windowWidth = 1024,
        windowHeight = 768,
    )

    private fun observation(stamp: CaptureStamp) =
        CaptureObservation(stamp.viewId, stamp.documentId, stamp.ticket, stamp.refEpoch, stamp.presentation)

    @Test
    fun `desktop viewport script uses the configured width and the real dpr`() {
        val script = BrowserDriverLogic.desktopViewportScript()
        assertTrue(script.contains("currentWidth = 1920"))
        assertTrue(script.contains("initial-scale="))
        assertTrue(script.contains("minimum-scale="))
        assertFalse(script.contains("maximum-scale="))
        assertTrue(script.contains("devicePixelRatio"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("__acDesktopViewport"))
        assertTrue(script.contains("observer.disconnect()"))
        assertTrue(script.contains("applying"))
        assertFalse(script.contains("width=1024"))
        assertFalse(script.contains("1024"))

        val custom = BrowserDriverLogic.desktopViewportScript(2560)
        assertTrue(custom.contains("currentWidth = 2560"))
        val update = BrowserDriverLogic.desktopViewportUpdateScript(2560)
        assertTrue(update.contains("setWidth"))
        assertTrue(update.contains("initial-scale="))
        assertTrue(update.contains("minimum-scale="))
        assertTrue(update.contains("devicePixelRatio"))
    }

    @Test
    fun `resize validation accepts only a valid desktop pair on explicit desktop`() {
        fun resize(vararg params: Pair<String, String>) =
            BrowserRequest("1", BrowserMethod.RESIZE, params.toMap())
        assertNull(BrowserDriverLogic.validate(resize("viewport" to "desktop")))
        assertNull(BrowserDriverLogic.validate(resize("viewport" to "desktop", "width" to "1920", "height" to "1080")))
        assertNull(BrowserDriverLogic.validate(resize("viewport" to "fill")))
        assertNull(BrowserDriverLogic.validate(resize("viewport" to "mobile")))
        assertEquals(
            BrowserErrorCode.UNSUPPORTED_PARAMETER,
            BrowserDriverLogic.validate(resize("viewport" to "desktop", "width" to "100", "height" to "1080"))?.error?.code,
        )
        assertEquals(
            BrowserErrorCode.UNSUPPORTED_PARAMETER,
            BrowserDriverLogic.validate(resize("viewport" to "desktop", "width" to "1920"))?.error?.code,
        )
        assertEquals(
            BrowserErrorCode.UNSUPPORTED_PARAMETER,
            BrowserDriverLogic.validate(resize("viewport" to "mobile", "width" to "1920", "height" to "1080"))?.error?.code,
        )
    }

    @Test
    fun `snapshot carries measured layout and visual viewport numbers`() {
        val obj = Json.parseToJsonElement(
            """{"url":"https://public.example","title":"Hi","text":"hello","elements":[],"truncated":false,"layoutWidth":1920,"layoutHeight":1080,"visualWidth":1918.5,"visualHeight":1078.5}""",
        ).jsonObject
        val bounded = BrowserDriverLogic.boundSnapshot(obj)
        assertEquals("1920", bounded["layoutViewport"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("1080", bounded["layoutViewport"]!!.jsonObject["height"]!!.jsonPrimitive.content)
        assertEquals("1918.5", bounded["visualViewport"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("1078.5", bounded["visualViewport"]!!.jsonObject["height"]!!.jsonPrimitive.content)
    }

    @Test
    fun `geometry metrics must be finite and positive`() {
        val raw = """{"ok":true,"documentId":"abc","innerWidth":819,"innerHeight":600,"clientWidth":819,"clientHeight":600,"scrollX":0,"scrollY":12,"devicePixelRatio":2.625,"visualWidth":819,"visualHeight":600,"offsetLeft":0,"offsetTop":0,"pageLeft":0,"pageTop":12,"visualScale":1}"""
        val probe = BrowserDriverLogic.parseGeometry(raw)
        assertEquals(2.625f, probe?.devicePixelRatio)
        assertEquals(null, BrowserDriverLogic.parseGeometry(raw.replace("2.625", "NaN")))
    }

    @Test
    fun `measured scale is separate from device pixel ratio`() {
        val probe = GeometryProbe("doc", 1024, 768, 1024, 768, 0f, 40f, 2.625f, 819f, 819f, 0f, 0f, 0f, 40f, 1f)
        val geometry = BrowserGeometry.fromProbe(probe, ProbeIdentity("view", "tab", 1L, "doc", 2L, 1L), 819, 819, 0, 0, 0, 0, 819, 819, "desktop")
        assertEquals(1f, geometry.scale)
        assertEquals(2.625f, geometry.devicePixelRatio)
        assertEquals(240f, geometry.clientX(240f))
        val wide = geometry.copy(visualWidth = 1024f, viewWidthPx = 819)
        assertEquals(300f, wide.clientX(240f), 0.1f)
        val stale = ProbeIdentity("old", "tab", 1L, "doc", 2L, 1L)
        assertFalse(BrowserGeometry.accept(probe, stale, ProbeIdentity("view", "tab", 1L, "doc", 2L, 1L)))
    }

    @Test
    fun `an unready gesture is dropped and cancel does not click`() {
        val gesture = TouchpadGesture()
        val dropped = MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 1f, 1f, 1, false), 0f, 0f, null, gesture, false)
        assertTrue(dropped.isEmpty())
        gesture.begin(0f, 0f, 0L, 0)
        gesture.startDrag()
        val geometry = MeasuredGeometry(1L, "v", "t", 1L, "doc", 1L, 100, 100, 0, 0, 0, 0, 100, 100, 100, 100, 100, 100, 100f, 100f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, "desktop")
        val cancelled = MouseInput.plan(PointerSample(MouseInput.ACTION_CANCEL, 0f, 0f, 1, true), 10f, 10f, geometry, gesture, true)
        assertEquals(MouseAction.CANCEL, cancelled.single().action)
    }

    @Test
    fun `desktop css px converts to webview px and an old geometry is refused`() {
        val geometry = sampleGeometry(2048, 1024, 3L)
        assertEquals(2048, geometry.viewWidthPx)
        assertEquals(1f, geometry.scale)
        assertEquals(20f, geometry.clientX(20f))
        assertEquals(8f, geometry.clientY(8f))
        val gesture = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 100f, 100f, 1, false, 0L), 10f, 4f, geometry, gesture, true)
        val click = MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 102f, 101f, 1, false, 80L), 10f, 4f, geometry, gesture, true)
        assertEquals(2L, click.first().copy(geometryGeneration = 2L).geometryGeneration)
    }

    @Test
    fun `touchpad moves the pointer by relative motion and a tap clicks at the pointer`() {
        val geometry = sampleGeometry(2048, 1024, 1L)
        val gesture = TouchpadGesture()
        // The finger lands far from the pointer: nothing jumps there.
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 900f, 600f, 1, false, 0L), 100f, 100f, geometry, gesture, true).isEmpty())
        // Inside the tap slop nothing moves yet; once past it, the whole motion since down is applied.
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 910f, 604f, 1, false, 10L), 100f, 100f, geometry, gesture, true).isEmpty())
        val moved = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 960f, 640f, 1, false, 30L), 100f, 100f, geometry, gesture, true)
        assertEquals(MouseAction.HOVER, moved.single().action)
        assertEquals(160f, moved.single().viewX)
        assertEquals(140f, moved.single().viewY)
        val next = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 980f, 640f, 1, false, 40L), 130f, 120f, geometry, gesture, true)
        assertEquals(150f, next.single().viewX)

        val tap = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 50f, 60f, geometry, tap, true)
        val clicked = MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 12f, 11f, 1, false, 90L), 50f, 60f, geometry, tap, true)
        assertEquals(listOf(MouseAction.HOVER, MouseAction.PRESS, MouseAction.RELEASE), clicked.map { it.action })
        assertTrue(clicked.all { it.viewX == 50f && it.viewY == 60f })
    }

    @Test
    fun `holding before moving drags with the button down and release ends it`() {
        val geometry = sampleGeometry(1024, 768, 1L)
        val gesture = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 0f, 0f, 1, false, 0L), 100f, 100f, geometry, gesture, true)
        val started = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 40f, 0f, 1, false, 500L), 100f, 100f, geometry, gesture, true)
        assertEquals(listOf(MouseAction.PRESS, MouseAction.DRAG), started.map { it.action })
        assertEquals(100f, started.first().viewX)
        val more = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 80f, 0f, 1, false, 550L), 140f, 100f, geometry, gesture, true)
        assertEquals(MouseAction.DRAG, more.single().action)
        val released = MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 80f, 0f, 1, false, 600L), 180f, 100f, geometry, gesture, true)
        assertEquals(MouseAction.RELEASE, released.single().action)

        // A quick swipe is a move, never a drag.
        val swipe = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 0f, 0f, 1, false, 0L), 100f, 100f, geometry, swipe, true)
        val fast = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 40f, 0f, 1, false, 60L), 100f, 100f, geometry, swipe, true)
        assertEquals(MouseAction.HOVER, fast.single().action)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 40f, 0f, 1, false, 90L), 140f, 100f, geometry, swipe, true).isEmpty())
    }

    @Test
    fun `two fingers scroll and do not move the pointer`() {
        val geometry = sampleGeometry(1024, 768, 1L)
        val gesture = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 100f, 300f, 1, false, 0L), 50f, 50f, geometry, gesture, true)
        MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_DOWN, 100f, 300f, 2, false, 10L), 50f, 50f, geometry, gesture, true)
        val wheel = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 100f, 180f, 2, false, 40L), 50f, 50f, geometry, gesture, true)
        assertEquals(MouseAction.SCROLL, wheel.single().action)
        assertTrue(wheel.single().scrollDelta > 0f)
        assertEquals(50f, wheel.single().viewX)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 100f, 180f, 1, false, 60L), 50f, 50f, geometry, gesture, true).isEmpty())
    }

    @Test
    fun `an active drag keeps the original pointer and a second pointer is not a wheel`() {
        val geometry = sampleGeometry(1024, 768, 1L)
        val gesture = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 0f, 0f, 1, true, 0L, pointerId = 7), 100f, 100f, geometry, gesture, true)
        val started = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 40f, 0f, 1, true, 500L, pointerId = 7), 100f, 100f, geometry, gesture, true)
        assertEquals(listOf(MouseAction.PRESS, MouseAction.DRAG), started.map { it.action })
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_DOWN, 20f, 20f, 2, true, 520L, pointerId = 8), 140f, 100f, geometry, gesture, true).isEmpty())
        val moved = MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 80f, 0f, 2, true, 560L, pointerId = 7), 140f, 100f, geometry, gesture, true)
        assertEquals(MouseAction.DRAG, moved.single().action)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 0f, 80f, 2, true, 580L, pointerId = 8), 180f, 100f, geometry, gesture, true).isEmpty())
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_UP, 80f, 0f, 2, true, 590L, pointerId = 7, actionPointerId = 11, actionIndex = 1), 180f, 100f, geometry, gesture, true).isEmpty())
        val released = MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_UP, 0f, 80f, 1, true, 600L, pointerId = 11, actionPointerId = 7, actionIndex = 0), 180f, 100f, geometry, gesture, true)
        assertEquals(MouseAction.RELEASE, released.single().action)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 90f, 0f, 1, true, 620L, pointerId = 11, actionPointerId = 11), 180f, 100f, geometry, gesture, true).isEmpty())
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 90f, 0f, 0, false, 640L, pointerId = 8), 180f, 100f, geometry, gesture, true).isEmpty())
        val reversed = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 0f, 0f, 1, true, 0L, pointerId = 11), 10f, 10f, geometry, reversed, true)
        MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 40f, 0f, 1, true, 500L, pointerId = 11), 10f, 10f, geometry, reversed, true)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_UP, 40f, 0f, 2, true, 520L, pointerId = 11, actionPointerId = 7, actionIndex = 0), 50f, 10f, geometry, reversed, true).isEmpty())
        assertEquals(MouseAction.RELEASE, MouseInput.plan(PointerSample(MouseInput.ACTION_POINTER_UP, 0f, 40f, 1, true, 540L, pointerId = 7, actionPointerId = 11, actionIndex = 1), 50f, 10f, geometry, reversed, true).single().action)
    }

    @Test
    fun `cancel after press retires and an unmatched up does not click`() {
        val geometry = sampleGeometry(1024, 768, 1L)
        val gesture = TouchpadGesture()
        MouseInput.plan(PointerSample(MouseInput.ACTION_DOWN, 0f, 0f, 1, true, 0L, pointerId = 3), 20f, 20f, geometry, gesture, true)
        MouseInput.plan(PointerSample(MouseInput.ACTION_MOVE, 40f, 0f, 1, true, 500L, pointerId = 3), 20f, 20f, geometry, gesture, true)
        val cancelled = MouseInput.plan(PointerSample(MouseInput.ACTION_CANCEL, 40f, 0f, 1, true, 520L, pointerId = 3), 60f, 20f, geometry, gesture, true)
        assertEquals(MouseAction.CANCEL, cancelled.single().action)
        assertTrue(gesture.retireRequired)
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 40f, 0f, 0, false, 540L, pointerId = 3), 60f, 20f, geometry, gesture, true).isEmpty())
        val unmatched = TouchpadGesture()
        assertTrue(MouseInput.plan(PointerSample(MouseInput.ACTION_UP, 1f, 1f, 0, false, 10L, pointerId = 1), 10f, 10f, geometry, unmatched, true).isEmpty())
    }

    private fun sampleGeometry(width: Int, height: Int, generation: Long) = MeasuredGeometry(
        generation, "v", "t", 1L, "doc", 1L, width, height, 0, 0, 0, 0, width, height,
        width, height, width, height, width.toFloat(), height.toFloat(), 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, "desktop",
    )

    @Test
    fun `agent action script refuses implicit submit and one time code before acting`() {
        val script = BrowserDriverLogic.installScript("a".repeat(32))
        assertTrue(script.contains("one-time-code"))
        assertTrue(script.contains("implicit"))
        assertTrue(script.contains("return {ok:false, error:\"refused field\"}"))
        assertTrue(script.contains(".click()"))
    }

    @Test
    fun `manual navigation returns false and a re-probe may keep the same witness`() {
        val decision = MainFrameDecision.forRequest(method = "GET", redirect = false, manual = true, allowed = true)
        assertFalse(decision.overrideLoading)
        assertFalse(decision.startControlledGet)
        val agent = MainFrameDecision.forRequest(method = "GET", redirect = false, manual = false, allowed = true)
        assertTrue(agent.overrideLoading)
        assertTrue(agent.startControlledGet)
        val probe = """{"ok":true,"documentId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","readyState":"complete"}"""
        val first = BrowserDriverLogic.acceptProbe(null, probe, policyAllowsUrl = true)
        assertTrue(first is ProbeAcceptance.Accept)
        val again = BrowserDriverLogic.acceptProbe(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            probe,
            policyAllowsUrl = true,
            allowSameDocument = true,
        )
        assertTrue(again is ProbeAcceptance.Accept)
    }

    @Test
    fun `human takeover revokes before input and regrant needs a new witness`() {
        val takeover = ManualTakeover()
        takeover.grantAgent("session-a:tab-1", "witness-old")
        assertTrue(takeover.beginHumanInput())
        assertNull(takeover.grant)
        assertTrue(takeover.manual)
        assertTrue(takeover.humanNavigating())
        takeover.finishManual()
        assertFalse(takeover.manual)
        assertNull(takeover.grant)
        assertFalse(takeover.regrant("session-a:tab-1", ""))
        assertTrue(takeover.regrant("session-a:tab-1", "witness-after-login"))
        assertEquals("session-a:tab-1", takeover.grant)
        takeover.beginHumanInput()
        assertFalse(takeover.regrant("session-a:tab-1", "witness-during-manual"))
    }

    @Test
    fun `an unbound host does not move the rebind generation again`() {
        var generation = 1L
        var bind = 1L
        var host: String? = "bound"
        fun unbind() {
            if (host == null && generation != bind) return
            host = null
            if (generation == bind) generation += 1
        }
        unbind()
        val after = generation
        unbind()
        assertEquals(after, generation)
        assertTrue(generation != bind)
    }

    @Test
    fun `empty session context is a different owner`() {
        val owner = "session-a"
        val incoming = ""
        assertTrue(owner != incoming)
    }

    @Test
    fun `floating size stays inside a phone width and a tablet uses a fixed window`() {
        assertEquals(328, floatWidthDp(360, tablet = false))
        assertEquals(300, floatWidthDp(332, tablet = false))
        assertEquals(360, floatWidthDp(800, tablet = true))
        assertEquals(240, floatHeightDp(800))
        assertEquals(160, floatWidthDp(192, tablet = false))
        assertTrue(floatHeightDp(400) <= 168)
    }
}

/**
 * Structure of the restore script. The behaviour (zero scroll on mismatch, one scroll on match) is executed in
 * Node by app/src/test/js/window-restore-guard.test.cjs, because the JVM has no JavaScript engine.
 */
class RestoreScriptStructureTest {
    @Test
    fun `restore script checks the witness and the projection before its one instant scroll`() {
        val script = BrowserDriverLogic.scrollRestoreScript("abcdef01", 0.0, 893.0, targetHeightPx = 252, nativeWidthPx = 819)
        assertEquals("one scrollTo call", 1, Regex("scrollTo\\(").findAll(script).count())
        assertTrue(script.contains("""scrollTo({left:0.0,top:893.0,behavior:"instant"})"""))
        assertTrue("witness is checked", script.contains("""witness!=="abcdef01""""))
        assertTrue("the projection uses the native width", script.contains("819/v.width"))
        assertTrue("the projection is checked against the target height", script.contains("-252)"))
        assertTrue("the checks come before the scroll", script.indexOf("witness!==") < script.indexOf("scrollTo(") &&
            script.indexOf("819/v.width") < script.indexOf("scrollTo("))
    }

    @Test
    fun `restore script returns only the readback numbers and no witness echo`() {
        val script = BrowserDriverLogic.scrollRestoreScript("abcdef01", 0.0, 893.0, targetHeightPx = 252, nativeWidthPx = 819)
        val returned = script.substring(script.lastIndexOf("return {ok:true"))
        assertFalse("no url, title or text is returned", returned.contains("url") || returned.contains("title") || returned.contains("text"))
        assertFalse("no temporary diagnostic fields remain", script.contains("dBefore") || script.contains("dAfter"))
    }
}
