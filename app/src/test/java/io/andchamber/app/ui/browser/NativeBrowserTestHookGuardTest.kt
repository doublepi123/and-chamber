package io.andchamber.app.ui.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The presenter keeps a few test entry points in main source. They must refuse to work outside a
 * debug build, so a release APK cannot turn on a test host, a test page decorator or a synthetic
 * probe. The guard is a `check(BuildConfig.DEBUG)` at the top of every entry point; a JVM unit test
 * cannot compile with the release flag itself, so this checks the source that ships in main.
 */
class NativeBrowserTestHookGuardTest {
    @Test
    fun `every test entry point starts with a debug-only check`() {
        val source = File(SOURCE).readText()
        for (name in ENTRY_POINTS) {
            val start = source.indexOf("fun $name(")
            assertTrue("missing $name", start >= 0)
            val body = source.substring(start, (start + HEAD_CHARS).coerceAtMost(source.length))
            val guard = body.indexOf("check(BuildConfig.DEBUG)")
            assertTrue("$name is not guarded by check(BuildConfig.DEBUG)", guard >= 0)
            val firstBrace = body.indexOf('{')
            assertTrue("$name has no body", firstBrace >= 0)
            assertTrue("$name runs something before the debug guard", guard > firstBrace)
        }
    }

    private companion object {
        /** Gradle runs unit tests in the module directory. */
        const val SOURCE = "src/main/java/io/andchamber/app/ui/browser/NativeBrowserPresenter.kt"

        /** Enough to cover a full signature and its first statement. */
        const val HEAD_CHARS = 600

        val ENTRY_POINTS =
            listOf(
                "attachHostForTest",
                "installPageDecorator",
                "beginForTest",
                "acceptProbeForTest",
            )
    }
}
