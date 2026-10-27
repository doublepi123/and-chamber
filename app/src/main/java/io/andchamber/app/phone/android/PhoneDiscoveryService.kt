package io.andchamber.app.phone.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Bounded foreground lifetime for the person's explicit wireless-debugging pairing.
 *
 * The notification that started this flow is the foreground-service entry: the person tapped Pair in
 * AndChamber, so `startForegroundService` is allowed. [onStartCommand] calls `startForeground`
 * immediately with a dedicated searching notification, never a lease-dependent one, so a missing lease
 * can no longer crash the service.
 *
 * It is deliberately not sticky and not a background searcher:
 * - the explicit pairing search, then one short connect search, are each bounded;
 * - cancel, disable, pairing completion, a failed/ambiguous outcome or the bound stops the service;
 * - [onDestroy] stops NSD and releases the API 30-32 multicast lock.
 *
 * It reports fixed [PhoneDiscoverySnapshot] values to the process-wide integration; it never pairs,
 * connects, grants, persists a port/code or writes a history.
 */
class PhoneDiscoveryService : Service() {
    private var discovery: PhonePortDiscovery? = null
    private var generation = 0L
    private var foreground = false
    private var integration: PhoneIntegration? = null

    override fun onCreate() {
        super.onCreate()
        integration = PhoneIntegrationHolder.current()
        running = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != ACTION_START_PAIR) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requested = intent.getLongExtra(EXTRA_GENERATION, NO_GENERATION)
        val trusted = PhoneProcessNonce.matches(intent.getStringExtra(EXTRA_NONCE))
        if (!trusted || requested <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        generation = requested
        // Must happen before any other work. A non-null searching notification is always available;
        // this path never depends on a live lease.
        if (!enterForeground(generation)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val phone = integration
        if (phone == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (discovery != null) {
            // A newer explicit attempt may arrive before the previous stop was destroyed. Never let the
            // old ledger publish under the new generation: replace it with a fresh session.
            discovery?.stop()
            discovery = null
        }
        val created = newDiscovery()
        discovery = created
        // Pairing first. The connect type is searched only after a real pairing success, so a stale
        // pre-pairing advertisement can never be selected as the automatic target.
        if (!created.start(setOf(PhoneDiscoveryKind.PAIRING), MAX_LIFETIME_MS)) {
            discovery = null
            created.stop()
            phone.onDiscoveryUnavailable(generation)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun newDiscovery(): PhonePortDiscovery =
        PhonePortDiscovery(this) { pairing, connect ->
            integration?.onDiscoverySnapshot(
                PhoneDiscoverySnapshot(
                    generation = generation,
                    pairing = pairing,
                    connect = connect,
                ),
            )
            if (pairing.isTerminal() && connect.isTerminal()) stopSelf()
        }

    /**
     * The exact explicit pairing just succeeded. Replace pairing discovery with a fresh, short connect
     * search, so only an endpoint advertised after pairing is considered. Called in-process from the
     * integration on the main thread; returns false when this instance is not the one for [generation].
     */
    internal fun startConnectPhase(generation: Long): Boolean {
        if (!foreground || generation != this.generation) return false
        discovery?.stop()
        val created = newDiscovery()
        discovery = created
        if (!created.start(setOf(PhoneDiscoveryKind.CONNECT), CONNECT_PHASE_MS)) {
            discovery = null
            created.stop()
            // Pairing itself succeeded; the caller keeps `paired` and reports the connect search state.
            // Never route this through the pairing-unavailable path.
            stopSelf()
            return false
        }
        return true
    }

    private fun enterForeground(generation: Long): Boolean {
        val notification =
            runCatching { PhoneControlNotifications(this).pairingSearching(generation) }.getOrNull()
                ?: return false
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        foreground =
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    PhoneControlNotifications.PAIRING_NOTIFICATION_ID,
                    notification,
                    type,
                )
            }.isSuccess
        return foreground
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        if (running === this) running = null
        val finishedGeneration = generation
        generation = 0L
        if (foreground) {
            // Detach rather than remove: a fixed failure/paired status posted around the stop stays
            // visible. Cancel/disable paths cancel the notification explicitly before stopping.
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }
        }
        foreground = false
        integration?.onDiscoveryServiceStopped(finishedGeneration)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_PAIR = "io.andchamber.app.phone.action.START_PAIR_DISCOVERY"
        const val ACTION_STOP = "io.andchamber.app.phone.action.STOP_PAIR_DISCOVERY"

        const val EXTRA_GENERATION = "discovery_generation"
        const val EXTRA_NONCE = "intent_nonce"

        /** One explicit pairing, including reading the code, is bounded to this long. */
        const val MAX_LIFETIME_MS: Long = 5 * 60_000L

        /** After pairing succeeds, one short connect search; no pre-pairing endpoint is reused. */
        const val CONNECT_PHASE_MS: Long = 60_000L

        private const val NO_GENERATION = -1L

        /** The instance for this process, so the main thread can switch phases without a background start. */
        @Volatile
        private var running: PhoneDiscoveryService? = null

        internal fun requestConnectPhase(generation: Long): Boolean {
            val service = running ?: return false
            if (service.generation != generation || !service.foreground) return false
            return service.startConnectPhase(generation)
        }

        /**
         * Asks the platform to start the bounded foreground discovery. A true result only means the
         * start request was accepted; [PhoneIntegration] waits for real discovery updates.
         */
        fun startPairing(
            context: Context,
            generation: Long,
        ): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, PhoneDiscoveryService::class.java)
                        .setAction(ACTION_START_PAIR)
                        .putExtra(EXTRA_GENERATION, generation)
                        .putExtra(EXTRA_NONCE, PhoneProcessNonce.value),
                )
            }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PhoneDiscoveryService::class.java)) }
        }
    }
}
