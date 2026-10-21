package io.andchamber.app.ui.browser

import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException

/**
 * Proves the hidden `MotionEvent.setActionButton` round trip before mouse injection is offered.
 * A missing or non-reading setter is unsupported. Callers must disable mouse and keep direct touch;
 * they must not fall back to touch synthesis.
 */
internal class NativeMouseSupport(
    private val session: MouseProbeSession = ReflectionMouseProbeSession(),
) {
    private var cached: MouseSupport? = null

    /** One successful probe is cached. A later failed apply clears that claim. */
    fun probe(): MouseSupport {
        cached?.let { return it }
        val result = session.probe()
        cached = result
        return result
    }

    /**
     * Sets [button] on an already constructed `ACTION_BUTTON_PRESS` or `RELEASE` event and reads
     * [MotionEvent.getActionButton] back. Failure updates the cached probe so a later probe cannot
     * keep claiming support.
     */
    fun applyActionButton(event: MotionEvent, button: Int): MouseSupport {
        val result = session.apply(event, button)
        if (result !is MouseSupport.Supported) cached = result
        return result
    }

    /** Pure round trip used by JVM tests. Does not construct or dispatch an input event. */
    internal fun applyRoundTrip(requested: Int, readBack: Int): MouseSupport {
        val result = mouseSupportFromRoundTrip(requested, readBack, null)
        if (result !is MouseSupport.Supported) cached = result
        return result
    }

    /** Pure failure mapping used by JVM tests. Does not construct or dispatch an input event. */
    internal fun applyFailure(error: Throwable): MouseSupport {
        val result = mouseSupportFromRoundTrip(MotionEvent.BUTTON_PRIMARY, null, error)
        cached = result
        return result
    }
}

internal sealed class MouseSupport {
    data object Supported : MouseSupport()

    data class Unsupported(val reason: String) : MouseSupport()
}

internal interface MouseProbeSession {
    fun probe(): MouseSupport

    fun apply(event: MotionEvent, button: Int): MouseSupport
}

internal class ReflectionMouseProbeSession : MouseProbeSession {
    override fun probe(): MouseSupport {
        val event = try {
            val now = android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_BUTTON_PRESS, 0f, 0f, 0)
        } catch (error: Throwable) {
            return mouseSupportFromRoundTrip(MotionEvent.BUTTON_PRIMARY, null, error)
        } ?: return mouseSupportFromRoundTrip(MotionEvent.BUTTON_PRIMARY, null, IllegalStateException("event unavailable"))
        return try {
            apply(event, MotionEvent.BUTTON_PRIMARY)
        } finally {
            runCatching { event.recycle() }
        }
    }

    override fun apply(event: MotionEvent, button: Int): MouseSupport =
        try {
            val method = MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
            method.invoke(event, button)
            mouseSupportFromRoundTrip(button, event.actionButton, null)
        } catch (error: Throwable) {
            mouseSupportFromRoundTrip(button, null, error)
        }
}

internal fun mouseSupportFromRoundTrip(
    requested: Int,
    readBack: Int?,
    failure: Throwable?,
): MouseSupport {
    failure?.let { return MouseSupport.Unsupported(reasonCode(it)) }
    return if (readBack == requested) {
        MouseSupport.Supported
    } else {
        MouseSupport.Unsupported("ACTION_BUTTON_MISMATCH")
    }
}

private fun reasonCode(error: Throwable): String =
    when (unwrap(error)) {
        is NoSuchMethodException -> "NO_SUCH_METHOD"
        is IllegalAccessException -> "ILLEGAL_ACCESS"
        is InvocationTargetException -> "INVOCATION_FAILED"
        is ClassNotFoundException -> "NO_SUCH_METHOD"
        else -> "EVENT_UNAVAILABLE"
    }

private fun unwrap(error: Throwable): Throwable =
    when (error) {
        is InvocationTargetException -> error.targetException ?: error
        else -> error.cause?.takeIf { error is java.lang.reflect.UndeclaredThrowableException } ?: error
    }
