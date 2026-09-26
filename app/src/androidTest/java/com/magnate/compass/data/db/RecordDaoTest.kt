package com.magnate.compass.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.magnate.compass.NOW
import com.magnate.compass.data.RecordFilter
import com.magnate.compass.data.RecordQueryBuilder
import com.magnate.compass.data.SortField
import com.magnate.compass.data.entity.RecordTagCrossRef
import com.magnate.compass.data.entity.TagEntity
import com.magnate.compass.record
import com.magnate.compass.sceneWithCoordinate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动态查询在**真实 SQLite** 上的行为。
 *
 * JVM 层的 `RecordQueryBuilderTest` 只能断言「拼出来的 SQL 文本与绑定参数长什么样」，
 * 断言不了「这段 SQL 真的能跑、真的筛出对的记录」。两者之间隔着一整层 SQLite 语义：
 * `LIKE ... ESCAPE` 的转义是否真的生效、`COUNT(DISTINCT ...) = ?` 的 AND 语义是否成立、
 * 空场景 LEFT JOIN 后 `s.name IS NULL` 判断是否如预期——这些只有跑一次才知道。
 *
 * 需要模拟器或真机。
 */
@RunWith(AndroidJUnit4::class)
class RecordDaoTest {

    private lateinit var db: MagnateDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MagnateDatabase::class.java,
        )
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 与 `RecordRepository` 里那条私有扩展保持一致。
     *
     * 故意在这里重写一遍而不是把生产代码里的改成 public：测试要能独立地
     * 把「拼好的 SQL」交给 DAO，一旦仓库层的绑定方式变了，这里也会立刻暴露。
     */
    private fun RecordQueryBuilder.BuiltQuery.toSimpleQuery() =
        androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())

    private fun query(filter: RecordFilter) =
        RecordQueryBuilder.build(filter).toSimpleQuery()

    private fun countQuery(filter: RecordFilter) =
        RecordQueryBuilder.buildCount(filter).toSimpleQuery()

    private suspend fun idsOf(filter: RecordFilter): List<Long> =
        db.recordDao().search(query(filter)).first().map { it.record.id }.sorted()

    // —— 关键词 ——

    @Test
    fun `关键词命中备注_标签名与场景名`() = runTest {
        val dao = db.recordDao()
        val tagDao = db.tagDao()
        val sceneDao = db.sceneDao()

        val sceneId = sceneDao.insert(sceneWithCoordinate(name = "阳光大厦 B2层"))
        val tagId = tagDao.insert(TagEntity(name = "配电房", createdAt = NOW))

        val byNote = dao.insert(record(id = 1, note = "检查配电房接地"))
        val byTag = dao.insert(record(id = 2))
        dao.linkTags(listOf(RecordTagCrossRef(byTag, tagId)))
        val byScene = dao.insert(record(id = 3, sceneId = sceneId))
        val unrelated = dao.insert(record(id = 4, note = "无关"))

        assertEquals(listOf(byNote, byScene, byTag).sorted(), idsOf(RecordFilter(keyword = "配电")))
        assertEquals(listOf(byScene), idsOf(RecordFilter(keyword = "阳光大厦")))
        assertTrue(unrelated > 0)
    }

    @Test
    fun `备注里的百分号被当成字面量_而不是匹配全部`() = runTest {
        // 这是 escapeLike 存在的唯一理由：不转义的话搜「50%」会返回所有记录，
        // 用户看到的是「搜索结果和没筛一样」，而不是任何报错
        val dao = db.recordDao()
        val percent = dao.insert(record(id = 1, note = "负载 50% 运行"))
        dao.insert(record(id = 2, note = "负载 80% 运行"))
        dao.insert(record(id = 3, note = "无关记录"))

        assertEquals(listOf(percent), idsOf(RecordFilter(keyword = "50%")))
    }

    @Test
    fun `备注里的下划线也被当成字面量`() = runTest {
        // `_` 是单字符通配符，不转义时「a_b」会匹配「aXb」
        val dao = db.recordDao()
        val literal = dao.insert(record(id = 1, note = "回路 a_b"))
        dao.insert(record(id = 2, note = "回路 aXb"))

        assertEquals(listOf(literal), idsOf(RecordFilter(keyword = "a_b")))
    }

    @Test
    fun `备注里的反斜杠不会把后面的字符吃掉`() = runTest {
        // 用户输入的反斜杠本身也要转义，否则它会把紧跟的字符变成转义序列
        val dao = db.recordDao()
        val withSlash = dao.insert(record(id = 1, note = """路径 C:\temp"""))
        dao.insert(record(id = 2, note = "路径 C:temp"))

        assertEquals(listOf(withSlash), idsOf(RecordFilter(keyword = """C:\t""")))
    }

    // —— 标签：OR 与 AND 的区别 ——

    @Test
    fun `标签默认是任一命中`() = runTest {
        val dao = db.recordDao()
        val tagDao = db.tagDao()
        val a = tagDao.insert(TagEntity(name = "A", createdAt = NOW))
        val b = tagDao.insert(TagEntity(name = "B", createdAt = NOW))

        val onlyA = dao.insert(record(id = 1))
        val both = dao.insert(record(id = 2))
        dao.linkTags(listOf(RecordTagCrossRef(onlyA, a)))
        dao.linkTags(listOf(RecordTagCrossRef(both, a), RecordTagCrossRef(both, b)))

        assertEquals(
            listOf(onlyA, both).sorted(),
            idsOf(RecordFilter(tagIds = setOf(a, b))),
        )
    }

    @Test
    fun `标签_AND_语义要求同时包含全部`() = runTest {
        // 用 IN 实现 AND 是最容易犯的错：它退化成 OR，筛选结果会多出一堆记录
        val dao = db.recordDao()
        val tagDao = db.tagDao()
        val a = tagDao.insert(TagEntity(name = "A", createdAt = NOW))
        val b = tagDao.insert(TagEntity(name = "B", createdAt = NOW))
        val c = tagDao.insert(TagEntity(name = "C", createdAt = NOW))

        val both = dao.insert(record(id = 1))
        val onlyA = dao.insert(record(id = 2))
        dao.linkTags(listOf(RecordTagCrossRef(both, a), RecordTagCrossRef(both, b)))
        dao.linkTags(listOf(RecordTagCrossRef(onlyA, a)))

        assertEquals(
            listOf(both),
            idsOf(RecordFilter(tagIds = setOf(a, b), tagMatchAll = true)),
        )
        // 三个标签要求全中，两条都不满足
        assertTrue(idsOf(RecordFilter(tagIds = setOf(a, b, c), tagMatchAll = true)).isEmpty())
    }

    // —— 坐标：SQL 侧必须和 resolveLocation 的结论一致 ——

    @Test
    fun `有坐标筛选把场景继承的坐标也算进去`() = runTest {
        // SQL 用 EXISTS 判断「记录自身有坐标 或 所属场景有坐标」，
        // 这套逻辑必须与 resolveLocation 逐一对应，否则筛选结果与列表上
        // 显示的坐标徽章会互相矛盾——同一个筛选条件，一眼看去有的记录「没有坐标」
        val dao = db.recordDao()
        val sceneDao = db.sceneDao()

        val withCoord = sceneDao.insert(sceneWithCoordinate(name = "有坐标场景"))
        val noCoord = sceneDao.insert(com.magnate.compass.scene(name = "无坐标场景"))

        val own = dao.insert(
            record(id = 1, latitude = 30.1, longitude = 120.2, locationAccuracy = 8f),
        )
        val inherited = dao.insert(record(id = 2, sceneId = withCoord))
        val sceneLacks = dao.insert(record(id = 3, sceneId = noCoord))
        val nothing = dao.insert(record(id = 4))

        assertEquals(
            listOf(own, inherited).sorted(),
            idsOf(RecordFilter(hasLocation = true)),
        )
        // 无坐标 = 其余全部，包含「场景存在但场景也没有坐标」的那条
        assertEquals(
            listOf(sceneLacks, nothing).sorted(),
            idsOf(RecordFilter(hasLocation = false)),
        )
    }

    @Test
    fun `精度阈值只作用于有坐标的记录_且缺失精度不通过`() = runTest {
        val dao = db.recordDao()

        val good = dao.insert(
            record(id = 1, latitude = 30.1, longitude = 120.2, locationAccuracy = 8f),
        )
        val coarse = dao.insert(
            record(id = 2, latitude = 30.3, longitude = 120.4, locationAccuracy = 200f),
        )
        // 有坐标但精度字段是 NULL：按「精度未知」处理，不满足任何阈值
        val unknown = dao.insert(record(id = 3, latitude = 30.5, longitude = 120.6))

        assertEquals(listOf(good), idsOf(RecordFilter(minLocationAccuracy = 50f)))
        assertEquals(
            listOf(coarse, good, unknown).sorted(),
            idsOf(RecordFilter(minLocationAccuracy = 1000f)),
        )
    }

    // —— 时间与磁场 ——

    @Test
    fun `时间区间是闭区间`() = runTest {
        val dao = db.recordDao()
        val early = dao.insert(record(id = 1, timestamp = 1000L))
        val mid = dao.insert(record(id = 2, timestamp = 2000L))
        val late = dao.insert(record(id = 3, timestamp = 3000L))

        assertEquals(
            listOf(mid),
            idsOf(RecordFilter(timeFrom = 2000L, timeTo = 2000L)),
        )
        assertEquals(listOf(early, mid).sorted(), idsOf(RecordFilter(timeFrom = 0L, timeTo = 2000L)))
        assertEquals(listOf(mid, late).sorted(), idsOf(RecordFilter(timeFrom = 2000L, timeTo = 9999L)))
    }

    @Test
    fun `磁场区间默认闭区间_开启排除后变成开区间`() = runTest {
        val dao = db.recordDao()
        val low = dao.insert(record(id = 1, magnitude = 40f))
        val edge = dao.insert(record(id = 2, magnitude = 50f))
        val high = dao.insert(record(id = 3, magnitude = 60f))

        assertEquals(
            listOf(edge, high).sorted(),
            idsOf(RecordFilter(magnitudeMin = 50f, magnitudeMax = 60f)),
        )
        // 排除下界后，正好等于 50 的那条被排除
        assertEquals(
            listOf(high),
            idsOf(RecordFilter(
                magnitudeMin = 50f,
                magnitudeMinExclusive = true,
                magnitudeMax = 60f,
            )),
        )
        assertTrue(low > 0)
    }

    // —— 排序 ——

    @Test
    fun `按磁场强度排序时不再是时间序`() = runTest {
        val dao = db.recordDao()
        dao.insert(record(id = 1, timestamp = 1000L, magnitude = 10f))
        dao.insert(record(id = 2, timestamp = 2000L, magnitude = 90f))
        dao.insert(record(id = 3, timestamp = 3000L, magnitude = 50f))

        val filter = RecordFilter(sortBy = SortField.MAGNITUDE, sortAsc = false)
        val byMagnitudeDesc = dao.search(query(filter)).first().map { it.record.id }

        assertEquals(listOf(2L, 3L, 1L), byMagnitudeDesc)
    }

    @Test
    fun `时间相同时按_id_倒序_保证顺序稳定`() = runTest {
        // 同一毫秒内的记录顺序若不稳定，分页与动画会抖动
        val dao = db.recordDao()
        dao.insert(record(id = 1, timestamp = 1000L))
        dao.insert(record(id = 2, timestamp = 1000L))
        dao.insert(record(id = 3, timestamp = 1000L))

        val ids = dao.search(query(RecordFilter())).first().map { it.record.id }
        assertEquals(listOf(3L, 2L, 1L), ids)
    }

    // —— 计数与实际结果必须一致 ——

    @Test
    fun `计数查询与结果条数在各种筛选下都一致`() = runTest {
        // 筛选面板底部的「N 条结果」用的就是 countSearch。它和列表走两套 SQL，
        // 一旦 WHERE 子句不同步，用户会看到「显示 5 条」但列表只有 2 条
        val dao = db.recordDao()
        val tagDao = db.tagDao()
        val sceneDao = db.sceneDao()
        val tagId = tagDao.insert(TagEntity(name = "A", createdAt = NOW))
        val sceneId = sceneDao.insert(sceneWithCoordinate(name = "场景"))

        val a = dao.insert(record(id = 1, timestamp = 1000L, magnitude = 40f, note = "x"))
        val b = dao.insert(record(id = 2, timestamp = 2000L, magnitude = 60f, sceneId = sceneId))
        dao.insert(record(id = 3, timestamp = 3000L, magnitude = 80f))
        dao.linkTags(listOf(RecordTagCrossRef(a, tagId)))
        assertTrue(b > 0)

        val filters = listOf(
            RecordFilter(),
            RecordFilter(keyword = "x"),
            RecordFilter(tagIds = setOf(tagId)),
            RecordFilter(tagIds = setOf(tagId), tagMatchAll = true),
            RecordFilter(sceneIds = setOf(sceneId)),
            RecordFilter(timeFrom = 1500L, timeTo = 3500L),
            RecordFilter(magnitudeMin = 50f),
            RecordFilter(hasLocation = true),
            RecordFilter(hasLocation = false),
            RecordFilter(keyword = "x", tagIds = setOf(tagId), timeFrom = 500L),
        )

        filters.forEach { filter ->
            val count = dao.countSearch(countQuery(filter)).first()
            val rows = dao.search(query(filter)).first().size
            assertEquals("筛选 $filter 的计数与结果条数不一致", rows, count)
        }
    }

    // —— 关系补齐 ——

    @Test
    fun `查询结果带回标签与场景`() = runTest {
        val dao = db.recordDao()
        val tagDao = db.tagDao()
        val sceneDao = db.sceneDao()
        val tagId = tagDao.insert(TagEntity(name = "A", createdAt = NOW))
        val sceneId = sceneDao.insert(sceneWithCoordinate(name = "场景"))

        val id = dao.insert(record(sceneId = sceneId))
        dao.linkTags(listOf(RecordTagCrossRef(id, tagId)))

        val loaded = dao.search(query(RecordFilter())).first().single()
        assertEquals(listOf("A"), loaded.tags.map { it.name })
        assertEquals("场景", loaded.scene?.name)
    }

    @Test
    fun `无场景记录的_scene_关系是_null_而不是空对象`() = runTest {
        // 若这里返回一个 name 为空的 SceneEntity，界面会显示成「当前场景：」，
        // 用户会以为记录挂在一个坏掉的场景上
        val dao = db.recordDao()
        dao.insert(record())
        assertNull(dao.search(query(RecordFilter())).first().single().scene)
    }

    // —— 坐标回填与固化（P2）——

    @Test
    fun `从记录导入坐标时只认记录自身的实测坐标`() = runTest {
        // 若把「场景继承来的坐标」也当成来源，A 场景导入 B 场景的坐标后，
        // B 改坐标会连带改变 A——引用环
        val dao = db.recordDao()
        val sceneDao = db.sceneDao()
        val sceneId = sceneDao.insert(sceneWithCoordinate(name = "场景"))

        val own = dao.insert(
            record(id = 1, timestamp = 3000L, latitude = 30.1, longitude = 120.2, locationAccuracy = 8f),
        )
        dao.insert(record(id = 2, timestamp = 2000L, sceneId = sceneId))
        dao.insert(record(id = 3, timestamp = 1000L))

        assertEquals(listOf(own), dao.observeRecentlyLocated(10).first().map { it.record.id })
    }

    @Test
    fun `固化场景坐标会写入该场景下的全部记录`() = runTest {
        val dao = db.recordDao()
        val sceneDao = db.sceneDao()
        val sceneId = sceneDao.insert(sceneWithCoordinate(name = "场景"))

        val a = dao.insert(record(id = 1, sceneId = sceneId))
        val b = dao.insert(record(id = 2, sceneId = sceneId))
        val untouched = dao.insert(record(id = 3))

        dao.freezeSceneLocationIntoRecords(
            sceneId = sceneId,
            lat = 30.5,
            lon = 120.5,
            alt = null,
            acc = 9f,
            provider = "gps",
            at = NOW,
        )

        listOf(a, b).forEach { id ->
            val r = dao.getRecord(id)!!
            assertEquals(30.5, r.latitude!!, 1e-9)
            assertEquals(120.5, r.longitude!!, 1e-9)
            assertEquals(9f, r.locationAccuracy!!, 1e-6f)
        }
        assertNull("其他场景/无场景的记录不该被改", dao.getRecord(untouched)!!.latitude)
    }
}
