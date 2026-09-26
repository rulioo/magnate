package com.magnate.compass.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.*, (SELECT COUNT(*) FROM record_tags rt WHERE rt.tagId = t.id) AS recordCount
        FROM tags t ORDER BY t.name ASC
        """
    )
    fun observeAllWithCount(): Flow<List<TagWithCount>>

    /**
     * 最近使用过的标签，供保存面板的 chip 流使用。
     * 按「最后一次被使用的时间」降序；从未使用的标签排在最后（SQLite 的 DESC 把 NULL 排在末尾）。
     */
    @Query(
        """
        SELECT t.*, (SELECT COUNT(*) FROM record_tags rt WHERE rt.tagId = t.id) AS recordCount
        FROM tags t
        ORDER BY (
            SELECT MAX(r.timestamp) FROM record_tags rt
            JOIN records r ON r.id = rt.recordId WHERE rt.tagId = t.id
        ) DESC, t.name ASC
        LIMIT :limit
        """
    )
    fun observeRecentlyUsed(limit: Int): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    /** 就地重命名，关联关系不变，因此所有关联记录同步呈现新名字。 */
    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE tags SET colorIndex = :colorIndex WHERE id = :id")
    suspend fun updateColor(id: Long, colorIndex: Int)

    /** 删除标签不删记录，只解除关联（record_tags 上的 CASCADE 负责清理关联行）。 */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int
}
