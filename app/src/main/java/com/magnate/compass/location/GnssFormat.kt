package com.magnate.compass.location

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 信号强度的分档，决定每颗卫星用什么颜色（design-gui.md §18.2）。
 *
 * 与 [com.magnate.compass.util.AccuracyTier] 同构，包括同一条铁律：
 * **缺失必须表现为最差**。`GnssStatus` 在拿不到测量值时会上报 0 或 NaN，
 * 若把它们当成「大于阈值」处理，一颗完全收不到的卫星会被画成信号满格——
 * 那比不显示更糟，因为它会让用户据此把设备故障误判成环境问题。
 */
enum class SignalTier {
    /** ≥ 40 dB-Hz */
    STRONG,

    /** ≥ 30 dB-Hz */
    GOOD,

    /** ≥ 20 dB-Hz。勉强可跟踪，通常还不足以参与解算。 */
    WEAK,

    /** < 20 dB-Hz，或压根没有上报。 */
    NONE,
}

/**
 * 卫星信号的格式化。纯函数，无 Android 依赖，可在 JVM 上直接单测。
 */
object GnssFormat {

    // C/N0 分档阈值（dB-Hz）。40 以上开阔天空的典型好值，
    // 30 是可靠参与解算的门槛，20 以下基本只够被「看见」。
    const val STRONG_DB_HZ = 40f
    const val GOOD_DB_HZ = 30f
    const val WEAK_DB_HZ = 20f

    /** 进度条的固定量程上限。见 [cn0BarFraction] 对「为什么不用自适应量程」的说明。 */
    const val BAR_RANGE_DB_HZ = 50f

    fun cn0Tier(cn0DbHz: Float): SignalTier = when {
        // 先排除非有限值与非正数。NaN 的所有比较都是 false，
        // 若不在这里拦下，下面的 >= 会全部落空而掉进最后的 else——
        // 那样倒也不算错，但把它显式写出来，语义才是「没有信号」而不是「碰巧没匹配上」
        !cn0DbHz.isFinite() || cn0DbHz <= 0f -> SignalTier.NONE
        cn0DbHz >= STRONG_DB_HZ -> SignalTier.STRONG
        cn0DbHz >= GOOD_DB_HZ -> SignalTier.GOOD
        cn0DbHz >= WEAK_DB_HZ -> SignalTier.WEAK
        else -> SignalTier.NONE
    }

    /** `38`。没有测量值时是 `—`，而不是 `0`——0 会被读成「信号强度为零」这个测量结论。 */
    fun formatCn0(cn0DbHz: Float): String =
        if (!cn0DbHz.isFinite() || cn0DbHz <= 0f) "—" else cn0DbHz.roundToInt().toString()

    /** 仰角或方位角。`45°`；未上报时 `—`。 */
    fun formatAngle(degrees: Float): String =
        if (!degrees.isFinite()) "—" else "${degrees.roundToInt()}°"

    /**
     * C/N0 在固定量程进度条上的占比，落在 `0f..1f`。
     *
     * **量程固定为 0–50 dB-Hz，不按当前最大值自适应。** 理由与主页磁场进度条用固定
     * 0–100 μT 相同：自适应量程下，一颗 15 dB-Hz 的弱星会被画成满格，
     * 用户从此失去「多大才算大」的直觉——而这个页面的全部意义就是让「为什么定不上位」变得可判断。
     */
    fun cn0BarFraction(cn0DbHz: Float): Float = when {
        !cn0DbHz.isFinite() || cn0DbHz <= 0f -> 0f
        else -> (cn0DbHz / BAR_RANGE_DB_HZ).coerceIn(0f, 1f)
    }

    /**
     * 汇总文案。一颗都没有时不说「可见 0 颗」——那是把「还没搜到」说成了一次测量结果。
     */
    fun formatSummary(snapshot: GnssSnapshot): String =
        if (snapshot.satellites.isEmpty()) {
            "尚未搜到卫星"
        } else {
            "可见 ${snapshot.visibleCount} 颗 · 参与定位 ${snapshot.usedInFixCount} 颗"
        }

    /**
     * GNSS 引擎的状态。三态必须各不相同，否则用户分不清「没启动」与「启动了但搜不到」。
     *
     * 被 [GnssBlock] 挡住时返回 `—`：那时 [GnssSnapshot.engineStarted] 必然为 false，
     * 报「引擎未启动」是把一个权限问题说成了硬件问题，而横幅已经把真实原因写清楚了。
     */
    fun formatEngineState(snapshot: GnssSnapshot): String = when {
        snapshot.blocked != null -> "—"
        !snapshot.engineStarted -> "GNSS 引擎未启动"
        snapshot.usedInFixCount == 0 -> "正在搜星"
        else -> "已定位"
    }

    /**
     * 顶部的横幅文案。数据能正常读到时返回 null，整块横幅不渲染。
     *
     * 三种成因各自说清「去哪里解决」：这是这个页面存在的意义——
     * 让「为什么定不上位」当场可判断，而不是靠猜。
     */
    fun formatBanner(snapshot: GnssSnapshot): String? = when (snapshot.blocked) {
        null -> null
        GnssBlock.PERMISSION -> "需要「精确位置」权限才能看到卫星。仅大致位置时系统不给卫星数据。"
        GnssBlock.SERVICES -> "系统定位服务已关闭，请先在系统设置中开启。"
        GnssBlock.UNAVAILABLE -> "本机无法提供卫星状态。可能是设备没有 GNSS 硬件。"
    }

    /** `首次定位 12.4 秒`。尚未定位时返回 null，由调用方决定整行不显示。 */
    fun formatFirstFix(elapsedMs: Long?): String? =
        elapsedMs?.let { String.format(Locale.US, "首次定位 %.1f 秒", it / 1000.0) }

    /**
     * 展示顺序：参与解算的排最前，组内按信号由强到弱。
     *
     * 这是一个**全序**，最后两级（星座序号、svid）不是凑数的：C/N0 是浮点数且常常相等，
     * 若比较到此为止，同值的两颗星在每一帧之间的相对顺序由排序实现决定，
     * 列表会随刷新不停互换位置——而 LazyColumn 又是按 [SatelliteInfo.key] 复用条目的，
     * 结果是画面在抖。
     */
    fun sortForDisplay(satellites: List<SatelliteInfo>): List<SatelliteInfo> =
        satellites.sortedWith(
            compareByDescending<SatelliteInfo> { it.usedInFix }
                .thenByDescending { cn0SortKey(it.cn0DbHz) }
                .thenBy { it.constellation.ordinal }
                .thenBy { it.svid }
        )

    /**
     * 排序专用的 C/N0 键。
     *
     * 不能直接把 `cn0DbHz` 丢给比较器：`Float.compareTo` 把 NaN 定义成**比任何数都大**，
     * 于是降序排列时一颗「没有上报测量值」的卫星会排到所有正常卫星前面。
     */
    private fun cn0SortKey(cn0DbHz: Float): Float =
        if (cn0DbHz.isFinite() && cn0DbHz > 0f) cn0DbHz else Float.NEGATIVE_INFINITY
}
