package io.andchamber.app.notify

import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.RuntimeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyLogicTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    // --- stdout line parsing ---

    @Test
    fun `a prefixed line with json parses into an event`() {
        val line =
            "[OpenChamberDesktopNotify] " +
                """{"kind":"ready","title":"Done","body":"tests pass","tag":"ready-ses_1","sessionId":"ses_1","directory":"/home/x/repo","requireHidden":true}"""

        val event = NotificationPayload.parseLine(line)!!

        assertEquals(DesktopNotification.Kind.Ready, event.kind)
        assertEquals("Done", event.title)
        assertEquals("tests pass", event.body)
        assertEquals("ready-ses_1", event.tag)
        assertEquals("ses_1", event.sessionId)
        assertEquals("/home/x/repo", event.directory)
    }

    @Test
    fun `lines without the exact prefix are ignored`() {
        assertNull(NotificationPayload.parseLine("INFO [OpenChamberDesktopNotify] {\"tag\":\"x\"}"))
        assertNull(NotificationPayload.parseLine("[openchamberdesktopnotify] {\"tag\":\"x\"}"))
        assertNull(NotificationPayload.parseLine("plain server log"))
    }

    @Test
    fun `malformed json and payloads without a tag are ignored`() {
        assertNull(NotificationPayload.parseLine("[OpenChamberDesktopNotify] {not json"))
        assertNull(NotificationPayload.parseLine("[OpenChamberDesktopNotify] {\"kind\":\"ready\"}"))
        assertNull(NotificationPayload.parseLine("[OpenChamberDesktopNotify] []"))
    }

    @Test
    fun `unknown kinds are kept as Unknown rather than dropped`() {
        val event = NotificationPayload.parseLine("[OpenChamberDesktopNotify] {\"kind\":\"new-kind\",\"tag\":\"t\"}")!!
        assertEquals(DesktopNotification.Kind.Unknown, event.kind)
    }

    @Test
    fun `restart-interrupted kind is recognised`() {
        val event = NotificationPayload.parseLine(
            "[OpenChamberDesktopNotify] {\"kind\":\"opencode-restart-interrupted\",\"tag\":\"opencode-restart-1\"}",
        )!!
        assertEquals(DesktopNotification.Kind.RestartInterrupted, event.kind)
    }

    // --- tag to id stability ---

    @Test
    fun `the same tag always gives the same notification id`() {
        assertEquals(NotificationPayload.notificationId("ready-ses_1"), NotificationPayload.notificationId("ready-ses_1"))
    }

    @Test
    fun `different tags give different ids and ids are non-negative`() {
        val a = NotificationPayload.notificationId("ready-ses_1")
        val b = NotificationPayload.notificationId("question-ses_1")
        assertTrue(a != b)
        assertTrue(a >= 0 && b >= 0)
    }

    // --- permission tag parsing ---

    @Test
    fun `permission request id is read from the tag for the matching session`() {
        assertEquals("per_9", NotificationPayload.permissionRequestId("permission-ses_1:per_9", "ses_1"))
    }

    @Test
    fun `a permission tag for another session or without an id yields no request id`() {
        assertNull(NotificationPayload.permissionRequestId("permission-ses_2:per_9", "ses_1"))
        assertNull(NotificationPayload.permissionRequestId("permission-ses_1:", "ses_1"))
        assertNull(NotificationPayload.permissionRequestId("permission-ses_1", "ses_1"))
    }

    // --- form simplicity classification ---

    private val singleChoice =
        """{"id":"frm_1","sessionID":"ses_1","title":"Pick one","fields":[{"key":"env","type":"string",
          "options":[{"value":"dev","label":"Development"},{"value":"prod","label":"Production"}]}]}"""

    @Test
    fun `a single choice field with at most three options becomes buttons`() {
        val answer = PendingForms.classifyForm(obj(singleChoice))
        assertEquals(
            FormAnswer.Choice("frm_1", "ses_1", "env", listOf("dev" to "Development", "prod" to "Production")),
            answer,
        )
    }

    @Test
    fun `a single text field becomes a reply field`() {
        val answer = PendingForms.classifyForm(
            obj("""{"id":"frm_2","sessionID":"ses_1","title":"Name?","fields":[{"key":"name","type":"string"}]}"""),
        )
        assertEquals(FormAnswer.Text("frm_2", "ses_1", "name"), answer)
    }

    @Test
    fun `two to eight plain string fields become a fillable form`() {
        val answer = PendingForms.classifyForm(
            obj(
                """{"id":"frm_7","sessionID":"ses_1","title":"Deploy","fields":[
                   {"key":"env","type":"string","title":"Environment","description":"target env","required":true,"placeholder":"dev","default":"dev"},
                   {"key":"branch","type":"string","title":"Branch"},
                   {"key":"note","type":"string","title":"Note","required":false}]}""",
            ),
        )
        assertEquals(
            FormAnswer.Fields(
                "frm_7",
                "ses_1",
                "Deploy",
                listOf(
                    FormTextField("env", "Environment", "target env", true, "dev", "dev"),
                    FormTextField("branch", "Branch", null, false, null, null),
                    FormTextField("note", "Note", null, false, null, null),
                ),
            ),
            answer,
        )
    }

    @Test
    fun `a multi-field form with a choice, when or hidden field stays open-only`() {
        val choice = """{"id":"f","sessionID":"ses_1","fields":[{"key":"a","type":"string"},{"key":"b","type":"string","options":[{"value":"x","label":"X"}]}]}"""
        val conditional = """{"id":"f","sessionID":"ses_1","fields":[{"key":"a","type":"string"},{"key":"b","type":"string","when":[{"key":"a","op":"eq","value":"x"}]}]}"""
        val hidden = """{"id":"f","sessionID":"ses_1","fields":[{"key":"a","type":"string"},{"key":"b","type":"string","hidden":true}]}"""
        listOf(choice, conditional, hidden).forEach { text ->
            assertEquals(FormAnswer.OpenOnly, PendingForms.classifyForm(obj(text)))
        }
    }

    @Test
    fun `a choice with more than three options is not answerable from the notification`() {
        val four =
            """{"id":"frm_3","sessionID":"ses_1","title":"x","fields":[{"key":"k","type":"string",
              "options":[{"value":"a","label":"A"},{"value":"b","label":"B"},{"value":"c","label":"C"},{"value":"d","label":"D"}]}]}"""
        assertEquals(FormAnswer.OpenOnly, PendingForms.classifyForm(obj(four)))
    }

    @Test
    fun `non-string, conditional, global and too-many-field forms are open-only`() {
        val tooMany = (1..9).joinToString(",") { """{"key":"k$it","type":"string"}""" }
        val nine = """{"id":"f","sessionID":"ses_1","title":"t","fields":[$tooMany]}"""
        val number = """{"id":"f","sessionID":"ses_1","title":"t","fields":[{"key":"a","type":"number"}]}"""
        val conditional = """{"id":"f","sessionID":"ses_1","title":"t","fields":[{"key":"a","type":"string","when":[{"key":"x","op":"eq","value":"y"}]}]}"""
        val global = """{"id":"f","sessionID":"global","title":"t","fields":[{"key":"a","type":"string"}]}"""
        listOf(nine, number, conditional, global).forEach { text ->
            assertEquals(FormAnswer.OpenOnly, PendingForms.classifyForm(obj(text)))
        }
    }

    @Test
    fun `a question with several pending forms is ambiguous and opens the app`() {
        val pending =
            obj("""{"ses_1":{"forms":[{"id":"a","sessionID":"ses_1","fields":[{"key":"k","type":"string"}]},{"id":"b","sessionID":"ses_1","fields":[{"key":"k","type":"string"}]}]}}""")
        assertNull(PendingForms.singleFormFor(pending, "ses_1"))
    }

    @Test
    fun `answer body is a record keyed by the field key, as Form-Reply requires`() {
        val body = PendingForms.answerBody("env", "prod")
        assertEquals("prod", (body["answer"] as JsonObject)["env"]!!.toString().trim('"'))
    }

    @Test
    fun `a multi-field answer body carries every field in one map`() {
        val body = PendingForms.answerBody(mapOf("env" to "prod", "branch" to "main", "note" to ""))
        val answer = body["answer"] as JsonObject
        assertEquals("prod", answer["env"]!!.toString().trim('"'))
        assertEquals("main", answer["branch"]!!.toString().trim('"'))
        assertEquals("", answer["note"]!!.toString().trim('"'))
    }

    @Test
    fun `missing required fields are listed by key after trimming`() {
        val form =
            FormAnswer.Fields(
                "frm_1",
                "ses_1",
                "t",
                listOf(
                    FormTextField("a", "A", null, true, null, null),
                    FormTextField("b", "B", null, false, null, null),
                    FormTextField("c", "C", null, true, null, null),
                ),
            )

        assertEquals(listOf("a", "c"), PendingForms.missingRequiredFields(form, mapOf("a" to "  ", "b" to "ok")))
        assertEquals(emptyList<String>(), PendingForms.missingRequiredFields(form, mapOf("a" to "x", "c" to " y ")))
        assertEquals(listOf("a"), PendingForms.missingRequiredFields(form, mapOf("c" to "z")))
    }

    // --- action to request mapping ---

    @Test
    fun `reply sends a prompt with only text`() {
        val request = NotificationActions.sendReply("ses_1", "continue")
        assertEquals("POST", request.method)
        assertEquals("/api/session/ses_1/prompt", request.path)
        assertEquals("continue", request.body!!["text"].toString().trim('"'))
    }

    @Test
    fun `permission decisions use the OpenCode literals`() {
        val once = NotificationActions.permissionReply("ses_1", "per_9", PermissionDecision.Once)
        assertEquals("/api/session/ses_1/permission/per_9/reply", once.path)
        assertEquals("once", once.body!!["decision"].toString().trim('"'))
        assertEquals("always", NotificationActions.permissionReply("s", "p", PermissionDecision.Always).body!!["decision"].toString().trim('"'))
        assertEquals("reject", NotificationActions.permissionReply("s", "p", PermissionDecision.Reject).body!!["decision"].toString().trim('"'))
    }

    @Test
    fun `form reply posts to the form route with a record answer`() {
        val request = NotificationActions.formReply("ses_1", "frm_1", "env", "dev")
        assertEquals("/api/session/ses_1/form/frm_1/reply", request.path)
        assertEquals("dev", ((request.body!!["answer"]) as JsonObject)["env"]!!.toString().trim('"'))
    }

    @Test
    fun `form reply with the map keeps every answer in one request`() {
        val request = NotificationActions.formReply("ses_1", "frm_1", mapOf("env" to "dev", "branch" to "main"))
        assertEquals("/api/session/ses_1/form/frm_1/reply", request.path)
        val answer = request.body!!["answer"] as JsonObject
        assertEquals("dev", answer["env"]!!.toString().trim('"'))
        assertEquals("main", answer["branch"]!!.toString().trim('"'))
    }

    @Test
    fun `path segments are percent-encoded`() {
        assertEquals("a%20b%2Fc", NotificationActions.encodeSegment("a b/c"))
    }

    @Test
    fun `permission summary names the action and first resource`() {
        val permission = PendingPermission("per_9", "bash", listOf("npm test"))
        assertEquals("bash: npm test", permission.summary())
    }

    @Test
    fun `a pending permission is found by id only in its own session`() {
        val pending =
            obj("""{"ses_1":{"permissions":[{"id":"per_9","action":"bash","resources":["npm test"]}]}}""")
        assertEquals("bash", PendingPermission.find(pending, "ses_1", "per_9")!!.action)
        assertNull(PendingPermission.find(pending, "ses_1", "per_missing"))
        assertNull(PendingPermission.find(pending, "ses_2", "per_9"))
    }

    // --- suppression only while the local page is on screen ---

    @Test
    fun `OpenChamber events are dropped while the local page is showing`() {
        assertEquals(Delivery.Suppressed, NotifyPolicy.deliveryFor(DesktopNotification.Kind.Ready, localPageShowing = true))
        assertEquals(Delivery.Suppressed, NotifyPolicy.deliveryFor(DesktopNotification.Kind.Permission, localPageShowing = true))
    }

    @Test
    fun `only a resumed local page with nothing over it counts as showing`() {
        assertTrue(NotifyPolicy.localPageShowing(isLocal = true, resumed = true, overlayShown = false))
        // Remote server page (Home has no web screen at all, so it never reports).
        assertFalse(NotifyPolicy.localPageShowing(isLocal = false, resumed = true, overlayShown = false))
        // Paused: background, mid-transition, or a file picker or other activity on top.
        assertFalse(NotifyPolicy.localPageShowing(isLocal = true, resumed = false, overlayShown = false))
        // Any overlay: starting, stopped, errors, connecting, isolation, not painted yet.
        assertFalse(NotifyPolicy.localPageShowing(isLocal = true, resumed = true, overlayShown = true))
    }

    @Test
    fun `nothing reported means not showing, so events are posted`() {
        assertFalse(PageVisibility().isShowing())
    }

    @Test
    fun `a page reports showing and clears it when it stops showing`() {
        val visibility = PageVisibility()
        val page = Any()
        visibility.report(page, showing = true)
        assertTrue(visibility.isShowing())
        visibility.report(page, showing = false)
        assertFalse(visibility.isShowing())
    }

    @Test
    fun `a leaving page cannot clear the report of the page that took over`() {
        val visibility = PageVisibility()
        val old = Any()
        val new = Any()
        visibility.report(old, showing = true)
        visibility.report(new, showing = true)
        visibility.report(old, showing = false)
        assertTrue("the new page still shows", visibility.isShowing())
        visibility.report(new, showing = false)
        assertFalse(visibility.isShowing())
    }

    @Test
    fun `the visibility source is not suppressing until a page reports`() {
        // The production source starts from this report, so a fresh process posts everything.
        val foreground = AppForeground { LocalPageVisibility.isShowing() }
        val delivery = NotifyPolicy.deliveryFor(DesktopNotification.Kind.Ready, foreground.isInForeground())
        assertEquals(Delivery.Post(Channel.AgentFinished), delivery)
    }

    @Test
    fun `OpenChamber events go to their channel in the background`() {
        assertEquals(Delivery.Post(Channel.AgentFinished), NotifyPolicy.deliveryFor(DesktopNotification.Kind.Ready, false))
        assertEquals(Delivery.Post(Channel.NeedsInput), NotifyPolicy.deliveryFor(DesktopNotification.Kind.Question, false))
        assertEquals(Delivery.Post(Channel.Errors), NotifyPolicy.deliveryFor(DesktopNotification.Kind.Error, false))
    }

    @Test
    fun `AndChamber runtime errors are always shown, even in the foreground`() {
        assertEquals(Delivery.Post(Channel.Errors), NotifyPolicy.runtimeErrorDelivery())
    }

    // --- action plan ---

    @Test
    fun `plugin and restart events open the app and never offer actions`() {
        val plugin = DesktopNotification(DesktopNotification.Kind.Plugin, "t", "b", "plugin-1", "ses_1", null)
        assertEquals(ActionPlan.Kind.OpenOnly, ActionPlan.forEvent(plugin))
    }

    @Test
    fun `permission without a request id opens the app instead of offering buttons`() {
        val event = DesktopNotification(DesktopNotification.Kind.Permission, "t", "b", "permission-ses_1", "ses_1", null)
        assertEquals(ActionPlan.Kind.OpenOnly, ActionPlan.forEvent(event))
    }

    // --- runtime error dedupe ---

    @Test
    fun `a failed setup alerts once and not again for the same reason`() {
        val watch = RuntimeErrorWatch()
        val failed = RuntimeState.Failed("no network", recoverable = true, phase = FailurePhase.Install)

        assertEquals(1, watch.onState(failed).size)
        assertTrue(watch.onState(failed).isEmpty())
    }

    @Test
    fun `failed start and failed runtime use their own titles`() {
        val watch = RuntimeErrorWatch()
        assertEquals("OpenChamber didn't start", watch.onState(RuntimeState.Failed("x", true, FailurePhase.Start)).single().title)
        assertEquals("OpenChamber stopped", watch.onState(RuntimeState.Failed("y", true, FailurePhase.Runtime)).single().title)
    }

    @Test
    fun `a stopped runtime is not an alert`() {
        val watch = RuntimeErrorWatch()
        assertTrue(watch.onState(RuntimeState.Stopped).isEmpty())
        assertTrue(watch.onState(RuntimeState.NotInstalled).isEmpty())
    }

    @Test
    fun `opencode backend error alerts once until it clears, then can alert again`() {
        val watch = RuntimeErrorWatch()

        assertEquals(1, watch.onHealth("provider down", openCodeRunning = false).size)
        assertTrue("same error: no repeat", watch.onHealth("provider down", openCodeRunning = false).isEmpty())
        assertTrue("cleared", watch.onHealth(null, openCodeRunning = true).isEmpty())
        assertEquals("re-arms after clearing", 1, watch.onHealth("provider down", openCodeRunning = false).size)
    }

    @Test
    fun `an error while opencode is still running is not an alert`() {
        assertTrue(RuntimeErrorWatch().onHealth("transient", openCodeRunning = true).isEmpty())
    }

    // --- settings bootstrap rule ---

    @Test
    fun `bootstrap enables the setting only when it is undefined`() {
        assertEquals(NotificationSettingsBootstrap.Decision.Enable, NotificationSettingsBootstrap.decide(obj("{}")))
        assertEquals(
            NotificationSettingsBootstrap.Decision.Enable,
            NotificationSettingsBootstrap.decide(obj("""{"nativeNotificationsEnabled":null}""")),
        )
    }

    @Test
    fun `a value the user already chose is never overridden`() {
        assertEquals(NotificationSettingsBootstrap.Decision.Leave, NotificationSettingsBootstrap.decide(obj("""{"nativeNotificationsEnabled":false}""")))
        assertEquals(NotificationSettingsBootstrap.Decision.Leave, NotificationSettingsBootstrap.decide(obj("""{"nativeNotificationsEnabled":true}""")))
    }

    @Test
    fun `the open-session link is taken once and only with a session id`() {
        OpenSessionLink.offer(null, "/x")
        assertNull(OpenSessionLink.take())
        OpenSessionLink.offer("ses_1", "/repo")
        assertEquals(OpenSessionLink.Target("ses_1", "/repo"), OpenSessionLink.take())
        assertNull(OpenSessionLink.take())
        assertEquals("/?session=ses_1", OpenSessionLink.pathFor("ses_1"))
    }

    @Test
    fun `the visibility rule does not depend on the event being a question`() {
        assertFalse(NotifyPolicy.deliveryFor(DesktopNotification.Kind.Goal, localPageShowing = false) == Delivery.Suppressed)
    }
}
