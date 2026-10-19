package io.andchamber.app.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DnsRefresherTest {
    @get:Rule val folder = TemporaryFolder()

    /** Fake event source: records the registered callback and the unregister count. */
    private class FakeEvents(var failRegister: Boolean = false) : NetworkEvents {
        var callback: (() -> Unit)? = null
        var registrations = 0
        var unregistrations = 0

        override fun register(onChange: () -> Unit): () -> Unit {
            if (failRegister) throw IllegalStateException("no network service")
            registrations++
            callback = onChange
            return {
                unregistrations++
                callback = null
            }
        }

        fun fire() {
            callback?.invoke()
        }
    }

    private class Reports {
        val messages = mutableListOf<String>()
    }

    private fun resolv(rootfs: File) = File(rootfs, "etc/resolv.conf")

    private fun refresher(
        provider: () -> List<String>,
        events: FakeEvents,
        reports: Reports = Reports(),
    ) = DnsRefresher(
        dnsProvider = SystemDnsProvider { provider() },
        events = events,
        reporter = RuntimeDnsReporter { reports.messages += it },
    )

    @Test
    fun `start refreshes immediately and a network change rewrites atomically`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var system = listOf("10.0.0.1")
        val refresher = refresher({ system }, events)

        assertTrue(refresher.start(rootfs).isSuccess)
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())

        system = listOf("192.168.50.1")
        events.fire()
        assertEquals("nameserver 192.168.50.1\n", resolv(rootfs).readText())
        // Atomic replace: no temp files are left behind in the directory.
        assertEquals(listOf("resolv.conf"), resolv(rootfs).parentFile.list()!!.toList())
    }

    @Test
    fun `unchanged servers do not rewrite the file`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var writes = 0
        val refresher =
            DnsRefresher(
                dnsProvider = SystemDnsProvider { listOf("10.0.0.1") },
                events = events,
                writeResolv = { root, servers ->
                    writes++
                    ResolvConf.writeAtomically(File(root, "etc/resolv.conf"), ResolvConf.render(servers))
                },
            )
        refresher.start(rootfs)
        events.fire()
        assertEquals(1, writes)
    }

    @Test
    fun `losing every system server falls back to public resolvers and never mixes them`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var system = listOf("10.0.0.1")
        val refresher = refresher({ system }, events)
        refresher.start(rootfs)

        system = listOf("bad addr", "0.0.0.0")
        events.fire()
        assertEquals(ResolvConf.PUBLIC_FALLBACK.map { "nameserver $it" }, resolv(rootfs).readLines())

        system = listOf("10.9.0.1")
        events.fire()
        assertEquals("nameserver 10.9.0.1\n", resolv(rootfs).readText())
    }

    /** stop() returns before any further write: a callback after stop is ignored, and no write lands. */
    @Test
    fun `a callback after stop writes nothing and unregisters`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var system = listOf("10.0.0.1")
        val refresher = refresher({ system }, events)
        refresher.start(rootfs)
        val staleCallback = events.callback!!

        refresher.stop()
        assertEquals(1, events.unregistrations)
        system = listOf("192.168.9.9")
        staleCallback.invoke() // a callback that still holds the old closure fires after stop

        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())
    }

    /**
     * The provider is sampled outside the lock. If stop happens during sampling, the sampled value
     * must be discarded, not committed.
     */
    @Test
    fun `stop during sampling discards the sampled servers`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        lateinit var refresher: DnsRefresher
        var sampling = false
        refresher =
            DnsRefresher(
                dnsProvider =
                    SystemDnsProvider {
                        if (sampling) {
                            // Stop lands while this sample is in flight.
                            refresher.stop()
                            listOf("203.0.113.9")
                        } else {
                            listOf("10.0.0.1")
                        }
                    },
                events = events,
            )
        refresher.start(rootfs)
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())

        sampling = true
        events.fire()
        assertEquals("the in-flight sample must not be written after stop", "nameserver 10.0.0.1\n", resolv(rootfs).readText())
    }

    /** A new start must not be overwritten by a callback from the previous generation. */
    @Test
    fun `a new start is not polluted by an old callback`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var system = listOf("10.0.0.1")
        val refresher = refresher({ system }, events)
        refresher.start(rootfs)
        val oldCallback = events.callback!!
        refresher.stop()

        val newRoot = folder.newFolder("newrootfs")
        system = listOf("172.16.0.1")
        refresher.start(newRoot)
        oldCallback.invoke() // stale generation

        assertEquals("nameserver 172.16.0.1\n", resolv(newRoot).readText())
        assertEquals("the stale callback never touched the old root again", "nameserver 10.0.0.1\n", resolv(rootfs).readText())
    }

    @Test
    fun `registration failure leaves nothing registered and a retry can succeed`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents(failRegister = true)
        val reports = Reports()
        val refresher = refresher({ listOf("10.0.0.1") }, events, reports)

        val failed = refresher.start(rootfs)
        assertTrue(failed.isFailure)
        assertEquals(0, events.registrations)
        assertTrue(reports.messages.any { it.contains("registration failed") })
        assertFalse(resolv(rootfs).exists())

        events.failRegister = false
        assertTrue(refresher.start(rootfs).isSuccess)
        assertEquals(1, events.registrations)
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())
    }

    /**
     * A failing write keeps the original file and is reported, not thrown. The DNS value must change
     * so that the refresh actually reaches the writer, which is then asserted to have been called.
     */
    @Test
    fun `a failing write keeps the original file and reports the failure`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        val reports = Reports()
        var system = listOf("10.0.0.1")
        var fail = false
        var writerCalls = 0
        val refresher =
            DnsRefresher(
                dnsProvider = SystemDnsProvider { system },
                events = events,
                reporter = RuntimeDnsReporter { reports.messages += it },
                writeResolv = { root, servers ->
                    writerCalls++
                    if (fail) error("disk full")
                    ResolvConf.writeAtomically(File(root, "etc/resolv.conf"), ResolvConf.render(servers))
                },
            )
        refresher.start(rootfs)
        assertEquals(1, writerCalls)

        fail = true
        system = listOf("10.9.9.9") // a real change, so the refresh must write and hit the failure
        events.fire()

        assertEquals("the writer must have been called for the change", 2, writerCalls)
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())
        assertTrue(reports.messages.any { it.contains("disk full") })
    }

    /**
     * Race: sample A starts first and blocks inside the provider. Sample B then starts later, gets the
     * new servers and commits. When A finally returns its stale servers, the sequence check must reject
     * them, so the file keeps B's value.
     */
    @Test
    fun `a stale sample that returns late cannot overwrite a newer committed sample`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val refresher =
            DnsRefresher(
                dnsProvider =
                    SystemDnsProvider {
                        when (calls.incrementAndGet()) {
                            1 -> listOf("10.0.0.1") // initial start refresh
                            2 -> {
                                // Sample A: stalls until released, returning the old value.
                                aEntered.countDown()
                                releaseA.await(5, TimeUnit.SECONDS)
                                listOf("10.0.0.1")
                            }
                            else -> listOf("10.9.9.9") // sample B: the new value, returned at once
                        }
                    },
                events = events,
            )
        refresher.start(rootfs) // call 1: initial refresh writes 10.0.0.1
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())

        val a = Thread { refresher.refresh(1L) } // same generation as the start, sample A
        a.start()
        assertTrue("sample A must be in progress", aEntered.await(5, TimeUnit.SECONDS))

        refresher.refresh(1L) // sample B, later in sequence, commits first
        assertEquals("nameserver 10.9.9.9\n", resolv(rootfs).readText())

        releaseA.countDown()
        a.join(5_000)
        assertFalse("sample A must finish", a.isAlive)
        assertEquals("the late stale sample must lose", "nameserver 10.9.9.9\n", resolv(rootfs).readText())
    }

    /** A reporter that itself throws must not escape the event callback. */
    @Test
    fun `a throwing reporter does not escape the network callback`() {
        val rootfs = folder.newFolder("rootfs")
        val events = FakeEvents()
        var system = listOf("10.0.0.1")
        var fail = false
        val refresher =
            DnsRefresher(
                dnsProvider = SystemDnsProvider { system },
                events = events,
                reporter = RuntimeDnsReporter { error("reporter itself broke") },
                writeResolv = { root, servers ->
                    if (fail) error("disk full")
                    ResolvConf.writeAtomically(File(root, "etc/resolv.conf"), ResolvConf.render(servers))
                },
            )
        assertTrue(refresher.start(rootfs).isSuccess)
        assertEquals("nameserver 10.0.0.1\n", resolv(rootfs).readText())

        fail = true
        system = listOf("10.9.9.9")
        events.fire() // write fails, reporter throws: neither may escape the callback

        assertEquals("the original file is intact", "nameserver 10.0.0.1\n", resolv(rootfs).readText())
    }

    @Test
    fun `writeAtomically replaces a whole file and leaves no temp files`() {
        val dir = folder.newFolder("etc")
        val target = File(dir, "resolv.conf")
        target.writeText("nameserver 1.2.3.4\n")

        ResolvConf.writeAtomically(target, "nameserver 5.6.7.8\n")

        assertEquals("nameserver 5.6.7.8\n", target.readText())
        assertEquals(listOf("resolv.conf"), dir.list()!!.toList())
    }
}
