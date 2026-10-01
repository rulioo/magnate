package com.magnate.compass.ui.compass

import android.hardware.GeomagneticField
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.data.RecordDraft
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.data.SaveRecordRequest
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SettingsStore
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TagWriteResult
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.data.previewLocation
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.location.LocationResult
import com.magnate.compass.sensor.AccuracyLevel
import com.magnate.compass.sensor.CalibrationCause
import com.magnate.compass.sensor.CalibrationMath
import com.magnate.compass.sensor.CalibrationMessages
import com.magnate.compass.sensor.CalibrationQuality
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.sensor.SensorRepository
import com.magnate.compass.sensor.SensorSource
import com.magnate.compass.sensor.accuracyLevelOf
import com.magnate.compass.util.LocationPermissionLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 主页状态。
 *
 * [azimuthDeg] 是**磁北**方位角；真北模式下由 [displayAzimuthDeg] 换算，
 * 原始值保持不变——保存时要把 `isTrueNorth` 与 `declination` 一并落库，
 * 只存换算后的值会让「这条记录到底以哪个北为基准」变得无法追溯（design.md §5.8e）。
 */
data class CompassUiState(
    val azimuthDeg: Float = 0f,
    val headingName: String = "北",
    val magnitudeUt: Float = 0f,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val accuracy: AccuracyLevel = AccuracyLevel.UNKNOWN,
    val source: SensorSource? = null,
    /** 校准质量的成因判断，见 [CalibrationMath]。默认 OK = 还没有读数，因此也没话说。 */
    val quality: CalibrationQuality = CalibrationQuality(),
    val trueNorth: Boolean = false,
    val declination: Float? = null,
    val activeScene: SceneEntity? = null,
    /** 当前若保存一条记录，它的坐标会是什么——含来源 */
    val locationPreview: EffectiveLocation = EffectiveLocation.None,
    /** 最近一次定位点，供保存时的缓存复用 */
    val lastKnownLocation: GeoPoint? = null,

    /**
     * 定位权限档位。
     *
     * 曾经是一个 `Boolean hasLocationPermission`，写了六处、**读了零处**——死状态。
     * 现在它有了唯一但真实的下游：`MetaRow` 据此区分「未开启定位」与「仅粗略定位」，
     * 而后者在 Android 12+ 上只给约 ±2km 的坐标，不说明白用户会以为测的是一个点。
     */
    val locationPermission: LocationPermissionLevel = LocationPermissionLevel.NONE,
    val sensorAvailable: Boolean = true,
) {
    /** 界面上该显示的方位角：真北模式下把磁偏角算进去。 */
    val displayAzimuthDeg: Float
        get() = if (trueNorth && declination != null) {
            CompassMath.trueAzimuth(azimuthDeg, declination)
        } else {
            azimuthDeg
        }

    val displayHeadingName: String
        get() = CompassMath.headingName(displayAzimuthDeg)

    /**
     * 合成倾角：屏幕法线离铅垂线的夹角，**0° 就是平放**，与屏幕朝上还是朝下无关。
     *
     * 放在 state 上而不是让 UI 各算各的：[tiltWarning] 的判据和水平仪气泡的几何
     * 必须出自同一个函数，否则又会回到「提示说很斜、读数显示很平」那种自相矛盾。
     */
    val tiltDeg: Float
        get() = CompassMath.tiltFromHorizontal(pitchDeg, rollDeg)

    /**
     * 手机太斜了，方位角噪声偏大。
     *
     * 判据是**离水平面多少度**，不是「离竖直多少度」。这里原先写的是
     * `abs(pitchDeg + 90f) > 30f`，注释还写着「平放时 pitch ≈ -90°」——
     * 而 `getOrientation` 的约定恰好相反：平放 pitch = 0，竖直握持才是 -90°。
     * 那个式子把两个状态整个对调了：平放时报警、竖持时安静，
     * 与屏幕上的文案「手机接近竖直，噪声偏大」正好拧着。
     *
     * 阈值仍取 30°（原作者的本意就是「离水平 30°」），只是改用合成倾角来量：
     * 单看 pitch 会把「俯仰 45° 且翻滚 45°」（实际 60°）误判成 45°。
     */
    val tiltWarning: Boolean
        get() = tiltDeg > CompassMath.TILT_WARNING_DEGREES

    /**
     * 罗盘读数是否处在「需要用户做点什么」的状态（design.md §5.9）。
     *
     * 判据从单纯的 `accuracy` 换成了 [quality]：精度只是四种成因之一，
     * 而「附近有磁铁」和「装了磁吸壳」都会让精度读数依然好看，
     * 却让指向实实在在是错的。`accuracy` 仍然是其中一条判据，
     * 只是它现在由 [CalibrationMath] 统一裁决，不再和别的信号各说各话。
     */
    val needsCalibration: Boolean
        get() = quality.needsAttention

    /** 主页顶部常驻提示条上的那句话；无事可报时为 null。 */
    val calibrationBanner: String?
        get() = CalibrationMessages.banner(quality.cause)

    /** 校准面板的标题与正文，按成因分支。 */
    val calibrationTitle: String
        get() = CalibrationMessages.title(quality.cause)

    val calibrationGuidance: String
        get() = CalibrationMessages.guidance(quality.cause)

    /** 来源降级说明；用旋转矢量时为 null。 */
    val calibrationSourceNote: String?
        get() = CalibrationMessages.sourceNote(source)
}

