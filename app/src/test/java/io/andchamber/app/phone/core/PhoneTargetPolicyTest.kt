package io.andchamber.app.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
