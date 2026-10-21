package io.andchamber.app.browser

/** Fixed numeric schema: no caller-supplied text can enter the trace. No Android dependencies. */
internal class NativeFixtureTimelineRing(private val clock: () -> Long) {
    enum class Kind { INSTALL, ATTACH, DETACH, FOCUS, LAYOUT, BEFORE_DOWN, AFTER_UP, COMMAND_END, CLOSE }
    private val rows = ArrayDeque<String>()
    private var bytes = 0
    private var lastTime = 0L
    var sequence = 0L
        private set
    var overflow = 0L
        private set

    fun record(kind: Kind, source: Int, values: List<Long?>) {
        require(values.size == FIELDS.size - 4)
        lastTime = maxOf(lastTime, clock())
        val row = (listOf(++sequence, lastTime, kind.ordinal.toLong(), source.toLong()) + values)
            .joinToString(prefix = "[", postfix = "]", separator = ",") { it?.toString() ?: "null" }
        rows.addLast(row)
        bytes += row.length + 1
        while (rows.size > 256 || bytes > 60_000) {
            bytes -= rows.removeFirst().length + 1
            overflow++
        }
    }

    fun json(): String = "{\"schema\":1,\"seq\":$sequence,\"overflow\":$overflow," +
        "\"fields\":[${FIELDS.joinToString(",") { "\"$it\"" }}]," +
        "\"kinds\":[${Kind.entries.joinToString(",") { "\"${it.name}\"" }}]," +
        "\"events\":[${rows.joinToString(",")}] }"

    companion object {
        val FIELDS = listOf("seq", "elapsedMs", "kind", "source", "generation", "current",
            "x", "y", "width", "height", "attached", "shown", "nativeFocus", "windowFocus",
            "activityFocus", "decorFocus", "parent", "holder", "rootHeight", "imeVisible",
            "imeCurrentBottom", "imeTargetBottom", "visibleLeft", "visibleTop", "visibleRight",
            "visibleBottom", "visible", "presenterShown", "retired", "agentOn", "oldFocus", "newFocus",
            "tight")
    }
}
