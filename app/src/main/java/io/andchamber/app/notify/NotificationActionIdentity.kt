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
     *
     * The inline form fields are separate on purpose, so a business token is never read as a
     * permission decision: [formId] the form, [token] and [generation] the inline draft's business
     * identity (see [InlineFormAction]), [revision] the notification revision the action was posted
     * with (see [NotificationRevisionGate]) and [command] the step command. Unset fields are omitted,
     * so every older identity keeps exactly its previous URI.
     */
    data class Spec(
        val action: String,
        val notificationId: Int,
        val sessionId: String,
        val directory: String? = null,
        val requestId: String? = null,
        val decision: String? = null,
        val formId: String? = null,
        val token: String? = null,
        val generation: Long? = null,
        val revision: String? = null,
        val command: String? = null,
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
            spec.formId?.let { append("&form=").append(encode(it)) }
            spec.token?.let { append("&token=").append(encode(it)) }
            spec.generation?.let { append("&gen=").append(it) }
            spec.revision?.let { append("&rev=").append(encode(it)) }
            spec.command?.let { append("&cmd=").append(encode(it)) }
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
                spec.formId?.let { putExtra(NotificationActionReceiver.EXTRA_FORM_ID, it) }
                spec.token?.let { putExtra(NotificationActionReceiver.EXTRA_INLINE_TOKEN, it) }
                spec.generation?.let { putExtra(NotificationActionReceiver.EXTRA_INLINE_GENERATION, it) }
                spec.revision?.let { putExtra(NotificationActionReceiver.EXTRA_INLINE_REVISION, it) }
                spec.command?.let { putExtra(NotificationActionReceiver.EXTRA_INLINE_COMMAND, it) }
            }

    /**
     * Reads an inline-form action back from a delivered Intent. The identity fields come from the
     * extras our own PendingIntent set (a mutable PendingIntent's sender cannot override existing
     * extras), and the Intent's data must be exactly the URI those fields encode, so a partial or
     * mixed Intent is refused. Null when anything is missing or does not match.
     */
    fun inlineSpecFrom(intent: Intent): Spec? {
        val action = intent.action ?: return null
        if (!intent.hasExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID)) return null
        val spec =
            Spec(
                action = action,
                notificationId = intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, 0),
                sessionId = intent.getStringExtra(NotificationActionReceiver.EXTRA_SESSION_ID) ?: return null,
                directory = intent.getStringExtra(NotificationActionReceiver.EXTRA_DIRECTORY),
                formId = intent.getStringExtra(NotificationActionReceiver.EXTRA_FORM_ID),
                token = intent.getStringExtra(NotificationActionReceiver.EXTRA_INLINE_TOKEN),
                generation =
                    if (intent.hasExtra(NotificationActionReceiver.EXTRA_INLINE_GENERATION)) {
                        intent.getLongExtra(NotificationActionReceiver.EXTRA_INLINE_GENERATION, 0)
                    } else {
                        null
                    },
                revision = intent.getStringExtra(NotificationActionReceiver.EXTRA_INLINE_REVISION) ?: return null,
                command = intent.getStringExtra(NotificationActionReceiver.EXTRA_INLINE_COMMAND) ?: return null,
            )
        return spec.takeIf { intent.dataString == dataUri(it) }
    }

    /**
     * An inline-form broadcast. Only an action carrying RemoteInput is [mutable] (Android must add the
     * typed text); every other inline action, and the delete intent, is immutable. Never an activity.
     */
    fun inlinePendingIntent(
        context: Context,
        spec: Spec,
        mutable: Boolean,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(spec),
            intent(context, spec),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

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

    /**
     * Opens [NotificationFormActivity] for one identity. The same data URI as a broadcast action keeps
     * two pending forms apart; [Spec.requestId] carries the form id and [Spec.decision] the revision
     * the form was posted with, also copied to [OpenChamberNotifier.EXTRA_REVISION] for the screen.
     * The activity builds its own UI, so its PendingIntent is immutable.
     */
    fun activityIntent(
        context: Context,
        spec: Spec,
    ): Intent =
        Intent(context, NotificationFormActivity::class.java)
            .setAction(NotificationFormActivity.ACTION_ANSWER_FORM)
            .setData(Uri.parse(dataUri(spec)))
            .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, spec.sessionId)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, spec.notificationId)
            .apply {
                spec.directory?.let { putExtra(NotificationActionReceiver.EXTRA_DIRECTORY, it) }
                spec.requestId?.let { putExtra(NotificationActionReceiver.EXTRA_FORM_ID, it) }
                spec.decision?.let { putExtra(OpenChamberNotifier.EXTRA_REVISION, it) }
            }

    /** The form screen's PendingIntent. See [activityIntent] for the identity it carries. */
    fun activityPendingIntent(
        context: Context,
        spec: Spec,
        requestCode: Int = requestCode(spec),
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            activityIntent(context, spec),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
