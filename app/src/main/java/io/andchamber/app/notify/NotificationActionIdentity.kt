package io.andchamber.app.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The identity of a notification action's PendingIntent.
 *
 * Android tells PendingIntents apart by their Intent (action, data, component, ...) and request code,
 * never by extras. Extras alone are therefore not enough: two notifications of the same session
 * would share one PendingIntent, and the later post's extras (its notification id) would win, so a
 * button on the older notification could act on the newer one.
 *
 * [dataUri] encodes every field that makes an action distinct, so identity does not depend on
 * extras. The request code is only an auxiliary hint derived from that URI; two different actions
 * may share it without sharing a PendingIntent.
 */
internal object NotificationActionIdentity {
    /** Scheme of the explicit identity URI; also needed to match such an Intent with an IntentFilter. */
    const val DATA_SCHEME = "andchamber-notify"

    private const val HOST = "action"
    private const val VERSION = "v1"
    private const val HEX = "0123456789ABCDEF"

    /**
     * One action on one notification. [requestId] and [decision] only apply to permission actions.
     * [directory] is part of the identity because the same session id can be opened in another
     * directory; null means "no directory" and is encoded differently from an empty string.
     */
    data class Spec(
        val action: String,
        val notificationId: Int,
        val sessionId: String,
        val directory: String? = null,
        val requestId: String? = null,
        val decision: String? = null,
    )

    /**
     * A stable, unambiguous identity. Each variable field is percent-encoded, so characters such as
     * `/`, `?`, `&` or `=` inside a value cannot be read as structure. A null directory is omitted;
     * an empty directory is present with an empty value, so the two remain distinct.
     */
    fun dataUri(spec: Spec): String =
        buildString {
            append(DATA_SCHEME).append("://").append(HOST).append('/').append(VERSION)
            append('/').append(encode(spec.action))
            append('/').append(spec.notificationId)
            append("?session=").append(encode(spec.sessionId))
            spec.directory?.let { append("&directory=").append(encode(it)) }
            spec.requestId?.let { append("&request=").append(encode(it)) }
            spec.decision?.let { append("&decision=").append(encode(it)) }
        }

    /** Auxiliary only: identity is carried by [dataUri], so a hash collision cannot merge actions. */
    fun requestCode(spec: Spec): Int = dataUri(spec).hashCode()

    fun intent(
        context: Context,
        spec: Spec,
    ): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(spec.action)
            .setData(Uri.parse(dataUri(spec)))
            .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, spec.sessionId)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, spec.notificationId)
            .apply {
                spec.directory?.let { putExtra(NotificationActionReceiver.EXTRA_DIRECTORY, it) }
                spec.requestId?.let { putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, it) }
                spec.decision?.let { putExtra(NotificationActionReceiver.EXTRA_DECISION, it) }
            }

    /**
     * Creates the action's PendingIntent. [requestCode] is overridable only so a test can prove that
     * equal request codes on different identities still yield different PendingIntents. Mutable is
     * required for RemoteInput.
     */
    fun pendingIntent(
        context: Context,
        spec: Spec,
        requestCode: Int = requestCode(spec),
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent(context, spec),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun encode(value: String): String =
        buildString(value.length) {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 0xff
                val c = b.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                    append(c)
                } else {
                    append('%').append(HEX[b ushr 4]).append(HEX[b and 0x0f])
                }
            }
        }
}
