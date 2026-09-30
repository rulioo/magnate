package com.magnate.compass.location

// —— android.location.GnssStatus 的星座常量，取值与平台一致 ——
// 在此重新声明，理由同 SensorModels.kt 对 SensorManager 精度常量的处理：
// 让本文件与 GnssFormat 保持纯 Kotlin，可在 JVM 上直接单测。
// 这是必要的而非图省事——GnssStatus 的构造函数是包私有的，测试里根本造不出一个实例来。
const val CONSTELLATION_UNKNOWN = 0
const val CONSTELLATION_GPS = 1
const val CONSTELLATION_SBAS = 2
const val CONSTELLATION_GLONASS = 3
const val CONSTELLATION_QZSS = 4
const val CONSTELLATION_BEIDOU = 5
const val CONSTELLATION_GALILEO = 6

/** API 29 起才有这个值；API 24–28 的设备上不会出现。可以安全引用：它是编译期内联的常量。 */
const val CONSTELLATION_IRNSS = 7

/**
 * 卫星所属的导航系统。
 *
 * 枚举顺序即 [GnssFormat.sortForDisplay] 的次级排序依据，所以 [UNKNOWN] **必须留到最后**——
 * 否则认不出星座的卫星会插到已知星座中间，看起来像排序坏了。
 */
enum class Constellation(
    /** 单字母标记。GPS 是 G、北斗是 C（BeiDou 的 C），与业界惯用的 RINEX 前缀一致。 */
    val shortName: String,
    val displayName: String,
) {
    GPS("G", "GPS"),
    GLONASS("R", "GLONASS"),
    BEIDOU("C", "北斗"),
    GALILEO("E", "Galileo"),
    QZSS("J", "QZSS"),
    SBAS("S", "SBAS"),
    IRNSS("I", "NavIC"),
    UNKNOWN("?", "未知"),
}

fun constellationOf(constellationType: Int): Constellation = when (constellationType) {
    CONSTELLATION_GPS -> Constellation.GPS
    CONSTELLATION_SBAS -> Constellation.SBAS
    CONSTELLATION_GLONASS -> Constellation.GLONASS
    CONSTELLATION_QZSS -> Constellation.QZSS
    CONSTELLATION_BEIDOU -> Constellation.BEIDOU
    CONSTELLATION_GALILEO -> Constellation.GALILEO
    CONSTELLATION_IRNSS -> Constellation.IRNSS
    else -> Constellation.UNKNOWN
}

/**
 * 一颗卫星的一帧状态。
 *
 * 字段与 `GnssStatus` 的取值逐一对应，但它是纯数据类，可以在 JVM 测试里直接构造。
 */
data class SatelliteInfo(
    /** 该星座内的卫星编号。跨星座会重复，所以它单独不唯一。 */
    val svid: Int,
    val constellation: Constellation,
    /** 载噪比（dB-Hz）。信号强度的标准度量，见 [GnssFormat.cn0Tier]。 */
    val cn0DbHz: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    /** 是否参与了解算。**可见 ≠ 参与**，这个字段是二者的分界。 */
    val usedInFix: Boolean,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
) {
    /**
     * 列表复用的稳定键。
     *
     * 必须带星座：svid 只在星座内唯一，不同星座的 5 号星是两颗不同的卫星。
     * 只用 svid 会让 LazyColumn 把 GPS 5 号和北斗 5 号当成同一项复用，滚动时内容会串。
     */
    val key: String get() = "${constellation.name}-$svid"
}

/**
 * 卫星数据完全拿不到的三种成因。
 *
 * 刻意做成快照上的一个字段而不是抛异常：这三种情况**都要画在屏幕上**，
 * 让用户知道该去做什么。抛出异常再由界面 `catch` 回来，只是绕了一圈把同一个信息
 * 变成一团更难分辨的东西——而且很容易在某一层被顺手吞掉。
 */
enum class GnssBlock {
    /** 没有精确位置权限。API 29 起只有粗略权限时，`GnssStatus` 给的是一个空列表。 */
    PERMISSION,

    /** 系统定位服务（GPS 总开关）关闭。 */
    SERVICES,

    /** 本机没有定位服务，或注册卫星状态回调失败。 */
    UNAVAILABLE,
}

/** 卫星状态的一帧快照。 */
data class GnssSnapshot(
    val satellites: List<SatelliteInfo> = emptyList(),

    /** 非 null 时 [satellites] 必然为空，界面据此显示横幅而不是一张空列表。 */
    val blocked: GnssBlock? = null,

    /**
     * GNSS 引擎是否在运转。
     *
     * 注册状态回调**不会**启动引擎，引擎只在存在活跃定位请求时才转，
     * 所以「一颗星都没有」有好几种成因，这个字段负责把「没启动」和「启动了但在搜」分开。
     */
    val engineStarted: Boolean = false,

    /** 系统定位服务（GPS provider）是否启用。 */
    val gpsEnabled: Boolean = true,

    /** 从开始收听到首次定位解算成功的耗时。null 表示这次会话还没定上位。 */
    val firstFixElapsedMs: Long? = null,

    /** 这一帧的时间戳，用于判断数据是否还在更新。 */
    val updatedAt: Long = 0L,
) {
    /** 可见卫星数。 */
    val visibleCount: Int get() = satellites.size

    /** 参与定位解算的卫星数。总是 ≤ [visibleCount]。 */
    val usedInFixCount: Int get() = satellites.count { it.usedInFix }
}
