package com.magnate.compass.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.magnate.compass.MagnateApp
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.util.LocationPermissionLevel
import com.magnate.compass.util.LocationPermissionState
import com.magnate.compass.util.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 定位权限的申请入口。
 *
 * **为什么门只有一个、且挂在应用根节点**——三条都是具体的，不是风格问题：
 *
 * 1. `rememberLauncherForActivityResult` 内部是 `DisposableEffect { onDispose { unregister() } }`。
 *    把 launcher 挂在 `SaveRecordSheet` 这类面板里，它**在面板关闭的瞬间就注销了**。
 *    用户在系统权限框还开着时划掉面板，结果会投递给一个已死的 launcher，
 *    授权成功后的自动重试永远不触发——而这正是最需要它触发的时刻。
 *    根节点全程只组合一次，不存在这个窗口。
 * 2. `shouldShowRequestPermissionRationale` 与「跳系统设置」都需要 `Activity`，
 *    全应用只有这一个位置能稳定拿到它。
 * 3. 五个触发点分散在三个页面两个面板里，说明文案写成五份必然漂移。
 */
@Stable
class LocationPermissionGate internal constructor(
    private val activityProvider: () -> Activity?,
    private val launchRequest: () -> Unit,
    private val persistAsked: () -> Unit,
) {

    /** 是否已经弹过一次申请框。见 [com.magnate.compass.util.classifyLocationPermission]。 */
    var hasAsked by mutableStateOf(false)
        private set

    /** 说明理由的对话框是否可见。由 [com.magnate.compass.ui.MagnateAppRoot] 渲染。 */
    var showRationaleDialog by mutableStateOf(false)
        private set

    private var pending: Pending? = null

    /**
     * [hasAsked] 是否已从偏好里读回来。
     *
     * 这个标志是必需的，不是保险：读偏好是异步的，而「启动时申请一次」的判断
     * 依赖 [hasAsked]。若在它还没加载完时就去读（此时它必然是初始值 false），
     * **第二次以及之后每一次启动都会再问一遍**——而反复自动申请正是要避免的行为。
     */
    private val loaded = MutableStateFlow(false)

    /** 挂起直到 [hasAsked] 从偏好里读回来。 */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    internal fun markLoaded(asked: Boolean) {
        hasAsked = asked
        loaded.value = true
    }

    private class Pending(
        val onGranted: () -> Unit,
        val onDenied: (LocationPermissionState) -> Unit,
    )

    val state: LocationPermissionState
        get() = PermissionHelper.stateOf(activityProvider(), hasAsked)

    /** 当前权限档位。界面据此区分「精确」与「仅大致」。 */
    val level: LocationPermissionLevel
        get() = activityProvider()?.let { PermissionHelper.levelOf(it) } ?: LocationPermissionLevel.NONE

    /**
     * 发起一次申请。
     *
     * 已授权时**同步**回调 [onGranted]，不弹任何窗——调用方因此可以无脑调它，
     * 不必先自己查一遍权限。
     */
    fun request(
        onGranted: () -> Unit,
        onDenied: (LocationPermissionState) -> Unit = {},
    ) {
        when (state) {
            LocationPermissionState.GRANTED -> onGranted()

            // 还能弹框：先讲清楚为什么，再交给系统弹窗
            LocationPermissionState.DENIED_CAN_ASK -> {
                pending = Pending(onGranted, onDenied)
                showRationaleDialog = true
            }

            // 系统不再允许弹框了。此处故意**不弹任何窗**——再弹一次说明框、
            // 点「继续」之后系统毫无反应，用户只会以为应用坏了（design.md §7）
            LocationPermissionState.DENIED_PERMANENTLY -> onDenied(LocationPermissionState.DENIED_PERMANENTLY)
        }
    }

    /**
     * 申请把「大致位置」升级成「精确位置」。
     *
     * 与 [request] 的区别只在 Android 12+ 用户选了「仅大致位置」时显形：那时
     * 两个权限里 COARSE 已授予，[state] 因此是 `GRANTED`，[request] 会**同步**回调
     * `onGranted` 而不弹任何窗——「升级到精确」于是成了一个按下去毫无反应的按钮。
     *
     * 这条路径**不经过说明框**：用户已经给过一次定位权限，再讲一遍「我们为什么要定位」
     * 是多余的，系统自己的升级确认框已经说清了区别。
     */
    fun requestFineLocation(
        onGranted: () -> Unit,
        onDenied: (LocationPermissionState) -> Unit = {},
    ) {
        when {
            level == LocationPermissionLevel.FINE -> onGranted()

            state == LocationPermissionState.DENIED_PERMANENTLY ->
                onDenied(LocationPermissionState.DENIED_PERMANENTLY)

            else -> {
                pending = Pending(onGranted, onDenied)
                // 与 confirmRationale 同理：不等落盘，否则紧接着的系统回调里
                // hasAsked 仍是 false，一次刚发生的拒绝会被误判成「从未申请过」
                hasAsked = true
                persistAsked()
                launchRequest()
            }
        }
    }

    internal fun confirmRationale() {
        showRationaleDialog = false

        // 立刻置位，不等 DataStore 落盘：否则紧接着的系统回调里 state 仍会读到
        // hasAsked = false，把一次刚发生的拒绝误判成「从未申请过」，从此分类再也回不到永久拒绝
        hasAsked = true
        persistAsked()
        launchRequest()
    }

    internal fun dismissRationale() {
        showRationaleDialog = false
        val p = pending ?: return
        pending = null
        p.onDenied(LocationPermissionState.DENIED_CAN_ASK)
    }

    internal fun onSystemResult(granted: Boolean) {
        val p = pending ?: return
        pending = null

        // 用**重新分类后**的 state 而不是发起时那个：首次拒绝后 shouldShowRationale 变 true，
        // 第二次拒绝后变 false，分类会自动跟到「永久拒绝」，这里不需要任何特判。
        // （也正因为如此，hasAsked 必须在 confirmRationale 里就置位。）
        if (granted) p.onGranted() else p.onDenied(state)
    }

    /** 跳到本应用的系统设置页。只在永久拒绝时才有意义，即用户必须在应用之外解决。 */
    fun openAppSettings() {
        val activity = activityProvider() ?: return
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        runCatching { activity.startActivity(intent) }
    }
}

