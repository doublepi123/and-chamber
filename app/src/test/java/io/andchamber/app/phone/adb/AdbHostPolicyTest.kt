package io.andchamber.app.phone.adb

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbHostPolicyTest {
    private val local = listOf(
        InetAddress.getByName("192.0.2.16"),
        InetAddress.getByName("192.0.2.7"),
    )
    private val policy = AdbHostPolicy(localAddresses = { local })

    @Test
    fun numericLiteralsAreParsedWithoutAResolver() {
        assertNotNull(AdbHostPolicy.parseNumericAddress("127.0.0.1"))
        assertNotNull(AdbHostPolicy.parseNumericAddress("192.0.2.16"))
        assertNotNull(AdbHostPolicy.parseNumericAddress("192.0.2.7"))
        assertNotNull(AdbHostPolicy.parseNumericAddress("::1"))
        assertNotNull(AdbHostPolicy.parseNumericAddress("fe80::1"))
        assertNull(AdbHostPolicy.parseNumericAddress("localhost"))
        assertNull(AdbHostPolicy.parseNumericAddress("example.com"))
        assertNull(AdbHostPolicy.parseNumericAddress("127.0.0.1."))
        assertNull(AdbHostPolicy.parseNumericAddress("010.0.0.1"))
        assertNull(AdbHostPolicy.parseNumericAddress("1080.1.1.1"))
        assertNull(AdbHostPolicy.parseNumericAddress("1.2.3"))
        assertNull(AdbHostPolicy.parseNumericAddress("fe80::1%wlan0"))
        assertNull(AdbHostPolicy.parseNumericAddress(""))
    }

    @Test
    fun loopbackAndOwnAddressesAreAllowed() {
        assertNull(policy.refusal("127.0.0.1"))
        assertNull(policy.refusal("::1"))
        assertNull(policy.refusal("192.0.2.16"))
        assertNull(policy.refusal("192.0.2.7"))
    }

    @Test
    fun hostNamesAndRemoteAddressesAreRefused() {
        assertEquals(AdbHostRefusal.DNS_FORBIDDEN, policy.refusal("localhost"))
        assertEquals(AdbHostRefusal.DNS_FORBIDDEN, policy.refusal("adb.example.com"))
        assertEquals(AdbHostRefusal.REMOTE_FORBIDDEN, policy.refusal("198.51.100.20"))
        assertEquals(AdbHostRefusal.REMOTE_FORBIDDEN, policy.refusal("8.8.8.8"))
        assertEquals(AdbHostRefusal.INVALID, policy.refusal(""))
        assertEquals(AdbHostRefusal.INVALID, policy.refusal(" 127.0.0.1"))
        assertEquals(AdbHostRefusal.INVALID, policy.refusal("a".repeat(65)))
    }

    @Test
    fun noHostNameEverLooksAllowed() {
        listOf("localhost", "example.com", "adb.local", "192.0.2.16.nip.io").forEach {
            assertTrue(!policy.isAllowed(it))
        }
    }
}
