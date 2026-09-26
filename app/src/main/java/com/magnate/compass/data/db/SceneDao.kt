package com.magnate.compass.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SceneDao {

    /** 按 `updatedAt` 降序——最近使用的在最上（design-gui.md §4.3）。 */
    @Query(
        """
        SELECT s.*, (SELECT COUNT(*) FROM records r WHERE r.sceneId = s.id) AS recordCount
        FROM scenes s ORDER BY s.updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<SceneWithCount>>

    @Query("SELECT * FROM scenes ORDER BY updatedAt DESC")
    fun observeAllPlain(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE id = :id")
    fun observeById(id: Long): Flow<SceneEntity?>

    @Query("SELECT * FROM scenes WHERE id = :id")
    suspend fun getById(id: Long): SceneEntity?

    @Query("SELECT * FROM scenes WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): SceneEntity?

    @Query("SELECT COUNT(*) FROM records WHERE sceneId = :id")
    fun observeRecordCount(id: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(scene: SceneEntity): Long

    @Query("UPDATE scenes SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE scenes SET note = :note, updatedAt = :now WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?, now: Long)

    /**
     * 更新场景坐标。
     *
     * FIXED 模式下坐标是**引用语义**——记录自身不存坐标，读取时从场景解析。
     * 因此这条 UPDATE 会静默改变该场景下所有历史记录的坐标，
     * 调用方必须先在 UI 上告知影响范围并提供撤销（design-gui.md §9.4）。
     */
    @Query(
        """
        UPDATE scenes SET latitude = :lat, longitude = :lon, altitude = :alt,
            locationAccuracy = :acc, locationProvider = :provider,
            locatedAt = :at, updatedAt = :now
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
        now: Long,
    )

    /** 清空场景坐标——撤销「首次为场景设置坐标」时用。 */
    @Query(
        """
        UPDATE scenes SET latitude = NULL, longitude = NULL, altitude = NULL,
            locationAccuracy = NULL, locationProvider = NULL, locatedAt = NULL,
            updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun clearLocation(id: Long, now: Long)

    @Query("UPDATE scenes SET coordMode = :mode, updatedAt = :now WHERE id = :id")
    suspend fun updateCoordMode(id: Long, mode: CoordMode, now: Long)

    /** 进入场景时刷新 `updatedAt`，让它排到选择器最上方。 */
    @Query("UPDATE scenes SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM scenes")
    suspend fun count(): Int
}
