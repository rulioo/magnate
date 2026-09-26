package com.magnate.compass.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间格式化。纯函数，可在 JVM 上直接单测。
 *
 * 时区作为参数显式传入（默认跟随系统）——测试里固定时区才能得到稳定断言。
 * 存储一律是 epoch millis（UTC），只有显示才落到本地时区（design.md §5.4）。
 *
 * 依赖 `java.time`，在 minSdk 24 上由 core library desugaring 提供（见 app/build.gradle.kts）。
 */
object TimeFormat {

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val SHORT_DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** `2026-09-24 14:32:07` */
    fun formatDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.format(toLocalDateTime(millis, zone))

    /** `2026-09-24` */
    fun formatDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_ONLY.format(toLocalDateTime(millis, zone))

    /** `14:32:07` */
    fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_ONLY.format(toLocalDateTime(millis, zone))

    /** `09-24 14:32` */
    fun formatShortDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        SHORT_DATE_TIME.format(toLocalDateTime(millis, zone))

    /**
     * 记录列表的日期分组标题：今天显示「今天」，昨天显示「昨天」，其余显示日期。
     * 比较用**日历日**而非固定 24 小时差——凌晨 1 点看昨天 23 点的记录应显示「昨天」。
     */
    fun dayHeader(
        millis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = toLocalDate(millis, zone)
        val today = toLocalDate(nowMillis, zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> DATE_ONLY.format(date)
        }
    }

    /** 某一天的 00:00:00.000。筛选区间用**半开区间** `[start, endExclusive)` 表达。 */
    fun startOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        toLocalDate(millis, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * 某一天的次日 00:00:00.000，即当天在**开区间**里的上界。
     *
     * 自定义区间的结束日期必须含当天 23:59:59（design-gui.md §8.4）——
     * 拿日期当天的 00:00 当上界，用户选了「今天」却查不到今天的记录，
     * 这是几乎每个筛选器都会踩一次的经典陷阱。这里用半开区间 `[start, nextDayStart)`
     * 表达，而不是去构造 23:59:59.999，省掉一层「毫秒算不算」的争议。
     */
    fun startOfNextDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        toLocalDate(millis, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 今天 00:00 往前推 [days] 天的 00:00。`lastDaysStart(now, 7)` 即「近 7 天」的起点（含今天）。 */
    fun lastDaysStart(
        nowMillis: Long,
        days: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = toLocalDate(nowMillis, zone).minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun toLocalDateTime(millis: Long, zone: ZoneId) =
        java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun toLocalDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
