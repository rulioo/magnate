package com.magnate.compass.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.ui.AppViewModelProvider
import com.magnate.compass.ui.compass.CompassScreen
import com.magnate.compass.ui.compass.CompassViewModel
import com.magnate.compass.ui.records.RecordDetailScreen
import com.magnate.compass.ui.records.RecordDetailViewModel
import com.magnate.compass.ui.records.RecordListScreen
import com.magnate.compass.ui.records.RecordsViewModel
import com.magnate.compass.ui.satellite.SatelliteScreen
import com.magnate.compass.ui.satellite.SatelliteViewModel
import com.magnate.compass.ui.scenes.SceneDetailScreen
import com.magnate.compass.ui.scenes.SceneDetailViewModel
import com.magnate.compass.ui.scenes.SceneListScreen
import com.magnate.compass.ui.scenes.SceneViewModel
import com.magnate.compass.ui.search.SearchScreen
import com.magnate.compass.ui.search.SearchViewModel
import com.magnate.compass.ui.tags.TagManageScreen
import com.magnate.compass.ui.tags.TagViewModel

/**
 * 导航图（design-gui.md §2）。
 *
 * 不用底部导航栏：主页是唯一一级页面，其余都是二级。底部导航会永久占用约 80dp，
 * 而罗盘是核心，不该为不常用的管理页让位。
 *
 * 场景详情用 `launchSingleTop`：从记录详情点「为场景设置坐标」进入场景详情、
 * 再从那里进另一个场景时，不该在返回栈里堆一串同类型的页面。
 */
@Composable
fun MagnateNavHost(
    navController: NavHostController,
    onRecordSaved: (SaveOutcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.COMPASS,
        modifier = modifier,
    ) {
        composable(Routes.COMPASS) {
            val viewModel: CompassViewModel = viewModel(factory = AppViewModelProvider.Factory)
            CompassScreen(
                viewModel = viewModel,
                onOpenRecords = { navController.navigate(Routes.RECORDS) },
                onOpenScenes = { navController.navigate(Routes.SCENES) },
                onOpenSatellite = { navController.navigate(Routes.SATELLITE) },
                onRecordSaved = onRecordSaved,
            )
        }

        composable(Routes.SATELLITE) {
            val viewModel: SatelliteViewModel = viewModel(factory = AppViewModelProvider.Factory)
            SatelliteScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.RECORDS) {
            val viewModel: RecordsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            RecordListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenRecord = { navController.navigate(Routes.recordDetail(it)) },
                onOpenScenes = { navController.navigate(Routes.SCENES) },
                onOpenTags = { navController.navigate(Routes.TAGS) },
                onGoMeasure = {
                    // 回主页而不是再叠一层——「去测量」的意图就是回到罗盘
                    navController.popBackStack(Routes.COMPASS, inclusive = false)
                },
            )
        }

        composable(Routes.SEARCH) {
            val viewModel: SearchViewModel = viewModel(factory = AppViewModelProvider.Factory)
            SearchScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenRecord = { navController.navigate(Routes.recordDetail(it)) },
            )
        }

        composable(Routes.SCENES) {
            val viewModel: SceneViewModel = viewModel(factory = AppViewModelProvider.Factory)
            SceneListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenScene = { navController.navigate(Routes.sceneDetail(it)) },
                onOpenRecord = { navController.navigate(Routes.recordDetail(it)) },
            )
        }

        composable(Routes.TAGS) {
            val viewModel: TagViewModel = viewModel(factory = AppViewModelProvider.Factory)
            TagManageScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RECORD_DETAIL,
            arguments = listOf(navArgument(Routes.RECORD_ID_ARG) { type = NavType.LongType }),
        ) {
            val viewModel: RecordDetailViewModel = viewModel(factory = AppViewModelProvider.Factory)
            RecordDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenScene = { navController.navigate(Routes.sceneDetail(it)) },
            )
        }

        composable(
            route = Routes.SCENE_DETAIL,
            arguments = listOf(navArgument(Routes.SCENE_ID_ARG) { type = NavType.LongType }),
        ) {
            val viewModel: SceneDetailViewModel = viewModel(factory = AppViewModelProvider.Factory)
            SceneDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenRecord = { navController.navigate(Routes.recordDetail(it)) },
                // 换个场景就是换个页面实例，popUpTo 掉当前这个，返回键回到来源页
                onOpenScene = { sceneId ->
                    navController.navigate(Routes.sceneDetail(sceneId)) {
                        popUpTo(Routes.SCENE_DETAIL) { inclusive = true }
                    }
                },
                onViewAllRecords = { navController.navigate(Routes.RECORDS) },
                onOpenSatellite = { navController.navigate(Routes.SATELLITE) },
            )
        }
    }
}
