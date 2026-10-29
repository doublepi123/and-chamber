// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import android.os.Build
import android.os.SystemClock
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneNode
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhonePoint
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.overlay.safety.OverlayExpectation
import io.andchamber.app.phone.overlay.safety.OverlayObservation
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.OverlaySafetyPolicy
import io.andchamber.app.phone.overlay.safety.OverlayWindowStateParser
import io.andchamber.app.phone.overlay.safety.OverlayWitness
import io.andchamber.app.phone.overlay.safety.OverlayWitnessReader
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native ADB driver for one already-paired local device.
 *
 * Observation uses `uiautomator dump /dev/tty`, a fixed `dumpsys window` read for the real foreground
 * window identity, and `wm size` for the display. Capture uses raw `exec:screencap -p`. Mutations are
 * generated from the fixed schema in [AdbCommandSchema].
 *
 * The value in [PhoneSnapshot.windowId] is a digest of both the real window-manager identity
 * (mCurrentFocus and mFocusedApp tokens) and the observed node content. Actions compare only the
 * window part: a same-package new activity, dialog, rotation or display change invalidates the
 * binding, while ordinary live content changes (timers, banners, labels) do not. A tap by ref still
 * requires a live element at exactly the observed bounds. The window identity
 * is read before and after observation/capture; an unreliable `dumpsys window` result fails closed.
 * Check and injection are still separate steps, not a cross-process atomic transaction.
 *
 * Overlay mode: observation and actions keep the capsule visible. The capsule is never the focused
 * window, so it cannot appear in the focused app's hierarchy dump. A fresh real-window witness is
 * required before and after the read, each injection and the capture hide. When a tap/focus point or
 * swipe segment would land on the capsule, one bounded make-way request asks the manager to move it
 * first; the action is only refused as `OVERLAY_OBSTRUCTED` if it is still in the way afterwards.
 * Capture still hides the window for the pixels, because the screenshot must not contain the
 * capsule's summary or notices.
 */
