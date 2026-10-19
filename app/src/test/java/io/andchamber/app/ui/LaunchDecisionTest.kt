package io.andchamber.app.ui

import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LaunchDecisionTest {
    private val running = RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L)

    @Test
    fun `detecting waits`() {
        assertEquals(LaunchTarget.Wait, launchTarget(RuntimeState.Detecting))
    }

    @Test
    fun `running and starting open the local server`() {
        assertEquals(LaunchTarget.OpenLocal, launchTarget(running))
        assertEquals(LaunchTarget.OpenLocal, launchTarget(RuntimeState.Starting("Starting")))
    }

    @Test
    fun `stopped starts then opens`() {
        assertEquals(LaunchTarget.StartAndOpenLocal, launchTarget(RuntimeState.Stopped))
    }

    @Test
    fun `setup, failures, unsupported and stopping stay on home`() {
        val home =
            listOf(
                RuntimeState.NotInstalled,
                RuntimeState.UnsupportedAbi,
                RuntimeState.Installing(InstallStep.InstallPackages, 0.5f, null),
                RuntimeState.Failed("boom", recoverable = true, phase = FailurePhase.Start),
                RuntimeState.Failed("boom", recoverable = false, phase = FailurePhase.Install),
                RuntimeState.Stopping("Stopping"),
            )
        home.forEach { assertEquals("for $it", LaunchTarget.Home, launchTarget(it)) }
    }

    @Test
    fun `cover only when the launch may open the local server`() {
        assertEquals(LaunchStage.Waiting, initialLaunchStage(true, true, RuntimeState.Detecting))
        assertEquals(LaunchStage.Opening, initialLaunchStage(true, true, running))
        assertEquals(LaunchStage.Opening, initialLaunchStage(true, true, RuntimeState.Stopped))
        assertEquals(LaunchStage.Done, initialLaunchStage(true, true, RuntimeState.NotInstalled))
        assertEquals(LaunchStage.Done, initialLaunchStage(true, true, RuntimeState.Failed("x", true)))
    }

    @Test
    fun `no cover when the setting is off or the launch was already handled`() {
        assertEquals(LaunchStage.Done, initialLaunchStage(true, false, RuntimeState.Detecting))
        assertEquals(LaunchStage.Done, initialLaunchStage(false, true, RuntimeState.Detecting))
        assertEquals(LaunchStage.Done, initialLaunchStage(false, true, running))
    }

    @Test
    fun `a requested start reads as starting until the runtime moves`() {
        val stopped = RuntimeState.Stopped
        assertEquals(RuntimeState.Starting(null), shownLocalState(stopped, pendingStartFrom = stopped))
        val failed = RuntimeState.Failed("exit 1", recoverable = true, phase = FailurePhase.Start)
        assertEquals(RuntimeState.Starting(null), shownLocalState(failed, pendingStartFrom = failed))
    }

    @Test
    fun `session path joins the server address with one slash`() {
        assertEquals("http://127.0.0.1:3000/?session=ses_1", withSessionPath("http://127.0.0.1:3000", "/?session=ses_1"))
        assertEquals("http://127.0.0.1:3000/?session=ses_1", withSessionPath("http://127.0.0.1:3000/", "/?session=ses_1"))
        assertEquals("http://127.0.0.1:3000/?session=a%20b", withSessionPath("http://127.0.0.1:3000", "?session=a%20b"))
    }

    @Test
    fun `resume target follows the remembered instance`() {
        val remotes = setOf("r1", "r2")
        assertEquals(ResumeTarget.Local, resumeTarget(null, remotes, linkPending = false))
        assertEquals(ResumeTarget.Local, resumeTarget(ServerTarget.LOCAL_ID, remotes, linkPending = false))
        assertEquals(ResumeTarget.Remote("r1"), resumeTarget("r1", remotes, linkPending = false))
        // A removed server falls back to the local logic.
        assertEquals(ResumeTarget.Local, resumeTarget("gone", remotes, linkPending = false))
        // A notification link always wins, even over a remembered remote.
        assertEquals(ResumeTarget.Local, resumeTarget("r1", remotes, linkPending = true))
    }

    @Test
    fun `remote resume covers the first frame regardless of runtime state`() {
        assertEquals(LaunchStage.Opening, initialLaunchStage(true, true, RuntimeState.NotInstalled, remoteResume = true))
        assertEquals(LaunchStage.Done, initialLaunchStage(true, false, RuntimeState.NotInstalled, remoteResume = true))
        assertEquals(LaunchStage.Done, initialLaunchStage(false, true, RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L), remoteResume = true))
    }

    @Test
    fun `page path keeps the same-origin path and query`() {
        val base = "http://127.0.0.1:3000"
        assertEquals("/?session=ses_x", sanitizedPagePath("$base/?session=ses_x", base))
        assertEquals("/", sanitizedPagePath(base, base))
        assertEquals("/", sanitizedPagePath("$base/", base))
        assertEquals("/?session=a%20b", sanitizedPagePath("$base/?session=a%20b", base))
        assertEquals("/chat?x=1&y=2", sanitizedPagePath("$base/chat?x=1&y=2", "$base/"))
    }

    @Test
    fun `page path drops fragments and refuses foreign, gateway and malformed pages`() {
        val base = "http://127.0.0.1:3000"
        assertEquals("/chat?x=1", sanitizedPagePath("$base/chat?x=1#frag", base))
        // A token in a fragment never reaches disk.
        assertEquals("/chat", sanitizedPagePath("$base/chat#tok=secret", base))
        assertNull(sanitizedPagePath("https://other.example.com/?session=ses_x", base))
        assertNull(sanitizedPagePath("http://127.0.0.1:3001/?session=ses_x", base))
        assertNull(sanitizedPagePath("$base/cdn-cgi/access/login", base))
        assertNull(sanitizedPagePath("$base/CDN-CGI/access/login", base))
        assertNull(sanitizedPagePath("http://user:pw@127.0.0.1:3000/", base))
        assertNull(sanitizedPagePath("not a url", base))
        assertNull(sanitizedPagePath("$base/", "not a url"))
    }

    @Test
    fun `page path matches default ports and case-insensitive scheme and host`() {
        assertEquals("/", sanitizedPagePath("http://example.com/", "http://example.com:80"))
        assertEquals("/x", sanitizedPagePath("HTTP://EXAMPLE.COM/x", "http://example.com"))
        assertNull(sanitizedPagePath("https://example.com/x", "http://example.com"))
    }

    @Test
    fun `page path over the cap is dropped, never truncated`() {
        val base = "http://127.0.0.1:3000"
        val tooLong = "$base/" + "a".repeat(MAX_PAGE_PATH_CHARS)
        assertNull(sanitizedPagePath(tooLong, base))
        val longest = "$base/" + "a".repeat(MAX_PAGE_PATH_CHARS - 1)
        assertEquals("/" + "a".repeat(MAX_PAGE_PATH_CHARS - 1), sanitizedPagePath(longest, base))
    }

    @Test
    fun `real states pass through`() {
        assertSame(running, shownLocalState(running, pendingStartFrom = RuntimeState.Stopped))
        val starting = RuntimeState.Starting("Starting OpenChamber on port 3000")
        assertSame(starting, shownLocalState(starting, pendingStartFrom = RuntimeState.Stopped))
        assertSame(RuntimeState.Stopped, shownLocalState(RuntimeState.Stopped, pendingStartFrom = null))
        // A new failure after the start is the answer to it, not the old one.
        val old = RuntimeState.Failed("old", recoverable = true, phase = FailurePhase.Start)
        val new = RuntimeState.Failed("new", recoverable = true, phase = FailurePhase.Start)
        assertSame(new, shownLocalState(new, pendingStartFrom = old))
    }
}
