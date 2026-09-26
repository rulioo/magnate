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
                        if (event.accuracy > lastMagAccuracy) lastMagAccuracy = event.accuracy
                    }
                }
                emitIfReady()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // 磁力计精度决定「建议校准 / 请做 8 字校准」提示，只有它需要跟踪。
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) lastMagAccuracy = accuracy
            }
        }

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
        )

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
