package io.andchamber.app.browser

internal object SystemMonotonicClock {
    fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}
