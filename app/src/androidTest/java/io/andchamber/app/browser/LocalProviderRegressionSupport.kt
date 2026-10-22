package io.andchamber.app.browser

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.andchamber.app.MainActivity
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.notify.OpenChamberNotifier
import io.andchamber.app.notify.OpenSessionLink
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.andchamber.app.ui.web.ComposerLayoutSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Main-thread, test-only structural readback. Never read text, tags, URLs or input values. */
internal object NativeFixtureFocusDiagnostics {
    fun snapshot(target: View?, selected: View?, activityFocus: View?, decorFocus: View?): JSONObject {
        fun belongsToTarget(view: View?): Boolean {
            if (target == null) return false
            var cursor = view
            while (cursor != null) {
                if (cursor === target) return true
                cursor = cursor.parent as? View
            }
            return false
        }
        fun describe(view: View?): Any = if (view == null) JSONObject.NULL else JSONObject()
            .put("identity", System.identityHashCode(view))
            .put("className", view.javaClass.name)
            .put("isTarget", view === target)
            .put("belongsToTarget", belongsToTarget(view))
            .put("isFocusable", view.isFocusable)
            .put("isFocusableInTouchMode", view.isFocusableInTouchMode)
            .put("isInTouchMode", view.isInTouchMode)
            .put("isFocused", view.isFocused)
            .put("hasFocus", view.hasFocus())
            .put("hasWindowFocus", view.hasWindowFocus())
            .put("isAttachedToWindow", view.isAttachedToWindow)
            .put("isShown", view.isShown)
            .put("isEnabled", view.isEnabled)
            .put("visibility", view.visibility)
            .put("descendantFocusability", (view as? ViewGroup)?.descendantFocusability ?: JSONObject.NULL)

        val ancestors = JSONArray()
        var ancestor = target?.parent as? View
        while (ancestor != null && ancestors.length() < 32) {
            ancestors.put(describe(ancestor))
            ancestor = ancestor.parent as? View
        }
        return JSONObject()
            .put("target", describe(target))
            .put("selectedView", describe(selected))
            .put("targetIsSelected", target != null && target === selected)
            .put("targetVisible", target?.let {
                it.isAttachedToWindow && it.isShown && it.getGlobalVisibleRect(Rect())
            } ?: JSONObject.NULL)
            .put("ancestors", ancestors)
            .put("ancestorsTruncated", ancestor != null)
            .put("activityCurrentFocus", describe(activityFocus))
            .put("decorFindFocus", describe(decorFocus))
    }
}

/**
 * Test-only producer for the long-lived host matrix. It uses the production Koin graph, runtime
 * service, chat WebView and provider; the page decorator only attaches the fixed fixture client and
 * a read-only recorder to the original page. It never replaces a page, grants, or creates a host
 * server/relay.
 */
