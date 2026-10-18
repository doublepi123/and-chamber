package io.andchamber.app.ui.preview

import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory runtime for previews. Actions jump straight to the resulting state. */
internal class FakeRuntimeController(
    initial: RuntimeState = RuntimeState.Stopped,
    info: RuntimeInfo? = PreviewData.info,
    log: List<String> = PreviewData.log,
) : LocalRuntimeController {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val mutableLog = MutableStateFlow(log)
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow(info)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    override fun install() {
        mutableState.value = RuntimeState.Installing(InstallStep.DownloadRootfs, 0.1f, "alpine-minirootfs")
    }

    override fun start() {
        mutableState.value = PreviewData.running
    }

    override fun stop() {
        mutableState.value = RuntimeState.Stopped
    }

    override fun repair() = install()

    override fun uninstall() {
        mutableInfo.value = null
        mutableState.value = RuntimeState.NotInstalled
    }
}

internal class FakeRemoteServerStore(
    initial: List<ServerTarget.Remote> = PreviewData.servers,
) : RemoteServerStore {
    private val mutable = MutableStateFlow(initial)
    override val servers: StateFlow<List<ServerTarget.Remote>> = mutable.asStateFlow()

    override fun upsert(server: ServerTarget.Remote) {
        mutable.value = mutable.value.filterNot { it.id == server.id } + server
    }

    override fun remove(id: String) {
        mutable.value = mutable.value.filterNot { it.id == id }
    }
}

/** Answers with [result], or never answers when it is null (to preview the connecting state). */
internal class FakeWebSessionAuthenticator(
    private val result: WebSessionResult? = null,
) : WebSessionAuthenticator {
    override suspend fun prepare(target: ServerTarget): WebSessionResult = result ?: awaitCancellation()
}

internal object PreviewData {
    val info =
        RuntimeInfo(
            abi = "arm64-v8a",
            alpineVersion = "3.24.1",
            openChamberVersion = "2.4.0",
            openCodeVersion = "2.0.32",
            nodeVersion = "24.14.0",
            diskUsageBytes = 1_262_485_504L,
            freeSpaceBytes = 38_654_705_664L,
        )

    val running =
        RuntimeState.Running(
            url = "http://127.0.0.1:3000",
            port = 3000,
            sinceEpochMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000L - 14 * 60 * 1000L,
        )

    val installing =
        RuntimeState.Installing(
            step = InstallStep.InstallPackages,
            progress = 0.62f,
            detail = "(38/61) Installing nodejs (24.14.0-r0)",
        )

    val servers =
        listOf(
            ServerTarget.Remote(
                id = "workstation",
                displayName = "Workstation",
                baseUrl = "http://192.168.1.20:3000",
                uiPassword = "secret",
            ),
            ServerTarget.Remote(
                id = "laptop",
                displayName = "Laptop (Tailscale)",
                baseUrl = "http://laptop.tail0c1d2.ts.net:3000",
                uiPassword = null,
            ),
        )

    val log =
        listOf(
            "$ apk add --no-cache nodejs npm git bash curl ripgrep",
            "fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz",
            "(36/61) Installing libstdc++ (14.2.0-r6)",
            "(37/61) Installing ca-certificates (20250911-r0)",
            "(38/61) Installing nodejs (24.14.0-r0)",
            "WARN: low battery, keep the phone plugged in",
            "OpenChamber listening on http://127.0.0.1:3000",
        )
}
