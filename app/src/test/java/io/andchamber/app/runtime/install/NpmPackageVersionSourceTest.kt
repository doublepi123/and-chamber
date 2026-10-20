package io.andchamber.app.runtime.install

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class NpmPackageVersionSourceTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun source() = NpmPackageVersionSource(client, registryBaseUrl = server.url("/").toString())

    private fun metadata(vararg versions: String): String =
        """{"name":"x","dist-tags":{"latest":"x"},"versions":{${versions.joinToString(",") { "\"$it\":{}" }}}}"""

    private val pins = PackageVersions(openChamber = "2.4.0", openCode = "2.0.32")

    @Test
    fun `picks the highest stable version of the pinned major and skips prereleases and other majors`() {
        server.enqueue(MockResponse().setBody(metadata("2.3.9", "2.4.0", "2.4.1", "2.5.0-beta.1", "2.9.0-rc.1", "3.0.0")))
        server.enqueue(MockResponse().setBody(metadata("0.0.0-dev-20260101", "2.0.32", "2.0.33", "2.1.0", "3.0.0")))

        val latest = runBlocking { source().latest(pins) }

        assertEquals(PackageVersions(openChamber = "2.4.1", openCode = "2.1.0"), latest)
    }

    @Test
    fun `opencode devs and versions below the minimum are excluded`() {
        server.enqueue(MockResponse().setBody(metadata("2.4.0")))
        server.enqueue(MockResponse().setBody(metadata("0.0.0-dev-1", "2.0.19", "2.0.20", "2.0.25")))

        val latest = runBlocking { source().latest(pins) }

        // 2.0.25 is a stable 2.x but below the pin's 2.0.32: the pin is the floor, so it wins.
        assertEquals("2.0.32", latest.openCode)
    }

    @Test
    fun `never returns a version below the pin`() {
        server.enqueue(MockResponse().setBody(metadata("2.3.0")))
        server.enqueue(MockResponse().setBody(metadata("2.0.30")))

        val latest = runBlocking { source().latest(pins) }

        assertEquals(PackageVersions(openChamber = "2.4.0", openCode = "2.0.32"), latest)
    }

    @Test
    fun `sends the abbreviated metadata accept header and percent-encodes the scope`() {
        server.enqueue(MockResponse().setBody(metadata("2.4.0")))
        server.enqueue(MockResponse().setBody(metadata("2.0.32")))

        runBlocking { source().latest(pins) }

        val first = server.takeRequest()
        assertEquals("application/vnd.npm.install-v1+json", first.getHeader("Accept"))
        assertEquals("/@openchamber%2fweb", first.path)
        val second = server.takeRequest()
        assertEquals("/@opencode%2fcli", second.path)
    }

    @Test
    fun `a non-success answer throws`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { source().latest(pins) }
        }
    }

    @Test
    fun `a body without versions throws`() {
        server.enqueue(MockResponse().setBody("""{"name":"x"}"""))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { source().latest(pins) }
        }
    }

    @Test
    fun `selectLatest ignores build metadata and every prerelease form`() {
        val picked =
            NpmPackageVersionSource.selectLatest(
                candidates = listOf("2.4.0+build.5", "2.4.0-dev", "2.4.1", "2.4.2-0"),
                pinned = "2.4.0",
                minimum = null,
            )

        assertEquals("2.4.1", picked)
    }
}
