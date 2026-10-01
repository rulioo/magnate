package com.magnate.compass.sensor

import kotlin.math.sqrt

/**
 * 未校准磁力计给出的**硬磁偏置估计**（μT），即 `TYPE_MAGNETIC_FIELD_UNCALIBRATED`
 * 的 `values[3..5]`。
 *
 * **它不是磁场，是「系统从磁场里减掉了多少」。** 校准后的 `TYPE_MAGNETIC_FIELD`
 * 等于未校准值减去这个偏置，所以拿它去判「附近有没有铁」是错的（那个问题要问校准后的强度），
 * 它唯一能回答的是「这次校准本身把多少东西当成了固定偏差」——
 * 偏置越大，说明手机周围有一块跟着手机一起转的磁铁，而那样的东西只能靠取下来解决。
 *
 * 做成不可变值类型而不是沿用 `FloatArray`：`SensorEvent.values` 是系统复用的数组，
 * 传引用出去迟早会有人忘掉拷贝（design.md §2.2）。
 */
data class MagneticBias(val x: Float, val y: Float, val z: Float) {
    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)
}

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
    /** 硬磁偏置估计；设备没有未校准磁力计时为 null。见 [MagneticBias] */
    val bias: MagneticBias? = null,
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
