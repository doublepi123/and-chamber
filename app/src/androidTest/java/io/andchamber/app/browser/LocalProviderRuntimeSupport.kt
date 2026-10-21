package io.andchamber.app.browser

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import io.andchamber.app.MainActivity
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeOperation
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionResult
import io.andchamber.app.runtime.install.OpenChamberProviderArtifact
import io.andchamber.app.runtime.install.RuntimeMetadata
import io.andchamber.app.runtime.manifest.RuntimeManifest
import io.andchamber.app.runtime.manifest.RuntimeManifestReader
import io.andchamber.app.runtime.process.BrowserProviderSession
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.OpenChamberServer
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * androidTest-only support for the true-local entry gate. It never replaces a Koin definition:
 * the controller, server, authenticator and launcher are the production graph from
 * AndChamberApplication. Only the target browser page may receive the existing HTTPS fixture after
 * `browser.open`; the chat page and the local profile stay real.
 */
internal class LocalProviderRun(
    val context: Context,
    val instrumentation: Instrumentation,
    val runId: String,
    val cold: Boolean = false,
) {
    val outDir = File(context.filesDir, "local-provider-e2e/current")
    val statusFile = File(outDir, if (cold) "cold-status.json" else "status.json")
    val evidenceFile = File(outDir, if (cold) "cold-evidence.json" else "evidence.json")
    val counters = linkedMapOf<String, Int>()
    private val facts = linkedMapOf<String, Any>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var failure: String? = null

    init {
        outDir.mkdirs()
        (outDir.parentFile ?: outDir).setReadable(true, true)
        statusFile.delete()
        evidenceFile.delete()
    }

    fun phase(name: String, detail: String? = null) {
        writeStatus(name, detail)
    }

    /**
     * Read-only accessibility loop for a human tap phase. The status always stays on this phase;
     * the test refreshes the exported target bounds while the host reads them. No waitForIdle, no
     * UiDevice, no click/grant. The returned target is metadata only; the host performs the tap.
     */
    fun awaitHumanTap(
        phase: String,
        label: String,
        budgetMs: Long = 120_000L,
        sourceViewHandle: () -> Int? = { null },
        done: () -> Boolean,
    ): LocalTapTarget? {
        var lastReason = "starting"
        var lastTarget: LocalTapTarget? = null
        writeStatus(phase, "label=$label")
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val read = readTapTarget(label, phase, sourceViewHandle())
            if (read.target != null) {
                lastTarget = read.target
                writeStatus(phase, "label=$label", read.target)
            } else if (read.reason != lastReason) {
                lastReason = read.reason
                writeStatus(phase, "missing:${read.reason}")
            }
            if (done()) return lastTarget
            SystemClock.sleep(500)
        }
        writeStatus(phase, "tap-target-missing:$lastReason")
        fail("tap-target-missing:$label:$lastReason")
    }

    fun readTapTarget(label: String, phase: String, sourceViewHandle: Int?): TapRead {
        val root = try {
            instrumentation.uiAutomation.rootInActiveWindow
        } catch (error: Throwable) {
            return TapRead(null, "accessibility-unavailable:${error.javaClass.simpleName}")
        } ?: return TapRead(null, "no-active-window")
        val tracked = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(),
        )
        fun track(node: AccessibilityNodeInfo) {
            tracked.add(node)
        }
        return try {
            track(root)
            val owner = root.packageName?.toString().orEmpty()
            if (owner != context.packageName) {
                return TapRead(null, "active-window-not-own-package:${owner.take(80)}")
            }
            val screen = screenBounds()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var fallback = "label-not-found"
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (matchesLabel(node, label) && node.isVisibleToUser &&
                    node.packageName?.toString() == context.packageName) {
                    val target = clickableTarget(node, label, phase, sourceViewHandle, screen) { track(it) }
                    if (target != null) return TapRead(target, "")
                    fallback = "label-not-clickable-or-outside-screen"
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        track(child)
                        queue.add(child)
                    }
                }
            }
            TapRead(null, fallback)
        } catch (error: Throwable) {
            TapRead(null, "accessibility-read-failed:${error.javaClass.simpleName}")
        } finally {
            tracked.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
        }
    }

    private fun clickableTarget(
        labelNode: AccessibilityNodeInfo,
        label: String,
        phase: String,
        sourceViewHandle: Int?,
        screen: Rect,
        track: (AccessibilityNodeInfo) -> Unit,
    ): LocalTapTarget? {
        var current: AccessibilityNodeInfo? = labelNode
        while (current != null) {
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) {
                val bounds = Rect()
                current.getBoundsInScreen(bounds)
                val owner = current.packageName?.toString().orEmpty()
                if (owner != context.packageName) return null
                if (!insideScreen(bounds, screen)) return null
                if (bounds.width() <= 0 || bounds.height() <= 0) return null
                return LocalTapTarget(
                    label = label,
                    bounds = Rect(bounds),
                    windowId = current.windowId,
                    observedAtMs = System.currentTimeMillis(),
                    ownerPackage = owner,
                    sourceViewHandle = sourceViewHandle,
                    phase = phase,
                    runId = runId,
                )
            }
            val parent = current.parent ?: break
            track(parent)
            current = parent
        }
        return null
    }

    private fun matchesLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString() == label || node.contentDescription?.toString() == label

    private fun insideScreen(bounds: Rect, screen: Rect): Boolean =
        bounds.left >= screen.left && bounds.top >= screen.top &&
            bounds.right <= screen.right && bounds.bottom <= screen.bottom

    private fun screenBounds(): Rect {
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    fun writeStatus(name: String, detail: String? = null, target: LocalTapTarget? = null) {
        val body = JSONObject()
            .put("phase", name)
            .put("runId", runId)
            .put("cold", cold)
            .put("updatedAtMs", System.currentTimeMillis())
            .put("tapTarget", target?.toJson() ?: JSONObject.NULL)
        if (detail != null) body.put("detail", detail.take(600))
        val state = runCatching { controller().state.value }.getOrNull()
        if (state != null) body.put("state", stateName(state))
        writePrivate(statusFile, body.toString())
    }

    fun record(key: String, value: Any?) {
        if (value != null) facts[key] = value
    }

    fun bump(action: String) {
        counters[action] = (counters[action] ?: 0) + 1
    }

    fun countOnce(action: String) {
        val next = (counters[action] ?: 0) + 1
        counters[action] = next
        if (next != 1) fail("action-executed-more-than-once: $action=$next")
    }

    fun fail(reason: String): Nothing {
        failure = reason
        runCatching { writeStatus("failed", reason) }
        runCatching { writeEvidence() }
        throw AssertionError(reason)
    }

    /** Records a caught exception with a fixed, low-risk stage/class only; never dumps its message. */
    fun recordFailure(error: Throwable, stage: String) {
        val safe = "$stage:${error.javaClass.simpleName}"
        if (failure == null) failure = safe
        record("failureStage", stage)
        runCatching { writeStatus("failed", safe) }
    }

    fun finish() {
        writeStatus("done")
        writeEvidence()
    }

    fun writeEvidence() {
        val body = JSONObject()
            .put("phase", if (failure == null) "done" else "failed")
            .put("runId", runId)
            .put("cold", cold)
            .put("failure", failure ?: JSONObject.NULL)
            .put("facts", JSONObject(facts as Map<*, *>))
            .put("actions", JSONObject(counters as Map<*, *>))
            .put("device", deviceJson())
            .put("statusFile", statusFile.name)
        writePrivate(evidenceFile, body.toString())
    }

    fun deviceJson(): JSONObject =
        JSONObject()
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull())
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("model", Build.MODEL)
            .put("package", context.packageName)
            .put("versionName", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull())

    fun controller(): LocalRuntimeController = GlobalContext.get().get()
    fun server(): OpenChamberServer = GlobalContext.get().get()
    fun presenter(): NativeBrowserPresenter = GlobalContext.get().get()
    fun providerSession(): BrowserProviderSession {
        // This credential owner is private to the real server, not a separately registered Koin bean.
        val actualServer = server()
        val field = actualServer.javaClass.getDeclaredField("browserProvider").apply { isAccessible = true }
        return field.get(actualServer) as? BrowserProviderSession
            ?: fail("production-provider-session-unavailable")
    }

    inline fun <reified T : Any> service(): T = GlobalContext.get().get()

    fun <T> onMain(block: () -> T): T {
        var value: T? = null
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                value = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun launchActivity(): MainActivity {
        // The production launch flow opens the local console when the runtime is Running and starts
        // it when the runtime is Stopped. A fresh dedicated test package has auto_open_local=true.
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_open_local", true)
            .putString("last_target_id", "local")
            .apply()
        // Do not use MonitoringInstrumentation.startActivitySync: a live Compose/WebView animation
        // can keep the main queue busy and that call waits for global idle. ActivityMonitor observes
        // the target Activity itself; readiness is the Activity lifecycle + attached decor, not idle.
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        return try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 30_000) as? MainActivity
                ?: fail("activity-launch-timeout: ${context.packageName}/MainActivity")
            val ready = waitFor("activity-ready", 30_000) { isActivityResumed(activity) }
            if (!ready) fail("activity-not-resumed: ${activity.componentName.flattenToString()}")
            onMain { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            activity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    fun isActivityResumed(activity: MainActivity): Boolean =
        onMain {
            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                activity.window.decorView.isAttachedToWindow
        }

    fun activityIdentity(activity: MainActivity): String =
        "${activity.componentName.flattenToString()}/task=${activity.taskId}"

    fun waitFor(label: String, budgetMs: Long, predicate: () -> Boolean): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < budgetMs) {
            if (predicate()) return true
            SystemClock.sleep(250)
        }
        return false
    }

    fun stateName(state: RuntimeState): String =
        when (state) {
            RuntimeState.Detecting -> "detecting"
            RuntimeState.UnsupportedAbi -> "unsupported-abi"
            RuntimeState.NotInstalled -> "not-installed"
            RuntimeState.Stopped -> "stopped"
            is RuntimeState.Starting -> "starting"
            is RuntimeState.Running -> "running"
            is RuntimeState.Stopping -> "stopping"
            is RuntimeState.Installing -> "installing:${state.step}"
            is RuntimeState.Failed -> "failed:${state.phase}:${state.reason}"
        }

    fun installed(paths: RuntimePaths): InstalledInspection? {
        val metadataFile = paths.metadataFile
        if (!metadataFile.isFile || !paths.rootfs.isDirectory) return null
        val metadata = runCatching { json.decodeFromString<RuntimeMetadata>(metadataFile.readText()) }.getOrNull()
            ?: return null
        val moduleRoot = File(paths.rootfs, "usr/local/lib/node_modules/@openchamber/web")
        return InstalledInspection(
            metadata = metadata,
            moduleRoot = moduleRoot,
            packageVersion = packageVersion(moduleRoot),
            applied = OpenChamberProviderArtifact.isApplied(moduleRoot, OpenChamberProviderArtifact.PACKAGE_VERSION),
        )
    }

    fun packageVersion(moduleRoot: File): String? {
        val file = File(moduleRoot, "package.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()).optString("version").takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun providerManifestFromApk(): OpenChamberProviderArtifact.LaneManifest {
        val raw = runCatching {
            context.assets.open("openchamber-provider/manifest.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: fail("apk-provider-manifest-missing: build with -Pandchamber.providerArtifactDir")
        val manifest = runCatching { OpenChamberProviderArtifact.parse(raw) }
            .getOrElse { fail("apk-provider-manifest-invalid: ${it.javaClass.simpleName}") }
        OpenChamberProviderArtifact.manifestRejection(manifest)?.let { fail("apk-provider-manifest-rejected: $it") }
        return manifest
    }

    fun verifyAssetPayload(manifest: OpenChamberProviderArtifact.LaneManifest): Int {
        var files = 0
        for (pin in manifest.server + manifest.dist) {
            val bytes = runCatching { context.assets.open("openchamber-provider/${pin.path}").use { it.readBytes() } }
                .getOrElse { fail("apk-provider-file-missing: ${pin.path}") }
            if (bytes.size.toLong() != pin.bytes) fail("apk-provider-file-size: ${pin.path}")
            if (sha256(bytes) != pin.sha256) fail("apk-provider-file-hash: ${pin.path}")
            files += 1
        }
        return files
    }

    fun verifyInstalledPayload(
        manifest: OpenChamberProviderArtifact.LaneManifest,
        paths: RuntimePaths,
    ): InstalledInspection {
        val inspection = installed(paths) ?: fail("installed-runtime-missing")
        val metadata = inspection.metadata
        if (metadata.abi != "x86_64") fail("installed-abi-not-x86_64: ${metadata.abi}")
        if (!inspection.moduleRoot.isDirectory) fail("installed-openchamber-package-missing")
        val packageJson = File(inspection.moduleRoot, "package.json")
        if (!packageJson.isFile) fail("installed-package-json-missing")
        val parsed = runCatching { JSONObject(packageJson.readText()) }
            .getOrElse { fail("installed-package-json-invalid") }
        if (parsed.optString("name") != OpenChamberProviderArtifact.PACKAGE_NAME) {
            fail("installed-package-name: ${parsed.optString("name")}")
        }
        if (parsed.optString("version") != OpenChamberProviderArtifact.PACKAGE_VERSION) {
            fail("installed-package-version: ${parsed.optString("version")}")
        }
        if (!OpenChamberProviderArtifact.isApplied(inspection.moduleRoot, OpenChamberProviderArtifact.PACKAGE_VERSION)) {
            fail("installed-provider-marker-not-applied")
        }
        if (!OpenChamberProviderArtifact.verifyInstalled(inspection.moduleRoot, OpenChamberProviderArtifact.PACKAGE_VERSION)) {
            fail("installed-provider-payload-hash-mismatch")
        }
        val markerFile = File(inspection.moduleRoot, OpenChamberProviderArtifact.MARKER)
        val marker = runCatching { OpenChamberProviderArtifact.parse(markerFile.readText()) }
            .getOrElse { fail("installed-provider-marker-invalid") }
        if (marker != manifest) fail("installed-provider-marker-differs-from-apk")
        // Re-hash every installed pin independently of the production helper and keep only a digest.
        val digest = MessageDigest.getInstance("SHA-256")
        for (pin in manifest.server + manifest.dist) {
            val relative = OpenChamberProviderArtifact.guestPath(pin.path) ?: fail("installed-provider-path-outside: ${pin.path}")
            val file = OpenChamberProviderArtifact.containedFile(inspection.moduleRoot, relative)
                ?: fail("installed-provider-path-unsafe: ${pin.path}")
            if (!file.isFile) fail("installed-provider-file-missing: ${pin.path}")
            if (file.length() != pin.bytes) fail("installed-provider-file-size: ${pin.path}")
            val hash = fileSha256(file)
            if (hash != pin.sha256) fail("installed-provider-file-hash: ${pin.path}")
            digest.update("${pin.path}:$hash\n".toByteArray())
        }
        record("installedProviderPayloadDigest", digest.digest().joinToString("") { "%02x".format(it) })
        record("installedProviderFiles", manifest.server.size + manifest.dist.size)
        record("installedMetadata", JSONObject()
            .put("runtimeVersion", metadata.runtimeVersion)
            .put("alpineVersion", metadata.alpineVersion)
            .put("abi", metadata.abi)
            .put("port", metadata.port)
            .put("openChamberVersion", metadata.openChamberVersion)
            .put("openCodeVersion", metadata.openCodeVersion)
            .put("nodeVersion", metadata.nodeVersion)
            .put("installedAt", metadata.installedAt))
        return inspection
    }

    fun runtimeManifest(): RuntimeManifest =
        RuntimeManifestReader {
            context.assets.open(RuntimeManifestReader.ASSET_NAME).bufferedReader().use { it.readText() }
        }.read()

    fun verifyProotProvenance(): String {
        val raw = runCatching {
            context.assets.open("licenses/native/provenance.json").bufferedReader().use { it.readText() }
        }.getOrElse { fail("proot-provenance-asset-missing") }
        val components = runCatching { JSONObject(raw).optJSONArray("components") }
            .getOrElse { fail("proot-provenance-invalid") } ?: fail("proot-provenance-invalid")
        var proot: JSONObject? = null
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: continue
            if (item.optString("name") == "proot") proot = item
        }
        val component = proot ?: fail("proot-provenance-component-missing")
        if (component.optString("version") != "5.1.107.98") {
            fail("proot-version: ${component.optString("version")}")
        }
        if (component.optString("commit") != "e2d843ee6dab076ea0ed1ffd5d0ea12232185499") {
            fail("proot-commit: ${component.optString("commit")}")
        }
        val suite = runCatching { EmbeddedCommandSuite(File(context.applicationInfo.nativeLibraryDir), RuntimePaths.under(context.filesDir)).resolve() }
            .getOrElse { fail("proot-native-libraries-missing: ${it.message}") }
        record("proot", JSONObject()
            .put("version", component.optString("version"))
            .put("commit", component.optString("commit"))
            .put("prootSha256", fileSha256(suite.proot))
            .put("loaderSha256", fileSha256(suite.loader))
            .put("loader32Sha256", fileSha256(suite.loader32)))
        return component.optString("version")
    }

    fun createSession(base: String, cookie: String): String {
        val directory = URLEncoder.encode("/root", "UTF-8")
        val reply = postJson(
            "$base/api/session?directory=$directory",
            cookie,
            "{}",
            extraHeaders = mapOf("x-opencode-directory" to "/root"),
        )
        if (reply.code != 200) fail("session-http-${reply.code}")
        val body = runCatching { JSONObject(reply.body) }.getOrElse { fail("session-body-invalid") }
        val id = body.optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: body.optString("id").takeIf { it.isNotBlank() }
            ?: fail("session-id-missing")
        if (!id.startsWith("ses_")) fail("session-id-not-real: ${id.take(12)}")
        record("sessionIdPrefix", id.take(8))
        return id
    }

    fun profileCookies(target: ServerTarget): Pair<WebSessionCookies, WebView> {
        val binding = onMain { service<WebSessionProfiles>().acquire(target) }
        val view = onMain { WebView(context) }
        val cookies = onMain { binding.bindFreshWebView(view) }
        return cookies to view
    }

    fun authenticate(base: String): WebSessionAuth {
        val target = ServerTarget.Local(base)
        val (cookies, view) = profileCookies(target)
        val result = runBlocking { service<WebSessionAuthenticator>().prepare(target, cookies) }
        if (result !is WebSessionResult.Ready) {
            fail("authenticator-result: ${result::class.simpleName}")
        }
        val header = runBlocking { cookies.cookieHeader(base) }
        if (header.isNullOrEmpty()) fail("profile-cookie-missing-after-auth")
        record("authCookieSha256", sha256(header.toByteArray()))
        record("authCookieLength", header.length)
        return WebSessionAuth(cookies, view, base, header)
    }

    fun health(base: String): JSONObject {
        val reply = get("$base/health")
        if (reply.code != 200) fail("health-http-${reply.code}")
        val body = runCatching { JSONObject(reply.body) }.getOrElse { fail("health-body-invalid") }
        if (body.optString("status") != "ok") fail("health-status: ${body.optString("status")}")
        // The real schema carries this boolean; a missing field is not readiness.
        if (!body.has("isOpenCodeReady") || !body.optBoolean("isOpenCodeReady")) {
            fail("health-opencode-not-ready")
        }
        record("health", JSONObject().put("status", body.optString("status"))
            .put("isOpenCodeReady", body.optBoolean("isOpenCodeReady")))
        return body
    }

    fun systemInfo(base: String, cookie: String): JSONObject {
        val reply = get("$base/api/system/info", cookie)
        if (reply.code != 200) fail("system-info-http-${reply.code}")
        val body = runCatching { JSONObject(reply.body) }.getOrElse { fail("system-info-body-invalid") }
        if (body.optJSONObject("data") == null) {
            // Actual route: {openchamberVersion, runtime, pid, startedAt, ...}
            if (body.optString("openchamberVersion").isBlank() || body.optString("runtime").isBlank()) {
                fail("system-info-schema-invalid")
            }
        }
        record("systemInfo", JSONObject().put("keys", body.keys().asSequence().sorted().joinToString(",")))
        return body
    }

    fun openCodeVersion(base: String, cookie: String, expected: String): JSONObject {
        val reply = get("$base/api/opencode/version", cookie)
        if (reply.code != 200) fail("opencode-version-http-${reply.code}")
        val body = runCatching { JSONObject(reply.body) }.getOrElse { fail("opencode-version-body-invalid") }
        val actual = body.optString("version")
        if (actual != expected) fail("opencode-version-mismatch: expected $expected actual ${actual.take(40)}")
        record("openCodeVersionBody", JSONObject().put("version", actual))
        return body
    }

    fun apiInfo(base: String, cookie: String): JSONObject {
        val reply = get("$base/api/info", cookie)
        if (reply.code != 200) fail("api-info-http-${reply.code}")
        record("apiInfo", runCatching { JSONObject(reply.body).toString().take(400) }.getOrElse { "non-json" })
        return runCatching { JSONObject(reply.body) }.getOrDefault(JSONObject())
    }

    fun get(url: String, cookie: String? = null): HttpReply {
        val request = Request.Builder().url(url).get().apply {
            if (cookie != null) header("Cookie", cookie)
            header("Connection", "close")
        }.build()
        return execute(request, url)
    }

    fun postJson(
        url: String,
        cookie: String?,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpReply {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply {
                if (cookie != null) header("Cookie", cookie)
                header("Connection", "close")
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()
        return execute(request, url)
    }

    private fun execute(request: Request, url: String): HttpReply {
        val client = service<OkHttpClient>().newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.length > 262_144) fail("http-response-too-large: $url")
            HttpReply(response.code, text)
        }
    }

    fun recordedCookieHash(base: String, auth: WebSessionAuth): String {
        val header = runBlocking { auth.cookies.cookieHeader(base) } ?: fail("profile-cookie-missing")
        if (header.isEmpty()) fail("profile-cookie-empty")
        return sha256(header.toByteArray())
    }

    fun capturePath(raw: String?): File? {
        if (raw.isNullOrBlank()) return null
        // The real capture service saves under the session directory `/root`, which is the
        // PRoot bind of RuntimePaths.home. Only `.openchamber/screenshots/` and its `/root/`
        // absolute spelling are accepted; any other directory or absolute host path fails.
        val relative = when {
            raw.startsWith("/root/") -> raw.removePrefix("/root/")
            raw.startsWith("/") -> return null
            else -> raw
        }
        if (!relative.startsWith(".openchamber/screenshots/")) return null
        val home = RuntimePaths.under(context.filesDir).home
        val file = OpenChamberProviderArtifact.containedFile(home, relative) ?: return null
        return file.takeIf { it.isFile }
    }

    fun eval(view: WebView, script: String): String? {
        var value: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        onMain {
            view.evaluateJavascript(script) { raw ->
                value = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return null
        return value
    }

    /**
     * One fixed JSON read of the current target document. The JS returns a JSON string; run.eval
     * already decodes one JS-string layer, so JSONObject(raw) is the single parse (no double parse).
     * It reads only fixed fixture ids/types/text and the witness presence, never arbitrary page data.
     */
    fun readFixtureDocument(view: WebView): FixtureDocumentRead {
        val raw = runCatching { eval(view, FIXTURE_DOCUMENT_SCRIPT) }.getOrNull()
            ?: return FixtureDocumentRead(error = "eval-unavailable")
        val obj = runCatching { JSONObject(raw) }
            .getOrElse { return FixtureDocumentRead(error = "eval-not-json") }
        return FixtureDocumentRead.fromJson(obj)
    }

    fun documentIdentity(
        presenter: NativeBrowserPresenter,
        view: WebView,
        openedTabId: String,
    ): DocumentIdentity {
        val current = onMain { presenter.currentView() }
        val state = presenter.state.value
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        return DocumentIdentity(
            viewMatches = current === view,
            selectedTabMatches = state.selectedTabId == openedTabId,
            viewHandle = state.viewHandle,
            viewGeneration = state.viewGeneration,
            documentEpoch = state.documentEpoch,
            nativeReady = state.pageReady && state.shown && state.eligible,
            pageReady = state.pageReady,
            selectedTabId = state.selectedTabId.orEmpty(),
            lastKnownUrl = selected?.url.orEmpty(),
        )
    }

    /**
     * Cold-only host handshake. The test waits while its process is alive for the host's real PTY
     * result file. No production control, grant or credential is involved here; the file carries
     * only the current run id and a fixed PTY report whitelist.
     */
    fun awaitHostPty(budgetMs: Long = 150_000L) {
        val file = File(outDir, "host-pty.json")
        file.delete()
        writeStatus("await-host-pty", "cold=true")
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        var lastReason = "waiting"
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) {
                val body = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (body != null && body.optString("runId") == runId && body.optBoolean("cold")) {
                    val handshake = HostPtyHandshake.fromJson(body, runId)
                    if (handshake.currentRun) {
                        record("hostPtyHandshake", handshake.evidence())
                        if (handshake.ok) return
                        fail("host-pty-handshake-not-ok:${body.optString("error").ifBlank { "report" }}")
                    } else {
                        lastReason = "stale-or-wrong-run"
                    }
                } else {
                    lastReason = "stale-or-wrong-run"
                }
            }
            SystemClock.sleep(250)
        }
        record("hostPtyHandshake", JSONObject()
            .put("runId", runId)
            .put("cold", true)
            .put("ok", false)
            .put("reason", lastReason))
        fail("host-pty-handshake-timeout:$lastReason")
    }

    /** Bounded read-only wait; no reload, tap or grant. Evidence records why when it fails. */
    fun waitFixtureDocument(
        presenter: NativeBrowserPresenter,
        view: WebView,
        openedTabId: String,
        budgetMs: Long = 45_000L,
    ): FixtureDocumentRead {
        var lastDoc: FixtureDocumentRead? = null
        var lastBefore: DocumentIdentity? = null
        var lastAfter: DocumentIdentity? = null
        var lastWhy = "starting"
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val before = documentIdentity(presenter, view, openedTabId)
            val doc = readFixtureDocument(view)
            val after = documentIdentity(presenter, view, openedTabId)
            lastDoc = doc
            lastBefore = before
            lastAfter = after
            lastWhy = when {
                before != after -> "identity-changed"
                !before.viewMatches -> "source-view-changed"
                !before.selectedTabMatches -> "selected-tab-changed"
                !before.nativeReady -> "native-not-ready"
                doc.error != null -> doc.error
                !doc.witnessExact -> if (doc.witnessPresent) "witness-invalid" else "witness-missing"
                doc.href != OpenChamberProviderFixtures.ORIGIN -> "href-not-fixture"
                !doc.readyExact -> "ready-state-${doc.readyState.ifBlank { "missing" }}"
                !doc.domExact -> "fixture-dom-missing"
                else -> ""
            }
            if (lastWhy.isEmpty()) {
                record("fixtureDocument", doc.evidence("ready"))
                record("fixtureIdentity", identityEvidence(before, after))
                return doc
            }
            SystemClock.sleep(250)
        }
        record("fixtureDocument", (lastDoc ?: FixtureDocumentRead(error = "no-read")).evidence(lastWhy))
        record("fixtureIdentity", identityEvidence(lastBefore, lastAfter))
        fail("fixture-document-not-ready:$lastWhy")
    }

    private fun identityEvidence(before: DocumentIdentity?, after: DocumentIdentity?): JSONObject =
        JSONObject()
            .put("before", before?.toJson() ?: JSONObject.NULL)
            .put("after", after?.toJson() ?: JSONObject.NULL)

    fun verifyPng(file: File): Pair<Int, Int> {
        val signature = ByteArray(8)
        runCatching { java.io.DataInputStream(file.inputStream()).use { it.readFully(signature) } }
            .getOrElse { fail("capture-not-png") }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (!signature.contentEquals(png)) fail("capture-not-png")
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrElse { fail("capture-decode-failed") } ?: fail("capture-decode-null")
        if (bitmap.width <= 0 || bitmap.height <= 0) fail("capture-empty")
        record("capture", JSONObject()
            .put("bytes", file.length())
            .put("width", bitmap.width)
            .put("height", bitmap.height)
            .put("sha256", fileSha256(file)))
        val size = bitmap.width to bitmap.height
        bitmap.recycle()
        return size
    }

    fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false)
        tmp.setWritable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    data class InstalledInspection(
        val metadata: RuntimeMetadata,
        val moduleRoot: File,
        val packageVersion: String?,
        val applied: Boolean,
    )

    data class WebSessionAuth(
        val cookies: WebSessionCookies,
        val view: WebView,
        val base: String,
        val header: String,
    )

    data class HostPtyHandshake(
        val runIdMatches: Boolean,
        val cold: Boolean,
        val ok: Boolean,
        val exitCode: Int,
        val error: String,
        val report: JSONObject?,
    ) {
        val currentRun: Boolean get() = runIdMatches && cold

        fun evidence(): JSONObject = JSONObject()
            .put("runIdMatches", runIdMatches)
            .put("cold", cold)
            .put("ok", ok)
            .put("exitCode", exitCode)
            .put("error", error)
            .put("tag", report?.optString("tag").orEmpty())
            .put("standaloneMarkerLine", report?.optBoolean("standaloneMarkerLine") ?: false)
            .put("markersComplete", report?.optBoolean("markersComplete") ?: false)
            .put("doneLine", report?.optBoolean("doneLine") ?: false)
            .put("gitVersion", report?.optString("gitVersion").orEmpty())
            .put("nodeVersion", report?.optString("nodeVersion").orEmpty())
            .put("opencodeVersion", report?.optString("opencodeVersion").orEmpty())

        companion object {
            fun fromJson(body: JSONObject, expectedRunId: String): HostPtyHandshake {
                val report = body.optJSONObject("report")
                val exitCode = body.optInt("exitCode", -1)
                val runIdMatches = body.optString("runId") == expectedRunId
                val cold = body.optBoolean("cold")
                val ok = runIdMatches && cold && body.optBoolean("ok") && exitCode == 0 && reportOk(report)
                return HostPtyHandshake(
                    runIdMatches = runIdMatches,
                    cold = cold,
                    ok = ok,
                    exitCode = exitCode,
                    error = body.optString("error"),
                    report = report,
                )
            }

            private fun reportOk(report: JSONObject?): Boolean =
                report != null &&
                    report.optBoolean("ok") &&
                    report.optBoolean("standaloneMarkerLine") &&
                    report.optBoolean("markersComplete") &&
                    report.optBoolean("doneLine") &&
                    report.optString("gitVersion").isNotBlank() &&
                    report.optString("nodeVersion").isNotBlank() &&
                    report.optString("opencodeVersion").isNotBlank()
        }
    }

    data class FixtureDocumentRead(
        val href: String = "",
        val readyState: String = "",
        val witnessPresent: Boolean = false,
        val witnessId: String = "",
        val notePresent: Boolean = false,
        val applyPresent: Boolean = false,
        val framedText: String = "",
        val error: String? = null,
    ) {
        companion object {
            fun fromJson(obj: JSONObject): FixtureDocumentRead {
                val witnessId = obj.optString("witnessId")
                return FixtureDocumentRead(
                    href = obj.optString("href"),
                    readyState = obj.optString("readyState"),
                    witnessPresent = obj.optBoolean("witnessPresent") && witnessId.isNotEmpty(),
                    witnessId = witnessId,
                    notePresent = obj.optBoolean("notePresent"),
                    applyPresent = obj.optBoolean("applyPresent"),
                    framedText = obj.optString("framedText").trim(),
                )
            }
        }

        val hrefExact: Boolean get() = href == OpenChamberProviderFixtures.ORIGIN
        val readyExact: Boolean get() = readyState == "complete"
        val witnessExact: Boolean get() = witnessPresent && Regex("^[0-9a-f]{32}$").matches(witnessId)
        val domExact: Boolean get() = notePresent && applyPresent && framedText == "framed:top"

        fun evidence(why: String): JSONObject = JSONObject()
            .put("why", why)
            .put("href", href.take(300))
            .put("hrefExact", hrefExact)
            .put("readyState", readyState)
            .put("readyExact", readyExact)
            .put("witnessPresent", witnessPresent)
            .put("witnessLength", witnessId.length)
            .put("witnessExact", witnessExact)
            .put("notePresent", notePresent)
            .put("applyPresent", applyPresent)
            .put("framedText", framedText.take(40))
            .put("domExact", domExact)
            .put("error", error ?: JSONObject.NULL)
    }

    data class DocumentIdentity(
        val viewMatches: Boolean,
        val selectedTabMatches: Boolean,
        val viewHandle: Int,
        val viewGeneration: Long,
        val documentEpoch: Long,
        val nativeReady: Boolean,
        val pageReady: Boolean,
        val selectedTabId: String,
        val lastKnownUrl: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("viewMatches", viewMatches)
            .put("selectedTabMatches", selectedTabMatches)
            .put("viewHandle", viewHandle)
            .put("viewGeneration", viewGeneration)
            .put("documentEpoch", documentEpoch)
            .put("nativeReady", nativeReady)
            .put("pageReady", pageReady)
            .put("selectedTabId", selectedTabId)
            .put("lastKnownUrl", lastKnownUrl.take(300))
    }

    data class HttpReply(val code: Int, val body: String)

    data class LocalTapTarget(
        val label: String,
        val bounds: Rect,
        val windowId: Int,
        val observedAtMs: Long,
        val ownerPackage: String,
        val sourceViewHandle: Int?,
        val phase: String,
        val runId: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("label", label)
            .put("left", bounds.left)
            .put("top", bounds.top)
            .put("right", bounds.right)
            .put("bottom", bounds.bottom)
            .put("windowId", windowId)
            .put("observedAtMs", observedAtMs)
            .put("ownerPackage", ownerPackage)
            .put("enabled", true)
            .put("phase", phase)
            .put("runId", runId)
            .apply { sourceViewHandle?.let { put("sourceViewHandle", it) } }
    }

    data class TapRead(val target: LocalTapTarget?, val reason: String)
}

