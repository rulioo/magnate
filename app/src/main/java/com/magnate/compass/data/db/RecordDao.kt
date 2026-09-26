package com.magnate.compass.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.RecordTagCrossRef
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.data.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    /**
     * 动态条件查询。SQL 由 [com.magnate.compass.data.RecordQueryBuilder] 纯函数构造，
     * 此处只负责执行。
     *
     * `@Transaction` 是必需的——`@Relation` 的标签与场景需要额外的查询补齐。
     */
    @Transaction
    @RawQuery(
        observedEntities = [
            RecordEntity::class,
            TagEntity::class,
            RecordTagCrossRef::class,
            SceneEntity::class,
        ],
    )
    fun search(query: SupportSQLiteQuery): Flow<List<RecordWithRelations>>

    /** 筛选面板底部的实时结果数。 */
    @RawQuery(
        observedEntities = [
            RecordEntity::class,
            TagEntity::class,
            RecordTagCrossRef::class,
            SceneEntity::class,
        ],
    )
    fun countSearch(query: SupportSQLiteQuery): Flow<Int>

    @Transaction
    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: Long): Flow<RecordWithRelations?>

    @Transaction
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: Long): RecordWithRelations?

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getRecord(id: Long): RecordEntity?

    @Transaction
    @Query("SELECT * FROM records WHERE sceneId = :sceneId ORDER BY timestamp DESC LIMIT :limit")
    fun observeByScene(sceneId: Long, limit: Int): Flow<List<RecordWithRelations>>

    /**
     * 最近若干条**有本机实测坐标**的记录，供「从记录导入坐标」使用。
     * 只看记录自身实测坐标——场景继承来的坐标不能作为另一个场景的来源，否则会形成引用环。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM records
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY timestamp DESC LIMIT :limit
        """
    )
    fun observeRecentlyLocated(limit: Int): Flow<List<RecordWithRelations>>

    @Insert
    suspend fun insert(record: RecordEntity): Long

    /** 标签名有唯一索引，重复插入返回 -1，由调用方回查已存在的 id 复用。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTags(refs: List<RecordTagCrossRef>)

    @Query("DELETE FROM record_tags WHERE recordId = :recordId")
    suspend fun unlinkAllTags(recordId: Long)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    @Query("UPDATE records SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    /**
     * 移出场景。坐标**不动**——记录自身若有实测坐标则回退到它，
     * 若没有则自然变成无坐标，这正是 UI 确认框里要说明的后果。
     */
    @Query("UPDATE records SET sceneId = NULL WHERE id = :id")
    suspend fun clearScene(id: Long)

    @Query(
        """
        UPDATE records SET latitude = :lat, longitude = :lon, altitude = :alt,
            locationAccuracy = :acc, locationProvider = :provider, locatedAt = :at
        WHERE id = :id
        """
    )
    suspend fun updateLocation(
        id: Long,
        lat: Double,
        lon: Double,
        alt: Double?,
        acc: Float,
        provider: String,
        at: Long,
    )

    /**
     * 把场景坐标**固化写入**该场景下的所有记录。
     * 仅用于「删除场景 → 保留坐标到记录」这一个场景（design-gui.md §9.5）。
     */
    @Query(
        """
        UPDATE records SET latitude = :lat, longitude = :lon, altitude = :alt,
            locationAccuracy = :acc, locationProvider = :provider, locatedAt = :at
        WHERE sceneId = :sceneId
        """
    )
    suspend fun freezeSceneLocationIntoRecords(
        sceneId: Long,
        lat: Double,
        lon: Double,
        alt: Double?,
        acc: Float,
        provider: String,
        at: Long,
    )

    @Query("SELECT COUNT(*) FROM records")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE sceneId = :sceneId")
    suspend fun countOfScene(sceneId: Long): Int
}
