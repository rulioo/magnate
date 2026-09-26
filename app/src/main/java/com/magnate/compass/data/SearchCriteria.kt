package com.magnate.compass.data

import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.util.TimeFormat
import java.time.ZoneId

enum class TimePreset { ALL, TODAY, LAST_7, LAST_30, CUSTOM }

enum class MagnitudePreset { ALL, LOW, NORMAL, HIGH, CUSTOM }

enum class CoordinatePreset { ALL, HAS, NONE }

/**
 * 搜索页的**用户意图**，与 [RecordFilter] 是两回事。
 *
 * `RecordFilter` 描述的是「SQL 该带哪些条件」，比如它只有 `timeFrom`/`timeTo` 两个毫秒值。
 * 但界面上用户点的是「近 7 天」这个**预设**，而且这个身份必须留到最后——
 * §8.3 的活跃筛选 chip 要显示「近 7天」而不是一串时间戳，点 ✕ 才知道该清哪一项。
 *
 * 从时间戳反推预设是不可靠的（一段恰好是 7 天的自定义区间会被误认成「近 7 天」），
 * 所以预设身份在这里作为**一等字段**保存，仅在需要查询时降维成时间戳。
 *
 * 纯数据 + 纯函数，可在 JVM 上直接单测——预设与时间戳的换算正是最容易算错的地方。
 */
data class SearchCriteria(
    val keyword: String = "",

    val sceneIds: Set<Long> = emptySet(),
    val tagIds: Set<Long> = emptySet(),

    /** true = 同时包含全部（AND），false = 任一包含（OR） */
    val tagMatchAll: Boolean = true,

    val timePreset: TimePreset = TimePreset.ALL,
    /** 自定义区间的起止（用户选的**日期**当天）。仅 [TimePreset.CUSTOM] 时有意义。 */
    val customTimeFrom: Long? = null,
    val customTimeTo: Long? = null,

    val magnitudePreset: MagnitudePreset = MagnitudePreset.ALL,
    val customMagnitudeMin: Float? = null,
    val customMagnitudeMax: Float? = null,

    val coordinatePreset: CoordinatePreset = CoordinatePreset.ALL,

    /** 数据质量过滤：±2000m 的网络定位点做空间分析时应当被排除（§8.4）。 */
    val goodAccuracyOnly: Boolean = false,
) {

    /** 除关键词外是否还有其他条件。用于「重置筛选」与活跃 chip 行的显隐。 */
    val hasFilters: Boolean
        get() = sceneIds.isNotEmpty() ||
            tagIds.isNotEmpty() ||
            timePreset != TimePreset.ALL ||
            magnitudePreset != MagnitudePreset.ALL ||
            coordinatePreset != CoordinatePreset.ALL ||
            goodAccuracyOnly

    /** 有没有任何条件（含关键词）。用于区分两种空状态（§8.5）。 */
    val hasAnything: Boolean get() = hasFilters || keyword.isNotBlank()

    /** §8.5 的出口之一：只清关键词。 */
    fun withoutKeyword(): SearchCriteria = copy(keyword = "")

    /** §8.5 的出口之二：清掉全部筛选条件，**保留关键词**。 */
    fun withoutFilters(): SearchCriteria = copy(
        sceneIds = emptySet(),
        tagIds = emptySet(),
        tagMatchAll = true,
        timePreset = TimePreset.ALL,
        customTimeFrom = null,
        customTimeTo = null,
        magnitudePreset = MagnitudePreset.ALL,
        customMagnitudeMin = null,
        customMagnitudeMax = null,
        coordinatePreset = CoordinatePreset.ALL,
        goodAccuracyOnly = false,
    )

    /**
     * 降维成 SQL 条件。
     *
     * [nowMillis] 显式传入而非内部取当前时间：预设区间（今天 / 近 7 天）依赖「现在」，
     * 而「现在」在测试里必须可控。
     */
    fun toFilter(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): RecordFilter =
        RecordFilter(
            keyword = keyword.trim().takeIf { it.isNotEmpty() },
            tagIds = tagIds,
            tagMatchAll = tagMatchAll,
            sceneIds = sceneIds,
            timeFrom = timeFrom(nowMillis, zone),
            timeTo = timeTo(nowMillis, zone),
            magnitudeMin = magnitudeMin(),
            magnitudeMax = magnitudeMax(),
            magnitudeMinExclusive = magnitudePreset == MagnitudePreset.HIGH,
            magnitudeMaxExclusive = magnitudePreset == MagnitudePreset.LOW,
            hasLocation = when (coordinatePreset) {
                CoordinatePreset.ALL -> null
                CoordinatePreset.HAS -> true
                CoordinatePreset.NONE -> false
            },
            minLocationAccuracy = if (goodAccuracyOnly) GOOD_ACCURACY_THRESHOLD_M else null,
            sortBy = SortField.TIME,
            sortAsc = false,
        )

    private fun timeFrom(nowMillis: Long, zone: ZoneId): Long? = when (timePreset) {
        TimePreset.ALL -> null
        TimePreset.TODAY -> TimeFormat.startOfDay(nowMillis, zone)
        TimePreset.LAST_7 -> TimeFormat.lastDaysStart(nowMillis, 7, zone)
        TimePreset.LAST_30 -> TimeFormat.lastDaysStart(nowMillis, 30, zone)
        TimePreset.CUSTOM -> customTimeFrom?.let { TimeFormat.startOfDay(it, zone) }
    }

    /**
     * 上界一律取「次日 00:00 减 1 毫秒」，即含当天 23:59:59.999。
     *
     * 直接用日期当天的 00:00 作上界，用户选了「今天」却查不到今天的记录——
     * 这是筛选器最经典的一个坑（§8.4）。
     */
    private fun timeTo(nowMillis: Long, zone: ZoneId): Long? = when (timePreset) {
        TimePreset.ALL -> null
        TimePreset.CUSTOM -> customTimeTo?.let { TimeFormat.startOfNextDay(it, zone) - 1 }
        // 三个「从某天起、到此刻为止」的预设上界完全一致：都是今天结束
        else -> TimeFormat.startOfNextDay(nowMillis, zone) - 1
    }

    private fun magnitudeMin(): Float? = when (magnitudePreset) {
        MagnitudePreset.ALL, MagnitudePreset.LOW -> null
        MagnitudePreset.NORMAL -> CompassMath.MAGNETIC_FIELD_MIN_UT
        MagnitudePreset.HIGH -> CompassMath.MAGNETIC_FIELD_MAX_UT
        MagnitudePreset.CUSTOM -> customMagnitudeMin
    }

    private fun magnitudeMax(): Float? = when (magnitudePreset) {
        MagnitudePreset.ALL -> null
        MagnitudePreset.LOW -> CompassMath.MAGNETIC_FIELD_MIN_UT
        MagnitudePreset.NORMAL -> CompassMath.MAGNETIC_FIELD_MAX_UT
        MagnitudePreset.HIGH -> null
        MagnitudePreset.CUSTOM -> customMagnitudeMax
    }

    companion object {
        /** 「仅显示定位精度优于 N 米」的 N（design-gui.md §8.4）。 */
        const val GOOD_ACCURACY_THRESHOLD_M = 50f
    }
}
