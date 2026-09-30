package com.magnate.compass.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.magnate.compass.util.PermissionHelper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 卫星信号的唯一入口（design-gui.md §18）。
 *
 * 所有 Android 的 GNSS API 都关在这个文件里，外面的 [GnssSnapshot] / [GnssFormat]
 * 是纯数据与纯函数，因此卫星页里真正会做决定的那部分能在 JVM 上单测。
 *
 * ## 一条决定实现形态的平台事实
 *
 * **注册 `GnssStatus.Callback` 不会启动 GNSS 引擎。** 引擎只在存在活跃定位请求时运转，
 * `onSatelliteStatusChanged` 也只在那时才回调。所以这里必须自己持有一个保活请求，
 * 否则屏幕上永远是「尚未搜到卫星」——而那看起来和设备坏了没有任何区别。
 *
 * 保活请求是**这个流自己的**，不复用 `LocationProvider.startTracking()`：
 * 那边的 `tracking` 是普通布尔量而不是引用计数（刻意的，见其注释），
 * 两个拥有者共享一个布尔量时，后注销的那个会把另一个留在没注册的状态。
 * 同一应用对同一 provider 的两次 `requestLocationUpdates` 本来就是互相独立的注册，
 * 引擎按请求的并集运转，各管各的注销才是对的。
 *
 * 冷流（`callbackFlow`）而不是热流 + start/stop 对：卫星数据只在可见时有意义，
 * 因此「可见才注册」是结构性的保证，而不是一条需要记得遵守的纪律（design.md §9）。
 */
class GnssProvider(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * GNSS 硬件年份（`LocationManager.getGnssYearOfHardware`）。
     *
     * 两处平台细节：它在 **API 28** 才有，且在 `LocationManager` 上而非 `GnssStatus` 上。
     * 很多设备干脆返回 0，此时返回 null 让界面整行不渲染——显示「GNSS 硬件 0 年」比不显示更糟。
     */
    fun hardwareYear(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val year = runCatching { manager?.gnssYearOfHardware }.getOrNull() ?: return null
        return year.takeIf { it > 0 }
    }

    /**
     * 卫星状态流。每个订阅者独立注册/注销，配合 `WhileSubscribed(0)` 使用，
     * 离开页面即刻停掉保活请求，零后台耗电（design.md §9）。
     */
    @SuppressLint("MissingPermission")
    fun satelliteStream(): Flow<GnssSnapshot> = callbackFlow {
        val m = manager
        if (m == null) {
            trySend(GnssSnapshot(blocked = GnssBlock.UNAVAILABLE, gpsEnabled = false))
            awaitClose { }
            return@callbackFlow
        }

        // API 29 起 GnssStatus 需要 ACCESS_FINE_LOCATION 才有有意义的数据；
        // 只有粗略权限时得到的是一个**空列表**，和「搜不到星」长得一模一样。
        // 与其让用户以为设备有问题，不如直接说清是权限。
        if (!PermissionHelper.hasFineLocationPermission(context)) {
            trySend(GnssSnapshot(blocked = GnssBlock.PERMISSION))
            awaitClose { }
            return@callbackFlow
        }

        val gpsEnabled = runCatching { m.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        if (!gpsEnabled) {
            trySend(GnssSnapshot(blocked = GnssBlock.SERVICES, gpsEnabled = false))
            awaitClose { }
            return@callbackFlow
        }

        val startedAt = System.currentTimeMillis()

        // 保活请求。回调全部留空——这个 listener 存在的唯一目的就是让引擎转起来，
        // 数据一律从 GnssStatus 走。
        val keepAlive = object : LocationListener {
            override fun onLocationChanged(location: Location) = Unit

            @Deprecated("API 30 起废弃，为兼容 API 24–29 保留实现")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        val keepAliveOk = runCatching {
            m.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                keepAlive,
                Looper.getMainLooper(),
            )
        }.isSuccess

        var firstFixElapsedMs: Long? = null

        // 注意只取 getCn0DbHz，不混用 API 30+ 的 getBasebandCn0DbHz：
        // 两者是有系统性偏移的不同测量值，按 API 等级混用会让同一个分档阈值
        // 在不同手机上含义不同——比数字「更好看」糟得多。
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val satellites = ArrayList<SatelliteInfo>(status.satelliteCount)
                for (i in 0 until status.satelliteCount) {
                    satellites += SatelliteInfo(
                        svid = status.getSvid(i),
                        constellation = constellationOf(status.getConstellationType(i)),
                        cn0DbHz = status.getCn0DbHz(i),
                        elevationDeg = status.getElevationDegrees(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                        // 是 hasAlmanacData / hasEphemerisData，不是 hasAlmanac / hasEphemeris
                        // （后者是旧的 GpsSatellite 上的名字）
                        hasAlmanac = status.hasAlmanacData(i),
                        hasEphemeris = status.hasEphemerisData(i),
                    )
                }
                // 首次定位耗时按**用户视角**算：从打开这个页面到看见第一颗参与解算的星。
                // 引擎若在别处已经跑了一阵，系统的 onFirstFix 会报一个用户没经历过的数，
                // 那个数看着精确，回答的却是另一个问题。
                if (firstFixElapsedMs == null && satellites.any { it.usedInFix }) {
                    firstFixElapsedMs = System.currentTimeMillis() - startedAt
                }
                trySend(
                    GnssSnapshot(
                        satellites = satellites,
                        engineStarted = true,
                        gpsEnabled = true,
                        firstFixElapsedMs = firstFixElapsedMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }

            override fun onStopped() {
                trySend(
                    GnssSnapshot(
                        engineStarted = false,
                        gpsEnabled = true,
                        firstFixElapsedMs = firstFixElapsedMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }

        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                m.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), callback)
            }.getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                m.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            }.getOrDefault(false)
        }

        if (!registered) {
            if (keepAliveOk) runCatching { m.removeUpdates(keepAlive) }
            trySend(GnssSnapshot(blocked = GnssBlock.UNAVAILABLE, gpsEnabled = true))
            awaitClose { }
            return@callbackFlow
        }

        // 先给一帧，否则在第一次 onSatelliteStatusChanged 到达之前（冷启动可能要好几秒），
        // 屏幕上是一片空白，用户无法区分「在搜」和「页面坏了」。
        // 此时保活请求已发出，引擎正在被启动，报 engineStarted = true 是如实的。
        trySend(
            GnssSnapshot(
                engineStarted = keepAliveOk,
                gpsEnabled = true,
                updatedAt = startedAt,
            )
        )

        awaitClose {
            runCatching { m.unregisterGnssStatusCallback(callback) }
            runCatching { m.removeUpdates(keepAlive) }
        }
    }
}