internal class LocalProviderRegressionSession(
    private val context: Context,
    private val instrumentation: Instrumentation,
    private val runId: String,
    private val requiredCases: List<String>,
    private val caseScope: String,
    private val casePartial: Boolean,
) {
    private val dir = File(context.filesDir, "local-provider-regression/current")
    private val statusFile = File(dir, "status.json")
    private val commandFile = File(dir, "command.json")
    private val resultFile = File(dir, "result.json")
    private val evidenceFile = File(dir, "evidence.json")
    private val receiptsDir = File(dir, "receipts")
    private val diagnosticsDir = File(dir, "diagnostics")
    private var activeCommandId: String? = null
    private var activeAction: String? = null
    private val run = LocalProviderRun(context, instrumentation, runId)
    private val recorder = LifecycleEventRecorder()
    private val gateCommands = LifecycleGateCommands(instrumentation, recorder)
    private val seenIds = HashSet<String>()
    private val createdSessions = LinkedHashSet<String>()
    private var selectedSessionId: String? = null
    private var base = ""
    private var auth: LocalProviderRun.WebSessionAuth? = null
    private var activity: MainActivity? = null
    private var presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter? = null
    private val nativeTimeline = NativeFixtureTimeline({ presenter }, { activity })
    private var view: WebView? = null
    private var toolCommands = 0
    private var deviceCommands = 0
    private var receiptCount = 0
    private var decoratedPages = 0
    private var caseSummary = JSONObject()
    private var finished = false
    private var observerScript: String? = null
    private var tapLabelScript: String? = null
    private var cookieWriteHandle: Int? = null
    private var sessionSetup: JSONObject? = null
    private val sessionSetups = JSONArray()
    private val deadline = SystemClock.elapsedRealtime() + 20 * 60_000L

    /**
     * Instrumentation accessibility-client state for the approved hover exception. The default
     * UiAutomation connection is itself an accessibility client, so Chromium consumes native hover
     * (WebContentsAccessibilityImpl consumes HOVER_*) while accessibility is enabled. [begin] swaps
     * the connection to FLAG_DONT_USE_ACCESSIBILITY; Instrumentation recreates the connection when
     * the flags differ, which unregisters the accessibility client. [end] restores the default
     * connection. Every accessibility-tree read goes through [requireTree], so no command reads a
     * connection that is intentionally off. The state is process-wide, so `finish` and every exit
     * path restore it.
     */
    private object Accessibility {
        private val lock = Any()

        @Volatile
        private var active = false

        fun isOff(): Boolean = active

        fun enabled(context: Context): Boolean =
            (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled == true

        fun requireTree() {
            if (active) throw CommandFailure(OFF_CODE, OFF_CODE)
        }

        fun begin(instrumentation: Instrumentation, context: Context): JSONObject {
            val started = SystemClock.elapsedRealtime()
            val before = enabled(context)
            try {
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            } catch (error: Throwable) {
                restore(instrumentation)
                throw CommandFailure("a11y-off-flag-failed", error.javaClass.simpleName)
            }
            var current = enabled(context)
            while (current && SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
                SystemClock.sleep(POLL_MS)
                current = enabled(context)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (current) {
                // Another enabled accessibility service keeps the app process enabled: the hover
                // exception cannot hold. Restore the default connection, then fail typed.
                restore(instrumentation)
                throw CommandFailure(
                    "a11y-still-enabled",
                    "services=${serviceIds(context).joinToString(",")}".take(280),
                )
            }
            synchronized(lock) { active = true }
            return JSONObject()
                .put("phase", "a11y-off")
                .put("state", "off")
                .put("a11yEnabledBefore", before)
                .put("a11yEnabledAfter", false)
                .put("elapsedMs", elapsed)
        }

        fun end(instrumentation: Instrumentation, context: Context): JSONObject {
            val started = SystemClock.elapsedRealtime()
            restore(instrumentation)
            var current = enabled(context)
            while (!current && SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
                SystemClock.sleep(POLL_MS)
                current = enabled(context)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            synchronized(lock) { active = false }
            if (!current) throw CommandFailure("a11y-restore-failed", "a11y not re-enabled after ${elapsed}ms")
            return JSONObject()
                .put("phase", "a11y-off")
                .put("state", "on")
                .put("a11yEnabled", true)
                .put("elapsedMs", elapsed)
        }

        /** Default-flag connection; [end] keeps polling after this if the app process is not yet on. */
        fun restore(instrumentation: Instrumentation) {
            runCatching { instrumentation.getUiAutomation(0) }
            synchronized(lock) { active = false }
        }

        private fun serviceIds(context: Context): List<String> = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)

        const val OFF_CODE = "a11y-off-active"
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L
    }

    init {
        dir.mkdirs()
        statusFile.delete()
        commandFile.delete()
        resultFile.delete()
        evidenceFile.delete()
        File(dir, "timeline.json").delete()
        receiptsDir.deleteRecursively()
        receiptsDir.mkdirs()
        diagnosticsDir.deleteRecursively()
        diagnosticsDir.mkdirs()
        writeStatus("booting")
    }

    fun boot() {
        try {
            if (requiredCases.isEmpty()) fail("regression-cases-empty")
            requireDevice()
            verifyEnvironment()
            // The decorator must be installed before the first target page. The chat Activity is not a
            // target page, so installing before its launch is still the first target page's start.
            installDecorator()
            // Foreground first: the production service start is then requested from a live, resumed
            // Activity instead of from the instrumentation process in the background.
            activity = run.launchActivity()
            startProductionRuntime()
            verifyLaunchArtifacts()
            awaitChatHost()
            createInitialSession()
            writeStatus("ready")
        } catch (error: Throwable) {
            fail("boot:${error.javaClass.simpleName}:${safeMessage(error.message)}")
        }
    }

    fun runCommands() {
        try {
            runCommandLoop()
        } finally {
            // A failed hover case must never leave the process with accessibility switched off.
            restoreAccessibility()
            closeTimeline()
        }
    }

    /** Bounded restore; the typed end failure is a command result, this quiet path is for exit only. */
    private fun restoreAccessibility(): JSONObject? {
        if (!Accessibility.isOff()) return null
        return runCatching { Accessibility.end(instrumentation, context) }
            .getOrElse {
                Accessibility.restore(instrumentation)
                JSONObject().put("phase", "a11y-off").put("state", "on")
                    .put("a11yEnabled", Accessibility.enabled(context))
                    .put("error", "a11y-restore-failed")
            }
    }

    private fun runCommandLoop() {
        while (!finished && SystemClock.elapsedRealtime() < deadline) {
            if (!commandFile.isFile) {
                SystemClock.sleep(250)
                continue
            }
            val raw = runCatching { commandFile.readText() }.getOrNull()
            commandFile.delete()
            if (raw.isNullOrBlank()) continue
            val parsed = LocalProviderRegressionProtocol.parse(raw, runId, seenIds)
            if (parsed is LocalProviderRegressionProtocol.Parsed.Rejected) {
                writeRejected(raw, parsed.reason)
                continue
            }
            val command = (parsed as LocalProviderRegressionProtocol.Parsed.Ok).command
            seenIds.add(command.id)
            val action = fixedAction(command)
            activeCommandId = command.id
            activeAction = action
            try {
                when (command.kind) {
                    LocalProviderRegressionProtocol.Kind.TOOL -> {
                        val data = executeTool(command)
                        writeResult(command.id, true, data, null, null)
                        writeReceipt(command, true, action)
                    }
                    LocalProviderRegressionProtocol.Kind.DEVICE -> {
                        val data = executeDevice(command)
                        if (data.has("error")) {
                            // One meaning only: an unsupported/error command is ok=false and never also
                            // carries a data object that a host could mistake for a successful result.
                            val code = data.optString("error").take(80).ifBlank { "device-error" }
                            writeResult(command.id, false, null, code, code)
                            writeReceipt(command, false, action)
                        } else {
                            writeResult(command.id, true, data, null, null)
                            writeReceipt(command, true, action)
                        }
                    }
                    LocalProviderRegressionProtocol.Kind.FINISH -> {
                        finish(command, action)
                        return
                    }
                }
            } catch (error: AssertionError) {
                throw error
            } catch (error: CommandFailure) {
                writeResult(command.id, false, null, error.code, error.message)
                writeReceipt(command, false, action)
            } catch (error: Throwable) {
                writeResult(command.id, false, null, "failed", safeMessage(error.message))
                writeReceipt(command, false, action)
            } finally {
                run.onMain { nativeTimeline.mark(NativeFixtureTimelineRing.Kind.COMMAND_END) }
                persistTimeline()
                activeCommandId = null
                activeAction = null
            }
        }
        if (!finished) fail("command-timeout")
    }

    private fun requireDevice() {
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "x86_64") fail("unsupported-abi")
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
            fail("multi-profile-unsupported")
        }
    }

    private fun verifyEnvironment() {
        val manifest = run.runtimeManifest()
        run.verifyProotProvenance()
        val paths = RuntimePaths.under(context.filesDir)
        val inspection = run.installed(paths) ?: fail("runtime-not-installed: run the basic entry first")
        if (inspection.metadata.port != manifest.port) fail("metadata-port")
        run.verifyStockOpenChamber(manifest, paths)
    }

    /** Launch-scoped evidence; the endpoint file exists only while a launch is ready. */
    private fun verifyLaunchArtifacts() {
        val paths = RuntimePaths.under(context.filesDir)
        run.record("pluginAssets", run.verifyPluginSync(paths))
        run.record("toolEndpoint", run.waitToolEndpoint(paths))
        run.record("agentWebToolEnabled", run.verifyAgentWebSetting(paths))
        run.record("pluginConfig", run.verifyPluginConfig(paths))
        run.record("pluginInventory", run.pluginInventory(base, auth!!.header))
    }

    private fun installDecorator() {
        presenter = run.presenter()
        val script = instrumentation.context.assets.open("lifecycle-recorder.js").bufferedReader().use { it.readText() }
        presenter!!.installPageDecorator { page ->
            val pageView = page.view as? WebView ?: return@installPageDecorator
            view = pageView
            OpenChamberProviderFixtures.attach(pageView, pageView.webViewClient)
            check(recorder.attach(pageView, script)) { "recorder attach failed" }
            nativeTimeline.install(pageView)
            decoratedPages += 1
        }
    }

    private fun startProductionRuntime() {
        val controller = run.controller()
        run.waitFor("runtime-detect", 60_000) { controller.state.value !is RuntimeState.Detecting }
        when (val state = controller.state.value) {
            is RuntimeState.Running -> Unit
            RuntimeState.Stopped -> {
                // The launch flow may already have asked for StartAndOpenLocal; start only if it did not.
                if (!controller.busy.value) controller.start()
                if (!run.waitFor("runtime-start", 120_000) { controller.state.value is RuntimeState.Running }) {
                    fail("runtime-start-timeout:${run.stateName(controller.state.value)}")
                }
            }
            is RuntimeState.Starting -> if (!run.waitFor("runtime-start", 120_000) { controller.state.value is RuntimeState.Running }) {
                fail("runtime-start-timeout:${run.stateName(controller.state.value)}")
            }
            else -> fail("runtime-not-ready:${run.stateName(state)}")
        }
        val running = controller.state.value as? RuntimeState.Running ?: fail("runtime-not-running")
        base = running.url
        run.health(base)
        auth = run.authenticate(base)
        run.openCodeVersion(base, auth!!.header, (controller.info.value?.openCodeVersion ?: fail("opencode-version-missing")))
    }

    /**
     * Read-only wait for the production console host. Home may be showing instead of an auto-opened
     * console; the regression status then carries the fixed "Open" target so the host performs the one
     * real ADB tap. This test never attaches the presenter host directly, navigates with JS or changes
     * Koin/DI to force the connection.
     */
    private fun awaitChatHost() {
        val until = SystemClock.elapsedRealtime() + 180_000L
        var lastReason = "starting"
        while (SystemClock.elapsedRealtime() < until) {
            val state = presenter!!.state.value
            if (state.eligible && state.connected) return
            val read = run.readTapTarget("Open", "await-ui", state.viewHandle)
            if (read.target != null) {
                writeStatus("await-ui", "label=Open", read.target)
                lastReason = "label=Open"
            } else if (read.reason != lastReason) {
                writeStatus("await-ui", "missing:${read.reason}")
                lastReason = read.reason
            }
            SystemClock.sleep(500)
        }
        fail("chat-host-not-attached:${presenter!!.state.value}")
    }

    private fun createInitialSession() {
        val session = run.createSession(base, auth!!.header)
        val setup = awaitMainChatSession(session)
        createdSessions.add(session)
        selectedSessionId = session
        rememberSessionSetup(setup)
    }

    /**
     * The API session exists first; this then uses the production notification/deep-link path to make
     * the actual main chat open that session. It never calls BrowserHostSurface.context, attachHost,
     * registry or writes chat context. Readiness is the real local URL + query session, document
     * complete, attached production presenter and a stable Main window.
     */
    private fun awaitMainChatSession(session: String, timeoutMs: Long = 120_000L): JSONObject {
        if (!LocalProviderConsoleSetup.validLink(session, LocalProviderConsoleSetup.SESSION_DIRECTORY)) {
            throw CommandFailure("session-link-invalid", "session/directory")
        }
        val activity = activity ?: throw CommandFailure("no-activity", "no activity")
        val offeredAt = System.currentTimeMillis()
        val pendingBefore = OpenSessionLink.pending.value
        var offerObserved = pendingBefore?.sessionId == session
        var takeObserved = false
        var takeAtMs: Long? = null
        val intent = Intent(context, MainActivity::class.java)
            .setAction(OpenChamberNotifier.ACTION_OPEN_SESSION)
            .putExtra(OpenChamberNotifier.EXTRA_SESSION_ID, session)
            .putExtra(OpenChamberNotifier.EXTRA_DIRECTORY, LocalProviderConsoleSetup.SESSION_DIRECTORY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // One production link per session setup; never retried after a grant failure.
        context.startActivity(intent)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastReason = "console-not-ready"
        var previousKey: String? = null
        var stable = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val pending = OpenSessionLink.pending.value
            if (!offerObserved && pending?.sessionId == session) offerObserved = true
            if (offerObserved && pending == null && !takeObserved) {
                takeObserved = true
                takeAtMs = System.currentTimeMillis()
            }
            if (!run.isActivityResumed(activity)) {
                lastReason = "activity-not-resumed"
                stable = 0
                previousKey = null
                SystemClock.sleep(250)
                continue
            }
            val chat = run.onMain {
                findMainConsoleWebView(activity.window.decorView, presenter?.currentView(), base, session)
            }
            if (chat == null) {
                lastReason = "main-console-webview-missing"
                stable = 0
                previousKey = null
                SystemClock.sleep(250)
                continue
            }
            val url = run.onMain { chat.url.orEmpty() }
            val querySession = LocalProviderConsoleSetup.consoleSession(url, base, session)
            if (querySession == null) {
                lastReason = "console-url-session-mismatch"
                stable = 0
                previousKey = null
                SystemClock.sleep(250)
                continue
            }
            val documentReady = run.eval(chat, "document.readyState")
            val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
            val targetSeparate = run.onMain { presenter?.currentView() !== chat }
            val frame = run.onMain { chatFrame(chat) }
            val activityResumed = run.isActivityResumed(activity)
            val key = listOf(
                url, documentReady, state.viewGeneration, state.viewHandle,
                state.eligible, state.connected, targetSeparate, frame,
            ).joinToString("|")
            val frameStable = previousKey == key
            val contract = LocalProviderConsoleSetup.readyContract(
                activityResumed = activityResumed,
                documentReady = documentReady,
                presenterEligible = state.eligible,
                presenterConnected = state.connected,
                targetViewSeparate = targetSeparate,
                frameStable = frameStable,
                consoleQuerySession = querySession,
                expectedSession = session,
            )
            previousKey = key
            if (!contract) {
                stable = 0
                lastReason = when {
                    documentReady != "complete" -> "console-document-not-complete"
                    !state.eligible -> "presenter-not-eligible"
                    !state.connected -> "host-not-connected"
                    !targetSeparate -> "main-console-is-target-view"
                    !frameStable -> "main-window-not-stable"
                    else -> "console-not-stable"
                }
                SystemClock.sleep(250)
                continue
            }
            stable += 1
            if (stable < 2) {
                SystemClock.sleep(250)
                continue
            }
            val settledAt = System.currentTimeMillis()
            return JSONObject()
                .put("createdSessionId", session)
                .put("consoleUrl", url)
                .put("consoleQuerySession", querySession)
                .put("documentReady", documentReady ?: JSONObject.NULL)
                .put("viewGeneration", state.viewGeneration)
                .put("viewHandle", state.viewHandle)
                .put("presenterEligible", state.eligible)
                .put("presenterConnected", state.connected)
                .put("targetViewSeparate", targetSeparate)
                .put("activityResumed", activityResumed)
                .put("frame", frame)
                .put("uiLink", JSONObject()
                    .put("action", OpenChamberNotifier.ACTION_OPEN_SESSION)
                    .put("offeredAtMs", offeredAt)
                    .put("offerObserved", offerObserved)
                    .put("takeObserved", takeObserved)
                    .put("takeAtMs", takeAtMs ?: JSONObject.NULL)
                    .put("selectedAtMs", settledAt)
                    .put("settledBy", "console-url+document-ready"))
                .put("activityIdentity", run.activityIdentity(activity))
                .put("readyAtMs", settledAt)
        }
        throw CommandFailure("session-ui-not-selected", lastReason)
    }

    private fun chatFrame(web: WebView): JSONObject {
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        return JSONObject()
            .put("left", loc[0]).put("top", loc[1])
            .put("width", web.width).put("height", web.height)
    }

    private fun findMainConsoleWebView(
        root: View?,
        excluded: View?,
        base: String,
        expectedSession: String,
    ): WebView? {
        if (root == null) return null
        if (root is WebView && root !== excluded) {
            val url = root.url.orEmpty()
            if (LocalProviderConsoleSetup.consoleSession(url, base, expectedSession) != null) return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findMainConsoleWebView(root.getChildAt(index), excluded, base, expectedSession)?.let { return it }
            }
        }
        return null
    }

    private fun rememberSessionSetup(setup: JSONObject) {
        sessionSetup = setup
        sessionSetups.put(setup)
        run.record("sessionSetups", sessionSetups)
    }

    private fun executeTool(command: LocalProviderRegressionProtocol.Command): JSONObject {
        val session = command.sessionId ?: selectedSessionId ?: throw CommandFailure("no-session", "no session")
        if (session !in createdSessions) throw CommandFailure("unknown-session", "unknown session")
        val home = RuntimePaths.under(context.filesDir).home
        val endpoint = LocalBrowserTool.discover(home)
            ?: throw CommandFailure("plugin-endpoint-missing", "missing")
        val agent = LocalBrowserToolClient(run, endpoint, session)
        val reply = agent.call(command.action!!, command.input)
        toolCommands += 1
        run.record("toolTransport", reply.transport)
        run.record("toolCallCount", toolCommands)
        if (!reply.ok) {
            throw CommandFailure(reply.errorCode.ifBlank { "tool-error" }, reply.error.take(300))
        }
        // The marker makes every stored tool result explicit: this call stood in for the model and
        // the OpenCode plugin and went straight to /v1/tool; it was not an LLM-driven invocation.
        return JSONObject(reply.data?.toString() ?: "{}")
            .put("transport", reply.transport)
    }

    private fun executeDevice(command: LocalProviderRegressionProtocol.Command): JSONObject {
        val text = command.text.orEmpty()
        val data = when {
            text == "session:new" -> {
                val session = run.createSession(base, auth!!.header)
                val setup = awaitMainChatSession(session, timeoutMs = 15_000L)
                createdSessions.add(session)
                selectedSessionId = session
                rememberSessionSetup(setup)
                writeStatus("ready")
                JSONObject().put("phase", "session-new")
                    .put("sessionId", session)
                    .put("sessionSetup", setup)
            }
            text == "lifecycle-diag" -> gateCommands.handle(text, currentView())
                ?: throw CommandFailure("lifecycle-handler-null", "null")
            text == "geometry-diag" -> geometryDiag()
            text == "composer-layout" -> composerLayout()
            text == "page-rect" -> pageRect()
            text == "fixture-state" -> fixtureState()
            text == "observe" -> observe()
            text == "input-value" -> inputValue()
            text == "ui-state" -> uiState()
            text == "presentation-observe" -> presentationObserve()
            text == "native-entry-state" -> nativeEntryState()
            text == "site-state-read" -> siteStateRead()
            text == "site-state-clear" -> siteStateClear()
            text.startsWith("site-state-write:") -> siteStateWrite(text.removePrefix("site-state-write:"))
            text == "profile-isolation" -> profileIsolation()
            text == "a11y-off:begin" -> Accessibility.begin(instrumentation, context)
            text == "a11y-off:end" -> Accessibility.end(instrumentation, context)
            text == "native-blocked-input" -> nativeBlockedInput()
            text.startsWith("ui-target-chat:") -> uiTargetChat(text.removePrefix("ui-target-chat:"))
            text == "cookie-read" -> cookieRead()
            text.startsWith("cookie-write:") -> cookieWrite(text.removePrefix("cookie-write:"))
            text.startsWith("fixture-target:") -> fixtureTarget(text.removePrefix("fixture-target:"))
            text.startsWith("ui-target:") -> uiTarget(text.removePrefix("ui-target:"))
            text.startsWith("select:") -> selectTab(text.removePrefix("select:"))
            text.startsWith("tap-label:") -> tapLabel(text.removePrefix("tap-label:"))
            text.startsWith("fixture-field:") -> fixtureField(text.removePrefix("fixture-field:"))
            text.startsWith("gate-finger:") || text.startsWith("gate-native:") ->
                gateCommands.handle(text, currentView())
                    ?: throw CommandFailure("gate-handler-null", "null")
            text.startsWith("finger:") -> finger(text.removePrefix("finger:"))
            text.startsWith("two-finger-scroll:") -> twoFingerScroll(text.removePrefix("two-finger-scroll:"))
            text.startsWith("pinch:") -> pinch(text.removePrefix("pinch:"))
            else -> throw CommandFailure("unsupported-device-command", text.take(80))
        }
        deviceCommands += 1
        return data
    }

    private fun finish(command: LocalProviderRegressionProtocol.Command, action: String) {
        // Restore before any summary/result write: later cases and the host both depend on it.
        restoreAccessibility()
        val summary = command.summary ?: JSONObject()
        caseSummary = summary
        val missing = LocalProviderRegressionProtocol.missingCases(summary, requiredCases)
        if (missing.isNotEmpty()) {
            writeResult(command.id, false, null, "finish-case-missing", missing.joinToString(","))
            writeReceipt(command, false, action)
            fail("finish-case-missing:${missing.joinToString(",")}")
        }
        if (toolCommands <= 0 || deviceCommands <= 0) {
            writeResult(command.id, false, null, "finish-counters-empty", "tool=$toolCommands device=$deviceCommands")
            writeReceipt(command, false, action)
            fail("finish-counters-empty")
        }
        writeResult(command.id, true, JSONObject()
            .put("toolCommands", toolCommands)
            .put("deviceCommands", deviceCommands)
            .put("expectedCases", JSONArray(requiredCases))
            .put("caseScope", caseScope)
            .put("casePartial", casePartial)
            .put("cases", JSONObject(summary.toString())), null, null)
        writeReceipt(command, true, action)
        // Mark complete before writing evidence: the evidence phase must be done, not failed.
        finished = true
        writeStatus("done")
        writeEvidence()
    }

    private fun fixedAction(command: LocalProviderRegressionProtocol.Command): String =
        command.action ?: command.text?.substringBefore(':')?.take(40) ?: "finish"

    private fun currentView(): WebView? = run.onMain { presenter?.currentView() as? WebView }

    /**
     * Single entry point for accessibility-tree reads. While the a11y-off hover window is active the
     * UiAutomation connection intentionally has no accessibility client, so every tree command refuses
     * with the typed a11y-off-active code instead of reading a disconnected automation.
     */
    private fun accessibility(): UiAutomation {
        Accessibility.requireTree()
        val automation = instrumentation.uiAutomation
        // Compose updates a Text node in place (Enlarge -> Restore); the a11y cache may keep serving
        // the old text for the same node id. Every read is a fresh observation (API 34+).
        if (Build.VERSION.SDK_INT >= 34) runCatching { automation.clearCache() }
        return automation
    }

    private fun stateViewHandle(): Int? = presenter?.state?.value?.viewHandle

    private fun pageGrantSelected(): Boolean = run.onMain {
        val presenter = presenter ?: return@onMain false
        val state = presenter.state.value
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId } ?: return@onMain false
        val grant = presenter.registry.currentGrant() ?: return@onMain false
        !state.pageRetired && grant.tabId == state.selectedTabId && grant.sessionId == selected.owner
    }

    private fun geometryDiag(): JSONObject {
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        return JSONObject()
            .put("phase", "geometry-diag")
            .put("agentOn", state.agentOn)
            .put("mode", state.mode.name)
            .put("pageReady", state.pageReady)
            .put("eligible", state.eligible)
            .put("connected", state.connected)
            .put("selectedTabId", state.selectedTabId ?: JSONObject.NULL)
            .put("selectedOwner", selected?.owner ?: JSONObject.NULL)
            .put("documentEpoch", state.documentEpoch)
            .put("viewHandle", state.viewHandle)
            .put("holdProbe", LocalProviderRegressionEvidence.HOLD_PROBE)
    }

    private fun composerLayout(): JSONObject {
        if (presenter == null) throw CommandFailure("no-presenter", "no presenter")
        val target = currentView()
        val activity = activity ?: throw CommandFailure("no-activity", "no activity")
        return run.onMain {
            val web = findComposerWebView(activity.window.decorView, target)
                ?: return@onMain null
            val result = JSONObject().put("phase", "composer-layout")
            val xy = IntArray(2)
            web.getLocationOnScreen(xy)
            val frame = Rect()
            web.getWindowVisibleDisplayFrame(frame)
            result.put("mainWebView", JSONObject()
                .put("x", xy[0]).put("y", xy[1]).put("width", web.width).put("height", web.height))
            result.put("usableFrame", JSONObject()
                .put("left", frame.left).put("top", frame.top)
                .put("right", frame.right).put("bottom", frame.bottom))
            when (val snapshot = web.tag as? ComposerLayoutSnapshot) {
                null -> result.put("validityReason", "no-layout-snapshot")
                else -> {
                    result.put("consoleGeneration", snapshot.consoleGeneration)
                        .put("nativeGeneration", snapshot.viewGeneration)
                        .put("contentWidth", snapshot.contentWidth)
                        .put("contentHeight", snapshot.contentHeight)
                        .put("selectedClearancePx", snapshot.selectedClearancePx)
                        .put("fallback", snapshot.fallback)
                        .put("validityReason", snapshot.validityReason)
                    snapshot.measurement?.let { measurement ->
                        result.put("measurement", JSONObject()
                            .put("consoleGeneration", measurement.consoleGeneration)
                            .put("nativeGeneration", measurement.viewGeneration)
                            .put("nativeWidth", measurement.nativeWidth)
                            .put("nativeHeight", measurement.nativeHeight)
                            .put("top", measurement.metrics.top)
                            .put("bottom", measurement.metrics.bottom)
                            .put("viewportWidth", measurement.metrics.viewportWidth)
                            .put("viewportHeight", measurement.metrics.viewportHeight)
                            .put("visible", measurement.metrics.visible))
                    }
                }
            }
            result
        } ?: throw CommandFailure("no-composer-webview", "no tagged composer webview")
    }

    /** Only the real chat WebView carries a ComposerLayoutSnapshot tag; the target page is excluded. */
    private fun findComposerWebView(view: View?, excluded: View?): WebView? {
        if (view == null) return null
        if (view is WebView && view !== excluded && view.tag is ComposerLayoutSnapshot) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findComposerWebView(view.getChildAt(index), excluded)?.let { return it }
            }
        }
        return null
    }

    private fun pageRect(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        val raw = run.eval(web, PAGE_RECT_SCRIPT) ?: throw CommandFailure("page-rect-eval-null", "eval null")
        val page = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("page-rect-not-json", "not json")
        val frame = run.onMain {
            val loc = IntArray(2)
            web.getLocationOnScreen(loc)
            JSONObject().put("x", loc[0]).put("y", loc[1])
                .put("w", web.width).put("h", web.height)
        }
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        val pointer = state.pointerUi
        return JSONObject()
            .put("phase", "page-rect")
            .put("x", frame.getInt("x")).put("y", frame.getInt("y"))
            .put("w", frame.getInt("w")).put("h", frame.getInt("h"))
            .put("physical", JSONObject()
                .put("left", frame.getInt("x")).put("top", frame.getInt("y"))
                .put("width", frame.getInt("w")).put("height", frame.getInt("h")))
            .put("page", page)
            .put("viewHandle", state.viewHandle)
            .put("documentEpoch", state.documentEpoch)
            .put("pointerUi", JSONObject()
                .put("viewHandle", pointer.viewHandle)
                .put("geometryEpoch", pointer.geometryEpoch)
                .put("pointerViewX", pointer.pointerViewX)
                .put("pointerViewY", pointer.pointerViewY)
                .put("geometryReady", pointer.geometryReady))
            .put("pageGrant", pageGrantSelected())
    }

    private fun fixtureState(): JSONObject {
        val web = currentView()
        if (web == null) {
            val native = nativeFixtureJson(null)
            writeFixtureDiagnostic("no-view", "no view", null, null, native, native, null, null)
            throw CommandFailure("no-view", "no view")
        }
        val currentUrl = run.onMain { web.url.orEmpty() }
        if (currentUrl !in FIXTURE_URLS) {
            val native = nativeFixtureJson(web)
            val identity = fixtureIdentity(web)
            writeFixtureDiagnostic("not-fixture", "not fixture", identity, identity, native, native, null, currentUrl)
            throw CommandFailure("not-fixture", "not fixture")
        }
        val nativeBefore = nativeFixtureJson(web)
        val before = fixtureIdentity(web)
        val raw = run.eval(web, FIXTURE_STATE_SCRIPT)
        val after = fixtureIdentity(web)
        val nativeAfter = nativeFixtureJson(web)
        val parsed = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val pageForDiagnostic = parsed?.takeIf { it.optString("url") in FIXTURE_URLS }
        if (currentView() !== web || !sameFixtureSource(before, after)) {
            writeFixtureDiagnostic("source-changed", "source changed", before, after, nativeBefore, nativeAfter, pageForDiagnostic, currentUrl)
            throw CommandFailure("source-changed", "source changed")
        }
        if (raw == null) {
            writeFixtureDiagnostic("fixture-state-eval", "eval null", before, after, nativeBefore, nativeAfter, null, currentUrl)
            throw CommandFailure("fixture-state-eval", "eval null")
        }
        if (parsed == null) {
            writeFixtureDiagnostic("fixture-state-not-json", "eval not json", before, after, nativeBefore, nativeAfter, null, currentUrl)
            throw CommandFailure("fixture-state-not-json", "eval not json")
        }
        if (parsed.optString("url") !in FIXTURE_URLS) {
            writeFixtureDiagnostic("not-fixture", "evaluated page is not a fixture", before, after, nativeBefore, nativeAfter, null, currentUrl)
            throw CommandFailure("not-fixture", "evaluated page is not a fixture")
        }
        return JSONObject()
            .put("phase", "fixture-state")
            .put("page", parsed)
            .put("native", nativeAfter.native)
            .put("postArrivals", OpenChamberProviderFixtures.postArrivals)
            .put("ime", JSONObject.NULL)
            .put("outerime", nativeAfter.outerIme)
            .put("identityBefore", before)
            .put("identityAfter", after)
    }

    /**
     * Fixed-fixture failure evidence. Only the exact example.com fixture page may contribute page fields;
     * any other URL records native/focus/identity only, never arbitrary DOM. No token or cookie is read.
     */
    private fun writeFixtureDiagnostic(
        code: String,
        detail: String,
        before: JSONObject?,
        after: JSONObject?,
        nativeBefore: NativeFixtureRead?,
        nativeAfter: NativeFixtureRead?,
        page: JSONObject?,
        currentUrl: String?,
    ) {
        val commandId = activeCommandId ?: "unknown"
        val safeId = commandId.replace(Regex("[^A-Za-z0-9._:-]"), "_")
        val allowedUrl = currentUrl in FIXTURE_URLS
        val source = JSONObject()
            .put("url", if (allowedUrl) currentUrl else JSONObject.NULL)
            .put("selectedTabId", (after ?: before)?.opt("selectedTabId") ?: JSONObject.NULL)
            .put("viewHandle", (after ?: before)?.opt("viewHandle") ?: JSONObject.NULL)
        val body = JSONObject()
            .put("runId", runId)
            .put("commandId", commandId)
            .put("action", activeAction ?: "fixture-state")
            .put("phase", "fixture-state-diagnostic")
            .put("code", code.take(80))
            .put("message", safeMessage(detail))
            .put("source", source)
            .put("identityBefore", before ?: JSONObject.NULL)
            .put("identityAfter", after ?: JSONObject.NULL)
            .put("nativeBefore", nativeBefore?.native ?: JSONObject.NULL)
            .put("outerImeBefore", nativeBefore?.outerIme ?: JSONObject.NULL)
            .put("nativeAfter", nativeAfter?.native ?: JSONObject.NULL)
            .put("outerImeAfter", nativeAfter?.outerIme ?: JSONObject.NULL)
            .put("page", if (allowedUrl && page != null) page else JSONObject.NULL)
        runCatching { run.writePrivate(File(diagnosticsDir, "$safeId.json"), body.toString()) }
    }

    /**
     * Identity is sampled before and after the one page eval. The exact document epoch may move on a
     * grant revoke without navigation, so it is exported, not used as a blanket navigation verdict.
     * A different current view, tab or source frame is a typed `source-changed` failure.
     */
    private fun sameFixtureSource(before: JSONObject, after: JSONObject): Boolean =
        before.optString("selectedTabId") == after.optString("selectedTabId") &&
            before.optString("owner") == after.optString("owner") &&
            before.optLong("viewHandle") == after.optLong("viewHandle") &&
            before.optJSONObject("frame").toString() == after.optJSONObject("frame").toString()

    private fun fixtureIdentity(web: WebView): JSONObject = run.onMain {
        val state = presenter!!.state.value
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        val loc = IntArray(2)
        web.getLocationOnScreen(loc)
        JSONObject()
            .put("selectedTabId", state.selectedTabId ?: JSONObject.NULL)
            .put("owner", selected?.owner ?: JSONObject.NULL)
            .put("viewHandle", state.viewHandle)
            .put("viewGeneration", state.viewGeneration)
            .put("documentEpoch", state.documentEpoch)
            .put("frame", JSONObject()
                .put("left", loc[0]).put("top", loc[1])
                .put("width", web.width).put("height", web.height))
    }

    private class NativeFixtureRead(val native: JSONObject, val outerIme: JSONObject)

    private fun nativeFixtureJson(web: WebView?): NativeFixtureRead = run.onMain {
        val state = presenter!!.state.value
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        val native = JSONObject()
            .put("selectedTabId", state.selectedTabId ?: JSONObject.NULL)
            .put("owner", selected?.owner ?: JSONObject.NULL)
            .put("viewHandle", state.viewHandle)
            .put("viewGeneration", state.viewGeneration)
            .put("documentEpoch", state.documentEpoch)
            .put("shown", state.shown)
            .put("pageReady", state.pageReady)
            .put("mouseMode", state.mouseMode)
            .put("agentOn", state.agentOn)
            .put("presentationBlocked", state.presentationBlocked)
            .put("mode", state.mode.name)
            .put("error", when (state.pageError) {
                null, "" -> "none"
                "reopen-required", "mode-reopen-required", "Re-open this page before allowing control" -> "reopen-required"
                else -> "other"
            })
        if (web != null) {
            native.put("hasFocus", web.hasFocus())
            native.put("hasWindowFocus", web.hasWindowFocus())
            native.put("isShown", web.isShown)
            native.put("windowTokenPresent", web.windowToken != null)
            val visibleRect = Rect()
            val visible = web.getGlobalVisibleRect(visibleRect)
            native.put("screenVisibleRect", JSONObject()
                .put("left", visibleRect.left).put("top", visibleRect.top)
                .put("right", visibleRect.right).put("bottom", visibleRect.bottom)
                .put("visible", visible))
            val frame = IntArray(2)
            web.getLocationOnScreen(frame)
            native.put("physicalFrame", JSONObject()
                .put("left", frame[0]).put("top", frame[1])
                .put("width", web.width).put("height", web.height))
        } else {
            native.put("hasFocus", JSONObject.NULL)
            native.put("hasWindowFocus", JSONObject.NULL)
            native.put("isShown", JSONObject.NULL)
            native.put("windowTokenPresent", JSONObject.NULL)
            native.put("screenVisibleRect", JSONObject.NULL)
            native.put("physicalFrame", JSONObject.NULL)
        }
        val decor = activity?.window?.decorView
        native.put("timeline", nativeTimeline.summary())
        native.put("focusChain", NativeFixtureFocusDiagnostics.snapshot(
            web, presenter?.currentView(), activity?.currentFocus, decor?.findFocus(),
        ))
        val insets = decor?.let { ViewCompat.getRootWindowInsets(it) }
        val outerIme = if (insets == null) {
            JSONObject().put("shown", JSONObject.NULL)
                .put("source", "unavailable")
                .put("bottomPx", JSONObject.NULL)
        } else {
            val ime = WindowInsetsCompat.Type.ime()
            JSONObject().put("shown", insets.isVisible(ime))
                .put("source", "root-window-insets")
                .put("bottomPx", insets.getInsets(ime).bottom)
        }
        NativeFixtureRead(native, outerIme)
    }

    private fun observe(): JSONObject {
        val web = currentView() ?: return JSONObject().put("phase", "observe").put("error", "no-view")
        val currentUrl = run.onMain { web.url.orEmpty() }
        if (currentUrl !in FIXTURE_URLS) {
            return JSONObject().put("phase", "observe").put("error", "not-fixture").put("url", currentUrl.take(200))
        }
        val script = observerAsset()
        val raw = run.eval(web, script) ?: return JSONObject().put("phase", "observe").put("error", "observe-null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: return JSONObject().put("phase", "observe").put("error", "observe-not-json")
        val url = body.optString("url")
        if (!body.has("url") || url !in FIXTURE_URLS) {
            return JSONObject().put("phase", "observe").put("error", "not-fixture").put("url", url.take(200))
        }
        val result = JSONObject().put("phase", "observe")
        for (key in OBSERVE_FIELDS) {
            if (body.has(key) && !body.isNull(key)) result.put(key, body.get(key)) else result.put(key, JSONObject.NULL)
        }
        return result
    }

    private fun inputValue(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        val raw = run.eval(web, "document.getElementById('note')&&document.getElementById('note').value")
            ?: throw CommandFailure("eval-null", "null")
        return JSONObject().put("phase", "input-value").put("value", raw.take(200))
    }

    private fun fixtureField(field: String): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        val url = run.onMain { web.url.orEmpty() }
        if (field in setOf("note", "Apply") && url != OpenChamberProviderFixtures.FOCUSED_URL) {
            throw CommandFailure("not-focused-url", "not focused")
        }
        if (field in setOf("login-user", "Sign in") && url != OpenChamberProviderFixtures.LOGIN_URL) {
            throw CommandFailure("not-login-url", "not login")
        }
        val raw = run.eval(web, FIXTURE_FIELD_SCRIPT.replace("__FIELD__", JSONObject.quote(field)))
            ?: throw CommandFailure("eval-null", "null")
        return runCatching { JSONObject(raw).put("phase", "fixture-field") }
            .getOrElse { throw CommandFailure("eval-not-json", "not json") }
    }

    /**
     * Fixed retirement DOM reader. It reads only #box/#next on the exact retirement fixture, reports
     * CSS client bounds (not screen/native), a visible-rect intersection, disabled/connected state and
     * an elementFromPoint hit. It never scrolls, clicks, captures or releases anything.
     */
    private fun fixtureTarget(spec: String): JSONObject {
        val parts = spec.split(",")
        if (parts.size != 3) throw CommandFailure("bad-fixture-target", "bad arity")
        val id = parts[0]
        if (id !in LocalProviderRegressionProtocol.FIXTURE_TARGETS) {
            throw CommandFailure("unknown-fixture-target", id.take(40))
        }
        val x = parts[1].toFloatOrNull()
        val y = parts[2].toFloatOrNull()
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
            throw CommandFailure("bad-point", "point")
        }
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        val url = run.onMain { web.url.orEmpty() }
        val retire = url == OpenChamberProviderFixtures.RETIRE_URL && id in LocalProviderRegressionProtocol.FIXTURE_TARGETS
        val mouse = url == OpenChamberProviderFixtures.MOUSE_URL && id == "box"
        if (!retire && !mouse) throw CommandFailure("not-fixture-target-url", "not fixture target")
        val before = sourceIdentity(web)
        val script = FIXTURE_TARGET_SCRIPT
            .replace("__ID__", JSONObject.quote(id))
            .replace("__X__", x.toString())
            .replace("__Y__", y.toString())
        val raw = run.eval(web, script) ?: throw CommandFailure("fixture-target-null", "eval null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("fixture-target-not-json", "not json")
        val after = sourceIdentity(web)
        if (currentView() !== web || before.toString() != after.toString()) {
            throw CommandFailure("source-changed", "source changed")
        }
        body.optString("error").takeIf { it.isNotBlank() }?.let {
            throw CommandFailure("fixture-target-$it", it)
        }
        if (body.optString("url") != url) {
            throw CommandFailure("fixture-target-url-changed", "url")
        }
        if (!body.optBoolean("pointInViewport")) throw CommandFailure("point-outside-visual", "point")
        if (!body.optBoolean("connected")) throw CommandFailure("node-not-connected", "node")
        val timeOrigin = body.optDouble("timeOrigin", Double.NaN)
        if (!timeOrigin.isFinite()) throw CommandFailure("time-origin-missing", "timeOrigin")
        val beforeOrigin = before.timeOrigin?.toDoubleOrNull()
        if (beforeOrigin == null || !beforeOrigin.isFinite() || kotlin.math.abs(beforeOrigin - timeOrigin) > 0.001) {
            throw CommandFailure("time-origin-changed", "timeOrigin")
        }
        val box = body.optJSONObject("box") ?: throw CommandFailure("box-missing", "box")
        val page = body.optJSONObject("page") ?: throw CommandFailure("page-missing", "page")
        return JSONObject()
            .put("phase", "fixture-target")
            .put("id", id)
            .put("timeOrigin", timeOrigin)
            .put("box", box)
            .put("visibleRect", body.optJSONObject("visibleRect") ?: JSONObject.NULL)
            .put("visible", body.optBoolean("visible"))
            .put("hit", body.optBoolean("hit"))
            .put("disabled", body.optBoolean("disabled"))
            .put("connected", true)
            .put("pointInViewport", true)
            .put("page", page)
            .put("url", body.optString("url"))
            .put("viewHandle", before.viewHandle)
            .put("documentEpoch", before.documentEpoch)
    }

    /**
     * Cookie read is limited to the single test cookie on the retirement fixture and reports only that
     * pair (`oc_fixture_retire=<value>`), never another cookie, CookieManager or the whole document.
     */
    private fun cookieRead(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        if (run.onMain { web.url.orEmpty() } != OpenChamberProviderFixtures.RETIRE_URL) {
            throw CommandFailure("not-retire-url", "not retire")
        }
        val raw = run.eval(web, COOKIE_READ_SCRIPT) ?: throw CommandFailure("cookie-read-null", "null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("cookie-read-not-json", "not json")
        return JSONObject()
            .put("phase", "cookie-read")
            .put("name", LocalProviderRegressionProtocol.TEST_COOKIE_NAME)
            .put("present", body.optBoolean("present"))
            .put("value", if (body.optBoolean("present")) body.optString("pair") else JSONObject.NULL)
    }

    /**
     * Writes one real test cookie on the retirement fixture and returns set=true only when the
     * immediate readback is exactly that pair. The old view is tracked so a later write from a new
     * reopened view cannot be used to fake the cookie-kept check.
     */
    private fun cookieWrite(value: String): JSONObject {
        if (!LocalProviderRegressionProtocol.isTestCookieValue(value)) {
            throw CommandFailure("bad-test-cookie", "bad value")
        }
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        if (run.onMain { web.url.orEmpty() } != OpenChamberProviderFixtures.RETIRE_URL) {
            throw CommandFailure("not-retire-url", "not retire")
        }
        val before = sourceIdentity(web)
        val previous = cookieWriteHandle
        if (previous != null && previous != before.viewHandle) {
            throw CommandFailure("cookie-write-new-view-refused", "new view")
        }
        val raw = run.eval(web, COOKIE_WRITE_SCRIPT.replace("__VALUE__", JSONObject.quote(value)))
            ?: throw CommandFailure("cookie-write-null", "null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("cookie-write-not-json", "not json")
        val after = sourceIdentity(web)
        if (currentView() !== web || before.toString() != after.toString()) {
            throw CommandFailure("source-changed", "source changed")
        }
        cookieWriteHandle = before.viewHandle
        return JSONObject()
            .put("phase", "cookie-write")
            .put("name", LocalProviderRegressionProtocol.TEST_COOKIE_NAME)
            .put("set", body.optBoolean("set"))
            .put("present", body.optBoolean("present"))
    }

    private class SourceIdentity(
        val frame: String,
        val viewHandle: Int,
        val documentEpoch: Long,
        val timeOrigin: String?,
    ) {
        override fun toString(): String = "$frame|$viewHandle|$documentEpoch|$timeOrigin"
    }

    private fun sourceIdentity(web: WebView): SourceIdentity {
        val frame = run.onMain {
            val loc = IntArray(2)
            web.getLocationOnScreen(loc)
            "${loc[0]},${loc[1]},${web.width},${web.height}"
        }
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        val timeOrigin = run.eval(web, TIME_ORIGIN_SCRIPT)
        return SourceIdentity(frame, state.viewHandle, state.documentEpoch, timeOrigin)
    }

    private fun uiTarget(label: String): JSONObject {
        Accessibility.requireTree()
        val target = run.readTapTarget(label, "ui-target", stateViewHandle()).target
            ?: throw CommandFailure("missing-target", label)
        val age = (System.currentTimeMillis() - target.observedAtMs).coerceAtLeast(0)
        return target.toJson()
            .put("phase", "ui-target")
            .put("ageMs", age)
            .put("fresh", age <= 5_000)
    }

    /**
     * One UiAutomation traversal for all fixed labels. Only exact fixed label text/descriptions are
     * kept; chat text is never copied. No waitForIdle. Surface/Tight are recorded even though they are
     * not clickable.
     */
    private fun readUiLabels(): Pair<JSONObject, String?> {
        Accessibility.requireTree()
        val root = try {
            accessibility().rootInActiveWindow
        } catch (error: Throwable) {
            return JSONObject() to "accessibility-unavailable:${error.javaClass.simpleName}"
        } ?: return JSONObject() to "no-active-window"
        val tracked = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        fun track(node: AccessibilityNodeInfo) {
            tracked.add(node)
        }
        return try {
            track(root)
            val owner = root.packageName?.toString().orEmpty()
            if (owner != context.packageName) {
                return JSONObject() to "active-window-not-own-package"
            }
            val labels = JSONObject()
            LocalProviderRegressionProtocol.UI_STATE_LABELS.forEach { labels.put(it, JSONObject.NULL) }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val matched = LocalProviderRegressionProtocol.UI_STATE_LABELS.firstOrNull { it == text || it == desc }
                if (matched != null && node.packageName?.toString() == context.packageName && labels.isNull(matched)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    labels.put(matched, JSONObject()
                        .put("text", text ?: JSONObject.NULL)
                        .put("desc", desc ?: JSONObject.NULL)
                        .put("matchedBy", if (text == matched && desc == matched) "both" else if (text == matched) "text" else "desc")
                        .put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom)
                        .put("visible", node.isVisibleToUser)
                        .put("enabled", node.isEnabled)
                        .put("clickable", node.isClickable)
                        .put("windowId", node.windowId)
                        .put("package", owner)
                        .put("phase", "ui-state")
                        .put("runId", runId)
                        .put("observedAtMs", System.currentTimeMillis()))
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        track(child)
                        queue.add(child)
                    }
                }
            }
            labels to null
        } catch (error: Throwable) {
            JSONObject() to "accessibility-read-failed:${error.javaClass.simpleName}"
        } finally {
            tracked.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
        }
    }

    private fun uiState(): JSONObject {
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        val (labels, reason) = readUiLabels()
        if (reason != null) throw CommandFailure(reason.substringBefore(':'), reason)
        return JSONObject()
            .put("phase", "ui-state")
            .put("selectedTabId", state.selectedTabId ?: JSONObject.NULL)
            .put("eligible", state.eligible)
            .put("connected", state.connected)
            .put("shown", state.shown)
            .put("pageReady", state.pageReady)
            .put("agentOn", state.agentOn)
            .put("mode", state.mode.name)
            .put("viewHandle", state.viewHandle)
            .put("observedAtMs", System.currentTimeMillis())
            .put("labels", labels)
    }

    /**
     * Read-only state of the native OpenBrowserUrlDialog. It reads the only editable field and the
     * Open button from the accessibility tree; the typed value itself is never exported, only
     * whether it is exactly the fixed fixture URL and its length.
     */
    private fun nativeEntryState(): JSONObject {
        Accessibility.requireTree()
        val root = try {
            accessibility().rootInActiveWindow
        } catch (error: Throwable) {
            throw CommandFailure("accessibility-unavailable", error.javaClass.simpleName)
        } ?: throw CommandFailure("no-active-window", "no window")
        val tracked = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        fun track(node: AccessibilityNodeInfo) { tracked.add(node) }
        return try {
            track(root)
            if (root.packageName?.toString() != context.packageName) {
                throw CommandFailure("active-window-not-own-package", root.packageName?.toString().orEmpty())
            }
            var address: AccessibilityNodeInfo? = null
            var open: AccessibilityNodeInfo? = null
            var titlePresent = false
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser) {
                    val text = node.text?.toString().orEmpty()
                    if (text == "Open web page") titlePresent = true
                    if (address == null && node.isEditable && node.isEnabled) address = node
                    // Material buttons expose the label on a child; the clickable node is its parent.
                    if (open == null && text == "Open") {
                        open = if (node.isClickable) node else node.parent?.also { track(it) }?.takeIf { it.isClickable }
                    }
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        track(child)
                        queue.add(child)
                    }
                }
            }
            val value = address?.text?.toString().orEmpty()
            JSONObject()
                .put("phase", "native-entry-state")
                .put("dialog", address != null)
                .put("titlePresent", titlePresent)
                .put(
                    "address",
                    JSONObject()
                        .put("present", address != null)
                        .put("focused", address?.isFocused ?: false)
                        .put("editable", address?.isEditable ?: false)
                        .put("enabled", address?.isEnabled ?: false)
                        .put("valueLength", value.length)
                        .put("valueExact", value == LocalProviderConsoleSetup.FOCUSED_URL),
                )
                .put(
                    "open",
                    JSONObject()
                        .put("present", open != null)
                        .put("enabled", open?.isEnabled ?: false),
                )
                .put("selectedTabId", presenter?.state?.value?.selectedTabId ?: JSONObject.NULL)
        } finally {
            tracked.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
        }
    }

    private fun presentationObserve(): JSONObject {
        val ui = safePhase("ui-state") { uiState() }
        val labels = ui.optJSONObject("labels")
        if (labels != null) {
            val nodes = JSONArray()
            val keys = labels.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val node = labels.optJSONObject(key) ?: continue
                nodes.put(JSONObject(node.toString()).put("label", key))
            }
            ui.put("nodes", nodes)
            ui.put("surface", labels.opt("BrowserFloatSurface") ?: JSONObject.NULL)
            ui.put("tight", labels.opt("BrowserFloatTight") ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("phase", "presentation-observe")
            .put("fixture", safePhase("fixture-state") { fixtureState() })
            .put("observe", safePhase("observe") { observe() })
            .put("ui", ui)
            .put("composer", safePhase("composer-layout") { composerLayout() })
    }

    private fun safePhase(phase: String, block: () -> JSONObject): JSONObject =
        try {
            block()
        } catch (error: CommandFailure) {
            JSONObject().put("phase", phase).put("error", error.code)
        } catch (error: Throwable) {
            JSONObject().put("phase", phase).put("error", "failed:${error.javaClass.simpleName}")
        }

    private fun requireFocused(web: WebView, code: String) {
        if (run.onMain { web.url.orEmpty() } != OpenChamberProviderFixtures.FOCUSED_URL) {
            throw CommandFailure(code, "not focused")
        }
    }

    private fun siteStateWrite(value: String): JSONObject {
        if (!LocalProviderRegressionProtocol.isSiteStateMarker(value)) {
            throw CommandFailure("bad-site-marker", "marker")
        }
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        requireFocused(web, "not-focused-url")
        val raw = run.eval(web, SITE_STATE_WRITE_SCRIPT.replace("__VALUE__", JSONObject.quote(value)))
            ?: throw CommandFailure("site-write-eval-null", "null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("site-write-not-json", "not json")
        val pair = body.optString("cookiePair")
        val storage = body.optString("storageValue")
        val set = pair == "oc_product_site=$value" && storage == value
        return JSONObject()
            .put("phase", "site-state-write")
            .put("set", set)
            .put("cookiePresent", pair.isNotBlank())
            .put("storagePresent", storage.isNotBlank())
    }

    private fun siteStateRead(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        requireFocused(web, "not-focused-url")
        val raw = run.eval(web, SITE_STATE_READ_SCRIPT)
            ?: throw CommandFailure("site-read-eval-null", "null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("site-read-not-json", "not json")
        val pair = body.optString("cookiePair").takeIf { it.isNotBlank() }
        val storage = body.optString("storageValue").takeIf { it.isNotBlank() }
        val cookieValue = pair?.substringAfter("oc_product_site=", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        val profileName = run.onMain { WebViewCompat.getProfile(web).name }
        return JSONObject()
            .put("phase", "site-state-read")
            .put("cookiePresent", pair != null)
            .put("storagePresent", storage != null)
            .put("cookieHash", cookieValue?.let { sha256(it.toByteArray()) } ?: JSONObject.NULL)
            .put("storageHash", storage?.let { sha256(it.toByteArray()) } ?: JSONObject.NULL)
            .put("profileName", profileName)
    }

    private fun siteStateClear(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        requireFocused(web, "not-focused-url")
        val raw = run.eval(web, SITE_STATE_CLEAR_SCRIPT)
            ?: throw CommandFailure("site-clear-eval-null", "null")
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw CommandFailure("site-clear-not-json", "not json")
        return JSONObject()
            .put("phase", "site-state-clear")
            .put("cleared", !body.optBoolean("cookiePresent") && !body.optBoolean("storagePresent"))
    }

    private fun cookieSet(manager: CookieManager, url: String, header: String): Boolean {
        val future = CompletableFuture<Boolean>()
        run.onMain { manager.setCookie(url, header) { accepted -> future.complete(accepted == true) } }
        return runCatching { future.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun cookieGet(manager: CookieManager, url: String): String? =
        run.onMain { manager.getCookie(url) }

    private fun flushCookies(manager: CookieManager) {
        run.onMain { manager.flush() }
    }

    private fun cookieHas(header: String?, name: String, value: String? = null): Boolean =
        header.orEmpty().split(';').map { it.trim() }
            .any { it.startsWith("$name=") && (value == null || it == "$name=$value") }

    private fun profileIsolation(): JSONObject {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            throw CommandFailure("multi-profile-unsupported", "unsupported")
        }
        val target = currentView() ?: throw CommandFailure("no-view", "no target view")
        requireFocused(target, "not-focused-url")
        val session = selectedSessionId ?: throw CommandFailure("no-session", "no session")
        val activity = activity ?: throw CommandFailure("no-activity", "no activity")
        val console = run.onMain {
            findMainConsoleWebView(activity.window.decorView, target, base, session)
        } ?: throw CommandFailure("no-console-view", "no console")
        if (run.onMain { presenter?.currentView() === console }) {
            throw CommandFailure("console-is-target", "console")
        }
        val otherTarget = ServerTarget.Remote(
            id = "product-other",
            displayName = "Product other",
            baseUrl = base,
            uiPassword = null,
        )
        val profiles = run.service<WebSessionProfiles>()
        val binding = run.onMain { profiles.acquire(otherTarget) }
        val otherView = run.onMain { WebView(context) }
        val managers = try {
            run.onMain {
                binding.bindFreshWebView(otherView)
                listOf(
                    WebViewCompat.getProfile(target).cookieManager,
                    WebViewCompat.getProfile(console).cookieManager,
                    WebViewCompat.getProfile(otherView).cookieManager,
                )
            }
        } catch (error: Throwable) {
            run.onMain { otherView.destroy() }
            throw CommandFailure("other-profile-bind-failed", error.javaClass.simpleName)
        }
        val names = listOf("oc_product_profile_browser", "oc_product_profile_console", "oc_product_profile_other")
        val profilesJson = run.onMain {
            JSONObject()
                .put("browser", WebViewCompat.getProfile(target).name)
                .put("console", WebViewCompat.getProfile(console).name)
                .put("other", WebViewCompat.getProfile(otherView).name)
        }
        val distinct = setOf(
            profilesJson.optString("browser"),
            profilesJson.optString("console"),
            profilesJson.optString("other"),
        ).size == 3
        val values = names.map { "${it}-${UUID.randomUUID().toString().replace("-", "")}" }
        val origin = "https://example.com"
        fun expireAll() {
            for (manager in managers) {
                for (name in names) {
                    cookieSet(manager, origin, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
                }
                flushCookies(manager)
            }
        }
        try {
            expireAll()
            for (index in managers.indices) {
                if (!cookieSet(managers[index], origin, "${names[index]}=${values[index]}; Path=/; Max-Age=3600; Secure; SameSite=Lax")) {
                    throw CommandFailure("cookie-write-rejected", names[index])
                }
                flushCookies(managers[index])
            }
            val checks = JSONArray()
            val writerNames = listOf("browser", "console", "other")
            for (writer in managers.indices) {
                val others = managers.indices.filter { it != writer }
                checks.put(ProfileIsolationEvidence.check(
                    writer = writerNames[writer],
                    ownPresent = cookieHas(cookieGet(managers[writer], origin), names[writer], values[writer]),
                    otherWriters = others.map { writerNames[it] },
                    otherPresent = others.map { other ->
                        cookieHas(cookieGet(managers[other], origin), names[writer], values[writer])
                    },
                ))
            }
            expireAll()
            val stillPresent = managers.any { manager ->
                names.any { name -> cookieHas(cookieGet(manager, origin), name) }
            }
            return JSONObject()
                .put("phase", "profile-isolation")
                .put("supported", true)
                .put("profiles", profilesJson)
                .put("cookieStoresDistinct", distinct)
                .put("checks", checks)
                .put("cleaned", ProfileIsolationEvidence.cleaned(stillPresent))
        } finally {
            runCatching { expireAll() }
            run.onMain { otherView.destroy() }
        }
    }

    private fun nativeBlockedInput(): JSONObject {
        val web = currentView() ?: throw CommandFailure("no-view", "no view")
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        if (!state.presentationBlocked) throw CommandFailure("not-blocked", "presentation not blocked")
        requireFocused(web, "not-focused-url")
        val before = nativeBlockedSnapshot(web)
        val identity = run.onMain {
            JSONObject()
                .put("viewHandle", presenter!!.state.value.viewHandle)
                .put("documentEpoch", presenter!!.state.value.documentEpoch)
                .put("url", web.url.orEmpty())
                .put("blocked", presenter!!.state.value.presentationBlocked)
                .put("container", (web.parent as? ViewGroup)?.javaClass?.simpleName ?: JSONObject.NULL)
        }
        val container = run.onMain { web.parent as? ViewGroup }
            ?: throw CommandFailure("no-browser-container", "no parent container")
        val handled = run.onMain { dispatchBlockedPairs(container) }
        val after = nativeBlockedSnapshot(web)
        val identityAfter = run.onMain {
            JSONObject()
                .put("viewHandle", presenter!!.state.value.viewHandle)
                .put("documentEpoch", presenter!!.state.value.documentEpoch)
                .put("url", web.url.orEmpty())
                .put("blocked", presenter!!.state.value.presentationBlocked)
        }
        if (currentView() !== web || identity.getString("url") != identityAfter.getString("url") ||
            identity.optInt("viewHandle") != identityAfter.optInt("viewHandle") ||
            identity.optLong("documentEpoch") != identityAfter.optLong("documentEpoch") ||
            !identityAfter.optBoolean("blocked")
        ) {
            throw CommandFailure("source-changed", "source changed")
        }
        val beforeHash = hashOptionalField(before, "inputValue")
        val afterHash = hashOptionalField(after, "inputValue")
        return JSONObject()
            .put("phase", "native-blocked-input")
            .put("blocked", true)
            .put("space", "container-local")
            .put("container", identity.optString("container"))
            .put("viewHandle", identity.optInt("viewHandle"))
            .put("documentEpoch", identity.optLong("documentEpoch"))
            .put("before", JSONObject()
                .put("keys", before.opt("keys") ?: JSONObject.NULL)
                .put("clicks", before.opt("clicks") ?: JSONObject.NULL)
                .put("applies", before.opt("applies") ?: JSONObject.NULL)
                .put("inputHash", beforeHash ?: JSONObject.NULL))
            .put("after", JSONObject()
                .put("keys", after.opt("keys") ?: JSONObject.NULL)
                .put("clicks", after.opt("clicks") ?: JSONObject.NULL)
                .put("applies", after.opt("applies") ?: JSONObject.NULL)
                .put("inputHash", afterHash ?: JSONObject.NULL))
            .put("delta", JSONObject()
                .put("keys", delta(before, after, "keys"))
                .put("clicks", delta(before, after, "clicks"))
                .put("applies", delta(before, after, "applies")))
            .put("inputChanged", beforeHash != afterHash)
            .put("handled", handled)
    }

    private fun delta(before: JSONObject, after: JSONObject, key: String): Any {
        if (!before.has(key) || !after.has(key) || before.isNull(key) || after.isNull(key)) return JSONObject.NULL
        return after.optInt(key) - before.optInt(key)
    }

    private fun hashOptionalField(body: JSONObject, key: String): Any {
        if (!body.has(key) || body.isNull(key)) return JSONObject.NULL
        return sha256(body.optString(key).toByteArray())
    }

    private fun nativeBlockedSnapshot(web: WebView): JSONObject {
        val raw = run.eval(web, BLOCKED_SNAPSHOT_SCRIPT)
            ?: throw CommandFailure("blocked-snapshot-null", "null")
        return runCatching { JSONObject(raw) }.getOrElse {
            throw CommandFailure("blocked-snapshot-not-json", "not json")
        }
    }

    private fun dispatchBlockedPairs(container: ViewGroup): JSONObject {
        val downTime = SystemClock.uptimeMillis()
        val touch = JSONArray()
        val generic = JSONArray()
        val key = JSONArray()
        try {
            fun sendTouch(action: Int, eventTime: Long): Boolean {
                val event = MotionEvent.obtain(downTime, eventTime, action, 6f, 6f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                return try {
                    container.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            fun sendHover(eventTime: Long): Boolean {
                val event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, 6f, 6f, 0)
                event.source = InputDevice.SOURCE_MOUSE
                return try {
                    container.dispatchGenericMotionEvent(event)
                } finally {
                    event.recycle()
                }
            }
            fun sendScroll(eventTime: Long): Boolean {
                val event = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_SCROLL, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                    arrayOf(MotionEvent.PointerCoords().apply {
                        x = 6f
                        y = 6f
                        pressure = 0f
                        size = 0f
                        setAxisValue(MotionEvent.AXIS_VSCROLL, 1f)
                    }),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                return try {
                    container.dispatchGenericMotionEvent(event)
                } finally {
                    event.recycle()
                }
            }
            touch.put(sendTouch(MotionEvent.ACTION_DOWN, downTime))
            touch.put(sendTouch(MotionEvent.ACTION_UP, downTime + 1))
            generic.put(sendHover(downTime + 2))
            generic.put(sendScroll(downTime + 3))
            val keyDown = KeyEvent(downTime, downTime + 4, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)
            val keyUp = KeyEvent(downTime, downTime + 5, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0)
            key.put(container.dispatchKeyEvent(keyDown))
            key.put(container.dispatchKeyEvent(keyUp))
        } catch (error: Throwable) {
            throw CommandFailure("blocked-dispatch-failed", error.javaClass.simpleName)
        }
        return JSONObject().put("touch", touch).put("generic", generic).put("key", key)
    }

    private fun uiTargetChat(label: String): JSONObject {
        if (label !in LocalProviderRegressionProtocol.CHAT_UI_LABELS) {
            throw CommandFailure("unknown-chat-label", label.take(60))
        }
        Accessibility.requireTree()
        val target = currentView()
        val targetBounds = target?.let { web ->
            run.onMain {
                val rect = Rect()
                web.getGlobalVisibleRect(rect)
                rect
            }
        }
        val root = try {
            accessibility().rootInActiveWindow
        } catch (error: Throwable) {
            throw CommandFailure("accessibility-unavailable", error.javaClass.simpleName)
        } ?: throw CommandFailure("no-active-window", "no window")
        val tracked = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        fun track(node: AccessibilityNodeInfo) { tracked.add(node) }
        return try {
            track(root)
            if (root.packageName?.toString() != context.packageName) {
                throw CommandFailure("active-window-not-own-package", root.packageName?.toString().orEmpty())
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                // Chromium exposes an empty <input aria-label> name as hintText (text stays empty or
                // the placeholder). Only an editable node may match by hint, never a plain label.
                val hintMatch = node.isEditable && node.hintText?.toString() == label
                // Compose exposes Modifier.clickable(onClickLabel=...) as an accessibility action,
                // not as text or a content description; the edge handle is found this way.
                val actionMatch = node.actionList.any { action -> action.label?.toString() == label }
                // The OpenBrowserUrlDialog has exactly one editable field; the fixed host label
                // "Address" selects it without reading or exporting what has been typed.
                val addressField = label == "Address" && node.isEditable
                if ((node.text?.toString() == label || node.contentDescription?.toString() == label ||
                        hintMatch || actionMatch || addressField) &&
                    node.isVisibleToUser && node.packageName?.toString() == context.packageName
                ) {
                    fun targetJson(current: AccessibilityNodeInfo, bounds: Rect, editable: Boolean): JSONObject {
                        val now = System.currentTimeMillis()
                        return JSONObject()
                            .put("phase", "ui-target-chat")
                            .put("label", label)
                            .put("left", bounds.left).put("top", bounds.top)
                            .put("right", bounds.right).put("bottom", bounds.bottom)
                            .put("windowId", current.windowId)
                            .put("ownerPackage", context.packageName)
                            .put("enabled", true)
                            .put("editable", editable)
                            .put("observedAtMs", now)
                            .put("ageMs", 0)
                            .put("fresh", true)
                            .put("runId", runId)
                            .put("source", "native-ui")
                    }
                    fun eligible(current: AccessibilityNodeInfo): JSONObject? {
                        if (!current.isEnabled || !current.isVisibleToUser) return null
                        val bounds = Rect()
                        current.getBoundsInScreen(bounds)
                        if (bounds.width() <= 0 || bounds.height() <= 0) return null
                        // The edge tab and its dialog are App-owned overlays above the page window:
                        // the handle sits on the screen edge the floating page leaves free, and the
                        // dialog is modal. Only stock chat labels keep the page-overlap exclusion.
                        if (!NATIVE_OVERLAY_LABELS.contains(label) && targetBounds != null &&
                            Rect.intersects(bounds, targetBounds)
                        ) {
                            return null
                        }
                        return targetJson(current, bounds, current.isEditable)
                    }
                    // An editable input is its own valid touch target; never fall through to the
                    // WebView root just because the input node itself is not marked clickable.
                    if (node.isEditable) {
                        eligible(node)?.let { return it }
                    }
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        val className = current.className?.toString().orEmpty()
                        if (current.isClickable && !className.contains("WebView", ignoreCase = true)) {
                            eligible(current)?.let { return it }
                            break
                        }
                        val parent = current.parent ?: break
                        track(parent)
                        current = parent
                    }
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        track(child)
                        queue.add(child)
                    }
                }
            }
            throw CommandFailure("missing-chat-target", label)
        } finally {
            tracked.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
        }
    }

    private val SITE_STATE_WRITE_SCRIPT = """
        (function(){
          var value = __VALUE__;
          document.cookie = 'oc_product_site=' + value + '; path=/; max-age=3600; secure; samesite=lax';
          localStorage.setItem('oc_product_site', value);
          var parts = String(document.cookie || '').split(';');
          var pair = null;
          for (var i = 0; i < parts.length; i++) {
            var item = parts[i].trim();
            if (item.indexOf('oc_product_site=') === 0) pair = item;
          }
          return JSON.stringify({cookiePair: pair, storageValue: localStorage.getItem('oc_product_site')});
        })()
    """

    private val SITE_STATE_READ_SCRIPT = """
        (function(){
          var parts = String(document.cookie || '').split(';');
          var pair = null;
          for (var i = 0; i < parts.length; i++) {
            var item = parts[i].trim();
            if (item.indexOf('oc_product_site=') === 0) pair = item;
          }
          return JSON.stringify({cookiePair: pair, storageValue: localStorage.getItem('oc_product_site')});
        })()
    """

    private val SITE_STATE_CLEAR_SCRIPT = """
        (function(){
          document.cookie = 'oc_product_site=; path=/; max-age=0; secure; samesite=lax';
          localStorage.removeItem('oc_product_site');
          var parts = String(document.cookie || '').split(';');
          var present = false;
          for (var i = 0; i < parts.length; i++) {
            if (parts[i].trim().indexOf('oc_product_site=') === 0) present = true;
          }
          return JSON.stringify({cookiePresent: present, storagePresent: localStorage.getItem('oc_product_site') !== null});
        })()
    """

    private val BLOCKED_SNAPSHOT_SCRIPT = """
        (function(){
          var report = null;
          try {
            var raw = window.__report ? window.__report() : null;
            if (typeof raw === 'string') {
              var once = JSON.parse(raw);
              report = typeof once === 'string' ? JSON.parse(once) : once;
            } else if (raw && typeof raw === 'object') report = raw;
          } catch (e) {}
          var note = document.querySelector('[aria-label=note]');
          return JSON.stringify({
            keys: window.__keys === undefined ? null : window.__keys,
            clicks: report && typeof report.clicks === 'number' ? report.clicks : null,
            applies: report && typeof report.applies === 'number' ? report.applies : null,
            inputValue: note && typeof note.value === 'string' ? note.value : null
          });
        })()
    """


    private fun selectTab(tabId: String): JSONObject {
        val state = presenter?.state?.value ?: throw CommandFailure("no-presenter", "no presenter")
        if (state.tabs.none { it.id == tabId }) {
            throw CommandFailure("unknown-tab", tabId.take(80))
        }
        // Native core/View work is main-thread only; the command loop runs on instrumentation.
        val selected = run.onMain { presenter!!.select(tabId) }
        return JSONObject().put("phase", "select").put("selected", selected).put("tabId", tabId)
    }

    private fun tapLabel(label: String): JSONObject {
        // Accessibility labels are read from the tree and the fixture taps are native pointer input;
        // while the explicit a11y-off window is active this command refuses instead of guessing.
        Accessibility.requireTree()
        if (label in LocalProviderRegressionProtocol.UI_LABELS) {
            // UI labels are host-tapped via ui-target. A native tap here would look like a producer
            // grant; keep it an explicit failure instead.
            return JSONObject().put("phase", "tap-label").put("sent", false)
                .put("label", label).put("error", "ui-label-host-tap-only")
        }
        if (label !in LocalProviderRegressionProtocol.FIXTURE_TAP_LABELS) {
            return JSONObject().put("phase", "tap-label").put("sent", false)
                .put("label", label).put("error", "unsupported-fixture-label")
        }
        val web = currentView() ?: return JSONObject().put("phase", "tap-label").put("sent", false).put("error", "no-view")
        val url = run.onMain { web.url.orEmpty() }
        if (label in setOf("note", "Apply") && url != OpenChamberProviderFixtures.FOCUSED_URL) {
            return JSONObject().put("phase", "tap-label").put("sent", false)
                .put("label", label).put("error", "not-focused-url")
        }
        if (label in setOf("login-user", "Sign in") && url != OpenChamberProviderFixtures.LOGIN_URL) {
            return JSONObject().put("phase", "tap-label").put("sent", false)
                .put("label", label).put("error", "not-login-url")
        }
        val before = run.onMain {
            val loc = IntArray(2)
            web.getLocationOnScreen(loc)
            JSONObject().put("x", loc[0]).put("y", loc[1]).put("width", web.width).put("height", web.height)
        }
        val raw = run.eval(web, "(function(){${tapLabelAsset()};return JSON.stringify(tapLabelPoint(${JSONObject.quote(label)}))})()")
            ?: return JSONObject().put("phase", "tap-label").put("sent", false).put("error", "tap-eval-null")
        val decoded = runCatching { JSONObject(raw) }.getOrNull()
            ?: return JSONObject().put("phase", "tap-label").put("sent", false).put("error", "tap-eval-not-json")
        val after = run.onMain {
            val loc = IntArray(2)
            web.getLocationOnScreen(loc)
            JSONObject().put("x", loc[0]).put("y", loc[1]).put("width", web.width).put("height", web.height)
        }
        val miss = JSONObject()
            .put("phase", "tap-label").put("label", label).put("sent", false)
            .put("beforeFrame", before).put("afterFrame", after)
            .put("viewHandle", presenter?.state?.value?.viewHandle ?: JSONObject.NULL)
            .put("documentEpoch", presenter?.state?.value?.documentEpoch ?: JSONObject.NULL)
            .put("decoded", decoded)
        if (before.toString() != after.toString()) return miss.put("error", "native-frame-changed")
        if (decoded.optString("error") == "no-label") return miss.put("error", "no-label")
        if (!decoded.optBoolean("pointVisible") || !decoded.optBoolean("hit")) {
            return miss.put("error", "label-not-in-viewport")
        }
        if (!decoded.has("visualWidth") || !decoded.has("offsetLeft") || !decoded.has("offsetTop")) {
            return miss.put("error", "label-geometry-missing")
        }
        val visualWidth = decoded.optDouble("visualWidth", Double.NaN)
        val scale = before.getInt("width").toDouble() / visualWidth
        if (!scale.isFinite() || scale <= 0.0) return miss.put("error", "bad-scale")
        val sx = before.getInt("x") + (decoded.optDouble("x", Double.NaN) - decoded.optDouble("offsetLeft", Double.NaN)) * scale
        val sy = before.getInt("y") + (decoded.optDouble("y", Double.NaN) - decoded.optDouble("offsetTop", Double.NaN)) * scale
        if (!sx.isFinite() || !sy.isFinite()) return miss.put("error", "bad-screen-point")
        val metrics = context.resources.displayMetrics
        if (sx < 0 || sy < 0 || sx >= metrics.widthPixels || sy >= metrics.heightPixels) {
            return miss.put("error", "screen-point-outside")
        }
        run.onMain { nativeTimeline.mark(NativeFixtureTimelineRing.Kind.BEFORE_DOWN) }
        fingerTap(sx.toFloat(), sy.toFloat())
        run.onMain { nativeTimeline.mark(NativeFixtureTimelineRing.Kind.AFTER_UP) }
        return JSONObject()
            .put("phase", "tap-label")
            .put("label", label)
            .put("sent", true)
            .put("hit", true)
            .put("pointVisible", true)
            .put("scale", scale)
            .put("sx", sx).put("sy", sy)
            .put("frame", before)
            .put("viewHandle", presenter?.state?.value?.viewHandle ?: JSONObject.NULL)
            .put("documentEpoch", presenter?.state?.value?.documentEpoch ?: JSONObject.NULL)
    }

    private fun finger(spec: String): JSONObject {
        val values = spec.split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size != 6 || values.any { !it.isFinite() }) throw CommandFailure("bad-finger", "bad finger")
        val (sx, sy, dx, dy) = values
        val hold = values[4].toLong()
        val steps = values[5].toInt()
        val downTime = SystemClock.uptimeMillis()
        var lastTime = downTime
        fun nextTime(): Long {
            lastTime = maxOf(SystemClock.uptimeMillis(), lastTime + 1)
            return lastTime
        }
        // The hover exception is only evidence with accessibility disabled in the app process at the
        // gesture itself; read it here, not from a cached pre-gesture value.
        val a11yOff = Accessibility.isOff()
        val a11yEnabledDuringGesture = if (a11yOff) Accessibility.enabled(context) else null
        send(MotionEvent.ACTION_DOWN, sx, sy, downTime, downTime)
        if (hold > 0) {
            SystemClock.sleep(hold)
            send(MotionEvent.ACTION_MOVE, sx, sy, nextTime(), downTime)
        }
        for (step in 1..steps) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, sx + dx * step / steps, sy + dy * step / steps, nextTime(), downTime)
        }
        send(MotionEvent.ACTION_UP, sx + dx, sy + dy, nextTime(), downTime)
        val body = JSONObject().put("phase", "finger").put("sent", true)
            .put("downTime", downTime).put("lastEventTime", lastTime)
        if (a11yEnabledDuringGesture != null) {
            body.put("a11yMode", "off").put("a11yEnabledDuringGesture", a11yEnabledDuringGesture)
        }
        return body
    }

    private fun twoFingerScroll(spec: String): JSONObject {
        val values = spec.split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size != 3 || values.any { !it.isFinite() } || values[2] == 0f) {
            throw CommandFailure("bad-scroll", "bad scroll")
        }
        val (sx, sy, dy) = values
        val downTime = SystemClock.uptimeMillis()
        var lastTime = downTime
        fun nextTime(): Long {
            lastTime = maxOf(SystemClock.uptimeMillis(), lastTime + 1)
            return lastTime
        }
        fun props() = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        fun coords(y: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { x = sx - 60f; this.y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = sx + 60f; this.y = y; pressure = 1f; size = 1f },
        )
        fun sendMulti(action: Int, count: Int, y: Float) {
            val time = nextTime()
            val event = MotionEvent.obtain(downTime, time, action, count, props(), coords(y), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        sendMulti(MotionEvent.ACTION_DOWN, 1, sy)
        sendMulti(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, sy)
        for (step in 1..8) {
            SystemClock.sleep(16)
            sendMulti(MotionEvent.ACTION_MOVE, 2, sy + dy * step / 8)
        }
        sendMulti(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, sy + dy)
        sendMulti(MotionEvent.ACTION_UP, 1, sy + dy)
        return JSONObject().put("phase", "two-finger-scroll").put("sent", true)
            .put("downTime", downTime).put("lastEventTime", lastTime)
    }

    private fun pinch(spec: String): JSONObject {
        val values = spec.split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size != 2 || values.any { !it.isFinite() }) throw CommandFailure("bad-pinch", "bad pinch")
        val (px, py) = values
        val downTime = SystemClock.uptimeMillis()
        var lastTime = downTime
        fun nextTime(): Long {
            lastTime = maxOf(SystemClock.uptimeMillis(), lastTime + 1)
            return lastTime
        }
        fun props() = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        fun coords(spread: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { x = px - spread; this.y = py; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = px + spread; this.y = py; pressure = 1f; size = 1f },
        )
        fun sendMulti(action: Int, count: Int, spread: Float) {
            val time = nextTime()
            val event = MotionEvent.obtain(downTime, time, action, count, props(), coords(spread), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        sendMulti(MotionEvent.ACTION_DOWN, 1, 40f)
        sendMulti(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 40f)
        sendMulti(MotionEvent.ACTION_MOVE, 2, 140f)
        sendMulti(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 140f)
        sendMulti(MotionEvent.ACTION_UP, 1, 140f)
        return JSONObject().put("phase", "pinch").put("sent", true)
            .put("downTime", downTime).put("lastEventTime", lastTime)
    }

    /** One real tap: one down time for both events and a monotonic event time. */
    private fun fingerTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        send(MotionEvent.ACTION_UP, x, y, maxOf(SystemClock.uptimeMillis(), downTime + 1), downTime)
    }

    private fun send(action: Int, x: Float, y: Float, eventTime: Long, downTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun observerAsset(): String = observerScript ?: instrumentation.context.assets
        .open("local-regression-observer.js").bufferedReader().use { it.readText() }
        .also { observerScript = it }

    private fun tapLabelAsset(): String = tapLabelScript ?: instrumentation.context.assets
        .open("tap-label.js").bufferedReader().use { it.readText() }
        .also { tapLabelScript = it }

    private fun writeStatus(phase: String, detail: String? = null, target: LocalProviderRun.LocalTapTarget? = null) {
        val body = JSONObject()
            .put("runId", runId)
            .put("phase", phase)
            .put("updatedAtMs", System.currentTimeMillis())
        // Same provenance object as evidence.json: explicit Boolean, scope and exact expected cases.
        LocalProviderRegressionEvidence.applyCaseMetadata(body, requiredCases, caseScope, casePartial)
        sessionSetup?.let { body.put("sessionSetup", it) }
        selectedSessionId?.let { body.put("selectedSessionId", it) }
        detail?.let { body.put(if (phase == "failed") "failure" else "detail", it.take(300)) }
        if (target != null) {
            body.put("label", target.label)
            body.put("tapTarget", target.toJson())
            body.put("bounds", JSONObject()
                .put("left", target.bounds.left).put("top", target.bounds.top)
                .put("right", target.bounds.right).put("bottom", target.bounds.bottom))
        }
        run.writePrivate(statusFile, body.toString())
    }

    private fun writeResult(id: String, ok: Boolean, data: JSONObject?, code: String?, message: String?) {
        val body = LocalProviderRegressionProtocol.result(runId, id, ok, data, code, message)
        run.writePrivate(resultFile, body.toString())
    }

    private fun writeRejected(raw: String, reason: String) {
        // A stale/unknown command must not change evidence or the run phase.
        val body = runCatching { JSONObject(raw) }.getOrNull()
        val id = body?.optString("id").orEmpty().ifBlank { "invalid" }
        writeResult(id, false, null, reason, reason)
    }

    private fun writeReceipt(command: LocalProviderRegressionProtocol.Command, ok: Boolean, action: String) {
        val body = JSONObject()
            .put("id", command.id)
            .put("runId", runId)
            .put("kind", command.kind.name.lowercase())
            .put("ok", ok)
            .put("action", action)
            .put("sourceHandle", stateViewHandle() ?: JSONObject.NULL)
            .put("time", System.currentTimeMillis())
        run.writePrivate(File(receiptsDir, command.id + ".json"), body.toString())
        receiptCount += 1
    }

    private fun writeEvidence(reason: String? = null) {
        val body = LocalProviderRegressionEvidence.build(
            runId = runId,
            finished = finished && reason == null,
            failure = reason,
            expectedCases = requiredCases,
            caseScope = caseScope,
            casePartial = casePartial,
            caseSummary = caseSummary,
            toolCommands = toolCommands,
            deviceCommands = deviceCommands,
            receiptCount = receiptCount,
            selectedSessionId = selectedSessionId,
            sessions = createdSessions,
            decoratedPages = decoratedPages,
        )
        run.writePrivate(evidenceFile, body.toString())
    }

    private fun fail(reason: String): Nothing {
        closeTimeline()
        writeStatus("failed", reason.take(300))
        writeEvidence(reason.take(300))
        throw AssertionError(reason)
    }

    private fun persistTimeline() {
        val body = run.onMain { nativeTimeline.json() }
        run.writePrivate(File(dir, "timeline.json"), body)
    }

    private fun closeTimeline() {
        run.onMain {
            nativeTimeline.mark(NativeFixtureTimelineRing.Kind.CLOSE)
            nativeTimeline.close()
        }
        persistTimeline()
    }

    private class CommandFailure(val code: String, message: String) : Exception(message)

    private fun safeMessage(message: String?): String =
        (message ?: "no-message").replace(Regex("(?i)(token|password|cookie)=\\S+"), "$1=[redacted]").take(200)

    companion object {
        private val FIXTURE_URLS = setOf(
            OpenChamberProviderFixtures.ORIGIN,
            OpenChamberProviderFixtures.ORIGIN2,
            OpenChamberProviderFixtures.MOUSE_URL,
            OpenChamberProviderFixtures.RETIRE_URL,
            OpenChamberProviderFixtures.FOCUSED_URL,
            OpenChamberProviderFixtures.LOGIN_URL,
            OpenChamberProviderFixtures.LOGIN_SUBMIT_URL,
            OpenChamberProviderFixtures.WELCOME_URL,
        )

        private val OBSERVE_FIELDS = listOf(
            "url", "href", "ready", "timeOrigin", "ua", "uaMobileOrDesktop",
            "clientWidth", "clientHeight", "innerWidth", "innerHeight", "media1000",
            "scrollX", "scrollY", "sy", "maxScrollY", "visualViewport", "dpr",
            "loads", "posts", "noteValue", "retireCookie", "log", "logTail", "pointerId",
            "nextClicks", "boxClicks",
            "boxCaptured", "buttons", "clicks", "wheel", "releases", "report", "reportError",
        )

        private const val TIME_ORIGIN_SCRIPT = "String(performance.timeOrigin)"

        private const val COOKIE_READ_SCRIPT = """
            (function(){
              var parts = String(document.cookie || '').split(';');
              var pair = null;
              for (var i = 0; i < parts.length; i++) {
                var item = parts[i].trim();
                if (item.indexOf('oc_fixture_retire=') === 0) pair = item;
              }
              return JSON.stringify({present: pair !== null, pair: pair});
            })()
        """

        private const val COOKIE_WRITE_SCRIPT = """
            (function(){
              var value = __VALUE__;
              document.cookie = 'oc_fixture_retire=' + value + '; path=/; max-age=3600; secure; samesite=lax';
              var parts = String(document.cookie || '').split(';');
              var pair = null;
              for (var i = 0; i < parts.length; i++) {
                var item = parts[i].trim();
                if (item.indexOf('oc_fixture_retire=') === 0) pair = item;
              }
              var expected = 'oc_fixture_retire=' + value;
              return JSON.stringify({set: pair === expected, present: pair !== null});
            })()
        """

        private const val FIXTURE_TARGET_SCRIPT = """
            (function(){
              var id = __ID__;
              var px = __X__;
              var py = __Y__;
              var node = document.getElementById(id);
              if (!node) return JSON.stringify({error: 'no-node'});
              var rect = node.getBoundingClientRect();
              var v = window.visualViewport || {offsetLeft:0, offsetTop:0, width: window.innerWidth, height: window.innerHeight};
              var left = Math.max(rect.left, v.offsetLeft);
              var top = Math.max(rect.top, v.offsetTop);
              var right = Math.min(rect.right, v.offsetLeft + v.width);
              var bottom = Math.min(rect.bottom, v.offsetTop + v.height);
              var visible = right > left && bottom > top;
              var pointInViewport = px >= v.offsetLeft && py >= v.offsetTop &&
                px <= v.offsetLeft + v.width && py <= v.offsetTop + v.height;
              var hitNode = pointInViewport ? document.elementFromPoint(px, py) : null;
              var disabled = node.disabled === true;
              var hit = !disabled && !!hitNode && (hitNode === node || node.contains(hitNode));
              var report = null;
              try {
                var raw = window.__report ? window.__report() : null;
                if (typeof raw === 'string') {
                  var once = JSON.parse(raw);
                  report = typeof once === 'string' ? JSON.parse(once) : once;
                } else if (raw && typeof raw === 'object') {
                  report = raw;
                }
              } catch (e) { report = null; }
              var clicks = null;
              if (Array.isArray(window.__clicks)) clicks = window.__clicks.length;
              else if (report && typeof report.clicks === 'number') clicks = report.clicks;
              else if (typeof window.clicks === 'number') clicks = window.clicks;
              var nextClicks = null;
              var boxClicks = null;
              if (Array.isArray(window.__clicks)) {
                nextClicks = window.__clicks.filter(function (target) { return target === 'next'; }).length;
                boxClicks = window.__clicks.filter(function (target) { return target === 'box'; }).length;
              }
              function num(value) {
                return (typeof value === 'number' && value === value && value !== Infinity && value !== -Infinity) ? value : null;
              }
              return JSON.stringify({
                url: String(location.href),
                timeOrigin: num(performance && performance.timeOrigin),
                box: {left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom},
                visibleRect: {left: left, top: top, right: right, bottom: bottom},
                visible: visible,
                pointInViewport: pointInViewport,
                hit: hit,
                disabled: node.disabled === true,
                connected: node.isConnected !== false,
                page: {
                  timeOrigin: num(performance && performance.timeOrigin),
                  ua: (navigator.userAgent || '').indexOf('Mobile') >= 0 ? 'mobile' : 'desktop',
                  clientWidth: document.documentElement ? num(document.documentElement.clientWidth) : null,
                  visualViewport: v ? {width:num(v.width),height:num(v.height),offsetLeft:num(v.offsetLeft),offsetTop:num(v.offsetTop),scale:num(v.scale)} : null,
                  clicks: clicks,
                  releases: report ? num(report.releases) : null,
                  buttons: report ? num(report.buttons) : null,
                  wheel: report ? num(report.wheel) : null,
                  boxCaptured: report && typeof report.boxCaptured === 'boolean' ? report.boxCaptured : null,
                  pointerId: report ? num(report.pointerId) : null,
                  log: report && Array.isArray(report.log) ? report.log.slice(-40) : null,
                  logTail: report && Array.isArray(report.logTail) ? report.logTail.slice(-40) : null,
                  nextClicks: nextClicks,
                  boxClicks: boxClicks,
                  loads: num(window.__loads),
                  posts: num(window.__posts),
                  noteValue: (function(){ var note = document.getElementById('note'); return note && typeof note.value === 'string' ? note.value : null; })()
                }
              });
            })()
        """

        private const val PAGE_RECT_SCRIPT = """
            (function(){
              if (typeof window.__geometry === 'function') return JSON.stringify(window.__geometry());
              var v = window.visualViewport;
              return JSON.stringify({
                innerWidth: innerWidth,
                innerHeight: innerHeight,
                clientWidth: document.documentElement.clientWidth,
                clientHeight: document.documentElement.clientHeight,
                media1000: matchMedia('(min-width:1000px)').matches,
                visualViewport: v ? {width:v.width,height:v.height,offsetLeft:v.offsetLeft,offsetTop:v.offsetTop,scale:v.scale} : null,
                devicePixelRatio: devicePixelRatio,
                scrollX: scrollX,
                scrollY: scrollY
              });
            })()
        """

        private const val FIXTURE_STATE_SCRIPT = """
            (function(){
              var loginUrl = 'https://example.com/openchamber-provider-login';
              var focusedUrl = 'https://example.com/openchamber-provider-focused';
              var loginPage = String(location.href) === loginUrl;
              var focusedPage = String(location.href) === focusedUrl;
              var n = loginPage ? document.querySelector('[aria-label=login-user]') : null;
              var note = focusedPage ? document.querySelector('[aria-label=note]') : null;
              var a = document.activeElement;
              var posts = (typeof window.__posts === 'number' && isFinite(window.__posts)) ? window.__posts : null;
              if (posts === null) {
                try {
                  var s = sessionStorage.getItem('posts');
                  if (s !== null && /^-?\d+$/.test(String(s))) posts = Number(s);
                } catch (e) {}
              }
              var loads = (typeof window.__loads === 'number' && isFinite(window.__loads)) ? window.__loads : null;
              var clicks = null;
              if (Array.isArray(window.__clicks)) clicks = window.__clicks.length;
              else if (typeof window.clicks === 'number' && isFinite(window.clicks)) clicks = window.clicks;
              var submits = (typeof window.__submits === 'number' && isFinite(window.__submits)) ? window.__submits : null;
              var timeOrigin = null;
              try {
                if (typeof performance !== 'undefined' && typeof performance.timeOrigin === 'number' &&
                    isFinite(performance.timeOrigin)) timeOrigin = performance.timeOrigin;
              } catch (e) {}
              return JSON.stringify({
                url: String(location.href),
                ready: String(document.readyState),
                documentHasFocus: typeof document.hasFocus === 'function' ? document.hasFocus() : null,
                inputValue: n ? String(n.value) : null,
                inputEnabled: n ? n.disabled !== true : null,
                inputReadOnly: n ? n.readOnly === true : null,
                inputTag: n ? n.tagName : null,
                noteValue: note ? String(note.value) : null,
                noteFocused: !!(note && a === note),
                noteEnabled: note ? note.disabled !== true : null,
                noteReadOnly: note ? note.readOnly === true : null,
                noteTag: note ? note.tagName : null,
                noteSelectionStart: (note && a === note && typeof note.selectionStart === 'number') ? note.selectionStart : null,
                noteSelectionEnd: (note && a === note && typeof note.selectionEnd === 'number') ? note.selectionEnd : null,
                formPresent: !!document.querySelector('form'),
                posts: posts,
                loads: loads,
                clicks: clicks,
                submits: submits,
                inputFocused: !!(n && a === n),
                activeTag: a && a.tagName ? String(a.tagName) : null,
                selectionStart: (n && a === n && typeof n.selectionStart === 'number') ? n.selectionStart : null,
                selectionEnd: (n && a === n && typeof n.selectionEnd === 'number') ? n.selectionEnd : null,
                timeOrigin: timeOrigin
              });
            })()
        """

        private const val FIXTURE_FIELD_SCRIPT = """
            (function(){
              var field = __FIELD__;
              var node = field === 'Sign in'
                ? document.querySelector('button[aria-label="Sign in"]')
                : field === 'Apply'
                  ? document.querySelector('button[aria-label="Apply"]')
                  : field === 'note'
                    ? document.querySelector('input[aria-label="note"]')
                    : document.querySelector('input[aria-label="login-user"]');
              return JSON.stringify({
                field: field,
                present: !!node,
                tag: node ? node.tagName : null,
                type: node && node.type ? node.type : null,
                value: node && typeof node.value === 'string' ? node.value : null,
                valueLength: node && typeof node.value === 'string' ? node.value.length : null
              });
            })()
        """
    }
}

/**
 * Pure guards for the production notification/deep-link session setup. The actual producer uses
 * these same functions before starting the intent and before accepting Main-chat readiness.
 */
/** App-owned Compose menu/dialog labels that sit above the floating browser page. */
private val NATIVE_OVERLAY_LABELS = setOf("AndChamber menu", "Open web page", "Address", "Open", "Cancel")

internal object LocalProviderConsoleSetup {
    const val SESSION_DIRECTORY = "/root"
    const val FOCUSED_URL = OpenChamberProviderFixtures.FOCUSED_URL
    private const val MAX_SESSION_ID = 200

    fun validSessionId(id: String): Boolean =
        id.length in 5..MAX_SESSION_ID && id.startsWith("ses_") &&
            id.all { !it.isWhitespace() && !Character.isISOControl(it) }

    fun validLink(sessionId: String, directory: String?): Boolean =
        validSessionId(sessionId) && directory == SESSION_DIRECTORY

    /**
     * Returns the decoded `session` query only when the URL has the exact local base origin, the
     * production root path, one unambiguous session parameter and that value equals the API-created
     * session. A config/runtime session number in a different origin or query is never accepted.
     */
    fun consoleSession(url: String, base: String, expectedSession: String): String? {
        if (!validSessionId(expectedSession)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val baseUri = runCatching { URI(base) }.getOrNull() ?: return null
        if (uri.scheme != "http" || baseUri.scheme != "http") return null
        if (uri.host != "127.0.0.1" || baseUri.host != "127.0.0.1") return null
        if (effectivePort(uri) != 3000 || effectivePort(baseUri) != 3000) return null
        if (uri.path != "/" && !uri.path.isNullOrEmpty()) return null
        val rawQuery = uri.rawQuery ?: return null
        var session: String? = null
        for (part in rawQuery.split('&')) {
            val index = part.indexOf('=')
            val key = if (index < 0) part else part.substring(0, index)
            if (key != "session") continue
            if (session != null) return null
            val raw = if (index < 0) "" else part.substring(index + 1)
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
            if (decoded != expectedSession) return null
            session = decoded
        }
        return session
    }

    fun readyContract(
        activityResumed: Boolean,
        documentReady: String?,
        presenterEligible: Boolean,
        presenterConnected: Boolean,
        targetViewSeparate: Boolean,
        frameStable: Boolean,
        consoleQuerySession: String?,
        expectedSession: String,
    ): Boolean =
        activityResumed && documentReady == "complete" && presenterEligible &&
            presenterConnected && targetViewSeparate && frameStable &&
            consoleQuerySession != null && consoleQuerySession == expectedSession

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme == "https" -> 443
        else -> 80
    }
}