private const val FIXTURE_DOCUMENT_SCRIPT = """
(function(){
  var witness = window.__acWitness;
  var note = document.querySelector('#note');
  var apply = document.querySelector('button#apply');
  var framed = document.querySelector('#framed');
  return JSON.stringify({
    href: String(location.href),
    readyState: String(document.readyState),
    witnessPresent: !!(witness && typeof witness.documentId === 'string' && witness.documentId.length > 0),
    witnessId: (witness && typeof witness.documentId === 'string') ? witness.documentId : '',
    notePresent: !!(note && note.tagName === 'INPUT'),
    applyPresent: !!(apply && apply.tagName === 'BUTTON'),
    framedText: framed ? String(framed.innerText || '') : ''
  });
})()
"""

/** Real tool endpoint discovery inside the app's own uid; the token is never written or logged. */
internal object LocalAgentTool {
    data class Endpoint(val url: String, val token: String, val pid: Int)

    fun discover(
        ownPid: Int = android.os.Process.myPid(),
        ownUid: Int = android.os.Process.myUid(),
        listPids: () -> List<Int> = {
            File("/proc").listFiles()?.mapNotNull { it.name.toIntOrNull() } ?: emptyList()
        },
        readFile: (File) -> ByteArray? = { file -> runCatching { file.readBytes() }.getOrNull() },
    ): Endpoint? {
        for (pid in listPids()) {
            if (pid == ownPid) continue
            val status = readFile(File("/proc/$pid/status"))?.toString(Charsets.UTF_8) ?: continue
            val uid = Regex("^Uid:\\s*(\\d+)", RegexOption.MULTILINE)
                .find(status)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (uid != ownUid) continue
            val env = readFile(File("/proc/$pid/environ")) ?: continue
            val values = HashMap<String, String>()
            for (part in env.toString(Charsets.UTF_8).split('\u0000')) {
                val index = part.indexOf('=')
                if (index > 0) values[part.substring(0, index)] = part.substring(index + 1)
            }
            val url = values["OPENCHAMBER_AGENT_TOOL_URL"] ?: continue
            val token = values["OPENCHAMBER_AGENT_TOOL_TOKEN"] ?: continue
            if (url.isBlank() || token.isBlank()) continue
            if (!url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) continue
            return Endpoint(url, token, pid)
        }
        return null
    }
}

