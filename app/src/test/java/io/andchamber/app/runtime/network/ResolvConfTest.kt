package io.andchamber.app.runtime.network

import io.andchamber.app.runtime.install.NetworkFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvConfTest {
    @Test
    fun `usable system servers are used without public resolvers`() {
        assertEquals(
            "nameserver 192.168.1.1\n",
            ResolvConf.render(listOf("192.168.1.1")),
        )
    }

    @Test
    fun `at most three nameservers because musl reads no more`() {
        val servers = ResolvConf.servers(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"))
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), servers)
    }

    @Test
    fun `public fallback is used when the system reports nothing`() {
        assertEquals(ResolvConf.PUBLIC_FALLBACK, ResolvConf.servers(emptyList()))
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", ResolvConf.render(emptyList()))
    }

    @Test
    fun `duplicates are removed`() {
        assertEquals(
            listOf("8.8.8.8"),
            ResolvConf.servers(listOf("8.8.8.8", "8.8.8.8")),
        )
    }

    @Test
    fun `empty and unusable system servers use public fallback`() {
        assertEquals(ResolvConf.PUBLIC_FALLBACK, ResolvConf.servers(emptyList()))
        assertEquals(ResolvConf.PUBLIC_FALLBACK, ResolvConf.servers(listOf("", "0.0.0.0", "::", "bad addr")))
    }

    @Test
    fun `ipv6 scope ids and brackets are stripped`() {
        assertEquals(
            listOf("fe80::1", "2001:db8::53"),
            ResolvConf.servers(listOf("fe80::1%wlan0", "[2001:db8::53]")),
        )
    }

    @Test
    fun `ipv6 system server is kept without public resolvers`() {
        assertEquals(
            listOf("2408:8888::53"),
            ResolvConf.servers(listOf("2408:8888::53")),
        )
    }

    @Test
    fun `network failure output is recognised`() {
        listOf(
            "fetch https://dl-cdn.alpinelinux.org: DNS: transient error",
            "getaddrinfo EAI_AGAIN registry.npmjs.org",
            "npm ERR! code ENOTFOUND",
            "connect ETIMEDOUT 1.2.3.4:443",
            "unable to connect: Operation not permitted",
            "bad address 'dl-cdn.alpinelinux.org'",
        ).forEach { assertTrue(it, NetworkFailure.isNetworkOutput(it)) }
        assertFalse(NetworkFailure.isNetworkOutput("added 412 packages"))
    }

    @Test
    fun `network failure message keeps the retry hint`() {
        assertTrue(NetworkFailure.MESSAGE.contains("try again"))
        assertEquals(
            "This device can't reach the package mirrors (Alpine, npm). Check the network or VPN, then try again.",
            NetworkFailure.MESSAGE,
        )
    }
}
