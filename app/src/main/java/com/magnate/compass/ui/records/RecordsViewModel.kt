package com.magnate.compass.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.RecordFilter
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.RecordSnapshot
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SortField
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.TagWithCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 列表页的快捷筛选。**单选**——多选与组合条件留给搜索页（design-gui.md §6.5）。
 *
 * 列表页承担「快速浏览某一类」的轻量任务，搜索页承担「精确查找」的重任务。
 * 让列表页也能叠加条件，只会让两页的职责变得模糊。
 */
sealed interface QuickFilter {
    data object All : QuickFilter

    data class Scene(val sceneId: Long) : QuickFilter

    data class Tag(val tagId: Long) : QuickFilter
}

/** `flatMapLatest` 目前仍标注为实验 API，与 `SearchViewModel` 保持一致就地 opt-in。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModel(
    private val recordRepository: RecordRepository,
    sceneRepository: SceneRepository,
    tagRepository: TagRepository,
) : ViewModel() {

    private val _quickFilter = MutableStateFlow<QuickFilter>(QuickFilter.All)
    val quickFilter: StateFlow<QuickFilter> = _quickFilter.asStateFlow()

    private val _sortBy = MutableStateFlow(SortField.TIME)
    val sortBy: StateFlow<SortField> = _sortBy.asStateFlow()

    private val _sortAsc = MutableStateFlow(false)
    val sortAsc: StateFlow<Boolean> = _sortAsc.asStateFlow()

    private val filter: StateFlow<RecordFilter> =
        combine(_quickFilter, _sortBy, _sortAsc) { quick, sort, asc ->
            when (quick) {
                QuickFilter.All -> RecordFilter(sortBy = sort, sortAsc = asc)
                is QuickFilter.Scene -> RecordFilter(
                    sceneIds = setOf(quick.sceneId),
                    sortBy = sort,
                    sortAsc = asc,
                )

                is QuickFilter.Tag -> RecordFilter(
                    tagIds = setOf(quick.tagId),
                    sortBy = sort,
                    sortAsc = asc,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordFilter())

    val records: StateFlow<List<RecordWithRelations>> =
        filter.flatMapLatest { recordRepository.observeRecords(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 快捷筛选行的场景 chip，按记录数降序（design-gui.md §6.5）。 */
    val scenes: StateFlow<List<SceneWithCount>> =
        sceneRepository.observeScenes().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val tags: StateFlow<List<TagWithCount>> =
        tagRepository.observeTags().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val totalCount: StateFlow<Int> =
        recordRepository.observeCount().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    // ————————————————————— 交互 —————————————————————

    /** 再次点击已选中的 chip 则取消，回到「全部」。 */
    fun toggleQuickFilter(target: QuickFilter) {
        _quickFilter.value = if (_quickFilter.value == target) QuickFilter.All else target
    }

    fun setSort(field: SortField) {
        if (_sortBy.value == field) {
            _sortAsc.value = !_sortAsc.value
        } else {
            _sortBy.value = field
            // 磁场强度默认降序（先看强的），时间默认降序（先看新的）
            _sortAsc.value = false
        }
    }

    fun clearFilters() {
        _quickFilter.value = QuickFilter.All
    }

    // ————————————————————— 删除与撤销 —————————————————————

    suspend fun deleteRecord(id: Long): RecordSnapshot? = recordRepository.deleteRecord(id)

    suspend fun restoreRecord(snapshot: RecordSnapshot) = recordRepository.restoreRecord(snapshot)

    /**
     * 清空所有记录。**不做撤销**——太危险。UI 用「输入『删除』二字」的二次确认
     * 把误触概率降到接近零（design-gui.md §6.6）。
     */
    fun clearAllRecords(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            recordRepository.clearAll()
            _quickFilter.value = QuickFilter.All
            onDone()
        }
    }
}
