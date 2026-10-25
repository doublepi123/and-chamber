package io.andchamber.app.phone.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The grant route is what stops a concurrent chat from claiming a natively confirmed task.
 *
 * A consumed proposal must pin its own session; a manual native task pins null explicitly. An
 * unwired publisher fails closed, which is what forces the integration to revoke instead of leaving
 * a lease that another chat could race to claim.
 */
class PhoneGrantRouteTest {
    @Test
    fun `an unwired publisher refuses every route so the caller must revoke`() {
        assertFalse(PhoneGrantRoutePublisher.Unavailable.publishGrantRoute(1, null))
        assertFalse(PhoneGrantRoutePublisher.Unavailable.publishGrantRoute(1, "ses-a"))
    }

    @Test
    fun `the publisher is a routing decision only`() {
        val calls = mutableListOf<Pair<Long, String?>>()
        val publisher =
            PhoneGrantRoutePublisher { epoch, label ->
                calls += epoch to label
                true
            }
        publisher.publishGrantRoute(9, "ses-b")
        publisher.publishGrantRoute(10, null)
        assertEquals(listOf(9L to "ses-b", 10L to null), calls)
    }
}
