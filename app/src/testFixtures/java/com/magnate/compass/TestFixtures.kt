package com.magnate.compass

import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.GeoPoint
import java.time.ZoneId

/** 测试固定时区。涉及「今天 / 昨天」的断言必须在固定时区下才稳定。 */
val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 2024-03-05 14:30:00 +08:00 */
const val NOW: Long = 1_709_620_200_000L

/**
 * 构造测试用记录。
 *
 * 所有字段都给默认值，测试只写它关心的那几项——坐标语义的用例组合很多，
 * 每个用例都写全 18 个字段会让真正要断言的东西淹没在样板里。
 */
fun record(
    id: Long = 1L,
    timestamp: Long = NOW,
    createdAt: Long = NOW,
    azimuthDeg: Float = 247f,
    magnitude: Float = 50f,
    sceneId: Long? = null,
    latitude: Double? = null,
    longitude: Double? = null,
    altitude: Double? = null,
    locationAccuracy: Float? = null,
    locationProvider: String? = null,
    locatedAt: Long? = null,
    note: String? = null,
): RecordEntity = RecordEntity(
    id = id,
    timestamp = timestamp,
    createdAt = createdAt,
    azimuthDeg = azimuthDeg,
    pitchDeg = 0f,
    rollDeg = 0f,
    magX = 20f,
    magY = 30f,
    magZ = 40f,
    magnitude = magnitude,
    sensorAccuracy = 3,
    isTrueNorth = false,
    declination = null,
    sceneId = sceneId,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    locationAccuracy = locationAccuracy,
    locationProvider = locationProvider,
    locatedAt = locatedAt,
    note = note,
)

fun scene(
    id: Long = 100L,
    name: String = "阳光大厦 B2层",
    latitude: Double? = null,
    longitude: Double? = null,
    altitude: Double? = null,
    locationAccuracy: Float? = null,
    locationProvider: String? = null,
    locatedAt: Long? = null,
    coordMode: CoordMode = CoordMode.FIXED,
    note: String? = null,
): SceneEntity = SceneEntity(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    locationAccuracy = locationAccuracy,
    locationProvider = locationProvider,
    locatedAt = locatedAt,
    coordMode = coordMode,
    note = note,
    createdAt = NOW,
    updatedAt = NOW,
)

/** 一个有坐标的场景。 */
fun sceneWithCoordinate(
    id: Long = 100L,
    name: String = "阳光大厦 B2层",
    latitude: Double = 30.123456,
    longitude: Double = 120.567890,
    altitude: Double? = null,
    accuracy: Float = 12f,
    coordMode: CoordMode = CoordMode.FIXED,
): SceneEntity = scene(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    locationAccuracy = accuracy,
    locationProvider = "gps",
    locatedAt = NOW,
    coordMode = coordMode,
)

fun point(
    latitude: Double = 30.111111,
    longitude: Double = 120.222222,
    // 默认 null：多数用例不关心海拔，而「拿不到海拔」也正是真机上的常见情况，
    // 让默认值落在这一侧，等于顺带把「缺失时不能崩、不能编造」变成默认被测状态
    altitude: Double? = null,
    accuracy: Float = 8f,
    provider: String = "gps",
    locatedAt: Long = NOW,
): GeoPoint = GeoPoint(
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    accuracyMeters = accuracy,
    provider = provider,
    locatedAt = locatedAt,
)