internal object AgentToolResponse {
    /**
     * Final envelope, mirroring the real service/HTTP shape:
     * `{schemaVersion:1, ok:boolean, action, data}` or `{ok:false, error:{message,kind,code}}`.
     * The OpenCode tool layer may carry the same envelope under `content` (JSON text) or `output`;
     * both are unwrapped here. A missing envelope, a nested failing envelope, or a wrapper with no
     * boolean `ok` is never a success.
     */
    fun decode(raw: String, httpStatus: Int? = null): Decoded {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Decoded(false, null, "invalid-agent-tool-json", "", "", null)
        val envelope = findEnvelope(root, 0)
            ?: return Decoded(false, null, "invalid-agent-tool-envelope", "", "", null)
        val ok = envelope.opt("ok") as? Boolean ?: false
        val data = payload(envelope.opt("data"), 0)
        val action = (envelope.opt("action") as? String)?.takeIf { it.isNotBlank() }
        val error = envelope.opt("error")
        val message = when (error) {
            is JSONObject -> (error.opt("message") as? String).orEmpty()
            is String -> error
            else -> ""
        }
        val kind = ((error as? JSONObject)?.opt("kind") as? String).orEmpty()
        val explicitCode = ((error as? JSONObject)?.opt("code") as? String)?.takeIf { it.isNotBlank() }
            ?: (data?.opt("code") as? String)?.takeIf { it.isNotBlank() }
        // Only a real failed runtime envelope from the agent-tool route (HTTP 200) may map an exact
        // provider code message; never an arbitrary uppercase word, a descriptive sentence, a
        // transport/NoProvider failure, or an HTTP auth rejection.
        val code = explicitCode ?: canonicalCode(ok, message, kind, httpStatus)
        val status = (error as? JSONObject)?.opt("status")?.toString().orEmpty()
        val text = listOf(message, kind, code, status)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        return Decoded(ok, data, text, kind, code, action)
    }

