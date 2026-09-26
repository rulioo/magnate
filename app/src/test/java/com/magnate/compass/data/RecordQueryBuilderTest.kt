package com.magnate.compass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 构造的测试。
 *
 * 这里断言的是 **SQL 文本与绑定参数**，不是查询结果——真正的结果正确性由
 * `androidTest` 的 DAO 测试覆盖（需要模拟器）。但字符串层面的错误（参数顺序错位、
 * 通配符没转义、开闭区间写反）恰恰是范围筛选最容易出的错，且完全可以在 JVM 上挡住。
 */
class RecordQueryBuilderTest {

    private fun sql(filter: RecordFilter) = RecordQueryBuilder.build(filter).sql
    private fun args(filter: RecordFilter) = RecordQueryBuilder.build(filter).args

    // —— 空筛选 ——

    @Test
    fun `空筛选生成可执行的最小查询`() {
        val built = RecordQueryBuilder.build(RecordFilter())

        assertTrue(built.sql.startsWith("SELECT r.* FROM records r LEFT JOIN scenes s ON s.id = r.sceneId"))
        assertTrue(built.sql.contains("WHERE 1 = 1"))
        assertTrue(built.sql.contains("ORDER BY r.timestamp DESC, r.id DESC"))
        assertTrue(built.args.isEmpty())
    }

    @Test
    fun `默认按时间倒序 次级键同样是 id`() {
        // 次级排序键保证同一时刻的记录顺序稳定，列表不会无故跳动
        assertTrue(sql(RecordFilter()).contains("ORDER BY r.timestamp DESC, r.id DESC"))
    }

    @Test
    fun `按磁场强度升序排序`() {
        val built = sql(RecordFilter(sortBy = SortField.MAGNITUDE, sortAsc = true))
        assertTrue(built.contains("ORDER BY r.magnitude ASC, r.id ASC"))
    }

    @Test
    fun `排序字段来自枚举白名单而不是拼接用户输入`() {
        SortField.entries.forEach { field ->
            val built = sql(RecordFilter(sortBy = field))
            val column = built.substringAfter("ORDER BY ").substringBefore(' ')
            assertTrue("非白名单列名: $column", column == "r.timestamp" || column == "r.magnitude")
        }
    }

    // —— 关键词与 LIKE 转义 ——

    @Test
    fun `关键词同时匹配备注 标签名与场景名`() {
        val built = sql(RecordFilter(keyword = "机房"))
        assertTrue(built.contains("r.note LIKE ?"))
        assertTrue(built.contains("t.name LIKE ?"))
        assertTrue(built.contains("s.name LIKE ?"))
        assertEquals(listOf("%机房%", "%机房%", "%机房%"), args(RecordFilter(keyword = "机房")))
    }

    @Test
    fun `LIKE 通配符被转义`() {
        // 用户输入 % 时若不转义，LIKE '%%%' 会匹配全部记录，看起来像「搜索失效」
        assertEquals("\\%", RecordQueryBuilder.escapeLike("%"))
        assertEquals("\\_", RecordQueryBuilder.escapeLike("_"))
        assertEquals("\\\\", RecordQueryBuilder.escapeLike("\\"))
        // `_` 也是 LIKE 的单字符通配符，必须一起转义
        assertEquals("50\\%\\_x", RecordQueryBuilder.escapeLike("50%_x"))
        assertEquals("机房", RecordQueryBuilder.escapeLike("机房"))
    }

    @Test
    fun `关键词里的百分号被当成字面量传入绑定参数`() {
        val built = args(RecordFilter(keyword = "50%"))
        assertEquals(listOf("%50\\%%", "%50\\%%", "%50\\%%"), built)
    }

    @Test
    fun `单引号不会破坏 SQL`() {
        // 绑定参数本身就挡住了注入，这条用例是为了防止将来有人改成字符串拼接
        val built = args(RecordFilter(keyword = "'; DROP TABLE records; --"))
        assertEquals(3, built.size)
        built.forEach { arg ->
            assertTrue(arg is String && arg.contains("DROP TABLE"))
        }
    }

    @Test
    fun `纯空白关键词被忽略`() {
        val built = RecordQueryBuilder.build(RecordFilter(keyword = "   "))
        assertFalse(built.sql.contains("LIKE"))
        assertTrue(built.args.isEmpty())
    }

    @Test
    fun `关键词两端空白被裁掉`() {
        assertEquals(listOf("%机房%", "%机房%", "%机房%"), args(RecordFilter(keyword = "  机房  ")))
    }

    // —— 标签：AND / OR ——

    @Test
    fun `标签 OR 语义用计数大于零`() {
        val built = sql(RecordFilter(tagIds = setOf(1L, 2L), tagMatchAll = false))
        assertTrue(built.contains("COUNT(DISTINCT rt.tagId)"))
        assertTrue(built.contains("> 0"))
        assertFalse(built.contains("= ?"))
    }

    @Test
    fun `标签 AND 语义要求计数等于标签个数`() {
        val built = RecordQueryBuilder.build(RecordFilter(tagIds = setOf(1L, 2L, 3L), tagMatchAll = true))
        assertTrue(built.sql.contains("= ?"))
        // 三个 id 先入参，然后是期望的命中数量
        assertEquals(listOf(1L, 2L, 3L, 3), built.args)
    }

