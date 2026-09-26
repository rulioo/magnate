package com.magnate.compass.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.magnate.compass.data.db.MagnateDatabase
import com.magnate.compass.data.db.RecordDao
import com.magnate.compass.data.db.SceneDao
import com.magnate.compass.data.db.TagDao
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.RecordTagCrossRef
import com.magnate.compass.data.entity.TAG_PALETTE_SIZE
import com.magnate.compass.data.entity.TagEntity
import com.magnate.compass.location.GeoPoint
import kotlinx.coroutines.flow.Flow

/**
 * 记录的唯一写入入口。
 *
 * 负责把**传感器快照 + 坐标解析 + 场景归属 + 标签**合成一条完整记录。
 * **UI 不得自行拼装记录对象，也不得自行解析坐标来源**（design.md §5.1）。
 */
class RecordRepository(
    private val db: MagnateDatabase,
    private val recordDao: RecordDao,
    private val tagDao: TagDao,
    private val sceneDao: SceneDao,
) {

    // ————————————————————— 读取 —————————————————————

    fun observeRecords(filter: RecordFilter): Flow<List<RecordWithRelations>> =
        recordDao.search(RecordQueryBuilder.build(filter).toQuery())

    /** 筛选面板底部的「查看 N 条结果」。 */
    fun observeMatchCount(filter: RecordFilter): Flow<Int> =
        recordDao.countSearch(RecordQueryBuilder.buildCount(filter).toQuery())

    fun observeCount(): Flow<Int> = recordDao.observeCount()

    fun observeById(id: Long): Flow<RecordWithRelations?> = recordDao.observeById(id)

    /** 供「从记录导入坐标」使用的候选：只取**自身有实测坐标**的记录。 */
    fun observeRecentlyLocated(limit: Int = 20): Flow<List<RecordWithRelations>> =
        recordDao.observeRecentlyLocated(limit)

    suspend fun getById(id: Long): RecordWithRelations? = recordDao.getById(id)

    // ————————————————————— 写入 —————————————————————

    /**
     * 保存一条记录（含标签与场景归属），**单事务**完成（design.md §5.10）。
     *
     * 坐标的落库与否由 [ownLocationToPersist] 决定：FIXED 场景且场景有坐标时
     * 记录不存自己的坐标，读取时从场景解析——这是引用语义的前提。
     */
    suspend fun save(request: SaveRecordRequest): SaveOutcome {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val scene = request.sceneId?.let { sceneDao.getById(it) }
            val persisted = ownLocationToPersist(scene, request.measuredLocation)
            val draft = request.draft

            val record = RecordEntity(
                timestamp = draft.timestamp,
                createdAt = now,
                azimuthDeg = draft.azimuthDeg,
                pitchDeg = draft.pitchDeg,
                rollDeg = draft.rollDeg,
                magX = draft.magX,
                magY = draft.magY,
                magZ = draft.magZ,
                magnitude = draft.magnitude,
                sensorAccuracy = draft.sensorAccuracy,
                isTrueNorth = draft.isTrueNorth,
                declination = draft.declination,
                sceneId = scene?.id,
                latitude = persisted?.latitude,
                longitude = persisted?.longitude,
                altitude = persisted?.altitude,
                locationAccuracy = persisted?.accuracyMeters,
                locationProvider = persisted?.provider,
                locatedAt = persisted?.locatedAt,
                note = request.note?.trim()?.takeIf { it.isNotEmpty() },
            )

            val id = recordDao.insert(record)
            linkTagsByName(id, request.tagNames, now)

            SaveOutcome(
                recordId = id,
                effectiveLocation = resolveLocation(record.copy(id = id), scene),
            )
        }
    }

    /** 备注是测量值之外少数可编辑的字段之一。 */
    suspend fun updateNote(id: Long, note: String?) {
        recordDao.updateNote(id, note?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** 详情页整体替换标签集合。 */
    suspend fun setTags(recordId: Long, tagNames: List<String>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            recordDao.unlinkAllTags(recordId)
            linkTagsByName(recordId, tagNames, now)
        }
    }

    /**
     * 移出场景（`sceneId` 置 null）。
     *
     * 坐标不动：记录自身若有实测坐标则回退到它，若没有则自然变成无坐标。
     * 调用方必须先在确认框里说明这个后果（design-gui.md §7.3）。
     */
    suspend fun moveOutOfScene(id: Long) {
        recordDao.clearScene(id)
    }

    /** 补录坐标（design.md §5.7）。 */
    suspend fun updateLocation(id: Long, point: GeoPoint) {
        recordDao.updateLocation(
            id = id,
            lat = point.latitude,
            lon = point.longitude,
            alt = point.altitude,
            acc = point.accuracyMeters,
            provider = point.provider,
            at = point.locatedAt,
        )
    }

    // ————————————————————— 删除与撤销 —————————————————————

    /**
     * 删除记录，返回足以完整恢复它的快照。
     *
     * **与 design.md §10 的写法有一处刻意的偏差**：文档建议「Snackbar 期间不执行真实删除，
     * 超时后落库」。但那样记录在 Snackbar 显示期间仍留在表里，列表（一个 Room Flow）
     * 会继续显示它——用户点了删除却什么都没发生，看起来像 bug。
     *
     * 这里改为**立即删除、撤销时按原 id 重新插入**（含标签关联），
     * 同样满足验收标准「删除记录后撤销，数据完整恢复（含标签关联）」，
     * 且列表行为符合直觉。
     */
    suspend fun deleteRecord(id: Long): RecordSnapshot? {
        val existing = recordDao.getById(id) ?: return null
        val snapshot = RecordSnapshot(
            record = existing.record,
            tagIds = existing.tags.map { it.id },
        )
        recordDao.delete(id)
        return snapshot
    }

    /** 撤销删除。按原 id 重新插入，列表中的位置与标签一并还原。 */
    suspend fun restoreRecord(snapshot: RecordSnapshot) {
        db.withTransaction {
            recordDao.insert(snapshot.record)
            // 标签行本身没被删（删记录只级联删关联行），但为稳妥起见过滤掉已被删除的标签
            val alive = snapshot.tagIds.filter { tagDao.getById(it) != null }
            if (alive.isNotEmpty()) {
                recordDao.linkTags(alive.map { RecordTagCrossRef(snapshot.record.id, it) })
            }
            Unit
        }
    }

    /**
     * 清空所有记录。**不做撤销**——太危险，改用「输入『删除』二字」的二次确认
     * 把误触概率降到接近零（design-gui.md §6.6）。
     */
    suspend fun clearAll() {
        recordDao.deleteAll()
    }

    // ————————————————————— 内部 —————————————————————

    /**
     * 标签在**同一个事务内**创建或复用。
     *
     * `insertTag` 用 IGNORE 冲突策略：标签名有唯一索引，重复插入返回 -1，
     * 此时回查已存在的 id 复用，避免并发创建出重复标签行。
     */
    private suspend fun linkTagsByName(recordId: Long, tagNames: List<String>, now: Long) {
        val names = tagNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS_PER_RECORD)
        if (names.isEmpty()) return

        var colorCursor = tagDao.count()
        val refs = names.mapNotNull { name ->
            val inserted = recordDao.insertTag(
                TagEntity(
                    name = name,
                    colorIndex = colorCursor % TAG_PALETTE_SIZE,
                    createdAt = now,
                )
            )
            val tagId = if (inserted != -1L) {
                colorCursor++
                inserted
            } else {
                tagDao.findByName(name)?.id
            }
            tagId?.let { RecordTagCrossRef(recordId, it) }
        }
        if (refs.isNotEmpty()) recordDao.linkTags(refs)
    }

    private fun RecordQueryBuilder.BuiltQuery.toQuery(): SimpleSQLiteQuery =
        SimpleSQLiteQuery(sql, args.toTypedArray())

    companion object {
        /** 单条记录的标签上限（design-gui.md §5.6）。 */
        const val MAX_TAGS_PER_RECORD = 10
    }
}
