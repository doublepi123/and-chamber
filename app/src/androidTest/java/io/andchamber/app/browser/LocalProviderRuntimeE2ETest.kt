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
import io.andchamber.app.runtime.process.BrowserPluginAssets
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Non-auth cookie used only to prove the console profile's cookie store survived a stop. */
private const val SENTINEL_COOKIE = "ac_sentinel"

/**
 * True-local entry gate. Uses the default AndroidJUnitRunner and AndChamberApplication's production
 * Koin graph: no controller/server/authenticator/launcher override and no loadKoinModules. Runtime
 * install/start go through RuntimeService exactly as Home does; the chat page is the real guest
 * server. The only fixture is the target browser page's HTTPS response, attached after a real
 * `browser.open`. An explicit open gets the ready default grant through the presenter's own allow
 * verification; the remaining real human UI taps are "Take over" (revocation) and "Resume agent"
 * (the explicit handback after it). The fixture page is then brought by the production manual
 * navigation entry (`navigateManual`), whose own ready default grant is observed from native diag.
 *
 * The test deliberately FAILS (never skips) when the guest is not x86_64, the installed
 * @openchamber/web is not the stock npm version from the runtime manifest, the guest plugin files
 * differ from the APK assets, the launch endpoint/settings/config overlay is missing, health/auth/
 * tool transport fails, or the host tap is not observed.
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

            // A visible activity makes the foreground-service start legal, then the production
            // launcher decides from the real state.
            val initial = run.launchActivity()
            run.phase("runtime-install-start")
            ensureRunning(run)

            run.phase("installed-stock")
            val paths = RuntimePaths.under(context.filesDir)
            val inspection = run.verifyStockOpenChamber(manifest, paths)
            val metadata = inspection.metadata
            if (metadata.port != manifest.port) run.fail("metadata-port: ${metadata.port}")
            if (metadata.alpineVersion != manifest.alpineVersion) run.fail("metadata-alpine: ${metadata.alpineVersion}")
            // The in-app update may have moved past the bundled pin; never below it.
            if (io.andchamber.app.runtime.install.OpenChamberInstaller.compareVersions(metadata.openChamberVersion, manifest.openChamberVersion) < 0) {
                run.fail("metadata-openchamber-below-pin: ${metadata.openChamberVersion}")
            }
            if (io.andchamber.app.runtime.install.OpenChamberInstaller.compareVersions(metadata.openCodeVersion, manifest.openCodeVersion) < 0) {
                run.fail("metadata-opencode-below-pin: ${metadata.openCodeVersion}")
            }
            val firstLaunch = run.server().readyLaunchId() ?: run.fail("launch-id-missing")
            run.record("firstRuntimeLaunchId", firstLaunch)
            run.record("firstRunningState", stateSnapshot(run))

            run.phase("plugin-sync")
            run.record("pluginAssets", run.verifyPluginSync(paths))
            run.phase("plugin-endpoint")
            run.record("toolEndpoint", run.waitToolEndpoint(paths))
            run.phase("openchamber-settings")
            val agentWeb = run.verifyAgentWebSetting(paths)
            run.record("agentWebToolEnabled", agentWeb)
            run.phase("opencode-config")
            run.record("pluginConfig", run.verifyPluginConfig(paths))

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

            run.phase("plugin-loaded")
            run.record("pluginInventory", run.pluginInventory(base, auth.header))
            run.record("agentWebExpectation", JSONObject()
                .put("agentWebToolEnabled", agentWeb.optBoolean("value"))
                .put("openchamberWebToolExpected", !agentWeb.optBoolean("value"))
                .put("toolNamesObservable", false)
                .put("reason", "opencode-2.0.32-has-no-tool-list-route"))

            val sessionId = run.createSession(base, auth.header)
            // The agent's session must be the chat the person is looking at: the console reports its
            // session from the stock `?session=` URL and a different one revokes the grant. Use the
            // production notification link (no presenter or bridge call) to show it.
            showChatSession(run, initial, presenter, base, sessionId)
            val home = RuntimePaths.under(context.filesDir).home
            var endpoint: LocalBrowserTool.Endpoint? = null
            val endpointFound = run.waitFor("plugin-transport", 120_000) {
                endpoint = LocalBrowserTool.discover(home)
                endpoint != null
            }
            if (!endpointFound) run.fail("plugin-endpoint-missing")
            run.record("toolTransport", LocalBrowserToolClient.AgentReply.TRANSPORT)
            val agent = LocalBrowserToolClient(run, endpoint!!, sessionId)
            // Test-only observer: every production browser page gets the in-memory fixture client.
            // It serves only exact fixture URLs; the initial example.com page still loads normally.
            presenter.installPageDecorator { page ->
                val view = page.view as? WebView ?: return@installPageDecorator
                OpenChamberProviderFixtures.attach(view, view.webViewClient)
            }

            run.countOnce("open")
            val opened = agent.call("browser.open", JSONObject().put("url", "https://example.com/"))
            if (!opened.ok) run.fail("browser-open-failed: http=${opened.status} ${opened.error}")
            val tabId = opened.data?.optString("tabId").orEmpty()
            if (tabId.isBlank()) run.fail("browser-open-tab-missing")
            run.record("openedTabId", tabId)

            waitTargetPage(run, presenter, tabId)
            // Agent-first baseline: this explicit open performs one ready default grant. The
            // presenter still runs its real allow verification; the test only observes the grant.
            val defaultGrant = run.waitFor("open-default-grant", 30_000) {
                val state = presenter.state.value
                state.agentOn && state.selectedTabId == tabId && state.mode.name == "AGENT"
            }
            if (!defaultGrant) run.fail("open-default-grant-missing: ${presenter.state.value}")
            run.phase("initial-open-granted")

            // Revocation check: the host taps the production "Take over" button. The fixture page
            // is then brought by the production manual navigation entry, not a test load or Allow
            // tap; its ready default grant is observed from diag below.
            val takeoverTarget = run.awaitHumanTap(
                phase = "await-takeover-tap",
                label = "Take over",
                sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
            ) { !presenter.state.value.agentOn && presenter.state.value.mode.name == "MANUAL" }
            if (takeoverTarget == null) run.fail("takeover-target-not-observed")
            run.phase("takeover-active")

            // The person's real UI handback after an explicit takeover: tap Resume agent and
            // observe the real presenter grant, not a test-side state change.
            val resumeTarget = run.awaitHumanTap(
                phase = "await-resume-agent-tap",
                label = "Resume agent",
                sourceViewHandle = { presenter.state.value.viewHandle.takeIf { it != 0 } },
            ) { presenter.state.value.agentOn }
            if (resumeTarget == null) run.fail("resume-agent-target-not-observed")
            run.phase("resume-agent-active")

            run.phase("fixture-manual-navigate")
            val navigated = runBlocking { presenter.navigateManual(OpenChamberProviderFixtures.ORIGIN) }
            if (!navigated.ok) run.fail("fixture-manual-navigate-failed: ${navigated.errorCode}")
            val fixtureTabId = presenter.state.value.selectedTabId.orEmpty()
            if (fixtureTabId.isBlank()) run.fail("fixture-manual-navigate-tab-missing")
            val fixtureView = waitTargetPage(run, presenter, fixtureTabId)
            val fixtureGrant = run.waitFor("fixture-default-grant", 30_000) {
                val state = presenter.state.value
                state.agentOn && state.selectedTabId == fixtureTabId && state.mode.name == "AGENT"
            }
            if (!fixtureGrant) run.fail("fixture-default-grant-missing: ${presenter.state.value}")
            run.waitFixtureDocument(presenter, fixtureView, fixtureTabId)
            run.phase("fixture-granted")

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
            // Only fields the plugin schema allows (action, selector...); no free-form label.
            val captured = agent.call("browser.capture")
            if (!captured.ok) run.fail("browser-capture-failed: ${captured.error}")
            // Plugin contract: capture returns the PNG inline (data.base64, mime image/png); the plugin
            // turns it into a file part for the model. No guest file path is involved any more.
            val captureData = captured.data ?: run.fail("capture-data-missing")
            if (captureData.optString("mime") != "image/png") run.fail("capture-mime: ${captureData.optString("mime").take(40)}")
            val captureSize = run.verifyPngBase64(captureData.optString("base64"))
            if (captureData.optInt("width") != captureSize.first || captureData.optInt("height") != captureSize.second) {
                run.fail("capture-png-metrics-mismatch")
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
            if (run.onMain { presenter.currentView() !== fixtureView } || presenter.state.value.selectedTabId != fixtureTabId) {
                run.fail("type-observation-source-changed")
            }
            val observedNote = run.eval(fixtureView, "document.getElementById('note')&&document.getElementById('note').value")
            if (run.onMain { presenter.currentView() !== fixtureView } || presenter.state.value.selectedTabId != fixtureTabId) {
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
            val inspection = run.verifyStockOpenChamber(manifest, paths)
            if (inspection.metadata.port != manifest.port) run.fail("cold-metadata-port: ${inspection.metadata.port}")
            val launch = run.server().readyLaunchId() ?: run.fail("cold-launch-id-missing")
            run.record("coldRuntimeLaunchId", launch)
            val runningState = controller.state.value as RuntimeState.Running
            val base = runningState.url
            waitOpenCodeReady(run, base)
            run.health(base)
            val auth = run.authenticate(base)
            run.openCodeVersion(base, auth.header, inspection.metadata.openCodeVersion)
            run.record("pluginAssets", run.verifyPluginSync(paths))
            run.record("toolEndpoint", run.waitToolEndpoint(paths))
            run.record("agentWebToolEnabled", run.verifyAgentWebSetting(paths))
            run.record("pluginConfig", run.verifyPluginConfig(paths))
            run.record("pluginInventory", run.pluginInventory(base, auth.header))
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
                    if (run.installed(paths) == null) run.fail("running-without-installed-runtime")
                    val ready = run.waitFor("launch-id", 30_000) { run.server().readyLaunchId() != null }
                    if (!ready) run.fail("running-without-launch-id")
                    return
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
                    } else {
                        action = "start"
                        run.phase("runtime-starting", "installed stock runtime")
                        controller.start()
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
                .put("packageName", inspection.packageName)
                .put("packageVersion", inspection.packageVersion))
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

    private fun verifyStopRestart(
        run: LocalProviderRun,
        base: String,
        auth: LocalProviderRun.WebSessionAuth,
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        firstLaunch: Long,
    ) {
        val controller = run.controller()
        val cookieBefore = run.recordedCookieHash(base, auth, excludingCookie = SENTINEL_COOKIE)
        // A non-auth cookie proves the profile store itself survived the stop. A silent re-login
        // would restore the session cookie but could not recreate this value. storeCookies flushes.
        val sentinelValue = UUID.randomUUID().toString()
        runBlocking {
            auth.cookies.storeCookies(base, listOf("$SENTINEL_COOKIE=$sentinelValue; Path=/"))
        }
        val home = RuntimePaths.under(context.filesDir).home
        val endpointBefore = LocalBrowserTool.discover(home) ?: run.fail("plugin-endpoint-missing-before-stop")
        run.phase("stop")
        controller.stop()
        val stopped = run.waitFor("stopped", 90_000) { controller.state.value is RuntimeState.Stopped }
        if (!stopped) run.fail("stop-timeout: ${run.stateName(controller.state.value)}")
        if (run.server().readyLaunchId() != null) run.fail("stopped-server-still-ready")
        // Stop tears the tool server down and deletes the endpoint: the old token reaches nothing.
        if (BrowserPluginAssets.endpointHostFile(home).exists()) run.fail("stopped-endpoint-still-present")
        val stale = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", endpointBefore.port), 2_000) }
        }
        if (stale.isSuccess) run.fail("stopped-tool-port-still-listening")
        run.record("stopToolEndpoint", JSONObject().put("endpointDeleted", true).put("portClosed", true))
        val cleared = run.waitFor("stop-clears-grant", 20_000) {
            !presenter.state.value.agentOn && !presenter.state.value.connected
        }
        if (!cleared) run.fail("stop-did-not-clear-grant: ${presenter.state.value}")
        val cookieAfterStop = run.recordedCookieHash(base, auth, excludingCookie = SENTINEL_COOKIE)
        if (cookieAfterStop != cookieBefore) run.fail("cookie-not-preserved-by-stop")

        run.phase("restart")
        controller.start()
        val running = run.waitFor("restarted", 150_000) { controller.state.value is RuntimeState.Running }
        if (!running) run.fail("restart-timeout: ${run.stateName(controller.state.value)}")
        val secondLaunch = run.server().readyLaunchId() ?: run.fail("restart-launch-id-missing")
        if (secondLaunch == firstLaunch) run.fail("restart-reused-launch-id")
        run.record("secondRuntimeLaunchId", secondLaunch)
        if (presenter.state.value.agentOn) run.fail("restart-restored-agent-grant")
        // The console reloads after a restart and briefly drops the connection again; require the
        // re-established connection to hold for 3s so the denial below is about authorization.
        var connectedSince = 0L
        val reconnected = run.waitFor("provider-reconnect", 90_000) {
            val now = SystemClock.elapsedRealtime()
            if (!presenter.state.value.connected) {
                connectedSince = 0L
                false
            } else {
                if (connectedSince == 0L) connectedSince = now
                now - connectedSince >= 3_000L
            }
        }
        if (!reconnected) run.fail("provider-not-connected-after-restart: transport-failure-not-auth")
        // The login must survive the restart. OpenChamber may refresh its session cookie when the
        // console reloads, so require that the profile's current cookie still authenticates (no new
        // password login by the test) and record whether the value was rotated.
        val headerAfterRestart = runBlocking { auth.cookies.cookieHeader(base) } ?: run.fail("profile-cookie-missing-after-restart")
        run.systemInfo(base, headerAfterRestart)
        // The sentinel has to come back byte-for-byte: re-authentication would prove only that the
        // password still works, not that this profile's cookie store persisted. Only the boolean is
        // recorded, never the value.
        val sentinelPersisted = headerAfterRestart.split(';').any {
            it.trim() == "$SENTINEL_COOKIE=$sentinelValue"
        }
        // Remove the sentinel so later checks see only the console's own cookies.
        runCatching {
            runBlocking { auth.cookies.storeCookies(base, listOf("$SENTINEL_COOKIE=; Path=/; Max-Age=0")) }
        }
        val cookieAfterRestart = run.recordedCookieHash(base, auth, excludingCookie = SENTINEL_COOKIE)
        run.record("cookieAcrossRestart", JSONObject()
            .put("stillAuthenticates", true)
            .put("sentinelPersisted", sentinelPersisted)
            .put("rotated", cookieAfterRestart != cookieBefore))
        if (!sentinelPersisted) run.fail("cookie-sentinel-not-persisted")

        var endpoint: LocalBrowserTool.Endpoint? = null
        val endpointFound = run.waitFor("plugin-transport-after-restart", 90_000) {
            endpoint = LocalBrowserTool.discover(home)
            endpoint != null
        }
        if (!endpointFound) run.fail("plugin-endpoint-missing-after-restart")
        run.record("restartToolEndpoint", run.waitToolEndpoint(RuntimePaths.under(context.filesDir)))
        val sessionId = run.createSession(base, auth.header)
        val agent = LocalBrowserToolClient(run, endpoint!!, sessionId)
        // A restart is not an explicit open: no ready default grant may be minted by the reconnect.
        run.bump("unauthorizedSnapshot")
        val denied = agent.call("browser.snapshot")
        if (denied.ok) run.fail("restart-auto-granted: old token or browser read succeeded")
        if (denied.error.contains("AUTHORIZATION_REQUIRED", ignoreCase = true)) {
            run.record("restartDenial", "AUTHORIZATION_REQUIRED")
        } else {
            run.fail("restart-denial-not-auth: ${denied.error.take(200)}")
        }
    }

    private fun showChatSession(
        run: LocalProviderRun,
        activity: MainActivity,
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        base: String,
        sessionId: String,
    ) {
        run.phase("chat-session")
        val intent = android.content.Intent(context, MainActivity::class.java)
            .setAction(io.andchamber.app.notify.OpenChamberNotifier.ACTION_OPEN_SESSION)
            .putExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_SESSION_ID, sessionId)
            .putExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_DIRECTORY, "/root")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
        // Settled, not just seen: the link may reload the console document after the URL first
        // shows the session. Require the same console WebView, URL and complete document for 3s.
        var lastKey: String? = null
        var stableSince = 0L
        val shown = run.waitFor("chat-session-shown", 120_000) {
            val view = if (run.isActivityResumed(activity)) {
                run.onMain { consoleView(activity.window.decorView, presenter.currentView(), base, sessionId) }
            } else {
                null
            }
            val state = presenter.state.value
            val ready = view != null && state.eligible && state.connected &&
                run.eval(view, "document.readyState")?.trim('"') == "complete"
            val key = if (ready) "${System.identityHashCode(view)}|${run.onMain { view!!.url }}|${state.viewGeneration}" else null
            val now = SystemClock.elapsedRealtime()
            if (key == null || key != lastKey) {
                lastKey = key
                stableSince = now
                false
            } else {
                now - stableSince >= 3_000L
            }
        }
        if (!shown) run.fail("chat-session-not-shown")
        run.record("chatSessionShown", true)
    }

    private fun consoleView(root: android.view.View?, excluded: android.view.View?, base: String, sessionId: String): WebView? {
        if (root == null) return null
        if (root is WebView && root !== excluded &&
            LocalProviderConsoleSetup.consoleSession(root.url.orEmpty(), base, sessionId) != null
        ) {
            return root
        }
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                consoleView(root.getChildAt(index), excluded, base, sessionId)?.let { return it }
            }
        }
        return null
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
}
