package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserErrorCode
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.runtime.ServerTarget
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
    fun `address field adds https and leaves an explicit scheme alone`() {
        assertEquals("https://example.com", normalizeAddress("example.com"))
        assertEquals("https://example.com", normalizeAddress("https://example.com"))
        assertEquals("http://example.com", normalizeAddress("http://example.com"))
    }
}
