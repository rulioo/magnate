package com.magnate.compass.ui.satellite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.location.GnssFormat
import com.magnate.compass.location.GnssProvider
import com.magnate.compass.location.GnssSnapshot
import com.magnate.compass.location.SatelliteInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 卫星信号页（design-gui.md §18）。
 *
 * ## 为什么是冷流 + `WhileSubscribed(0)`
 *
 * [GnssProvider.satelliteStream] 是冷流：**每个订阅者独立注册，取消订阅即注销**。
 * 于是零后台耗电（design.md §9）是结构性的，而不是一条需要记得遵守的纪律——
 * 离开这个页面，保活定位请求就没了，不需要任何 `onPause` 钩子。
 *
 * 订阅停止的延迟因此必须取 **0** 而不是项目惯用的 5000：那 5 秒的宽限期是为
 * 「转屏、短暂遮挡」这类瞬断准备的，而这里任何延迟都会让 GPS 请求在页面已经不可见之后
 * 继续存活。`StateFlow` 本身仍会跨订阅保留最后一个值，所以重进页面看到的是上一次读数，
 * 不是一片空白。
 */
/** `flatMapLatest` 目前仍标注为实验 API，与 `RecordsViewModel` 保持一致就地 opt-in。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteViewModel(private val gnssProvider: GnssProvider) : ViewModel() {

    /**
     * 重试计数。
     *
     * **授权之后必须重来一次**：流的权限检查发生在**收集开始**的那一瞬间，
     * 用户当场授予权限并不会让一个已经建好的流重新检查。把 count 一加，
     * `flatMapLatest` 会取消旧流、建一条新的，新的那条才看得见刚拿到的权限。
     */
    private val retry = MutableStateFlow(0)

    val snapshot: StateFlow<GnssSnapshot> = retry
        .flatMapLatest {
            gnssProvider.satelliteStream()
                // provider 内部已经把所有已知失败转成了 GnssBlock（见其注释），
                // 走到这里的是没料到的异常。**不能让它冒泡**——那会取消整个 ViewModel
                // 作用域，页面从此永远停在上一帧，而且没有任何提示。
                .catch { emit(GnssSnapshot(updatedAt = System.currentTimeMillis())) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(0),
            initialValue = GnssSnapshot(),
        )

    /** 展示顺序：参与解算的在前，组内按信号强弱。见 [GnssFormat.sortForDisplay]。 */
    fun sortedForDisplay(snapshot: GnssSnapshot): List<SatelliteInfo> =
        GnssFormat.sortForDisplay(snapshot.satellites)

    /** GNSS 硬件年份。null 表示设备不报，界面整行不显示。 */
    fun hardwareYear(): Int? = gnssProvider.hardwareYear()

    fun retry() {
        retry.value++
    }
}