/**
 * 沿 `ContextWrapper` 往上找宿主 Activity。
 *
 * 不能用 `LocalActivity`：它与本项目锁定的 activity-compose 版本不兼容，
 * 当前版本里根本没有这个 API。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

val LocalLocationPermissionGate = staticCompositionLocalOf<LocationPermissionGate> {
    error("LocationPermissionGate 未提供——请在 MagnateAppRoot 中调用 rememberLocationPermissionGate()")
}

@Composable
fun rememberLocationPermissionGate(): LocationPermissionGate {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val settingsStore = remember(context) {
        (context.applicationContext as MagnateApp).container.settingsStore
    }

    // 门需要 launcher，而 launcher 的回调又需要门。用一个可变槽打破这个环：
    // 回调发生的时候门早已存在（它至少在第一次组合时就建好了）。
    val gateSlot = remember { mutableStateOf<LocationPermissionGate?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // 两个权限任一授予就算成功，与 PermissionHelper.hasLocationPermission 的判定保持一致
        gateSlot.value?.onSystemResult(grants.values.any { it })
    }

    val gate = remember(activity, launcher) {
        LocationPermissionGate(
            activityProvider = { activity },
            launchRequest = { launcher.launch(PermissionHelper.LOCATION_PERMISSIONS) },
            persistAsked = { scope.launch { settingsStore.setLocationPermissionRequested() } },
        )
    }
    SideEffect { gateSlot.value = gate }

    LaunchedEffect(settingsStore, gate) {
        gate.markLoaded(settingsStore.locationPermissionRequested.first())
    }

    return gate
}

/**
 * 申请前的说明（design.md §7）。两句话：为什么要、不给会怎样。
 *
 * 「不给也照常能用」必须写出来——它是事实，也是这个应用对权限克制的承诺：
 * 拒绝定位不会让任何一项测量功能失效，只是记录里没有坐标。
 */
@Composable
fun LocationPermissionRationaleDialog(gate: LocationPermissionGate) {
    if (!gate.showRationaleDialog) return

    AlertDialog(
        onDismissRequest = gate::dismissRationale,
        title = { Text("需要定位权限", style = BodyStyle) },
        text = {
            Text(
                text = "Magnate 用定位记录每个测量点的经纬度与海拔。\n\n" +
                    "不授权也能正常测量、保存与导出，只是记录里不会有坐标。" +
                    "坐标只保存在本机。",
                style = BodyStyle,
            )
        },
        confirmButton = { TextButton(onClick = gate::confirmRationale) { Text("继续") } },
        dismissButton = { TextButton(onClick = gate::dismissRationale) { Text("暂不") } },
    )
}
