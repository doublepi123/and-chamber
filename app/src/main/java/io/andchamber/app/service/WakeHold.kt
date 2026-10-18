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

    /** Safety valve so a lost release cannot keep the device awake indefinitely. */
    const val MAX_HOLD_MILLIS = 60 * 60 * 1000L

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(context: Context) {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) return
            val power = context.applicationContext.getSystemService(PowerManager::class.java)
            wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                    setReferenceCounted(false)
                    acquire(MAX_HOLD_MILLIS)
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
