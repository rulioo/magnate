package com.magnate.compass.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.RecordTagCrossRef
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.data.entity.TagEntity

/**
 * 记录 + 标签 + 归属场景的聚合读取模型。
 *
 * 这是一个纯 Room POJO：只有直连的列与关系，**不含任何派生逻辑**。
 * 有效坐标由 [location] 扩展属性在类外解析（原因见那里）。
 */
data class RecordWithRelations(
    @Embedded val record: RecordEntity,

    /**
     * 多对多标签。
     *
     * `@Relation.entityColumn` 指的是 **TagEntity 的主键**（`id`），
     * 中间表 record_tags 的两个列名写在 [Junction] 里——
     * 把 `recordId` 写到 entityColumn 上，Room 会去 TagEntity 里找这一列而报错。
     */
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecordTagCrossRef::class,
            parentColumn = "recordId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,

    @Relation(parentColumn = "sceneId", entityColumn = "id")
    val scene: SceneEntity?,
)

/**
 * 解析后的有效坐标及其来源。UI 一律用它，不得自行拼装。
 *
 * **写成扩展属性而不是成员属性**：Room 会把 POJO 的每个成员属性都当成待映射的列，
 * 一个带自定义 getter、没有 backing field 的成员会让 KSP 直接报
 * 「Cannot find setter for field」。声明在类外，Room 的处理器就看不见它了。
 *
 * 坐标为 null 时不做任何缓存——场景坐标被修改后，所有引用它的记录立刻呈现新坐标。
 */
val RecordWithRelations.location: EffectiveLocation
    get() = resolveLocation(record, scene)

data class SceneWithCount(
    @Embedded val scene: SceneEntity,
    val recordCount: Int,
)

data class TagWithCount(
    @Embedded val tag: TagEntity,
    val recordCount: Int,
)
