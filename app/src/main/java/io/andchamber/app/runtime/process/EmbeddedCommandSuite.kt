package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import java.io.File

/**
 * Resolves the PRoot launcher and its loaders. They are packaged as jniLibs (see
 * scripts/prepare_android_runtime_native_libs.py), so they live in the app's native library dir.
 * Ported from AndCode's EmbeddedCommandSuite (MIT).
 */
class EmbeddedCommandSuite(
    private val nativeLibraryDirectory: File,
    private val paths: RuntimePaths,
) {
    data class Paths(
        val home: File,
        val tmp: File,
        val nativeLibraryDirectory: File,
        val proot: File,
        val loader: File,
        val loader32: File,
    ) {
        fun environment(): Map<String, String> =
            mapOf(
                "HOME" to home.absolutePath,
                "TMPDIR" to tmp.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
                "LD_LIBRARY_PATH" to nativeLibraryDirectory.absolutePath,
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_LOADER_32" to loader32.absolutePath,
            )
    }

    fun resolve(): Paths {
        val stateRoot = paths.commandSuite.apply { mkdirs() }
        val home = File(stateRoot, "home").apply { mkdirs() }
        val tmp = File(stateRoot, "tmp").apply { mkdirs() }
        val proot = File(nativeLibraryDirectory, PROOT_LIBRARY_NAME)
        val loader = File(nativeLibraryDirectory, LOADER_LIBRARY_NAME)
        val loader32 = File(nativeLibraryDirectory, LOADER32_LIBRARY_NAME)
        require(proot.isFile && proot.canExecute()) { "Embedded PRoot is unavailable" }
        require(loader.isFile && loader.canExecute()) { "Embedded PRoot loader is unavailable" }
        require(loader32.isFile && loader32.canExecute()) { "Embedded PRoot 32-bit loader is unavailable" }
        return Paths(home, tmp, nativeLibraryDirectory, proot, loader, loader32)
    }

    companion object {
        const val PROOT_LIBRARY_NAME = "libandchamber_android_proot.so"
        const val LOADER_LIBRARY_NAME = "libandchamber_android_proot_loader.so"
        const val LOADER32_LIBRARY_NAME = "libandchamber_android_proot_loader32.so"
    }
}
