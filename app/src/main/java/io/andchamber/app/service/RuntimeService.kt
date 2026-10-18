package io.andchamber.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the OpenChamber server alive in the background. Shows an ongoing notification with the
 * current state and a Stop action, and holds a partial wake lock while installing or running.
 * Stops itself once the runtime is Stopped, NotInstalled or Failed.
 */
class RuntimeService : Service() {
    private val runtime: LocalRuntimeController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        scope.launch {
            runtime.state.collectLatest { state ->
                updateNotification(state)
                updateWakeLock(state)
                if (state.shouldStopSelf()) {
                    stopSelfCompat()
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> runtime.stop()
        }
        val initial = runtime.state.value
        startForegroundCompat(buildNotification(initial))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun updateNotification(state: RuntimeState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * Held from install/start until Running (or until stopped/failed). Dozing otherwise freezes the
     * CPU mid-startup, and OpenChamber's internal readiness probe times out.
     */
    private fun updateWakeLock(state: RuntimeState) {
        val keepAwake =
            state is RuntimeState.Installing ||
                state is RuntimeState.Starting ||
                state is RuntimeState.Running
        if (keepAwake) WakeHold.acquire(this) else WakeHold.release()
    }

    private fun releaseWakeLock() = WakeHold.release()

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: RuntimeState): Notification {
        val (text, ongoing) =
            when (state) {
                is RuntimeState.Installing -> setupText(state) to true
                is RuntimeState.Starting -> getString(R.string.runtime_notification_starting) to true
                is RuntimeState.Running -> getString(R.string.runtime_notification_running, state.url) to true
                is RuntimeState.Stopping -> getString(R.string.runtime_notification_stopping) to true
                else -> (state.label()) to false
            }
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.runtime_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (state is RuntimeState.Running || state is RuntimeState.Starting) {
                    addAction(0, getString(R.string.runtime_notification_stop), stopIntent)
                }
            }
            .build()
    }

    private fun setupText(state: RuntimeState.Installing): String {
        val percent = state.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return (state.detail ?: getString(R.string.runtime_notification_setup)) + percent
    }

    private fun RuntimeState.label(): String =
        when (this) {
            is RuntimeState.Failed -> reason
            RuntimeState.NotInstalled -> "Not installed"
            RuntimeState.Stopped -> "Stopped"
            RuntimeState.UnsupportedAbi -> "Unsupported device"
            else -> ""
        }

    private fun RuntimeState.shouldStopSelf(): Boolean =
        this is RuntimeState.Stopped || this is RuntimeState.NotInstalled || this is RuntimeState.Failed

    companion object {
        const val ACTION_STOP = "io.andchamber.app.action.STOP_RUNTIME"
        private const val CHANNEL_ID = "openchamber-runtime"
        private const val NOTIFICATION_ID = 4101

        /**
         * Brings the service up in the foreground before the controller starts any network work.
         * Must be called while the app is in the foreground (from a UI action).
         */
        fun start(context: Context) {
            WakeHold.acquire(context)
            val intent = Intent(context, RuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Called by the controller on every state change. Starts the service while work is in
         * flight (install, start, running) and does nothing otherwise; the service stops itself on
         * Stopped, NotInstalled or Failed.
         */
        fun sync(
            context: Context,
            state: RuntimeState,
        ) {
            val keepAlive =
                state is RuntimeState.Installing ||
                    state is RuntimeState.Starting ||
                    state is RuntimeState.Running
            if (!keepAlive) return
            val intent = Intent(context, RuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.runtime_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.runtime_notification_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
