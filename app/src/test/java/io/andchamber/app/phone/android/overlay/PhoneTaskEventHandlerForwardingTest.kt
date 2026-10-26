package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventError
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tool server holds the lazy handler from DI. `PhoneTaskEventHandler`'s native callbacks have
 * default no-ops, so a wrapper that forgets to forward them silently loses every route claim and
 * native result, leaving the overlay events empty forever. This pins the forwarding.
 */
class PhoneTaskEventHandlerForwardingTest {
    private val key = PhoneTaskKey("task-1", "launch-1")

    private class Recorder(
        private val isAvailable: Boolean = true,
    ) : PhoneTaskEventHandler {
        var route: Triple<PhoneTaskKey, Long, String>? = null
        var completed: Pair<String, PhoneNativeCallOutcome>? = null

        override val available: Boolean get() = isAvailable

        override fun currentTask(epoch: Long): PhoneTaskKey? = null

        override suspend fun handle(
            key: PhoneTaskKey,
            epoch: Long,
            sessionLabel: String,
            event: PhoneTaskEvent,
        ): PhoneTaskEventReply = PhoneTaskEventReply.Refused(PhoneTaskEventError.UNAVAILABLE)

        override fun onRouteClaimed(
            key: PhoneTaskKey,
            admittedEpoch: Long,
            sessionLabel: String,
        ) {
            route = Triple(key, admittedEpoch, sessionLabel)
        }

        override fun onNativeCallCompleted(
            key: PhoneTaskKey,
            admittedEpoch: Long,
            requestId: String,
            outcome: PhoneNativeCallOutcome,
        ) {
            completed = requestId to outcome
        }
    }

    @Test
    fun `the lazy handler forwards the route claim and native result callbacks`() {
        val recorder = Recorder()
        val handler = LazyPhoneTaskEventHandler { recorder }

        handler.onRouteClaimed(key, 7L, "ses-a")
        handler.onNativeCallCompleted(
            key = key,
            admittedEpoch = 7L,
            requestId = "req-1",
            outcome = PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
        )

        assertEquals(Triple(key, 7L, "ses-a"), recorder.route)
        assertEquals(
            "req-1" to PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
            recorder.completed,
        )
    }

    @Test
    fun `the lazy handler forwards availability instead of the interface default`() {
        assertEquals(true, LazyPhoneTaskEventHandler { Recorder(isAvailable = true) }.available)
        assertEquals(false, LazyPhoneTaskEventHandler { Recorder(isAvailable = false) }.available)
        assertEquals(false, LazyPhoneTaskEventHandler { PhoneTaskEventHandler.Unavailable }.available)
    }
}
