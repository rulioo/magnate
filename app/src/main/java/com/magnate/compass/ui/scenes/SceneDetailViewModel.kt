package com.magnate.compass.ui.scenes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneLocationChange
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SceneWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.location.LocationResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 场景详情（design-gui.md §9.2）。
 *
 * 这一页最重要的交互是「进入此场景 / ✓ 当前场景」的两种状态：
 * 用户从场景列表点进来，最常见的意图就是「我要在这个场景里测量」，
 * 给一个直接入口比让他返回主页再切换少两步。
 */
class SceneDetailViewModel(
    private val sceneRepository: SceneRepository,
    private val locationProvider: LocationProvider,
    private val sceneId: Long,
) : ViewModel() {

    val scene: StateFlow<SceneEntity?> = sceneRepository.observeScene(sceneId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recordCount: StateFlow<Int> = sceneRepository.observeSceneRecordCount(sceneId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recentRecords: StateFlow<List<RecordWithRelations>> =
        sceneRepository.observeSceneRecords(sceneId, PREVIEW_RECORD_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isActive: StateFlow<Boolean> = sceneRepository.observeActiveScene()
        .map { it?.id == sceneId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun enter() {
        viewModelScope.launch { sceneRepository.enterScene(sceneId) }
    }

    fun leave() {
        viewModelScope.launch { sceneRepository.leaveScene() }
    }

    fun updateNote(note: String?) {
        viewModelScope.launch { sceneRepository.updateNote(sceneId, note) }
    }

    fun setCoordMode(mode: CoordMode) {
        viewModelScope.launch { sceneRepository.updateCoordMode(sceneId, mode) }
    }

    /**
     * suspend 而非回调：调用点在编辑面板的**提交协程**里，需要拿到结果
     * 才能决定是关闭面板，还是弹「场景名已存在 → 查看该场景」。
     */
    suspend fun rename(newName: String): SceneWriteResult =
        sceneRepository.renameScene(sceneId, newName)

    /** 取一次坐标。失败原因一并返回，见 [LocationResult]。 */
    suspend fun acquireLocation(): LocationResult =
        locationProvider.acquire(LocationProvider.DEFAULT_TIMEOUT_MS)

    /**
     * 更新场景坐标。
     *
     * FIXED 模式下这会**静默改变该场景下所有历史记录的坐标**——引用语义使然。
     * 调用方必须先用 [SceneEntity] 上的记录数弹确认框（§9.4），
     * 成功后再用返回的 [SceneLocationChange] 提供 5 秒撤销。
     */
    fun updateLocation(point: GeoPoint, onChange: (SceneLocationChange?) -> Unit) {
        viewModelScope.launch { onChange(sceneRepository.updateLocation(sceneId, point)) }
    }

    fun revertLocation(change: SceneLocationChange) {
        viewModelScope.launch { sceneRepository.revertLocation(change) }
    }

    fun delete(keepCoordinates: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            sceneRepository.deleteScene(sceneId, keepCoordinates)
            onDone()
        }
    }

    private companion object {
        /** 详情页只预览最近 3 条，其余走「查看全部」（§9.2）。 */
        const val PREVIEW_RECORD_LIMIT = 3
    }
}
