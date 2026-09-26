package com.magnate.compass.data

/**
 * 动态 SQL 的构造。**纯函数**，可在 JVM 上直接单测（design.md §8）。
 *
 * 本文件与 [resolveLocation] 是坐标语义的 SQL 侧与 Kotlin 侧两套实现，
 * **必须由同一组用例交叉验证两者结论一致**——尤其「有坐标」筛选。
 *
 * 防注入：任何用户输入都走绑定参数；唯一被字符串拼接的是排序字段名与方向，
 * 且都来自枚举白名单，不可能是用户输入（design.md §5.10）。
 */
object RecordQueryBuilder {

    /** LIKE 的转义符。用户输入的 `%` `_` 必须转义，否则会被当成通配符。 */
    private const val ESCAPE_CHAR = '\\'

    private const val FROM_CLAUSE = "FROM records r LEFT JOIN scenes s ON s.id = r.sceneId"

    data class BuiltQuery(val sql: String, val args: List<Any?>)

    /** 结果集查询：`SELECT r.*` 以便 Room 映射 [RecordWithRelations]。 */
    fun build(filter: RecordFilter): BuiltQuery {
        val (where, args) = buildWhere(filter)
        val column = sortColumnOf(filter.sortBy)
        val direction = if (filter.sortAsc) "ASC" else "DESC"
        val sql = buildString {
            append("SELECT r.* ").append(FROM_CLAUSE)
            append(" WHERE 1 = 1").append(where)
            // r.id 作为次级排序键：同一时刻或同一强度的记录之间顺序稳定，列表不会无故跳动
            append(" ORDER BY ").append(column).append(' ').append(direction)
            append(", r.id ").append(direction)
        }
        return BuiltQuery(sql, args)
    }

    /** 计数查询：供筛选面板底部的「查看 N 条结果」实时回显。 */
    fun buildCount(filter: RecordFilter): BuiltQuery {
        val (where, args) = buildWhere(filter)
        return BuiltQuery("SELECT COUNT(*) $FROM_CLAUSE WHERE 1 = 1$where", args)
    }

    private fun sortColumnOf(sortBy: SortField): String = when (sortBy) {
        SortField.TIME -> "r.timestamp"
        SortField.MAGNITUDE -> "r.magnitude"
    }

    private fun buildWhere(filter: RecordFilter): Pair<String, List<Any?>> {
        val sql = StringBuilder()
        val args = mutableListOf<Any?>()

        // —— 关键词：备注 / 标签名 / 场景名，任一命中（OR）——
        filter.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { keyword ->
            val pattern = "%${escapeLike(keyword)}%"
            sql.append(
                """
                |AND (r.note LIKE ? ESCAPE '\'
                |  OR EXISTS (SELECT 1 FROM record_tags rt2 JOIN tags t ON t.id = rt2.tagId
                |             WHERE rt2.recordId = r.id AND t.name LIKE ? ESCAPE '\')
                |  OR (s.name IS NOT NULL AND s.name LIKE ? ESCAPE '\'))
                """.trimMargin()
            )
            repeat(3) { args += pattern }
        }

        // —— 标签：AND 语义要求「同时包含全部」，不能简单用 IN ——
        if (filter.tagIds.isNotEmpty()) {
            val ids = filter.tagIds.sorted()
            val placeholders = ids.joinToString(", ") { "?" }
            sql.append(
                " AND (SELECT COUNT(DISTINCT rt.tagId) FROM record_tags rt" +
                    " WHERE rt.recordId = r.id AND rt.tagId IN ($placeholders))"
            )
            args.addAll(ids)
            if (filter.tagMatchAll) {
                sql.append(" = ?")
                // 显式转 Long：当前走 SimpleSQLiteQuery，它能识别 Integer；
                // 但 Room 为 `List<Any?>` 生成的绑定器只认 Long/Double/String/byte[]，
                // 遇到 Integer 会悄悄回退成 bindString，`COUNT(*) = '3'` 恒不成立。
                // 保持参数类型同质，换绑定路径时不会静默失效。
                args += ids.size.toLong()
            } else {
                sql.append(" > 0")
            }
        }

        // —— 场景：多选 OR。一条记录至多属于一个场景 ——
        if (filter.sceneIds.isNotEmpty()) {
            val ids = filter.sceneIds.sorted()
            sql.append(" AND r.sceneId IN (${ids.joinToString(", ") { "?" }})")
            args.addAll(ids)
        }

        filter.timeFrom?.let { sql.append(" AND r.timestamp >= ?"); args += it }
        filter.timeTo?.let { sql.append(" AND r.timestamp <= ?"); args += it }
        filter.magnitudeMin?.let {
            sql.append(if (filter.magnitudeMinExclusive) " AND r.magnitude > ?" else " AND r.magnitude >= ?")
            args += it
        }
        filter.magnitudeMax?.let {
            sql.append(if (filter.magnitudeMaxExclusive) " AND r.magnitude < ?" else " AND r.magnitude <= ?")
            args += it
        }

        // —— 有无坐标 ——
        // 这是场景机制引入的最大一致性陷阱：任何「记录有没有坐标」的判断都不能只看 records 表，
        // 必须同时考虑场景继承。FIXED 场景内的记录自身 latitude 为 null，但解析后有坐标。
        filter.hasLocation?.let { hasLocation ->
            sql.append(
                if (hasLocation) {
                    " AND (r.latitude IS NOT NULL" +
                        " OR (r.sceneId IS NOT NULL AND s.latitude IS NOT NULL))"
                } else {
                    " AND (r.latitude IS NULL" +
                        " AND (r.sceneId IS NULL OR s.latitude IS NULL))"
                }
            )
        }

        // —— 精度门槛：作用于「解析后」的那个坐标，与 resolveLocation 的选择顺序一致 ——
        filter.minLocationAccuracy?.let { threshold ->
            sql.append(
                """
                |AND (CASE
                |       WHEN r.latitude IS NOT NULL THEN COALESCE(r.locationAccuracy, ?)
                |       WHEN r.sceneId IS NOT NULL AND s.latitude IS NOT NULL
                |            THEN COALESCE(s.locationAccuracy, ?)
                |       ELSE ?
                |     END) <= ?
                """.trimMargin()
            )
            repeat(3) { args += Float.MAX_VALUE }
            args += threshold
        }

        return sql.toString() to args
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * 绑定参数挡住了注入，但挡不住语义——用户输入 `%` 时若不转义，
     * `LIKE '%%%'` 会匹配全部记录，看起来像「搜索失效」。
     */
    fun escapeLike(raw: String): String = buildString(raw.length) {
        raw.forEach { c ->
            when (c) {
                ESCAPE_CHAR, '%', '_' -> {
                    append(ESCAPE_CHAR)
                    append(c)
                }

                else -> append(c)
            }
        }
    }
}
