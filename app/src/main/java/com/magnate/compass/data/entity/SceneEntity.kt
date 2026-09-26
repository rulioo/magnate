package com.magnate.compass.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 坐标模式（design.md §5.5）。
 *
 * 两种模式对应两种真实的采样方式，坐标语义完全不同——
 * 一个是**引用**（改场景坐标会同步改所有历史记录），一个是**快照**（记录写入后固定不变）。
 */
enum class CoordMode {
    /** 固定：场景内所有记录共用场景坐标。保存时不做定位，瞬时完成。 */
    FIXED,

    /** 跟随：每条记录尝试定位，成功用实测值，失败回退场景坐标。 */
    FOLLOW,
}

/**
 * 场景 = 空间容器，持有坐标（R11）。
 *
 * 当前模型是**一个场景 = 一个坐标**。楼层这类需求靠建多个场景表达
 * （「阳光大厦 B1层」「阳光大厦 B2层」），而不是场景内多坐标点。
 */
@Entity(
    tableName = "scenes",
    indices = [Index(value = ["name"], unique = true)],
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 唯一。重名时提供「查看该场景」——用户往往是想进入那个已有场景。 */
    val name: String,

    // —— 场景坐标。可空：允许先建场景后取坐标 ——
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationProvider: String? = null,
    val locatedAt: Long? = null,

    val coordMode: CoordMode = CoordMode.FIXED,

    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 场景是否已有坐标。无坐标的场景只是分组容器，不提供坐标系能力。 */
    val hasCoordinate: Boolean
        get() = latitude != null && longitude != null
}
