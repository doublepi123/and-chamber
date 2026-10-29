package io.andchamber.app.phone.core

/**
 * The first version drives one selected target at a time.
 *
 * Explicit-list mode (the default) refuses the host app, Android itself and the
 * system/authorization surfaces (system UI, settings, permission controller, package installer,
 * keyguard) even if the person names them. Ordinary preinstalled packages, including
 * `com.android.*` and `com.google.android.*`, are not refused by brand prefix alone: the native
 * standing/preauthorization allow-list is the second gate.
 *
 * All-apps mode is the person's explicit "authorize every app" standing choice. It allows the host
 * app, Settings, permission controller, package installer, Google settings variants and `android.*`
 * packages. It still always refuses a blank/invalid package, `android` itself, `com.android.systemui`
 * and `com.android.keyguard`: those are authorization/secure surfaces, never a task target. The
 * native side additionally requires a real launcher activity for the package before it grants.
 */
class PhoneTargetPolicy(
    private val hostPackage: String = "io.andchamber.app",
) {
    fun refusal(
        packageName: String,
        allApps: Boolean = false,
    ): PhoneErrorCode? =
        if (allApps) allAppsRefusal(packageName) else explicitListRefusal(packageName)

    fun isAllowed(
        packageName: String,
        allApps: Boolean = false,
    ): Boolean = refusal(packageName, allApps) == null

    /** Historical explicit-list behavior; unchanged byte for byte. */
    private fun explicitListRefusal(packageName: String): PhoneErrorCode? = when {
        packageName.isBlank() || packageName.length > PhoneLimits.MAX_PACKAGE_CHARS ->
            PhoneErrorCode.INVALID_REQUEST
        packageName == hostPackage -> PhoneErrorCode.TARGET_FORBIDDEN
        packageName in SYSTEM_PACKAGES -> PhoneErrorCode.TARGET_FORBIDDEN
        packageName.startsWith(SYSTEM_PREFIX) -> PhoneErrorCode.TARGET_FORBIDDEN
        !PACKAGE_PATTERN.matches(packageName) -> PhoneErrorCode.INVALID_REQUEST
        else -> null
    }

    /**
     * All-apps mode: a package may be named even if it is Settings, an installer or the host, but a
     * blank/invalid name and the secure/authorization surfaces stay refused. A package that is not
     * launchable is refused by the native launcher check, not by this name policy.
     */
    private fun allAppsRefusal(packageName: String): PhoneErrorCode? = when {
        packageName.isBlank() || packageName.length > PhoneLimits.MAX_PACKAGE_CHARS ->
            PhoneErrorCode.INVALID_REQUEST
        // The exact secure names win over the dotted-shape check: `android` is a refusal, not a typo.
        packageName in ALL_APPS_FORBIDDEN -> PhoneErrorCode.TARGET_FORBIDDEN
        !PACKAGE_PATTERN.matches(packageName) -> PhoneErrorCode.INVALID_REQUEST
        // Keyguard/system-UI variants (for example a keyguard subpackage) are the same secure
        // surface family; they stay refused by prefix instead of name-by-name.
        ALL_APPS_FORBIDDEN_PREFIXES.any { packageName.startsWith(it) } -> PhoneErrorCode.TARGET_FORBIDDEN
        else -> null
    }

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

        /**
         * Never a target, even in all-apps mode: `android` is not an app, system UI owns the
         * notification/stop surface, and keyguard guards the lock screen.
         */
        val ALL_APPS_FORBIDDEN = setOf(
            "android",
            "com.android.systemui",
            "com.android.keyguard",
        )

        val ALL_APPS_FORBIDDEN_PREFIXES = listOf(
            "com.android.systemui.",
            "com.android.keyguard.",
        )
    }
}
