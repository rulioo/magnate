package com.magnate.compass.data

import com.magnate.compass.data.db.TagDao
import com.magnate.compass.data.entity.TAG_PALETTE_SIZE
import com.magnate.compass.data.entity.TagEntity
import kotlinx.coroutines.flow.Flow

sealed interface TagWriteResult {
    data class Success(val tagId: Long) : TagWriteResult

    /** 重名。标签名唯一，此时复用已有标签而不是新建。 */
    data class Duplicate(val existingTagId: Long) : TagWriteResult
}

class TagRepository(private val tagDao: TagDao) {

    fun observeTags(): Flow<List<TagWithCount>> = tagDao.observeAllWithCount()

    fun observeAllPlain(): Flow<List<TagEntity>> = tagDao.observeAll()

    /** 保存面板的 chip 流：最近使用的 8 个（design-gui.md §5.6）。 */
    fun observeRecentlyUsed(limit: Int = 8): Flow<List<TagWithCount>> =
        tagDao.observeRecentlyUsed(limit)

    suspend fun findByName(name: String): TagEntity? = tagDao.findByName(name.trim())

    suspend fun getByIds(ids: List<Long>): List<TagEntity> = tagDao.getByIds(ids)

    suspend fun createTag(name: String, colorIndex: Int? = null): TagWriteResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return TagWriteResult.Duplicate(-1L)

        tagDao.findByName(trimmed)?.let { return TagWriteResult.Duplicate(it.id) }

        val now = System.currentTimeMillis()
        val index = colorIndex ?: (tagDao.count() % TAG_PALETTE_SIZE)
        val inserted = tagDao.insert(TagEntity(name = trimmed, colorIndex = index, createdAt = now))
        return if (inserted != -1L) {
            TagWriteResult.Success(inserted)
        } else {
            tagDao.findByName(trimmed)
                ?.let { TagWriteResult.Duplicate(it.id) }
                ?: TagWriteResult.Duplicate(-1L)
        }
    }

    /** 就地重命名。标签名是标签行上的字段，所有关联记录自动同步呈现新名字。 */
    suspend fun renameTag(id: Long, newName: String): TagWriteResult {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return TagWriteResult.Duplicate(id)

        tagDao.findByName(trimmed)?.takeIf { it.id != id }
            ?.let { return TagWriteResult.Duplicate(it.id) }

        tagDao.rename(id, trimmed)
        return TagWriteResult.Success(id)
    }

    suspend fun updateColor(id: Long, colorIndex: Int) {
        tagDao.updateColor(id, colorIndex)
    }

    /**
     * 删除标签。关联的记录**不会被删除**，只是不再带有这个标签。
     * UI 上必须明确告知这一点——用户看到「删除」与「12 条记录」放在一起会恐慌。
     */
    suspend fun deleteTag(id: Long) {
        tagDao.delete(id)
    }
}
