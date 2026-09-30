package com.magnate.compass.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卫星状态的映射与展示契约。
 *
 * 这些断言之所以值得写，是因为 `GnssStatus` 的构造函数是包私有的——真机上拿到什么数据
 * 无法在测试里复现，而**所有**读数到界面的转换逻辑都发生在这两个纯文件里。
 * 换句话说，这个文件是这套映射唯一的验证点。
 */
class GnssFormatTest {

    private val eps = 1e-6f

    private fun sat(
        svid: Int = 5,
        constellation: Constellation = Constellation.GPS,
        cn0: Float = 35f,
        usedInFix: Boolean = false,
    ) = SatelliteInfo(
        svid = svid,
        constellation = constellation,
        cn0DbHz = cn0,
        elevationDeg = 45f,
        azimuthDeg = 210f,
        usedInFix = usedInFix,
        hasAlmanac = true,
        hasEphemeris = true,
    )

    // —— 星座映射 ——

    @Test
    fun `星座常量映射到正确的星座`() {
        assertEquals(Constellation.GPS, constellationOf(CONSTELLATION_GPS))
        assertEquals(Constellation.SBAS, constellationOf(CONSTELLATION_SBAS))
        assertEquals(Constellation.GLONASS, constellationOf(CONSTELLATION_GLONASS))
        assertEquals(Constellation.QZSS, constellationOf(CONSTELLATION_QZSS))
        assertEquals(Constellation.BEIDOU, constellationOf(CONSTELLATION_BEIDOU))
        assertEquals(Constellation.GALILEO, constellationOf(CONSTELLATION_GALILEO))
    }

    @Test
    fun `IRNSS 映射为 NavIC`() {
        assertEquals("I", Constellation.IRNSS.shortName)
        assertEquals("NavIC", Constellation.IRNSS.displayName)
    }

    @Test
    fun `未知的星座类型回落到 UNKNOWN 而不是崩溃`() {
        // 平台以后可能新增星座。落到 UNKNOWN 只是显示成「?」，
        // 而抛异常会让整个卫星页白屏——为一个显示问题赔上整个页面不划算
        assertEquals(Constellation.UNKNOWN, constellationOf(CONSTELLATION_UNKNOWN))
        assertEquals(Constellation.UNKNOWN, constellationOf(99))
        assertEquals(Constellation.UNKNOWN, constellationOf(-1))
    }

    @Test
    fun `UNKNOWN 排在星座枚举的最后`() {
        // 枚举顺序即排序的次级依据。UNKNOWN 若不在最后，
        // 认不出星座的卫星会插到已知星座中间，看起来像排序坏了
        assertEquals(Constellation.UNKNOWN.ordinal, Constellation.entries.last().ordinal)
    }

    // —— C/N0 分档 ——

    @Test
    fun `C N0 分档的边界是闭区间`() {
        // 阈值上的值算「达到了这一档」，写成开区间会让 40.0 掉进 GOOD
        assertEquals(SignalTier.STRONG, GnssFormat.cn0Tier(40f))
        assertEquals(SignalTier.GOOD, GnssFormat.cn0Tier(30f))
        assertEquals(SignalTier.WEAK, GnssFormat.cn0Tier(20f))

        assertEquals(SignalTier.STRONG, GnssFormat.cn0Tier(41f))
        assertEquals(SignalTier.GOOD, GnssFormat.cn0Tier(35f))
        assertEquals(SignalTier.WEAK, GnssFormat.cn0Tier(25f))
        assertEquals(SignalTier.NONE, GnssFormat.cn0Tier(19.9f))
    }

    @Test
    fun `C N0 为 NaN 或非正数时分为 NONE 而不是 STRONG`() {
        // NaN 的所有比较都是 false。若分档写成「大于等于阈值就升级」，
        // NaN 会一路落空掉进最后一档——那种写法在这里碰巧是对的，
        // 但只要有人把它改写成 if/else-if 链就会静默变成「最强档」。
        // 这两条断言钉住语义：没有测量值 = 最差，不是最好
        assertEquals(SignalTier.NONE, GnssFormat.cn0Tier(Float.NaN))
        assertEquals(SignalTier.NONE, GnssFormat.cn0Tier(0f))
        assertEquals(SignalTier.NONE, GnssFormat.cn0Tier(-1f))
        assertEquals(SignalTier.NONE, GnssFormat.cn0Tier(Float.POSITIVE_INFINITY.let { -it }))
    }

