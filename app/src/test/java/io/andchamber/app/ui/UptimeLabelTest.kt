package io.andchamber.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UptimeLabelTest {
    private val chinese =
        DurationUnits(
            minutes = { m -> "$m 分钟" },
            hoursMinutes = { h, m -> "$h 小时 $m 分钟" },
            daysHours = { d, h -> "$d 天 $h 小时" },
        )

    private fun label(minutes: Long) =
        uptimeLabel(
            sinceEpochMs = 1_000L,
            nowEpochMs = 1_000L + minutes * 60_000L,
            justStarted = "刚刚启动",
            upTemplate = { "已运行 $it" },
            units = chinese,
        )

    @Test
    fun `uses the localised duration units`() {
        assertEquals("刚刚启动", label(0))
        assertEquals("已运行 6 分钟", label(6))
        assertEquals("已运行 2 小时 5 分钟", label(125))
        assertEquals("已运行 3 天 4 小时", label(3 * 24 * 60 + 4 * 60 + 7))
    }

    @Test
    fun `a clock that went backwards counts as just started`() {
        assertEquals(
            "刚刚启动",
            uptimeLabel(5_000_000L, 1_000L, "刚刚启动", { "已运行 $it" }, chinese),
        )
    }
}
