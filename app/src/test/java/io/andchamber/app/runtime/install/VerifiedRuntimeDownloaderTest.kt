package io.andchamber.app.runtime.install

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class VerifiedRuntimeDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        directory = kotlin.io.path.createTempDirectory("verified-downloader-").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun download(file: File, bytes: ByteArray, size: Long? = bytes.size.toLong()) = runBlocking {
        VerifiedRuntimeDownloader().download(server.url("/archive").toString(), file, digest(bytes), size)
    }

    @Test fun `valid destination is reused without request and stale files removed`() {
        val bytes = "cached archive".toByteArray()
        server.enqueue(MockResponse().setResponseCode(503))
        val dest = File(directory, "archive").apply { writeBytes(bytes) }
        val partial = File(directory, "archive.partial").apply { writeText("stale") }
        val backup = File(directory, "archive.backup").apply { writeText("stale") }
        var progress: Float? = null

        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(bytes)) { progress = it } }

        assertEquals(0, server.requestCount)
        assertEquals(1f, progress)
        assertFalse(partial.exists())
        assertFalse(backup.exists())
    }

    @Test fun `size mismatch triggers request and preserves destination`() {
        val bytes = "fresh archive".toByteArray()
        server.enqueue(MockResponse().setBody(bytes.toString(Charsets.UTF_8)))
        // The hash already matches: the size alone must prevent a cache hit.
        val old = bytes
        val dest = File(directory, "archive").apply { writeBytes(old) }
        try {
            runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(bytes), (bytes.size + 1).toLong()) }
            fail("expected size validation failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("size mismatch"))
        }
        assertEquals(1, server.requestCount)
        assertEquals(old.toList(), dest.readBytes().toList())

    }

    @Test fun `hash mismatch does not trust destination and downloads replacement`() {
        server.enqueue(MockResponse().setBody("replacement"))
        val dest = File(directory, "archive").apply { writeText("wrong hash") }
        val replacement = "replacement".toByteArray()
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(replacement)) }
        assertEquals(replacement.toList(), dest.readBytes().toList())
        assertEquals(1, server.requestCount)
    }

    @Test fun `network failure preserves old destination`() {
        val old = "old valid contents".toByteArray()
        val dest = File(directory, "archive").apply { writeBytes(old) }
        server.enqueue(MockResponse().setResponseCode(503))
        var thrown = false
        try { download(dest, "new contents".toByteArray()) } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue("expected HTTP failure", thrown)
        assertEquals(old.toList(), dest.readBytes().toList())
        assertEquals(1, server.requestCount)
    }

    @Test fun `range response appends partial bytes`() {
        val complete = "abcdef".toByteArray()
        val dest = File(directory, "archive")
        File(directory, "archive.partial").writeBytes("abc".toByteArray())
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(complete), complete.size.toLong()) }
        assertEquals(complete.toList(), dest.readBytes().toList())
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `ignored range restarts from zero`() {
        val complete = "replacement".toByteArray()
        val dest = File(directory, "archive")
        File(directory, "archive.partial").writeBytes("prefix".toByteArray())
        server.enqueue(MockResponse().setBody(complete.toString(Charsets.UTF_8)))
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(complete)) }
        assertEquals(complete.toList(), dest.readBytes().toList())
        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `416 retries from zero`() {
        val complete = "abcdef".toByteArray()
        val dest = File(directory, "archive")
        File(directory, "archive.partial").writeBytes("bad".toByteArray())
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(complete.toString(Charsets.UTF_8)))
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(complete)) }
        assertEquals(complete.toList(), dest.readBytes().toList())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test fun `bad downloaded hash leaves old destination untouched`() {
        val old = "old archive".toByteArray()
        val dest = File(directory, "archive").apply { writeBytes(old) }
        server.enqueue(MockResponse().setBody("corrupt"))
        var thrown = false
        try { runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest("expected".toByteArray())) } }
        catch (_: IllegalArgumentException) { thrown = true }
        assertTrue("expected hash validation failure", thrown)
        assertEquals(old.toList(), dest.readBytes().toList())
        assertEquals(1, server.requestCount)
    }

    @Test fun `valid complete partial and recovered backup activate offline`() {
        val bytes = "partial archive".toByteArray()
        val dest = File(directory, "archive")
        val partial = File(directory, "archive.partial").apply { writeBytes(bytes) }
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(bytes), bytes.size.toLong()) }
        assertEquals(bytes.toList(), dest.readBytes().toList())
        assertEquals(0, server.requestCount)

        dest.delete()
        File(directory, "archive.backup").writeBytes(bytes)
        runBlocking { VerifiedRuntimeDownloader().download(server.url("/archive").toString(), dest, digest(bytes)) }
        assertEquals(bytes.toList(), dest.readBytes().toList())
        assertEquals(0, server.requestCount)
        assertFalse(partial.exists())
    }
}
