package io.andchamber.app.phone.android

import io.andchamber.app.ui.phone.PhoneTargetVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker is launcher apps only, sorted, deduplicated and bounded. The policy refusals themselves
 * stay in the controller; here only the list shape is pinned.
 */
class PhoneTargetCatalogTest {
    @Test
    fun `deduplicates packages, sorts by label and keeps the host visible`() {
        val raw =
            listOf(
                RawLauncherTarget("Zebra", "com.example.zebra", system = false),
                RawLauncherTarget("alarm", "com.example.alarm", system = false),
                RawLauncherTarget("Zebra again", "com.example.zebra", system = false),
                RawLauncherTarget("AndChamber", "io.andchamber.app", system = false),
                RawLauncherTarget("Settings", "com.android.settings", system = true),
            )
        val entries = PhoneTargetCatalog.entries(raw, limit = 100)
        assertEquals(
            listOf("com.example.alarm", "io.andchamber.app", "com.android.settings", "com.example.zebra"),
            entries.map { it.packageName },
        )
        val zebra = entries.first { it.packageName == "com.example.zebra" }
        assertEquals("Zebra", zebra.label)
        val settings = entries.first { it.packageName == "com.android.settings" }
        assertTrue(settings.system)
        assertFalse(entries.first { it.packageName == "io.andchamber.app" }.system)
    }

    @Test
    fun `bounds the list and drops blank packages`() {
        val raw =
            (1..50).map { RawLauncherTarget("App $it", "com.example.app$it", system = false) } +
                RawLauncherTarget("blank", "", system = false)
        val entries = PhoneTargetCatalog.entries(raw, limit = 10)
        assertEquals(10, entries.size)
        assertTrue(entries.none { it.packageName.isBlank() })
    }

    @Test
    fun `a missing label falls back to the package name`() {
        val entries =
            PhoneTargetCatalog.entries(
                listOf(RawLauncherTarget("", "com.example.notes", system = false)),
                limit = 10,
            )
        assertEquals("com.example.notes", entries.single().label)
    }

    @Test
    fun `blank labels and duplicate packages do not change the allowed list`() {
        // Two entries for the same package resolve to one row, so the person cannot pick a shadowed app.
        val entries =
            PhoneTargetCatalog.entries(
                listOf(
                    RawLauncherTarget("Notes", "com.example.notes", system = false),
                    RawLauncherTarget("Notes old", "com.example.notes", system = false),
                ),
                limit = 5,
            )
        assertEquals(1, entries.size)
        assertEquals("Notes", entries.single().label)
    }

    @Test
    fun `ordinary preinstalled apps are allowed while authorization surfaces stay blocked`() {
        val raw =
            listOf(
                RawLauncherTarget("Notes", "com.example.notes", system = false),
                RawLauncherTarget("Maps", "com.google.android.apps.maps", system = true),
                RawLauncherTarget("Documents", "com.android.documentsui", system = true),
                RawLauncherTarget("Settings", "com.android.settings", system = true),
                RawLauncherTarget("System UI", "com.android.systemui", system = true),
                RawLauncherTarget("Installer", "com.android.packageinstaller", system = true),
                RawLauncherTarget("Lock screen", "com.android.keyguard", system = true),
                RawLauncherTarget("AndChamber", "io.andchamber.app", system = false),
            )
        val verdicts = PhoneTargetCatalog.entries(raw, limit = 50).associate { it.packageName to it.verdict }
        assertEquals(PhoneTargetVerdict.Allowed, verdicts["com.example.notes"])
        assertEquals(PhoneTargetVerdict.Allowed, verdicts["com.google.android.apps.maps"])
        assertEquals(PhoneTargetVerdict.Allowed, verdicts["com.android.documentsui"])
        assertEquals(PhoneTargetVerdict.Sensitive, verdicts["com.android.settings"])
        assertEquals(PhoneTargetVerdict.Sensitive, verdicts["com.android.systemui"])
        assertEquals(PhoneTargetVerdict.Sensitive, verdicts["com.android.packageinstaller"])
        assertEquals(PhoneTargetVerdict.Sensitive, verdicts["com.android.keyguard"])
        assertEquals(PhoneTargetVerdict.Host, verdicts["io.andchamber.app"])
    }
}
