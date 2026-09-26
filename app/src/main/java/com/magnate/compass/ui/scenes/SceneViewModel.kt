package com.magnate.compass.ui.scenes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneDraft
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SceneWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 场景列表与「新建场景」面板共用的状态。
 *
 * 列表按 `updatedAt` 降序（仓库层保证），当前激活场景带 `✓ 当前` 徽标。
 */
class SceneViewModel(
    private val sceneRepository: SceneRepository,
    private val recordRepository: RecordRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    val scenes: StateFlow<List<SceneWithCount>> = sceneRepository.observeScenes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前激活场景的 id。
     *
     * 走 [SceneRepository.observeActiveScene] 而不是直接读 DataStore：前者会处理
     * **悬空 ID**——场景删除后 DataStore 里可能还留着它的 id，此时应视为无场景。
     */
    val activeSceneId: StateFlow<Long?> = sceneRepository.observeActiveScene()
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 「从记录导入坐标」的候选：最近 20 条**自身有实测坐标**的记录（§9.3）。 */
    val recentlyLocated: StateFlow<List<RecordWithRelations>> =
        recordRepository.observeRecentlyLocated(IMPORT_CANDIDATE_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun enterScene(sceneId: Long) {
        viewModelScope.launch { sceneRepository.enterScene(sceneId) }
    }

    fun leaveScene() {
        viewModelScope.launch { sceneRepository.leaveScene() }
    }

    suspend fun createScene(draft: SceneDraft): SceneWriteResult = sceneRepository.createScene(draft)

    suspend fun renameScene(id: Long, newName: String): SceneWriteResult =
        sceneRepository.renameScene(id, newName)

    /**
     * 取一次坐标。
     *
     * 用完整超时预算而非场景保存时的 3s：建场景时人多半就站在那个点上等结果，
     * 拿到一个精确坐标比快 7 秒重要得多。
     */
    suspend fun acquireLocation(): GeoPoint? =
        locationProvider.acquire(LocationProvider.DEFAULT_TIMEOUT_MS)

    fun setCoordMode(id: Long, mode: CoordMode) {
        viewModelScope.launch { sceneRepository.updateCoordMode(id, mode) }
    }

    companion object {
        /** design-gui.md §9.3：从记录导入时列出最近 20 条有坐标的记录。 */
        const val IMPORT_CANDIDATE_LIMIT = 20
    }
}