    @Test
    fun `C N0 格式化为整数`() {
        assertEquals("38", GnssFormat.formatCn0(38.4f))
        assertEquals("39", GnssFormat.formatCn0(38.6f))
    }

    @Test
    fun `无效 C N0 显示为破折号而不是零`() {
        // 显示 0 会被读成「信号强度为零」这个测量结论，而实际是没测到
        assertEquals("—", GnssFormat.formatCn0(0f))
        assertEquals("—", GnssFormat.formatCn0(Float.NaN))
    }

    @Test
    fun `角度未上报时显示破折号`() {
        assertEquals("45°", GnssFormat.formatAngle(45.4f))
        assertEquals("—", GnssFormat.formatAngle(Float.NaN))
    }

    // —— 进度条量程 ——

    @Test
    fun `进度条用固定量程而不是自适应量程`() {
        // 自适应量程下，一颗 15 dB-Hz 的弱星会被画成满格，
        // 用户从此失去「多大才算大」的直觉——而本页的全部意义就是让信号好坏可判断
        assertEquals(0.5f, GnssFormat.cn0BarFraction(25f), eps)
        assertEquals(0.8f, GnssFormat.cn0BarFraction(40f), eps)
        assertEquals(1f, GnssFormat.cn0BarFraction(50f), eps)
    }

    @Test
    fun `进度条比例被夹在 0 到 1 之间`() {
        assertEquals(1f, GnssFormat.cn0BarFraction(80f), eps)
        assertEquals(0f, GnssFormat.cn0BarFraction(0f), eps)
        assertEquals(0f, GnssFormat.cn0BarFraction(Float.NaN), eps)
        assertEquals(0f, GnssFormat.cn0BarFraction(-5f), eps)
    }

    // —— 排序 ——

    @Test
    fun `参与定位的卫星排在最前`() {
        val weakButUsed = sat(svid = 1, cn0 = 22f, usedInFix = true)
        val strongUnused = sat(svid = 2, cn0 = 45f, usedInFix = false)

        val sorted = GnssFormat.sortForDisplay(listOf(strongUnused, weakButUsed))

        assertEquals(listOf(1, 2), sorted.map { it.svid })
    }

    @Test
    fun `同一组内按信号由强到弱`() {
        val sorted = GnssFormat.sortForDisplay(
            listOf(sat(svid = 1, cn0 = 22f), sat(svid = 2, cn0 = 45f), sat(svid = 3, cn0 = 33f))
        )

        assertEquals(listOf(2, 3, 1), sorted.map { it.svid })
    }

    @Test
    fun `没有上报信号的卫星排在最后而不是最前`() {
        // 这是最容易写错的一条：Float 的 compareTo 把 NaN 定义成**比任何数都大**，
        // 于是直接拿 cn0DbHz 去降序比较，一颗完全没上报测量值的卫星会排到所有正常卫星前面
        val sorted = GnssFormat.sortForDisplay(
            listOf(
                sat(svid = 1, cn0 = Float.NaN),
                sat(svid = 2, cn0 = 25f),
                sat(svid = 3, cn0 = 0f),
            )
        )

        assertEquals(2, sorted.first().svid)
        assertTrue("NaN 与 0 都必须落到末尾", sorted.drop(1).map { it.svid }.all { it != 2 })
    }

    @Test
    fun `C N0 相同时顺序稳定`() {
        // C/N0 是浮点数且常常相等。若比较到此为止，同值的两颗星在帧间会互换位置，
        // 而列表又按 key 复用条目，于是画面在抖
        val sorted = GnssFormat.sortForDisplay(
            listOf(sat(svid = 9, cn0 = 30f), sat(svid = 3, cn0 = 30f), sat(svid = 7, cn0 = 30f))
        )

        assertEquals(listOf(3, 7, 9), sorted.map { it.svid })
    }

    @Test
    fun `同编号不同星座的卫星键必须不同`() {
        // svid 只在星座内唯一。只用 svid 作 key 会让 LazyColumn 把 GPS 5 号
        // 和北斗 5 号当成同一项复用，滚动时内容会串
        val gps = sat(svid = 5, constellation = Constellation.GPS)
        val beidou = sat(svid = 5, constellation = Constellation.BEIDOU)

        assertNotEquals(gps.key, beidou.key)
    }

    // —— 汇总 ——

