package com.magnate.compass.ui.records

import com.magnate.compass.data.RecordWithRelations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 列表的一行：要么是日期分组头，要么是一条记录。 */
sealed interface RecordListEntry {

    val key: String

    data class DayHeader(val label: String, override val key: String) : RecordListEntry

    data class Item(val record: RecordWithRelations) : RecordListEntry {
        override val key: String = "record-${record.record.id}"
    }
}

private val DATE_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * 按日历日分组，供 `LazyColumn` 的 sticky header 使用（design-gui.md §6.4）。
 *
 * 纯函数，可 JVM 单测。
 *
 * 两个刻意的选择：
 *
 * 1. **按日历日而非 24 小时差**。凌晨 1 点看昨晚 23 点的记录，应该显示「昨天」，
 *    而不是被算成「今天」——用户脑子里的「天」是日历，不是滑动窗口。
 * 2. **分组的先后顺序沿用记录自身的顺序**（首次出现的日期先排）。
 *    按时间排序时它自然是倒序；按磁场强度排序时，日期会按「该日最强的那条」
 *    的排名出现——这正是用户在按强度浏览时期望看到的顺序。
 */
fun groupRecordsByDay(
    records: List<RecordWithRelations>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordListEntry> {
    if (records.isEmpty()) return emptyList()

    val today = toLocalDate(nowMillis, zone)
    val buckets = LinkedHashMap<LocalDate, MutableList<RecordListEntry.Item>>()

    records.forEach { record ->
        val date = toLocalDate(record.record.timestamp, zone)
        buckets.getOrPut(date) { mutableListOf() }.add(RecordListEntry.Item(record))
    }

    val entries = ArrayList<RecordListEntry>(records.size + buckets.size)
    buckets.forEach { (date, items) ->
        val label = when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> DATE_KEY.format(date)
        }
        entries += RecordListEntry.DayHeader(label = label, key = DATE_KEY.format(date))
        entries += items
    }
    return entries
}

private fun toLocalDate(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
