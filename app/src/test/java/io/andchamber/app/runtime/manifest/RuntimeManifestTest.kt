package io.andchamber.app.runtime.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeManifestTest {
    private val architecture =
        RuntimeArchitecture(
            alpineUrl = "https://example.com/alpine.tar.gz",
            alpineSha256 = "a".repeat(64),
        )

    private fun manifest(arch: RuntimeArchitecture = architecture) =
        RuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "2026.10.18.1",
            alpineVersion = "3.24.1",
            openChamberVersion = "2.4.0",
            openCodeVersion = "2.0.32",
            port = 3000,
            architectures = mapOf("arm64-v8a" to arch),
        )

    @Test
    fun `valid manifest resolves architecture`() {
        manifest().validate()
        assertEquals(architecture, manifest().architecture("arm64-v8a"))
    }

    @Test
    fun `manifest rejects insecure rootfs URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            architecture.copy(alpineUrl = "http://example.com/alpine.tar.gz").validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects malformed sha256`() {
        assertThrows(IllegalArgumentException::class.java) {
            architecture.copy(alpineSha256 = "not-a-hash").validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects unsupported schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(schemaVersion = 2).validate()
        }
    }

    @Test
    fun `reader parses the bundled json shape`() {
        val json =
            """
            {"schemaVersion":1,"runtimeVersion":"r","alpineVersion":"3.24.1","openChamberVersion":"2.4.0",
             "openCodeVersion":"2.0.32","port":3000,
             "architectures":{"x86_64":{"alpineUrl":"https://e/x.tar.gz","alpineSha256":"${"b".repeat(64)}"}}}
            """.trimIndent()
        val parsed = RuntimeManifestReader { json }.read()
        assertEquals("2.4.0", parsed.openChamberVersion)
        assertEquals(3000, parsed.port)
    }
}
