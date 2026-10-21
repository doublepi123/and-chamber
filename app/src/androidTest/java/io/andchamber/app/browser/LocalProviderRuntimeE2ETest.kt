package io.andchamber.app.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.andchamber.app.MainActivity
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.RuntimeState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * True-local entry gate. Uses the default AndroidJUnitRunner and AndChamberApplication's production
 * Koin graph: no controller/server/authenticator/launcher override and no loadKoinModules. Runtime
 * install/start go through RuntimeService exactly as Home does; the chat page is the real guest
 * server. The only fixture is the target browser page's HTTPS response, attached after a real
 * `browser.open`, and the only human grant is a host `adb shell input tap` on "Allow agent".
 *
 * The test deliberately FAILS (never skips) when the APK has no provider artifact, the guest is not
 * x86_64, the installed marker/package/hash does not match, health/auth/tool transport fails, or the
 * host tap is not observed. Build without -Pandchamber.providerRunner=true.
 */
@RunWith(AndroidJUnit4::class)
class LocalProviderRuntimeE2ETest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val runId: String = InstrumentationRegistry.getArguments().getString("runId")?.trim().orEmpty()

    private fun requireRunId(): String {
        if (!Regex("^[A-Za-z0-9._:-]{8,80}$").matches(runId)) {
            fail("run-id-missing-or-invalid: pass -e runId <host-run-id>")
        }
        return runId
    }

    @Test
    fun realLocalRuntimeEntry() {
        val run = LocalProviderRun(context, instrumentation, requireRunId())
        try {
            run.phase("device")
            requireX86(run)
            run.record("build", JSONObject()
                .put("sdk", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT)
                .put("abis", Build.SUPPORTED_ABIS.joinToString(",")))
            val manifest = run.runtimeManifest()
            run.record("runtimeManifest", JSONObject()
                .put("runtimeVersion", manifest.runtimeVersion)
                .put("alpineVersion", manifest.alpineVersion)
                .put("openChamberVersion", manifest.openChamberVersion)
                .put("openCodeVersion", manifest.openCodeVersion)
                .put("port", manifest.port))
            run.verifyProotProvenance()

            run.phase("apk-asset-pins")
            val provider = run.providerManifestFromApk()
            val assetFiles = run.verifyAssetPayload(provider)
            run.record("apkProvider", JSONObject()
                .put("version", provider.version)
                .put("baseCommit", provider.baseCommit)
                .put("sourcePatchSha256", provider.sourcePatchSha256)
                .put("serverFiles", provider.server.size)
                .put("distFiles", provider.dist.size)
                .put("verifiedFiles", assetFiles))

            // A visible activity makes the foreground-service start legal, then the production
            // launcher decides from the real state.
            val initial = run.launchActivity()
            run.phase("runtime-install-start")
            ensureRunning(run)

            run.phase("installed-pins")
            val paths = RuntimePaths.under(context.filesDir)
            val inspection = run.verifyInstalledPayload(provider, paths)
            val metadata = inspection.metadata
            if (metadata.port != manifest.port) run.fail("metadata-port: ${metadata.port}")
            if (metadata.alpineVersion != manifest.alpineVersion) run.fail("metadata-alpine: ${metadata.alpineVersion}")
            if (metadata.openChamberVersion != manifest.openChamberVersion) run.fail("metadata-openchamber: ${metadata.openChamberVersion}")
            if (metadata.openCodeVersion != manifest.openCodeVersion) run.fail("metadata-opencode: ${metadata.openCodeVersion}")
            val firstLaunch = run.server().readyLaunchId() ?: run.fail("launch-id-missing")
            run.record("firstRuntimeLaunchId", firstLaunch)
            run.record("firstRunningState", stateSnapshot(run))

            run.phase("health-auth")
            val running = run.controller().state.value as? RuntimeState.Running
                ?: run.fail("not-running-at-health")
            val base = running.url
            run.record("localOrigin", "http://127.0.0.1:${running.port}")
            waitOpenCodeReady(run, base)
            run.health(base)
            val auth = run.authenticate(base)
            run.systemInfo(base, auth.header)
            run.openCodeVersion(base, auth.header, metadata.openCodeVersion)
            run.apiInfo(base, auth.header)

            // Do not restart MainActivity (startActivitySync waits for global idle and timed out on
            // the live Compose/WebView queue). The already-resumed production Activity stays; the
            // host taps its real "Open" button on the local-server card once.
            run.phase("local-chat")
            if (!run.isActivityResumed(initial)) {
                run.fail("initial-activity-not-resumed: ${run.activityIdentity(initial)}")
            }
            run.record("chatActivity", run.activityIdentity(initial))
            val presenter = run.presenter()
            val alreadyOpen = presenter.state.value.eligible && presenter.state.value.connected
            if (alreadyOpen) {
                run.phase("local-chat-already-open")
            } else {
                val chatTarget = run.awaitHumanTap(
                    phase = "await-chat-open",
                    label = "Open",
                    sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
                ) { presenter.state.value.eligible && presenter.state.value.connected }
                if (chatTarget == null) run.fail("chat-open-target-not-observed")
            }
            val attached = run.waitFor("chat-host", 120_000) {
                presenter.state.value.eligible && presenter.state.value.connected
            }
            if (!attached) run.fail("local-chat-host-not-attached: ${presenter.state.value}")
            run.phase("runtime-ready")

            val sessionId = run.createSession(base, auth.header)
            var endpoint: LocalAgentTool.Endpoint? = null
            val endpointFound = run.waitFor("agent-tool", 120_000) {
                endpoint = LocalAgentTool.discover()
                endpoint != null
            }
            if (!endpointFound) run.fail("agent-tool-endpoint-missing")
            run.record("agentToolPort", runCatching { java.net.URI(endpoint!!.url).port }.getOrDefault(-1))
            val agent = LocalAgentClient(run, endpoint!!, sessionId)

            run.countOnce("open")
            val opened = agent.call("browser.open", JSONObject().put("url", "https://example.com/"))
            if (!opened.ok) run.fail("browser-open-failed: http=${opened.status} ${opened.error}")
            val tabId = opened.data?.optString("tabId").orEmpty()
            if (tabId.isBlank()) run.fail("browser-open-tab-missing")
            run.record("openedTabId", tabId)

            val targetView = waitTargetPage(run, presenter, tabId)
            val allowTarget = run.awaitHumanTap(
                phase = "await-allow-tap",
                label = "Allow agent",
                sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
            ) { presenter.state.value.agentOn }
            if (allowTarget == null) run.fail("allow-target-not-observed")
            run.phase("allow-active")

            // Fixture preparation only: the host taps the production "Take over" button to return
            // the current tab to manual navigation, then the test loads the fixed fixture as a
            // manual document. This is not a new tool open or an action result.
            val takeoverTarget = run.awaitHumanTap(
                phase = "await-takeover-tap",
                label = "Take over",
                sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
            ) { !presenter.state.value.agentOn && presenter.state.value.mode.name == "MANUAL" }
            if (takeoverTarget == null) run.fail("takeover-target-not-observed")
            run.phase("takeover-active")

            attachTargetFixture(run, presenter, tabId, targetView)
            val fixtureAllowTarget = run.awaitHumanTap(
                phase = "await-allow-fixture-tap",
                label = "Allow agent",
                sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
            ) { presenter.state.value.agentOn }
            if (fixtureAllowTarget == null) run.fail("fixture-allow-target-not-observed")
            run.phase("fixture-allow-active")

            run.bump("snapshot")
            val firstSnapshot = agent.call("browser.snapshot")
            if (!firstSnapshot.ok || firstSnapshot.data == null || !firstSnapshot.data.optBoolean("drivable")) {
                run.fail("snapshot-not-drivable: ${firstSnapshot.error}")
            }
            val elements = firstSnapshot.data.optJSONArray("elements") ?: JSONArray()
            val note = findElement(elements, "note") ?: run.fail("snapshot-note-missing")
            val applyButton = findElement(elements, "Apply") ?: run.fail("snapshot-apply-missing")
            if (!firstSnapshot.data.optString("text").contains("framed:top")) {
                run.fail("fixture-not-top-level")
            }

            run.countOnce("type")
            val typed = agent.call(
                "browser.type",
                JSONObject().put("selector", note.optString("selector")).put("value", "local-provider-e2e"),
            )
            if (!typed.ok) run.fail("browser-type-failed: ${typed.error}")

            run.countOnce("click")
            val clicked = agent.call("browser.click", JSONObject().put("selector", applyButton.optString("selector")))
            if (!clicked.ok) run.fail("browser-click-failed: ${clicked.error}")

            run.countOnce("scroll")
            val scrolled = agent.call("browser.scroll", JSONObject().put("direction", "down"))
            if (!scrolled.ok) run.fail("browser-scroll-failed: ${scrolled.error}")

            run.countOnce("capture")
            val captured = agent.call("browser.capture", JSONObject().put("label", "local-provider-e2e"))
            if (!captured.ok) run.fail("browser-capture-failed: ${captured.error}")
            val captureRaw = captured.data?.optString("path").orEmpty()
            if (captureRaw.isBlank()) run.fail("capture-path-missing")
            if (!captureRaw.startsWith(".openchamber/screenshots/") &&
                !captureRaw.startsWith("/root/.openchamber/screenshots/")
            ) {
                run.fail("capture-path-outside-session: ${captureRaw.take(160)}")
            }
            val captureFile = run.capturePath(captureRaw) ?: run.fail("capture-file-missing: ${captureRaw.take(120)}")
            val captureSize = run.verifyPng(captureFile)
            run.record("capturePathKind", if (captureFile.absolutePath.startsWith(context.filesDir.absolutePath)) "runtime-home" else "unexpected")
            captured.data?.optJSONObject("png")?.let { claimed ->
                if (claimed.optInt("width") != captureSize.first || claimed.optInt("height") != captureSize.second) {
                    run.fail("capture-png-metrics-mismatch")
                }
            }
            captured.data?.optJSONObject("css")?.let { css ->
                val required = listOf("width", "height", "visualWidth", "visualHeight", "offsetLeft", "offsetTop", "dpr", "scale", "mode")
                if (required.any { !css.has(it) }) run.fail("capture-css-incomplete")
                run.record("captureCss", JSONObject().put("width", css.optInt("width"))
                    .put("height", css.optInt("height"))
                    .put("visualWidth", css.optDouble("visualWidth"))
                    .put("visualHeight", css.optDouble("visualHeight"))
                    .put("offsetLeft", css.optDouble("offsetLeft"))
                    .put("offsetTop", css.optDouble("offsetTop"))
                    .put("dpr", css.optDouble("dpr"))
                    .put("scale", css.optDouble("scale"))
                    .put("mode", css.optString("mode")))
            }
            captured.data?.opt("viewport")?.let { viewport ->
                run.record("captureViewport", viewport.toString().take(200))
            }

            run.bump("snapshot")
            val finalSnapshot = agent.call("browser.snapshot")
            if (!finalSnapshot.ok || finalSnapshot.data == null) run.fail("final-snapshot-failed: ${finalSnapshot.error}")
            val finalText = finalSnapshot.data.optString("text")
            // Snapshot refs only cover visible elements; scrolling legitimately removes the input.
            if (run.onMain { presenter.currentView() !== targetView } || presenter.state.value.selectedTabId != tabId) {
                run.fail("type-observation-source-changed")
            }
            val observedNote = run.eval(targetView, "document.getElementById('note')&&document.getElementById('note').value")
            if (run.onMain { presenter.currentView() !== targetView } || presenter.state.value.selectedTabId != tabId) {
                run.fail("type-observation-source-changed")
            }
            run.record("observedFixtureInput", observedNote ?: JSONObject.NULL)
            if (observedNote != "local-provider-e2e") run.fail("type-value-not-observed")
            if (lastNumber(finalText, "click-count:") != 1) run.fail("click-count-not-1")
            val scrollY = lastNumber(finalText, "scroll-y:") ?: run.fail("scroll-y-missing")
            if (scrollY <= 0) run.fail("scroll-not-observed: $scrollY")
            if (!finalSnapshot.data.optString("url").startsWith(OpenChamberProviderFixtures.ORIGIN)) {
                run.fail("target-url-not-fixture: ${finalSnapshot.data.optString("url").take(120)}")
            }
            run.record("actionText", JSONObject()
                .put("clickCount", lastNumber(finalText, "click-count:"))
                .put("scrollY", scrollY)
                .put("noteValue", observedNote))
            run.phase("actions-done")

            verifyStopRestart(run, base, auth, presenter, firstLaunch)
            run.finish()
        } catch (t: Throwable) {
            run.recordFailure(t, "realLocalRuntimeEntry")
            run.writeEvidence()
            throw t
        }
    }

    /**
     * Host force-stops the package and starts this method in a second instrumentation. It never
     * installs: an installed environment is required, and the production launch flow starts it.
     */
    @Test
    fun coldStartAfterForceStop() {
        val run = LocalProviderRun(context, instrumentation, requireRunId(), cold = true)
        try {
            run.phase("cold-device")
            requireX86(run)
            val manifest = run.runtimeManifest()
            val provider = run.providerManifestFromApk()
            run.verifyAssetPayload(provider)
            run.verifyProotProvenance()
            val paths = RuntimePaths.under(context.filesDir)
            if (run.installed(paths) == null) run.fail("cold-start-without-installed-runtime")
            run.launchActivity()
            val controller = run.controller()
            // The production launch flow starts a Stopped runtime; if it landed on Home before
            // detection settled, ask the same production controller only when nothing is queued.
            if (controller.state.value is RuntimeState.Stopped && !controller.busy.value) controller.start()
            run.phase("cold-starting")
            val running = run.waitFor("cold-running", 150_000) { controller.state.value is RuntimeState.Running }
            if (!running) run.fail("cold-start-timeout: ${run.stateName(controller.state.value)}")
            val inspection = run.verifyInstalledPayload(provider, paths)
            if (inspection.metadata.port != manifest.port) run.fail("cold-metadata-port: ${inspection.metadata.port}")
            val launch = run.server().readyLaunchId() ?: run.fail("cold-launch-id-missing")
            run.record("coldRuntimeLaunchId", launch)
            val runningState = controller.state.value as RuntimeState.Running
            val base = runningState.url
            waitOpenCodeReady(run, base)
            run.health(base)
            val auth = run.authenticate(base)
            run.openCodeVersion(base, auth.header, inspection.metadata.openCodeVersion)
            val presenter = run.presenter()
            val attached = run.waitFor("cold-chat-host", 120_000) {
                presenter.state.value.eligible && presenter.state.value.connected
            }
            if (!attached) run.fail("cold-chat-host-not-attached: ${presenter.state.value}")
            if (presenter.state.value.agentOn) run.fail("cold-start-auto-granted")
            run.record("coldRunningState", stateSnapshot(run))
            // The cold child must remain alive while the host runs the real PTY once. This is a
            // test-private handshake only; it does not grant or control the runtime.
            run.awaitHostPty()
            run.phase("cold-ready")
            run.finish()
        } catch (t: Throwable) {
            run.recordFailure(t, "coldStartAfterForceStop")
            run.writeEvidence()
            throw t
        }
    }

    private fun waitOpenCodeReady(run: LocalProviderRun, base: String) {
        val ready = run.waitFor("opencode-ready", 120_000) {
            runCatching {
                val reply = run.get("$base/health")
                val body = JSONObject(reply.body)
                reply.code == 200 && body.optString("status") == "ok" &&
                    body.has("isOpenCodeReady") && body.optBoolean("isOpenCodeReady")
            }.getOrDefault(false)
        }
        if (!ready) run.fail("opencode-not-ready")
    }

    private fun requireX86(run: LocalProviderRun) {        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi != "x86_64") run.fail("unsupported-primary-abi: $abi")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            run.fail("multi-profile-unsupported: profile isolation gate fails closed")
        }
    }

    private fun ensureRunning(run: LocalProviderRun) {
        val controller = run.controller()
        val paths = RuntimePaths.under(context.filesDir)
        val budget = StageBudget({ SystemClock.elapsedRealtime() })
        var action: String? = null
        var actionStoppedAt = 0L
        var last = ""
        while (!budget.overallTimedOut()) {
            val state = controller.state.value
            when (state) {
                is RuntimeState.Installing -> budget.enterInstalling()
                is RuntimeState.Starting -> budget.enterStarting()
                else -> Unit
            }
            val label = run.stateName(state)
            if (label != last) {
                run.phase("runtime-$label")
                last = label
            }
            when (state) {
                RuntimeState.Detecting -> SystemClock.sleep(250)
                RuntimeState.UnsupportedAbi -> run.fail("unsupported-abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                is RuntimeState.Failed -> {
                    recordStageFailure(run, budget, paths, "failed")
                    run.fail("runtime-failed: ${state.phase}:${state.reason}")
                }
                is RuntimeState.Running -> {
                    val inspection = run.installed(paths)
                    if (inspection == null) run.fail("running-without-installed-runtime")
                    if (!inspection.applied) {
                        if (action != null) run.fail("running-stock-runtime-after-$action")
                        val version = inspection.packageVersion
                        if (version != null && compareVersions(version, io.andchamber.app.runtime.install.OpenChamberProviderArtifact.PACKAGE_VERSION) > 0) {
                            run.fail("incompatible-installed-package-$version: refusing downgrade to 2.4.0")
                        }
                        action = "repair"
                        run.phase("runtime-repairing", "stock/legacy runtime needs the patched pair")
                        controller.repair()
                        SystemClock.sleep(500)
                    } else {
                        val ready = run.waitFor("launch-id", 30_000) { run.server().readyLaunchId() != null }
                        if (!ready) run.fail("running-without-launch-id")
                        return
                    }
                }
                is RuntimeState.Installing -> {
                    if (budget.installTimedOut()) {
                        recordStageFailure(run, budget, paths, "install")
                        run.fail("install-timeout")
                    }
                    SystemClock.sleep(500)
                }
                is RuntimeState.Starting -> {
                    if (budget.startTimedOut()) {
                        recordStageFailure(run, budget, paths, "starting")
                        run.fail("start-timeout")
                    }
                    SystemClock.sleep(500)
                }
                is RuntimeState.Stopping -> SystemClock.sleep(500)
                RuntimeState.NotInstalled -> {
                    if (action != null) run.fail("install-did-not-produce-runtime: $action")
                    if (controller.busy.value) {
                        // The UI launch flow or the service already queued an operation.
                        SystemClock.sleep(500)
                        continue
                    }
                    action = "install"
                    run.phase("runtime-installing", "no installed runtime")
                    controller.install()
                    SystemClock.sleep(500)
                }
                RuntimeState.Stopped -> {
                    if (action != null) {
                        if (actionStoppedAt == 0L) {
                            actionStoppedAt = SystemClock.elapsedRealtime()
                            // Report a possible post-action hang; never resend install/start/repair here.
                            run.phase("runtime-stopped-after-action", "action=$action; not resending")
                        }
                        SystemClock.sleep(500)
                        continue
                    }
                    if (controller.busy.value) {
                        // The UI launch flow already requested a start; do not send a second one.
                        SystemClock.sleep(500)
                        continue
                    }
                    val inspection = run.installed(paths)
                    if (inspection == null) {
                        action = "install"
                        run.phase("runtime-installing", "empty runtime")
                        controller.install()
                    } else if (inspection.applied) {
                        action = "start"
                        run.phase("runtime-starting", "installed patched runtime")
                        controller.start()
                    } else {
                        val version = inspection.packageVersion
                        if (version != null && compareVersions(version, io.andchamber.app.runtime.install.OpenChamberProviderArtifact.PACKAGE_VERSION) > 0) {
                            run.fail("incompatible-installed-package-$version: refusing downgrade to 2.4.0")
                        }
                        action = "repair"
                        run.phase("runtime-repairing", "stock/legacy runtime needs the patched pair")
                        controller.repair()
                    }
                    SystemClock.sleep(500)
                }
            }
        }
        recordStageFailure(run, budget, paths, "overall")
        run.fail("runtime-timeout: ${run.stateName(controller.state.value)}; installMs=${budget.installElapsedMs()} startMs=${budget.startElapsedMs()} overallMs=${budget.overallElapsedMs()}")
    }

    private fun recordStageFailure(
        run: LocalProviderRun,
        budget: StageBudget,
        paths: RuntimePaths,
        stage: String,
    ) {
        run.record("stageFailure", JSONObject()
            .put("stage", stage)
            .put("state", run.stateName(run.controller().state.value))
            .put("installElapsedMs", budget.installElapsedMs())
            .put("startElapsedMs", budget.startElapsedMs())
            .put("overallElapsedMs", budget.overallElapsedMs()))
        run.installed(paths)?.let { inspection ->
            run.record("stageInstalledMetadata", JSONObject()
                .put("abi", inspection.metadata.abi)
                .put("runtimeVersion", inspection.metadata.runtimeVersion)
                .put("alpineVersion", inspection.metadata.alpineVersion)
                .put("openChamberVersion", inspection.metadata.openChamberVersion)
                .put("openCodeVersion", inspection.metadata.openCodeVersion)
                .put("nodeVersion", inspection.metadata.nodeVersion)
                .put("packageVersion", inspection.packageVersion)
                .put("providerApplied", inspection.applied))
        }
    }

    private fun waitTargetPage(
        run: LocalProviderRun,
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        tabId: String,
    ): WebView {
        var page: WebView? = null
        val appeared = run.waitFor("browser-view", 20_000) {
            page = run.onMain { presenter.currentView() as? WebView }
            page != null && presenter.state.value.tabs.any { it.id == tabId }
        }
        if (!appeared || page == null) run.fail("browser-page-view-missing")
        val ready = run.waitFor("target-page", 45_000) {
            val state = presenter.state.value
            state.pageReady && state.shown && state.eligible &&
                state.tabs.any { it.id == tabId && it.url.startsWith("https://example.com/") }
        }
        if (!ready) run.fail("target-page-not-ready: ${presenter.state.value}")
        return page!!
    }

    private fun attachTargetFixture(
        run: LocalProviderRun,
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        tabId: String,
        view: WebView,
    ): WebView {
        // The browser page WebView only: the existing androidTest fixture wraps its production
        // client and serves exact example.com URLs from memory. The chat page is not touched.
        run.onMain {
            OpenChamberProviderFixtures.attach(view, view.webViewClient)
            view.loadUrl(OpenChamberProviderFixtures.ORIGIN)
        }
        // Read the real document and its identities, not the registry tab.url. The registry only
        // updates its URL on a later provider open/snapshot; state pageReady at this point can still
        // carry the old metadata URL.
        run.waitFixtureDocument(presenter, view, tabId)
        return view
    }

    private fun verifyStopRestart(
        run: LocalProviderRun,
        base: String,
        auth: LocalProviderRun.WebSessionAuth,
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        firstLaunch: Long,
    ) {
        val controller = run.controller()
        val cookieBefore = run.recordedCookieHash(base, auth)
        val session = run.providerSession()
        run.phase("stop")
        controller.stop()
        val stopped = run.waitFor("stopped", 90_000) { controller.state.value is RuntimeState.Stopped }
        if (!stopped) run.fail("stop-timeout: ${run.stateName(controller.state.value)}")
        if (run.server().readyLaunchId() != null) run.fail("stopped-server-still-ready")
        if (session.currentToken() != null) run.fail("stopped-provider-token-still-issued")
        val cleared = run.waitFor("stop-clears-grant", 20_000) {
            !presenter.state.value.agentOn && !presenter.state.value.connected
        }
        if (!cleared) run.fail("stop-did-not-clear-grant: ${presenter.state.value}")
        val cookieAfterStop = run.recordedCookieHash(base, auth)
        if (cookieAfterStop != cookieBefore) run.fail("cookie-not-preserved-by-stop")

        run.phase("restart")
        controller.start()
        val running = run.waitFor("restarted", 150_000) { controller.state.value is RuntimeState.Running }
        if (!running) run.fail("restart-timeout: ${run.stateName(controller.state.value)}")
        val secondLaunch = run.server().readyLaunchId() ?: run.fail("restart-launch-id-missing")
        if (secondLaunch == firstLaunch) run.fail("restart-reused-launch-id")
        run.record("secondRuntimeLaunchId", secondLaunch)
        if (presenter.state.value.agentOn) run.fail("restart-restored-agent-grant")
        val reconnected = run.waitFor("provider-reconnect", 90_000) { presenter.state.value.connected }
        if (!reconnected) run.fail("provider-not-connected-after-restart: transport-failure-not-auth")
        val cookieAfterRestart = run.recordedCookieHash(base, auth)
        if (cookieAfterRestart != cookieBefore) run.fail("cookie-not-preserved-across-restart")

        var endpoint: LocalAgentTool.Endpoint? = null
        val endpointFound = run.waitFor("agent-tool-after-restart", 90_000) {
            endpoint = LocalAgentTool.discover()
            endpoint != null
        }
        if (!endpointFound) run.fail("agent-tool-endpoint-missing-after-restart")
        val sessionId = run.createSession(base, auth.header)
        val agent = LocalAgentClient(run, endpoint!!, sessionId)
        run.bump("unauthorizedSnapshot")
        val denied = agent.call("browser.snapshot")
        if (denied.ok) run.fail("restart-auto-granted: old token or browser read succeeded")
        if (denied.error.contains("AUTHORIZATION_REQUIRED", ignoreCase = true)) {
            run.record("restartDenial", "AUTHORIZATION_REQUIRED")
        } else {
            run.fail("restart-denial-not-auth: ${denied.error.take(200)}")
        }
    }

    private fun stateSnapshot(run: LocalProviderRun): JSONObject {
        val state = run.controller().state.value
        val info: io.andchamber.app.runtime.RuntimeInfo? = run.controller().info.value
        return JSONObject()
            .put("state", run.stateName(state))
            .put("launchId", run.server().readyLaunchId())
            .put("info", info?.let {
                JSONObject()
                    .put("abi", it.abi)
                    .put("alpineVersion", it.alpineVersion)
                    .put("openChamberVersion", it.openChamberVersion)
                    .put("openCodeVersion", it.openCodeVersion)
                    .put("nodeVersion", it.nodeVersion)
            })
    }

    private fun findElement(elements: JSONArray, name: String): JSONObject? {
        for (index in 0 until elements.length()) {
            val item = elements.optJSONObject(index) ?: continue
            if (item.optString("name") == name) return item
        }
        return null
    }

    private fun lastNumber(text: String, prefix: String): Int? =
        Regex("${Regex.escape(prefix)}(\\d+)").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
