package io.andchamber.app.browser.provider

import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.ui.browser.BrowserDriverLogic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The click adapter shares the caller's remaining work budget with the driver and reports the
 * internal ACK fields without claiming navigation itself.
 */
class ProviderClickNavigationTest {
    private companion object {
        const val REF = "abcdef01-1234abcd-1"
    }

    @Test
    fun `click passes the shared remaining deadline to the driver`() = runBlocking {
        val driver = ClickDriver()
        val (registry, pages) = granted(driver)
        val clicked = pages.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            registry,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(clicked.errorCode ?: "ok", clicked.ok)
        assertEquals("the driver never gets a fresh timeout", 750L, driver.lastRequest?.timeoutMs)
    }

    @Test
    fun `click reports the link ACK fields only`() = runBlocking {
        val driver = ClickDriver()
        val (registry, pages) = granted(driver)
        val clicked = pages.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            registry,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(clicked.errorCode ?: "ok", clicked.ok)
        assertEquals(true, clicked.data["clicked"]?.jsonPrimitive?.contentOrNull?.isNotBlank())
        assertEquals(true, clicked.data[BrowserDriverLogic.RESULT_CLICK_DISPATCHED]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, clicked.data[BrowserDriverLogic.RESULT_NAVIGATION_EXPECTED]?.jsonPrimitive?.booleanOrNull)
        assertEquals("https://public.example/next", clicked.data[BrowserDriverLogic.RESULT_EXPECTED_URL]?.jsonPrimitive?.contentOrNull)
        assertEquals("click-token", clicked.data[BrowserDriverLogic.RESULT_NAVIGATION_TOKEN]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a DOM control ACK has no expected target`() = runBlocking {
        val driver = ClickDriver(navigationExpected = false)
        val (registry, pages) = granted(driver)
        val clicked = pages.execute(
            request("browser.click", mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            registry,
            "tab-1",
            ProviderMode.AGENT,
            1_000L,
        )
        assertTrue(clicked.errorCode ?: "ok", clicked.ok)
        assertEquals(false, clicked.data[BrowserDriverLogic.RESULT_NAVIGATION_EXPECTED]?.jsonPrimitive?.booleanOrNull)
        assertNull(clicked.data[BrowserDriverLogic.RESULT_EXPECTED_URL])
    }

    @Test
    fun `the click script classifies the target and clicks exactly once`() {
        val nonce = "a".repeat(32)
        val script = BrowserDriverLogic.installScript(nonce)
        assertEquals("one synthetic click", 1, Regex("""target\.click\(\)""").findAll(script).count())
        assertTrue(script.contains("navigationExpected"))
        assertTrue(script.contains("clickDispatched"))
        assertTrue(script.contains("sameTabTarget"))
        assertTrue(script.contains("hasAttribute(\"download\")"))
    }

    private class ClickDriver(
        private val navigationExpected: Boolean = true,
    ) : BrowserDriver {
        var lastRequest: BrowserRequest? = null
        override suspend fun execute(request: BrowserRequest): BrowserReply {
            lastRequest = request
            return when (request.method) {
                BrowserMethod.SNAPSHOT -> BrowserReply(
                    ok = true,
                    result = mapOf(
                        BrowserDriverLogic.RESULT_SNAPSHOT to
                            """{"url":"https://public.example","title":"Results","text":"body","elements":[{"ref":"$REF","role":"link","name":"Next"}]}""",
                    ),
                )
                BrowserMethod.CLICK -> BrowserReply(
                    ok = true,
                    result = buildMap {
                        put(BrowserDriverLogic.RESULT_CLICK_DISPATCHED, "true")
                        put(BrowserDriverLogic.RESULT_NAVIGATION_EXPECTED, navigationExpected.toString())
                        put(BrowserDriverLogic.RESULT_NAVIGATION_TOKEN, "click-token")
                        if (navigationExpected) put(BrowserDriverLogic.RESULT_EXPECTED_URL, "https://public.example/next")
                    },
                )
                else -> BrowserReply(ok = true)
            }
        }
    }

    private fun adapter(driver: BrowserDriver) = OpenChamberPageAdapter(
        driver = driver,
        clock = { 250L },
        viewportSize = { 12 to 34 },
    )

    private suspend fun granted(driver: ClickDriver): Pair<ProviderRegistry, OpenChamberPageAdapter> {
        val pages = adapter(driver)
        val registry = ProviderRegistry { "tab-1" }
        registry.open("s1", "/proj", "local", "https://public.example", "tab-1")
        registry.grant(1L, "c1", "s1", "tab-1")
        val snap = pages.execute(request("browser.snapshot"), registry, "tab-1", ProviderMode.AGENT, 1_000L)
        assertTrue(snap.errorCode ?: "ok", snap.ok)
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
}
