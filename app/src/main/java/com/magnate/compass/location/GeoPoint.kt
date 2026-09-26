package com.magnate.compass.location

/**
 * 一次定位结果。
 *
 * [accuracyMeters] 是必填项（对应 `Location.getAccuracy()`，95% 置信半径）——
 * ±5m 和 ±2000m 的坐标价值完全不同，只存经纬度不存精度是有害的设计（design.md §3.2）。
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
    val provider: String,
    val locatedAt: Long,
)
