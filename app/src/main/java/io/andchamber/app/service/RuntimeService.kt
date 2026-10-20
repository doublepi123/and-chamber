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
import io.andchamber.app.i18n.UserMessages
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RuntimeOperation
import io.andchamber.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Hosts runtime work in the foreground for the whole life of the service.
 *
 * The service is started with an operation intent and calls [startForegroundCompat] first, then
 * hands the operation to the controller. It then observes the controller for its entire life and
 * applies [RuntimeServicePolicy] to (busy, state) on every change: it stays foreground while work is
 * queued or running, or while the server is Running or Starting, and stops itself otherwise.
 *
 * The wake lock is held exactly while the service is foreground ([WakeHold] is released on every
 * path that leaves the foreground, including onDestroy).
 */
class RuntimeService : Service() {
    private val runtime: LocalRuntimeController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var foreground = false

    /** True once an intent has been handled; the observer ignores resting states before that. */
    private var startedOnce = false

    /** The newest startId seen. Stopping uses it so an older request's completion cannot stop a newer one. */
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        observer =
            scope.launch {
                combine(runtime.busy, runtime.state) { busy, state -> busy to state }.collect { (busy, state) ->
                    apply(RuntimeServicePolicy.decide(busy, state, startedOnce), state)
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Barrier: foreground first, always, before anything touches the controller.
        enterForeground(buildNotification(runtime.state.value))
        latestStartId = startId
        startedOnce = true
        when (intent?.action) {
            ACTION_OPERATION -> {
                intent.getStringExtra(EXTRA_OPERATION)?.let(RuntimeOperation::valueOf)?.let(runtime::perform)
            }
            ACTION_STOP_NOTIFICATION -> runtime.perform(RuntimeOperation.Stop)
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

    /** Applies the policy: the notification follows the state, the foreground follows the policy. */
    private fun apply(
        action: RuntimeServicePolicy.Action,
        state: RuntimeState,
    ) {
        when (action) {
            RuntimeServicePolicy.Action.KeepRunning -> {
                if (foreground) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                } else {
                    enterForeground(buildNotification(state))
                }
                WakeHold.acquire(this)
            }
            RuntimeServicePolicy.Action.Finish -> leaveForeground()
        }
    }

    private fun enterForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        foreground = true
    }

    private fun leaveForeground() {
        WakeHold.release()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        // Stop only if no newer start request arrived since the one this completion belongs to.
        if (!stopSelfResult(latestStartId)) return
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
        val fallback =
            if (state.updating) R.string.runtime_notification_updating else R.string.runtime_notification_setup
        return (state.detail ?: getString(fallback)) + percent
    }

    private fun RuntimeState.label(): String =
        when (this) {
            is RuntimeState.Failed -> UserMessages.failureText(this@RuntimeService, this)
            RuntimeState.NotInstalled -> getString(R.string.runtime_notification_not_installed)
            RuntimeState.Stopped -> getString(R.string.runtime_notification_stopped)
            RuntimeState.UnsupportedAbi -> getString(R.string.runtime_notification_unsupported)
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

        /** Re-applied on every start so a language change renames the channel in system settings. */
        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val name = context.getString(R.string.runtime_notification_channel)
            val description = context.getString(R.string.runtime_notification_channel_description)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                if (existing.name != name || existing.description != description) {
                    existing.name = name
                    existing.description = description
                    manager.createNotificationChannel(existing)
                }
                return
            }
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                    this.description = description
                    setShowBadge(false)
                },
            )
        }
    }
}
