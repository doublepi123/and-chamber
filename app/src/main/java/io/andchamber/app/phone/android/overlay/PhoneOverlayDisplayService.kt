package io.andchamber.app.phone.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.phone.android.PhoneControlService
import io.andchamber.app.phone.android.PhoneProcessNonce
import io.andchamber.app.notify.OpenChamberNotifier
import java.util.concurrent.atomic.AtomicLong

/**
 * Shows the overlay surface for one task, independent from the lease.
 *
 * It follows the task, not the grant: after the lease ends it may still show the take-over, waiting or
 * needs-check state for a bounded time (the coordinator stops it). It never starts a lease, never
 * resumes one, and `onDestroy` removes the window without reviving control.
 *
 * Every notification action carries this instance's owner (nonce + instance + opaque task owner).
 * A stale close from an older notification is a no-op, and a close while the lease is live is refused
 * by the coordinator (which offers the end confirmation instead); the service then stays alive instead
 * of disappearing and leaving a live task without its stop surface.
 */
class PhoneOverlayDisplayService : Service() {
    private var foreground = false
    private var bridge: PhoneOverlayDisplayBridge? = null
    private var owner: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val current = PhoneOverlayHolder.current()?.let(::CoordinatorDisplayBridge)
        bridge = current
        if (current == null) {
            stopSelf()
            return
        }
        val minted =
            PhoneOverlayDisplayOwner.mint(
                nonce = PhoneProcessNonce.value,
                instance = NEXT_INSTANCE.getAndIncrement(),
                taskOwner = current.taskOwner(),
            )
        owner = minted
        if (!enterForeground(minted)) {
            // No visible stop entry: the native side must not keep a lease it cannot show.
            current.onPermissionLost(minted)
            stopSelf()
            return
        }
        current.onServiceStarted(minted)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val bridge = bridge ?: return START_NOT_STICKY
        val current = owner ?: return START_NOT_STICKY
        val provided =
            intent?.getStringExtra(EXTRA_OWNER)
                ?: PhoneOverlayDisplayOwner.fromData(intent?.dataString)
        val nonce = intent?.getStringExtra(EXTRA_NONCE)
            ?: io.andchamber.app.phone.android.phoneIntentNonce(intent?.dataString)
        // Both the process nonce and the exact service owner must match. An old intent cannot act on
        // a newer display instance, and a forged one cannot act at all.
        val trusted = PhoneProcessNonce.matches(nonce) && PhoneOverlayDisplayOwner.matches(provided, current)
        when (intent?.action) {
            ACTION_CLOSE -> {
                if (!trusted) return START_NOT_STICKY
                val closed = bridge.onCloseRequested(current)
                // Only a real close stops this instance; a refused close (live lease/not ended) keeps
                // the stop surface and the notification alive.
                if (closed) stopSelfResult(startId)
            }
            ACTION_SHOW, null -> {
                if (!trusted) return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(owner: String): Boolean {
        val notification = notification(owner)
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        val started =
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }.isSuccess
        foreground = started
        return started
    }

    private fun notification(owner: String): Notification {
        val requestCode = owner.hashCode() and 0x3FFF_FFFF
        val open =
            PendingIntent.getActivity(
                this,
                requestCode,
                openNativeIntent(this, owner),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val close =
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, PhoneOverlayDisplayService::class.java)
                    .setAction(ACTION_CLOSE)
                    .setData(Uri.parse(PhoneOverlayDisplayOwner.closeIntentData(owner)))
                    .putExtra(EXTRA_OWNER, owner)
                    .putExtra(EXTRA_NONCE, PhoneProcessNonce.value),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.phone_overlay_notif_title))
            .setContentText(getString(R.string.phone_overlay_notif_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.phone_overlay_notif_body)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, getString(R.string.phone_overlay_notif_open), open)
            .addAction(R.drawable.ic_notification, getString(R.string.phone_overlay_notif_close), close)
            .build()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val current = owner ?: return
        bridge?.onConfigurationChanged(current)
    }

    override fun onDestroy() {
        val current = owner
        owner = null
        bridge?.let { service -> current?.let(service::onServiceDestroyed) }
        bridge = null
        if (foreground) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        }
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        val channel =
            NotificationChannel(
                CHANNEL,
                getString(R.string.phone_overlay_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = getString(R.string.phone_overlay_notif_channel_desc)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        system.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "phone_overlay"
        const val NOTIFICATION_ID = 4713

        private const val ACTION_SHOW = "io.andchamber.app.phone.overlay.SHOW"
        private const val ACTION_CLOSE = "io.andchamber.app.phone.overlay.CLOSE"
        private const val EXTRA_OWNER = "display_owner"
        private const val EXTRA_NONCE = "intent_nonce"

        private val NEXT_INSTANCE = AtomicLong(1L)

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PhoneOverlayDisplayService::class.java)
                        .setAction(ACTION_SHOW)
                        .putExtra(EXTRA_NONCE, PhoneProcessNonce.value),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PhoneOverlayDisplayService::class.java)) }
        }

        /**
         * The overlay's "open AndChamber" is a direct Activity intent (never a service trampoline).
         * The overlay window stays attached until MainActivity is really resumed and asks to close it.
         */
        fun openNativeIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(PhoneControlService.ACTION_OPEN_PHONE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        internal fun openNativeIntent(
            context: Context,
            owner: String,
        ): Intent =
            openNativeIntent(context)
                .setData(Uri.parse(PhoneOverlayDisplayOwner.openIntentData(owner)))
                .putExtra(EXTRA_OWNER, owner)
                .putExtra(EXTRA_NONCE, PhoneProcessNonce.value)

        /**
         * The overlay's "open chat": an explicit Activity intent for the current/retained task's
         * session. A null session still opens the app's local screen. It grants nothing, resumes
         * nothing and does not revoke the lease.
         */
        fun openChatIntent(
            context: Context,
            sessionId: String?,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(OpenChamberNotifier.ACTION_OPEN_SESSION)
                .apply {
                    sessionId?.takeIf { it.isNotBlank() }?.let {
                        putExtra(OpenChamberNotifier.EXTRA_SESSION_ID, it)
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
