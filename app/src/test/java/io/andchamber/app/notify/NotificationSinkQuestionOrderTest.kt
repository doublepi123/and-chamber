package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question sink reads pending before it takes the per-id lock. This proves that an older read
 * which resumes after a newer question already posted can never overwrite it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSinkQuestionOrderTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val formA =
        """{"id":"frm_A","sessionID":"ses_1","title":"A","fields":[
           {"key":"a","title":"A","type":"string","required":true}]}"""

    private val formB =
        """{"id":"frm_B","sessionID":"ses_1","title":"B","fields":[
           {"key":"b","title":"B","type":"string","required":true}]}"""

    private fun pending(form: String): JsonObject = obj("""{"ses_1":{"forms":[$form]}}""")

    private fun line(
        tag: String,
        kind: String = "question",
    ): String =
        """${NotificationPayload.PREFIX}{"kind":"$kind","title":"Question","body":"Answer","tag":"$tag","sessionId":"ses_1","directory":"/repo"}"""

    /** Records what the sink posted; no notification or Android object is needed. */
    private class FakePoster : NotificationPoster {
        val forms = mutableListOf<String>()
        val openOnly = mutableListOf<FormAnswer?>()
        private var counter = 0

        override fun post(
            event: DesktopNotification,
            channel: Channel,
            questionAnswer: FormAnswer?,
        ) {
            openOnly += questionAnswer
        }

        override fun isInlineRevisionCurrent(
            id: Int,
            revision: String,
        ) = true

        override fun hasNewerThan(
            id: Int,
            revision: String,
        ) = false

        override suspend fun awaitShown(
            id: Int,
            revision: String,
        ) = true

        override fun postInline(
            id: Int,
            channel: Channel,
            key: InlineFormKey,
            view: InlineFormView,
        ): String {
            forms += key.formId
            counter += 1
            return "rev$counter"
        }

        override fun updateInlineIfRevision(
            id: Int,
            revision: String,
            key: InlineFormKey,
            view: InlineFormView,
        ): String? = null

        override fun inlineSendingIfRevision(
            id: Int,
            revision: String,
            key: InlineFormKey,
            retry: Boolean,
        ): String? = null

        override fun inlineDoneIfRevision(
            id: Int,
            revision: String,
            done: InlineFormDone,
        ): String? = null

        override fun postInlineLookup(
            id: Int,
            channel: Channel,
            sessionId: String,
            directory: String?,
        ): String? = null

        override fun updateInlineLookupIfRevision(
            id: Int,
            revision: String,
            sessionId: String,
            directory: String?,
        ): String? = null

        override fun inlineFallbackIfRevision(
            id: Int,
            revision: String,
            sessionId: String,
            directory: String?,
        ): String? = null

        override fun cancelIfRevision(
            id: Int,
            token: String,
        ) = false

        override fun repostInlineIfLatest(
            id: Int,
            revision: String,
            key: InlineFormKey,
            view: InlineFormView,
        ): String? = null

        override fun repostInlineDoneIfLatest(
            id: Int,
            revision: String,
            done: InlineFormDone,
        ): String? = null
    }

    private object NoSendTransport : NotificationFormTransport {
        override suspend fun pending(): JsonObject? = null

        override suspend fun send(
            request: ApiRequest,
            directory: String?,
        ): ApiResult = ApiResult(0, "")
    }

    @Test
    fun `an older question read cannot overwrite a newer form on the same notification id`() =
        runTest {
            val aRead = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            var calls = 0
            val pendingRead: suspend () -> JsonObject? =
                {
                    calls += 1
                    if (calls == 1) {
                        // A's snapshot is valid for A, but the read suspends before the lock.
                        aRead.complete(Unit)
                        releaseA.await()
                        pending(formA)
                    } else {
                        pending(formB)
                    }
                }
            val poster = FakePoster()
            val store = InlineNotificationFormStore()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
            val sink =
                NotificationSink(
                    notifier = poster,
                    foreground = AppForeground { false },
                    pendingRead = pendingRead,
                    inline = InlineNotificationFormDelivery(poster, NoSendTransport, store),
                    scope = scope,
                    order = NotificationEventOrder(),
                )
            val tag = "question-order-${System.nanoTime()}"

            sink.accept(line(tag))
            aRead.await()

            // B arrives and finishes its read/post while A is still paused.
            sink.accept(line(tag))
            runCurrent()
            assertEquals(listOf("frm_B"), poster.forms)
            assertEquals(1, store.size())

            // A resumes with a perfectly valid A snapshot; its event ticket is older.
            releaseA.complete(Unit)
            runCurrent()

            assertEquals("the newer form is not replaced", listOf("frm_B"), poster.forms)
            assertEquals("frm_B", store.find(NotificationPayload.notificationId(tag))?.key?.formId)
            assertEquals("no open-only fallback is posted either", 0, poster.openOnly.size)
            assertNull("A posted nothing", poster.openOnly.firstOrNull())
        }

    @Test
    fun `a non-question event on the same id retires a waiting question`() =
        runTest {
            val aRead = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            var calls = 0
            val pendingRead: suspend () -> JsonObject? =
                {
                    calls += 1
                    aRead.complete(Unit)
                    releaseA.await()
                    pending(formA)
                }
            val poster = FakePoster()
            val store = InlineNotificationFormStore()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
            val sink =
                NotificationSink(
                    notifier = poster,
                    foreground = AppForeground { false },
                    pendingRead = pendingRead,
                    inline = InlineNotificationFormDelivery(poster, NoSendTransport, store),
                    scope = scope,
                    order = NotificationEventOrder(),
                )
            val tag = "question-plain-order-${System.nanoTime()}"

            sink.accept(line(tag))
            aRead.await()
            // A newer ready event posts a plain notification on the same id while A's read is paused.
            sink.accept(line(tag, kind = "ready"))
            runCurrent()
            assertEquals(listOf<FormAnswer?>(null), poster.openOnly)
            assertEquals(0, store.size())

            releaseA.complete(Unit)
            runCurrent()

            assertEquals("the older question posts nothing", listOf<FormAnswer?>(null), poster.openOnly)
            assertTrue("no inline draft was created", poster.forms.isEmpty())
        }

    @Test
    fun `a ticket is not reused after its id was evicted`() {
        val order = NotificationEventOrder(maxEntries = 1)
        val old = order.issue(1)
        order.issue(2) // evicts id 1 from the table
        val next = order.issue(1)

        assertNotEquals("a re-inserted id gets a fresh global ticket", old, next)
        assertFalse("the old ticket never becomes current again", order.isCurrent(1, old))
        assertTrue(order.isCurrent(1, next))
    }
}
