package io.andchamber.app.phone.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSessionRouterTest {
    @Test
    fun `an unpublished epoch refuses every real action`() {
        val router = PhoneSessionRouter()
        assertEquals(
            PhoneSessionRouter.Claim.ROUTE_NOT_PUBLISHED,
            router.claim(grantEpoch = 3L, sessionLabel = "ses-a"),
        )
        assertNull(router.currentOwner())
    }

    @Test
    fun `a pinned route owns the epoch before its first action`() {
        val router = PhoneSessionRouter()
        assertTrue(router.publish(epoch = 3L, sessionLabel = "ses-a"))

        assertEquals(PhoneSessionRouter.Claim.SESSION_BUSY, router.claim(3L, "ses-b"))
        assertEquals("a pinned session owns the epoch from publication", "ses-a", router.currentOwner())
        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(3L, "ses-a"))
        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(3L, "ses-a"))
        assertEquals("ses-a", router.currentOwner())
    }

    @Test
    fun `a manual route lets the first real action claim`() {
        val router = PhoneSessionRouter()
        assertTrue(router.publish(epoch = 4L, sessionLabel = null))

        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(4L, "ses-b"))
        assertEquals(PhoneSessionRouter.Claim.SESSION_BUSY, router.claim(4L, "ses-a"))
        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(4L, "ses-b"))
        assertEquals("ses-b", router.currentOwner())
    }

    @Test
    fun `a stale or duplicate publish never changes an existing route`() {
        val router = PhoneSessionRouter()
        assertTrue(router.publish(epoch = 5L, sessionLabel = "ses-a"))
        assertFalse("a duplicate publish is refused", router.publish(5L, "ses-b"))
        assertFalse("an older publish is refused", router.publish(4L, "ses-c"))
        assertEquals(PhoneSessionRouter.Claim.SESSION_BUSY, router.claim(5L, "ses-b"))
        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(5L, "ses-a"))

        // A strictly newer epoch replaces the route; old epochs stay unpublished.
        assertTrue(router.publish(epoch = 6L, sessionLabel = null))
        assertEquals(PhoneSessionRouter.Claim.ROUTE_NOT_PUBLISHED, router.claim(5L, "ses-a"))
        assertEquals(PhoneSessionRouter.Claim.CLAIMED, router.claim(6L, "ses-d"))
        assertEquals(PhoneSessionRouter.Claim.SESSION_BUSY, router.claim(6L, "ses-e"))
    }

    @Test
    fun `clear forgets the route`() {
        val router = PhoneSessionRouter()
        assertTrue(router.publish(epoch = 7L, sessionLabel = "ses-a"))
        router.clear()
        assertEquals(PhoneSessionRouter.Claim.ROUTE_NOT_PUBLISHED, router.claim(7L, "ses-a"))
        assertNull(router.currentOwner())
    }
}
