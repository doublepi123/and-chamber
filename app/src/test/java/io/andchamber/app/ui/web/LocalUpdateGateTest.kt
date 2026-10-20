package io.andchamber.app.ui.web

import io.andchamber.app.runtime.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUpdateGateTest {
    private val origin = "http://127.0.0.1:3000"
    private val check = "http://127.0.0.1:3000/api/openchamber/update-check"

    private fun intercepts(
        targetId: String = ServerTarget.LOCAL_ID,
        method: String = "GET",
        url: String = check,
        boundOrigin: String = origin,
    ) = LocalUpdateGate.interceptsLocalUpdateCheck(targetId, boundOrigin, method, url)

    @Test
    fun `local same-origin GET of the exact path is intercepted`() {
        assertTrue(intercepts())
    }

    @Test
    fun `a query string does not stop the interception`() {
        assertTrue(intercepts(url = "$check?channel=stable&v=2.5.0"))
        assertTrue(intercepts(url = "$check?"))
    }

    @Test
    fun `a fragment does not stop the interception`() {
        assertTrue(intercepts(url = "$check#top"))
    }

    @Test
    fun `a remote target is never intercepted`() {
        assertFalse(intercepts(targetId = "workstation"))
    }

    @Test
    fun `a request to another origin is not intercepted`() {
        assertFalse(intercepts(url = "http://127.0.0.1:3001/api/openchamber/update-check"))
        assertFalse(intercepts(url = "https://api.openchamber.dev/api/openchamber/update-check"))
    }

    @Test
    fun `a POST is not intercepted`() {
        assertFalse(intercepts(method = "POST"))
        assertFalse(intercepts(method = "get"))
    }

    @Test
    fun `the install route is not intercepted`() {
        assertFalse(intercepts(url = "http://127.0.0.1:3000/api/openchamber/update-install"))
    }

    @Test
    fun `path prefix tricks are not intercepted`() {
        assertFalse(intercepts(url = "http://127.0.0.1:3000/api/openchamber/update-check-x"))
        assertFalse(intercepts(url = "http://127.0.0.1:3000/api/openchamber/update-check/extra"))
        assertFalse(intercepts(url = "http://127.0.0.1:3000/x/api/openchamber/update-check"))
        assertFalse(intercepts(url = "http://127.0.0.1:3000/api/openchamber/update-check2"))
    }

    @Test
    fun `an empty bound origin never intercepts`() {
        assertFalse(intercepts(boundOrigin = ""))
    }

    @Test
    fun `the answer says there is no update and parses as JSON with available false`() {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(LocalUpdateGate.UPDATE_CHECK_BODY)
            as kotlinx.serialization.json.JsonObject
        assertEquals("false", (parsed["available"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `pathOf strips the query and fragment and rejects non-absolute input`() {
        assertEquals("/api/openchamber/update-check", LocalUpdateGate.pathOf("$check?x=1#y"))
        assertEquals("", LocalUpdateGate.pathOf("/api/openchamber/update-check"))
        assertEquals("", LocalUpdateGate.pathOf("http://127.0.0.1:3000"))
    }
}
