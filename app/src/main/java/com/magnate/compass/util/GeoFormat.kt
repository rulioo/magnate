package com.magnate.compass.util

import java.util.Locale

/** 定位精度的分档，决定 UI 用 success / 次要色 / warning 中的哪一个（design-gui.md §6.3）。 */
enum class AccuracyTier {
    /** ≤ 10m */
    GOOD,

    /** ≤ 50m */
    MEDIUM,

    /** > 50m */
    POOR,

    /** 精度字段缺失。缺失必须表现为最差，不能表现为「极好」。 */
    UNKNOWN,
}

/**
 * 坐标与精度的格式化。纯函数，无 Android 依赖，可在 JVM 上直接单测。
 */
object GeoFormat {

    fun formatLatitude(latitude: Double): String {
        val hemisphere = if (latitude < 0) "S" else "N"
        return String.format(Locale.US, "%.6f°%s", kotlin.math.abs(latitude), hemisphere)
    }

    fun formatLongitude(longitude: Double): String {
        val hemisphere = if (longitude < 0) "W" else "E"
        return String.format(Locale.US, "%.6f°%s", kotlin.math.abs(longitude), hemisphere)
    }

    /** `30.123456°N, 120.567890°E` */
    fun formatCoordinates(latitude: Double, longitude: Double): String =
        "${formatLatitude(latitude)}, ${formatLongitude(longitude)}"

    /** `30.1234°N, 120.5678°E`——场景选择器等空间紧张处使用 */
    fun formatCoordinatesShort(latitude: Double, longitude: Double): String {
        val lat = String.format(Locale.US, "%.4f°%s", kotlin.math.abs(latitude), if (latitude < 0) "S" else "N")
        val lon = String.format(Locale.US, "%.4f°%s", kotlin.math.abs(longitude), if (longitude < 0) "W" else "E")
        return "$lat, $lon"
    }

    /**
     * `±8m`。
     *
     * `Float.MAX_VALUE` 是 [com.magnate.compass.data.resolveLocation] 为缺失精度填入的哨兵值，
     * 必须显示为「精度未知」而不是一个天文数字。
     */
    fun formatAccuracy(accuracyMeters: Float): String = when {
        accuracyMeters.isNaN() -> "精度未知"
        accuracyMeters >= Float.MAX_VALUE -> "精度未知"
        accuracyMeters < 1f -> "±<1m"
        else -> "±${Math.round(accuracyMeters)}m"
    }

    fun accuracyTier(accuracyMeters: Float): AccuracyTier = when {
        accuracyMeters.isNaN() || accuracyMeters >= Float.MAX_VALUE -> AccuracyTier.UNKNOWN
        accuracyMeters <= 10f -> AccuracyTier.GOOD
        accuracyMeters <= 50f -> AccuracyTier.MEDIUM
        else -> AccuracyTier.POOR
    }

    /**
     * 海拔，缺失时返回 null 由调用方决定不显示。
     *
     * **数值取自 `Location.getAltitude()`，是 WGS84 椭球高，不是海拔高程。**
     * 两者在国内可差几十米（大地水准面差距），拿它跟地图上的海拔对不上是正常的。
     * 因此这里只呈现数字、不标注「海拔」以外的任何基准说明——
     * 写成「海拔高程」等于替平台许下一个它没做的承诺。
     */
    fun formatAltitude(altitude: Double?): String? =
        altitude?.let { String.format(Locale.US, "海拔 %.0fm", it) }

    /**
     * 带符号的一位小数，如 `-12.4` / `+8.7`。
     *
     * 磁场三分量在正负之间频繁穿越，符号必须**始终占位**：若负号只在为负时出现，
     * 数字左右边界会随读数跳动，整列 X/Y/Z 看起来在抖（design-gui.md §12.3 等宽原则）。
     */
    fun formatSigned(value: Float): String = String.format(Locale.US, "%+.1f", value)

    /** 磁场总强度的展示形式，如 `51.3 μT`。强度恒为正，不需要符号占位。 */
    fun formatMagnitude(magnitudeUt: Float): String =
        String.format(Locale.US, "%.1f μT", magnitudeUt)
}