class AdbPhoneDriver(
    private val device: AdbDevice,
    private val runtimeId: String,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val connectionId: () -> String = { UUID.randomUUID().toString() },
    private val maxNodes: Int = PhoneLimits.MAX_NODES,
    private val captureMaxBytes: Int = PhoneLimits.MAX_CAPTURE_BYTES,
    private val platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    private val overlayBarrier: PhoneOverlayBarrier? = null,
) : PhoneDriver {
    init {
        require(runtimeId.isNotBlank() && runtimeId.length <= PhoneLimits.MAX_WINDOW_ID_CHARS)
        require(runtimeId.none { it.isISOControl() })
        require(maxNodes in 1..PhoneLimits.MAX_NODES)
        require(captureMaxBytes in 1..PhoneLimits.MAX_CAPTURE_BYTES)
    }

    private val overlayObservations = LinkedHashMap<String, OverlayObservation>()

    val transportSecurity: AdbTransportSecurity
        get() = device.transportSecurity

    override fun capabilities(): PhoneCapabilities = capabilitiesFor(platformSupported)

    /** Static report: it must be usable while disconnected. Unicode input stays unavailable. */
    fun capabilityReport(): AdbCapabilityReport = AdbCapabilityReport(
        phone = capabilities(),
        inputAsciiOnly = true,
        unicodeInputSupported = false,
        submitSupported = false,
        inputMaxChars = AdbCommandSchema.MAX_INPUT_CHARS,
        observeMaxNodes = maxNodes,
        captureMaxBytes = captureMaxBytes,
    )

    override suspend fun connect(deadlineMs: Long): PhoneConnection = withContext(Dispatchers.IO) {
        if (!platformSupported) throw PhoneDriverException(PhoneDriverFailure.UNSUPPORTED)
        val bounded = deadlineMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS)
        try {
            device.connect(bounded)
        } catch (failure: AdbDeviceException) {
            throw PhoneDriverException(mapFailure(failure.failure))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        }
        if (device.transportSecurity != AdbTransportSecurity.TLS) {
            // The connection is not allowed to count as usable without a verified TLS upgrade.
            device.disconnect()
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        PhoneConnection(connectionId = connectionId(), runtimeId = runtimeId)
    }

    override suspend fun disconnect(deadlineMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                device.disconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort; the controller has already revoked the lease.
            }
        }
    }

    override suspend fun observe(deadlineMs: Long): PhoneSnapshot = withContext(Dispatchers.IO) {
        val started = clock()
        try {
            if (!overlayRequired(started + deadlineMs)) {
                observeWithoutOverlay(started, deadlineMs)
            } else {
                observeWithOverlay(started, deadlineMs)
            }
        } catch (failure: AdbDeviceException) {
            throw PhoneDriverException(mapFailure(failure.failure))
        } catch (failure: PhoneDriverException) {
            throw failure
        } catch (failure: AdbHierarchyException) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        } catch (failure: AdbWindowStateException) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        }
    }

    override suspend fun capture(
        binding: PhoneObservationBinding,
        deadlineMs: Long,
    ): PhoneImage = withContext(Dispatchers.IO) {
        val started = clock()
        try {
            if (!overlayRequired(started + deadlineMs)) {
                val image = captureWithoutOverlay(binding, started, deadlineMs)
                if (overlayRequired(started + deadlineMs)) {
                    // The mode became overlay while pixels were copied without hiding it: discard.
                    throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
                }
                clearOverlayObservation(binding.windowId)
                image
            } else {
                captureWithOverlay(binding, started, deadlineMs)
            }
        } catch (failure: AdbDeviceException) {
            throw PhoneDriverException(mapFailure(failure.failure))
        } catch (failure: PhoneDriverException) {
            throw failure
        } catch (failure: AdbHierarchyException) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        } catch (failure: AdbWindowStateException) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        }
    }

    private fun captureWithoutOverlay(
        binding: PhoneObservationBinding,
        startedAt: Long,
        deadlineMs: Long,
        notificationFallback: Boolean = false,
    ): PhoneImage {
        val before = verifyLiveWindow(binding, startedAt, deadlineMs)
        if (notificationFallback &&
            !notificationFallbackProof(
                focusedPackage = before.focus.focusedAppPackage,
                focusMatchesHierarchy = before.focus.focusedAppPackage == before.hierarchy.packageName,
                display = binding.geometry,
                expectation = overlayExpectation(startedAt + deadlineMs),
                startedAt = startedAt,
                deadlineMs = deadlineMs,
            )
        ) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        val bytes = device.capturePng(left(startedAt, deadlineMs), captureMaxBytes)
        // Re-read the real window after the screenshot. If it changed while pixels were being
        // copied, discard the image instead of returning another window's content.
        val after = verifyLiveWindow(binding, startedAt, deadlineMs)
        if (windowDigest(before.snapshot.windowId) != windowDigest(after.snapshot.windowId)) {
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        val size = AdbPng.readSize(bytes)
            ?: throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        if (size.first != binding.geometry.displayWidth ||
            size.second != binding.geometry.displayHeight
        ) {
            throw PhoneDriverException(PhoneDriverFailure.GEOMETRY_CHANGED)
        }
        if (!notificationFallback && overlayRequired(startedAt + deadlineMs)) {
            // The mode became overlay while pixels were copied without hiding it: discard.
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        clearOverlayObservation(binding.windowId)
        return PhoneImage(
            bytes = bytes,
            mimeType = "image/png",
            width = size.first,
            height = size.second,
        )
    }

    private suspend fun captureWithOverlay(
        binding: PhoneObservationBinding,
        startedAt: Long,
        deadlineMs: Long,
    ): PhoneImage {
        val observation = overlayObservation(binding.windowId)
        if (observation == null) {
            // No stored overlay observation means the preceding observe used the notification
            // fallback (the target force-hides non-system overlays). Capture without hide/restore
            // only when the same fresh proof still holds; the helper refuses otherwise.
            return captureWithoutOverlay(binding, startedAt, deadlineMs, notificationFallback = true)
        }
        val preExpectation = overlayExpectation(startedAt + deadlineMs)
        val pre = readOverlayWitness(preExpectation, binding.geometry, startedAt, deadlineMs)
        if (OverlaySafetyPolicy.identityRefusal(pre, observation, clock()) != null) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        // The slow page reads (a full hierarchy dump each) bracket the hidden period instead of
        // running inside it, so the capsule disappears only for the screenshot itself. The window
        // identity must be the same before the hide and after the restore, which covers the capture.
        val before = verifyLiveWindow(binding, startedAt, deadlineMs)
        val image = withOverlayHidden(
            expectation = preExpectation,
            phase = OverlayPhase.CAPTURE_HIDDEN,
            deadlineAtMs = startedAt + deadlineMs,
        ) {
            val hidden = readOverlayWitness(
                expectation = preExpectation,
                display = binding.geometry,
                startedAt = startedAt,
                deadlineMs = deadlineMs,
                forcedHiddenPhase = OverlayPhase.CAPTURE_HIDDEN,
            )
            if (hidden.phase != OverlayPhase.CAPTURE_HIDDEN) {
                throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
            }
            val bytes = device.capturePng(left(startedAt, deadlineMs), captureMaxBytes)
            val size = AdbPng.readSize(bytes)
                ?: throw PhoneDriverException(PhoneDriverFailure.DRIVER)
            if (size.first != binding.geometry.displayWidth ||
                size.second != binding.geometry.displayHeight
            ) {
                throw PhoneDriverException(PhoneDriverFailure.GEOMETRY_CHANGED)
            }
            PhoneImage(
                bytes = bytes,
                mimeType = "image/png",
                width = size.first,
                height = size.second,
            )
        }
        val after = verifyLiveWindow(binding, startedAt, deadlineMs)
        if (windowDigest(before.snapshot.windowId) != windowDigest(after.snapshot.windowId)) {
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        val postExpectation = overlayExpectation(startedAt + deadlineMs)
        val post = readOverlayWitness(postExpectation, binding.geometry, startedAt, deadlineMs)
        if (OverlaySafetyPolicy.identityRefusal(post, observation, clock()) != null) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        if (!overlayRequired(startedAt + deadlineMs)) {
            // A mode change after the capture discards the pixels.
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        return image
    }

    override suspend fun dispatch(
        request: PhoneDispatchRequest,
        deadlineMs: Long,
    ): PhoneDispatchReceipt = withContext(Dispatchers.IO) {
        val started = clock()
        val binding = request.binding
        if (!platformSupported) return@withContext PhoneDispatchReceipt.NotSent(
            PhoneDriverFailure.UNSUPPORTED,
        )

        val live = try {
            verifyLiveWindow(binding, started, deadlineMs)
        } catch (failure: AdbDeviceException) {
            return@withContext PhoneDispatchReceipt.NotSent(mapFailure(failure.failure))
        } catch (failure: PhoneDriverException) {
            return@withContext PhoneDispatchReceipt.NotSent(failure.failure)
        } catch (failure: AdbHierarchyException) {
            return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
        } catch (failure: AdbWindowStateException) {
            return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
        }

        // Freeze the dynamic mode for this mutation. A fresh observation is required when the
        // overlay mode is on; an observation captured under overlay mode taints a notification-mode
        // dispatch until the caller observes again (fail closed on a mode change).
        //
        // The task's own target may force-hide non-system overlays (Settings does). That task then
        // uses the notification stop entry: the overlay cannot be drawn for it, so the original
        // notification path applies (no capsule to avoid, no overlay observation). The proof is
        // frozen once here, exactly like the mode, and re-proved before every injection.
        val overlayRequiredNow = overlayRequired(started + deadlineMs)
        val overlayFallback =
            overlayRequiredNow &&
                notificationFallbackStillHolds(binding.packageName, binding.geometry, started, deadlineMs)
        val overlayMode = overlayRequiredNow && !overlayFallback
        val storedOverlay = if (overlayMode) {
            overlayObservation(binding.windowId)
                ?: return@withContext PhoneDispatchReceipt.NotSent(
                    PhoneDriverFailure.POLICY_DENIED,
                )
        } else {
            if (overlayObservation(binding.windowId) != null) {
                return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED)
            }
            null
        }

        fun guards(validate: () -> OverlayExpectation?): Pair<() -> Unit, () -> Unit> {
            // The expectation that produced the validated WM witness, per injection step.
            val validated = arrayOfNulls<OverlayExpectation>(1)
            val before = {
                if (overlayFallback) {
                    // Fresh re-proof: the target must still be the focused app that force-hides
                    // non-system overlays, and the verified stop entry must still be available.
                    if (!notificationFallbackStillHolds(
                            binding.packageName,
                            binding.geometry,
                            started,
                            deadlineMs,
                        )
                    ) {
                        throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
                    }
                } else {
                    if (overlayRequired(started + deadlineMs) != overlayMode) {
                        // Mode changed between the operation start and this injection.
                        throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
                    }
                    validated[0] = validate()
                }
            }
            val finalGuard = {
                if (!overlayFallback) {
                    finalOverlayGuard(
                        overlayMode = overlayMode,
                        validated = validated[0],
                        startedAt = started,
                        deadlineMs = deadlineMs,
                    )
                }
                // Fallback: the target force-hides non-system overlays, so there is no expectation
                // for a drawn capsule to validate. The per-step before-guard already re-proved the
                // real force-hide state immediately before this injection.
            }
            return before to finalGuard
        }

        fun overlayStep(
            command: AdbInputCommand,
            action: PhoneDriverAction,
        ): InjectionStep {
            val (before, finalGuard) = guards {
                if (overlayMode) {
                    verifyOverlayAction(
                        binding = binding,
                        observation = storedOverlay!!,
                        action = action,
                        startedAt = started,
                        deadlineMs = deadlineMs,
                    )
                } else {
                    null
                }
            }
            return InjectionStep(command, before, finalGuard)
        }

        val steps = when (val action = request.action) {
            is PhoneDriverAction.Tap -> {
                if (!action.at.isWithin(binding.geometry)) {
                    return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.INVALID_REQUEST)
                }
                try {
                    requireLiveTarget(live, action.target)
                } catch (failure: PhoneDriverException) {
                    return@withContext PhoneDispatchReceipt.NotSent(failure.failure)
                }
                listOf(overlayStep(AdbInputCommand.Tap(action.at.x, action.at.y), action))
            }
            is PhoneDriverAction.Swipe -> {
                if (!action.from.isWithin(binding.geometry) ||
                    !action.to.isWithin(binding.geometry) ||
                    action.from == action.to ||
                    action.durationMs !in PhoneLimits.MIN_SWIPE_MS..PhoneLimits.MAX_SWIPE_MS
                ) {
                    return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.INVALID_REQUEST)
                }
                listOf(
                    overlayStep(
                        AdbInputCommand.Swipe(
                            fromX = action.from.x,
                            fromY = action.from.y,
                            toX = action.to.x,
                            toY = action.to.y,
                            durationMs = action.durationMs,
                        ),
                        action,
                    ),
                )
            }
            is PhoneDriverAction.Input -> {
                when (AdbCommandSchema.textRefusal(action.text)) {
                    null -> Unit
                    AdbTextRefusal.TOO_LONG,
                    AdbTextRefusal.EMPTY,
                    AdbTextRefusal.CONTROL -> return@withContext PhoneDispatchReceipt.NotSent(
                        PhoneDriverFailure.INVALID_REQUEST,
                    )
                    AdbTextRefusal.NON_ASCII,
                    AdbTextRefusal.PERCENT -> return@withContext PhoneDispatchReceipt.NotSent(
                        PhoneDriverFailure.UNSUPPORTED,
                    )
                }
                val focus = action.focus
                if (focus != null) {
                    if (!focus.isWithin(binding.geometry)) {
                        return@withContext PhoneDispatchReceipt.NotSent(
                            PhoneDriverFailure.INVALID_REQUEST,
                        )
                    }
                    val target = inputTarget(live.snapshot.nodes, focus)
                    if (target == null || !target.editable) {
                        return@withContext PhoneDispatchReceipt.NotSent(
                            PhoneDriverFailure.UNSUPPORTED,
                        )
                    }
                    val (textBefore, textFinalGuard) = guards {
                        // After the focus tap the hierarchy/content has legitimately changed.
                        // Re-check the real window identity, the actual focus field and the
                        // current overlay witness before the text injection.
                        verifyInputFollowup(
                            binding = binding,
                            focus = focus,
                            overlayObservation = storedOverlay,
                            overlayMode = overlayMode,
                            startedAt = started,
                            deadlineMs = deadlineMs,
                        )
                    }
                    listOf(
                        overlayStep(AdbInputCommand.Tap(focus.x, focus.y), action),
                        InjectionStep(
                            command = AdbInputCommand.Text(action.text),
                            before = textBefore,
                            finalGuard = textFinalGuard,
                        ),
                    )
                } else {
                    val focused = live.snapshot.nodes.firstOrNull { node ->
                        node.focused && node.editable
                    }
                    if (focused == null) {
                        return@withContext PhoneDispatchReceipt.NotSent(
                            PhoneDriverFailure.UNSUPPORTED,
                        )
                    }
                    listOf(overlayStep(AdbInputCommand.Text(action.text), action))
                }
            }
            PhoneDriverAction.Back ->
                listOf(overlayStep(AdbInputCommand.Back, request.action))
        }

        // Occlusion auto make-way, before anything is injected. One bounded attempt per dispatch:
        // the step validations below still run (and re-read a fresh witness), so a capsule that did
        // not move, or moved back, is refused as OVERLAY_OBSTRUCTED with nothing sent.
        if (overlayMode) {
            try {
                makeWayIfObstructed(
                    action = request.action,
                    binding = binding,
                    observation = storedOverlay!!,
                    startedAt = started,
                    deadlineMs = deadlineMs,
                )
            } catch (failure: AdbDeviceException) {
                return@withContext PhoneDispatchReceipt.NotSent(mapFailure(failure.failure))
            } catch (failure: PhoneDriverException) {
                return@withContext PhoneDispatchReceipt.NotSent(failure.failure)
            } catch (failure: AdbHierarchyException) {
                return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
            } catch (failure: AdbWindowStateException) {
                return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
            }
        }

        var touched = false
        for (step in steps) {
            step.before?.let { before ->
                try {
                    before()
                } catch (failure: AdbDeviceException) {
                    return@withContext if (touched) {
                        PhoneDispatchReceipt.Unknown(mapFailure(failure.failure))
                    } else {
                        PhoneDispatchReceipt.NotSent(mapFailure(failure.failure))
                    }
                } catch (failure: PhoneDriverException) {
                    return@withContext if (touched) {
                        PhoneDispatchReceipt.Unknown(failure.failure)
                    } else {
                        PhoneDispatchReceipt.NotSent(failure.failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withContext if (touched) {
                        PhoneDispatchReceipt.Unknown(PhoneDriverFailure.DRIVER)
                    } else {
                        PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
                    }
                }
            }
            // Controller-issued native admission guard, immediately before this real injection.
            val admitted = try {
                request.guard.check() == null
            } catch (_: Exception) {
                false
            }
            if (!admitted) {
                return@withContext if (touched) {
                    PhoneDispatchReceipt.Unknown(PhoneDriverFailure.ADMISSION_REFUSED)
                } else {
                    PhoneDispatchReceipt.NotSent(PhoneDriverFailure.ADMISSION_REFUSED)
                }
            }
            // Final TOCTOU guard: re-read the non-blocking manager claim right before the real
            // injection. The WM witness/frame is NOT re-read or retried; a changed mode, owner,
            // generation, revision, phase, key or a stale deadline refuses this step.
            try {
                step.finalGuard?.invoke()
            } catch (failure: AdbDeviceException) {
                val mapped = mapFailure(failure.failure)
                return@withContext if (touched) {
                    PhoneDispatchReceipt.Unknown(mapped)
                } else {
                    PhoneDispatchReceipt.NotSent(mapped)
                }
            } catch (failure: PhoneDriverException) {
                return@withContext if (touched) {
                    PhoneDispatchReceipt.Unknown(failure.failure)
                } else {
                    PhoneDispatchReceipt.NotSent(failure.failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext if (touched) {
                    PhoneDispatchReceipt.Unknown(PhoneDriverFailure.DRIVER)
                } else {
                    PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
                }
            }
            try {
                device.inject(step.command, left(started, deadlineMs))
                touched = true
            } catch (failure: AdbDeviceException) {
                val mapped = mapFailure(failure.failure)
                return@withContext if (failure.afterWrite || touched) {
                    PhoneDispatchReceipt.Unknown(mapped)
                } else {
                    PhoneDispatchReceipt.NotSent(mapped)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext if (touched) {
                    PhoneDispatchReceipt.Unknown(PhoneDriverFailure.DRIVER)
                } else {
                    PhoneDispatchReceipt.NotSent(PhoneDriverFailure.DRIVER)
                }
            }
        }
        PhoneDispatchReceipt.Sent
    }

    private fun observeWithoutOverlay(startedAt: Long, deadlineMs: Long): PhoneSnapshot {
        val snapshot = readLiveWindow(startedAt, deadlineMs).snapshot
        if (overlayRequired(startedAt + deadlineMs)) {
            // The mode became overlay while content was read without hiding it: discard.
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        clearOverlayObservation(snapshot.windowId)
        return snapshot
    }

    /**
     * The only dynamic mode gate. A missing barrier means no overlay path at all; a throw from the
     * manager gate is treated as overlay-required (fail closed).
     */
    private fun overlayRequired(deadlineAtMs: Long): Boolean {
        val barrier = overlayBarrier ?: return false
        return try {
            barrier.required(deadlineAtMs)
        } catch (_: Exception) {
            true
        }
    }

    private fun clearOverlayObservation(windowId: String) {
        synchronized(overlayObservations) { overlayObservations.remove(windowId) }
    }

    /**
     * Overlay-mode observation keeps the overlay visible.
     *
     * The capsule is `FLAG_NOT_FOCUSABLE`, so it is never the focused window: `uiautomator dump` only
     * returns the focused app's nodes and [readLiveWindow] additionally requires the focus package to
     * be the hierarchy package. Removing and re-adding the window on every snapshot therefore only
     * made the capsule flicker; it never kept its own nodes out of the read.
     *
     * The protection is the real window witness instead: the manager must intend a VISIBLE window
     * with a discoverable token, a fresh visible witness is required before and after the read, and a
     * mode change while the read is in flight discards the snapshot. The witness is remembered for
     * the following action/capture checks exactly as before. Capture still hides (see
     * [captureWithOverlay]) because pixels would otherwise contain the capsule.
     *
     * One honest exception: the task's own target may set `mForceHideNonSystemOverlayWindow=true`
     * (Settings does this). The window still exists with a surface, but WindowManager refuses to show
     * it and no overlay can be drawn for that target. With the fresh witness proving exactly that,
     * the focused app being this task's target, and a verified notification stop entry from the
     * barrier, this operation continues on the original notification path instead of claiming an
     * overlay visibility loss.
     */
    private suspend fun observeWithOverlay(
        startedAt: Long,
        deadlineMs: Long,
    ): PhoneSnapshot {
        val expectation = overlayExpectation(startedAt + deadlineMs)
        if (expectation.phase != OverlayPhase.VISIBLE || expectation.windowToken == null) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        val live = readLiveWindow(startedAt, deadlineMs)
        val pre = readOverlayWitness(
            expectation = expectation,
            display = live.geometry,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
        if (OverlaySafetyPolicy.visibleRefusal(pre, clock()) != null) {
            if (!notificationFallbackProof(
                    focusedPackage = live.focus.focusedAppPackage,
                    focusMatchesHierarchy = live.focus.focusedAppPackage == live.hierarchy.packageName,
                    display = live.geometry,
                    expectation = expectation,
                    startedAt = startedAt,
                    deadlineMs = deadlineMs,
                )
            ) {
                throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
            }
            // Notification-only path for this operation: no expectation token, no hide/restore and
            // no stored overlay observation for the actions that follow. The mode must still be
            // overlay-required; a switch to notification-only mid-read is not a downgrade source.
            if (!overlayRequired(startedAt + deadlineMs)) {
                throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
            }
            clearOverlayObservation(live.snapshot.windowId)
            return live.snapshot
        }
        val postExpectation = overlayExpectation(startedAt + deadlineMs)
        val post = readOverlayWitness(
            expectation = postExpectation,
            display = live.geometry,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
        if (OverlaySafetyPolicy.visibleRefusal(post, clock()) != null) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        if (!overlayRequired(startedAt + deadlineMs)) {
            // The mode changed while the overlay read was in flight: discard, never return a
            // snapshot whose final mode is no longer the frozen one.
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        rememberOverlay(live.snapshot.windowId, post)
        return live.snapshot
    }

    /**
     * The one accepted reason to use the notification path while Overlay is selected: the real
     * window scan proves our own window still exists (surface attached) while
     * `mForceHideNonSystemOverlayWindow=true` is set - the focused app itself asked the system to
     * hide non-system overlays. It additionally requires the focused app to be the live task's own
     * target and the person's notification stop entry to be verified. Any other reason our window is
     * not visible (our own detach, permission loss, unknown state) stays fail-closed.
     */
    private fun notificationFallbackProof(
        focusedPackage: String,
        focusMatchesHierarchy: Boolean,
        display: PhoneGeometry,
        expectation: OverlayExpectation,
        startedAt: Long,
        deadlineMs: Long,
    ): Boolean {
        if (!focusMatchesHierarchy || focusedPackage.isBlank()) return false
        val barrier = overlayBarrier ?: return false
        val target =
            try {
                barrier.liveTargetPackage()
            } catch (_: Exception) {
                return false
            }
        if (target == null || target != focusedPackage) return false
        if (expectation.phase != OverlayPhase.VISIBLE || expectation.windowToken == null) return false
        val witness =
            try {
                readOverlayWitness(expectation, display, startedAt, deadlineMs)
            } catch (_: Exception) {
                return false
            }
        if (witness.phase != OverlayPhase.UNAVAILABLE || witness.visible) return false
        if (!witness.hasSurface || !witness.forceHiddenBySystem) return false
        return try {
            barrier.notificationFallbackAllowed()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fresh bounded re-proof before each injection of a frozen notification-fallback dispatch.
     * Reads the real focus and overlay window state again, so a target that stops force-hiding (or a
     * lost stop entry / changed task target) refuses the step without injecting anything.
     */
    private fun notificationFallbackStillHolds(
        targetPackage: String,
        display: PhoneGeometry,
        startedAt: Long,
        deadlineMs: Long,
    ): Boolean {
        val expectation =
            try {
                overlayExpectation(startedAt + deadlineMs)
            } catch (_: Exception) {
                return false
            }
        val focus =
            try {
                readWindowFocus(startedAt, deadlineMs)
            } catch (_: Exception) {
                return false
            }
        if (focus.currentFocusPackage != targetPackage || focus.focusedAppPackage != targetPackage) {
            return false
        }
        return notificationFallbackProof(
            focusedPackage = focus.focusedAppPackage,
            focusMatchesHierarchy = true,
            display = display,
            expectation = expectation,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
    }

    private suspend fun <T> withOverlayHidden(
        expectation: io.andchamber.app.phone.overlay.safety.OverlayExpectation,
        phase: OverlayPhase,
        deadlineAtMs: Long,
        block: suspend () -> T,
    ): T {
        val barrier = overlayBarrier ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        try {
            return barrier.withContentHidden(
                owner = expectation.owner,
                generation = expectation.generation,
                revision = expectation.revision,
                phase = phase,
                deadlineMs = deadlineAtMs,
                block = block,
            )
        } catch (failure: PhoneDriverException) {
            throw failure
        } catch (failure: AdbDeviceException) {
            throw failure
        } catch (failure: AdbHierarchyException) {
            throw failure
        } catch (failure: AdbWindowStateException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
    }

    private fun overlayExpectation(deadlineAtMs: Long): io.andchamber.app.phone.overlay.safety.OverlayExpectation {
        val barrier = overlayBarrier ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        return try {
            barrier.expectation(deadlineAtMs)
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
    }

    private fun readOverlayWitness(
        expectation: io.andchamber.app.phone.overlay.safety.OverlayExpectation,
        display: PhoneGeometry,
        startedAt: Long,
        deadlineMs: Long,
        forcedHiddenPhase: OverlayPhase? = null,
    ): OverlayWitness {
        val fullScan = try {
            OverlayWindowStateParser.parse(
                device.windowStateText(left(startedAt, deadlineMs), AdbWindowStateParser.MAX_BYTES),
            )
        } catch (_: Exception) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        // The full API 35 window list has no frame. For a visible overlay, read that one window's
        // full state so the witness carries the real `Frames:` rectangle instead of the requested
        // layout params (the two differ by the status-bar inset).
        val key = expectation.windowToken
        val scan = if (forcedHiddenPhase == null && key != null) {
            // The manager supplies a stable `LayoutParams.title`; tests/legacy may supply a binder
            // token. The binder token needed for the targeted dump is discovered here and never
            // travels back to the manager.
            val candidate = OverlayWindowStateParser.findWindow(fullScan, key)
            if (candidate != null &&
                candidate.hasSurface &&
                candidate.visible &&
                AdbCommandSchema.isWindowToken(candidate.windowHash)
            ) {
                val detail = try {
                    OverlayWindowStateParser.parse(
                        device.windowDetailText(
                            windowToken = candidate.windowHash,
                            deadlineMs = left(startedAt, deadlineMs),
                            maxBytes = AdbWindowStateParser.MAX_BYTES,
                        ),
                    )
                } catch (_: Exception) {
                    throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
                }
                if (detail.windows.any { it.windowHash == candidate.windowHash }) {
                    detail.copy(currentFocusToken = fullScan.currentFocusToken)
                } else {
                    fullScan
                }
            } else {
                fullScan
            }
        } else {
            fullScan
        }
        return OverlayWitnessReader.read(
            expectation = expectation,
            scan = scan,
            display = display,
            nowMs = clock(),
            forcedHiddenPhase = forcedHiddenPhase,
        )
    }

    private fun verifyOverlayAction(
        binding: PhoneObservationBinding,
        observation: OverlayObservation,
        action: PhoneDriverAction,
        startedAt: Long,
        deadlineMs: Long,
    ): OverlayExpectation {
        val expectation = overlayExpectation(startedAt + deadlineMs)
        val witness = readOverlayWitness(
            expectation = expectation,
            display = binding.geometry,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
        val now = clock()
        val identity = OverlaySafetyPolicy.identityRefusal(witness, observation, now)
        val refusal = identity ?: when (action) {
            is PhoneDriverAction.Tap ->
                OverlaySafetyPolicy.tapRefusal(witness, action.at, now)
            is PhoneDriverAction.Swipe ->
                OverlaySafetyPolicy.swipeRefusal(witness, action.from, action.to, now)
            is PhoneDriverAction.Input ->
                OverlaySafetyPolicy.inputFocusRefusal(witness, action.focus, now)
            PhoneDriverAction.Back ->
                OverlaySafetyPolicy.backRefusal(witness, now)
        }
        if (refusal != null) throw PhoneDriverException(overlayRefusalFailure(refusal))
        return expectation
    }

    /**
     * Asks the manager to move its own capsule clear of this action before anything is injected.
     *
     * Overlay mode only, and only when a fresh witness says the action would hit the capsule: the tap
     * point, the input focus point, or the whole swipe segment. [overlayAvoidRects] supplies a small
     * square around each point and the expanded bounding box of a swipe segment. The current real
     * frame and the absolute call deadline go to [PhoneOverlayBarrier.makeWay]; that call is bounded
     * by the same deadline and may only re-layout this manager's own window.
     *
     * Nothing is injected here. A non-obstruction refusal still fails closed as before. After a
     * make-way attempt (or a refused one) the ordinary per-step `before` validation re-reads a fresh
     * witness; if the capsule is still in the way the action is refused as OVERLAY_OBSTRUCTED.
     */
    private suspend fun makeWayIfObstructed(
        action: PhoneDriverAction,
        binding: PhoneObservationBinding,
        observation: OverlayObservation,
        startedAt: Long,
        deadlineMs: Long,
    ) {
        val avoid = overlayAvoidRects(action) ?: return
        val expectation = overlayExpectation(startedAt + deadlineMs)
        val witness = readOverlayWitness(
            expectation = expectation,
            display = binding.geometry,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
        val now = clock()
        val refusal = OverlaySafetyPolicy.identityRefusal(witness, observation, now)
            ?: when (action) {
                is PhoneDriverAction.Tap -> OverlaySafetyPolicy.tapRefusal(witness, action.at, now)
                is PhoneDriverAction.Swipe ->
                    OverlaySafetyPolicy.swipeRefusal(witness, action.from, action.to, now)
                is PhoneDriverAction.Input ->
                    OverlaySafetyPolicy.inputFocusRefusal(witness, action.focus, now)
                PhoneDriverAction.Back -> null
            }
        if (refusal == null) return
        if (!OverlaySafetyPolicy.isObstruction(refusal)) {
            throw PhoneDriverException(overlayRefusalFailure(refusal))
        }
        val currentFrame = witness.actualBounds.firstOrNull()
            ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        val barrier = overlayBarrier ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        try {
            barrier.makeWay(
                avoid = avoid,
                currentFrame = currentFrame,
                deadlineMs = startedAt + deadlineMs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A manager that cannot move is not a stop and not a loss: the per-step validation below
            // still refuses the obstruction without injecting anything.
        }
    }

    /**
     * The rectangles the capsule must leave clear for this action: a small square around a tap or
     * input-focus point, or the whole expanded segment box of a swipe (so a capsule outside the box
     * cannot cross the segment). Back and focus-less text input have no coordinate and need none.
     */
    private fun overlayAvoidRects(action: PhoneDriverAction): List<PhoneRect>? =
        when (action) {
            is PhoneDriverAction.Tap -> listOf(pointClearance(action.at))
            is PhoneDriverAction.Swipe -> listOf(segmentClearance(action.from, action.to))
            is PhoneDriverAction.Input -> action.focus?.let { listOf(pointClearance(it)) }
            PhoneDriverAction.Back -> null
        }

    private fun pointClearance(point: PhonePoint): PhoneRect = PhoneRect(
        left = point.x - MAKE_WAY_POINT_MARGIN_PX,
        top = point.y - MAKE_WAY_POINT_MARGIN_PX,
        right = point.x + MAKE_WAY_POINT_MARGIN_PX,
        bottom = point.y + MAKE_WAY_POINT_MARGIN_PX,
    )

    private fun segmentClearance(from: PhonePoint, to: PhonePoint): PhoneRect = PhoneRect(
        left = minOf(from.x, to.x) - MAKE_WAY_POINT_MARGIN_PX,
        top = minOf(from.y, to.y) - MAKE_WAY_POINT_MARGIN_PX,
        right = maxOf(from.x, to.x) + MAKE_WAY_POINT_MARGIN_PX,
        bottom = maxOf(from.y, to.y) + MAKE_WAY_POINT_MARGIN_PX,
    )

    /**
     * The last check before a real injection. It never re-reads the WM frame and never retries a
     * coordinate. It only re-reads the manager's non-blocking claim and requires the same mode and
     * the same owner/generation/revision/key/phase that produced the validated witness, still fresh.
     */
    private fun finalOverlayGuard(
        overlayMode: Boolean,
        validated: OverlayExpectation?,
        startedAt: Long,
        deadlineMs: Long,
    ) {
        if (overlayRequired(startedAt + deadlineMs) != overlayMode) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        // Notification mode never reads the expectation (original path, no overlay observation).
        if (!overlayMode) return
        val expected = validated ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        val fresh = overlayExpectation(startedAt + deadlineMs)
        val now = clock()
        val changed = fresh.owner != expected.owner ||
            fresh.generation != expected.generation ||
            fresh.revision != expected.revision ||
            fresh.windowToken != expected.windowToken ||
            fresh.phase != OverlayPhase.VISIBLE ||
            fresh.freshnessDeadlineMs <= now
        if (changed) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        // A mode change while the claim was being read is still a refusal, not a downgrade.
        if (overlayRequired(startedAt + deadlineMs) != true) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
    }

    private fun rememberOverlay(windowId: String, witness: OverlayWitness) {
        synchronized(overlayObservations) {
            overlayObservations[windowId] = OverlayObservation(
                owner = witness.owner,
                generation = witness.generation,
                revision = witness.revision,
                display = witness.display,
            )
            while (overlayObservations.size > MAX_OVERLAY_OBSERVATIONS) {
                val oldest = overlayObservations.keys.firstOrNull() ?: break
                overlayObservations.remove(oldest)
            }
        }
    }

    private fun overlayObservation(windowId: String): OverlayObservation? =
        synchronized(overlayObservations) { overlayObservations[windowId] }

    private fun readLiveWindow(startedAt: Long, deadlineMs: Long): LiveWindow {
        val before = readWindowFocus(startedAt, deadlineMs)
        val hierarchy = readHierarchy(startedAt, deadlineMs)
        val sizeText = device.displaySizeText(left(startedAt, deadlineMs))
        val after = readWindowFocus(startedAt, deadlineMs)
        if (before != after) throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        if (after.currentFocusPackage != hierarchy.packageName ||
            after.focusedAppPackage != hierarchy.packageName
        ) {
            // The foreground window-manager identity is not the hierarchy's window (IME, overlay,
            // another app). Do not mix that hierarchy with the expected window.
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        val size = AdbDisplay.parseSize(sizeText)
            ?: throw PhoneDriverException(PhoneDriverFailure.GEOMETRY_CHANGED)
        val geometry = AdbDisplay.geometry(size, hierarchy.rotation)
            ?: throw PhoneDriverException(PhoneDriverFailure.GEOMETRY_CHANGED)
        val snapshot = AdbSnapshotBuilder.build(hierarchy, geometry, after, maxNodes)
        return LiveWindow(hierarchy, geometry, after, snapshot)
    }

    private fun verifyLiveWindow(
        binding: PhoneObservationBinding,
        startedAt: Long,
        deadlineMs: Long,
    ): LiveWindow {
        val live = readLiveWindow(startedAt, deadlineMs)
        if (live.hierarchy.packageName != binding.packageName) {
            throw PhoneDriverException(PhoneDriverFailure.TARGET_CHANGED)
        }
        if (live.focus.focusedAppPackage != binding.packageName) {
            throw PhoneDriverException(PhoneDriverFailure.TARGET_CHANGED)
        }
        if (live.hierarchy.rotation != binding.rotation || live.geometry != binding.geometry) {
            throw PhoneDriverException(PhoneDriverFailure.GEOMETRY_CHANGED)
        }
        // Only the real window identity (focus/app tokens, activity, package, rotation, display)
        // must match. Page content is allowed to keep changing (countdowns, banners, prices):
        // requiring an identical content digest made ordinary live pages impossible to act on.
        if (windowDigest(live.snapshot.windowId) != windowDigest(binding.windowId)) {
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        return live
    }

    private fun windowDigest(windowId: String): String =
        AdbWindowWitness.parse(windowId)?.windowDigest
            ?: throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)

    /** A tap by ref still needs a live element at exactly the observed bounds. */
    private fun requireLiveTarget(live: LiveWindow, target: PhoneRect?) {
        if (target == null) return
        if (live.snapshot.nodes.none { it.bounds == target }) {
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
    }

    private fun verifyInputFollowup(
        binding: PhoneObservationBinding,
        focus: PhonePoint,
        overlayObservation: OverlayObservation?,
        overlayMode: Boolean,
        startedAt: Long,
        deadlineMs: Long,
    ): OverlayExpectation? {
        // A bounded settle for the focus event; never beyond a quarter of the remaining deadline.
        val settle = minOf(FOCUS_SETTLE_MS, left(startedAt, deadlineMs) / 4)
        if (settle > 0L) Thread.sleep(settle)
        val live = readLiveWindow(startedAt, deadlineMs)
        val expected = AdbWindowWitness.parse(binding.windowId)
            ?: throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        val actual = AdbWindowIdentity.witness(
            focus = live.focus,
            packageName = live.hierarchy.packageName,
            geometry = live.geometry,
            nodes = live.snapshot.nodes,
        )
        if (actual.windowDigest != expected.windowDigest) { // content may legitimately change
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        if (live.hierarchy.packageName != binding.packageName ||
            live.focus.focusedAppPackage != binding.packageName
        ) {
            throw PhoneDriverException(PhoneDriverFailure.TARGET_CHANGED)
        }
        val target = inputTarget(live.snapshot.nodes, focus)
        if (target == null || !target.editable || !target.focused) {
            throw PhoneDriverException(PhoneDriverFailure.UNSUPPORTED)
        }
        if (overlayRequired(startedAt + deadlineMs) != overlayMode) {
            throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        }
        if (!overlayMode) return null
        val stored = overlayObservation
            ?: throw PhoneDriverException(PhoneDriverFailure.POLICY_DENIED)
        val expectation = overlayExpectation(startedAt + deadlineMs)
        val witness = readOverlayWitness(
            expectation = expectation,
            display = binding.geometry,
            startedAt = startedAt,
            deadlineMs = deadlineMs,
        )
        val now = clock()
        val refusal = OverlaySafetyPolicy.identityRefusal(witness, stored, now)
            ?: OverlaySafetyPolicy.inputFocusRefusal(witness, focus, now)
        if (refusal != null) throw PhoneDriverException(overlayRefusalFailure(refusal))
        return expectation
    }

    /** An action that would only hit the visible overlay is refused alone, not as overlay loss. */
    private fun overlayRefusalFailure(refusal: io.andchamber.app.phone.overlay.safety.OverlayRefusal): PhoneDriverFailure =
        if (OverlaySafetyPolicy.isObstruction(refusal)) {
            PhoneDriverFailure.OVERLAY_OBSTRUCTED
        } else {
            PhoneDriverFailure.POLICY_DENIED
        }

    private fun inputTarget(nodes: List<PhoneNode>, focus: PhonePoint): PhoneNode? =
        nodes.asSequence()
            .filter { node ->
                node.bounds.left <= focus.x && focus.x < node.bounds.right &&
                    node.bounds.top <= focus.y && focus.y < node.bounds.bottom
            }
            .minByOrNull { node ->
                node.bounds.width.toLong() * node.bounds.height.toLong()
            }

    private fun readHierarchy(startedAt: Long, deadlineMs: Long): AdbHierarchy {
        val xml = device.hierarchyXml(left(startedAt, deadlineMs), AdbHierarchyParser.MAX_XML_BYTES)
        return AdbHierarchyParser.parse(xml)
    }

    private fun readWindowFocus(startedAt: Long, deadlineMs: Long): AdbFocusedWindow {
        val text = device.windowStateText(left(startedAt, deadlineMs), AdbWindowStateParser.MAX_BYTES)
        return try {
            AdbWindowStateParser.parse(text)
        } catch (failure: AdbWindowStateException) {
            // A complete official `=null` field line during a window transition is a retryable
            // window change inside the caller's existing bounded startup budget. Every other
            // parser failure stays the fixed DRIVER failure.
            throw PhoneDriverException(
                if (failure.failure == AdbWindowStateFailure.NOT_READY) {
                    PhoneDriverFailure.WINDOW_CHANGED
                } else {
                    PhoneDriverFailure.DRIVER
                },
            )
        }
    }

    private fun left(startedAt: Long, deadlineMs: Long): Long {
        val remaining = deadlineMs - (clock() - startedAt)
        if (remaining <= 0L) throw PhoneDriverException(PhoneDriverFailure.TIMEOUT)
        return remaining
    }

    private fun mapFailure(failure: AdbDeviceFailure): PhoneDriverFailure = when (failure) {
        AdbDeviceFailure.NOT_CONNECTED -> PhoneDriverFailure.NOT_CONNECTED
        AdbDeviceFailure.UNREACHABLE -> PhoneDriverFailure.NOT_CONNECTED
        AdbDeviceFailure.DEADLINE -> PhoneDriverFailure.TIMEOUT
        AdbDeviceFailure.CANCELLED -> PhoneDriverFailure.CANCELLED
        AdbDeviceFailure.POLICY -> PhoneDriverFailure.POLICY_DENIED
        AdbDeviceFailure.PAIRING_REQUIRED -> PhoneDriverFailure.POLICY_DENIED
        AdbDeviceFailure.TLS_REQUIRED -> PhoneDriverFailure.POLICY_DENIED
        AdbDeviceFailure.TLS_UNAVAILABLE -> PhoneDriverFailure.UNSUPPORTED
        AdbDeviceFailure.AUTH_FAILED -> PhoneDriverFailure.POLICY_DENIED
        AdbDeviceFailure.LIMIT -> PhoneDriverFailure.DRIVER
        AdbDeviceFailure.OUTCOME_UNKNOWN -> PhoneDriverFailure.OUTCOME_UNKNOWN
        AdbDeviceFailure.HIERARCHY_NOT_IDLE -> PhoneDriverFailure.HIERARCHY_NOT_IDLE
        AdbDeviceFailure.READ_TIMEOUT -> PhoneDriverFailure.READ_TIMEOUT
        AdbDeviceFailure.DRIVER -> PhoneDriverFailure.DRIVER
    }

    private data class LiveWindow(
        val hierarchy: AdbHierarchy,
        val geometry: PhoneGeometry,
        val focus: AdbFocusedWindow,
        val snapshot: PhoneSnapshot,
    )

    private data class InjectionStep(
        val command: AdbInputCommand,
        val before: (() -> Unit)? = null,
        /** Runs after the controller admission guard, immediately before the real injection. */
        val finalGuard: (() -> Unit)? = null,
    )

    companion object {
        fun capabilitiesFor(platformSupported: Boolean): PhoneCapabilities =
            if (platformSupported) {
                PhoneCapabilities(
                    observe = true,
                    capture = true,
                    tap = true,
                    swipe = true,
                    input = true,
                    unicodeInput = false,
                    back = true,
                )
            } else {
                PhoneCapabilities()
            }

        private const val FOCUS_SETTLE_MS = 120L
        private const val MAX_OVERLAY_OBSERVATIONS = 8

        /** Clearance around a tap/focus point and around a swipe segment for a make-way request. */
        private const val MAKE_WAY_POINT_MARGIN_PX = 8
    }
}
