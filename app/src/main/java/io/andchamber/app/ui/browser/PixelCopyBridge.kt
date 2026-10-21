package io.andchamber.app.ui.browser

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.view.PixelCopy
import android.view.Window
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * PixelCopy ownership seam. The platform callback may still write the bitmap
 * after the caller is cancelled, so an accepted request is recycled only from
 * that callback, or from the continuation when a resumed bitmap is never read.
 *
 * [onRequested] fires after [PixelCopy.request] has been issued. It exists so
 * an androidTest can move the frame or hold the listener. It is not a user
 * capability. Production passes null. A throw from this hook means the copy
 * is already queued: it must not recycle the bitmap.
 */
internal class PixelCopyBridge(
    private val request: (
        Window,
        Rect,
        Bitmap,
        PixelCopy.OnPixelCopyFinishedListener,
        Handler,
    ) -> Unit = { window, rect, bitmap, listener, handler ->
        PixelCopy.request(window, rect, bitmap, listener, handler)
    },
    private val onRequested: (() -> Unit)? = null,
) {
    suspend fun copy(
        window: Window,
        rect: Rect,
        width: Int,
        height: Int,
        handler: Handler,
        isCurrent: () -> Boolean,
    ): Bitmap? {
        if (width < 1 || height < 1) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var callbackSettled = false
        return awaitOwnedResult(
            start = { deliver ->
                try {
                    request(window, rect, bitmap, PixelCopy.OnPixelCopyFinishedListener { result ->
                        settleOwnedCopy(
                            resource = bitmap,
                            success = result == PixelCopy.SUCCESS,
                            current = isCurrent(),
                            deliver = deliver,
                            dispose = { value -> if (!value.isRecycled) value.recycle() },
                            alreadySettled = { callbackSettled },
                            markSettled = { callbackSettled = true },
                        )
                    }, handler)
                } catch (error: Exception) {
                    // request threw before the platform accepted the copy.
                    // No callback will run, so this path owns the bitmap.
                    if (!bitmap.isRecycled) bitmap.recycle()
                    throw error
                }
                onRequested?.invoke()
            },
            dispose = { value -> if (value != null && !value.isRecycled) value.recycle() },
        )
    }
}

/**
 * One callback settlement. A failed or stale copy disposes [resource] here,
 * then delivers null. A successful current copy delivers the same instance.
 * A second call for that same instance is a no-op, so it cannot recycle twice.
 */
internal fun <T> settleOwnedCopy(
    resource: T,
    success: Boolean,
    current: Boolean,
    deliver: (T?) -> Unit,
    dispose: (T) -> Unit,
    alreadySettled: (T) -> Boolean = { false },
    markSettled: (T) -> Unit = {},
) {
    if (alreadySettled(resource)) return
    markSettled(resource)
    if (!success || !current) {
        dispose(resource)
        deliver(null)
    } else {
        deliver(resource)
    }
}

/**
 * Transfers [T] only when the caller is still waiting. Cancellation before the
 * callback does not dispose: the platform may still be writing. A resumed
 * value that the caller never reads is disposed for any cancellation cause.
 * A throw from [start] completes the continuation with that exception; it does
 * not also resume null. A second delivery is ignored.
 */
internal suspend fun <T> awaitOwnedResult(
    start: (deliver: (T?) -> Unit) -> Unit,
    dispose: (T?) -> Unit,
): T? = suspendCancellableCoroutine { continuation ->
    var settled = false
    val deliver: (T?) -> Unit = deliver@{ value ->
        if (settled) return@deliver
        settled = true
        if (!continuation.isActive) {
            dispose(value)
            return@deliver
        }
        continuation.resume(value) { _, delivered, _ -> dispose(delivered) }
    }
    try {
        start(deliver)
    } catch (error: Exception) {
        if (!settled && continuation.isActive) continuation.resumeWithException(error)
    }
}
