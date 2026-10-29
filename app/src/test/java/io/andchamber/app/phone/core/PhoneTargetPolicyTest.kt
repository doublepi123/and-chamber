package io.andchamber.app.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand alone is not a refusal. Ordinary preinstalled apps (including com.android.* and
 * com.google.android.*) pass this policy and are then narrowed by the native standing allow-list.
 * Authorization surfaces stay hard-denied regardless of the allow-list.
 */
class PhoneTargetPolicyTest {
    private val policy = PhoneTargetPolicy(hostPackage = "io.andchamber.app")

    @Test
    fun `ordinary preinstalled and google packages are not refused by brand`() {
        assertNull(policy.refusal("com.google.android.dialer"))
        assertNull(policy.refusal("com.google.android.calendar"))
        assertNull(policy.refusal("com.android.chrome"))
        assertNull(policy.refusal("com.android.documentsui"))
        assertNull(policy.refusal("com.example.notes"))
    }

    @Test
    fun `host and authorization surfaces stay forbidden`() {
        listOf(
            "io.andchamber.app",
            "android",
            "android.permission",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.keyguard",
            "com.google.android.settings",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
        ).forEach { packageName ->
            assertEquals(
                packageName,
                PhoneErrorCode.TARGET_FORBIDDEN,
                policy.refusal(packageName),
            )
        }
    }

    @Test
    fun `blank and malformed packages are invalid`() {
        assertEquals(PhoneErrorCode.INVALID_REQUEST, policy.refusal(""))
        assertEquals(PhoneErrorCode.INVALID_REQUEST, policy.refusal("not-a-package"))
    }

    @Test
    fun `all apps mode allows the host settings installers and android packages`() {
        listOf(
            "io.andchamber.app",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.settings",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "android.permission",
            "com.android.chrome",
            "com.example.bank",
        ).forEach { packageName ->
            assertNull("$packageName must be allowed in all-apps mode", policy.refusal(packageName, allApps = true))
            assertTrue(policy.isAllowed(packageName, allApps = true))
        }
    }

    @Test
    fun `all apps mode still refuses the secure surfaces and invalid names`() {
        listOf(
            "android",
            "com.android.systemui",
            "com.android.keyguard",
            "com.android.keyguard.something",
        ).forEach { packageName ->
            assertEquals(
                packageName,
                PhoneErrorCode.TARGET_FORBIDDEN,
                policy.refusal(packageName, allApps = true),
            )
        }
        assertEquals(PhoneErrorCode.INVALID_REQUEST, policy.refusal("", allApps = true))
        assertEquals(PhoneErrorCode.INVALID_REQUEST, policy.refusal("not-a-package", allApps = true))
        assertFalse(policy.isAllowed("com.android.systemui", allApps = true))
    }

    @Test
    fun `explicit mode is unchanged by the all apps flag`() {
        // The default parameter keeps the historical behavior byte for byte.
        assertNull(policy.refusal("com.example.notes", allApps = false))
        assertEquals(PhoneErrorCode.TARGET_FORBIDDEN, policy.refusal("com.android.settings", allApps = false))
        assertEquals(PhoneErrorCode.TARGET_FORBIDDEN, policy.refusal("android.permission", allApps = false))
        assertEquals(PhoneErrorCode.TARGET_FORBIDDEN, policy.refusal("io.andchamber.app", allApps = false))
    }
}
