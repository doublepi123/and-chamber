package io.andchamber.app.phone.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one short, user-confirmed phone-control lease visible while the person is in the controlled
 * app, with the Pause and Return controls that the grant gate requires.
 *
 * It is independent from the local runtime: nothing here starts, stops or reaches OpenChamber.
 *
 * Fail-closed rules:
 * - Every action (including the start request) must carry this process's nonce and the *live* lease
 *   epoch. A stale intent never resurrects the service and never touches a newer grant.
 * - [readyEpoch] is published only after `startForeground` and the epoch notification actually
 *   succeeded. The coordinator waits for it before publishing the grant route or launching the app.
 * - A service that dies, is stopped, or cannot enter the foreground revokes exactly the epoch it
 *   served (through the coordinator) and never a newer one. A stop entry that is gone means no lease.
 */
class PhoneControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    /** Epoch the current collector watches; a newer grant restarts it instead of stopping itself. */
    private var observedEpoch: Long? = null
    private var integration: PhoneIntegration? = null
    private var foreground = false
    private var servedEpoch: Long? = null

    override fun onCreate() {
        super.onCreate()
        integration = PhoneIntegrationHolder.current()
        if (integration == null) {
            // No graph (for example a resurrected service after process death): do nothing at all.
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val phone = integration ?: return START_NOT_STICKY
        val live = phone.liveLeaseEpoch()
        val epoch = intent?.getLongExtra(EXTRA_EPOCH, NO_EPOCH) ?: NO_EPOCH
        // The nonce proves the intent was minted by this process; the epoch proves it belongs to the
        // live lease. ACTION_START is checked exactly like the others: it must not revive an old state.
        val trusted = PhoneProcessNonce.matches(intent?.getStringExtra(EXTRA_NONCE))
        if (!trusted || !serviceActionIsCurrent(epoch, live)) {
            if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground(phone, epoch)) {
            // No usable stop entry: the lease must not continue.
            markNotReady()
            phone.onServiceLost(epoch)
            stopSelf()
            return START_NOT_STICKY
        }
        servedEpoch = epoch
        when (intent?.action) {
            ACTION_PAUSE -> phone.onNotificationPause(epoch)
            ACTION_RETURN, ACTION_STOP -> phone.onNotificationReturn(epoch)
            ACTION_START, ACTION_OPEN_PHONE, null -> Unit
        }
        observe(phone, epoch)
        return START_NOT_STICKY
    }

    private fun enterForeground(
        phone: PhoneIntegration,
        epoch: Long,
    ): Boolean {
        val notification = phone.serviceNotificationFor(epoch) ?: return false
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        val started =
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    PhoneControlNotifications.CONTROL_NOTIFICATION_ID,
                    notification,
                    type,
                )
            }.isSuccess
        foreground = started
        if (started) markReady(epoch) else markNotReady()
        return started
    }

    private fun observe(
        phone: PhoneIntegration,
        epoch: Long,
    ) {
        if (observedEpoch == epoch && observer != null) return
        observer?.cancel()
        observedEpoch = epoch
        observer =
            scope.launch {
                phone.state.collect {
                    // A newer grant (or none) means this instance is not the stop entry any more.
                    if (serviceShouldStop(epoch, phone.liveLeaseEpoch())) {
                        stopSelf()
                        return@collect
                    }
                    updateNotification(phone, epoch)
                }
            }
    }

    private fun updateNotification(
        phone: PhoneIntegration,
        epoch: Long,
    ) {
        if (!foreground) return
        val notification = phone.serviceNotificationFor(epoch) ?: return
        runCatching {
            ServiceCompat.startForeground(
                this,
                PhoneControlNotifications.CONTROL_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        observedEpoch = null
        scope.cancel()
        markNotReady()
        if (foreground) {
            // Only this service's own notification.
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        }
        foreground = false
        // The stop entry is gone. If the epoch it served is still live, the lease cannot continue.
        val epoch = servedEpoch
        servedEpoch = null
        integration?.let { phone ->
            if (epoch != null && !serviceShouldStop(epoch, phone.liveLeaseEpoch())) {
                phone.onServiceLost(epoch)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE = "io.andchamber.app.phone.action.PAUSE"
        const val ACTION_RETURN = "io.andchamber.app.phone.action.RETURN"
        const val ACTION_STOP = "io.andchamber.app.phone.action.STOP"
        const val ACTION_OPEN_PHONE = "io.andchamber.app.phone.action.OPEN_PHONE"

        /** A neutral start action: it only raises the foreground notification for a live epoch. */
        const val ACTION_START = "io.andchamber.app.phone.action.START"

        const val EXTRA_EPOCH = "lease_epoch"
        const val EXTRA_NONCE = "intent_nonce"

        private const val NO_EPOCH = -1L

        /** Epoch accepted by startForeground, or null; not proof of display or delivery. */
        private val ready = MutableStateFlow<Long?>(null)

        /** True once `startForeground` succeeded for [epoch] in this process. */
        val readyEpoch: StateFlow<Long?> = ready.asStateFlow()

        private fun markReady(epoch: Long) {
            ready.value = epoch
        }

        private fun markNotReady() {
            ready.value = null
        }

        /**
         * Bounded acknowledgement that the service is a real foreground stop entry for [epoch].
         * Returns false on timeout, on a rejected start, or when another epoch became ready.
         */
        suspend fun awaitReady(
            epoch: Long,
            timeoutMs: Long = READY_TIMEOUT_MS,
        ): Boolean =
            withTimeoutOrNull(timeoutMs) {
                ready.first { it == epoch }
            } != null

        /** Asks the platform to start the service. A true result only means the request was accepted. */
        fun start(
            context: Context,
            epoch: Long,
        ): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, PhoneControlService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_EPOCH, epoch)
                        .putExtra(EXTRA_NONCE, PhoneProcessNonce.value),
                )
            }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PhoneControlService::class.java)) }
        }

        const val READY_TIMEOUT_MS = 2_000L
    }
}
