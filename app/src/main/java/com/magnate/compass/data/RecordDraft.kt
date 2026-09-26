package com.magnate.compass.data

import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.location.GeoPoint

/**
 * 点击「保存当前读数」那一瞬间的**不可变快照**。
 *
 * 用户填备注、选标签期间传感器仍在刷新；若存「点确定那一刻」的值，
 * 与用户点保存时看到的可能不同。因此快照在**点击瞬间冻结**，
 * 同时 `SaveRecordSheet` 内停止订阅实时状态——避免用户看到对话框里的数字在跳，
 * 从而怀疑自己到底存的哪个值（design-gui.md §5.5）。
 *
 * 保存的是**滤波后**的值：它才是用户在屏幕上看到并确认的那个数
 * （design.md §5.8d）。
 */
data class RecordDraft(
    /** 测量时刻 */
    val timestamp: Long,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val magX: Float,
    val magY: Float,
    val magZ: Float,
    val magnitude: Float,
    val sensorAccuracy: Int,
    val isTrueNorth: Boolean,
    val declination: Float?,
)

/** 保存一条记录的完整请求。 */
data class SaveRecordRequest(
    val draft: RecordDraft,

    /** 归属场景，在 Sheet 内可改（用户能在保存的最后一刻改变归属）。 */
    val sceneId: Long?,

    val note: String?,

    val tagNames: List<String>,

    /**
     * 本机实测坐标；FIXED 场景下为 null（压根没定位）。
     * 最终是否落库由 [ownLocationToPersist] 决定。
     */
    val measuredLocation: GeoPoint?,
)

/** 保存结果，供 Snackbar 按坐标状态选择文案。 */
data class SaveOutcome(
    val recordId: Long,
    val effectiveLocation: EffectiveLocation,
)

/** 删除记录前的完整快照，用于 Snackbar 撤销。 */
data class RecordSnapshot(
    val record: com.magnate.compass.data.entity.RecordEntity,
    val tagIds: List<Long>,
)

/** 新建/编辑场景的表单数据。 */
data class SceneDraft(
    val name: String,
    val note: String?,
    val coordMode: CoordMode,
    /** 场景坐标。**允许为空**——坐标获取失败不阻塞创建，之后可补录。 */
    val point: GeoPoint?,
)

/** 场景坐标的变更记录，用于 5 秒撤销。 */
data class SceneLocationChange(
    val sceneId: Long,
    val sceneName: String,
    val affectedRecordCount: Int,
    val previousLocation: SceneLocationSnapshot?,
)

data class SceneLocationSnapshot(
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val locationAccuracy: Float?,
    val locationProvider: String?,
    val locatedAt: Long?,
)
