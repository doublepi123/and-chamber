package io.andchamber.app.browser.provider

import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.ui.browser.BrowserDriverLogic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPageAdapterTest {
    private val ref = "abcdef01-1234abcd-1"
    private val other = "abcdef01-1234abcd-2"

    @Test
    fun `unauthorized snapshot and capture do not touch the page`() = runBlocking {
        val driver = FakeDriver()
        val pages = adapter(driver)
        val registry = ProviderRegistry()
        val denied = pages.execute(request("browser.snapshot"), registry, null, ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, denied.errorCode)
        assertEquals(0, pages.reads)
        assertEquals(0, pages.captures)
        assertEquals(0, driver.calls)
        val shot = pages.execute(request("browser.capture"), registry, null, ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, shot.errorCode)
        assertEquals(0, pages.captures)
    }

    @Test
    fun `open presents a controlled get and does not grant`() = runBlocking {
        val driver = FakeDriver()
        var presented = 0
        val pages = adapter(driver) { presented += 1; true }
        val registry = ProviderRegistry { "tab-fixed" }
        val opened = pages.execute(
            request("browser.open", mapOf("url" to "https://public.example/login")),
            registry,
            null,
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(opened.ok)
        assertEquals(true, opened.data["opened"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("tab-fixed", opened.data["tabId"]!!.jsonPrimitive.content)
        assertEquals(false, opened.data["drivable"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("authorization_required", opened.data["reason"]!!.jsonPrimitive.content)
        assertEquals(1, presented)
        assertTrue(driver.methods.isEmpty())
        assertNull(registry.currentGrant())
        assertEquals(0, pages.reads)
    }

    @Test
    fun `grant then snapshot is drivable and uses only native refs`() = runBlocking {
        val driver = FakeDriver()
        val pages = adapter(driver)
        val registry = ProviderRegistry { "tab-1" }
        val tab = registry.open("s1", "/proj", "local", "https://public.example", null)
        registry.grant(1L, "c1", "s1", tab.tabId)
        val snap = pages.execute(request("browser.snapshot"), registry, tab.tabId, ProviderMode.AGENT, 1_000L)
        assertTrue(snap.ok)
        assertEquals(true, snap.data["drivable"]!!.jsonPrimitive.booleanOrNull)
        val element = snap.data["elements"]!!.jsonArray.single().jsonObject
        assertEquals("""[data-andchamber-ref="$ref"]""", element["selector"]!!.jsonPrimitive.content)
        assertEquals("Sign in", element["name"]!!.jsonPrimitive.content)
        assertEquals(1, pages.reads)
        assertEquals(BrowserMethod.SNAPSHOT, driver.methods.single())
    }

    @Test
    fun `click accepts only the current snapshot ref and rejects stale or css`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver)
        val current = """[data-andchamber-ref="$ref"]"""
        val clicked = pages.second.execute(
            request("browser.click", mapOf("selector" to current)),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(clicked.ok)
        assertEquals(ref, driver.lastRef)

        driver.snapshotJson = snapshotJson(other, "Other")
        pages.second.execute(request("browser.snapshot"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        val stale = pages.second.execute(
            request("browser.click", mapOf("selector" to current)),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.REF_EXPIRED, stale.errorCode)

        val before = driver.calls
        val css = pages.second.execute(
            request("browser.click", mapOf("selector" to "button.sign-in")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, css.errorCode)
        val extra = pages.second.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$other"] div""")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, extra.errorCode)
        assertEquals(before, driver.calls)
    }

    @Test
    fun `click text requires one current name`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver)
        val unique = pages.second.execute(
            request("browser.click", mapOf("text" to "Sign in")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(unique.ok)
        assertEquals(ref, driver.lastRef)

        driver.snapshotJson = """{"url":"https://public.example","title":"Hi","text":"x","elements":[
            {"ref":"$ref","role":"button","name":"Go"},
            {"ref":"$other","role":"link","name":"Go"}
        ]}"""
        pages.second.execute(request("browser.snapshot"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        val ambiguous = pages.second.execute(
            request("browser.click", mapOf("text" to "Go")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, ambiguous.errorCode)
    }

    @Test
    fun `type rejects submit sensitive and credential fields without calling the driver`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver, snapshotJson(ref, "Password", role = "input", type = "password"))
        val before = driver.calls
        val password = pages.second.execute(
            request("browser.type", mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "secret")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, password.errorCode)
        val submit = pages.second.execute(
            request("browser.type", mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "a", "submit" to "true")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, submit.errorCode)
        assertEquals(before, driver.calls)
        val note = granted(FakeDriver(), snapshotJson(ref, "Note", role = "input", type = "text"))
        val typed = note.second.execute(
            request("browser.type", mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "hello", "submit" to "false")),
            note.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(typed.ok)
    }

    @Test
    fun `type submit true forwards one flag and returns enterDispatched`() = runBlocking {
        val driver = FakeDriver()
        driver.typeReply = BrowserReply(
            ok = true,
            result = mapOf(BrowserDriverLogic.RESULT_ENTER_DISPATCHED to "true"),
        )
        val pages = granted(driver, snapshotJson(ref, "Query", role = "input", type = "text"))
        val result = pages.second.execute(
            request(
                "browser.type",
                mapOf(
                    "selector" to """[data-andchamber-ref="$ref"]""",
                    "value" to "hello-search",
                    "submit" to "true",
                ),
            ),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(result.ok)
        assertEquals("true", driver.lastParams[BrowserDriverLogic.PARAM_SUBMIT])
        assertEquals("hello-search", driver.lastParams[BrowserDriverLogic.PARAM_TEXT])
        assertEquals(1_000L, driver.lastRequest?.timeoutMs)
        assertEquals(true, result.data["enterDispatched"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `type missing or false submit keeps the original action`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver, snapshotJson(ref, "Query", role = "input", type = "text"))
        val missing = pages.second.execute(
            request("browser.type", mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "a")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(missing.ok)
        assertFalse(driver.lastParams.containsKey(BrowserDriverLogic.PARAM_SUBMIT))
        val falseSubmit = pages.second.execute(
            request(
                "browser.type",
                mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "b", "submit" to "false"),
            ),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(falseSubmit.ok)
        assertFalse(driver.lastParams.containsKey(BrowserDriverLogic.PARAM_SUBMIT))
        assertNull(falseSubmit.data["enterDispatched"])
    }

    @Test
    fun `type submit refuses a credential autocomplete field without calling the driver`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver, snapshotJson(ref, "Password", role = "input", type = "current-password"))
        val refused = pages.second.execute(
            request(
                "browser.type",
                mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "do-not-submit", "submit" to "true"),
            ),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refused.errorCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `submit is rejected outside type and for a non-boolean value`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver, snapshotJson(ref, "Query", role = "input", type = "text"))
        val click = pages.second.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$ref"]""", "submit" to "true")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, click.errorCode)
        val bad = pages.second.execute(
            request(
                "browser.type",
                mapOf("selector" to """[data-andchamber-ref="$ref"]""", "value" to "x", "submit" to "1"),
            ),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, bad.errorCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `open and resize reject an invalid or unpaired desktop size before load`() = runBlocking {
        val driver = FakeDriver()
        var presented = 0
        val pages = OpenChamberPageAdapter(
            driver,
            present = { presented += 1; true },
            viewportSize = { 12 to 34 },
        )
        val registry = ProviderRegistry { "tab-1" }
        suspend fun open(vararg params: Pair<String, String>) = pages.execute(
            request("browser.open", mapOf("url" to "https://public.example") + params.toMap()),
            registry,
            null,
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, open("viewport" to "desktop", "width" to "100", "height" to "1080").errorCode)
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, open("viewport" to "desktop", "width" to "1920").errorCode)
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, open("viewport" to "mobile", "width" to "1920", "height" to "1080").errorCode)
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, open("width" to "1920", "height" to "1080").errorCode)
        assertEquals(0, presented)
        assertTrue(registry.tabs().isEmpty())
        val valid = open("viewport" to "desktop", "width" to "2560", "height" to "1440")
        assertTrue(valid.ok)
        assertEquals(1, presented)
    }

    @Test
    fun `resize validates dimensions and forwards the real configure request`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver, snapshotJson(ref, "Query", role = "input", type = "text"))
        val before = driver.calls
        val invalid = pages.second.execute(
            request("browser.resize", mapOf("viewport" to "desktop", "width" to "100", "height" to "1080")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, invalid.errorCode)
        assertEquals(before, driver.calls)
        val mobileDims = pages.second.execute(
            request("browser.resize", mapOf("viewport" to "mobile", "width" to "1920", "height" to "1080")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, mobileDims.errorCode)
        assertEquals(before, driver.calls)
        val valid = pages.second.execute(
            request("browser.resize", mapOf("viewport" to "desktop", "width" to "2560", "height" to "1440")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(valid.ok)
        assertEquals(BrowserMethod.RESIZE, driver.lastRequest?.method)
        assertEquals("2560", driver.lastRequest?.params?.get(BrowserDriverLogic.PARAM_WIDTH))
        assertEquals("1440", driver.lastRequest?.params?.get(BrowserDriverLogic.PARAM_HEIGHT))
        assertEquals(2560, valid.data["desktopWidth"]!!.jsonPrimitive.content.toInt())
        assertEquals(1440, valid.data["desktopHeight"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `snapshot forwards measured layout and visual viewport objects`() = runBlocking {
        val driver = FakeDriver()
        val json = """{"url":"https://public.example","title":"Hi","text":"hello","elements":[],"layoutViewport":{"width":1920,"height":1080},"visualViewport":{"width":1918.5,"height":1078.5}}"""
        val pages = granted(driver, json)
        val snap = pages.second.execute(request("browser.snapshot"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertTrue(snap.ok)
        assertEquals("1920", snap.data["layoutViewport"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("1080", snap.data["layoutViewport"]!!.jsonObject["height"]!!.jsonPrimitive.content)
        assertEquals("1918.5", snap.data["visualViewport"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("1078.5", snap.data["visualViewport"]!!.jsonObject["height"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tabs include id title url owner and active`() = runBlocking {
        val driver = FakeDriver()
        val pages = adapter(driver)
        val registry = ProviderRegistry { "tab-1" }
        registry.open("s1", "/proj", "local", "https://public.example", "tab-1")
        registry.updateTitle("tab-1", "Login")
        registry.grant(1L, "c1", "s1", "tab-1")
        val snap = pages.execute(request("browser.snapshot"), registry, "tab-1", ProviderMode.AGENT, 1_000L)
        val tab = snap.data["tabs"]!!.jsonArray.single().jsonObject
        assertEquals("tab-1", tab["id"]!!.jsonPrimitive.content)
        assertEquals("Login", tab["title"]!!.jsonPrimitive.content)
        assertEquals("https://public.example", tab["url"]!!.jsonPrimitive.content)
        assertEquals("s1", tab["owner"]!!.jsonPrimitive.content)
        assertEquals(true, tab["active"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `click checks the current dom before a form submission`() = runBlocking {
        val driver = FakeDriver()
        val pages = OpenChamberPageAdapter(
            driver,
            viewportSize = { 12 to 34 },
            documentFields = { requested -> if (requested == ref) mapOf(ref to PageField("button", "submit", "Sign in", submit = true)) else null },
        )
        val registry = ProviderRegistry { "tab-1" }
        registry.open("s1", "/proj", "local", "https://public.example", "tab-1")
        registry.grant(1L, "c1", "s1", "tab-1")
        pages.execute(request("browser.snapshot"), registry, "tab-1", ProviderMode.AGENT, 1_000L)
        val before = driver.calls
        val refused = pages.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$ref"]""")),
            registry,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refused.errorCode)
        assertEquals(before, driver.calls)
    }

    @Test
    fun `capture returns the gate fields`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver)
        val shot = pages.second.execute(request("browser.capture"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertTrue(shot.ok)
        assertEquals("aGVsbG8=", shot.data["base64"]!!.jsonPrimitive.content)
        assertEquals("image/png", shot.data["mime"]!!.jsonPrimitive.content)
        assertEquals("https://public.example", shot.data["url"]!!.jsonPrimitive.content)
        assertEquals("Hi", shot.data["title"]!!.jsonPrimitive.content)
        assertEquals(12, shot.data["width"]!!.jsonPrimitive.content.toInt())
        assertEquals(34, shot.data["height"]!!.jsonPrimitive.content.toInt())
        assertEquals("fill", shot.data["viewport"]!!.jsonPrimitive.content)
        assertEquals(1, pages.second.captures)
    }

    @Test
    fun `unsupported actions and parameters stay explicit after grant`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver)
        val inspect = pages.second.execute(request("browser.inspect"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.UNSUPPORTED_ACTION, inspect.errorCode)
        val back = pages.second.execute(request("browser.back"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.UNSUPPORTED_ACTION, back.errorCode)
        val resize = pages.second.execute(
            request("browser.resize", mapOf("viewport" to "desktop")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(resize.ok)
        val local = pages.second.execute(
            request("browser.snapshot", mapOf("selector" to """[data-andchamber-ref="$ref"]""")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, local.errorCode)
        val scrolled = pages.second.execute(
            request("browser.scroll", mapOf("direction" to "down", "selector" to """[data-andchamber-ref="$ref"]""")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, scrolled.errorCode)
        val openViewport = pages.second.execute(
            request("browser.open", mapOf("url" to "https://public.example", "viewport" to "desktop")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(openViewport.ok)
        assertFalse(driver.methods.contains(BrowserMethod.SNAPSHOT))
        assertFalse(driver.methods.contains(BrowserMethod.SCROLL))
    }

    @Test
    fun `a real driver snapshot map is converted without executing the selector`() = runBlocking {
        val driver = FieldDriver()
        val pages = adapter(driver)
        val registry = ProviderRegistry { "tab-1" }
        val tab = registry.open("s1", "/proj", "local", "https://public.example", "tab-1")
        registry.grant(1L, "c1", "s1", tab.tabId)
        val snap = pages.handle(request("browser.snapshot"), registry, tab.tabId, ProviderMode.AGENT, 1_000L)
        val element = snap["elements"]!!.jsonArray.single().jsonObject
        assertEquals("""[data-andchamber-ref="$ref"]""", element["selector"]!!.jsonPrimitive.content)
        assertEquals(true, snap["drivable"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(driver.scripts.none { it.contains("querySelector") || it.contains(element["selector"]!!.jsonPrimitive.content) })
    }

    @Test
    fun `manual mode refuses agent reads even if a grant object remains`() = runBlocking {
        val driver = FakeDriver()
        val pages = granted(driver)
        val denied = pages.second.execute(
            request("browser.snapshot"),
            pages.first,
            "tab-1",
            ProviderMode.MANUAL,
            1_000L,
        )
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, denied.errorCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `a malformed snapshot is unknown without a page-level callback`() = runBlocking {
        val driver = FakeDriver()
        var unknown = 0
        val pages = granted(driver, onOutcomeUnknown = { unknown += 1 })
        driver.snapshotJson = "not-json"
        val snap = pages.second.execute(request("browser.snapshot"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, snap.errorCode)
        assertEquals("a read failure never marks the document uncertain", 0, unknown)
    }

    @Test
    fun `a scroll with no reply or a cancelled scroll is unknown without a page-level callback`() = runBlocking {
        val driver = FakeDriver()
        var unknown = 0
        val pages = granted(driver, onOutcomeUnknown = { unknown += 1 })
        driver.failing = setOf(BrowserMethod.SCROLL)
        val before = driver.calls
        val failed = pages.second.execute(
            request("browser.scroll", mapOf("direction" to "down")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, failed.errorCode)
        assertEquals("the increment is sent exactly once", before + 1, driver.calls)
        assertEquals("a failed scroll keeps the page trustworthy", 0, unknown)

        driver.failing = emptySet()
        driver.cancelling = setOf(BrowserMethod.SCROLL)
        val afterFailure = driver.calls
        var cancelled = false
        try {
            pages.second.execute(
                request("browser.scroll", mapOf("direction" to "down")),
                pages.first,
                "tab-1",
                ProviderMode.AGENT,
                1_000L,
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("the cancellation still propagates", cancelled)
        assertEquals("a cancelled scroll is not replayed", afterFailure + 1, driver.calls)
        assertEquals("a cancelled scroll keeps the page trustworthy", 0, unknown)
    }

    @Test
    fun `a click with no reply is unknown and marks the document uncertain`() = runBlocking {
        val driver = FakeDriver()
        var unknown = 0
        val pages = granted(driver, onOutcomeUnknown = { unknown += 1 })
        driver.failing = setOf(BrowserMethod.CLICK)
        val clicked = pages.second.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$ref"]""")),
            pages.first,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, clicked.errorCode)
        assertEquals("an unobserved click effect is page-level uncertainty", 1, unknown)
    }

    @Test
    fun `a capture without geometry is unavailable without a page-level callback`() = runBlocking {
        val driver = FakeDriver()
        var unknown = 0
        val pages = granted(driver, onOutcomeUnknown = { unknown += 1 })
        driver.captureNoGeometry = true
        val shot = pages.second.execute(request("browser.capture"), pages.first, "tab-1", ProviderMode.AGENT, 1_000L)
        assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, shot.errorCode)
        assertEquals(1, pages.second.captures)
        assertEquals("a capture geometry failure is not a document uncertainty", 0, unknown)
    }

    private fun adapter(driver: BrowserDriver, present: suspend (String) -> Boolean = { true }) =
        OpenChamberPageAdapter(driver, present = { tab -> present(tab.url) }, viewportSize = { 12 to 34 })

    private fun granted(
        driver: FakeDriver,
        json: String = snapshotJson(ref, "Sign in"),
        onOutcomeUnknown: () -> Unit = {},
    ): Pair<ProviderRegistry, OpenChamberPageAdapter> {
        driver.snapshotJson = json
        val pages = OpenChamberPageAdapter(driver, viewportSize = { 12 to 34 }, onOutcomeUnknown = onOutcomeUnknown)
        val registry = ProviderRegistry { "tab-1" }
        registry.open("s1", "/proj", "local", "https://public.example", "tab-1")
        registry.grant(1L, "c1", "s1", "tab-1")
        runBlocking { pages.execute(request("browser.snapshot"), registry, "tab-1", ProviderMode.AGENT, 1_000L) }
        driver.methods.clear()
        driver.calls = 0
        return registry to pages
    }

    private fun request(action: String, parameters: Map<String, String> = emptyMap()) = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = "r1",
        action = action,
        parameters = parameters,
        directory = "/proj",
        sessionId = "s1",
        timeoutMs = 1_000,
    )

    private fun snapshotJson(ref: String, name: String, role: String = "button", type: String = ""): String {
        val typeField = if (type.isEmpty()) "" else ""","type":"$type""""
        return """{"url":"https://public.example","title":"Hi","text":"hello","elements":[{"ref":"$ref","role":"$role","name":"$name"$typeField}]}"""
    }

    private class FakeDriver : BrowserDriver {
        var calls = 0
        val methods = mutableListOf<BrowserMethod>()
        var lastRef: String? = null
        var lastParams: Map<String, String> = emptyMap()
        var lastRequest: BrowserRequest? = null
        var typeReply: BrowserReply? = null
        var failing: Set<BrowserMethod> = emptySet()
        var cancelling: Set<BrowserMethod> = emptySet()
        var captureNoGeometry = false
        var snapshotJson: String = """{"url":"https://public.example","title":"Hi","text":"hello","elements":[{"ref":"abcdef01-1234abcd-1","role":"button","name":"Sign in"}]}"""

        override suspend fun execute(request: BrowserRequest): BrowserReply {
            calls += 1
            methods += request.method
            lastRef = request.params[BrowserDriverLogic.PARAM_REF]
            lastParams = request.params
            lastRequest = request
            if (request.method in cancelling) throw kotlinx.coroutines.CancellationException("cancelled")
            if (request.method in failing) error("driver failure")
            return when (request.method) {
                BrowserMethod.NAVIGATE -> BrowserReply(ok = true, result = mapOf("phase" to "started"))
                BrowserMethod.SNAPSHOT -> BrowserReply(ok = true, result = mapOf(BrowserDriverLogic.RESULT_SNAPSHOT to snapshotJson))
                BrowserMethod.TYPE -> typeReply ?: BrowserReply(ok = true)
                BrowserMethod.RESIZE -> BrowserReply(
                    ok = true,
                    result = mapOf(
                        BrowserDriverLogic.PARAM_VIEWPORT to (request.params[BrowserDriverLogic.PARAM_VIEWPORT] ?: ""),
                        "desktop" to "true",
                        BrowserDriverLogic.PARAM_WIDTH to (request.params[BrowserDriverLogic.PARAM_WIDTH] ?: "1920"),
                        BrowserDriverLogic.PARAM_HEIGHT to (request.params[BrowserDriverLogic.PARAM_HEIGHT] ?: "1080"),
                    ),
                )
                BrowserMethod.CAPTURE -> if (captureNoGeometry) {
                    BrowserReply(ok = true, result = mapOf(BrowserDriverLogic.RESULT_DATA to "aGVsbG8="))
                } else {
                    BrowserReply(
                        ok = true,
                        result = mapOf(
                            BrowserDriverLogic.RESULT_DATA to "aGVsbG8=",
                            BrowserDriverLogic.RESULT_MIME to "image/png",
                            BrowserDriverLogic.RESULT_URL to "https://public.example",
                            "title" to "Hi",
                            "width" to "12",
                            "height" to "34",
                            "cssWidth" to "1024",
                            "cssHeight" to "768",
                            "dpr" to "2.625",
                            "visualWidth" to "819",
                            "visualHeight" to "600",
                            "scale" to "1",
                            "viewport" to "fill",
                        ),
                    )
                }
                else -> BrowserReply(ok = true)
            }
        }
    }

    /** The field map BrowserPageDriver.snapshotMap produces, without a WebView. */
    private class FieldDriver : BrowserDriver {
        val scripts = mutableListOf<String>()
        override suspend fun execute(request: BrowserRequest): BrowserReply {
            scripts += request.params.values.joinToString(" ")
            val json = Json.parseToJsonElement(
                """{"url":"https://public.example","title":"Hi","text":"hello","elements":[{"ref":"abcdef01-1234abcd-1","role":"link","name":"Docs"}]}""",
            ).jsonObject
            return BrowserReply(ok = true, result = BrowserDriverLogic.snapshotMap(json))
        }
    }
}
