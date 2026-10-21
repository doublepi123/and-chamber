package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.UserFacingException
import io.andchamber.app.runtime.manifest.RuntimeArchitecture
import io.andchamber.app.runtime.manifest.RuntimeManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The request gate and per-operation lifecycle of a provisioned provider artifact, exercised
 * through the real [RuntimeInstallerImpl]. The suite is a sentinel: reaching it proves the gate
 * let the operation continue, so a stock request is never restricted by this code.
 */
class RuntimeInstallerProviderGateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val report = object : InstallReporter {
        override fun step(
            step: io.andchamber.app.runtime.InstallStep,
            progress: Float?,
            detail: String?,
            messageKey: MessageKey?,
        ) = Unit

        override fun log(line: String) = Unit
    }

    @Test
    fun `a provisioned artifact rejects a 2_5_0 install before any download or staging`() {
        val artifact = artifactDirectory()
        var suiteCalls = 0
        val installer = installer(suiteCalls = { suiteCalls++ }) { manifest(artifact) to artifact }

        val error = expectUserFacing {
            runBlocking { installer.install(report, PackageVersions(openChamber = "2.5.0", openCode = "2.0.32")) }
        }

        assertEquals(MessageKey.OpenChamberInstallFailed, error.key)
        assertTrue(error.message.orEmpty().contains("2.5.0"))
        assertEquals(0, suiteCalls)
        assertFalse(RuntimePaths.under(folder.root).staging.exists())
        assertFalse(RuntimePaths.under(folder.root).cache.exists())
    }

    @Test
    fun `a provisioned artifact rejects a 2_5_0 update before the staging copy`() {
        val artifact = artifactDirectory()
        var suiteCalls = 0
        val installer = installer(suiteCalls = { suiteCalls++ }) { manifest(artifact) to artifact }

        expectUserFacing {
            runBlocking { installer.update(report, PackageVersions(openChamber = "2.5.0", openCode = "2.0.32")) }
        }

        assertEquals(0, suiteCalls)
        assertFalse(RuntimePaths.under(folder.root).staging.exists())
    }

    @Test
    fun `a declared but unreadable artifact is invalid, not absent`() {
        var suiteCalls = 0
        val installer = installer(suiteCalls = { suiteCalls++ }) { throw IOException("corrupt asset") }

        val error = expectUserFacing { runBlocking { installer.install(report, null) } }

        assertEquals(MessageKey.OpenChamberInstallFailed, error.key)
        assertEquals(0, suiteCalls)
        assertFalse(RuntimePaths.under(folder.root).staging.exists())
    }

    @Test
    fun `a declared artifact with bad pins is invalid before download and its source is cleaned`() {
        val artifact = artifactDirectory()
        var suiteCalls = 0
        val installer = installer(suiteCalls = { suiteCalls++ }) {
            manifest(artifact).copy(sourcePatchSha256 = "0".repeat(64)) to artifact
        }

        expectUserFacing { runBlocking { installer.install(report, null) } }

        assertEquals(0, suiteCalls)
        assertFalse(artifact.exists())
        assertFalse(RuntimePaths.under(folder.root).staging.exists())
    }

    @Test
    fun `a provisioned 2_4_0 install loads the artifact once and cleans it after the operation`() {
        val artifact = artifactDirectory()
        var sourceCalls = 0
        val installer = installer(suiteCalls = { throw IllegalStateException("suite reached") }) {
            sourceCalls++
            manifest(artifact) to artifact
        }

        val error = runCatching {
            runBlocking { installer.install(report, PackageVersions(openChamber = "2.4.0", openCode = "2.0.32")) }
        }.exceptionOrNull()

        assertEquals("suite reached", error?.message)
        assertEquals(1, sourceCalls)
        assertFalse("the operation must clean its materialized source", artifact.exists())
    }

    @Test
    fun `stock install and update keep main behaviour for a 2_5_0 request`() {
        // No provisioned artifact: the gate must not reject or downgrade anything.
        var suiteCalls = 0
        val installer = installer(suiteCalls = { suiteCalls++; throw IllegalStateException("suite reached") }) { null }

        val installError = runCatching {
            runBlocking { installer.install(report, PackageVersions(openChamber = "2.5.0", openCode = "2.0.32")) }
        }.exceptionOrNull()
        assertEquals("suite reached", installError?.message)

        val updateError = runCatching {
            runBlocking { installer.update(report, PackageVersions(openChamber = "2.5.0", openCode = "2.0.32")) }
        }.exceptionOrNull()
        assertEquals("suite reached", updateError?.message)
        assertEquals(2, suiteCalls)
    }

    private fun installer(
        suiteCalls: () -> Unit,
        source: () -> Pair<OpenChamberProviderArtifact.LaneManifest, File>?,
    ): RuntimeInstallerImpl =
        RuntimeInstallerImpl(
            paths = RuntimePaths.under(folder.root),
            abi = "x86_64",
            manifest = runtimeManifest(),
            suite = {
                suiteCalls()
                throw IllegalStateException("suite must not be reached")
            },
            downloader = VerifiedRuntimeDownloader(),
            shimAsset = { "" },
            providerArtifactSource = source,
        )

    private fun expectUserFacing(block: () -> Unit): UserFacingException {
        try {
            block()
            fail("expected a UserFacingException")
        } catch (error: UserFacingException) {
            return error
        }
        error("unreachable")
    }

    private fun artifactDirectory(): File {
        val dir = folder.newFolder("provider-artifact")
        File(dir, "packages/web/server/index.js").apply { parentFile.mkdirs(); writeText("server") }
        File(dir, "packages/web/dist/index.html").apply { parentFile.mkdirs(); writeText("dist") }
        return dir
    }

    private fun manifest(dir: File): OpenChamberProviderArtifact.LaneManifest =
        OpenChamberProviderArtifact.LaneManifest(
            packageName = "@openchamber/web",
            version = "2.4.0",
            baseCommit = OpenChamberProviderArtifact.REQUIRED_COMMIT,
            sourcePatchSha256 = OpenChamberProviderArtifact.REQUIRED_PATCH_SHA256,
            server = listOf(pin("packages/web/server/index.js", File(dir, "packages/web/server/index.js"))),
            dist = listOf(pin("packages/web/dist/index.html", File(dir, "packages/web/dist/index.html"))),
        )

    private fun pin(path: String, file: File): OpenChamberProviderArtifact.LaneFile =
        OpenChamberProviderArtifact.LaneFile(path, sha(file.readBytes()), file.length())

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun runtimeManifest(): RuntimeManifest =
        RuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "test",
            alpineVersion = "3.24",
            openChamberVersion = "2.4.0",
            openCodeVersion = "2.0.32",
            port = 3000,
            architectures = mapOf(
                "x86_64" to RuntimeArchitecture(
                    alpineUrl = "https://example.com/alpine.tar.gz",
                    alpineSha256 = "a".repeat(64),
                ),
            ),
        )
}
