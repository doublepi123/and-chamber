package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ProviderArtifactPolicyTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a provisioned artifact only serves its own 2_4_0 baseline`() {
        val manifest = manifest()

        assertNull(ProviderArtifactPolicy.requestRejection(manifest, "2.4.0"))
        assertEquals(
            "requested @openchamber/web@2.5.0 is not the artifact's 2.4.0 baseline",
            ProviderArtifactPolicy.requestRejection(manifest, "2.5.0"),
        )
        try {
            ProviderArtifactPolicy.requireDeployable(manifest, "2.5.0")
            fail("2.5.0 must not deploy the 2.4.0 artifact")
        } catch (error: UserFacingException) {
            assertEquals(MessageKey.OpenChamberInstallFailed, error.key)
        }
    }

    @Test
    fun `an artifact built for another baseline, commit or patch is rejected`() {
        assertEquals(
            "unexpected base version",
            OpenChamberProviderArtifact.manifestRejection(manifest().copy(version = "2.5.0")),
        )
        assertEquals(
            "unexpected base commit",
            OpenChamberProviderArtifact.manifestRejection(manifest().copy(baseCommit = "0".repeat(40))),
        )
        assertEquals(
            "unexpected source patch",
            OpenChamberProviderArtifact.manifestRejection(manifest().copy(sourcePatchSha256 = "0".repeat(64))),
        )
    }

    @Test
    fun `repair of a newer installed package is rejected instead of downgraded`() {
        // The controller's original repair rule: per package, max(installed, pinned).
        val installed = "2.5.0"
        val pinned = "2.4.0"
        val repairTarget = if (OpenChamberInstaller.compareVersions(installed, pinned) >= 0) installed else pinned

        assertEquals("2.5.0", repairTarget)
        assertTrue(ProviderArtifactPolicy.requestRejection(manifest(), repairTarget) != null)
    }

    private fun manifest(): OpenChamberProviderArtifact.LaneManifest =
        OpenChamberProviderArtifact.LaneManifest(
            packageName = "@openchamber/web",
            version = "2.4.0",
            baseCommit = OpenChamberProviderArtifact.REQUIRED_COMMIT,
            sourcePatchSha256 = OpenChamberProviderArtifact.REQUIRED_PATCH_SHA256,
            server = listOf(pin("packages/web/server/index.js", "server")),
            dist = listOf(pin("packages/web/dist/index.html", "dist")),
        )

    private fun pin(path: String, content: String): OpenChamberProviderArtifact.LaneFile {
        val bytes = content.toByteArray()
        return OpenChamberProviderArtifact.LaneFile(
            path,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(),
        )
    }
}
