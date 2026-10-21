package io.andchamber.app.browser

import org.json.JSONObject

/**
 * Fixed product-case catalog. The asset is the single source of the ten names; the instrumentation
 * argument may only select a non-empty, duplicate-free subset of it. The existing eight-case lifecycle
 * whitelist is a separate contract and is not extended here.
 */
internal object LocalProviderProductCatalog {
    const val ASSET = "local-provider-product-cases.json"
    const val SUITE = "local-provider-product"
    const val CASE_SCOPE = "product"

    val CASE_NAMES: List<String> = listOf(
        "product-blocked-tight",
        "product-focused-restore",
        "product-desktop-1024",
        "product-mouse-move-click",
        "product-mouse-hover-a11y-off",
        "product-mouse-drag-wheel",
        "product-pinch",
        "product-session-storage",
        "product-console-profile-isolation",
        "product-native-entry",
    )

    data class Request(val cases: List<String>)

    sealed interface Parsed {
        data class Ok(val request: Request) : Parsed
        data class Rejected(val reason: String) : Parsed
    }

    fun parse(catalogRaw: String, requestedRaw: String?): Parsed {
        val catalog = runCatching { JSONObject(catalogRaw) }.getOrNull()
            ?: return Parsed.Rejected("catalog-json")
        if (catalog.optInt("schemaVersion", -1) != 1) return Parsed.Rejected("catalog-schema")
        if (catalog.optString("suite") != SUITE) return Parsed.Rejected("catalog-suite")
        val cases = catalog.optJSONArray("cases") ?: return Parsed.Rejected("catalog-cases")
        val names = ArrayList<String>()
        for (index in 0 until cases.length()) {
            val name = (cases.opt(index) as? String)?.takeIf { it.isNotBlank() }
                ?: ((cases.opt(index) as? JSONObject)?.opt("name") as? String)?.takeIf { it.isNotBlank() }
                ?: return Parsed.Rejected("catalog-case-name")
            names.add(name)
        }
        if (names.size != names.distinct().size) return Parsed.Rejected("catalog-duplicate")
        if (names.toSet() != CASE_NAMES.toSet()) return Parsed.Rejected("catalog-case-set")

        val requested = requestedRaw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (requested.isEmpty()) return Parsed.Rejected("cases-empty")
        if (requested.size != requested.distinct().size) return Parsed.Rejected("cases-duplicate")
        val unknown = requested.filter { it !in names }
        if (unknown.isNotEmpty()) return Parsed.Rejected("cases-unknown:${unknown.joinToString(",")}")
        return Parsed.Ok(Request(requested))
    }
}
