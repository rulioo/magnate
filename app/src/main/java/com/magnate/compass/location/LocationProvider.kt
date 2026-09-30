package com.magnate.compass.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.magnate.compass.util.LocationPermissionLevel
import com.magnate.compass.util.PermissionHelper
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

    /**
     * 当前权限档位。
     *
     * 由 provider 代答而不是让 ViewModel 自己查：判定需要 `Context`，
     * 而本项目的 ViewModel 一律不持有 Context——它们只拿 repository 与 provider。
     *
     * 逻辑本体只有 [PermissionHelper] 一份。此前这里有一份复制的
     * `LOCATION_PERMISSIONS` 与 `hasLocationPermission()`，两处独立演化迟早会分叉。
     */
    fun permissionLevel(): LocationPermissionLevel = PermissionHelper.levelOf(context)

    fun hasLocationPermission(): Boolean = PermissionHelper.hasLocationPermission(context)

    /** 定位服务总开关关闭时，直接跳过定位，不必等超时（design.md §10）。 */
    fun isAnyProviderEnabled(): Boolean {
        val m = manager ?: return false
        return enabledProviders(m).isNotEmpty()
    }

    private fun enabledProviders(m: LocationManager): List<String> = PROVIDER_PRIORITY.filter {
        runCatching { m.isProviderEnabled(it) }.getOrDefault(false)
    }

    /**
     * 主页可见期间开启低频更新，把坐标「预热」好。
     * 60s 间隔 + 50m 位移门槛，功耗与一次普通消息推送相当（design.md §5.7）。
     */
    @SuppressLint("MissingPermission")
    fun startTracking() {
        val m = manager ?: return
        if (tracking || !PermissionHelper.hasLocationPermission(context)) return

        val enabled = enabledProviders(m)
        if (enabled.isEmpty()) return

        // 先用系统缓存里的最后位置垫一下，让主页立刻有坐标可显示。
        // 看**全部**已启用的 provider，而不是只看优先级最高的那个：
        // 只看 GPS 的话，「GPS 开着但还没定上位」时一个刚刷新的 NETWORK 缓存会被整个忽略，
        // 而那恰是室内最需要垫底的场景。哪些缓存不可信由 selectBestLastKnown 负责筛。
        val cached = enabled.mapNotNull { provider ->
            runCatching { m.getLastKnownLocation(provider) }.getOrNull()?.toGeoPoint()
        }
        selectBestLastKnown(cached, System.currentTimeMillis())?.let { _lastKnown.value = it }

        // 同理，注册也覆盖全部已启用 provider。只注册 GPS 时，室内「已启用但定不上位」
        // 的机器永远预热不出新坐标，而预热存在的全部意义就是这个场景。
        // 代价是多一路 60s/50m 的注册，且只在主页可见期间存在——
        // 这是对 design.md §9 的**有意修订**，不是疏漏。
        val registered = enabled.map { provider ->
            runCatching {
                m.requestLocationUpdates(
                    provider,
                    TRACK_INTERVAL_MS,
                    TRACK_MIN_DISTANCE_M,
                    trackingListener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
        }
        // 注意不能写成 enabled.any { ... }：any 会在第一个成功后短路，
        // 后面的 provider 就不会被注册了
        tracking = registered.any { it }
    }

    /** `onPause` 立即注销传感器与定位，做到零后台耗电（design.md §9）。 */
    @SuppressLint("MissingPermission")
    fun stopTracking() {
        if (!tracking) return
        runCatching { manager?.removeUpdates(trackingListener) }
        tracking = false
    }

    /**
     * 保存时调用：缓存够新直接返回，否则主动定位，超时返回原因。
     *
     * 失败是**正常路径**——保存不被定位阻塞（design.md §9 验收标准 8）。
     * 但失败**必须带上原因**：返回值原先是个 `GeoPoint?`，把没权限、服务关闭、超时
     * 三种情况压成同一个 `null`，于是界面只能一律写「请到窗边或室外再试」，
     * 而当时绝大多数失败的真实原因是应用从未申请过权限——用户照做永远也不会好。
     */
    @SuppressLint("MissingPermission")
    suspend fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS): LocationResult {
        if (!PermissionHelper.hasLocationPermission(context)) return LocationResult.NoPermission

        _lastKnown.value
            ?.takeIf { System.currentTimeMillis() - it.locatedAt < CACHE_TTL_MS }
            ?.let { return LocationResult.Success(it) }

        if (!isAnyProviderEnabled()) return LocationResult.ServicesDisabled

        return withTimeoutOrNull(timeoutMs) { raceProviders() }
            // 顺手记进缓存。这不只是省一次定位：主页那行「磁偏角需要坐标 · 去取坐标」
            // 靠的就是 [lastKnown]，不写回去的话用户点完按钮屏幕上什么都不会变。
            // 语义上也确实成立——一次成功的 acquire 就是最近一次定位。
            ?.also { _lastKnown.value = it }
            ?.let { LocationResult.Success(it) }
            ?: LocationResult.Timeout
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
