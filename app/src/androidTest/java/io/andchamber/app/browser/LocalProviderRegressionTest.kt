package io.andchamber.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Long-lived host-driven regression producer. The host lane sends fixed tool/device commands and a
 * finish summary; this test owns the production runtime, chat host and page decorator for its whole
 * lifetime, so it must not finish early and let the instrumentation process kill the runtime.
 *
 * The page decorator is installed before the first browser page: it only wraps the original page
 * with the fixed example.com fixture client and a read-only lifecycle recorder. The chat WebView,
 * profile/auth, production Node server/SSE/claim/result and grant flow stay production.
 */
@RunWith(AndroidJUnit4::class)
class LocalProviderRegressionTest {
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val runId = args.getString("runId")?.trim().orEmpty()

    @Test
    fun runGuestMatrix() {
        if (!Regex("^[A-Za-z0-9._:-]{8,80}$").matches(runId)) {
            fail("regression-run-id-missing-or-invalid")
        }
        val parsed = LocalProviderRegressionProtocol.parseCases(args.getString("cases"))
        if (parsed is LocalProviderRegressionProtocol.CasesParsed.Rejected) {
            fail("regression-cases-${parsed.reason}")
        }
        val request = (parsed as LocalProviderRegressionProtocol.CasesParsed.Ok).request
        val session = LocalProviderRegressionSession(
            context,
            instrumentation,
            runId,
            request.cases,
            request.scope,
            request.partial,
        )
        session.boot()
        session.runCommands()
    }
}
