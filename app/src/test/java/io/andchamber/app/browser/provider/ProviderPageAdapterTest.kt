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
        assertEquals(ProviderCodes.UNSUPPORTED_ACTION, resize.errorCode)
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
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, openViewport.errorCode)
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

    private fun adapter(driver: BrowserDriver, present: suspend (String) -> Boolean = { true }) =
        OpenChamberPageAdapter(driver, present = { tab -> present(tab.url) }, viewportSize = { 12 to 34 })

    private fun granted(driver: FakeDriver, json: String = snapshotJson(ref, "Sign in")): Pair<ProviderRegistry, OpenChamberPageAdapter> {
        driver.snapshotJson = json
        val pages = adapter(driver)
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
        var snapshotJson: String = """{"url":"https://public.example","title":"Hi","text":"hello","elements":[{"ref":"abcdef01-1234abcd-1","role":"button","name":"Sign in"}]}"""

        override suspend fun execute(request: BrowserRequest): BrowserReply {
            calls += 1
            methods += request.method
            lastRef = request.params[BrowserDriverLogic.PARAM_REF]
            return when (request.method) {
                BrowserMethod.NAVIGATE -> BrowserReply(ok = true, result = mapOf("phase" to "started"))
                BrowserMethod.SNAPSHOT -> BrowserReply(ok = true, result = mapOf(BrowserDriverLogic.RESULT_SNAPSHOT to snapshotJson))
                BrowserMethod.CAPTURE -> BrowserReply(
                    ok = true,
                    result = mapOf(
                        BrowserDriverLogic.RESULT_DATA to "aGVsbG8=",
                        BrowserDriverLogic.RESULT_MIME to "image/png",
                        BrowserDriverLogic.RESULT_URL to "https://public.example",
                        "title" to "Hi",
                        "width" to "12",
                        "height" to "34",
                        "viewport" to "fill",
                    ),
                )
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