    @Test
    fun `可见数与参与定位数分别统计`() {
        val snapshot = GnssSnapshot(
            satellites = listOf(
                sat(svid = 1, usedInFix = true),
                sat(svid = 2, usedInFix = true),
                sat(svid = 3, usedInFix = false),
            )
        )

        assertEquals(3, snapshot.visibleCount)
        assertEquals(2, snapshot.usedInFixCount)
    }

    @Test
    fun `一颗都没有时汇总文案是尚未搜到卫星`() {
        // 说「可见 0 颗」是把「还没搜到」讲成了一次测量结论
        val message = GnssFormat.formatSummary(GnssSnapshot())

        assertEquals("尚未搜到卫星", message)
    }

    @Test
    fun `有卫星时汇总同时给出可见数与参与定位数`() {
        val snapshot = GnssSnapshot(
            satellites = listOf(sat(svid = 1, usedInFix = true), sat(svid = 2))
        )

        val message = GnssFormat.formatSummary(snapshot)

        assertTrue(message, message.contains("可见 2 颗"))
        assertTrue(message, message.contains("参与定位 1 颗"))
    }

    // —— 引擎状态 ——

    @Test
    fun `引擎状态三态文案各不相同`() {
        // 三态必须可区分，否则用户分不清「没启动」与「启动了但搜不到」，
        // 而这两者的处理方式完全相反：前者要开定位服务，后者要换个位置
        val notStarted = GnssFormat.formatEngineState(GnssSnapshot(engineStarted = false))
        val searching = GnssFormat.formatEngineState(
            GnssSnapshot(engineStarted = true, satellites = listOf(sat(usedInFix = false)))
        )
        val fixed = GnssFormat.formatEngineState(
            GnssSnapshot(engineStarted = true, satellites = listOf(sat(usedInFix = true)))
        )

        assertEquals(3, setOf(notStarted, searching, fixed).size)
        assertTrue(notStarted, notStarted.contains("未启动"))
        assertTrue(searching, searching.contains("搜星"))
    }

    @Test
    fun `引擎在跑但一颗星都没搜到时算正在搜星`() {
        // 卫星列表为空时「参与解算数为 0」，此时应报「正在搜星」而不是「未启动」
        val message = GnssFormat.formatEngineState(GnssSnapshot(engineStarted = true))

        assertEquals("正在搜星", message)
    }

    // —— 首次定位 ——

    @Test
    fun `首次定位耗时格式化为秒`() {
        assertEquals("首次定位 12.4 秒", GnssFormat.formatFirstFix(12_400L))
        assertEquals("首次定位 0.5 秒", GnssFormat.formatFirstFix(500L))
    }

    @Test
    fun `尚未定位时没有首次定位文案`() {
        // 返回 null 而不是「首次定位 0.0 秒」——后者会被读成「刚定上位」
        assertNull(GnssFormat.formatFirstFix(null))
    }

    // —— 受阻横幅 ——

    @Test
    fun `数据正常时没有横幅`() {
        assertNull(GnssFormat.formatBanner(GnssSnapshot()))
        assertNull(
            GnssFormat.formatBanner(
                GnssSnapshot(satellites = listOf(sat()), engineStarted = true)
            )
        )
    }

    @Test
    fun `三种受阻成因各有一句不同的说明`() {
        val messages = GnssBlock.entries.map { block ->
            GnssFormat.formatBanner(GnssSnapshot(blocked = block))
        }
        assertEquals(3, messages.size)
        assertTrue("三种成因的文案不能重复", messages.toSet().size == 3)
        messages.forEach { assertTrue("文案不能为空", !it.isNullOrBlank()) }
    }

    @Test
    fun `权限不足的横幅指向权限而不是环境`() {
        val message = GnssFormat.formatBanner(GnssSnapshot(blocked = GnssBlock.PERMISSION))!!
        assertTrue("必须点明是权限问题", message.contains("权限"))
        // 这是本次修复的那条回归本身：把权限问题说成「去室外站着」，
        // 用户照做永远不会好
        assertTrue("权限问题不能劝用户去室外", !message.contains("室外"))
    }

    @Test
    fun `受阻时引擎状态显示为破折号而不是引擎未启动`() {
        // 受阻时 engineStarted 必然是 false，直接照报会把权限问题说成硬件问题——
        // 而横幅已经把真实原因写清楚了，这一栏再说一句只会打架
        assertEquals("—", GnssFormat.formatEngineState(GnssSnapshot(blocked = GnssBlock.PERMISSION)))
        assertEquals("—", GnssFormat.formatEngineState(GnssSnapshot(blocked = GnssBlock.SERVICES)))
    }
}
