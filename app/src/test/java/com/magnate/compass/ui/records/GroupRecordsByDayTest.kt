package com.magnate.compass.ui.records

import com.magnate.compass.NOW
import com.magnate.compass.TEST_ZONE
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GroupRecordsByDayTest {

    private fun millisOf(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(TEST_ZONE).toInstant().toEpochMilli()

    private fun at(date: LocalDate, hour: Int, id: Long): RecordWithRelations =
        RecordWithRelations(
            record = record(id = id, timestamp = millisOf(date, hour)),
            tags = emptyList(),
            scene = null,
        )

    private val today = LocalDate.of(2024, 3, 5)
    private val yesterday = LocalDate.of(2024, 3, 4)

    @Test
    fun `空列表返回空结果`() {
        assertTrue(groupRecordsByDay(emptyList(), NOW, TEST_ZONE).isEmpty())
    }

    @Test
    fun `同一天的记录共用一个分组头`() {
        val entries = groupRecordsByDay(
            listOf(at(today, 14, 1), at(today, 9, 2)),
            NOW,
            TEST_ZONE,
        )

        assertEquals(3, entries.size)
        assertEquals(RecordListEntry.DayHeader::class, entries[0]::class)
        assertEquals(RecordListEntry.Item::class, entries[1]::class)
        assertEquals(RecordListEntry.Item::class, entries[2]::class)
    }

    @Test
    fun `分组头文案是今天与昨天`() {
        val entries = groupRecordsByDay(
            listOf(at(today, 14, 1), at(yesterday, 14, 2), at(LocalDate.of(2024, 3, 2), 14, 3)),
            NOW,
            TEST_ZONE,
        )

        val headers = entries.filterIsInstance<RecordListEntry.DayHeader>().map { it.label }
        assertEquals(listOf("今天", "昨天", "2024-03-02"), headers)
    }

    @Test
    fun `分组顺序沿用记录自身的顺序`() {
        // 按时间排序时自然倒序；按磁场强度排序时日期会按「该日最强那条」的排名出现，
        // 这正是用户按强度浏览时期望的顺序
        val entries = groupRecordsByDay(
            listOf(at(yesterday, 14, 1), at(today, 14, 2)),
            NOW,
            TEST_ZONE,
        )

        val headers = entries.filterIsInstance<RecordListEntry.DayHeader>().map { it.label }
        assertEquals(listOf("昨天", "今天"), headers)
    }

    @Test
    fun `同一日期即使不相邻也只出现一个分组头`() {
        // 按强度排序时同一天的记录很可能被拆散，分组头不能重复出现
        val entries = groupRecordsByDay(
            listOf(at(today, 9, 1), at(yesterday, 12, 2), at(today, 20, 3)),
            NOW,
            TEST_ZONE,
        )

        val headers = entries.filterIsInstance<RecordListEntry.DayHeader>()
        assertEquals("同一天只应有一个分组头", 2, headers.size)
        assertEquals(1, headers.count { it.label == "今天" })
    }

    @Test
    fun `分组头紧邻它所属的记录`() {
        val entries = groupRecordsByDay(
            listOf(at(today, 9, 1), at(yesterday, 12, 2), at(today, 20, 3)),
            NOW,
            TEST_ZONE,
        )

        // today 的两条是 entries[0] 头之后的连续两条（按首次出现顺序，today 在前）
        assertTrue(entries[0] is RecordListEntry.DayHeader)
        assertTrue(entries[1] is RecordListEntry.Item)
        assertTrue(entries[2] is RecordListEntry.Item)
        assertTrue(entries[3] is RecordListEntry.DayHeader)

        val todayItems = listOf(entries[1], entries[2]).filterIsInstance<RecordListEntry.Item>()
        todayItems.forEach {
            assertEquals(LocalDate.of(2024, 3, 5), dateOf(it.record.record.timestamp))
        }
    }

    @Test
    fun `分组头与记录的 key 都唯一`() {
        // LazyColumn 的 key 重复会直接抛异常
        val entries = groupRecordsByDay(
            listOf(
                at(today, 9, 1),
                at(today, 20, 2),
                at(yesterday, 12, 3),
                at(LocalDate.of(2024, 3, 2), 12, 4),
            ),
            NOW,
            TEST_ZONE,
        )

        val keys = entries.map { it.key }
        assertEquals("存在重复的 key: $keys", keys.size, keys.toSet().size)
        assertEquals("record-1", entries[1].key)
    }

    @Test
    fun `凌晨的记录归到它所属的日历日`() {
        // 凌晨 1 点看昨晚 23 点的记录，应分在「昨天」而不是「今天」
        val entries = groupRecordsByDay(
            listOf(at(today, 1, 1), at(yesterday, 23, 2)),
            millisOf(today, 1),
            TEST_ZONE,
        )

        val headers = entries.filterIsInstance<RecordListEntry.DayHeader>().map { it.label }
        assertEquals(listOf("今天", "昨天"), headers)
    }

    @Test
    fun `每条记录都恰好被输出一次`() {
        val records = (1L..20L).map { id ->
            at(LocalDate.of(2024, 3, ((id % 5) + 1).toInt()), hour = (id % 24).toInt(), id = id)
        }
        val entries = groupRecordsByDay(records, NOW, TEST_ZONE)

        val items = entries.filterIsInstance<RecordListEntry.Item>()
        assertEquals(records.size, items.size)
        assertEquals(records.map { it.record.id }.toSet(), items.map { it.record.record.id }.toSet())
    }

    private fun dateOf(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(TEST_ZONE).toLocalDate()
}
