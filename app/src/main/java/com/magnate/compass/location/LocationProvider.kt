package com.magnate.compass.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 坐标获取：缓存优先 + 按需定位 + 超时降级（design.md §5.7）。
 *
 * **不使用 `FusedLocationProviderClient`**：国产 ROM 普遍不带 GMS，
 * 依赖它会让应用在主力机型上直接失去定位能力（design.md §3.1）。
 * 全程使用系统 `LocationManager`。
 */
class LocationProvider(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _lastKnown = MutableStateFlow<GeoPoint?>(null)

    /** 最近一次定位。主页可见期间由 [startTracking] 持续维护。 */
    val lastKnown: StateFlow<GeoPoint?> = _lastKnown.asStateFlow()

    private var tracking = false

    private val trackingListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _lastKnown.value = location.toGeoPoint()
        }

        @Deprecated("API 30 起废弃，为兼容 API 24–29 保留实现")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasLocationPermission(): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 定位服务总开关关闭时，直接跳过定位，不必等超时（design.md §10）。 */
    fun isAnyProviderEnabled(): Boolean {
        val m = manager ?: return false
        return PROVIDER_PRIORITY.any { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /**
     * 主页可见期间开启低频更新，把坐标「预热」好。
     * 60s 间隔 + 50m 位移门槛，功耗与一次普通消息推送相当（design.md §5.7）。
     */
    @SuppressLint("MissingPermission")
    fun startTracking() {
        val m = manager ?: return
        if (tracking || !hasLocationPermission()) return

        val provider = PROVIDER_PRIORITY.firstOrNull {
            runCatching { m.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return

        // 先用系统缓存里的最后位置垫一下，让主页立刻有坐标可显示。
        runCatching { m.getLastKnownLocation(provider) }
            .getOrNull()
            ?.let { _lastKnown.value = it.toGeoPoint() }

        runCatching {
            m.requestLocationUpdates(
                provider,
                TRACK_INTERVAL_MS,
                TRACK_MIN_DISTANCE_M,
                trackingListener,
                Looper.getMainLooper(),
            )
        }.onSuccess { tracking = true }
    }

    /** `onPause` 立即注销传感器与定位，做到零后台耗电（design.md §9）。 */
    @SuppressLint("MissingPermission")
    fun stopTracking() {
        if (!tracking) return
        runCatching { manager?.removeUpdates(trackingListener) }
        tracking = false
    }

    /**
     * 保存时调用：缓存够新直接返回，否则主动定位，超时返回 null。
     *
     * 返回 null 是**正常路径**——保存不被定位阻塞（design.md §9 验收标准 8）。
     */
    @SuppressLint("MissingPermission")
    suspend fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS): GeoPoint? {
        if (!hasLocationPermission()) return null

        _lastKnown.value
            ?.takeIf { System.currentTimeMillis() - it.locatedAt < CACHE_TTL_MS }
            ?.let { return it }

        if (!isAnyProviderEnabled()) return null

        return withTimeoutOrNull(timeoutMs) { raceProviders() }
    }

    /**
     * 并发请求 GPS 与 NETWORK，取先返回且精度更好的一个。
     *
     * 若先返回的那个已经「足够好」就不再等另一个——否则网络定位会拖满整个超时预算。
     */
    @SuppressLint("MissingPermission")
    private suspend fun raceProviders(): GeoPoint? = coroutineScope {
        val m = manager ?: return@coroutineScope null
        val enabled = PROVIDER_PRIORITY.filter {
            runCatching { m.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (enabled.isEmpty()) return@coroutineScope null
        if (enabled.size == 1) return@coroutineScope requestCurrent(enabled.first())

        val results = Channel<GeoPoint?>(Channel.UNLIMITED)
        val jobs = enabled.map { provider ->
            launch { results.send(requestCurrent(provider)) }
        }

        var best: GeoPoint? = null
        try {
            var received = 0
            while (received < enabled.size) {
                val candidate = results.receive()
                received++
                if (candidate == null) continue
                val current = best
                if (current == null || candidate.accuracyMeters < current.accuracyMeters) {
                    best = candidate
                }
                if (best!!.accuracyMeters <= GOOD_ENOUGH_ACCURACY_M) break
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
        best
    }

    /** API 30 前后两套路径（design.md §5.7）。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(provider: String): GeoPoint? =
        suspendCancellableCoroutine { cont ->
            val m = manager
            if (m == null || !runCatching { m.isProviderEnabled(provider) }.getOrDefault(false)) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    m.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (cont.isActive) cont.resume(location?.toGeoPoint())
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location.toGeoPoint())
                    }

                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }

                    @Deprecated("API 30 起废弃，为兼容 API 24–29 保留实现")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit
                }
                cont.invokeOnCancellation { runCatching { m.removeUpdates(listener) } }
                try {
                    @Suppress("DEPRECATION")
                    m.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    companion object {
        /** Android 12+ 要求 COARSE 与 FINE 同时申请（design.md §7） */
        val LOCATION_PERMISSIONS = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** GPS 室外精度高，优先；NETWORK 室内可用但精度低，作兜底。 */
        private val PROVIDER_PRIORITY = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        private const val TRACK_INTERVAL_MS = 60_000L
        private const val TRACK_MIN_DISTANCE_M = 50f

        /** 缓存有效期：超过这个时长的缓存不再算「够新」 */
        private const val CACHE_TTL_MS = 120_000L

        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** FOLLOW 场景有场景坐标兜底，不必久等（design.md §5.7 超时预算表） */
        const val FOLLOW_SCENE_TIMEOUT_MS = 3_000L

        /** 已经好到不必再等另一个 provider 的精度阈值 */
        private const val GOOD_ENOUGH_ACCURACY_M = 15f
    }
}

private fun Location.toGeoPoint(): GeoPoint = GeoPoint(
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    provider = provider ?: "unknown",
    locatedAt = time,
)