    @Test
    fun `标签 id 排序后再拼占位符 保证参数与占位符一一对应`() {
        // Set 的迭代顺序不保证稳定，若不排序，占位符个数虽对但顺序会随实现变化
        val built = RecordQueryBuilder.build(RecordFilter(tagIds = setOf(9L, 3L, 7L), tagMatchAll = true))
        assertEquals(listOf(3L, 7L, 9L, 3), built.args)
    }

    // —— 场景：多选 OR ——

    @Test
    fun `场景筛选是 IN 多选`() {
        val built = RecordQueryBuilder.build(RecordFilter(sceneIds = setOf(5L, 2L)))
        assertTrue(built.sql.contains("r.sceneId IN (?, ?)"))
        assertEquals(listOf(2L, 5L), built.args)
    }

    @Test
    fun `空场景集合不产生条件`() {
        assertFalse(sql(RecordFilter(sceneIds = emptySet())).contains("r.sceneId IN"))
    }

    // —— 时间区间 ——

    @Test
    fun `时间区间两端都是闭区间`() {
        val built = RecordQueryBuilder.build(RecordFilter(timeFrom = 100L, timeTo = 200L))
        assertTrue(built.sql.contains("r.timestamp >= ?"))
        assertTrue(built.sql.contains("r.timestamp <= ?"))
        assertEquals(listOf(100L, 200L), built.args)
    }

    // —— 磁场强度区间与开闭端点 ——

    @Test
    fun `磁场区间默认两端闭合`() {
        val built = RecordQueryBuilder.build(RecordFilter(magnitudeMin = 25f, magnitudeMax = 65f))
        assertTrue(built.sql.contains("r.magnitude >= ?"))
        assertTrue(built.sql.contains("r.magnitude <= ?"))
        assertEquals(listOf(25f, 65f), built.args)
    }

    @Test
    fun `偏低预设的右端点取开区间`() {
        // 三个预设 chip 写的是 < 25 / 25–65 / > 65，两两相邻、边界重合。
        // 若两端都闭合，恰好 25.0 的读数会同时落进「偏低」和「正常」两个预设。
        val built = RecordQueryBuilder.build(
            RecordFilter(magnitudeMax = 25f, magnitudeMaxExclusive = true),
        )
        assertTrue(built.sql.contains("r.magnitude < ?"))
        assertFalse(built.sql.contains("r.magnitude <= ?"))
    }

    @Test
    fun `偏高预设的左端点取开区间`() {
        val built = RecordQueryBuilder.build(
            RecordFilter(magnitudeMin = 65f, magnitudeMinExclusive = true),
        )
        assertTrue(built.sql.contains("r.magnitude > ?"))
        assertFalse(built.sql.contains("r.magnitude >= ?"))
    }

    @Test
    fun `正常预设两端都闭合 与相邻两个预设恰好不重叠`() {
        // 偏低 = (-∞, 25)，正常 = [25, 65]，偏高 = (65, +∞)
        val low = RecordQueryBuilder.build(
            RecordFilter(magnitudeMax = 25f, magnitudeMaxExclusive = true),
        ).sql
        val normal = RecordQueryBuilder.build(
            RecordFilter(magnitudeMin = 25f, magnitudeMax = 65f),
        ).sql
        val high = RecordQueryBuilder.build(
            RecordFilter(magnitudeMin = 65f, magnitudeMinExclusive = true),
        ).sql

        assertTrue(low.contains("r.magnitude < ?"))
        assertTrue(normal.contains("r.magnitude >= ?") && normal.contains("r.magnitude <= ?"))
        assertTrue(high.contains("r.magnitude > ?"))
    }

    @Test
    fun `只给下界时不产生上界条件`() {
        val built = RecordQueryBuilder.build(RecordFilter(magnitudeMin = 25f))
        assertTrue(built.sql.contains("r.magnitude >= ?"))
        assertFalse(built.sql.contains("r.magnitude <="))
        assertEquals(listOf(25f), built.args)
    }

    // —— 有无坐标：必须包含场景继承 ——

    @Test
    fun `筛选有坐标时必须同时考虑场景继承`() {
        // 这是场景机制引入的最大一致性陷阱：FIXED 场景内的记录自身 latitude 为 null，
        // 但解析后有坐标。只查 records 表会让这些记录凭空消失。
        val built = sql(RecordFilter(hasLocation = true))
        assertTrue(built.contains("r.latitude IS NOT NULL"))
        assertTrue(built.contains("r.sceneId IS NOT NULL AND s.latitude IS NOT NULL"))
    }

    @Test
    fun `筛选无坐标时必须两个来源都没有`() {
        val built = sql(RecordFilter(hasLocation = false))
        assertTrue(built.contains("r.latitude IS NULL"))
        assertTrue(built.contains("r.sceneId IS NULL OR s.latitude IS NULL"))
    }

