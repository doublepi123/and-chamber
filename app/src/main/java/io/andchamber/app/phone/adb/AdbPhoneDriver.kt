// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import android.os.Build
import android.os.SystemClock
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
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
 * (mCurrentFocus and mFocusedApp tokens) and the observed node content. A same-package new activity,
 * a dialog, a moved element or changed label therefore invalidates the binding. The window identity
 * is read before and after observation/capture; an unreliable `dumpsys window` result fails closed.
 * Check and injection are still separate steps, not a cross-process atomic transaction.
 */
class AdbPhoneDriver(
    private val device: AdbDevice,
    private val runtimeId: String,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val connectionId: () -> String = { UUID.randomUUID().toString() },
    private val maxNodes: Int = PhoneLimits.MAX_NODES,
    private val captureMaxBytes: Int = PhoneLimits.MAX_CAPTURE_BYTES,
    private val platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
) : PhoneDriver {
    init {
        require(runtimeId.isNotBlank() && runtimeId.length <= PhoneLimits.MAX_WINDOW_ID_CHARS)
        require(runtimeId.none { it.isISOControl() })
        require(maxNodes in 1..PhoneLimits.MAX_NODES)
        require(captureMaxBytes in 1..PhoneLimits.MAX_CAPTURE_BYTES)
    }

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
            readLiveWindow(started, deadlineMs).snapshot
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
            val before = verifyLiveWindow(binding, started, deadlineMs)
            val bytes = device.capturePng(left(started, deadlineMs), captureMaxBytes)
            // Re-read the real window after the screenshot. If it changed while pixels were being
            // copied, discard the image instead of returning another window's content.
            val after = verifyLiveWindow(binding, started, deadlineMs)
            if (before.snapshot.windowId != after.snapshot.windowId) {
                throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
            }
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

        val steps = when (val action = request.action) {
            is PhoneDriverAction.Tap -> {
                if (!action.at.isWithin(binding.geometry)) {
                    return@withContext PhoneDispatchReceipt.NotSent(PhoneDriverFailure.INVALID_REQUEST)
                }
                listOf(InjectionStep(AdbInputCommand.Tap(action.at.x, action.at.y)))
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
                    InjectionStep(
                        AdbInputCommand.Swipe(
                            fromX = action.from.x,
                            fromY = action.from.y,
                            toX = action.to.x,
                            toY = action.to.y,
                            durationMs = action.durationMs,
                        ),
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
                    listOf(
                        InjectionStep(AdbInputCommand.Tap(focus.x, focus.y)),
                        InjectionStep(AdbInputCommand.Text(action.text)) {
                            // After the focus tap the hierarchy/content has legitimately changed.
                            // Re-check the real window identity and the actual focus field.
                            verifyInputFollowup(binding, focus, started, deadlineMs)
                        },
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
                    listOf(InjectionStep(AdbInputCommand.Text(action.text)))
                }
            }
            PhoneDriverAction.Back -> listOf(InjectionStep(AdbInputCommand.Back))
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
        if (live.snapshot.windowId != binding.windowId) {
            throw PhoneDriverException(PhoneDriverFailure.WINDOW_CHANGED)
        }
        return live
    }

    private fun verifyInputFollowup(
        binding: PhoneObservationBinding,
        focus: PhonePoint,
        startedAt: Long,
        deadlineMs: Long,
    ) {
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
        if (actual.windowDigest != expected.windowDigest) {
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
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
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
    }
}
