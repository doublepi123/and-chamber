package io.andchamber.app.phone.tool

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolProtocolTest {
    @Test
    fun `parseBody accepts the plugin envelope and rejects unknown top-level fields`() {
        val body = toolBody(input = input("status"))
        val call = PhoneToolProtocol.parseBody(body)
        assertNotNull(call)
        assertEquals("ses-a", call!!.sessionId)
        assertEquals("call-1", call.callId)
        assertEquals("status", call.action)

        val withUnknown = buildJsonObject {
            put("version", 1)
            put("sessionID", "ses-a")
            put("callID", "call-1")
            put("input", input("status"))
            put("token", "nope")
        }.toString()
        assertNull(PhoneToolProtocol.parseBody(withUnknown))

        val missingSession = buildJsonObject {
            put("version", 1)
            put("callID", "call-1")
            put("input", input("status"))
        }.toString()
        assertNull(PhoneToolProtocol.parseBody(missingSession))

        val wrongVersion = body.replace("\"version\":1", "\"version\":2")
        assertNull(PhoneToolProtocol.parseBody(wrongVersion))

        val unknownAction = toolBody(input = input("shell"))
        assertNull(PhoneToolProtocol.parseBody(unknownAction))

        val nonStringMessage = buildJsonObject {
            put("version", 1)
            put("sessionID", "ses-a")
            put("callID", "call-1")
            put("messageID", 7)
            put("input", input("status"))
        }.toString()
        assertNull(PhoneToolProtocol.parseBody(nonStringMessage))
    }

    @Test
    fun `parseAction accepts each fixed action shape`() {
        fun action(body: String) = PhoneToolProtocol.parseAction(PhoneToolProtocol.parseBody(body)!!)

        assertEquals(PhoneToolAction.Status, action(toolBody(input = input("status"))))
        assertNull(
            "a direct request requires the preauthorized package",
            action(toolBody(input = input("request", extra = mapOf("summary" to "Book a table")))),
        )
        assertEquals(
            PhoneToolAction.Request("Book a table", "com.example.target"),
            action(
                toolBody(
                    input = input(
                        "request",
                        extra = mapOf("summary" to "Book a table", "packageName" to "com.example.target"),
                    ),
                ),
            ),
        )
        assertEquals(PhoneToolAction.Apps, action(toolBody(input = input("apps"))))
        assertEquals(
            PhoneToolAction.OpenApp("com.example.target"),
            action(
                toolBody(
                    input = input("open_app", extra = mapOf("packageName" to "com.example.target")),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Progress(5L, "checking the form"),
            action(
                toolBody(
                    input = input(
                        "progress",
                        extra = mapOf("epoch" to 5L, "phase" to "checking the form"),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Progress(5L, "checking the form"),
            action(
                toolBody(
                    input = input(
                        "progress",
                        extra = mapOf("epoch" to 5L, "message" to "checking the form"),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.NeedUser(5L, "a person must enter the code"),
            action(
                toolBody(
                    input = input(
                        "need_user",
                        extra = mapOf("epoch" to 5L, "reason" to "a person must enter the code"),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Finish(5L, "I have reported the end"),
            action(
                toolBody(
                    input = input(
                        "finish",
                        extra = mapOf("epoch" to 5L, "summary" to "I have reported the end"),
                    ),
                ),
            ),
        )
        assertEquals(PhoneToolAction.Snapshot, action(toolBody(input = input("snapshot"))))
        assertEquals(
            PhoneToolAction.Capture("obs-1"),
            action(toolBody(input = input("capture", observationId = "obs-1"))),
        )
        assertEquals(
            PhoneToolAction.TapRef("n1", "obs-1"),
            action(toolBody(input = input("tap", observationId = "obs-1", extra = mapOf("ref" to "n1")))),
        )
        assertEquals(
            PhoneToolAction.TapPoint(10, 20, "obs-1"),
            action(toolBody(input = input("tap", observationId = "obs-1", extra = mapOf("x" to 10, "y" to 20)))),
        )
        assertEquals(
            PhoneToolAction.Swipe(10, 20, 30, 40, 300, "obs-1"),
            action(
                toolBody(
                    input = input(
                        "swipe",
                        observationId = "obs-1",
                        extra = mapOf("x" to 10, "y" to 20, "x2" to 30, "y2" to 40),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Swipe(10, 20, 30, 40, 50, "obs-1"),
            action(
                toolBody(
                    input = input(
                        "swipe",
                        observationId = "obs-1",
                        extra = mapOf("x" to 10, "y" to 20, "x2" to 30, "y2" to 40, "durationMs" to 50),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Input("hello", null, "obs-1"),
            action(toolBody(input = input("input", observationId = "obs-1", extra = mapOf("text" to "hello")))),
        )
        assertEquals(
            PhoneToolAction.Input("hello", "n2", "obs-1"),
            action(
                toolBody(
                    input = input(
                        "input",
                        observationId = "obs-1",
                        extra = mapOf("text" to "hello", "ref" to "n2"),
                    ),
                ),
            ),
        )
        assertEquals(
            PhoneToolAction.Back("obs-1"),
            action(toolBody(input = input("back", observationId = "obs-1"))),
        )
    }

    @Test
    fun `parseAction refuses extra fields and malformed values`() {
        fun refused(raw: String) = PhoneToolProtocol.parseAction(PhoneToolProtocol.parseBody(raw)!!) == null

        assertTrue("status takes no parameters", refused(toolBody(input = input("status", extra = mapOf("x" to 1)))))
        assertTrue("snapshot takes no parameters", refused(toolBody(input = input("snapshot", extra = mapOf("ref" to "n1")))))
        assertTrue(
            "tap cannot mix a ref with coordinates",
            refused(
                toolBody(
                    input = input("tap", observationId = "obs-1", extra = mapOf("ref" to "n1", "x" to 10, "y" to 10)),
                ),
            ),
        )
        assertTrue(
            "tap needs both coordinates",
            refused(toolBody(input = input("tap", observationId = "obs-1", extra = mapOf("x" to 10)))),
        )
        assertTrue(
            "coordinates are bounded",
            refused(
                toolBody(
                    input = input("tap", observationId = "obs-1", extra = mapOf("x" to 10_001, "y" to 10)),
                ),
            ),
        )
        assertTrue(
            "a swipe needs both endpoints",
            refused(
                toolBody(
                    input = input(
                        "swipe",
                        observationId = "obs-1",
                        extra = mapOf("x" to 10, "y" to 20, "x2" to 30),
                    ),
                ),
            ),
        )
        assertTrue(
            "a swipe cannot be a no-op",
            refused(
                toolBody(
                    input = input(
                        "swipe",
                        observationId = "obs-1",
                        extra = mapOf("x" to 10, "y" to 20, "x2" to 10, "y2" to 20),
                    ),
                ),
            ),
        )
        assertTrue(
            "swipe duration is bounded",
            refused(
                toolBody(
                    input = input(
                        "swipe",
                        observationId = "obs-1",
                        extra = mapOf("x" to 10, "y" to 20, "x2" to 30, "y2" to 40, "durationMs" to 5),
                    ),
                ),
            ),
        )
        assertTrue(
            "input text is required",
            refused(toolBody(input = input("input", observationId = "obs-1"))),
        )
        assertTrue(
            "blank input is refused",
            refused(
                toolBody(input = input("input", observationId = "obs-1", extra = mapOf("text" to "   "))),
            ),
        )
        assertTrue(
            "control characters are refused",
            refused(
                toolBody(
                    input = input("input", observationId = "obs-1", extra = mapOf("text" to "a\u0001b")),
                ),
            ),
        )
        assertTrue(
            "a ref must match the snapshot ref shape",
            refused(
                toolBody(
                    input = input("tap", observationId = "obs-1", extra = mapOf("ref" to "bad ref")),
                ),
            ),
        )
        assertTrue(
            "capture needs an observation",
            refused(toolBody(input = input("capture"))),
        )
        assertTrue(
            "an observation id is bounded and shaped",
            refused(
                toolBody(input = input("capture", observationId = "obs 1")),
            ),
        )
        assertTrue(
            "unknown action fields are refused",
            refused(
                toolBody(
                    input = input("back", observationId = "obs-1", extra = mapOf("package" to "com.example")),
                ),
            ),
        )
        assertTrue(
            "request needs a bounded summary",
            refused(toolBody(input = input("request"))),
        )
        assertTrue(
            "a blank request summary is refused",
            refused(toolBody(input = input("request", extra = mapOf("summary" to "   ")))),
        )
        assertTrue(
            "an overlong request summary is refused",
            refused(
                toolBody(
                    input = input(
                        "request",
                        extra = mapOf("summary" to "x".repeat(PhoneToolProtocol.MAX_SUMMARY_CHARS + 1)),
                    ),
                ),
            ),
        )
        assertTrue(
            "request summary control characters are refused",
            refused(
                toolBody(
                    input = input("request", extra = mapOf("summary" to "a\u0001b")),
                ),
            ),
        )
        assertTrue(
            "status takes no epoch",
            refused(toolBody(input = input("status", extra = mapOf("epoch" to 1L)))),
        )
        assertTrue(
            "progress needs an epoch",
            refused(toolBody(input = input("progress", extra = mapOf("phase" to "x")))),
        )
        assertTrue(
            "progress epoch is non-negative",
            refused(toolBody(input = input("progress", extra = mapOf("epoch" to -1L, "phase" to "x")))),
        )
        assertTrue(
            "progress takes exactly one of phase/message",
            refused(
                toolBody(
                    input = input(
                        "progress",
                        extra = mapOf("epoch" to 5L, "phase" to "a", "message" to "b"),
                    ),
                ),
            ),
        )
        assertTrue(
            "progress needs phase or message",
            refused(toolBody(input = input("progress", extra = mapOf("epoch" to 5L)))),
        )
        assertTrue(
            "progress text is bounded",
            refused(
                toolBody(
                    input = input(
                        "progress",
                        extra = mapOf("epoch" to 5L, "phase" to "x".repeat(121)),
                    ),
                ),
            ),
        )
        assertTrue(
            "progress text has no control characters",
            refused(
                toolBody(
                    input = input(
                        "progress",
                        extra = mapOf("epoch" to 5L, "phase" to "bad\u0001text"),
                    ),
                ),
            ),
        )
        assertTrue(
            "progress takes no observation",
            refused(
                toolBody(
                    input = input(
                        "progress",
                        observationId = "obs-1",
                        extra = mapOf("epoch" to 5L, "phase" to "x"),
                    ),
                ),
            ),
        )
        assertTrue(
            "need_user takes exactly one of reason/message",
            refused(
                toolBody(
                    input = input(
                        "need_user",
                        extra = mapOf("epoch" to 5L, "reason" to "a", "message" to "b"),
                    ),
                ),
            ),
        )
        assertTrue(
            "need_user needs a reason",
            refused(toolBody(input = input("need_user", extra = mapOf("epoch" to 5L)))),
        )
        assertTrue(
            "finish needs a summary",
            refused(toolBody(input = input("finish", extra = mapOf("epoch" to 5L)))),
        )
        assertTrue(
            "finish summary is bounded",
            refused(
                toolBody(
                    input = input(
                        "finish",
                        extra = mapOf("epoch" to 5L, "summary" to "x".repeat(121)),
                    ),
                ),
            ),
        )
        assertTrue(
            "a malformed request package is refused",
            refused(
                toolBody(
                    input = input("request", extra = mapOf("summary" to "book", "packageName" to "bad package")),
                ),
            ),
        )
        assertTrue(
            "request takes no observation",
            refused(
                toolBody(
                    input = input(
                        "request",
                        observationId = "obs-1",
                        extra = mapOf("summary" to "book"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `request ids keep safe call ids and digest anything else`() {
        assertEquals("call_abc-1", PhoneToolProtocol.requestId("call_abc-1"))
        val digested = PhoneToolProtocol.requestId("call id with spaces")
        assertTrue(digested.matches(Regex("^c-[0-9a-f]{40}$")))
        assertEquals(digested, PhoneToolProtocol.requestId("call id with spaces"))
    }

    @Test
    fun `every core refusal code has a fixed message`() {
        for (code in io.andchamber.app.phone.core.PhoneErrorCode.values()) {
            val message = PhoneToolProtocol.fixedMessage(code.name)
            assertTrue("no fixed message for $code", message.isNotBlank())
            assertNotEquals(
                "core code $code fell back to the generic message",
                "The phone action failed.",
                message,
            )
            assertTrue("message leaked a token for $code", !message.contains(TOKEN))
        }
        assertEquals(
            "The phone is busy with another action.",
            PhoneToolProtocol.fixedMessage(io.andchamber.app.phone.core.PhoneErrorCode.BUSY.name),
        )
    }

    @Test
    fun `fixed messages are stable and never echo request content`() {
        val secret = "com.example.secret label"
        for (code in listOf(PhoneToolCodes.STALE_OBSERVATION, PhoneToolCodes.SESSION_BUSY, PhoneToolCodes.OUTCOME_UNKNOWN)) {
            val message = PhoneToolProtocol.fixedMessage(code)
            assertTrue(message.isNotBlank())
            assertTrue("message leaked content for $code", !message.contains(secret))
            assertTrue("message leaked a token", !message.contains(TOKEN))
        }
        val envelope = PhoneToolProtocol.envelope(
            action = "phone.tap",
            ok = false,
            epoch = 3L,
            code = PhoneToolCodes.SESSION_BUSY,
        )
        val root = kotlinx.serialization.json.Json.parseToJsonElement(envelope) as kotlinx.serialization.json.JsonObject
        assertEquals(1, root["version"]!!.toString().toInt())
        assertEquals("phone.tap", root["action"]!!.toString().trim('"'))
        assertEquals(3L, root["epoch"]!!.toString().toLong())
        assertTrue(root.containsKey("error"))
        assertTrue(!root.containsKey("data"))
    }

    @Test
    fun `the plugin ready body is closed and exact`() {
        val body = """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone"}"""
        assertTrue(PhoneToolProtocol.isReadyBody(body))
        // Key order does not matter; the set of keys and the values do.
        assertTrue(
            PhoneToolProtocol.isReadyBody(
                """{"tool":"andchamber_phone","plugin":"andchamber-phone","version":1}""",
            ),
        )

        val refused = listOf(
            "",
            "not json",
            """{"version":1,"plugin":"andchamber-phone"}""",
            """{"version":1,"plugin":"andchamber-phone","tool":"other"}""",
            """{"version":1,"plugin":"other","tool":"andchamber_phone"}""",
            """{"version":2,"plugin":"andchamber-phone","tool":"andchamber_phone"}""",
            """{"version":"1","plugin":"andchamber-phone","tool":"andchamber_phone"}""",
            """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone","sessionID":"ses-a"}""",
            """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone","token":"$TOKEN"}""",
            """[1,2]""",
        )
        for (raw in refused) {
            assertTrue("accepted: $raw", !PhoneToolProtocol.isReadyBody(raw))
        }
    }

    @Test
    fun `the ready ack carries no token, port, session or launch`() {
        val ack = PhoneToolProtocol.readyAck()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(ack) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("version", "ready"), root.keys)
        assertEquals(1, root["version"]!!.toString().toInt())
        assertTrue(root["ready"]!!.toString().toBoolean())
        assertTrue(!ack.contains(TOKEN))
        assertTrue(!ack.contains("launch"))
        assertTrue(!ack.contains("ses-"))
    }
}
