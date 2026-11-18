package io.andchamber.app.runtime.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [PackageVersionSource] over the npm registry's abbreviated metadata (`GET /<name>`, with the
 * `application/vnd.npm.install-v1+json` accept header). [registryBaseUrl] is injectable for tests.
 *
 * Uses dist-tags.latest without a major-version ceiling. OpenCode must meet the compatibility minimum.
 */
class NpmPackageVersionSource(
    private val client: OkHttpClient,
    private val registryBaseUrl: String = DEFAULT_REGISTRY,
) : PackageVersionSource {
    override suspend fun latest(): PackageVersions =
        withContext(Dispatchers.IO) {
            PackageVersions(
                openChamber = latestOf(OPENCHAMBER_PACKAGE, minimum = null),
                openCode = latestOf(OPENCODE_PACKAGE, minimum = OpenChamberInstaller.MIN_OPENCODE_VERSION),
            )
        }

    private fun latestOf(
        name: String,
        minimum: String?,
    ): String {
        val found = fetchLatest(name)
        require(minimum == null || OpenChamberInstaller.compareVersions(found, minimum) >= 0) {
            "$name latest $found is older than the required $minimum"
        }
        return found
    }

    private fun fetchLatest(name: String): String {
        // The scope slash must be percent-encoded for the registry's path routing.
        val encoded = name.replace("/", "%2f")
        val request =
            Request.Builder()
                .url("${registryBaseUrl.trimEnd('/')}/$encoded")
                .header("Accept", "application/vnd.npm.install-v1+json")
                .build()
        // Derived client: keeps the shared connection pool, but bounds this call to the registry timeout.
        val bounded =
            client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        bounded.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "npm registry answered ${response.code} for $name" }
            val body = response.body?.string() ?: error("npm registry sent no body for $name")
            return parseLatest(body)
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://registry.npmjs.org"
        const val OPENCHAMBER_PACKAGE = "@openchamber/web"
        const val OPENCODE_PACKAGE = "@opencode/cli"
        private const val TIMEOUT_SECONDS = 15L

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the stable latest tag, never guesses from the versions catalog. */
        internal fun parseLatest(body: String): String {
            val root: JsonObject = json.parseToJsonElement(body).jsonObject
            val latest = root["dist-tags"]?.jsonObject?.get("latest")?.jsonPrimitive
                ?: error("npm metadata has no dist-tags.latest")
            require(latest.isString && STABLE.matches(latest.content)) { "npm latest must be a stable numeric version" }
            return latest.content
        }

        private val STABLE = Regex("""^\d+(\.\d+)+$""")
    }
}
