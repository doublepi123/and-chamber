package io.andchamber.app.phone.android

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.RemoteInput
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.ui.phone.PhoneStopChannel

/**
 * Notifications for phone control. Two channels, deliberately separate:
 *
 * - `phone_control`: the foreground-service lease notification. Its Pause/Return actions are the stop
 *   entry a person can reach from another app, so its availability decides whether a grant may start
 *   ([stopChannelState]). A posted notification is never taken as proof of delivery on its own; the
 *   channel and the runtime permission are checked explicitly.
 * - `phone_control_pairing`: the pairing prompt with the two RemoteInput steps. Its replies are
 *   broadcasts, so answering never opens an Activity and the system pairing dialog can stay on top.
 *
 * Privacy: the lease notification shows only the target app's label and a fixed phase line. The task
 * summary, page text, coordinates and any pairing code never enter a notification.
 *
 * Identity: every PendingIntent carries a data URI with its kind, action, generation/epoch and this
 * process's nonce. Two attempts can never share a PendingIntent, and an intent minted by an earlier
 * process is refused by the receiver/service.
 */
internal class PhoneControlNotifications(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val control = NotificationChannel(CHANNEL_CONTROL, text(R.string.phone_notif_channel_control), NotificationManager.IMPORTANCE_LOW)
        control.description = text(R.string.phone_notif_channel_control_desc)
        control.setShowBadge(false)
        control.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val pairing =
            NotificationChannel(CHANNEL_PAIRING, text(R.string.phone_notif_channel_pairing), NotificationManager.IMPORTANCE_DEFAULT)
        pairing.description = text(R.string.phone_notif_channel_pairing_desc)
        pairing.setShowBadge(true)
        pairing.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        listOf(control, pairing).forEach { channel ->
            // Re-creating keeps the person's own importance choice on an existing channel.
            system.createNotificationChannel(channel)
        }
    }

    /**
     * True when the app may post a notification a person can see from another app.
     *
     * `areNotificationsEnabled()` alone only reflects the runtime permission and the app's own
     * switch; an independent `POST_NOTIFICATION` app-op denial (`adb appops set … deny`) is invisible
     * to it, so this also reads this app's own app-op. It never asks for notification state of another
     * app and needs no extra permission for its own UID.
     */
    fun notificationsAllowed(): Boolean = gate(channelAvailable = true) == PhoneNotificationsGate.AVAILABLE

    /**
     * The stop entry the grant gate requires: the platform switch, the runtime permission, this app's
     * POST_NOTIFICATION app-op and the control channel. `Unknown` only when the platform cannot
     * answer, which callers treat as unavailable (fail closed).
     */
    fun stopChannelState(): PhoneStopChannel =
        when (gate(channelAvailable = controlChannelAvailable())) {
            PhoneNotificationsGate.AVAILABLE -> PhoneStopChannel.Verified
            PhoneNotificationsGate.NOTIFICATIONS_DISABLED -> PhoneStopChannel.NotificationsDisabled
            PhoneNotificationsGate.CHANNEL_DISABLED -> PhoneStopChannel.ChannelDisabled
            PhoneNotificationsGate.UNKNOWN -> PhoneStopChannel.Unknown
        }

    private fun gate(channelAvailable: Boolean): PhoneNotificationsGate =
        phoneNotificationsGate(
            systemEnabled = manager.areNotificationsEnabled(),
            permissionGranted = runtimePermissionGranted(),
            appOpMode = appOpMode(),
            channelAvailable = channelAvailable,
        )

    private fun runtimePermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * This app's own `POST_NOTIFICATION` app-op, read only. Querying our own UID needs no app-op
     * permission; `unsafeCheckOpNoThrow` (API 29+) does not throw when the op is unknown to the
     * platform. Any exception or unknown value becomes null, which the gate treats as unknown.
     */
    private fun appOpMode(): PhonePostNotificationMode? =
        runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
            val uid = android.os.Process.myUid()
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(POST_NOTIFICATION_OP, uid, context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(POST_NOTIFICATION_OP, uid, context.packageName)
                }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> PhonePostNotificationMode.ALLOWED
                AppOpsManager.MODE_DEFAULT -> PhonePostNotificationMode.DEFAULT
                AppOpsManager.MODE_IGNORED -> PhonePostNotificationMode.IGNORED
                AppOpsManager.MODE_ERRORED -> PhonePostNotificationMode.ERRORED
                AppOpsManager.MODE_FOREGROUND -> PhonePostNotificationMode.FOREGROUND
                else -> PhonePostNotificationMode.UNKNOWN
            }
        }.getOrNull()

    private fun controlChannelAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val system = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = system.getNotificationChannel(CHANNEL_CONTROL) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * The foreground-service notification for a live lease: target app label plus a fixed phase line.
     * No task summary, no page text, no chat name.
     */
    fun running(
        epoch: Long,
        appLabel: String,
        paused: Boolean,
        /** A pause was requested while an action was still in flight; it is not settled yet. */
        pausing: Boolean = false,
    ): Notification {
        val pause =
            serviceAction(
                id = R.string.phone_notif_pause,
                intent = pauseIntentFor(context, epoch),
                requestCode = requestCode(epoch, ACTION_ID_PAUSE),
            )
        // The Return action is a direct Activity intent: Android 12+ blocks a notification trampoline
        // that starts an Activity from a service, and the person must land back in AndChamber.
        val back =
            activityAction(
                id = R.string.phone_notif_return,
                intent = returnIntentFor(context, epoch),
                requestCode = requestCode(epoch, ACTION_ID_RETURN),
            )
        // Fixed phases only: no task summary, no page text. "Pausing" must not claim the stop settled.
        val title =
            when {
                pausing -> text(R.string.phone_notif_pausing_title)
                paused -> text(R.string.phone_notif_paused_title)
                else -> context.getString(R.string.phone_notif_running_title, appLabel)
            }
        val body =
            when {
                pausing -> text(R.string.phone_notif_pausing_body)
                paused -> text(R.string.phone_notif_paused_body)
                else -> text(R.string.phone_notif_running_body)
            }
        return NotificationCompat.Builder(context, CHANNEL_CONTROL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openPhoneScreen())
            .addAction(pause)
            .addAction(back)
            .build()
    }

    /**
     * Step one: the transient pairing port from the system dialog. No content intent: tapping the body
     * must not open another Activity, because that would close the system pairing dialog.
     */
    fun pairingPort(generation: Long): Notification {
        val reply =
            remoteInputAction(
                action = PhonePairingActionReceiver.ACTION_PAIR_PORT,
                generation = generation,
                label = text(R.string.phone_pair_notif_port_label),
                key = PhonePairingActionReceiver.KEY_PORT,
                requestCode = requestCode(generation, ACTION_ID_PAIR_PORT),
            )
        return pairingBuilder()
            .setContentTitle(text(R.string.phone_pair_notif_title))
            .setContentText(text(R.string.phone_pair_notif_port_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(R.string.phone_pair_notif_port_body)))
            .addAction(reply)
            .addAction(cancelPairingAction(generation))
            .build()
    }

    /** Step two: the six-digit code from the same system dialog. No content intent, for the same reason. */
    fun pairingCode(generation: Long): Notification {
        val reply =
            remoteInputAction(
                action = PhonePairingActionReceiver.ACTION_PAIR_CODE,
                generation = generation,
                label = text(R.string.phone_pair_notif_code_label),
                key = PhonePairingActionReceiver.KEY_CODE,
                requestCode = requestCode(generation, ACTION_ID_PAIR_CODE),
            )
        return pairingBuilder()
            .setContentTitle(text(R.string.phone_pair_notif_title))
            .setContentText(text(R.string.phone_pair_notif_code_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(R.string.phone_pair_notif_code_body)))
            .setOnlyAlertOnce(true)
            .addAction(reply)
            .addAction(cancelPairingAction(generation))
            .build()
    }

    /** One bounded exchange is running; no further reply is accepted for this generation. */
    fun pairingBusy(generation: Long): Notification =
        pairingBuilder()
            .setContentTitle(text(R.string.phone_pair_notif_title))
            .setContentText(text(R.string.phone_pair_notif_busy))
            .setOnlyAlertOnce(true)
            .addAction(cancelPairingAction(generation))
            .build()

    /** Paired. The connect port still has to be read from the system screen by the person. */
    fun paired(): Notification =
        NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.phone_pair_notif_paired_title))
            .setContentText(text(R.string.phone_pair_notif_paired_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(R.string.phone_pair_notif_paired_body)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPhoneScreen())
            .build()

    /** Fixed failure text. This never claims the device's system authorization was revoked. */
    fun pairingFailed(outcome: PhonePairingOutcome): Notification {
        val body =
            when (outcome) {
                PhonePairingOutcome.CODE_REJECTED -> R.string.phone_pair_notif_failed_code
                PhonePairingOutcome.PORT_UNREACHABLE -> R.string.phone_pair_notif_failed_port
                PhonePairingOutcome.POLICY_REFUSED -> R.string.phone_pair_notif_failed_policy
                PhonePairingOutcome.TIMEOUT -> R.string.phone_pair_notif_failed_timeout
                PhonePairingOutcome.CANCELLED, PhonePairingOutcome.UNAVAILABLE -> R.string.phone_pair_notif_failed_other
                PhonePairingOutcome.PAIRED -> R.string.phone_pair_notif_paired_body
            }
        return NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.phone_pair_notif_title))
            .setContentText(text(body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(body)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPhoneScreen())
            .build()
    }

    fun notifyPairing(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    fun cancelPairing() {
        runCatching { manager.cancel(PAIRING_NOTIFICATION_ID) }
    }

    fun cancelControl() {
        runCatching { manager.cancel(CONTROL_NOTIFICATION_ID) }
    }

    private fun pairingBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(false)

    private fun cancelPairingAction(generation: Long): NotificationCompat.Action =
        broadcastAction(
            id = R.string.phone_notif_cancel_pairing,
            intent = pairingIntent(PhonePairingActionReceiver.ACTION_CANCEL, generation),
            requestCode = requestCode(generation, ACTION_ID_CANCEL),
        )

    private fun text(id: Int): String = context.getString(id)

    private fun remoteInputAction(
        action: String,
        generation: Long,
        label: String,
        key: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val input = RemoteInput.Builder(key).setLabel(label).build()
        val intent = pairingIntent(action, generation)
        // The system fills the RemoteInput result into this broadcast, so it must be mutable. The
        // reply never opens the UI and the system may not add generated contextual actions.
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    /** A notification action that starts our foreground service. */
    private fun serviceAction(
        id: Int,
        intent: Intent,
        requestCode: Int,
    ): NotificationCompat.Action {
        val pending =
            PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, text(id), pending).build()
    }

    /** A notification action that opens an invisible Activity (no service trampoline). */
    private fun activityAction(
        id: Int,
        intent: Intent,
        requestCode: Int,
    ): NotificationCompat.Action {
        val pending =
            PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, text(id), pending).build()
    }

    /** A notification action delivered to our broadcast receiver (pairing replies and cancel). */
    private fun broadcastAction(
        id: Int,
        intent: Intent,
        requestCode: Int,
    ): NotificationCompat.Action {
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, text(id), pending).build()
    }

    /** Pause stays a service action: it must not open any UI and must not wait for the model. */
    private fun serviceIntent(action: String, epoch: Long): Intent =
        Intent(context, PhoneControlService::class.java)
            .setAction(action)
            .setData(Uri.parse(phoneIntentData("service", actionName(action), epoch)))
            .putExtra(PhoneControlService.EXTRA_EPOCH, epoch)
            .putExtra(PhoneControlService.EXTRA_NONCE, PhoneProcessNonce.value)

    private fun pairingIntent(action: String, generation: Long): Intent =
        Intent(context, PhonePairingActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse(phoneIntentData("pair", actionName(action), generation)))
            .putExtra(PhonePairingActionReceiver.EXTRA_GENERATION, generation)
            .putExtra(PhonePairingActionReceiver.EXTRA_NONCE, PhoneProcessNonce.value)
            // Explicit component and package: the system cannot deliver this reply elsewhere.
            .setPackage(context.packageName)

    private fun openPhoneScreen(): PendingIntent =
        PendingIntent.getActivity(
            context,
            ACTION_ID_OPEN_SCREEN,
            Intent(context, MainActivity::class.java)
                .setAction(PhoneControlService.ACTION_OPEN_PHONE)
                .setData(Uri.parse(phoneIntentData("screen", "open", 0L)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionName(action: String): String = action.substringAfterLast('.').lowercase()

    companion object {
        /**
         * `AppOpsManager.OPSTR_POST_NOTIFICATION`, which is not exposed in the public SDK stubs used
         * by this build. The op name is stable platform API (API 33+, present earlier as a hidden op).
         */
        private const val POST_NOTIFICATION_OP = "android:post_notification"

        const val CHANNEL_CONTROL = "phone_control"
        const val CHANNEL_PAIRING = "phone_control_pairing"

        const val CONTROL_NOTIFICATION_ID = 4711
        const val PAIRING_NOTIFICATION_ID = 4712

        /** Request-code namespaces; each attempt/lease gets its own identity. */
        private const val ACTION_ID_PAUSE = 1
        private const val ACTION_ID_RETURN = 2
        private const val ACTION_ID_CANCEL = 3
        private const val ACTION_ID_PAIR_PORT = 4
        private const val ACTION_ID_PAIR_CODE = 5
        private const val ACTION_ID_OPEN_SCREEN = 7

        /**
         * A stable non-negative request code from a 64-bit generation/epoch and an action id. The
         * data URI is the real identity; the request code only keeps the system table tidy.
         */
        fun requestCode(value: Long, action: Int): Int {
            val folded = (value xor (value ushr 32)).toInt()
            return (folded * 31 + action) and 0x3FFF_FFFF
        }

        /** The invisible validation Activity that stops the lease and opens AndChamber. */
        internal fun returnIntentFor(context: Context, epoch: Long): Intent =
            Intent(context, PhoneControlReturnActivity::class.java)
                .setAction(PhoneControlService.ACTION_RETURN)
                .setData(Uri.parse(phoneIntentData("return", "return", epoch)))
                .putExtra(PhoneControlService.EXTRA_EPOCH, epoch)
                .putExtra(PhoneControlService.EXTRA_NONCE, PhoneProcessNonce.value)
                .setPackage(context.packageName)

        /** Pause stays in the foreground service; it never opens a screen. */
        internal fun pauseIntentFor(context: Context, epoch: Long): Intent =
            Intent(context, PhoneControlService::class.java)
                .setAction(PhoneControlService.ACTION_PAUSE)
                .setData(Uri.parse(phoneIntentData("service", "pause", epoch)))
                .putExtra(PhoneControlService.EXTRA_EPOCH, epoch)
                .putExtra(PhoneControlService.EXTRA_NONCE, PhoneProcessNonce.value)
    }
}
