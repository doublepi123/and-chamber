package io.andchamber.app.phone.core

/**
 * The first version drives one selected target at a time. The host app, Android itself and the
 * system/authorization surfaces (system UI, settings, permission controller, package installer,
 * keyguard) are refused even if the person names them. Ordinary preinstalled packages, including
 * `com.android.*` and `com.google.android.*`, are not refused by brand prefix alone: the native
 * standing/preauthorization allow-list is the second gate.
 */
class PhoneTargetPolicy(
    private val hostPackage: String = "io.andchamber.app",
) {
    fun refusal(packageName: String): PhoneErrorCode? = when {
        packageName.isBlank() || packageName.length > PhoneLimits.MAX_PACKAGE_CHARS ->
            PhoneErrorCode.INVALID_REQUEST
        packageName == hostPackage -> PhoneErrorCode.TARGET_FORBIDDEN
        packageName in SYSTEM_PACKAGES -> PhoneErrorCode.TARGET_FORBIDDEN
        packageName.startsWith(SYSTEM_PREFIX) -> PhoneErrorCode.TARGET_FORBIDDEN
        !PACKAGE_PATTERN.matches(packageName) -> PhoneErrorCode.INVALID_REQUEST
        else -> null
    }

    fun isAllowed(packageName: String): Boolean = refusal(packageName) == null

    private companion object {
        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        const val SYSTEM_PREFIX = "android."

        /** Authorization/system surfaces only; brand prefixes are deliberately not blacklisted. */
        val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.keyguard",
            "com.google.android.settings",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
        )
    }
}
