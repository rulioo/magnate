package com.magnate.compass.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 传感器注册/注销与降级链的唯一入口。
 *
 * 磁场**始终**直接读 `TYPE_MAGNETIC_FIELD` 原始值——旋转矢量经过融合与归一化，
 * 丢失了真实场强量级，不能用于强度测量（design.md §2.2）。
 *
 * 另外**尽力**注册 `TYPE_MAGNETIC_FIELD_UNCALIBRATED`（API 18，minSdk 24 起恒可用）：
 * 它的 `values[3..5]` 是系统估计的硬磁偏置，是「校准够不够」唯一的判据来源。
 * 它是**可选**的——设备不提供时整条链路照常工作，只是诊断少一项（[CalibrationMath]）。
 */
class SensorRepository(private val context: Context) {

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)

    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    /** 无磁力计设备上，记录与场景功能必须仍然可用（design.md §10）。 */
    fun hasMagnetometer(): Boolean =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

    /**
     * 按 A → B → C 选择可用的方位角来源，全部不可用时返回 null。
     */
    fun resolveSource(): SensorSource? {
        val manager = sensorManager ?: return null
        if (manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) == null) return null
        return when {
            manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ->
                SensorSource.ROTATION_VECTOR

            manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null ->
                SensorSource.GEOMAGNETIC_ROTATION_VECTOR

            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ->
                SensorSource.ACCEL_MAG

            else -> null
        }
    }

    /**
     * 冷流：每个订阅者独立注册/注销传感器。
     * 配合 `repeatOnLifecycle` 使用，`onPause` 时自动注销，做到零后台耗电（design.md §9）。
     */
    fun readings(): Flow<RawSensorData> = callbackFlow {
        val manager = sensorManager
        val source = resolveSource()
        if (manager == null || source == null) {
            close(SensorUnavailableException())
            return@callbackFlow
        }

        val magSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!!
        val orientationSensor = when (source) {
            SensorSource.ROTATION_VECTOR ->
                manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            SensorSource.GEOMAGNETIC_ROTATION_VECTOR ->
                manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

            SensorSource.ACCEL_MAG ->
                manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        if (orientationSensor == null) {
            close(SensorUnavailableException())
            return@callbackFlow
        }

        // SensorEvent.values 的数组会被系统复用，必须拷贝后再留存。
        var lastOrientationValues: FloatArray? = null
        var lastAccelValues: FloatArray? = null
        var lastMagValues: FloatArray? = null
        var lastMagBias: MagneticBias? = null
        var lastMagAccuracy = SENSOR_STATUS_UNRELIABLE

        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        fun emitIfReady() {
            val mag = lastMagValues ?: return

            val matrix = when (source) {
                SensorSource.ACCEL_MAG -> {
                    val accel = lastAccelValues ?: return
                    if (!SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)) return
                    rotationMatrix
                }

                else -> {
                    val rv = lastOrientationValues ?: return
                    // 与 getRotationMatrix 不同，这个重载返回 void：它内部对四元数做归一化，
                    // 没有「失败」这个结果，因此不判返回值
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, rv)
                    rotationMatrix
                }
            }

            val remap = CompassMath.axisRemapFor(currentRotation())
            if (!SensorManager.remapCoordinateSystem(
                    matrix, remap.x, remap.y, remappedMatrix,
                )
            ) {
                return
            }
            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            trySend(
                RawSensorData(
                    azimuthRad = orientationAngles[0],
                    pitchRad = orientationAngles[1],
                    rollRad = orientationAngles[2],
                    magX = mag[0],
                    magY = mag[1],
                    magZ = mag[2],
                    accuracy = lastMagAccuracy,
                    timestampNanos = System.nanoTime(),
                    bias = lastMagBias,
                )
            )
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                    -> lastOrientationValues = event.values.copyOf()

                    Sensor.TYPE_ACCELEROMETER -> lastAccelValues = event.values.copyOf()

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lastMagValues = event.values.copyOf()
                        // **赋值，不是「取较大者」。**
                        // 这里原先是 `if (event.accuracy > lastMagAccuracy)`，那维护的是
                        // 「历史最好精度」：一旦升到 HIGH 就再也降不下来，
                        // 于是把手机贴到磁铁、钢门、电梯上，徽标仍然是绿的——
                        // 而「磁场异常」这个提示存在的全部意义就是那一刻。
                        // 精度是**状态**不是**成就**，两个写者（这里与 onAccuracyChanged）
                        // 必须同义，否则这个字段既不是「当前」也不是「最好」，
                        // 而是两者的混合，谁也说不清它代表什么。
                        lastMagAccuracy = event.accuracy
                    }

                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                        // values[0..2] 是未校准的磁场本身，values[3..5] 才是硬磁偏置估计。
                        // 长度检查不是多余的：这是厂商 HAL 分叉最厉害的一处，
                        // 而越界会让整个主页崩掉——诊断信息不值得拿主流程去换。
                        if (event.values.size >= 6) {
                            lastMagBias = MagneticBias(
                                x = event.values[3],
                                y = event.values[4],
                                z = event.values[5],
                            )
                        }
                    }
                }
                emitIfReady()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // 磁力计精度决定「建议校准 / 请做 8 字校准」提示，只有它需要跟踪。
                // 未校准磁力计的精度不参与判断：校准质量由偏置占比给出，那是另一个问题。
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) lastMagAccuracy = accuracy
            }
        }

        // 未校准磁力计是**可选**的：没有它只是诊断少一项，罗盘照常工作。
        // 因此它是 `null || 注册成功` 里的一项，而不是让整条链路失败。
        val uncalibratedSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

        val handler = Handler(Looper.getMainLooper())
        val registered = manager.registerListener(
            listener,
            magSensor,
            SensorManager.SENSOR_DELAY_GAME,
            handler,
        ) && manager.registerListener(
            listener,
            orientationSensor,
            SensorManager.SENSOR_DELAY_GAME,
            handler,
        ) && (uncalibratedSensor == null || manager.registerListener(
            listener,
            uncalibratedSensor,
            SensorManager.SENSOR_DELAY_GAME,
            handler,
        ))

        if (!registered) {
            manager.unregisterListener(listener)
            close(SensorUnavailableException())
            return@callbackFlow
        }

        awaitClose { manager.unregisterListener(listener) }
    }

    /**
     * 屏幕旋转必须实时读取，而不是注册时读一次——
     * 用户平举手机时系统随时可能转到横屏（design.md §5.8a）。
     *
     * 走 `DisplayManager` 而非 `Context.getDisplay()`，因为后者要求 Context 关联到窗口，
     * 而这里持有的是 Application Context。
     */
    private fun currentRotation(): Int =
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: CompassMath.ROTATION_0
}
