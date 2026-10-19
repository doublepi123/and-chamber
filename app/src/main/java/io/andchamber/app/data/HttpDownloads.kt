package io.andchamber.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.andchamber.app.R
import io.andchamber.app.runtime.WebSessionCookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * A plain http(s) file download requested by the web page (a link or a non-blob response), run
 * natively instead of through DownloadManager so the session cookie can be applied safely.
 *
 * [userAgent] is the only header taken from the page; no other header is accepted.
 */
data class HttpDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val userAgent: String?,
)

sealed interface HttpDownloadResult {
    data class Saved(val fileName: String) : HttpDownloadResult

    data class Failed(val reason: String?) : HttpDownloadResult
}

interface HttpDownloadStarter {
    /**
     * Starts a download in the background. [onFinished] is called exactly once on a background
     * thread, unless the starter's scope is cancelled first. It is not called on the main thread,
     * so UI code must post to the main thread.
     *
     * The download is not tied to any screen: leaving the page does not cancel it. It is not
     * resumed after the process dies, and partial output is removed.
     */
    fun start(
        cookies: WebSessionCookies,
        request: HttpDownloadRequest,
        onFinished: (HttpDownloadResult) -> Unit,
    )
}

/**
 * Android implementation. Runs on [scope] (application-level by default, so leaving the page does not
 * cancel it), saves through [destinationFor], and shows progress and result notifications only when
 * the app has notification permission.
 */
class AndroidHttpDownloadStarter(
    context: Context,
    client: OkHttpClient = newHttpDownloadClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : HttpDownloadStarter {
    private val appContext = context.applicationContext
    private val transfer = HttpDownloadTransfer(client)
    private val notifier = DownloadNotifier(appContext)

    override fun start(
        cookies: WebSessionCookies,
        request: HttpDownloadRequest,
        onFinished: (HttpDownloadResult) -> Unit,
    ) {
        val notificationId = notifier.nextId()
        val displayName = sanitizeDownloadFileName(request.fileName)
        scope.launch {
            var result: HttpDownloadResult = HttpDownloadResult.Failed("Download was cancelled")
            try {
                runCatching { notifier.started(notificationId, displayName) }
                result =
                    transfer.download(
                        cookies = cookies,
                        request = request,
                        destination = destinationFor(appContext),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                result = HttpDownloadResult.Failed(error.message ?: error.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) { notifier.finished(notificationId, result) }
            }
            // A failing observer must not take the app scope down with it.
            runCatching { onFinished(result) }
        }
    }
}

/** Notifications for background downloads: one ongoing entry, updated in place to its result. */
internal class DownloadNotifier(private val context: Context) {
    private val ids = AtomicInteger(BASE_ID)

    fun nextId(): Int = ids.getAndIncrement()

    fun started(
        id: Int,
        name: String,
    ) {
        if (!canPost()) return
        ensureChannel()
        post(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.downloading, name))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    fun finished(
        id: Int,
        result: HttpDownloadResult,
    ) {
        if (!canPost()) return
        ensureChannel()
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        when (result) {
            is HttpDownloadResult.Saved -> {
                builder.setContentTitle(context.getString(R.string.saved_to_downloads, result.fileName))
                builder.setContentIntent(activityIntent(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS), id))
            }
            is HttpDownloadResult.Failed -> {
                builder.setContentTitle(context.getString(R.string.download_failed))
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    builder.setContentIntent(activityIntent(it, id))
                }
            }
        }
        post(id, builder.build())
    }

    @SuppressLint("MissingPermission") // Callers check canPost() first.
    private fun post(
        id: Int,
        notification: Notification,
    ) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun activityIntent(
        intent: Intent,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Runtime permission (API 33+) and the user's global switch for the app's notifications. */
    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Reuses the existing "Other" channel label so no new string resource is needed.
        val label = context.getString(R.string.channel_other)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW))
    }

    private companion object {
        const val CHANNEL_ID = "http_downloads"

        /** Kept clear of the runtime notification ids, which are tag hashes. */
        const val BASE_ID = 0x6A000000
    }
}
