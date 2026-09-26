package com.magnate.compass.data

import com.magnate.compass.NOW
import com.magnate.compass.TEST_ZONE
import com.magnate.compass.sensor.CompassMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 预设 → 时间戳的换算测试。
 *
 * 期望值在这里**用 `java.time` 独立算一遍**，而不是回头调用 [com.magnate.compass.util.TimeFormat]——
 * 后者正是被测代码，拿它当基准等于自己证明自己。日界线的偏移错误（少一天、把当天 00:00
 * 当成上界）是筛选器最经典的坑，独立算一遍才能真的挡住。
 */
class SearchCriteriaTest {

    private fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(TEST_ZONE).toLocalDate()

    private fun startOf(date: LocalDate): Long =
        date.atStartOfDay(TEST_ZONE).toInstant().toEpochMilli()

    private fun filter(criteria: SearchCriteria) = criteria.toFilter(NOW, TEST_ZONE)

    // —— 时间预设 ——

    @Test
    fun `不限时间时不产生时间条件`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.ALL))
        assertNull(f.timeFrom)
        assertNull(f.timeTo)
    }

    @Test
    fun `今天 的起点是今天零点 上界是今天 23 59 59 999`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.TODAY))
        val today = dateOf(NOW)

        assertEquals(startOf(today), f.timeFrom)
        // 上界必须含当天绝大多数时刻——用当天 00:00 作上界会让「选了今天却查不到今天」
        assertEquals(startOf(today.plusDays(1)) - 1, f.timeTo)
        assertTrue("14:30 的记录必须落在今天区间内", NOW <= f.timeTo!!)
    }

    @Test
    fun `近 7 天 含今天 因此起点是 6 天前的零点`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.LAST_7))
        val today = dateOf(NOW)

        assertEquals(startOf(today.minusDays(6)), f.timeFrom)
        assertEquals(startOf(today.plusDays(1)) - 1, f.timeTo)
    }

    @Test
    fun `近 30 天 含今天 因此起点是 29 天前的零点`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.LAST_30))
        val today = dateOf(NOW)

        assertEquals(startOf(today.minusDays(29)), f.timeFrom)
        assertEquals(startOf(today.plusDays(1)) - 1, f.timeTo)
    }

    @Test
    fun `三个时间预设的上界完全一致 都是今天结束`() {
        val today = dateOf(NOW)
        val expected = startOf(today.plusDays(1)) - 1
        listOf(TimePreset.TODAY, TimePreset.LAST_7, TimePreset.LAST_30).forEach { preset ->
            assertEquals("预设 $preset 的上界不符", expected, filter(SearchCriteria(timePreset = preset)).timeTo)
        }
    }

    @Test
    fun `近 7 天区间真的覆盖了 7 个日历日`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.LAST_7))
        val days = java.time.temporal.ChronoUnit.DAYS.between(dateOf(f.timeFrom!!), dateOf(f.timeTo!!)) + 1
        assertEquals(7L, days)
    }

    @Test
    fun `自定义区间含结束当天`() {
        val from = startOf(LocalDate.of(2024, 1, 10))
        val to = startOf(LocalDate.of(2024, 1, 20))
        val f = filter(
            SearchCriteria(
                timePreset = TimePreset.CUSTOM,
                customTimeFrom = from,
                customTimeTo = to,
            ),
        )

        assertEquals(startOf(LocalDate.of(2024, 1, 10)), f.timeFrom)
        // 结束日期选 1 月 20 日，当天 23:59:59.999 的记录必须能查到
        assertEquals(startOf(LocalDate.of(2024, 1, 21)) - 1, f.timeTo)
    }

    @Test
    fun `自定义区间只给一端时另一端不产生条件`() {
        val onlyFrom = filter(
            SearchCriteria(timePreset = TimePreset.CUSTOM, customTimeFrom = NOW),
        )
        assertNull(onlyFrom.timeTo)
        assertEquals(startOf(dateOf(NOW)), onlyFrom.timeFrom)

        val onlyTo = filter(SearchCriteria(timePreset = TimePreset.CUSTOM, customTimeTo = NOW))
        assertNull(onlyTo.timeFrom)
    }

    @Test
    fun `自定义区间没给日期时不产生时间条件`() {
        val f = filter(SearchCriteria(timePreset = TimePreset.CUSTOM))
        assertNull(f.timeFrom)
        assertNull(f.timeTo)
    }

    @Test
    fun `跨月与跨年的日期边界不会少算一天`() {
        // 3 月 1 日往前推 6 天要跨回 2 月（闰年是 24 日，平年是 23 日），
        // 用固定 86400000 毫秒去减在夏令时地区还会再错一小时
        val march1 = startOf(LocalDate.of(2024, 3, 1)) + 12 * 3600_000L
        val f = SearchCriteria(timePreset = TimePreset.LAST_7).toFilter(march1, TEST_ZONE)
        assertEquals(startOf(LocalDate.of(2024, 2, 24)), f.timeFrom)

        val jan1 = startOf(LocalDate.of(2025, 1, 1)) + 12 * 3600_000L
        val g = SearchCriteria(timePreset = TimePreset.LAST_7).toFilter(jan1, TEST_ZONE)
        assertEquals(startOf(LocalDate.of(2024, 12, 26)), g.timeFrom)
    }

    // —— 磁场强度预设 ——

    @Test
    fun `偏低预设是小于 25 且右端取开`() {
        val f = filter(SearchCriteria(magnitudePreset = MagnitudePreset.LOW))
        assertNull(f.magnitudeMin)
        assertEquals(CompassMath.MAGNETIC_FIELD_MIN_UT, f.magnitudeMax!!, 0f)
        assertTrue("端点必须排除等值，否则 25.0 会同时落进偏低与正常", f.magnitudeMaxExclusive)
        assertFalse(f.magnitudeMinExclusive)
    }

    @Test
    fun `正常预设是闭区间 25 到 65`() {
        val f = filter(SearchCriteria(magnitudePreset = MagnitudePreset.NORMAL))
        assertEquals(CompassMath.MAGNETIC_FIELD_MIN_UT, f.magnitudeMin!!, 0f)
        assertEquals(CompassMath.MAGNETIC_FIELD_MAX_UT, f.magnitudeMax!!, 0f)
        assertFalse(f.magnitudeMinExclusive)
        assertFalse(f.magnitudeMaxExclusive)
    }

    @Test
    fun `偏高预设是大于 65 且左端取开`() {
        val f = filter(SearchCriteria(magnitudePreset = MagnitudePreset.HIGH))
        assertEquals(CompassMath.MAGNETIC_FIELD_MAX_UT, f.magnitudeMin!!, 0f)
        assertNull(f.magnitudeMax)
        assertTrue("端点必须排除等值，否则 65.0 会同时落进正常与偏高", f.magnitudeMinExclusive)
        assertFalse(f.magnitudeMaxExclusive)
    }

    @Test
    fun `三个磁场预设的边界恰好不重叠`() {
        val low = filter(SearchCriteria(magnitudePreset = MagnitudePreset.LOW))
        val normal = filter(SearchCriteria(magnitudePreset = MagnitudePreset.NORMAL))
        val high = filter(SearchCriteria(magnitudePreset = MagnitudePreset.HIGH))

        val lowMax = low.magnitudeMax!!
        val normalMin = normal.magnitudeMin!!
        assertEquals("偏低的上界与正常的下界必须是同一个数", lowMax, normalMin, 0f)
        assertTrue("偏低必须排除 $lowMax 这个值", low.magnitudeMaxExclusive)
        assertFalse("正常必须包含 $normalMin 这个值", normal.magnitudeMinExclusive)

        val normalMax = normal.magnitudeMax!!
        val highMin = high.magnitudeMin!!
        assertEquals(normalMax, highMin, 0f)
        assertTrue(high.magnitudeMinExclusive)
        assertFalse(normal.magnitudeMaxExclusive)
    }

    @Test
    fun `不限磁场强度时不产生强度条件`() {
        val f = filter(SearchCriteria(magnitudePreset = MagnitudePreset.ALL))
        assertNull(f.magnitudeMin)
        assertNull(f.magnitudeMax)
    }

    @Test
    fun `自定义强度区间两端都闭合`() {
        val f = filter(
            SearchCriteria(
                magnitudePreset = MagnitudePreset.CUSTOM,
                customMagnitudeMin = 30f,
                customMagnitudeMax = 40f,
            ),
        )
        assertEquals(30f, f.magnitudeMin!!, 0f)
        assertEquals(40f, f.magnitudeMax!!, 0f)
        assertFalse(f.magnitudeMinExclusive)
        assertFalse(f.magnitudeMaxExclusive)
    }

    // —— 坐标与精度 ——

    @Test
    fun `坐标预设映射到 hasLocation 三态`() {
        assertNull(filter(SearchCriteria(coordinatePreset = CoordinatePreset.ALL)).hasLocation)
        assertEquals(true, filter(SearchCriteria(coordinatePreset = CoordinatePreset.HAS)).hasLocation)
        assertEquals(false, filter(SearchCriteria(coordinatePreset = CoordinatePreset.NONE)).hasLocation)
    }

    @Test
    fun `仅高精度开关映射到 50 米门槛`() {
        val f = filter(SearchCriteria(goodAccuracyOnly = true))
        assertEquals(SearchCriteria.GOOD_ACCURACY_THRESHOLD_M, f.minLocationAccuracy!!, 0f)

        assertNull(filter(SearchCriteria(goodAccuracyOnly = false)).minLocationAccuracy)
    }

    // —— 关键词与标签 ——

    @Test
    fun `空白关键词降维成 null`() {
        assertNull(filter(SearchCriteria(keyword = "   ")).keyword)
        assertNull(filter(SearchCriteria(keyword = "")).keyword)
    }

    @Test
    fun `关键词两端空白被裁掉`() {
        assertEquals("机房", filter(SearchCriteria(keyword = "  机房  ")).keyword)
    }

    @Test
    fun `标签与场景条件原样透传`() {
        val f = filter(
            SearchCriteria(
                tagIds = setOf(1L, 2L),
                tagMatchAll = false,
                sceneIds = setOf(3L),
            ),
        )
        assertEquals(setOf(1L, 2L), f.tagIds)
        assertFalse(f.tagMatchAll)
        assertEquals(setOf(3L), f.sceneIds)
    }

    @Test
    fun `降维不改变排序默认值`() {
        val f = filter(SearchCriteria())
        assertEquals(SortField.TIME, f.sortBy)
        assertFalse(f.sortAsc)
    }

    // —— hasFilters / hasAnything / 两个出口 ——

    @Test
    fun `hasFilters 不看关键词 hasAnything 才看`() {
        val onlyKeyword = SearchCriteria(keyword = "机房")
        assertFalse("关键词不算筛选条件", onlyKeyword.hasFilters)
        assertTrue(onlyKeyword.hasAnything)

        val onlyFilter = SearchCriteria(coordinatePreset = CoordinatePreset.HAS)
        assertTrue(onlyFilter.hasFilters)
        assertTrue(onlyFilter.hasAnything)

        val empty = SearchCriteria()
        assertFalse(empty.hasFilters)
        assertFalse(empty.hasAnything)

        assertFalse(SearchCriteria(keyword = "   ").hasAnything)
    }

    @Test
    fun `每一个筛选维度都能让 hasFilters 为真`() {
        // 新增筛选维度时若忘了加进 hasFilters，筛选面板的「重置」按钮就不会亮
        listOf(
            SearchCriteria(sceneIds = setOf(1L)),
            SearchCriteria(tagIds = setOf(1L)),
            SearchCriteria(timePreset = TimePreset.TODAY),
            SearchCriteria(magnitudePreset = MagnitudePreset.LOW),
            SearchCriteria(coordinatePreset = CoordinatePreset.HAS),
            SearchCriteria(goodAccuracyOnly = true),
        ).forEach {
            assertTrue("$it 应当被判定为有筛选条件", it.hasFilters)
        }
    }

    @Test
    fun `withoutKeyword 只清关键词`() {
        val criteria = SearchCriteria(keyword = "机房", sceneIds = setOf(1L), timePreset = TimePreset.TODAY)
        val cleared = criteria.withoutKeyword()

        assertEquals("", cleared.keyword)
        assertEquals(setOf(1L), cleared.sceneIds)
        assertEquals(TimePreset.TODAY, cleared.timePreset)
    }

    @Test
    fun `withoutFilters 清掉全部筛选但保留关键词`() {
        val criteria = SearchCriteria(
            keyword = "机房",
            sceneIds = setOf(1L),
            tagIds = setOf(2L),
            tagMatchAll = false,
            timePreset = TimePreset.CUSTOM,
            customTimeFrom = 1L,
            customTimeTo = 2L,
            magnitudePreset = MagnitudePreset.CUSTOM,
            customMagnitudeMin = 3f,
            customMagnitudeMax = 4f,
            coordinatePreset = CoordinatePreset.NONE,
            goodAccuracyOnly = true,
        )
        val cleared = criteria.withoutFilters()

        assertEquals("关键词必须保留——用户的搜索意图不该被「重置筛选」抹掉", "机房", cleared.keyword)
        assertFalse(cleared.hasFilters)
        assertEquals(SearchCriteria().sceneIds, cleared.sceneIds)
        assertNull(cleared.customTimeFrom)
        assertNull(cleared.customTimeTo)
        assertNull(cleared.customMagnitudeMin)
        assertTrue(cleared.tagMatchAll)
        assertFalse(cleared.goodAccuracyOnly)
    }

    // —— 预设身份必须保留到最后 ——

    @Test
    fun `预设身份不会在降维时丢失`() {
        // §8.3 的活跃 chip 要显示「近 7 天」而不是一串时间戳。
        // 从时间戳反推预设不可靠——一段恰好 7 天的自定义区间会被误认成「近 7 天」。
        val criteria = SearchCriteria(timePreset = TimePreset.LAST_7)
        criteria.toFilter(NOW, TEST_ZONE)
        assertEquals(TimePreset.LAST_7, criteria.timePreset)
    }

    @Test
    fun `恰好七天的自定义区间与近 7 天 产生同样的时间戳 但预设身份不同`() {
        // 这正是「预设身份必须作为一等字段保存」的理由：两者降维后完全没有区别，
        // 想从时间戳反推出用户当初点的是哪个 chip 是不可能的。
        val today = dateOf(NOW)
        val custom = SearchCriteria(
            timePreset = TimePreset.CUSTOM,
            customTimeFrom = startOf(today.minusDays(6)),
            customTimeTo = startOf(today),
        )

        val customFilter = custom.toFilter(NOW, TEST_ZONE)
        val presetFilter = SearchCriteria(timePreset = TimePreset.LAST_7).toFilter(NOW, TEST_ZONE)

        assertEquals("时间戳应当完全一致", presetFilter.timeFrom, customFilter.timeFrom)
        assertEquals("时间戳应当完全一致", presetFilter.timeTo, customFilter.timeTo)
        assertEquals("但预设身份必须不同，否则 chip 会显示错标签", TimePreset.CUSTOM, custom.timePreset)
    }
}
