package com.magnate.compass.data

import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.GeoPoint

/**
 * 有效坐标：解析后的坐标**及其来源**。来源必须可追溯，绝不静默混同。
 *
 * 一个坐标是「我在这个点实测的」还是「我从场景继承的」，是语义上完全不同的两件事：
 * 前者说明测量点位于 ±8m 内，后者说明测量点位于该场景范围内的某处——可能相隔几十米。
 * 把它们压平成一个 lat/lng 字段，用户就再也无法区分。
 *
 * **来源信息一旦丢失就无法重建**，所以从模型层就要保住它。
 */
sealed interface EffectiveLocation {

    /** 本机在测量时刻实测 */
    data class Measured(val point: GeoPoint) : EffectiveLocation

    /** 继承自所属场景 */
    data class FromScene(
        val sceneId: Long,
        val sceneName: String,
        val point: GeoPoint,
    ) : EffectiveLocation

    /** 该记录关联了场景，但场景自身也没有坐标 */
    data class SceneWithoutCoordinate(
        val sceneId: Long,
        val sceneName: String,
    ) : EffectiveLocation

    /** 完全无坐标 */
    data object None : EffectiveLocation

    /** 解析出的地理坐标，没有则为 null。 */
    val pointOrNull: GeoPoint?
        get() = when (this) {
            is Measured -> point
            is FromScene -> point
            else -> null
        }
}

/**
 * 全应用坐标语义的**唯一权威实现**——UI、导出、未来的地图视图都必须调用它。
 *
 * 纯函数，无副作用，可 JVM 单测。
 *
 * @param record 记录。`latitude`/`longitude` 是记录**自身实测**的坐标，不是解析结果。
 * @param scene  记录归属的场景，无归属时为 null。
 */
fun resolveLocation(record: RecordEntity, scene: SceneEntity?): EffectiveLocation {
    // 1. 记录自身实测坐标（FOLLOW 模式，或无场景时的定位结果）
    if (record.latitude != null && record.longitude != null) {
        return EffectiveLocation.Measured(
            GeoPoint(
                latitude = record.latitude,
                longitude = record.longitude,
                altitude = record.altitude,
                // 缺失精度视为**极差**而非 0。0 会被「精度优于 N 米」的筛选误判为精度极高。
                accuracyMeters = record.locationAccuracy ?: Float.MAX_VALUE,
                provider = record.locationProvider ?: "unknown",
                locatedAt = record.locatedAt ?: record.timestamp,
            )
        )
    }

    // 2. 回退到场景坐标
    if (scene != null) {
        if (scene.latitude != null && scene.longitude != null) {
            return EffectiveLocation.FromScene(
                sceneId = scene.id,
                sceneName = scene.name,
                point = GeoPoint(
                    latitude = scene.latitude,
                    longitude = scene.longitude,
                    altitude = scene.altitude,
                    accuracyMeters = scene.locationAccuracy ?: Float.MAX_VALUE,
                    provider = scene.locationProvider ?: "unknown",
                    locatedAt = scene.locatedAt ?: scene.createdAt,
                ),
            )
        }
        return EffectiveLocation.SceneWithoutCoordinate(scene.id, scene.name)
    }

    // 3. 无坐标
    return EffectiveLocation.None
}

/**
 * 保存时该往记录里写哪个坐标。
 *
 * FIXED 模式且场景有坐标时**返回 null**——记录不存自己的坐标，读取时从场景解析（引用语义）。
 * 这样修改场景坐标才能同步影响该场景下所有历史记录，也正是用户要的
 * 「这些测量值都属于这个场景的坐标」。
 */
fun ownLocationToPersist(scene: SceneEntity?, measured: GeoPoint?): GeoPoint? = when {
    scene != null && scene.coordMode == CoordMode.FIXED && scene.hasCoordinate -> null
    else -> measured
}

/**
 * 主页场景条与保存面板要显示的「这条记录将会落在哪里」。
 *
 * **实现方式是构造一条假记录再走 [resolveLocation]**，而不是另写一套 if-else。
 * 预览与实际写入必须由同一段逻辑推导——否则「预览显示场景坐标、存下来却是本机坐标」
 * 这类偏差会悄无声息地存在，而它恰恰是最难在事后发现的一类 bug。
 *
 * FIXED 场景下**忽略** [measured]：该模式压根不定位，预览也不该显示一个不会落库的坐标。
 */
fun previewLocation(scene: SceneEntity?, measured: GeoPoint?): EffectiveLocation {
    val persisted = ownLocationToPersist(scene, measured)
    return resolveLocation(
        RecordEntity(
            timestamp = 0L,
            createdAt = 0L,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            magX = 0f,
            magY = 0f,
            magZ = 0f,
            magnitude = 0f,
            sensorAccuracy = 0,
            isTrueNorth = false,
            declination = null,
            sceneId = scene?.id,
            latitude = persisted?.latitude,
            longitude = persisted?.longitude,
            altitude = persisted?.altitude,
            locationAccuracy = persisted?.accuracyMeters,
            locationProvider = persisted?.provider,
            locatedAt = persisted?.locatedAt,
            note = null,
        ),
        scene,
    )
}

/**
 * 保存时是否需要主动定位，以及给多少超时预算（design.md §5.7 超时预算表 + §5.3 归属场景区）。
 *
 * | 情形 | 超时 |
 * | --- | --- |
 * | FIXED 场景**且有坐标** | 0（不定位，坐标来自场景） |
 * | FOLLOW 场景且有坐标 | 3s（有场景坐标兜底，不必久等） |
 * | 无场景 / 场景无坐标 | 10s（没有兜底，多给一点时间） |
 */
sealed interface LocationPolicy {
    /** 不定位，保存瞬时完成 */
    data object Skip : LocationPolicy

    /** 主动定位，超时后按降级路径继续保存 */
    data class Acquire(val timeoutMs: Long) : LocationPolicy
}

fun locationPolicyFor(scene: SceneEntity?, noSceneTimeoutMs: Long, followSceneTimeoutMs: Long): LocationPolicy =
    when {
        scene == null -> LocationPolicy.Acquire(noSceneTimeoutMs)
        !scene.hasCoordinate -> LocationPolicy.Acquire(noSceneTimeoutMs)
        scene.coordMode == CoordMode.FIXED -> LocationPolicy.Skip
        else -> LocationPolicy.Acquire(followSceneTimeoutMs)
    }
