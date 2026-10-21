package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.UserFacingException
import io.andchamber.app.runtime.fs.deleteTree
import java.io.File

/**
 * One operation's materialized provider artifact. It is loaded once per install/update, validated
 * before any download, copy or npm work, used by the single deployment in that operation, and
 * deleted by the caller in a `finally` block (never before the deployer has read it).
 */
class LoadedProviderArtifact(
    val manifest: OpenChamberProviderArtifact.LaneManifest,
    val source: File,
) : AutoCloseable {
    override fun close() {
        deleteTree(source)
    }
}

/**
 * Request-side compatibility gate for a provisioned provider artifact. Null means the app has no
 * provisioned artifact (stock npm behaviour, unchanged and not restricted). A non-null artifact
 * may only serve exactly its own baseline ([OpenChamberProviderArtifact.PACKAGE_VERSION]) with the
 * pinned commit and patch. Nothing here modifies the target package or downgrades a request.
 */
internal object ProviderArtifactPolicy {
    fun requestRejection(
        manifest: OpenChamberProviderArtifact.LaneManifest,
        requestedOpenChamber: String,
    ): String? {
        if (requestedOpenChamber != OpenChamberProviderArtifact.PACKAGE_VERSION) {
            return "requested @openchamber/web@$requestedOpenChamber is not the artifact's 2.4.0 baseline"
        }
        if (manifest.version != requestedOpenChamber) {
            return "artifact is built for @openchamber/web@${manifest.version}, not $requestedOpenChamber"
        }
        return OpenChamberProviderArtifact.manifestRejection(manifest)
    }

    /**
     * Throws a typed, user-facing failure instead of silently falling back to stock npm or
     * downgrading the request. The message carries versions and pin labels, never local paths.
     */
    fun requireDeployable(
        manifest: OpenChamberProviderArtifact.LaneManifest,
        requestedOpenChamber: String,
    ) {
        requestRejection(manifest, requestedOpenChamber)?.let { reason ->
            throw UserFacingException(
                MessageKey.OpenChamberInstallFailed,
                "The bundled OpenChamber provider artifact is incompatible: $reason",
            )
        }
    }
}
