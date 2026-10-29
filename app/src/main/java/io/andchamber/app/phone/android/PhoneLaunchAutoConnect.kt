package io.andchamber.app.phone.android

/**
 * Whether the local chat may reconnect a phone link that the person already set up, in the
 * background, without opening the native phone page.
 *
 * This is a guard, not authority: it neither pairs, grants, changes the standing scope nor starts a
 * task. Every input is a fact the caller already knows:
 * - [enabled]/[available]: the experiment is on and this Android version supports it.
 * - [hasPairingReceipt]: a real pairing receipt is stored for the local host. A background connect
 *   never creates one.
 * - [hasStandingScope]: the person saved a standing configuration. Without it there is nothing to
 *   reconnect for, so the manual page stays the only path.
 * - [localRuntimeReady]: the local runtime is starting or running. Setup, failures and a stopped
 *   server never trigger it, and it never restarts the runtime for the plugin.
 * - [connected]/[connecting]: an existing link or an in-flight search is left alone.
 * - [attemptedThisRun]: one attempt per app run; a timeout or an off wireless-debugging switch is
 *   not retried in a loop, and "Find and connect" stays available.
 */
internal fun shouldAutoConnectOnLaunch(
    enabled: Boolean,
    available: Boolean,
    hasPairingReceipt: Boolean,
    hasStandingScope: Boolean,
    localRuntimeReady: Boolean,
    connected: Boolean,
    connecting: Boolean,
    attemptedThisRun: Boolean,
): Boolean =
    enabled &&
        available &&
        hasPairingReceipt &&
        hasStandingScope &&
        localRuntimeReady &&
        !connected &&
        !connecting &&
        !attemptedThisRun