/**
 * 主页罗盘的 ViewModel。
 *
 * **传感器订阅由生命周期驱动**：屏幕可见才注册，`onPause` 立即注销，
 * 做到零后台耗电（design.md §9）。因此这里用 [startListening] / [stopListening]
 * 而不是构造时无条件订阅。
 */
class CompassViewModel(
    private val sensorRepository: SensorRepository,
    private val sceneRepository: SceneRepository,
    private val recordRepository: RecordRepository,
    private val tagRepository: TagRepository,
    private val locationProvider: LocationProvider,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _sensor = MutableStateFlow(SensorSnapshot())
    private val _hasPermission = MutableStateFlow(LocationPermissionLevel.NONE)
    private val _sensorAvailable = MutableStateFlow(true)

    private var sensorJob: Job? = null

    /**
     * 滤波状态。**必须跨帧保存**——低通滤波的输入是上一帧的输出，
     * 每帧都从 0 重新开始等于没有滤波（design.md §5.8d）。
     *
     * 只在 UI 可见期间存活；重新进入主页时从当前读数重新收敛，这是可接受的。
     */
    private var smoothedAzimuth: Float? = null
    private var smoothedMagnitude: Float? = null

    /**
     * 俯仰/翻滚的滤波状态。翻滚这一路是**连续值**（可能短暂超出 ±180），
     * 因为一旦把它归一化，±180° 附近的滤波就会在 -179° 与 179° 之间来回跳。
     * 归一化只在发布到 [CompassUiState] 的那一步做。
     */
    private var smoothedPitchDeg: Float? = null
    private var smoothedRollDeg: Float? = null

    /** 磁偏角按坐标缓存，避免每帧都构造 [GeomagneticField]（它内部要展开球谐级数）。 */
    private var declinationCache: Pair<GeoPoint, Float>? = null

    /**
     * 上一帧的校准成因，喂给 [CalibrationMath.assessCalibration] 做迟滞。
     *
     * 必须**跨帧保存**，与滤波状态同理：磁场是连续量，会一直在阈值上下漂，
     * 每一帧都当作首次判断的话，提示条会一闪一闪。
     */
    private var lastCalibrationCause: CalibrationCause? = null

    val uiState: StateFlow<CompassUiState> =
        combine(
            _sensor,
            sceneRepository.observeActiveScene(),
            locationProvider.lastKnown,
            settingsStore.trueNorthEnabled,
            combine(_hasPermission, _sensorAvailable) { permission, available ->
                permission to available
            },
        ) { sensor, scene, lastKnown, trueNorth, (permission, available) ->
            val declination = if (trueNorth) declinationFor(scene, lastKnown) else null
            CompassUiState(
                azimuthDeg = sensor.azimuthDeg,
                headingName = CompassMath.headingName(sensor.azimuthDeg),
                magnitudeUt = sensor.magnitudeUt,
                magX = sensor.magX,
                magY = sensor.magY,
                magZ = sensor.magZ,
                pitchDeg = sensor.pitchDeg,
                rollDeg = sensor.rollDeg,
                accuracy = sensor.accuracy,
                source = sensor.source,
                quality = sensor.quality,
                trueNorth = trueNorth,
                declination = declination,
                activeScene = scene,
                locationPreview = previewLocation(scene, lastKnown),
                lastKnownLocation = lastKnown,
                locationPermission = permission,
                sensorAvailable = available,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CompassUiState(
                sensorAvailable = sensorRepository.hasMagnetometer(),
                locationPermission = locationProvider.permissionLevel(),
            ),
        )

    init {
        _sensorAvailable.value = sensorRepository.hasMagnetometer()
        _hasPermission.value = locationProvider.permissionLevel()
    }

    /** 场景选择器的数据源。按 `updatedAt` 降序，最近使用的在最上。 */
    val scenes: StateFlow<List<SceneWithCount>> =
        sceneRepository.observeScenes().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 状态栏右上角的记录总数。 */
    val recordCount: StateFlow<Int> =
        recordRepository.observeCount().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    // ————————————————————— 生命周期 —————————————————————

    /** 主页可见时调用。重复调用是幂等的。 */
    fun startListening() {
        if (sensorJob != null) return

        _hasPermission.value = locationProvider.permissionLevel()
        locationProvider.startTracking()

        sensorJob = viewModelScope.launch {
            sensorRepository.readings()
                .catch { _sensorAvailable.value = false }
                .collect { raw ->
                    val azimuthDeg = CompassMath.azimuthDegreesFrom(raw.azimuthRad)
                    val magnitude = CompassMath.magneticMagnitude(raw.magX, raw.magY, raw.magZ)

                    // 首帧直接采用，不做滤波——从 0° 收敛到真实方位要好几秒，
                    // 用户会看到一个缓慢爬升的错误读数。
                    val smoothedAz = smoothedAzimuth
                        ?.let { CompassMath.smoothAngle(it, azimuthDeg, CompassMath.AZIMUTH_ALPHA) }
                        ?: azimuthDeg
                    val smoothedMag = smoothedMagnitude
                        ?.let { CompassMath.smoothScalar(it, magnitude, CompassMath.MAGNITUDE_ALPHA) }
                        ?: magnitude

                    val rawPitch = CompassMath.pitchDegreesFrom(raw.pitchRad)
                    val rawRoll = CompassMath.rollDegreesFrom(raw.rollRad)

                    // 俯仰量程 [-90, 90]，不存在环绕，直接标量滤波。
                    val smoothedPitch = smoothedPitchDeg
                        ?.let { CompassMath.smoothScalar(it, rawPitch, CompassMath.TILT_ALPHA) }
                        ?: rawPitch

                    // 翻滚在 ±180° 处环绕，必须先把目标角展开到上一帧附近再滤波，
                    // 否则 179° → -179° 会被当成一次 358° 的跳变，读数猛甩一下。
                    //
                    // 这里只借用 unwrapNear，**不能换成 smoothAngle**：smoothAngle 内部走
                    // normalizeDegrees，返回值落在 [0, 360)，会把 -90° 变成 270°。
                    // 俯仰/翻滚是有符号量，显示与落库都按有符号处理。
                    val smoothedRoll = smoothedRollDeg
                        ?.let {
                            CompassMath.smoothScalar(
                                prev = it,
                                next = CompassMath.unwrapNear(it, rawRoll),
                                alpha = CompassMath.TILT_ALPHA,
                            )
                        }
                        ?: rawRoll

                    smoothedAzimuth = smoothedAz
                    smoothedMagnitude = smoothedMag
                    smoothedPitchDeg = smoothedPitch
                    smoothedRollDeg = smoothedRoll

                    // 判定用的是**滤波后**的强度，与屏幕上显示的是同一个数。
                    // 拿原始值判会导致「读数看着正常、提示条却说有干扰」，
                    // 这类自相矛盾在本项目里已经出现过一次（倾角提示），不能再犯。
                    val accuracy = accuracyLevelOf(raw.accuracy)
                    val quality = CalibrationMath.assessCalibration(
                        bias = raw.bias,
                        magnitudeUt = smoothedMag,
                        accuracy = accuracy,
                        previousCause = lastCalibrationCause,
                    )
                    lastCalibrationCause = quality.cause

                    _sensor.value = SensorSnapshot(
                        azimuthDeg = smoothedAz,
                        magnitudeUt = smoothedMag,
                        // 三分量不做滤波：它们是原始观测值，滤波会让「当前这一帧的场」失真，
                        // 而磁场卡片要的正是「此刻这里的场」。
                        magX = raw.magX,
                        magY = raw.magY,
                        magZ = raw.magZ,
                        // 发布前归一到 (-180, 180]：滤波状态要保持连续，但屏幕上的读数和
                        // freezeDraft 落库的值都应当是规范的带符号角度。
                        // 不这么做的话，手机接近倒置时详情页会出现 181.4° 这种数。
                        pitchDeg = CompassMath.normalizeSignedDegrees(smoothedPitch),
                        rollDeg = CompassMath.normalizeSignedDegrees(smoothedRoll),
                        accuracy = accuracy,
                        source = sensorRepository.resolveSource(),
                        quality = quality,
                    )
                }
        }
    }

    /** `onPause` 调用。传感器与定位一并注销，零后台耗电。 */
    fun stopListening() {
        sensorJob?.cancel()
        sensorJob = null
        locationProvider.stopTracking()
    }

    // ————————————————————— 校准 —————————————————————

    private val _calibrationMode = MutableStateFlow(false)

    /**
     * 是否处于校准模式。
     *
     * **不是一条独立路由，而是主页上的一种模式**——`design-gui.md:877` 已经写明
     * 「校准中 → 顶部提示条 + 表盘降为 50% 透明度」，表盘必须仍然可见。
     * 这也正是校准与「看卫星」的区别：卫星页是一个可以离开的地方，
     * 而校准要求用户一边画 8 字一边看着读数回升，那两件事必须同屏。
     *
     * 单独一个 StateFlow 而不并进 [uiState]：`uiState` 已经由五路 `combine` 构成，
     * 再加一路要换成数组重载并丢掉类型，代价大于收益，而这两份状态本来也不同源。
     */
    val calibrationMode: StateFlow<Boolean> = _calibrationMode.asStateFlow()

    fun startCalibration() {
        _calibrationMode.value = true
    }

    fun finishCalibration() {
        _calibrationMode.value = false
    }

    // ————————————————————— 交互 —————————————————————

    fun setTrueNorth(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setTrueNorthEnabled(enabled) }
    }

    fun enterScene(sceneId: Long) {
        viewModelScope.launch { sceneRepository.enterScene(sceneId) }
    }

    fun leaveScene() {
        viewModelScope.launch { sceneRepository.leaveScene() }
    }

    // ————————————————————— 保存 —————————————————————

    /**
     * 冻结当前读数为不可变快照。
     *
     * **保存滤波后的值**——它才是用户在屏幕上看到并确认的那个数。
     * 存一个用户没见过的原始值，会造成「存的和显示的不一致」的困惑（design.md §5.8d）。
     */
    fun freezeDraft(): RecordDraft {
        val state = uiState.value
        return RecordDraft(
            timestamp = System.currentTimeMillis(),
            azimuthDeg = state.azimuthDeg,
            pitchDeg = state.pitchDeg,
            rollDeg = state.rollDeg,
            magX = state.magX,
            magY = state.magY,
            magZ = state.magZ,
            magnitude = state.magnitudeUt,
            sensorAccuracy = state.accuracy.toSensorStatus(),
            isTrueNorth = state.trueNorth && state.declination != null,
            declination = state.declination,
        )
    }

    fun refreshPermission() {
        _hasPermission.value = locationProvider.permissionLevel()
    }

    /**
     * 授权成功后调用。
     *
     * 除了刷新状态，**还必须补一次 [LocationProvider.startTracking]**：
     * 授权之前它会在 `!hasLocationPermission()` 处提前返回，且 `tracking` 保持 `false`，
     * 于是主页的坐标预热从来没有真正启动过——用户在主页上永远等不到 `lastKnown` 出现。
     *
     * 不能改用 [startListening] 代替：它在 `sensorJob != null` 时提前返回，
     * 而走到这里时传感器早就开着，等于什么都没做。
     */
    fun onLocationPermissionGranted() {
        refreshPermission()
        locationProvider.startTracking()
    }

    // ————————————————————— 保存面板的数据 —————————————————————

    /**
     * 按预算主动定位。失败是**正常路径**——缓存够新就直接返回缓存，
     * 超时、无权限、定位服务关闭都不阻塞保存（design.md §5.7）。
     *
     * 但失败**带原因**返回，界面才能把话说到点子上：
     * 没权限就说没权限，而不是一律让用户去室外站着。
     */
    suspend fun acquireLocation(timeoutMs: Long): LocationResult =
        locationProvider.acquire(timeoutMs)

    /** 保存面板的 chip 流：最近使用的 8 个标签（design-gui.md §5.6）。 */
    fun observeRecentTags(): Flow<List<TagWithCount>> = tagRepository.observeRecentlyUsed()

    /** 面板内新建标签。重名时返回已有标签，直接选中它而不是报错。 */
    suspend fun createTag(name: String): TagWriteResult = tagRepository.createTag(name)

    /**
     * 落库。定位策略由 [com.magnate.compass.data.locationPolicyFor] 决定，
     * 但**定位失败不阻塞保存**——超时后按降级路径继续（design.md §9 验收标准 8）。
     */
    suspend fun saveRecord(request: SaveRecordRequest): SaveOutcome =
        recordRepository.save(request)

    // ————————————————————— 内部 —————————————————————

    /**
     * 磁偏角。
     *
     * 优先用场景坐标——**这正是场景机制的额外收益**：室内没有 GPS，
     * 但场景带着坐标，于是真北模式在室内依然可用（design.md §5.8e）。
     */
    private fun declinationFor(scene: SceneEntity?, lastKnown: GeoPoint?): Float? {
        val point = scene?.takeIf { it.hasCoordinate }?.let {
            GeoPoint(
                latitude = it.latitude!!,
                longitude = it.longitude!!,
                altitude = it.altitude,
                accuracyMeters = it.locationAccuracy ?: Float.MAX_VALUE,
                provider = it.locationProvider ?: "unknown",
                locatedAt = it.locatedAt ?: it.createdAt,
            )
        } ?: lastKnown ?: return null

        declinationCache?.let { (cached, value) ->
            // 坐标变动不到 1km 时磁偏角的变化远小于显示精度，无需重算
            if (abs(cached.latitude - point.latitude) < DECLINATION_EPSILON_DEG &&
                abs(cached.longitude - point.longitude) < DECLINATION_EPSILON_DEG
            ) {
                return value
            }
        }

        val field = GeomagneticField(
            point.latitude.toFloat(),
            point.longitude.toFloat(),
            point.altitude?.toFloat() ?: 0f,
            System.currentTimeMillis(),
        )
        return field.declination.also { declinationCache = point to it }
    }

    private data class SensorSnapshot(
        val azimuthDeg: Float = 0f,
        val magnitudeUt: Float = 0f,
        val magX: Float = 0f,
        val magY: Float = 0f,
        val magZ: Float = 0f,
        val pitchDeg: Float = 0f,
        val rollDeg: Float = 0f,
        val accuracy: AccuracyLevel = AccuracyLevel.UNKNOWN,
        val source: SensorSource? = null,
        val quality: CalibrationQuality = CalibrationQuality(),
    )

    private companion object {
        // 倾斜提示的阈值在 CompassMath.TILT_WARNING_DEGREES——它和水平仪气泡的
        // 「已水平」容差是同一族常量，放在纯函数文件里才能被单测覆盖，
        // 也才不会出现两处各写一个数、改了一处忘另一处。

        /** 约 1km。 */
        const val DECLINATION_EPSILON_DEG = 0.01
    }
}

/**
 * UI 的 [AccuracyLevel] 转回落库用的 `SensorEvent.accuracy` 原始值。
 *
 * 落库存原始整数而非枚举名：`SensorManager` 的取值是平台契约，
 * 未来若要细分精度等级，旧数据仍然可解释。
 */
private fun AccuracyLevel.toSensorStatus(): Int = when (this) {
    AccuracyLevel.HIGH -> com.magnate.compass.sensor.SENSOR_STATUS_ACCURACY_HIGH
    AccuracyLevel.MEDIUM -> com.magnate.compass.sensor.SENSOR_STATUS_ACCURACY_MEDIUM
    AccuracyLevel.LOW -> com.magnate.compass.sensor.SENSOR_STATUS_ACCURACY_LOW
    AccuracyLevel.UNRELIABLE -> com.magnate.compass.sensor.SENSOR_STATUS_UNRELIABLE
    AccuracyLevel.UNKNOWN -> com.magnate.compass.sensor.SENSOR_STATUS_UNRELIABLE
}
