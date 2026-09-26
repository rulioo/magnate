package com.magnate.compass.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.CoordinatePreset
import com.magnate.compass.data.MagnitudePreset
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SearchCriteria
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TimePreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 活跃筛选 chip 行上的一枚 chip（§8.3）。 */
sealed interface ActiveFilter {
    val label: String

    data class Scene(val id: Long, val name: String) : ActiveFilter {
        override val label get() = "📍$name"
    }

    data class Tag(val id: Long, val name: String) : ActiveFilter {
        override val label get() = "#$name"
    }

    data class Time(val preset: TimePreset) : ActiveFilter {
        override val label: String
            get() = when (preset) {
                TimePreset.TODAY -> "今天"
                TimePreset.LAST_7 -> "近 7 天"
                TimePreset.LAST_30 -> "近 30 天"
                TimePreset.CUSTOM -> "自定义时间"
                TimePreset.ALL -> "不限"
            }
    }

    data class Magnitude(val preset: MagnitudePreset) : ActiveFilter {
        override val label: String
            get() = when (preset) {
                MagnitudePreset.LOW -> "< 25 μT"
                MagnitudePreset.NORMAL -> "25 – 65 μT"
                MagnitudePreset.HIGH -> "> 65 μT"
                MagnitudePreset.CUSTOM -> "自定义强度"
                MagnitudePreset.ALL -> "不限"
            }
    }

    data class Coordinate(val preset: CoordinatePreset) : ActiveFilter {
        override val label: String
            get() = when (preset) {
                CoordinatePreset.HAS -> "有坐标"
                CoordinatePreset.NONE -> "无坐标"
                CoordinatePreset.ALL -> "不限"
            }
    }

    data object Accuracy : ActiveFilter {
        override val label get() = "精度优于 50m"
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val recordRepository: RecordRepository,
    tagRepository: TagRepository,
    sceneRepository: SceneRepository,
) : ViewModel() {

    /** 输入框里的原始文本。**不加 debounce**——输入框必须逐字跟手。 */
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /** 已应用的筛选条件。 */
    private val _criteria = MutableStateFlow(SearchCriteria())
    val criteria: StateFlow<SearchCriteria> = _criteria.asStateFlow()

    /**
     * 筛选面板里的**草稿**条件。
     *
     * 面板底部的「查看 N 条结果」要随草稿实时变化，但此时用户还没点确认，
     * 结果列表不该跟着动。草稿与应用值分开存，是这两个需求唯一能共存的方式。
     */
    private val _draft = MutableStateFlow<SearchCriteria?>(null)
    val draft: StateFlow<SearchCriteria?> = _draft.asStateFlow()

    private val debouncedKeyword: StateFlow<String> = _keyword
        .debounce(KEYWORD_DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * 真正拿去查询的条件 = 已应用的筛选 + 防抖后的关键词。
     *
     * `debounce` 只挂在关键词上：勾选一个场景 chip 是明确的一次点击，
     * 让它延迟 300ms 才生效只会显得卡顿。防抖要解决的是「打字」，不是「点击」。
     */
    private val appliedFilter = combine(_criteria, debouncedKeyword) { c, k ->
        c.copy(keyword = k).toFilter(System.currentTimeMillis())
    }

    val records: StateFlow<List<RecordWithRelations>> = appliedFilter
        .flatMapLatest { recordRepository.observeRecords(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 「找到 N 条记录」。来自独立的 COUNT 查询而非 `records.size`——列表是懒加载的，不完整。 */
    val resultCount: StateFlow<Int> = appliedFilter
        .flatMapLatest { recordRepository.observeMatchCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 面板底部按钮上的实时数字，跟随**草稿**而非已应用条件。 */
    val draftResultCount: StateFlow<Int> = combine(_draft, debouncedKeyword) { d, k ->
        (d ?: _criteria.value).copy(keyword = k)
    }
        .flatMapLatest { recordRepository.observeMatchCount(it.toFilter(System.currentTimeMillis())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val scenes: StateFlow<List<SceneWithCount>> = sceneRepository.observeScenes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagWithCount>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** §8.3：用户设了五个条件后忘了设过什么，看到空结果也不知道为什么。 */
    val activeFilters: StateFlow<List<ActiveFilter>> =
        combine(_criteria, scenes, tags) { c, sceneList, tagList ->
            buildList {
                sceneList.filter { it.scene.id in c.sceneIds }
                    .forEach { add(ActiveFilter.Scene(it.scene.id, it.scene.name)) }
                tagList.filter { it.tag.id in c.tagIds }
                    .forEach { add(ActiveFilter.Tag(it.tag.id, it.tag.name)) }
                if (c.timePreset != TimePreset.ALL) add(ActiveFilter.Time(c.timePreset))
                if (c.magnitudePreset != MagnitudePreset.ALL) add(ActiveFilter.Magnitude(c.magnitudePreset))
                if (c.coordinatePreset != CoordinatePreset.ALL) add(ActiveFilter.Coordinate(c.coordinatePreset))
                if (c.goodAccuracyOnly) add(ActiveFilter.Accuracy)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ————————————————————— 输入 —————————————————————

    fun setKeyword(value: String) {
        _keyword.value = value
    }

    fun clearKeyword() {
        _keyword.value = ""
    }

    /** §8.5 的出口之二：清掉全部筛选，关键词保留。 */
    fun resetFilters() {
        _criteria.value = _criteria.value.withoutFilters()
    }

    fun applyCriteria(updated: SearchCriteria) {
        _criteria.value = updated
        _draft.value = null
    }

    // ————————————————————— 筛选面板 —————————————————————

    fun openFilterSheet() {
        _draft.value = _criteria.value
    }

    fun closeFilterSheet() {
        _draft.value = null
    }

    /** 面板内每次改动都走这里，草稿计数随之刷新。 */
    fun updateDraft(transform: (SearchCriteria) -> SearchCriteria) {
        val current = _draft.value ?: _criteria.value
        _draft.value = transform(current)
    }

    fun resetDraft() {
        _draft.value = (_draft.value ?: _criteria.value).withoutFilters()
    }

    fun confirmDraft() {
        _draft.value?.let { _criteria.value = it }
        _draft.value = null
    }

    // ————————————————————— chip 移除 —————————————————————

    /**
     * 移除一枚活跃 chip。
     *
     * 走 [SearchCriteria.withoutFilters] 的反向操作而不是重建整个条件对象——
     * 这样「移除 B2层 chip」只动场景集合，用户设的其他四个条件一个都不受影响。
     */
    fun removeActiveFilter(filter: ActiveFilter) {
        _criteria.value = when (filter) {
            is ActiveFilter.Scene -> _criteria.value.copy(sceneIds = _criteria.value.sceneIds - filter.id)
            is ActiveFilter.Tag -> _criteria.value.copy(tagIds = _criteria.value.tagIds - filter.id)
            is ActiveFilter.Time -> _criteria.value.copy(
                timePreset = TimePreset.ALL,
                customTimeFrom = null,
                customTimeTo = null,
            )

            is ActiveFilter.Magnitude -> _criteria.value.copy(
                magnitudePreset = MagnitudePreset.ALL,
                customMagnitudeMin = null,
                customMagnitudeMax = null,
            )

            is ActiveFilter.Coordinate -> _criteria.value.copy(coordinatePreset = CoordinatePreset.ALL)
            ActiveFilter.Accuracy -> _criteria.value.copy(goodAccuracyOnly = false)
        }
    }

    companion object {
        /** §8.2：输入即搜，300ms 防抖。 */
        const val KEYWORD_DEBOUNCE_MS = 300L
    }
}
