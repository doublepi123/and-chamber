package io.andchamber.app.phone.core

/**
 * The first version drives only one selected third-party package. The host app, Android itself and
 * the usual system/Google packages are refused even if the person names them.
 */
class PhoneTargetPolicy(
    private val hostPackage: String = "io.andchamber.app",
) {
    fun refusal(packageName: String): PhoneErrorCode? = when {
        packageName.isBlank() || packageName.length > PhoneLimits.MAX_PACKAGE_CHARS ->
            PhoneErrorCode.INVALID_REQUEST
        packageName == hostPackage -> PhoneErrorCode.TARGET_FORBIDDEN
        packageName in SYSTEM_PACKAGES -> PhoneErrorCode.TARGET_FORBIDDEN
        SYSTEM_PREFIXES.any { packageName.startsWith(it) } -> PhoneErrorCode.TARGET_FORBIDDEN
        !PACKAGE_PATTERN.matches(packageName) -> PhoneErrorCode.INVALID_REQUEST
        else -> null
    }

    fun isAllowed(packageName: String): Boolean = refusal(packageName) == null

    private companion object {
        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        val SYSTEM_PREFIXES = listOf(
            "android.",
            "com.android.",
            "com.google.android.",
        )
    }
}