    /** Maintained provider codes only; message must match exactly, not contain the code. */
    private val CANONICAL_PROVIDER_CODES = setOf(
        ProviderCodes.AUTHORIZATION_REQUIRED,
        ProviderCodes.REF_EXPIRED,
        ProviderCodes.BROWSER_PAGE_UNAVAILABLE,
    )

    private fun canonicalCode(ok: Boolean, message: String, kind: String, httpStatus: Int?): String {
        if (ok || kind != "runtime" || httpStatus != 200) return ""
        return message.takeIf { it in CANONICAL_PROVIDER_CODES }.orEmpty()
    }

    private fun findEnvelope(node: Any?, depth: Int): JSONObject? {
        if (node == null || depth > 5) return null
        return when (node) {
            is JSONObject -> {
                if (node.opt("ok") is Boolean) node
                else listOf("data", "content", "output").firstNotNullOfOrNull { key ->
                    findEnvelope(node.opt(key), depth + 1)
                }
            }
            is String -> {
                val trimmed = node.trimStart()
                if (!trimmed.startsWith("{")) null
                else runCatching { JSONObject(node) }.getOrNull()?.let { findEnvelope(it, depth + 1) }
            }
            else -> null
        }
    }

    private fun payload(node: Any?, depth: Int): JSONObject? {
        if (node == null || depth > 5) return null
        return when (node) {
            is JSONObject -> {
                if (node.opt("ok") is Boolean) payload(node.opt("data"), depth + 1)
                else {
                    val content = node.opt("content")
                    val parsedContent = (content as? String)?.trimStart()?.takeIf { it.startsWith("{") }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (parsedContent != null) payload(parsedContent, depth + 1)
                    else {
                        val output = node.opt("output")
                        if (output != null) payload(output, depth + 1) else node
                    }
                }
            }
            is String -> {
                val trimmed = node.trimStart()
                if (!trimmed.startsWith("{")) null
                else runCatching { JSONObject(node) }.getOrNull()?.let { payload(it, depth + 1) }
            }
            else -> null
        }
    }

