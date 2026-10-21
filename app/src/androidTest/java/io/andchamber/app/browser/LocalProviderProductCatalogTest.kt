package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure catalog checks; the same parser is used by LocalProviderProductTest on device. */
class LocalProviderProductCatalogTest {
    private val asset = File("app/src/androidTest/assets/${LocalProviderProductCatalog.ASSET}")

    @Test
    fun actualPackagedCatalogHasExactlyTheTenFixedProductCases() {
        assertTrue("missing ${asset.absolutePath}", asset.isFile)
        val raw = asset.readText()
        val parsed = LocalProviderProductCatalog.parse(raw, LocalProviderProductCatalog.CASE_NAMES.joinToString(","))
        assertTrue(parsed is LocalProviderProductCatalog.Parsed.Ok)
        assertEquals(
            LocalProviderProductCatalog.CASE_NAMES,
            (parsed as LocalProviderProductCatalog.Parsed.Ok).request.cases,
        )
        assertEquals(10, LocalProviderProductCatalog.CASE_NAMES.size)
        assertEquals(
            LocalProviderProductCatalog.CASE_NAMES.indexOf("product-mouse-move-click") + 1,
            LocalProviderProductCatalog.CASE_NAMES.indexOf("product-mouse-hover-a11y-off"),
        )
    }

    @Test
    fun requestedCasesMayBeANonEmptyKnownSubsetWithoutDuplicates() {
        val raw = asset.readText()
        val subset = listOf("product-focused-restore", "product-native-entry")
        val parsed = LocalProviderProductCatalog.parse(raw, subset.joinToString(","))
        assertEquals(subset, (parsed as LocalProviderProductCatalog.Parsed.Ok).request.cases)

        for (requested in listOf(
            "",
            "product-focused-restore,product-focused-restore",
            "product-focused-restore,product-unknown",
        )) {
            assertTrue(
                LocalProviderProductCatalog.parse(raw, requested) is LocalProviderProductCatalog.Parsed.Rejected,
            )
        }
    }

    @Test
    fun catalogSchemaSuiteAndExactCaseSetAreValidated() {
        val raw = asset.readText()
        assertTrue(
            LocalProviderProductCatalog.parse(raw.replace("local-provider-product", "other-suite"), "product-pinch")
                is LocalProviderProductCatalog.Parsed.Rejected,
        )
        assertTrue(
            LocalProviderProductCatalog.parse(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), "product-pinch")
                is LocalProviderProductCatalog.Parsed.Rejected,
        )
        assertTrue(
            LocalProviderProductCatalog.parse(raw.replace("product-pinch", "product-pinch-extra"), "product-pinch-extra")
                is LocalProviderProductCatalog.Parsed.Rejected,
        )
        assertTrue(
            LocalProviderProductCatalog.parse("{}", "product-pinch")
                is LocalProviderProductCatalog.Parsed.Rejected,
        )
    }
}