    @Test
    fun `hasLocation 为 null 时不产生条件`() {
        val built = sql(RecordFilter(hasLocation = null))
        assertFalse(built.contains("r.latitude IS"))
    }

    @Test
    fun `SQL 侧与 Kotlin 侧的坐标判定条件一一对应`() {
        // resolveLocation 的顺序是：记录自身 → 场景 → 无。
        // SQL 侧必须用同样的顺序，否则同一条记录在列表里显示有坐标、筛选时却被排除。
        val has = sql(RecordFilter(hasLocation = true))
        val ownFirst = has.indexOf("r.latitude IS NOT NULL")
        val thenScene = has.indexOf("s.latitude IS NOT NULL")
        assertTrue("记录自身坐标的判定必须出现在场景回退之前", ownFirst in 0..<thenScene)
    }

    // —— 精度门槛 ——

    @Test
    fun `精度门槛作用于解析后的坐标`() {
        val built = RecordQueryBuilder.build(RecordFilter(minLocationAccuracy = 50f))

        assertTrue(built.sql.contains("CASE"))
        assertTrue(built.sql.contains("COALESCE(r.locationAccuracy, ?)"))
        assertTrue(built.sql.contains("COALESCE(s.locationAccuracy, ?)"))
        // 三个缺失精度的分支各填一个 MAX_VALUE 哨兵，最后是阈值本身
        assertEquals(listOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 50f), built.args)
    }

    @Test
    fun `精度门槛用 Float MAX_VALUE 兜底 而不是 0`() {
        // 若兜底值写成 0，「精度优于 50m」会把所有缺失精度的记录都放进来，
        // 而那些恰恰是最不可信的坐标。
        val built = args(RecordFilter(minLocationAccuracy = 50f))
        assertEquals(3, built.count { it == Float.MAX_VALUE })
    }

    // —— 计数查询 ——

    @Test
    fun `计数查询用 COUNT 且不带排序`() {
        val built = RecordQueryBuilder.buildCount(RecordFilter(keyword = "机房"))

        assertTrue(built.sql.startsWith("SELECT COUNT(*) FROM records r LEFT JOIN scenes s ON s.id = r.sceneId"))
        assertFalse(built.sql.contains("ORDER BY"))
    }

    @Test
    fun `计数查询与结果查询条件完全一致`() {
        // 筛选面板底部显示「查看 N 条结果」，N 若与实际条数不符就是明确的 bug
        val filter = RecordFilter(
            keyword = "机房",
            tagIds = setOf(1L, 2L),
            tagMatchAll = false,
            sceneIds = setOf(3L),
            timeFrom = 100L,
            timeTo = 200L,
            magnitudeMin = 25f,
            magnitudeMax = 65f,
            hasLocation = true,
            minLocationAccuracy = 50f,
        )
        val result = RecordQueryBuilder.build(filter)
        val count = RecordQueryBuilder.buildCount(filter)

        val whereOfResult = result.sql.substringAfter("WHERE 1 = 1").substringBefore(" ORDER BY")
        val whereOfCount = count.sql.substringAfter("WHERE 1 = 1")
        assertEquals(whereOfResult, whereOfCount)
        assertEquals(result.args, count.args)
    }

    // —— 组合 ——

    @Test
    fun `多个条件之间一律是 AND`() {
        val built = sql(
            RecordFilter(
                keyword = "机房",
                tagIds = setOf(1L),
                sceneIds = setOf(2L),
                timeFrom = 100L,
                magnitudeMin = 25f,
                hasLocation = true,
            ),
        )
        // 关键词自身的三个分支是 OR；其余条件与它之间必须是 AND
        assertTrue(built.contains("AND ("))
        val andCount = Regex(" AND ").findAll(built).count()
        assertTrue("条件之间缺少 AND 连接: $built", andCount >= 5)
    }

    @Test
    fun `绑定参数个数与占位符个数一致`() {
        // 参数错位是动态 SQL 最隐蔽的一类 bug：SQL 能跑，结果却是错的
        val filters = listOf(
            RecordFilter(),
            RecordFilter(keyword = "机房"),
            RecordFilter(tagIds = setOf(1L, 2L), tagMatchAll = true),
            RecordFilter(tagIds = setOf(1L, 2L), tagMatchAll = false),
            RecordFilter(sceneIds = setOf(3L, 4L)),
            RecordFilter(timeFrom = 1L, timeTo = 2L),
            RecordFilter(magnitudeMin = 25f, magnitudeMax = 65f),
            RecordFilter(hasLocation = true),
            RecordFilter(hasLocation = false),
            RecordFilter(minLocationAccuracy = 50f),
            RecordFilter(
                keyword = "a",
                tagIds = setOf(1L),
                sceneIds = setOf(2L),
                timeFrom = 1L,
                timeTo = 2L,
                magnitudeMin = 3f,
                magnitudeMax = 4f,
                hasLocation = true,
                minLocationAccuracy = 5f,
            ),
        )

        filters.forEach { filter ->
            val built = RecordQueryBuilder.build(filter)
            val placeholders = built.sql.count { it == '?' }
            assertEquals("筛选条件 $filter 的参数与占位符数量不符", placeholders, built.args.size)
        }
    }
}
