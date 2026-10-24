package io.andchamber.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionIdentityTest {
    private fun reply(
        notificationId: Int,
        sessionId: String = "ses_1",
        directory: String? = null,
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_REPLY,
        notificationId = notificationId,
        sessionId = sessionId,
        directory = directory,
    )

    private fun permission(
        notificationId: Int,
        requestId: String = "per_1",
        decision: String = "once",
        sessionId: String = "ses_1",
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_PERMISSION,
        notificationId = notificationId,
        sessionId = sessionId,
        requestId = requestId,
        decision = decision,
    )

    @Test
    fun `the same action keeps one identity across reposts`() {
        val first = reply(101)
        val second = reply(101)

        assertEquals(NotificationActionIdentity.dataUri(first), NotificationActionIdentity.dataUri(second))
        assertEquals(NotificationActionIdentity.requestCode(first), NotificationActionIdentity.requestCode(second))
    }

    @Test
    fun `two notifications of one session have different identities`() {
        val first = reply(101)
        val second = reply(202)

        assertNotEquals(NotificationActionIdentity.dataUri(first), NotificationActionIdentity.dataUri(second))
    }

    @Test
    fun `the same notification id in another session has a different identity`() {
        val first = reply(101, sessionId = "ses_1")
        val second = reply(101, sessionId = "ses_2")

        assertNotEquals(NotificationActionIdentity.dataUri(first), NotificationActionIdentity.dataUri(second))
    }

    @Test
    fun `the directory is part of the identity`() {
        val noDirectory = reply(101, directory = null)
        val emptyDirectory = reply(101, directory = "")
        val first = reply(101, directory = "/repo/a")
        val second = reply(101, directory = "/repo/b")

        assertNotEquals(NotificationActionIdentity.dataUri(noDirectory), NotificationActionIdentity.dataUri(emptyDirectory))
        assertNotEquals(NotificationActionIdentity.dataUri(first), NotificationActionIdentity.dataUri(second))
        assertNotEquals(NotificationActionIdentity.dataUri(noDirectory), NotificationActionIdentity.dataUri(first))
        assertNotEquals(NotificationActionIdentity.dataUri(emptyDirectory), NotificationActionIdentity.dataUri(first))
    }

    @Test
    fun `null and directory values keep stable identities`() {
        listOf(null, "", "/repo/a").forEach { directory ->
            val first = reply(101, directory = directory)
            val again = reply(101, directory = directory)

            assertEquals(NotificationActionIdentity.dataUri(first), NotificationActionIdentity.dataUri(again))
            assertEquals(NotificationActionIdentity.requestCode(first), NotificationActionIdentity.requestCode(again))
        }
    }

    @Test
    fun `the action is part of the identity`() {
        val reply = reply(101)
        val permission = permission(101)

        assertNotEquals(NotificationActionIdentity.dataUri(reply), NotificationActionIdentity.dataUri(permission))
    }

    @Test
    fun `permission request id and decision are both part of the identity`() {
        val once = permission(101, requestId = "per_1", decision = "once")
        val always = permission(101, requestId = "per_1", decision = "always")
        val otherRequest = permission(101, requestId = "per_2", decision = "once")

        assertNotEquals(NotificationActionIdentity.dataUri(once), NotificationActionIdentity.dataUri(always))
        assertNotEquals(NotificationActionIdentity.dataUri(once), NotificationActionIdentity.dataUri(otherRequest))
    }

    @Test
    fun `delimiter characters in a field cannot be read as structure`() {
        // "ses_1&request=per_1" must not be encoded like the pair session="ses_1", request="per_1".
        val oneField = reply(101, sessionId = "ses_1&request=per_1")
        val twoFields =
            NotificationActionIdentity.Spec(
                NotificationActionReceiver.ACTION_REPLY,
                101,
                "ses_1",
                requestId = "per_1",
            )

        assertNotEquals(NotificationActionIdentity.dataUri(oneField), NotificationActionIdentity.dataUri(twoFields))
    }

    @Test
    fun `values are percent encoded with a fixed scheme`() {
        val uri = NotificationActionIdentity.dataUri(reply(7, sessionId = "会话/一?#%"))

        assertTrue(uri.startsWith("andchamber-notify://action/v1/"))
        assertTrue("path separators are escaped", uri.contains("%2F"))
        assertTrue("query markers are escaped", uri.contains("%3F"))
        assertTrue("fragments are escaped", uri.contains("%23"))
        assertTrue("percent signs are escaped", uri.contains("%25"))
        assertFalse("non-ASCII stays encoded", uri.contains("会话"))
    }

    @Test
    fun `the notification id is its own path segment`() {
        val one = NotificationActionIdentity.dataUri(reply(1, sessionId = "2"))
        val twelve = NotificationActionIdentity.dataUri(reply(12, sessionId = ""))

        assertNotEquals(one, twelve)
    }
}
