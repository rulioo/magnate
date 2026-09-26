package com.magnate.compass.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签调色板的容量，约束 [TagEntity.colorIndex] 的合法取值范围。
 *
 * 常量放在数据层（而非 `ui.theme`）是因为它是一条**数据约束**；
 * 具体色值属于 UI，见 `ui/theme/Color.kt` 的 `tagColor()`。
 */
const val TAG_PALETTE_SIZE = 8

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    /** 0–7 调色板索引。**不存 hex**，便于主题适配（design-gui.md §12.2）。 */
    val colorIndex: Int = 0,
    val createdAt: Long,
)

/**
 * 记录与标签的多对多关联。
 *
 * 用 CASCADE：标签关联是纯附属数据，标签没了关联自然失效；
 * 与 `records.sceneId` 的 SET_NULL 形成对照（design.md §5.4）。
 */
@Entity(
    tableName = "record_tags",
    primaryKeys = ["recordId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecordTagCrossRef(
    val recordId: Long,
    val tagId: Long,
)
