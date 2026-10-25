package io.andchamber.app.browser

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.andchamber.app.R
import io.andchamber.app.notify.Channel
import io.andchamber.app.notify.LocalPageVisibility
import io.andchamber.app.notify.OpenChamberNotifier
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.OpenChamberInstaller
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.ProotCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLEncoder

/**
 * Isolated-package E2E for the real notification-shade pairing flow.
 *
 * The form is created through the real 2.0.32 routes and answered only by three SystemUI RemoteInput
 * replies; the test never opens a form Activity, broadcasts, calls PendingIntent.send or posts the
 * reply. Per-step gate: one broadcast action with a free-form RemoteInput, no activity action (a
 * "Fill form" action fails), contentIntent null, and the step observable by the field key
 * (`RemoteInput.resultKey` or a string extra) or by the current field title in the notification
 * title/text/action title; the title is what the host waits for. Replies 1 and 2 must keep the
 * server form pending; only reply 3 may answer.
 *
 * `PendingIntent.showsUserInterface` has no public getter; the gate executes the observable
 * equivalent (broadcast, not activity/service) and records that the flag is not readable.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPairingE2ETest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val runId: String = InstrumentationRegistry.getArguments().getString("runId")?.trim().orEmpty()

    /** Second real question shape: one custom field whose answer is "port code connect". */
    private val singleField: Boolean = when (
        val arg = InstrumentationRegistry.getArguments().getString("singleField")?.trim()
    ) {
        null, "", "false" -> false
        "true" -> true
        else -> {
            fail("singleField-must-be-true-or-false")
            false
        }
    }
    private val fieldKeys = if (singleField) listOf(SINGLE_FIELD_KEY) else FIELD_KEYS
    private val fieldTitles = if (singleField) listOf(SINGLE_FORM_TITLE) else FIELD_TITLES
    private val formTitle = if (singleField) SINGLE_FORM_TITLE else FORM_TITLE
    private val inputShape = if (singleField) "single-custom-question" else "three-field-remote-input"
    private var formCreatedAtMs = 0L

    private fun requireRunId(): String {
        if (!Regex("^[A-Za-z0-9._:-]{8,80}$").matches(runId)) {
            fail("run-id-missing-or-invalid: pass -e runId <host-run-id>")
        }
        return runId
    }

    @Test
    fun realRemoteInputPairingOverGuestAdb() {
        if (context.packageName != ISOLATED_PACKAGE) fail("not-isolated-package: ${context.packageName}")
        val run = LocalProviderRun(context, instrumentation, requireRunId())
        val paths = RuntimePaths.under(context.filesDir)
        val pairCodeFile = File(paths.home, PAIR_CODE_FILE)
        var guest: OpenChamberInstaller.GuestRunner? = null
        var isolated = false
        var cleaned = false
        var stage = "device"
        try {
            run.record("mode", JSONObject()
                .put("singleField", singleField)
                .put("inputShape", inputShape)
                .put("stepCount", fieldKeys.size))
            stage = "device"
            requireDevice(run)
            isolated = true
            grantNotifications(run)
            val manifest = run.runtimeManifest()
            if (manifest.openChamberVersion != OPEN_CHAMBER_PIN || manifest.openCodeVersion != OPEN_CODE_PIN) {
                run.fail("manifest-pins-changed: ${manifest.openChamberVersion}/${manifest.openCodeVersion}")
            }

            stage = "runtime"
            val activity = run.launchActivity()
            ensureRunning(run, paths)
            val metadata = run.verifyStockOpenChamber(manifest, paths).metadata
            if (metadata.openChamberVersion != OPEN_CHAMBER_PIN) run.fail("installed-openchamber-version")
            if (metadata.openCodeVersion != OPEN_CODE_PIN) run.fail("installed-opencode-version")
            run.record("runtime", JSONObject()
                .put("openChamberVersion", metadata.openChamberVersion)
                .put("openCodeVersion", metadata.openCodeVersion)
                .put("alpineVersion", metadata.alpineVersion)
                .put("abi", metadata.abi))

            stage = "health-auth"
            val base = (run.controller().state.value as? RuntimeState.Running)?.url
                ?: run.fail("not-running-at-health")
            waitOpenCodeReady(run, base)
            run.health(base)
            val auth = run.authenticate(base)
            run.systemInfo(base, auth.header)
            run.openCodeVersion(base, auth.header, metadata.openCodeVersion)

            stage = "notification-settings"
            run.record("notificationSettings", awaitNotificationSettings(run, base, auth.header))

            stage = "background"
            run.onMain { activity.moveTaskToBack(true) }
            if (!run.waitFor("local-page-hidden", 30_000) {
                    !run.isActivityResumed(activity) && !LocalPageVisibility.isShowing()
                }
            ) {
                run.fail("local-page-still-showing")
            }

            // Install and prove the guest adb server before the form exists: a real pairing code
            // must never wait behind a package download.
            stage = "guest-adb"
            val suite = EmbeddedCommandSuite(File(context.applicationInfo.nativeLibraryDir), paths).resolve()
            val runner = OpenChamberInstaller.GuestRunner(
                paths = paths,
                proot = suite,
                rootfs = paths.rootfs,
                environment = ProotCommands.installEnvironment(suite.environment(), paths.prootTmp),
                report = NoopInstallReporter,
            )
            guest = runner
            run.record("guestAdb", installAndPreflightAdb(run, runner))

            stage = "form"
            val sessionId = run.createSession(base, auth.header)
            val formId = createForm(run, base, auth.header, sessionId)
            formCreatedAtMs = System.currentTimeMillis()
            if (!run.waitFor("form-pending", 30_000) {
                    readForm(run, base, auth.header, sessionId, formId)?.status == "pending"
                }
            ) {
                run.fail("form-not-pending")
            }

            stage = "inline-notification"
            val manager = context.getSystemService(NotificationManager::class.java)
            val step1 = awaitInlineStep(
                run, manager, base, auth.header, sessionId, formId, 0, null,
                SystemClock.elapsedRealtime() + 60_000,
            )
            run.record("inlineSteps", JSONArray().put(step1.toJson()))
            writeHandshake(run, "await-inline-answers", sessionId, formId, step1, null)
            run.phase(
                "await-inline-answers",
                if (singleField) {
                    "single field: Answer & send with pairPort pairingCode connectionPort separated by spaces"
                } else {
                    "shade: reply Pairing port with the real pairing port"
                },
            )
            val hostDeadline = SystemClock.elapsedRealtime() + HOST_BUDGET_MS
            var lastStep = step1

            if (!singleField) {
                stage = "inline-step-2"
                val step2 = awaitInlineStep(
                    run, manager, base, auth.header, sessionId, formId, 1, step1.notificationId, hostDeadline,
                )
                assertPending(run, base, auth.header, sessionId, formId, "after-pair-port")
                run.record("inlineSteps", JSONArray().put(step1.toJson()).put(step2.toJson()))
                writeHandshake(run, "inline-step-2", sessionId, formId, step2, null)
                run.phase("inline-step-2", "shade: reply Pairing code with the real pairing code")

                stage = "inline-step-3"
                val step3 = awaitInlineStep(
                    run, manager, base, auth.header, sessionId, formId, 2, step2.notificationId, hostDeadline,
                )
                assertPending(run, base, auth.header, sessionId, formId, "after-pair-code")
                run.record(
                    "inlineSteps",
                    JSONArray().put(step1.toJson()).put(step2.toJson()).put(step3.toJson()),
                )
                writeHandshake(run, "inline-step-3", sessionId, formId, step3, null)
                run.phase("inline-step-3", "shade: reply Connection port with the real connection port")
                lastStep = step3
            }

            stage = "answered"
            val answers = awaitAnswered(run, base, auth.header, sessionId, formId, hostDeadline)
            val pairValues = validateAnswers(run, answers)

            stage = "pair"
            writeHandshake(run, "pairing", sessionId, formId, lastStep, answers)
            run.phase("pairing", "answered; guest adb pairs on loopback while the system dialog stays open")
            val answeredAt = SystemClock.elapsedRealtime()
            run.writePrivate(pairCodeFile, pairValues.code)
            val pairing = pairOverGuestAdb(
                run,
                runner,
                pairValues.port.toInt(),
                pairValues.connect.toInt(),
            )
            if (pairCodeFile.exists()) run.fail("pairing-code-file-not-deleted")
            run.record("pairing", pairing
                .put("answeredToPairMs", SystemClock.elapsedRealtime() - answeredAt)
                .put("serverPort", ADB_SERVER_PORT)
                .put("endpoint", "127.0.0.1")
                .put("pairCodeFileDeleted", true))

            stage = "disclosure"
            val code = pairValues.code
            val handshake = File(run.outDir, "pairing-handshake.json").readText()
            val status = run.statusFile.takeIf { it.isFile }?.readText().orEmpty()
            if (handshake.contains(code) || status.contains(code)) run.fail("pairing-code-leaked-to-status")
            run.record("pairCodeDisclosure", JSONObject().put("status", false).put("handshake", false))

            stage = "cleanup"
            run.phase("cleanup")
            stopGuestAdbServer(run, runner)
            stopRuntime(run)
            cleaned = true
            run.finish()
            val evidence = run.evidenceFile.takeIf { it.isFile }?.readText().orEmpty()
            if (evidence.contains(code)) run.fail("pairing-code-leaked-to-evidence")
            run.record("evidenceDisclosure", JSONObject().put("pairingCode", false))
            run.writeEvidence()
        } catch (t: Throwable) {
            run.recordFailure(t, stage)
            throw t
        } finally {
            if (isolated) {
                runCatching { pairCodeFile.delete() }
                if (!cleaned) {
                    runCatching { stopGuestAdbServer(run, guest) }
                    runCatching { stopRuntime(run) }
                }
                runCatching { run.writeEvidence() }
            }
        }
    }

    // --- device and runtime --------------------------------------------------------------------

    private fun requireDevice(run: LocalProviderRun) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi != "x86_64") run.fail("unsupported-primary-abi: $abi")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) run.fail("multi-profile-unsupported")
        run.record("device", JSONObject()
            .put("package", context.packageName)
            .put("isolated", true)
            .put("abi", abi)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abiGate", true))
    }


    private fun grantNotifications(run: LocalProviderRun) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission"),
                ).use { it.readBytes() }
            }
            run.waitFor("notification-permission", 5_000) {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            run.fail("notification-permission-missing")
        }
    }

    /** Production controller only: install when missing, start when Stopped. */
    private fun ensureRunning(run: LocalProviderRun, paths: RuntimePaths) {
        val controller = run.controller()
        val budget = StageBudget({ SystemClock.elapsedRealtime() })
        var action: String? = null
        var idleAfterRequestSince = 0L
        var last = ""
        while (!budget.overallTimedOut()) {
            val state = controller.state.value
            if (state != RuntimeState.NotInstalled || controller.busy.value) idleAfterRequestSince = 0L
            when (state) {
                is RuntimeState.Installing -> budget.enterInstalling()
                is RuntimeState.Starting -> budget.enterStarting()
                else -> Unit
            }
            run.stateName(state).takeIf { it != last }?.let {
                last = it
                run.phase("runtime-$it")
            }
            when (state) {
                RuntimeState.Detecting, is RuntimeState.Stopping -> SystemClock.sleep(500)
                RuntimeState.UnsupportedAbi -> run.fail("unsupported-abi")
                is RuntimeState.Failed -> run.fail("runtime-failed: ${state.phase}:${state.reason}")
                is RuntimeState.Running -> {
                    if (run.installed(paths) == null) run.fail("running-without-installed-runtime")
                    if (!run.waitFor("launch-id", 30_000) { run.server().readyLaunchId() != null }) {
                        run.fail("running-without-launch-id")
                    }
                    return
                }
                is RuntimeState.Installing -> {
                    if (budget.installTimedOut()) run.fail("install-timeout")
                    SystemClock.sleep(500)
                }
                is RuntimeState.Starting -> {
                    if (budget.startTimedOut()) run.fail("start-timeout")
                    SystemClock.sleep(500)
                }
                RuntimeState.NotInstalled -> {
                    if (action != null) {
                        // Service admission is asynchronous; never issue a second install request.
                        if (!controller.busy.value) {
                            val now = SystemClock.elapsedRealtime()
                            if (idleAfterRequestSince == 0L) idleAfterRequestSince = now
                            if (now - idleAfterRequestSince >= 30_000L) run.fail("install-did-not-produce-runtime")
                        }
                        SystemClock.sleep(500)
                        continue
                    }
                    if (!controller.busy.value) {
                        action = "install"
                        controller.install()
                    }
                    SystemClock.sleep(500)
                }
                RuntimeState.Stopped -> {
                    if (action != null || controller.busy.value) {
                        SystemClock.sleep(500)
                    } else {
                        val installed = run.installed(paths) != null
                        action = if (installed) "start" else "install"
                        if (installed) controller.start() else controller.install()
                    }
                }
            }
        }
        run.fail("runtime-timeout: ${run.stateName(controller.state.value)}")
    }

    private fun waitOpenCodeReady(run: LocalProviderRun, base: String) {
        val ready = run.waitFor("opencode-ready", 120_000) {
            runCatching {
                val body = JSONObject(run.get("$base/health").body)
                body.optString("status") == "ok" && body.optBoolean("isOpenCodeReady")
            }.getOrDefault(false)
        }
        if (!ready) run.fail("opencode-not-ready")
    }

    private fun awaitNotificationSettings(run: LocalProviderRun, base: String, cookie: String): JSONObject {
        var last = "unread"
        val enabled = run.waitFor("notification-settings", 90_000) {
            val reply = run.get("$base/api/config/settings", cookie)
            val settings = runCatching { JSONObject(reply.body) }.getOrNull()
                ?.let { it.optJSONObject("data") ?: it }
            if (reply.code != 200 || settings == null) {
                last = "http-${reply.code}"
                return@waitFor false
            }
            if (settings.opt("notifyOnQuestion") == false) run.fail("notify-on-question-disabled")
            last = settings.opt("nativeNotificationsEnabled")?.toString() ?: "null"
            settings.opt("nativeNotificationsEnabled") == true
        }
        if (!enabled) run.fail("native-notifications-not-enabled: $last")
        return JSONObject().put("enabled", true).put("notifyOnQuestionEnabled", true)
    }

    // --- guest adb -----------------------------------------------------------------------------

    private fun installAndPreflightAdb(
        run: LocalProviderRun,
        guest: OpenChamberInstaller.GuestRunner,
    ): JSONObject {
        val result = runGuest(
            guest,
            """
            set -e
            export PATH=${ProotCommands.GUEST_PATH}
            grep -q '$COMMUNITY_REPO' /etc/apk/repositories
            apk --cache-dir /var/cache/apk update >/dev/null
            apk --cache-dir /var/cache/apk add "$ADB_PACKAGE=$ADB_VERSION"
            echo "PKG=$(apk info -v | grep '^$ADB_PACKAGE-')"
            echo "ADBPATH=$(command -v adb)"
            echo "BIN=$(sha256sum /usr/bin/adb | cut -d' ' -f1)"
            echo "VER1=$(adb version | head -n 1)"
            echo "VER2=$(adb version | sed -n '2p')"
            echo "REPOS=$(grep -c '$COMMUNITY_REPO' /etc/apk/repositories)"
            pkgapk=${'$'}(find /var/cache/apk -name '$ADB_PACKAGE-$ADB_VERSION*.apk' -type f | head -n 1)
            if [ -n "${'$'}pkgapk" ]; then
              echo "PKGFILE=$(sha256sum "${'$'}pkgapk" | cut -d' ' -f1)"
              echo "SIGFILE=$(tar -tzf "${'$'}pkgapk" 2>/dev/null | grep '^[.]SIGN[.]' | head -n 1)"
            fi
            mkdir -p $ADB_HOME/tmp
            chmod 700 $ADB_HOME $ADB_HOME/tmp
            echo "HOMEMODE=$(stat -c '%a' $ADB_HOME)"
            export HOME=$ADB_HOME
            export TMPDIR=$ADB_HOME/tmp
            export ANDROID_ADB_SERVER_PORT=$ADB_SERVER_PORT
            adb -P $ADB_SERVER_PORT kill-server >/dev/null 2>&1 || true
            adb -P $ADB_SERVER_PORT start-server
            adb -P $ADB_SERVER_PORT host-features >/dev/null
            echo "SERVER=ready"
            adb -P $ADB_SERVER_PORT kill-server >/dev/null 2>&1 || true
            echo "STAGE=preflight-ok"
            """.trimIndent(),
            timeoutMinutes = 20,
        )
        val values = result.values()
        if (result.exit != 0) run.fail("guest-adb-preflight-exit:${result.exit}")
        if (values["PKG"] != "$ADB_PACKAGE-$ADB_VERSION") run.fail("guest-adb-version")
        if (values["ADBPATH"] != "/usr/bin/adb") run.fail("guest-adb-path")
        if (!Regex("^[0-9a-f]{64}$").matches(values["BIN"].orEmpty())) run.fail("guest-adb-binary-hash")
        if (!values["VER1"].orEmpty().contains("Android Debug Bridge version")) {
            run.fail("guest-adb-client-version-line")
        }
        // `adb version` has two version lines: the client banner (1.0.41) and the build version.
        if (!values["VER2"].orEmpty().contains("35.0.2")) run.fail("guest-adb-build-version")
        if (values["REPOS"] != "1") run.fail("guest-adb-community-repo")
        // Pin the exact HTTPS archive, not just this run's measured hash.
        run.record("guestAdbArchive", JSONObject()
            .put("url", ALPINE_APK_URL)
            .put("expectedSha256", ADB_APK_SHA256)
            .put("measuredSha256", values["PKGFILE"]))
        if (values["PKGFILE"].orEmpty() != ADB_APK_SHA256) run.fail("guest-adb-apk-sha-pin")
        if (!values["SIGFILE"].orEmpty().startsWith(".SIGN.")) run.fail("guest-adb-signature-entry")
        if (values["HOMEMODE"] != "700") run.fail("guest-adb-home-not-private")
        if (values["SERVER"] != "ready" || values["STAGE"] != "preflight-ok") run.fail("guest-adb-server")
        if (OpenChamberInstaller.APK_PACKAGES.contains("android-tools")) run.fail("production-package-list")
        return JSONObject()
            .put("package", values["PKG"])
            .put("binaryPath", values["ADBPATH"])
            .put("binarySha256", values["BIN"])
            .put("downloadedApkSha256", values["PKGFILE"])
            .put("archiveSha256Pinned", true)
            .put("archiveUrl", ALPINE_APK_URL)
            .put("signatureEntry", values["SIGFILE"]?.take(120))
            .put("clientVersionLine", values["VER1"]?.take(80))
            .put("buildVersionLine", values["VER2"]?.take(80))
            .put("privateHomeMode", values["HOMEMODE"])
            .put("serverPreflight", values["SERVER"])
            .put("productionPackageListModified", false)
    }

    private fun pairOverGuestAdb(
        run: LocalProviderRun,
        guest: OpenChamberInstaller.GuestRunner,
        pairPort: Int,
        connectPort: Int,
    ): JSONObject {
        val result = runGuest(
            guest,
            """
            set -e
            export PATH=${ProotCommands.GUEST_PATH}
            export HOME=$ADB_HOME
            export TMPDIR=$ADB_HOME/tmp
            export ANDROID_ADB_SERVER_PORT=$ADB_SERVER_PORT
            chmod 700 $ADB_HOME ${'$'}TMPDIR
            adb -P $ADB_SERVER_PORT kill-server >/dev/null 2>&1 || true
            adb -P $ADB_SERVER_PORT start-server >/dev/null 2>&1
            adb -P $ADB_SERVER_PORT host-features >/dev/null
            echo "STAGE=server-started"
            adb -P $ADB_SERVER_PORT pair 127.0.0.1:$pairPort < /root/$PAIR_CODE_FILE
            rm -f /root/$PAIR_CODE_FILE
            echo "STAGE=pair-ok"
            adb -P $ADB_SERVER_PORT connect 127.0.0.1:$connectPort
            state=unknown
            for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
              state=${'$'}(adb -P $ADB_SERVER_PORT -s 127.0.0.1:$connectPort get-state 2>/dev/null || true)
              if [ "${'$'}state" = "device" ]; then break; fi
              sleep 1
            done
            echo "STATE=${'$'}state"
            [ "${'$'}state" = "device" ] || { echo "STAGE=connect-state-failed"; exit 3; }
            marker=${'$'}(adb -P $ADB_SERVER_PORT -s 127.0.0.1:$connectPort shell echo PAIR_OK 2>&1 | tr -d '\r\n')
            echo "MARKER=${'$'}marker"
            [ "${'$'}marker" = "PAIR_OK" ] || { echo "STAGE=marker-mismatch"; exit 4; }
            echo "STAGE=done"
            adb -P $ADB_SERVER_PORT kill-server >/dev/null 2>&1 || true
            """.trimIndent(),
            timeoutMinutes = 6,
        )
        val stages = result.lines.filter { it.startsWith("STAGE=") }
            .map { it.removePrefix("STAGE=").trim() }
        val values = result.values()
        if (result.exit != 0) run.fail("guest-adb-pair-exit:${result.exit}:stage=${stages.lastOrNull() ?: "?"}")
        if (values["STATE"] != "device") run.fail("guest-adb-connect-state:${values["STATE"] ?: "missing"}")
        if (values["MARKER"] != "PAIR_OK") run.fail("guest-adb-shell-marker:${values["MARKER"] ?: "missing"}")
        if (stages.lastOrNull() != "done") run.fail("guest-adb-sequence-incomplete")
        return JSONObject()
            .put("exit", result.exit)
            .put("stages", JSONArray(stages))
            .put("deviceState", values["STATE"])
            .put("shellMarker", values["MARKER"])
            .put("pairSuccess", true)
            .put("connectSuccess", true)
    }

    private data class GuestResult(val exit: Int, val lines: List<String>) {
        fun values(): Map<String, String> = lines.mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
        }.toMap()
    }

    private fun runGuest(
        guest: OpenChamberInstaller.GuestRunner,
        script: String,
        timeoutMinutes: Long,
    ): GuestResult {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val exit = guest.run(script, timeoutMinutes) { line -> if (lines.size < 512) lines += line }
        return GuestResult(exit, synchronized(lines) { lines.toList() })
    }

    private object NoopInstallReporter : InstallReporter {
        override fun step(step: InstallStep, progress: Float?, detail: String?, messageKey: MessageKey?) = Unit
        override fun log(line: String) = Unit
    }

    // --- form API and RemoteInput stages -------------------------------------------------------

    private data class FormRead(val status: String, val answers: JSONObject?)
    private data class PairValues(val port: String, val code: String, val connect: String)

    private fun createForm(
        run: LocalProviderRun,
        base: String,
        cookie: String,
        sessionId: String,
    ): String {
        val fields = JSONArray().apply {
            fieldKeys.forEachIndexed { index, key ->
                put(JSONObject().put("key", key).put("type", "string")
                    // Match the question tool's wire shape: listed suggestions do not prohibit
                    // a user-provided value when custom is explicitly true.
                    .put("custom", true)
                    .put("options", JSONArray().put(JSONObject().put("value", "example").put("label", "Example")))
                    .apply {
                        if (singleField) {
                            put("description", "Enter pairPort, pairingCode and connectionPort separated by spaces.")
                        }
                    }
                    .put("title", fieldTitles[index]).put("required", true))
            }
        }
        val reply = run.postJson(
            "$base/api/session/${encode(sessionId)}/form",
            cookie,
            JSONObject().put("title", formTitle).put("fields", fields).toString(),
            mapOf("x-opencode-directory" to "/root"),
        )
        if (reply.code != 200) run.fail("form-create-http-${reply.code}")
        val data = runCatching { JSONObject(reply.body) }.getOrNull()?.optJSONObject("data")
            ?: run.fail("form-create-body")
        val id = data.optString("id").takeIf { it.isNotBlank() } ?: run.fail("form-id-missing")
        if (!id.startsWith("frm_")) run.fail("form-id-shape")
        return id
    }

    private fun readForm(
        run: LocalProviderRun,
        base: String,
        cookie: String,
        sessionId: String,
        formId: String,
    ): FormRead? {
        val reply = run.get(
            "$base/api/session/${encode(sessionId)}/form/${encode(formId)}",
            cookie,
            mapOf("x-opencode-directory" to "/root"),
        )
        if (reply.code != 200) return null
        val data = runCatching { JSONObject(reply.body) }.getOrNull()?.optJSONObject("data") ?: return null
        val state = data.optJSONObject("state") ?: return null
        val answers = mutableListOf<JSONObject>().apply {
            listOf("answer", "answers").forEach { (state.opt(it) as? JSONObject)?.let(::add) }
        }.firstOrNull { candidate -> fieldKeys.all { candidate.optString(it).isNotBlank() } }
        return FormRead(state.optString("status"), answers)
    }

    private fun assertPending(
        run: LocalProviderRun,
        base: String,
        cookie: String,
        sessionId: String,
        formId: String,
        marker: String,
    ) {
        val read = runCatching { readForm(run, base, cookie, sessionId, formId) }.getOrNull()
            ?: run.fail("form-read-failed:$marker")
        if (read.status != "pending") run.fail("early-send:$marker:${read.status}")
        run.record("pending:$marker", true)
    }

    private data class InlineStep(
        val notificationId: Int,
        val key: String,
        val index: Int,
        val resultKey: String,
        val metadataSource: String,
        val activityActions: Int,
        val contentIntentNull: Boolean,
        val broadcast: Boolean,
        val fillFormAction: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("notificationId", notificationId)
            .put("fieldKey", key)
            .put("index", index)
            .put("resultKey", resultKey)
            .put("metadataSource", metadataSource)
            .put("activityActions", activityActions)
            .put("contentIntentNull", contentIntentNull)
            .put("actionIntentBroadcast", broadcast)
            .put("remoteInputFreeform", true)
            .put("fillFormAction", fillFormAction)
            .put("showsUserInterfaceReadable", false)
    }

    private fun awaitInlineStep(
        run: LocalProviderRun,
        manager: NotificationManager,
        base: String,
        cookie: String,
        sessionId: String,
        formId: String,
        expectedIndex: Int,
        previousId: Int?,
        deadlineMs: Long,
    ): InlineStep {
        var lastKey = "none"
        var lastStatus = "none"
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val read = runCatching { readForm(run, base, cookie, sessionId, formId) }.getOrNull()
            lastStatus = read?.status ?: "unreadable"
            if (read != null && read.status != "pending") {
                run.fail("form-early-settled: step=${expectedIndex + 1} status=${read.status}")
            }
            inspectInline(run, manager, formId, previousId, expectedIndex)?.let { step ->
                lastKey = step.key
                if (step.index == expectedIndex) return step
            }
            SystemClock.sleep(500)
        }
        run.fail("inline-step-timeout: expected=${expectedIndex + 1} lastKey=$lastKey form=$lastStatus")
    }

    /** Gate for the currently posted step; a candidate at another index is left for the caller. */
    private fun inspectInline(
        run: LocalProviderRun,
        manager: NotificationManager,
        formId: String,
        previousId: Int?,
        expectedIndex: Int,
    ): InlineStep? {
        val sbn = findCandidate(manager, formId, previousId, expectedIndex) ?: return null
        val n = sbn.notification
        val actions = n.actions.orEmpty()
        val fillForm = actions.any { it.title?.toString() == context.getString(R.string.action_fill_form) }
        if (fillForm) run.fail("fill-form-activity-action-present")
        val activityActions = actions.count { it.actionIntent?.isActivity == true }
        if (activityActions > 0) run.fail("inline-activity-action-present")
        if (n.contentIntent != null) run.fail("inline-content-intent-present")
        val reply = actions.firstOrNull { action ->
            action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
        } ?: run.fail("inline-remote-input-missing: actions=${actions.size} titles=${actionTitles(actions)}")
        val intent = reply.actionIntent ?: run.fail("inline-action-intent-missing")
        // showsUserInterface has no public getter; broadcast + not activity/service is observable and
        // is what SystemUI requires for an inline RemoteInput.
        if (!intent.isBroadcast) run.fail("inline-action-not-broadcast")
        if (intent.isActivity || intent.isService) run.fail("inline-action-shows-ui")
        val input = reply.remoteInputs.orEmpty().firstOrNull { it.allowFreeFormInput }
            ?: run.fail("inline-remote-input-not-freeform")
        val visible = stepVisibleText(sbn)
        val extraField = fieldKeys.firstOrNull { key ->
            n.extras.keySet().any { name -> n.extras.getString(name) == key }
        }
        val key = extraField ?: input.resultKey?.takeIf { it in fieldKeys }
        val byKey = key?.let(fieldKeys::indexOf)
        val byTitle = stepTitleIndex(sbn)
        val index = byKey ?: byTitle
            ?: run.fail("inline-step-metadata-missing: resultKey=${input.resultKey?.take(40)}")
        val source = if (byKey != null) "key" else "title"
        if (index != expectedIndex) {
            return InlineStep(
                sbn.id, key ?: fieldKeys[index], index, input.resultKey.orEmpty(), source,
                activityActions, n.contentIntent == null, intent.isBroadcast, fillForm,
            )
        }
        if (!visible.contains(fieldTitles[index], ignoreCase = true)) {
            run.fail("inline-step-title-missing: ${fieldTitles[index]}")
        }
        if (n.channelId != Channel.NeedsInput.id) run.fail("inline-channel: ${n.channelId}")
        return InlineStep(
            sbn.id, key ?: fieldKeys[index], index, input.resultKey.orEmpty(), source,
            activityActions, n.contentIntent == null, intent.isBroadcast, fillForm,
        )
    }

    private fun findCandidate(
        manager: NotificationManager,
        formId: String,
        previousId: Int?,
        expectedIndex: Int,
    ): StatusBarNotification? {
        val active = manager.activeNotifications.filter { it.postTime >= formCreatedAtMs - 2_000 }
        val expectedKey = fieldKeys[expectedIndex]
        // The expected step wins over a still-posted previous step with another id.
        active.firstOrNull { sbn ->
            sbn.notification.extras.keySet().any { name -> sbn.notification.extras.getString(name) == expectedKey } ||
                sbn.notification.actions.orEmpty().any { action ->
                    action.remoteInputs.orEmpty().any { it.resultKey == expectedKey }
                }
        }?.let { return it }
        active.firstOrNull {
            it.notification.extras.getString(OpenChamberNotifier.EXTRA_FORM_ID) == formId &&
                stepTitleIndex(it) == expectedIndex
        }?.let { return it }
        active.firstOrNull { stepTitleIndex(it) == expectedIndex }?.let { return it }
        previousId?.let { id -> active.firstOrNull { it.id == id }?.let { return it } }
        return active.firstOrNull { sbn ->
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            title.contains(formTitle, true) || text.contains(formTitle, true)
        }
    }

    private fun stepVisibleText(sbn: StatusBarNotification): String =
        listOfNotNull(
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ") + " " + sbn.notification.actions.orEmpty()
            .joinToString(" ") { it.title?.toString().orEmpty() }

    private fun stepTitleIndex(sbn: StatusBarNotification): Int? {
        val visible = stepVisibleText(sbn)
        return fieldTitles.indices.filter { visible.contains(fieldTitles[it], true) }.singleOrNull()
    }

    private fun actionTitles(actions: Array<out Notification.Action>): String =
        actions.joinToString(",") { it.title?.toString()?.take(24).orEmpty() }.take(160)

    private fun awaitAnswered(
        run: LocalProviderRun,
        base: String,
        cookie: String,
        sessionId: String,
        formId: String,
        deadlineMs: Long,
    ): JSONObject {
        val transitions = JSONArray()
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val read = runCatching { readForm(run, base, cookie, sessionId, formId) }.getOrNull()
            val status = read?.status.orEmpty()
            if (status.isNotEmpty() && status != last) {
                transitions.put(status)
                last = status
            }
            when (status) {
                "answered" -> {
                    val answers = read?.answers ?: run.fail("answered-without-required-fields")
                    run.record("form", JSONObject()
                        .put("statuses", transitions)
                        .put("inputShape", inputShape)
                        .put("stepCount", fieldKeys.size)
                        .put("answerKeys", JSONArray(answers.keys().asSequence().toList()))
                        .put("answeredAfterSingleReply", singleField)
                        .put("answeredAfterThirdReply", !singleField)
                        .put("valuesRecorded", false))
                    return answers
                }
                "cancelled" -> run.fail("form-cancelled")
            }
            SystemClock.sleep(500)
        }
        run.record("form", JSONObject().put("statuses", transitions).put("lastStatus", last ?: "none"))
        run.fail("inline-form-not-answered")
    }

    /**
     * Returns the standard q0/q1/q2 values. In single mode the one server answer is split on
     * whitespace and mapped here; the host handshake never carries an answer.
     */
    private fun validateAnswers(run: LocalProviderRun, answers: JSONObject): PairValues {
        val values = if (singleField) {
            val parts = answers.optString(fieldKeys[0]).trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (parts.size != 3) run.fail("single-answer-segments:${parts.size}")
            PairValues(parts[0], parts[1], parts[2])
        } else {
            PairValues(
                answers.optString(fieldKeys[0]).trim(),
                answers.optString(fieldKeys[1]).trim(),
                answers.optString(fieldKeys[2]).trim(),
            )
        }
        val pairPort = values.port.toIntOrNull()
        val connectPort = values.connect.toIntOrNull()
        if (pairPort == null || pairPort !in 1..65535) run.fail("pair-port-invalid")
        if (connectPort == null || connectPort !in 1..65535) run.fail("connect-port-invalid")
        if (!Regex("^[0-9]{4,12}$").matches(values.code)) run.fail("pair-code-not-digits")
        run.record("answerShape", JSONObject()
            .put("inputShape", inputShape)
            .put("pairPortInRange", true)
            .put("connectPortInRange", true)
            .put("pairCodeDigits", true)
            .apply {
                if (singleField) {
                    put("splitWhitespaceSegments", 3)
                    put("mappedToStandard", JSONArray(listOf("q0", "q1", "q2")))
                }
            })
        return values
    }

    private fun writeHandshake(
        run: LocalProviderRun,
        phase: String,
        sessionId: String,
        formId: String,
        step: InlineStep,
        answers: JSONObject?,
    ) {
        // No typed value and no pairing code ever enters this file.
        val body = JSONObject()
            .put("phase", phase)
            .put("runId", run.runId)
            .put("package", context.packageName)
            .put("sessionId", sessionId)
            .put("formId", formId)
            .put("formTitle", formTitle)
            .put("notificationId", step.notificationId)
            .put("stepIndex", step.index)
            .put("stepKey", step.key)
            .put("fieldKeys", JSONArray(fieldKeys))
            .put("fieldTitles", JSONArray(fieldTitles))
            .put("stepCount", fieldKeys.size)
            .put("inputShape", inputShape)
            .put(
                "phases",
                JSONArray(
                    if (singleField) listOf("await-inline-answers")
                    else listOf("await-inline-answers", "inline-step-2", "inline-step-3"),
                ),
            )
            .put("answerWaitBudgetMs", HOST_BUDGET_MS)
        if (answers != null) body.put("answered", true)
        run.writePrivate(File(run.outDir, "pairing-handshake.json"), body.toString())
    }

    // --- cleanup -------------------------------------------------------------------------------

    private fun stopGuestAdbServer(run: LocalProviderRun, guest: OpenChamberInstaller.GuestRunner?) {
        if (guest == null) return
        val result = runCatching {
            runGuest(
                guest,
                """
                export HOME=$ADB_HOME
                adb -P $ADB_SERVER_PORT kill-server >/dev/null 2>&1 || true
                rm -f /root/$PAIR_CODE_FILE
                """.trimIndent(),
                timeoutMinutes = 2,
            )
        }
        run.record("guestAdbServerKill", result.isSuccess && result.getOrNull()?.exit == 0)
    }

    private fun stopRuntime(run: LocalProviderRun) {
        val controller = run.controller()
        when (controller.state.value) {
            is RuntimeState.Stopped -> {
                run.record("controllerStopped", true)
                return
            }
            is RuntimeState.Running, is RuntimeState.Starting, is RuntimeState.Stopping -> Unit
            else -> {
                run.record("controllerStopped", false)
                return
            }
        }
        controller.stop()
        run.record("controllerStopped", run.waitFor("controller-stop", 90_000) {
            controller.state.value is RuntimeState.Stopped
        })
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val ISOLATED_PACKAGE = "io.andchamber.pairprobe"
        const val OPEN_CHAMBER_PIN = "2.4.0"
        const val OPEN_CODE_PIN = "2.0.32"
        const val COMMUNITY_REPO = "https://dl-cdn.alpinelinux.org/alpine/v3.24/community"
        const val ALPINE_APK_URL =
            "$COMMUNITY_REPO/x86_64/android-tools-adb-35.0.2-r21.apk"

        /** SHA-256 of that exact HTTPS archive (downloaded twice, byte-identical). */
        const val ADB_APK_SHA256 = "5bccb098ffdc0c36667588c725f5767ca9c698fc17ebda353521cb8f709cddbe"
        const val ADB_PACKAGE = "android-tools-adb"
        const val ADB_VERSION = "35.0.2-r21"
        const val ADB_SERVER_PORT = 15037
        const val ADB_HOME = "/root/adb-probe"
        const val PAIR_CODE_FILE = "adb-probe/pair-code"
        const val FORM_TITLE = "Pair this device"
        const val SINGLE_FORM_TITLE = "Pairing details"
        const val SINGLE_FIELD_KEY = "q0"
        const val HOST_BUDGET_MS = 10 * 60_000L

        /** Required from the renderer: the step's field key must be observable (extra or resultKey). */

        val FIELD_KEYS = listOf("q0", "q1", "q2")
        val FIELD_TITLES = listOf("Pairing port", "Pairing code", "Connection port")
    }
}
