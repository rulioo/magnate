package com.magnate.compass.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.RecordSnapshot
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.location.LocationResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记录详情。
 *
 * **测量值不可编辑**——不提供改方位角、磁场、时间的入口。它们是观测事实，改了就失去意义。
 * 可编辑的只有备注、标签、归属场景（仅移出）与坐标（补录）（design-gui.md §7.4）。
 */
class RecordDetailViewModel(
    private val recordRepository: RecordRepository,
    private val tagRepository: TagRepository,
    private val sceneRepository: SceneRepository,
    private val locationProvider: LocationProvider,
    private val recordId: Long,
) : ViewModel() {

    val record: StateFlow<RecordWithRelations?> =
        recordRepository.observeById(recordId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /** 标签编辑器的候选。用全量而非「最近使用」——详情页是在整理，不是在快速记录。 */
    val allTags: StateFlow<List<TagWithCount>> =
        tagRepository.observeTags().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun updateNote(note: String?) {
        viewModelScope.launch { recordRepository.updateNote(recordId, note) }
    }

    fun setTags(names: List<String>) {
        viewModelScope.launch { recordRepository.setTags(recordId, names) }
    }

    /**
     * 新建标签。**不需要回调**——[allTags] 是 Room 的 Flow，标签一动列表自己就更新了；
     * 再让调用方手动往本地状态里塞一份，反而是第二个真相来源。
     */
    fun createTag(name: String) {
        viewModelScope.launch { tagRepository.createTag(name) }
    }

    /**
     * 移出场景。
     *
     * 坐标不动：记录自身若有实测坐标则回退到它，若没有则自然变成无坐标。
     * **调用方必须先在确认框里说明这个后果**（design-gui.md §7.3）——
     * 用户点「移出场景」时想的是「换个分组」，而不是「这条记录的坐标没了」。
     */
    fun moveOutOfScene() {
        viewModelScope.launch { recordRepository.moveOutOfScene(recordId) }
    }

    /**
     * 只取坐标、不落库。
     *
     * **刻意与写入分开**：取坐标这一步可能要先弹权限说明框、再弹系统弹窗、授权后再重试，
     * 整个过程由界面层的 `acquireLocationWithPermission` 驱动；把写入也塞进那个流程里，
     * 就得让 ViewModel 去理解权限那套状态机。
     *
     * 走与保存相同的缓存优先 + 超时降级路径。
     */
    suspend fun acquireLocation(): LocationResult =
        locationProvider.acquire(LocationProvider.DEFAULT_TIMEOUT_MS)

    /** 把补录到的坐标写进记录。 */
    fun applyBackfilledLocation(point: GeoPoint) {
        viewModelScope.launch { recordRepository.updateLocation(recordId, point) }
    }

    /** 删除。返回快照供 Snackbar 撤销。 */
    suspend fun delete(): RecordSnapshot? = recordRepository.deleteRecord(recordId)

    suspend fun restore(snapshot: RecordSnapshot) = recordRepository.restoreRecord(snapshot)

    /** 当前记录归属的场景是否需要「为场景设置坐标」而非「补录坐标」（§7.2）。 */
    suspend fun shouldFixSceneInstead(): Boolean {
        val current = record.first() ?: return false
        val sceneId = current.record.sceneId ?: return false
        val scene = sceneRepository.getScene(sceneId) ?: return false
        return !scene.hasCoordinate
    }
}
