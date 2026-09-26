package com.magnate.compass.data

enum class SortField {
    TIME,
    MAGNITUDE,
}

/**
 * 搜索与筛选条件（design.md §5.10）。
 *
 * 语义规则：关键词与筛选条件是 **AND**，筛选条件之间也是 **AND**，
 * 只有标签之间可选 AND/OR（[tagMatchAll]）。场景条件是多选 OR——
 * 一条记录至多属于一个场景，选中多个场景即「属于其中任一个」。
 */
data class RecordFilter(
    /** 匹配备注 / 标签名 / 场景名，任一命中即可 */
    val keyword: String? = null,

    val tagIds: Set<Long> = emptySet(),

    /** true = 同时包含全部（AND），false = 任一包含（OR） */
    val tagMatchAll: Boolean = true,

    /** 场景筛选，OR 语义 */
    val sceneIds: Set<Long> = emptySet(),

    val timeFrom: Long? = null,
    val timeTo: Long? = null,

    val magnitudeMin: Float? = null,
    val magnitudeMax: Float? = null,

    /**
     * 区间端点是否**排除**等值。
     *
     * 存在的理由只有一个：筛选面板上那三个预设 chip 写的是 `< 25` / `25–65` / `> 65`，
     * 它们两两相邻、边界重合。若端点一律取闭，恰好等于 25.0 的读数会同时落进
     * 「偏低」和「正常」两个预设——用户点哪个都合理，看起来像筛选器坏了。
     * 一个数值区间本来就有两个独立的开闭属性，如实建模比事后找补便宜。
     */
    val magnitudeMinExclusive: Boolean = false,
    val magnitudeMaxExclusive: Boolean = false,

    /**
     * 是否解析出有效坐标。
     *
     * **判定必须包含场景继承**：FIXED 场景内的记录自身无坐标，但解析后有坐标，必须命中。
     */
    val hasLocation: Boolean? = null,

    /** 只保留精度优于 N 米的记录。同时作用于本机实测坐标与场景坐标。 */
    val minLocationAccuracy: Float? = null,

    val sortBy: SortField = SortField.TIME,
    val sortAsc: Boolean = false,
) {
    /** 是否设置了任何「会缩小结果集」的条件，用于区分两种空状态。 */
    val hasActiveFilters: Boolean
        get() = !keyword.isNullOrBlank() ||
            tagIds.isNotEmpty() ||
            sceneIds.isNotEmpty() ||
            timeFrom != null ||
            timeTo != null ||
            magnitudeMin != null ||
            magnitudeMax != null ||
            hasLocation != null ||
            minLocationAccuracy != null
}
