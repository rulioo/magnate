package com.magnate.compass.data

import androidx.room.withTransaction
import com.magnate.compass.data.db.MagnateDatabase
import com.magnate.compass.data.db.RecordDao
import com.magnate.compass.data.db.SceneDao
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

/** 新建/重命名场景的结果。重名要能引导用户去看那个已存在的场景。 */
sealed interface SceneWriteResult {
    data class Success(val sceneId: Long) : SceneWriteResult
    data class DuplicateName(val existingSceneId: Long) : SceneWriteResult
    data object NotFound : SceneWriteResult
}

class SceneRepository(
    private val db: MagnateDatabase,
    private val sceneDao: SceneDao,
    private val recordDao: RecordDao,
    private val activeSceneStore: ActiveSceneStore,
) {

    // ————————————————————— 读取 —————————————————————

    fun observeScenes(): Flow<List<SceneWithCount>> = sceneDao.observeAll()

    fun observeScene(id: Long): Flow<SceneEntity?> = sceneDao.observeById(id)

    fun observeSceneRecordCount(id: Long): Flow<Int> = sceneDao.observeRecordCount(id)

    fun observeSceneRecords(id: Long, limit: Int = 3): Flow<List<RecordWithRelations>> =
        recordDao.observeByScene(id, limit)

    /**
     * 当前激活场景。
     *
     * 处理**悬空 ID**：场景被删除后 DataStore 里可能还留着它的 id，
     * 此时视为无场景并顺手清掉该键（design.md §10）。
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest 仍标注为实验 API
    fun observeActiveScene(): Flow<SceneEntity?> =
        activeSceneStore.activeSceneId.flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                sceneDao.observeById(id).onEach { scene ->
                    if (scene == null) activeSceneStore.setActiveScene(null)
                }
            }
        }

    suspend fun getScene(id: Long): SceneEntity? = sceneDao.getById(id)

    suspend fun getSceneByName(name: String): SceneEntity? = sceneDao.findByName(name)

    // ————————————————————— 激活状态 —————————————————————

    /** 切换场景立即生效，不需要额外的「确定」——这是低风险操作，随时可切回。 */
    suspend fun enterScene(sceneId: Long) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            activeSceneStore.setActiveScene(sceneId)
            // 刷新 updatedAt，让它排到选择器最上方
            sceneDao.touch(sceneId, now)
        }
    }

    suspend fun leaveScene() {
        activeSceneStore.setActiveScene(null)
    }

    // ————————————————————— 写入 —————————————————————

    /** 坐标可为空——**坐标获取失败不阻塞创建**，此时场景只是个分组容器。 */
    suspend fun createScene(draft: SceneDraft): SceneWriteResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return SceneWriteResult.NotFound

        sceneDao.findByName(name)?.let { return SceneWriteResult.DuplicateName(it.id) }

        val now = System.currentTimeMillis()
        val scene = SceneEntity(
            name = name,
            latitude = draft.point?.latitude,
            longitude = draft.point?.longitude,
            altitude = draft.point?.altitude,
            locationAccuracy = draft.point?.accuracyMeters,
            locationProvider = draft.point?.provider,
            locatedAt = draft.point?.locatedAt,
            coordMode = draft.coordMode,
            note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
        // 唯一索引冲突（并发创建同名场景）也走重名分支
        return runCatching { sceneDao.insert(scene) }
            .fold(
                onSuccess = { SceneWriteResult.Success(it) },
                onFailure = {
                    sceneDao.findByName(name)
                        ?.let { SceneWriteResult.DuplicateName(it.id) }
                        ?: SceneWriteResult.NotFound
                },
            )
    }

    suspend fun renameScene(id: Long, newName: String): SceneWriteResult {
        val name = newName.trim()
        if (name.isEmpty()) return SceneWriteResult.NotFound
        sceneDao.findByName(name)?.takeIf { it.id != id }
            ?.let { return SceneWriteResult.DuplicateName(it.id) }

        val now = System.currentTimeMillis()
        return runCatching {
            sceneDao.rename(id, name, now)
            SceneWriteResult.Success(id)
        }.getOrElse {
            sceneDao.findByName(name)
                ?.let { SceneWriteResult.DuplicateName(it.id) }
                ?: SceneWriteResult.NotFound
        }
    }

    suspend fun updateNote(id: Long, note: String?) {
        sceneDao.updateNote(id, note?.trim()?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())
    }

    suspend fun updateCoordMode(id: Long, mode: CoordMode) {
        sceneDao.updateCoordMode(id, mode, System.currentTimeMillis())
    }

    /**
     * 更新场景坐标，返回可撤销的变更记录。
     *
     * FIXED 模式下这会**静默改变该场景下所有历史记录的坐标**——引用语义使然。
     * 调用方必须先弹确认框告知影响记录数，成功后提供 5 秒撤销（design-gui.md §9.4）。
     */
    suspend fun updateLocation(id: Long, point: GeoPoint): SceneLocationChange? {
        val scene = sceneDao.getById(id) ?: return null
        val affected = recordDao.countOfScene(id)
        val change = SceneLocationChange(
            sceneId = id,
            sceneName = scene.name,
            affectedRecordCount = affected,
            previousLocation = SceneLocationSnapshot(
                latitude = scene.latitude,
                longitude = scene.longitude,
                altitude = scene.altitude,
                locationAccuracy = scene.locationAccuracy,
                locationProvider = scene.locationProvider,
                locatedAt = scene.locatedAt,
            ),
        )
        sceneDao.updateLocation(
            id = id,
            lat = point.latitude,
            lon = point.longitude,
            alt = point.altitude,
            acc = point.accuracyMeters,
            provider = point.provider,
            at = point.locatedAt,
            now = System.currentTimeMillis(),
        )
        return change
    }

    /** 撤销一次场景坐标更新。恢复旧坐标即可让所有引用它的记录恢复原值。 */
    suspend fun revertLocation(change: SceneLocationChange) {
        val previous = change.previousLocation
        val now = System.currentTimeMillis()
        if (previous == null || previous.latitude == null || previous.longitude == null) {
            // 原先就没有坐标：清空
            sceneDao.clearLocation(change.sceneId, now)
            return
        }
        sceneDao.updateLocation(
            id = change.sceneId,
            lat = previous.latitude,
            lon = previous.longitude,
            alt = previous.altitude,
            acc = previous.locationAccuracy ?: Float.MAX_VALUE,
            provider = previous.locationProvider ?: "unknown",
            at = previous.locatedAt ?: now,
            now = now,
        )
    }

    /**
     * 删除场景。记录**不会被删除**。
     *
     * 两个选项（design-gui.md §9.5）：
     * - [keepCoordinates] = true（默认）：把场景坐标**固化写入**这些记录，
     *   它们仍带有坐标，只是不再属于任何场景。FIXED 模式下记录自身不存坐标，
     *   若直接解除关联，删一个场景会让十几条记录的坐标**凭空消失**。
     * - false：仅解除关联，这些记录将变为无坐标。
     *
     * 这是整个应用里唯一一处「删除操作可能需要写数据」的地方，
     * 必须放在同一个事务里：先批量 updateLocation，再 delete（design-gui.md §9.5）。
     */
    suspend fun deleteScene(id: Long, keepCoordinates: Boolean) {
        db.withTransaction {
            val scene = sceneDao.getById(id) ?: return@withTransaction

            if (keepCoordinates && scene.hasCoordinate) {
                recordDao.freezeSceneLocationIntoRecords(
                    sceneId = id,
                    lat = scene.latitude!!,
                    lon = scene.longitude!!,
                    alt = scene.altitude,
                    acc = scene.locationAccuracy ?: Float.MAX_VALUE,
                    provider = scene.locationProvider ?: "unknown",
                    at = scene.locatedAt ?: scene.createdAt,
                )
            }

            // 删除的是激活中的场景 → 自动退出该场景，主页场景条回到「未选择场景」
            if (activeSceneStore.activeSceneId.first() == id) {
                activeSceneStore.setActiveScene(null)
            }

            sceneDao.delete(id)
        }
    }
}
