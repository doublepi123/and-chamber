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
 * Selection per package (see [selectLatest]): the highest stable version with the same major as the pin.
 * OpenCode additionally needs at least [OpenChamberInstaller.MIN_OPENCODE_VERSION]. The result is never
 * lower than the pin, so a registry that lags behind the app's bundled versions cannot downgrade it.
 */
class NpmPackageVersionSource(
    private val client: OkHttpClient,
    private val registryBaseUrl: String = DEFAULT_REGISTRY,
) : PackageVersionSource {
    override suspend fun latest(pinned: PackageVersions): PackageVersions =
        withContext(Dispatchers.IO) {
            PackageVersions(
                openChamber = latestOf(OPENCHAMBER_PACKAGE, pinned.openChamber, minimum = null),
                openCode = latestOf(OPENCODE_PACKAGE, pinned.openCode, minimum = OpenChamberInstaller.MIN_OPENCODE_VERSION),
            )
        }

    private fun latestOf(
        name: String,
        pinned: String,
        minimum: String?,
    ): String {
        val found = selectLatest(fetchVersions(name), pinned, minimum)
        // Never below the pin: the registry can only add a newer version, never remove the bundled one.
        return if (found == null || OpenChamberInstaller.compareVersions(found, pinned) < 0) pinned else found
    }

    private fun fetchVersions(name: String): Collection<String> {
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
            return parseVersionKeys(body)
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://registry.npmjs.org"
        const val OPENCHAMBER_PACKAGE = "@openchamber/web"
        const val OPENCODE_PACKAGE = "@opencode/cli"
        private const val TIMEOUT_SECONDS = 15L

        private val json = Json { ignoreUnknownKeys = true }

        /** The keys of the registry document's `versions` object. Throws when the document is malformed. */
        internal fun parseVersionKeys(body: String): Set<String> {
            val root: JsonObject = json.parseToJsonElement(body).jsonObject
            val versions = root["versions"]?.jsonObject ?: error("npm metadata has no versions")
            return versions.keys
        }

        /**
         * The highest stable version among [candidates] that shares [pinned]'s major version and, when
         * [minimum] is set, is at least it. Pre-releases (any `-` or `+` suffix, which covers `0.0.0-dev-*`)
         * are excluded. Returns null when nothing qualifies.
         */
        internal fun selectLatest(
            candidates: Collection<String>,
            pinned: String,
            minimum: String?,
        ): String? {
            val major = majorOf(pinned)
            return candidates
                .filter { STABLE.matches(it) }
                .filter { majorOf(it) == major }
                .filter { minimum == null || OpenChamberInstaller.compareVersions(it, minimum) >= 0 }
                .maxWithOrNull { a, b -> OpenChamberInstaller.compareVersions(a, b) }
        }

        private fun majorOf(version: String): Int =
            OpenChamberInstaller.extractVersion(version).substringBefore('.').toIntOrNull() ?: -1

        private val STABLE = Regex("""^\d+(\.\d+)+$""")
    }
}
