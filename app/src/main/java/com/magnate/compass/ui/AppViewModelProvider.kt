package com.magnate.compass.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.magnate.compass.MagnateApp
import com.magnate.compass.ui.compass.CompassViewModel
import com.magnate.compass.ui.nav.Routes
import com.magnate.compass.ui.records.RecordDetailViewModel
import com.magnate.compass.ui.records.RecordsViewModel
import com.magnate.compass.ui.satellite.SatelliteViewModel
import com.magnate.compass.ui.scenes.SceneDetailViewModel
import com.magnate.compass.ui.scenes.SceneViewModel
import com.magnate.compass.ui.search.SearchViewModel
import com.magnate.compass.ui.tags.TagViewModel

/**
 * 手写的 ViewModel 工厂。
 *
 * 用 `viewModelFactory { initializer { ... } }` 而不是给每个 ViewModel 配一个
 * `ViewModelProvider.Factory` 子类：工厂只做一件事——从 [CreationExtras] 里取出
 * Application、拿到容器、把依赖按构造参数摆好。为这一件事写七个类不值。
 *
 * **导航参数走 `SavedStateHandle`**（[Routes.RECORD_ID_ARG] / [Routes.SCENE_ID_ARG]），
 * 而不是在 Composable 里读出来再传给 ViewModel：这样进程被系统回收后重建时，
 * 导航参数由 SavedState 自动恢复，详情页不会因为拿不到 id 而白屏。
 */
object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer {
            val c = container()
            CompassViewModel(
                sensorRepository = c.sensorRepository,
                sceneRepository = c.sceneRepository,
                recordRepository = c.recordRepository,
                tagRepository = c.tagRepository,
                locationProvider = c.locationProvider,
                settingsStore = c.settingsStore,
            )
        }

        initializer {
            val c = container()
            RecordsViewModel(
                recordRepository = c.recordRepository,
                sceneRepository = c.sceneRepository,
                tagRepository = c.tagRepository,
            )
        }

        initializer {
            val c = container()
            RecordDetailViewModel(
                recordRepository = c.recordRepository,
                tagRepository = c.tagRepository,
                sceneRepository = c.sceneRepository,
                locationProvider = c.locationProvider,
                recordId = requireArg(Routes.RECORD_ID_ARG),
            )
        }

        initializer {
            val c = container()
            SearchViewModel(
                recordRepository = c.recordRepository,
                tagRepository = c.tagRepository,
                sceneRepository = c.sceneRepository,
            )
        }

        initializer {
            val c = container()
            SceneViewModel(
                sceneRepository = c.sceneRepository,
                recordRepository = c.recordRepository,
                locationProvider = c.locationProvider,
            )
        }

        initializer {
            val c = container()
            SceneDetailViewModel(
                sceneRepository = c.sceneRepository,
                locationProvider = c.locationProvider,
                sceneId = requireArg(Routes.SCENE_ID_ARG),
            )
        }

        initializer {
            TagViewModel(tagRepository = container().tagRepository)
        }

        initializer {
            SatelliteViewModel(gnssProvider = container().gnssProvider)
        }
    }

    private fun CreationExtras.container() =
        (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MagnateApp).container

    /**
     * 取导航参数。
     *
     * 缺失时抛异常而不是回退到 0：`recordId = 0` 会变成一次「记录不存在」的空页面，
     * 把「路由没配对」这种开发期错误伪装成运行期的正常状态，最难排查。
     */
    private fun CreationExtras.requireArg(key: String): Long =
        checkNotNull(createSavedStateHandle()[key]) {
            "导航参数 $key 缺失——检查 NavHost 的路由定义是否声明了该参数"
        }
}
