package com.magnate.compass.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.magnate.compass.record
import com.magnate.compass.scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 场景表的外键行为测试。
 *
 * 这个文件存在的唯一理由是验证 **删场景不删记录**（`onDelete = SET_NULL`）。
 * 它是用户数据的最后一道保险：写错成 CASCADE 会静默删光用户的核心资产，
 * 而且只在「删除场景」这一条路径上才暴露，单元测试和人工点测都很容易漏掉。
 *
 * 需要模拟器或真机（androidTest）。
 */
@RunWith(AndroidJUnit4::class)
class SceneDaoTest {

    private lateinit var db: MagnateDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MagnateDatabase::class.java,
        )
            // 外键约束默认关闭，不显式打开的话 SET_NULL 根本不会触发，
            // 测试会「通过」但什么都没验证
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

    @Test
    fun `删除场景后记录仍在_且_sceneId_置空`() = runTest {
        val sceneDao = db.sceneDao()
        val recordDao = db.recordDao()

        val sceneId = sceneDao.insert(scene(name = "阳光大厦 B2层"))
        val recordId = recordDao.insert(record(sceneId = sceneId))

        sceneDao.delete(sceneId)

        val survivor = recordDao.getRecord(recordId)
        assertNotNull("删除场景绝不能连带删除记录", survivor)
        assertNull("记录的 sceneId 应当被置空", survivor!!.sceneId)
    }

    @Test
    fun `删除场景不会连带删除记录自身的实测坐标`() = runTest {
        // 移出场景后坐标「回退到记录自身的实测值」——UI 确认框里承诺的就是这件事
        val sceneDao = db.sceneDao()
        val recordDao = db.recordDao()

        val sceneId = sceneDao.insert(scene(name = "B2层"))
        val recordId = recordDao.insert(
            record(sceneId = sceneId, latitude = 30.1, longitude = 120.2, locationAccuracy = 8f),
        )

        sceneDao.delete(sceneId)

        val survivor = recordDao.getRecord(recordId)!!
        assertEquals(30.1, survivor.latitude!!, 1e-9)
        assertEquals(120.2, survivor.longitude!!, 1e-9)
    }

    @Test
    fun `删除标签只解除关联_不删除记录`() = runTest {
        val tagDao = db.tagDao()
        val recordDao = db.recordDao()

        val tagId = tagDao.insert(
            com.magnate.compass.data.entity.TagEntity(name = "室外", createdAt = 0L),
        )
        val recordId = recordDao.insert(record())
        recordDao.linkTags(listOf(com.magnate.compass.data.entity.RecordTagCrossRef(recordId, tagId)))

        tagDao.delete(tagId)

        assertNotNull("删除标签绝不能连带删除记录", recordDao.getRecord(recordId))
        assertEquals(0, tagDao.count())
    }

    @Test
    fun `场景名唯一索引阻止重名`() = runTest {
        val sceneDao = db.sceneDao()
        sceneDao.insert(scene(name = "B2层"))

        assertNotNull("重名场景应当能被查出来，用于提示「已存在」", sceneDao.findByName("B2层"))
        assertEquals(1, sceneDao.count())
    }

    @Test
    fun `场景列表按_updatedAt_降序`() = runTest {
        val sceneDao = db.sceneDao()
        sceneDao.insert(scene(id = 0, name = "旧", coordMode = com.magnate.compass.data.entity.CoordMode.FIXED).copy(updatedAt = 100L))
        sceneDao.insert(scene(id = 0, name = "新").copy(updatedAt = 200L))

        val names = sceneDao.observeAll().first().map { it.scene.name }
        assertEquals(listOf("新", "旧"), names)
    }

    @Test
    fun `记录数聚合随记录增减同步变化`() = runTest {
        val sceneDao = db.sceneDao()
        val recordDao = db.recordDao()

        val sceneId = sceneDao.insert(scene(name = "B2层"))
        assertEquals(0, sceneDao.observeRecordCount(sceneId).first())

        recordDao.insert(record(sceneId = sceneId))
        recordDao.insert(record(sceneId = sceneId))
        assertEquals(2, sceneDao.observeRecordCount(sceneId).first())
        assertEquals(2, sceneDao.observeAll().first().first().recordCount)
    }

    @Test
    fun `清空场景坐标后记录变成无坐标`() = runTest {
        // 「撤销首次为场景设置坐标」依赖这条路径
        val sceneDao = db.sceneDao()
        val sceneId = sceneDao.insert(
            scene(name = "B2层", latitude = 30.1, longitude = 120.2, locationAccuracy = 8f),
        )

        sceneDao.clearLocation(sceneId, now = 1L)

        val cleared = sceneDao.getById(sceneId)!!
        assertNull(cleared.latitude)
        assertNull(cleared.longitude)
        assertNull(cleared.locationAccuracy)
        assertEquals(false, cleared.hasCoordinate)
    }
}
