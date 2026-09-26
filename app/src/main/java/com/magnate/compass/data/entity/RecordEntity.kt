package com.magnate.compass.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条测量记录。
 *
 * **测量值不可变**：创建后不提供编辑方位角/磁场/时间的接口。它们是观测事实，改了就失去意义。
 * 可变的只有备注、标签、归属场景（仅移出）与坐标（补录）。
 * 这条约束同时简化了实现——无需并发控制、无需审计日志、`magnitude` 冗余字段永不失效。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("timestamp"),
        Index("magnitude"),
        Index("createdAt"),
        Index("sceneId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
            // 关键：删场景不删记录。记录是用户的核心资产。
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // —— 时间 ——
    /** 测量时刻（epoch millis，UTC 存储，本地时区显示） */
    val timestamp: Long,
    /** 入库时刻 */
    val createdAt: Long,

    // —— 测量值（不可变）——
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val magX: Float,
    val magY: Float,
    val magZ: Float,
    /** 由 xyz 派生，但 SQL 排序与范围筛选需要真实列 */
    val magnitude: Float,
    val sensorAccuracy: Int,
    val isTrueNorth: Boolean,
    val declination: Float?,

    // —— 场景归属（R11）——
    val sceneId: Long?,

    // —— 记录自身的实测坐标（可空）——
    // FIXED 模式且场景有坐标时恒为 null（坐标从场景解析）
    // FOLLOW 模式或无场景时，定位成功则写入
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val locationAccuracy: Float?,
    val locationProvider: String?,
    val locatedAt: Long?,

    // —— 用户标注 ——
    val note: String?,
)
