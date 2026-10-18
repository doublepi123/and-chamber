package io.andchamber.app.service

import android.content.Context
import android.os.PowerManager

/**
 * One process-wide partial wake lock for runtime work. Acquired synchronously by the foreground
 * starter (before any install/start command runs) and released when the runtime stops or fails.
 * Idempotent: repeated acquire calls keep the single lock held.
 */
object WakeHold {
    private const val TAG = "AndChamber:runtime"

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * No timeout: while Running the lock is held for the server's lifetime, and every end of work
     * (terminal state, exception, service destroy) calls [release]. A timed lock would silently lose
     * the CPU guarantee in the middle of a long-running server.
     */
    fun acquire(context: Context) {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) return
            val power = context.applicationContext.getSystemService(PowerManager::class.java)
            wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    fun release() {
        synchronized(lock) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    /** True while the lock is held; for diagnostics and tests. */
    fun isHeld(): Boolean = synchronized(lock) { wakeLock?.isHeld == true }
}
