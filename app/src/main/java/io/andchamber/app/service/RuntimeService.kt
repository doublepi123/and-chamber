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
import io.andchamber.app.runtime.RuntimeOperation
import io.andchamber.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Hosts runtime work in the foreground.
 *
 * Lifetime is the operation's, not the state's: the service is started with an operation intent,
 * calls [startForeground] first, then hands the operation to the controller. It stops only when
 * the controller reaches a terminal state after that operation. Intermediate states (Stopped between
 * install and start, Detecting, progress ticks) never stop it. The wake lock is held while work runs
 * and released on terminal states, on exceptions, and in onDestroy.
 */
class RuntimeService : Service() {
    private val runtime: LocalRuntimeController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    /** True between an operation intent and its terminal state. Read on the main thread only. */
    private var operationActive = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        observer =
            scope.launch {
                runtime.state.collectLatest { state ->
                    if (operationActive) updateNotification(state)
                    if (operationActive && state.isTerminal()) finishOperation()
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Barrier: foreground first, always, before anything touches the controller.
        startForegroundCompat(buildNotification(runtime.state.value))
        WakeHold.acquire(this)
        when (intent?.action) {
            ACTION_OPERATION -> {
                val operation = intent.getStringExtra(EXTRA_OPERATION)?.let(RuntimeOperation::valueOf)
                if (operation != null) {
                    operationActive = true
                    runtime.perform(operation)
                }
            }
            ACTION_STOP_NOTIFICATION -> {
                operationActive = true
                runtime.perform(RuntimeOperation.Stop)
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WakeHold.release()
        observer?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    /** Terminal states end the operation; the service then stops itself. */
    private fun RuntimeState.isTerminal(): Boolean =
        this is RuntimeState.Stopped ||
            this is RuntimeState.NotInstalled ||
            this is RuntimeState.Failed ||
            this is RuntimeState.UnsupportedAbi ||
            this is RuntimeState.Running

    private fun finishOperation() {
        operationActive = false
        // Running keeps the service (and its notification) alive as the server's lifetime.
        if (runtime.state.value is RuntimeState.Running) {
            WakeHold.acquire(this)
            return
        }
        WakeHold.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RuntimeState): Notification {
        val (text, ongoing) =
            when (state) {
                is RuntimeState.Installing -> setupText(state) to true
                is RuntimeState.Starting -> getString(R.string.runtime_notification_starting) to true
                is RuntimeState.Running -> getString(R.string.runtime_notification_running, state.url) to true
                is RuntimeState.Stopping -> getString(R.string.runtime_notification_stopping) to true
                else -> state.label() to false
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
                Intent(this, RuntimeService::class.java).setAction(ACTION_STOP_NOTIFICATION),
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

    companion object {
        private const val ACTION_OPERATION = "io.andchamber.app.action.OPERATION"
        private const val ACTION_STOP_NOTIFICATION = "io.andchamber.app.action.STOP_FROM_NOTIFICATION"
        private const val EXTRA_OPERATION = "operation"
        private const val CHANNEL_ID = "openchamber-runtime"
        private const val NOTIFICATION_ID = 4101

        /** Starts the service in the foreground for [operation]. Called from the UI, in the foreground. */
        fun request(
            context: Context,
            operation: RuntimeOperation,
        ) {
            val intent =
                Intent(context, RuntimeService::class.java)
                    .setAction(ACTION_OPERATION)
                    .putExtra(EXTRA_OPERATION, operation.name)
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
