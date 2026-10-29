package io.andchamber.app.phone.tool

import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
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
        // Context the model copies from status/snapshot is ignored, not a refusal of the whole call.
        assertTrue(
            "snapshot ignores a copied epoch",
            !refused(toolBody(input = input("snapshot", extra = mapOf("epoch" to 3)))),
        )
        assertTrue(
            "status ignores copied epoch/observationId",
            !refused(toolBody(input = input("status", extra = mapOf("epoch" to 3, "observationId" to "obs-1")))),
        )
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
            "status ignores a copied epoch",
            !refused(toolBody(input = input("status", extra = mapOf("epoch" to 1L)))),
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
            "progress ignores a copied observationId",
            !refused(
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
    fun `hierarchy refusal keeps a live task and never demands a manual restart or reconnect`() {
        val hierarchy = PhoneToolProtocol.fixedMessage(PhoneToolCodes.HIERARCHY_NOT_IDLE)
        val readTimeout = PhoneToolProtocol.fixedMessage(PhoneToolCodes.READ_TIMEOUT)

        assertNotEquals("The phone action failed.", hierarchy)
        assertNotEquals("The phone action failed.", readTimeout)
        assertNotEquals(hierarchy, readTimeout)
        // A read-only observation refusal is not a connection-loss or manual-step instruction.
        assertTrue(hierarchy.contains("does not mean the connection is down"))
        assertTrue(hierarchy.contains("does not by itself revoke an existing task"))
        assertTrue(hierarchy.contains("Do not call need_user"))
        assertTrue(hierarchy.contains("Use status"))
        assertTrue(hierarchy.contains("still live or a start was rolled back"))
        assertTrue(hierarchy.contains("do not fake a grant"))
        assertTrue(hierarchy.contains("never authorises automatic recovery of stopped control"))
        assertTrue(hierarchy.contains("retries are bounded"))
        assertTrue(!hierarchy.contains("Ask the person", ignoreCase = true))
        assertTrue(!hierarchy.contains("reconnect", ignoreCase = true))
        assertTrue(!hierarchy.contains("There is no confirmed phone task"))
        assertTrue("must not claim missing XML support", !hierarchy.contains("XML"))
        // READ_TIMEOUT abandons the connection, but it is not proof of a network/socket close. It
        // may announce one bounded background reconnect; it must never tell the model to repeat a
        // mutating action or to treat the action as failed.
        assertTrue(readTimeout.contains("no longer used"))
        assertTrue(readTimeout.contains("does not prove the network is down"))
        assertTrue(readTimeout.contains("bounded automatic reconnect in the background"))
        assertTrue(readTimeout.contains("Check \"status\" for connected"))
        assertTrue(readTimeout.contains("Do not repeat a mutating action"))
        assertTrue(!readTimeout.contains("reconnect explicitly"))
        assertTrue(!readTimeout.contains("cannot recover it automatically"))
        assertTrue(!readTimeout.contains("There is no confirmed phone task"))
        assertTrue(!readTimeout.contains("need_user", ignoreCase = true))
        assertTrue(!readTimeout.contains("hierarchy", ignoreCase = true))
        assertTrue("must not claim missing XML support", !readTimeout.contains("XML"))

        val secret = "com.example.secret label"
        for (message in listOf(hierarchy, readTimeout)) {
            assertTrue("message leaked request content", !message.contains(secret))
            assertTrue("message leaked a token", !message.contains(TOKEN))
            assertTrue("message leaked an exception", !message.contains("Exception"))
            assertTrue("message leaked a site", !message.contains("example.test"))
        }
    }

    @Test
    fun `standing refusals point at the saved scope and native continue entry`() {
        val notGranted = PhoneToolProtocol.fixedMessage(PhoneToolCodes.NOT_GRANTED)
        val blocked = PhoneToolProtocol.fixedMessage(PhoneToolCodes.STANDING_BLOCKED)

        for (message in listOf(notGranted, blocked)) {
            assertTrue(message.contains("saved standing scope may still exist"))
            assertTrue(message.contains("Do not re-pair"))
            assertTrue(!message.contains("will recover automatically"))
            assertTrue(!message.contains("will resume automatically"))
        }
        assertTrue(notGranted.contains("waiting task"))
        assertTrue(notGranted.contains("native continue entry"))
        assertTrue(!notGranted.contains("Ask the person to grant it on the phone."))
        assertTrue(blocked.contains("native waiting/continue entry"))
        assertTrue(blocked.contains("Only the person can clear the block"))
        assertTrue(blocked.contains("cannot resume or recover it automatically"))
    }

    @Test
    fun `unknown outcome stays non-repeatable and never auto-resumes`() {
        val message = PhoneToolProtocol.fixedMessage(PhoneToolCodes.OUTCOME_UNKNOWN)
        assertTrue(message.contains("Do not repeat"))
        assertTrue(!message.contains("retry", ignoreCase = true))
        assertTrue(!message.contains("clear", ignoreCase = true))
        assertTrue(!message.contains("resume automatically", ignoreCase = true))
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
    fun `failure envelopes expose only a typed start stage and never arbitrary data`() {
        val secret = "com.example.secret label"
        val arbitrary = buildJsonObject {
            put("label", secret)
            put("token", TOKEN)
            put("raw", "java.lang.IllegalStateException at example.test")
        }

        // A legacy failure has no data key at all.
        val legacy = PhoneToolProtocol.envelope(
            action = "phone.request",
            ok = false,
            epoch = 4L,
            code = PhoneToolCodes.START_FAILED,
        )
        val legacyRoot = kotlinx.serialization.json.Json.parseToJsonElement(legacy) as kotlinx.serialization.json.JsonObject
        assertTrue(!legacyRoot.containsKey("data"))

        // An arbitrary success-shaped payload passed to a failure is ignored and cannot leak.
        val refused = PhoneToolProtocol.envelope(
            action = "phone.request",
            ok = false,
            epoch = 4L,
            data = arbitrary,
            code = PhoneToolCodes.READ_TIMEOUT,
        )
        val refusedRoot = kotlinx.serialization.json.Json.parseToJsonElement(refused) as kotlinx.serialization.json.JsonObject
        assertTrue(!refusedRoot.containsKey("data"))
        assertTrue(!refused.contains(secret))
        assertTrue(!refused.contains(TOKEN))
        assertTrue(!refused.contains("Exception"))
        assertTrue(!refused.contains("example.test"))

        // The typed stage is the only failure metadata, and it is the enum name only.
        val staged = PhoneToolProtocol.envelope(
            action = "phone.request",
            ok = false,
            epoch = 4L,
            data = arbitrary,
            code = PhoneToolCodes.READ_TIMEOUT,
            startFailureStage = PhoneTaskStartFailureStage.OBSERVATION,
        )
        val stagedRoot = kotlinx.serialization.json.Json.parseToJsonElement(staged) as kotlinx.serialization.json.JsonObject
        val stagedData = stagedRoot["data"] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("startFailureStage"), stagedData.keys)
        assertEquals("OBSERVATION", stagedData["startFailureStage"]!!.toString().trim('"'))
        assertTrue(!staged.contains(secret))
        assertTrue(!staged.contains(TOKEN))

        // Success data is unchanged; a stage argument never turns into success metadata.
        val success = PhoneToolProtocol.envelope(
            action = "phone.snapshot",
            ok = true,
            epoch = 5L,
            data = buildJsonObject { put("n", 1) },
            startFailureStage = PhoneTaskStartFailureStage.OBSERVATION,
        )
        val successRoot = kotlinx.serialization.json.Json.parseToJsonElement(success) as kotlinx.serialization.json.JsonObject
        assertEquals("""{"n":1}""", successRoot["data"].toString())
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
