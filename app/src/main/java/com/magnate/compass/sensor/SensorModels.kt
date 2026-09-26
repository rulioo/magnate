package com.magnate.compass.sensor

/**
 * 一帧原始传感器读数。
 *
 * 角度均为弧度（与 `SensorManager.getOrientation` 的输出一致），
 * 在 [CompassMath] 里统一转成角度制后再进入 UI —— 转换只发生一次，
 * 避免两套单位在代码里混流。
 */
data class RawSensorData(
    val azimuthRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
    val magX: Float,
    val magY: Float,
    val magZ: Float,
    /** `SensorEvent.accuracy`，见 [accuracyLevelOf] */
    val accuracy: Int,
    val timestampNanos: Long,
)

enum class AccuracyLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNRELIABLE,
    UNKNOWN,
    ;

    /** 低精度与不可信都需要引导用户做 8 字校准（design.md §5.9） */
    val needsCalibration: Boolean
        get() = this == LOW || this == UNRELIABLE
}

/** 方位角来源的降级链：A → B → C（design.md §2.1） */
enum class SensorSource {
    /** A. 旋转矢量，系统融合，主方案 */
    ROTATION_VECTOR,

    /** B. 地磁旋转矢量，不依赖陀螺仪 */
    GEOMAGNETIC_ROTATION_VECTOR,

    /** C. 加速度计 + 磁力计兜底 */
    ACCEL_MAG,
}

// —— SensorManager 的 accuracy 常量，取值与 android.hardware.SensorManager 一致 ——
// 在此重新声明，使本文件与 CompassMath 保持纯 Kotlin，可在 JVM 上直接单测。
const val SENSOR_STATUS_UNRELIABLE = 0
const val SENSOR_STATUS_ACCURACY_LOW = 1
const val SENSOR_STATUS_ACCURACY_MEDIUM = 2
const val SENSOR_STATUS_ACCURACY_HIGH = 3

fun accuracyLevelOf(sensorStatus: Int): AccuracyLevel = when (sensorStatus) {
    SENSOR_STATUS_ACCURACY_HIGH -> AccuracyLevel.HIGH
    SENSOR_STATUS_ACCURACY_MEDIUM -> AccuracyLevel.MEDIUM
    SENSOR_STATUS_ACCURACY_LOW -> AccuracyLevel.LOW
    SENSOR_STATUS_UNRELIABLE -> AccuracyLevel.UNRELIABLE
    else -> AccuracyLevel.UNKNOWN
}

/** 设备上没有可用的磁力计/方向传感器时抛出，UI 据此显示整页空状态。 */
class SensorUnavailableException : IllegalStateException("设备没有可用的磁力计")
