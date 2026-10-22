package io.andchamber.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Long-lived product coverage producer. It uses the same production runtime/console/page decorator
 * and status/result protocol as LocalProviderRegressionTest, but selects only the finite product
 * catalog names from the packaged asset. The existing eight lifecycle names and their flows are
 * unchanged.
 */
@RunWith(AndroidJUnit4::class)
class LocalProviderProductTest {
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val runId = args.getString("runId")?.trim().orEmpty()

    @Test
    fun runGuestProduct() {
        if (!Regex("^[A-Za-z0-9._:-]{8,80}$").matches(runId)) {
            fail("product-run-id-missing-or-invalid")
        }
        val catalogRaw = runCatching {
            instrumentation.context.assets.open(LocalProviderProductCatalog.ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: missing("product-catalog-missing")
        val parsed = LocalProviderProductCatalog.parse(catalogRaw, args.getString("cases"))
        if (parsed is LocalProviderProductCatalog.Parsed.Rejected) {
            fail("product-cases-${parsed.reason}")
        }
        val request = (parsed as LocalProviderProductCatalog.Parsed.Ok).request
        val session = LocalProviderRegressionSession(
            context,
            instrumentation,
            runId,
            request.cases,
            LocalProviderProductCatalog.CASE_SCOPE,
            true,
        )
        session.boot()
        session.runCommands()
    }

    private fun missing(reason: String): Nothing {
        fail(reason)
        throw AssertionError(reason)
    }
}
