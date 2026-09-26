package com.magnate.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.ui.nav.MagnateNavHost
import com.magnate.compass.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * 应用根节点。
 *
 * 保存成功的 Snackbar 挂在这一层而不是罗盘页里：它需要同时做两件事——
 * 显示坐标状态、并能点「查看」跳到刚存下的那条记录。跳转是导航行为，
 * 交给持有 NavController 的这一层最自然。
 *
 * 用 `Box` + 底部对齐而不是 `Scaffold`：每个二级页面自带 Scaffold 与 TopAppBar，
 * 再套一层 Scaffold 会让系统栏内边距被算两次，顶部凭空多出一截空白。
 * 根节点只需要一个浮在最上层的 Snackbar 位置。
 */
@Composable
fun MagnateAppRoot() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        MagnateNavHost(
            navController = navController,
            onRecordSaved = { outcome ->
                scope.launch { showSavedSnackbar(snackbarHostState, outcome, navController::navigate) }
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

/**
 * 保存后的确认（design-gui.md §5.1）。
 *
 * 文案随坐标状态变化——**三句话必须不同**，因为它们对应三种完全不同的数据质量：
 * 用户看到「已保存」就以为拿到的是一份带精确坐标的记录，
 * 而实际上可能是继承来的场景坐标，甚至根本没有坐标。
 *
 * 时长用 [SnackbarDuration.Long] 而非 Short：视障用户需要更多反应时间（§15）。
 *
 * **与文档的两处刻意偏差**：
 * 1. §5.1 的文案带读数（`已保存 · 247° 51.3μT`），但 `SaveOutcome` 只带 id 与坐标来源。
 *    为一句话再去查一次库不划算，改为只讲坐标来源——那才是这三句文案真正要区分的重点。
 * 2. §5.1 同时给了 `[查看]` 和 `[撤销]` 两个动作，而 Material3 的 Snackbar 只支持一个。
 *    保留「查看」：撤销的诉求在记录列表页有等价且更完整的出口（删除 + 5 秒撤销），
 *    而「刚存下的这条坐标到底算不算数」只有从这里能一步问到底。
 */
private suspend fun showSavedSnackbar(
    hostState: SnackbarHostState,
    outcome: SaveOutcome,
    navigate: (String) -> Unit,
) {
    val message = when (outcome.effectiveLocation) {
        is EffectiveLocation.Measured -> "已保存 · 使用本机坐标"
        is EffectiveLocation.FromScene -> "已保存 · 使用场景坐标"
        is EffectiveLocation.SceneWithoutCoordinate -> "已保存（无坐标）· 场景未设坐标"
        EffectiveLocation.None -> "已保存（无坐标）· 可稍后补录"
    }

    val result = hostState.showSnackbar(
        message = message,
        actionLabel = "查看",
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) {
        navigate(Routes.recordDetail(outcome.recordId))
    }
}
