package io.andchamber.app.runtime.storage

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.SharedStorageController
import io.andchamber.app.runtime.SharedStorageState
import io.andchamber.app.runtime.SharedStorageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Persistence and platform facts, injected so the controller is unit-tested without Android. */
interface SharedStoragePrefs {
    fun enabled(): Boolean

    fun setEnabled(enabled: Boolean)
}

/** Platform facts: whether all-files access is granted, and whether the shared root can be used. */
fun interface SharedStoragePermission {
    fun granted(): Boolean
}

/**
 * The shared-storage feature: persists the switch, decides the status, prepares the shared folders
 * on activation, and remembers the binds the running server was started with (for restartRequired).
 */
class SharedStorageControllerImpl(
    private val paths: RuntimePaths,
    /** `/storage/emulated/0/AndChamber`; injected so tests use a temporary directory. */
    private val hostRoot: File,
    private val prefs: SharedStoragePrefs,
    private val permission: SharedStoragePermission,
) : SharedStorageController {
    private val mutableState = MutableStateFlow(compute(startedBinds = null))
    override val state: StateFlow<SharedStorageState> = mutableState.asStateFlow()

    /** The status and binds of the running server; null when no server was started through us. */
    @Volatile private var startedBinds: Pair<SharedStorageStatus, List<String>>? = null

    override fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        refresh()
    }

    override fun refreshPermission() = refresh()

    /**
     * The binds for the next server start. Prepares the shared folders when the decision is Active:
     * creates them, seeds the config, and writes the README. Failure to prepare falls back to private.
     */
    fun prepareForStart(): GuestBinds {
        var status = decideStatus()
        if (status == SharedStorageStatus.Active) {
            // Shared storage is a convenience: if it can't be prepared (FUSE refused, volume gone),
            // start on private storage rather than failing the whole server start.
            val prepared = runCatching { prepareShared() }
            if (prepared.isFailure) {
                android.util.Log.w("AndChamber", "Shared storage unavailable, using private", prepared.exceptionOrNull())
                status = SharedStorageStatus.NeedsPermission
            }
        }
        val binds = SharedStorageDecision.binds(status, paths.home, paths.workspace, hostRoot)
        // Placeholders must exist before PRoot binds a file onto them, in any status that binds them.
        if (binds.nestedConfig.isNotEmpty()) {
            SharedStorageDecision.placeholdersNeeded(paths.home).forEach { it.parentFile?.mkdirs(); it.createNewFile() }
        }
        return binds
    }

    /** Called by the server once a start has been issued with [binds]. */
    fun recordStarted(binds: GuestBinds) {
        val status = decideStatus()
        startedBinds = status to binds.specs()
        refresh()
    }

    /** Called when the server stops: nothing is running, so nothing needs a restart. */
    fun recordStopped() {
        startedBinds = null
        refresh()
    }

    private fun refresh() {
        mutableState.value = compute(startedBinds)
    }

    private fun compute(startedBinds: Pair<SharedStorageStatus, List<String>>?): SharedStorageState {
        val status = decideStatus()
        val current = status to SharedStorageDecision.binds(status, paths.home, paths.workspace, hostRoot).specs()
        return SharedStorageState(
            status = status,
            hostRoot = hostRoot.absolutePath,
            restartRequired = SharedStorageDecision.restartRequired(startedBinds, current),
        )
    }

    private fun decideStatus(): SharedStorageStatus =
        SharedStorageDecision.status(
            enabled = prefs.enabled(),
            permissionGranted = permission.granted(),
            sharedRootUsable = sharedRootUsable(),
        )

    /** The shared root can hold the folders: it exists or can be created, and is writable. */
    private fun sharedRootUsable(): Boolean =
        runCatching {
            hostRoot.mkdirs()
            hostRoot.isDirectory && hostRoot.canWrite()
        }.getOrDefault(false)

    private fun prepareShared() {
        val projects = SharedStorageLayout.projectsDir(hostRoot).apply { mkdirs() }
        check(projects.isDirectory) { "cannot create ${projects.path}" }
        SharedStorageLayout.seedShared(
            sharedDir = SharedStorageLayout.opencodeDir(hostRoot),
            privateDir = File(paths.home, ".config/opencode"),
        )
        File(hostRoot, SharedStorageLayout.README).writeText(SharedStorageLayout.readme(hostRoot))
    }
}
