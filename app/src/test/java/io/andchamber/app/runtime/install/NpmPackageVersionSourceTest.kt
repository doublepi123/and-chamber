package io.andchamber.app.runtime.install

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NpmPackageVersionSourceTest {
    private fun metadata(version: String) = """{"dist-tags":{"latest":"$version"},"versions":{"99.0.0":{}}}"""

    @Test
    fun `latest dist tag allows new majors and ignores higher catalog versions`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(metadata("3.1.0")))
            server.enqueue(MockResponse().setBody(metadata("4.0.0")))
            val result = runBlocking { NpmPackageVersionSource(OkHttpClient(), server.url("/").toString()).latest() }
            assertEquals(PackageVersions("3.1.0", "4.0.0"), result)
            val request = server.takeRequest()
            assertEquals("/@openchamber%2fweb", request.path)
            assertEquals("application/vnd.npm.install-v1+json", request.getHeader("Accept"))
            assertEquals("/@opencode%2fcli", server.takeRequest().path)
        }
    }

    @Test
    fun `minimum opencode is accepted but older or prerelease latest fails`() {
        for (version in listOf("2.0.20", "2.0.19", "3.0.0-beta.1")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody(metadata("2.0.0")))
                server.enqueue(MockResponse().setBody(metadata(version)))
                val source = NpmPackageVersionSource(OkHttpClient(), server.url("/").toString())
                if (version == "2.0.20") assertEquals(version, runBlocking { source.latest() }.openCode)
                else assertThrows(IllegalArgumentException::class.java) { runBlocking { source.latest() } }
            }
        }
    }

    @Test
    fun `malformed absent or unstable dist tags are rejected`() {
        for (body in listOf("{}", "not json", """{"dist-tags":{"latest":3}}""", metadata("v3.0.0"), metadata("3.0.0+build"), metadata("latest"), metadata("3.0.0-rc.1"))) {
            assertThrows(Exception::class.java) { NpmPackageVersionSource.parseLatest(body) }
        }
    }

    @Test
    fun `registry error throws instead of returning bundled versions`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { NpmPackageVersionSource(OkHttpClient(), server.url("/").toString()).latest() }
            }
        }
    }
}
