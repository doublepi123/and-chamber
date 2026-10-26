package io.andchamber.app.phone.overlay.integration

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.andchamber.app.MainActivity
import io.andchamber.app.phone.android.PhoneControlService
import io.andchamber.app.phone.android.overlay.PhoneOverlayDisplayService
import io.andchamber.app.phone.android.overlay.PhoneOverlayGate
import io.andchamber.app.phone.android.overlay.PhoneOverlayMode
import io.andchamber.app.phone.android.overlay.PhoneOverlaySettings
import io.andchamber.app.phone.android.overlay.effectiveOverlayMode
import io.andchamber.app.phone.android.overlay.overlayGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Manifest and settings wiring for the optional overlay. Metadata only: it never attaches a window,
 * never changes the system permission and never grants a lease.
 */
@RunWith(AndroidJUnit4::class)
class PhoneOverlayWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun overlayDisplayServiceIsDeclaredAsSpecialUseAndNotExported() {
        @Suppress("DEPRECATION")
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, PhoneOverlayDisplayService::class.java),
                0,
            )
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
            )
        }
    }

    @Test
    fun overlayPermissionIsRequestedButNeverAssumedGranted() {
        @Suppress("DEPRECATION")
        val declared =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        assertTrue(declared.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW))
        // The permission is a request; the settings gate must reflect the actual grant at runtime.
        assertEquals(
            if (android.provider.Settings.canDrawOverlays(context)) {
                PhoneOverlayGate.READY
            } else {
                PhoneOverlayGate.NEEDS_PERMISSION
            },
            overlayGate(android.provider.Settings.canDrawOverlays(context)),
        )
    }

    @Test
    fun overlaySettingsDefaultOffAndFallBackToNotificationMode() {
        val prefs = context.getSharedPreferences("phone_overlay_test_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        val settings = PhoneOverlaySettings(context, prefs)
        assertEquals(PhoneOverlayMode.OFF, settings.mode())
        // Asking for overlay without the grant is the verified notification mode, never "off".
        assertEquals(
            PhoneOverlayMode.NOTIFICATION_ONLY,
            effectiveOverlayMode(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.NEEDS_PERMISSION),
        )
        settings.setMode(PhoneOverlayMode.NOTIFICATION_ONLY)
        assertEquals(PhoneOverlayMode.NOTIFICATION_ONLY, settings.mode())
    }

    @Test
    fun openNativeIntentIsAnActivityIntentNotAServiceTrampoline() {
        val intent = PhoneOverlayDisplayService.openNativeIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(PhoneControlService.ACTION_OPEN_PHONE, intent.action)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
