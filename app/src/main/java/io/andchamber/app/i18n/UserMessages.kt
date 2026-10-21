package io.andchamber.app.i18n

import android.content.Context
import androidx.annotation.StringRes
import io.andchamber.app.R
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimeState

/**
 * The one place that turns runtime keys into localised text. The UI and the notifications both go
 * through here, so a headline reads the same everywhere.
 */
object UserMessages {
    @StringRes
    fun headline(key: MessageKey): Int =
        when (key) {
            MessageKey.MirrorsUnreachable -> R.string.msg_mirrors_unreachable
            MessageKey.PackagesFailed -> R.string.msg_packages_failed
            MessageKey.OpenChamberInstallFailed -> R.string.msg_openchamber_install_failed
            MessageKey.NativeBuildFailed -> R.string.msg_native_build_failed
            MessageKey.PtySmokeFailed -> R.string.msg_pty_smoke_failed
            MessageKey.PortInUse -> R.string.msg_port_in_use
            MessageKey.OpenCodeStartFailed -> R.string.msg_opencode_start_failed
            MessageKey.ExitedDuringStart -> R.string.msg_exited_during_start
            MessageKey.NotListeningInTime -> R.string.msg_not_listening_in_time
            MessageKey.NotReadyInTime -> R.string.msg_not_ready_in_time
            MessageKey.KeptStopping -> R.string.msg_kept_stopping
            MessageKey.StoppedUnexpectedly -> R.string.msg_stopped_unexpectedly
            MessageKey.RestartFailed -> R.string.msg_restart_failed
            MessageKey.EmbeddedProotMissing -> R.string.msg_embedded_proot_missing
            MessageKey.Generic -> R.string.msg_generic
            MessageKey.ProgressUnpacking -> R.string.msg_progress_unpacking
            MessageKey.ProgressInstallingPackages -> R.string.msg_progress_installing_packages
            MessageKey.ProgressInstallingOpenChamber -> R.string.msg_progress_installing_openchamber
            MessageKey.ProgressBuildingNative -> R.string.msg_progress_building_native
            MessageKey.ProgressVerifying -> R.string.msg_progress_verifying
            MessageKey.ProgressActivating -> R.string.msg_progress_activating
            MessageKey.ProgressDone -> R.string.msg_progress_done
            MessageKey.ProgressCopyingEnvironment -> R.string.msg_progress_copying_environment
            MessageKey.UpdateNotEnoughSpace -> R.string.msg_update_not_enough_space
        }

    /**
     * The localised headline for a failed state: the key's text when there is one, otherwise the
     * English technical reason (never empty, so the user always sees something).
     */
    fun failureText(
        context: Context,
        failed: RuntimeState.Failed,
    ): String =
        failed.messageKey?.let { context.getString(headline(it)) }
            ?: failed.reason.ifBlank { context.getString(R.string.msg_generic) }

    /** Localised text for an install step's label. */
    @StringRes
    fun stepLabel(step: InstallStep): Int =
        when (step) {
            InstallStep.DownloadRootfs -> R.string.step_download_rootfs
            InstallStep.ExtractRootfs -> R.string.step_extract_rootfs
            InstallStep.InstallPackages -> R.string.step_install_packages
            InstallStep.CopyEnvironment -> R.string.step_copy_environment
            InstallStep.InstallOpenChamber -> R.string.step_install_openchamber
            InstallStep.BuildNativeModules -> R.string.step_build_native_modules
            InstallStep.Verify -> R.string.step_verify
        }
}
