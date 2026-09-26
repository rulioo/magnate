package com.magnate.compass.util

import com.magnate.compass.TEST_ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimeFormatTest {

    // 2024-03-05 14:30:00 +08:00
    private val now = 1_709_620_200_000L

    // 2024-03-05 23:59:59.999 +08:00
    private val lastMomentOfToday = 1_709_654_399_999L

    // 2024-03-06 00:00:00.000 +08:00
    private val firstMomentOfTomorrow = 1_709_654_400_000L

    private fun millisOf(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(TEST_ZONE).toInstant().toEpochMilli()

    // —— 格式化 ——

    @Test
    fun `按固定时区格式化日期时间`() {
        assertEquals("2024-03-05 14:30:00", TimeFormat.formatDateTime(now, TEST_ZONE))
        assertEquals("2024-03-05", TimeFormat.formatDate(now, TEST_ZONE))
        assertEquals("14:30:00", TimeFormat.formatTime(now, TEST_ZONE))
        assertEquals("03-05 14:30", TimeFormat.formatShortDateTime(now, TEST_ZONE))
    }

    @Test
    fun `同一个时间戳在不同时区显示不同日期`() {
        // 存储一律是 epoch millis，只有显示才落本地时区——这条用例把这个约定钉住
        assertEquals("2024-03-05", TimeFormat.formatDate(now, TEST_ZONE))
        assertEquals("2024-03-05", TimeFormat.formatDate(now, java.time.ZoneId.of("UTC"))) // 06:30 UTC
        assertEquals("2024-03-04", TimeFormat.formatDate(now, java.time.ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `日期分组标题用日历日而不是 24 小时差`() {
        val yesterdayEvening = millisOf(LocalDate.of(2024, 3, 4), hour = 23)
        val earlyMorning = millisOf(LocalDate.of(2024, 3, 5), hour = 1)

        // 两者只差 2 小时，但分属两个日历日。
        // 若按固定 24 小时差分组，凌晨 1 点看昨晚 23 点的记录会被算成「今天」。
        assertEquals("昨天", TimeFormat.dayHeader(yesterdayEvening, earlyMorning, TEST_ZONE))
        assertEquals("今天", TimeFormat.dayHeader(earlyMorning, earlyMorning, TEST_ZONE))
    }

    @Test
    fun `更早的日期直接显示日期`() {
        val threeDaysAgo = millisOf(LocalDate.of(2024, 3, 2), hour = 10)
        assertEquals("2024-03-02", TimeFormat.dayHeader(threeDaysAgo, now, TEST_ZONE))
    }

    @Test
    fun `跨年的昨天仍然显示为昨天`() {
        val dec31 = millisOf(LocalDate.of(2024, 12, 31), hour = 20)
        val jan1 = millisOf(LocalDate.of(2025, 1, 1), hour = 9)
        assertEquals("昨天", TimeFormat.dayHeader(dec31, jan1, TEST_ZONE))
    }

    // —— 日界线 ——

    @Test
    fun `startOfDay 是当天零点`() {
        assertEquals(millisOf(LocalDate.of(2024, 3, 5)), TimeFormat.startOfDay(now, TEST_ZONE))
        // 当天最后一毫秒仍属于同一天
        assertEquals(
            millisOf(LocalDate.of(2024, 3, 5)),
            TimeFormat.startOfDay(lastMomentOfToday, TEST_ZONE),
        )
        // 次日第一毫秒属于次日
        assertEquals(
            millisOf(LocalDate.of(2024, 3, 6)),
            TimeFormat.startOfDay(firstMomentOfTomorrow, TEST_ZONE),
        )
    }

    @Test
    fun `startOfNextDay 是次日零点 正好是当天的开区间上界`() {
        assertEquals(millisOf(LocalDate.of(2024, 3, 6)), TimeFormat.startOfNextDay(now, TEST_ZONE))
        // 半开区间 [startOfDay, startOfNextDay) 恰好覆盖当天全部毫秒，
        // 且 nextDayStart - 1 就是 23:59:59.999，不多不少
        assertEquals(lastMomentOfToday, TimeFormat.startOfNextDay(now, TEST_ZONE) - 1)
    }

    @Test
    fun `半开区间覆盖当天每一毫秒且不越界`() {
        val start = TimeFormat.startOfDay(now, TEST_ZONE)
        val endExclusive = TimeFormat.startOfNextDay(now, TEST_ZONE)

        assertTrue("零点必须落在区间内", start < endExclusive)
        assertTrue("23:59:59.999 必须落在区间内", lastMomentOfToday < endExclusive)
        assertTrue("次日零点必须落在区间外", firstMomentOfTomorrow >= endExclusive)
    }

    @Test
    fun `跨月与跨年的次日边界`() {
        val march1 = millisOf(LocalDate.of(2024, 3, 1), hour = 8)
        assertEquals(millisOf(LocalDate.of(2024, 3, 2)), TimeFormat.startOfNextDay(march1, TEST_ZONE))

        val jan1 = millisOf(LocalDate.of(2025, 1, 1), hour = 8)
        assertEquals(millisOf(LocalDate.of(2025, 1, 2)), TimeFormat.startOfNextDay(jan1, TEST_ZONE))

        // 平年 2 月 28 日的次日是 3 月 1 日
        val feb28 = millisOf(LocalDate.of(2025, 2, 28), hour = 8)
        assertEquals(millisOf(LocalDate.of(2025, 3, 1)), TimeFormat.startOfNextDay(feb28, TEST_ZONE))

        // 闰年 2 月 28 日的次日是 2 月 29 日
        val leapFeb28 = millisOf(LocalDate.of(2024, 2, 28), hour = 8)
        assertEquals(millisOf(LocalDate.of(2024, 2, 29)), TimeFormat.startOfNextDay(leapFeb28, TEST_ZONE))
    }

    @Test
    fun `lastDaysStart 含今天`() {
        // 「近 7 天」在用户心里是「今天在内的 7 天」，不是「7 个 24 小时」
        assertEquals(millisOf(LocalDate.of(2024, 3, 5)), TimeFormat.lastDaysStart(now, 1, TEST_ZONE))
        assertEquals(millisOf(LocalDate.of(2024, 2, 29)), TimeFormat.lastDaysStart(now, 6, TEST_ZONE))
        assertEquals(millisOf(LocalDate.of(2024, 2, 28)), TimeFormat.lastDaysStart(now, 7, TEST_ZONE))
        assertEquals(millisOf(LocalDate.of(2024, 2, 4)), TimeFormat.lastDaysStart(now, 31, TEST_ZONE))
    }

    @Test
    fun `lastDaysStart 跨年正确`() {
        val jan1 = millisOf(LocalDate.of(2025, 1, 1), hour = 9)
        assertEquals(millisOf(LocalDate.of(2024, 12, 26)), TimeFormat.lastDaysStart(jan1, 7, TEST_ZONE))
    }

    @Test
    fun `时间戳在日界线上不会因毫秒四舍五入而错位`() {
        // 用「加一天减一毫秒」构造上界时，若中途做过秒级取整，
        // 23:59:59.500 这样的值就会被漏掉
        val almostMidnight = millisOf(LocalDate.of(2024, 3, 5), hour = 23, minute = 59)
        assertEquals(
            millisOf(LocalDate.of(2024, 3, 5)),
            TimeFormat.startOfDay(almostMidnight, TEST_ZONE),
        )
        assertEquals(
            millisOf(LocalDate.of(2024, 3, 6)),
            TimeFormat.startOfNextDay(almostMidnight, TEST_ZONE),
        )
    }
}
