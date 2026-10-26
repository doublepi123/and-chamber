package io.andchamber.app.phone.android.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * What the system allows for an overlay right now.
 *
 * [READY] only means "the app holds the draw-over-other-apps permission". It is **not** evidence that
 * an overlay is visible, that another app did not hide it, or that the system kept it attached.
 */
internal enum class PhoneOverlayGate { UNAVAILABLE, NEEDS_PERMISSION, READY }

internal enum class PhoneOverlayMode {
    OFF,
    NOTIFICATION_ONLY,
    OVERLAY,
}

/**
 * The person's explicit choice, off by default. Overlay is only effective while the permission is
 * granted; otherwise the effective mode falls back to the already verified notification mode.
 * Nothing here starts a service, asks for a permission or changes system settings.
 */
internal class PhoneOverlaySettings(
    private val context: Context,
    private val prefs: android.content.SharedPreferences,
) {
    fun mode(): PhoneOverlayMode =
        runCatching { PhoneOverlayMode.valueOf(prefs.getString(KEY_MODE, PhoneOverlayMode.OFF.name).orEmpty()) }
            .getOrDefault(PhoneOverlayMode.OFF)

    fun setMode(mode: PhoneOverlayMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun gate(): PhoneOverlayGate = overlayGate(canDrawOverlays = canDrawOverlays())

    /** The mode that may actually run now. Overlay never silently degrades to OFF. */
    fun effectiveMode(): PhoneOverlayMode = effectiveOverlayMode(mode(), gate())

    /** Opens the system screen. Android 11+ may show the app list instead of this app's page. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val FILE = "andchamber_phone_overlay"
        private const val KEY_MODE = "mode"
    }
}

/**
 * Overlay requires the platform overlay window type (API 26+ for the whole app, which is the app's
 * minimum) and the person's draw-over-other-apps grant.
 */
internal fun overlayGate(canDrawOverlays: Boolean): PhoneOverlayGate =
    if (canDrawOverlays) PhoneOverlayGate.READY else PhoneOverlayGate.NEEDS_PERMISSION

/**
 * Overlay is only effective when its permission is held. A request for overlay without permission
 * falls back to the verified notification mode, never to "off" and never to an unattached window.
 */
internal fun effectiveOverlayMode(
    requested: PhoneOverlayMode,
    gate: PhoneOverlayGate,
): PhoneOverlayMode =
    when {
        requested != PhoneOverlayMode.OVERLAY -> requested
        gate == PhoneOverlayGate.READY -> PhoneOverlayMode.OVERLAY
        gate == PhoneOverlayGate.NEEDS_PERMISSION -> PhoneOverlayMode.NOTIFICATION_ONLY
        else -> PhoneOverlayMode.NOTIFICATION_ONLY
    }