    data class Decoded(
        val ok: Boolean,
        val data: JSONObject?,
        val errorText: String,
        val errorKind: String,
        val errorCode: String,
        val action: String?,
    )
}

internal class LocalAgentClient(
    private val run: LocalProviderRun,
    private val endpoint: LocalAgentTool.Endpoint,
    private val sessionId: String,
) {
    fun call(action: String, input: JSONObject = JSONObject()): AgentReply {
        if (!endpoint.url.startsWith("http://127.0.0.1:") && !endpoint.url.startsWith("http://localhost:")) {
            run.fail("agent-tool-url-not-loopback")
        }
        val actionInput = JSONObject().put("action", action)
        input.keys().forEach { key -> actionInput.put(key, input.get(key)) }
        val payload = JSONObject()
            .put("tool", "openchamber_web")
            .put("sessionID", sessionId)
            .put("input", actionInput)
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Authorization", "Bearer ${endpoint.token}")
            .header("Connection", "close")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val reply = run.service<OkHttpClient>().newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                val text = response.body?.string().orEmpty()
                if (text.length > 262_144) run.fail("agent-response-too-large")
                response.code to text
            }
        val decoded = AgentToolResponse.decode(reply.second, reply.first)
        if (decoded.action != null && decoded.action != action) {
            run.fail("agent-action-mismatch: expected $action got ${decoded.action}")
        }
        return AgentReply(
            status = reply.first,
            ok = decoded.ok,
            data = decoded.data,
            error = decoded.errorText,
            errorKind = decoded.errorKind,
            errorCode = decoded.errorCode,
        )
    }

    data class AgentReply(
        val status: Int,
        val ok: Boolean,
        val data: JSONObject?,
        val error: String,
        val errorKind: String = "",
        val errorCode: String = "",
    )
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun fileSha256(file: File): String =
    file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
