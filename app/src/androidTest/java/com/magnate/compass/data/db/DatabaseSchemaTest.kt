package com.magnate.compass.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库结构与删除语义的「护栏」测试。
 *
 * 这些断言在功能上看起来是重复的——`SceneDaoTest` 已经验过「删场景不删记录」了。
 * 但它们验的是**不同的东西**：那个测的是当下这一次的行为，这里测的是**声明本身**。
 *
 * 差别在于失败模式。若有人把 `onDelete = SET_NULL` 改成 `CASCADE`：
 * - 行为测试只在「删场景」这条路径上失败，改动者若只跑了他关心的用例，是看不到的；
 * - 这里直接读 `PRAGMA foreign_key_list`，一改就红，且报错信息直指被改的那一行。
 *
 * 用户记录是这个应用里唯一不可再生的资产，值得多一道这样的锁。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {

    private lateinit var db: MagnateDatabase

    @Before
    fun setUp() {
        // 刻意用 create()，而不是 inMemoryDatabaseBuilder：
        // 要测的正是生产构建路径上的行为（外键 PRAGMA、迁移列表），
        // 测试自己搭一个 builder 就绕开了被测对象
        db = MagnateDatabase.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase("magnate.db")
    }

    /** `PRAGMA foreign_key_list(<表>)` 的每一行：(子表列, 引用表, 删除动作)。 */
    private fun foreignKeysOf(table: String): List<Triple<String, String, String>> {
        val cursor = db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(`$table`)")
        return cursor.use {
            val from = it.getColumnIndexOrThrow("from")
            val refTable = it.getColumnIndexOrThrow("table")
            val onDelete = it.getColumnIndexOrThrow("on_delete")
            buildList {
                while (it.moveToNext()) {
                    add(Triple(it.getString(from), it.getString(refTable), it.getString(onDelete)))
                }
            }
        }
    }

    @Test
    fun `外键约束是打开的`() {
        // 不开的话 SET_NULL 与 CASCADE 全都不会触发，
        // 删除场景会留下指向不存在场景的悬空 sceneId——
        // 界面表现为「该记录属于一个不存在的场景」，且再也删不掉
        val cursor = db.openHelper.readableDatabase.query("PRAGMA foreign_keys")
        val enabled = cursor.use { it.moveToFirst() && it.getInt(0) == 1 }
        assertTrue("PRAGMA foreign_keys 必须为 ON", enabled)
    }

    @Test
    fun `删除场景时记录转为无场景_而不是被删除`() {
        val fks = foreignKeysOf("records")
        assertEquals(
            "records.sceneId 的删除动作必须是 SET NULL",
            listOf(Triple("sceneId", "scenes", "SET NULL")),
            fks,
        )
    }

    @Test
    fun `删除标签只清理关联表`() {
        val fks = foreignKeysOf("record_tags")
        // 两张外键都是 CASCADE，但删的是关联行，不是记录本身
        assertEquals(
            setOf(
                Triple("recordId", "records", "CASCADE"),
                Triple("tagId", "tags", "CASCADE"),
            ),
            fks.toSet(),
        )
        // 反过来说：records 表上不该有任何指向 tags 的外键
        assertTrue(
            "记录不该直接引用标签——多对多必须走关联表",
            foreignKeysOf("records").none { it.second == "tags" },
        )
    }

    @Test
    fun `标签与场景名有唯一索引`() {
        val indicesOf = { table: String ->
            db.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                val unique = c.getColumnIndexOrThrow("unique")
                buildList { while (c.moveToNext()) if (c.getInt(unique) == 1) add(c.getString(name)) }
            }
        }

        assertTrue("scenes.name 需要唯一索引", indicesOf("scenes").isNotEmpty())
        assertTrue("tags.name 需要唯一索引", indicesOf("tags").isNotEmpty())
    }

    @Test
    fun `查询用到的列都有索引`() {
        // 记录列表按时间倒序、按强度排序、按场景聚合，三条路径都直接扫这几列。
        // 记录数上千后缺索引会明显卡顿，而这种退化在开发期的小数据集上完全看不出来
        val indexed = db.openHelper.readableDatabase.query("PRAGMA index_list(`records`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }
        val coveredColumns = indexed.flatMap { indexName ->
            db.openHelper.readableDatabase.query("PRAGMA index_info(`$indexName`)").use { c ->
                val col = c.getColumnIndexOrThrow("name")
                buildList { while (c.moveToNext()) add(c.getString(col)) }
            }
        }.toSet()

        listOf("timestamp", "magnitude", "createdAt", "sceneId").forEach { column ->
            assertTrue("records.$column 缺少索引", column in coveredColumns)
        }
    }

    @Test
    fun `数据库版本为_1_且迁移列表为空`() {
        // 这条断言是给未来的改动者的：提升版本号时它会红，
        // 提醒你去 Migrations.kt 里补一个显式迁移，而不是顺手加上
        // fallbackToDestructiveMigration() 把用户的记录删掉
        assertEquals(1, db.openHelper.readableDatabase.version)
        assertEquals(
            "v1 是首个版本，迁移列表应为空；若已升版本，请在此更新预期",
            0,
            MIGRATIONS.size,
        )
    }
}
