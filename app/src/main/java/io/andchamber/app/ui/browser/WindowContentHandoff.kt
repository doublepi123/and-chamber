package io.andchamber.app.ui.browser

/** Actual Compose content allocation in window physical pixels, never Session.currentFrame. */
internal data class WindowContentRect(val left: Float, val top: Float, val width: Int, val height: Int) {
    fun valid(): Boolean = left.isFinite() && top.isFinite() && width > 0 && height > 0
}

/** Immutable render request captured by the content composition, not looked up in a posted callback. */
internal class WindowContentRender internal constructor(
    val token: WindowChangeToken,
    val source: Any?,
    val sourceHandle: Int,
    val afterRevision: Long,
    val binding: WindowContentBinding?,
)

internal class WindowContentAllocation internal constructor(
    val render: WindowContentRender?,
    val source: Any,
    val mount: Any,
    val revision: Long,
    val rect: WindowContentRect,
)

internal sealed interface WindowContentReport {
    data object Ignore : WindowContentReport
    data object GeometryOnly : WindowContentReport
    data class Restore(val target: WindowContentTarget) : WindowContentReport
}

/** Main-owned UI observation bookkeeping, not page authority or a second bookmark store. */
internal class WindowContentHandoff {
    var lastActualContentRevision = 0L
        private set
    private var prepared: WindowContentRender? = null

    /** Called after fresh capture, before the synchronous invalidation/constraint flip. */
    fun prepare(
        result: WindowChange,
        source: Any?,
        sourceHandle: Int,
        bindRestore: (WindowChangeToken, Long) -> WindowContentBinding?,
    ): WindowChange {
        if (result !is WindowChange.Proceed) return result
        val floor = lastActualContentRevision
        // Core's bind method is restore-only. Enlarge must not depend on it returning non-null.
        val binding = if (result.token.next) null else bindRestore(result.token, floor)
        if (!result.token.next && (source == null || binding == null ||
                binding.change != result.token || binding.sourceHandle != sourceHandle ||
                binding.afterLayoutRevision != floor)) return WindowChange.Superseded
        prepared = WindowContentRender(result.token, source, sourceHandle, floor, binding)
        return result
    }

    fun rendered(token: WindowChangeToken?): WindowContentRender? =
        prepared?.takeIf { token != null && it.token == token }

    /** Invoke only from an actual content allocation/place callback, even if its size is unchanged. */
    fun allocated(render: WindowContentRender?, source: Any, mount: Any, rect: WindowContentRect): WindowContentAllocation {
        check(lastActualContentRevision < Long.MAX_VALUE) { "content layout revision exhausted" }
        return WindowContentAllocation(render, source, mount, ++lastActualContentRevision, rect)
    }

    /** Builds the immutable target BEFORE acknowledging. A failed ack cannot consume a new token. */
    fun report(
        allocation: WindowContentAllocation,
        currentSource: Any?,
        currentParent: Any?,
        sourceHandle: Int,
        attached: Boolean,
        acknowledge: (WindowChangeToken) -> Boolean,
    ): WindowContentReport {
        if (!attached || allocation.source !== currentSource || allocation.mount !== currentParent ||
            allocation.revision != lastActualContentRevision || !allocation.rect.valid()) return WindowContentReport.Ignore
        val render = allocation.render ?: return WindowContentReport.GeometryOnly
        if (prepared !== render || render.source !== allocation.source || render.sourceHandle != sourceHandle ||
            allocation.revision <= render.afterRevision) return WindowContentReport.Ignore
        val target = if (render.token.next) null else {
            val binding = render.binding ?: return WindowContentReport.Ignore
            if (allocation.revision <= binding.afterLayoutRevision) return WindowContentReport.Ignore
            val rect = allocation.rect
            WindowContentTarget(binding, allocation.revision, rect.left, rect.top, rect.width, rect.height)
        }
        if (!acknowledge(render.token)) return WindowContentReport.Ignore
        return if (target == null) WindowContentReport.GeometryOnly else WindowContentReport.Restore(target)
    }
}
