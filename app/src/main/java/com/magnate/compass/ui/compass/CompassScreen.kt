package com.magnate.compass.ui.compass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.data.RecordDraft
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.ui.common.AccuracyBadge
import com.magnate.compass.ui.common.Banner
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.LocalLocationPermissionGate
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.common.acquireLocationWithPermission
import com.magnate.compass.ui.scenes.ScenePickerSheet
import com.magnate.compass.ui.theme.AzimuthReadoutStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.ButtonSubtitleStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.LocationPermissionLevel
import com.magnate.compass.util.locationFailureMessage
import com.magnate.compass.util.offersSettings
import kotlinx.coroutines.launch

/**
 * 主页（design-gui.md §3）。
 *
 * 从状态栏到主按钮，七个区域全部服务于同一件事：**让用户在按下保存之前，
 * 确信自己存的是哪一个数、归到哪一个场景。**
 */
@Composable
fun CompassScreen(
    viewModel: CompassViewModel,
    onOpenRecords: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenSatellite: () -> Unit,
    onRecordSaved: (SaveOutcome) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val recordCount by viewModel.recordCount.collectAsStateWithLifecycle()
    val recentTags by viewModel.observeRecentTags()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gate = LocalLocationPermissionGate.current

    var showScenePicker by remember { mutableStateOf(false) }

    // 校准是**主页上的一种模式**，不是一条可以离开的路线——用户要一边画 8 字
    // 一边看着读数回升，那两件事必须同屏（design-gui.md §11「校准中」）
    val calibrationMode by viewModel.calibrationMode.collectAsStateWithLifecycle()

    // 保存面板的输入：**点击瞬间冻结**。之后传感器继续刷新，面板里的数字纹丝不动（§5.5）。
    var pendingDraft by remember { mutableStateOf<RecordDraft?>(null) }
    var pendingScene by remember { mutableStateOf<SceneEntity?>(null) }

    // 屏幕可见才注册传感器，onPause 立即注销——零后台耗电（design.md §9）
    LifecycleResumeEffect(Unit) {
        viewModel.startListening()
        viewModel.refreshPermission()
        onPauseOrDispose { viewModel.stopListening() }
    }

    if (!state.sensorAvailable) {
        EmptyState(
            icon = MagnateIcons.SensorsOff,
            title = "这台设备没有可用的磁力计",
            message = "无法测量方位角与磁场强度。记录列表、场景管理与搜索仍可正常使用。",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "查看记录",
            onAction = onOpenRecords,
            secondaryActionLabel = "管理场景",
            onSecondaryAction = onOpenScenes,
        )
        return
    }

    val onSaveClick = {
        pendingScene = state.activeScene
        pendingDraft = viewModel.freezeDraft()
        Unit
    }

    /**
     * 「开启定位 / 去取坐标」。这两件事在实现上是同一件：先确保有权限，再拿一个坐标。
     *
     * **不必先 `gate.request` 再取坐标**——`acquireLocationWithPermission` 自己会处理权限，
     * 没授权就先弹说明框、再弹系统框，授权后自动重试一次。手动分两步会连弹两次框。
     *
     * 成功时走 [CompassViewModel.onLocationPermissionGranted] 而不是只刷新状态：
     * 授权之前 `startTracking()` 会在权限检查处提前返回且 `tracking` 保持 `false`，
     * 主页的坐标预热根本没启动过，光刷新权限不会让它开始。
     */
    fun acquireFromHome() {
        acquireLocationWithPermission(
            scope = scope,
            gate = gate,
            acquire = { viewModel.acquireLocation(LocationProvider.DEFAULT_TIMEOUT_MS) },
            onSuccess = { viewModel.onLocationPermissionGranted() },
            onFailure = { failure ->
                viewModel.refreshPermission()
                val message = locationFailureMessage(failure.result, failure.permissionState)
                    ?: return@acquireLocationWithPermission
                val settings = offersSettings(failure.result, failure.permissionState)
                scope.launch {
                    val outcome = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = if (settings) "去设置" else null,
                    )
                    if (outcome == SnackbarResult.ActionPerformed) gate.openAppSettings()
                }
            },
        )
    }

    /**
     * 「升级到精确」。
     *
     * 单独一个入口是必需的：Android 12+ 用户选了「大致位置」时，权限判定是**已授予**，
     * 于是 `gate.request` 会同步回调 `onGranted` 而不弹任何窗——那会变成一个
     * 按下去毫无反应的按钮。
     */
    fun upgradeToFine() {
        gate.requestFineLocation(
            onGranted = {
                viewModel.onLocationPermissionGranted()
                acquireFromHome()
            },
            onDenied = { viewModel.refreshPermission() },
        )
    }

    // 边到边（enableEdgeToEdge）之下，其余六个页面都有 Scaffold 自动让开系统栏，
    // 唯独主页没有——不补这一步，状态栏会直接压在精度徽标那一行上。
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // 在嵌套的 Box/Row 作用域里，隐式接收者不再唯一，`maxWidth` 会解析失败，
        // 因此在这里先取出来（design-gui.md §14 横屏左右分栏：左罗盘，右信息与操作）
        val landscape = maxWidth > maxHeight

        val bannerMessage = state.calibrationBanner

        // 校准面板的高度上限。**表盘在校准期间必须仍然可见**——
        // design-gui.md §11 写的就是「顶部提示条 + 表盘降为 50% 透明度」，
        // 让面板吃掉整屏等于把校准偷偷做成了一条独立路由，违背那条承诺。
        val panelMaxHeight = minOf(
            maxHeight * CALIBRATION_PANEL_MAX_FRACTION,
            CALIBRATION_PANEL_ABS_MAX,
        )

        // 顶部让出的高度。表盘尺寸必须把它扣掉：提示条是**常驻**的，
        // 不扣的话它会把读数挤出可视区，而 design-gui.md §3 明确反对挤压罗盘空间。
        val topReserve = when {
            calibrationMode -> panelMaxHeight + 16.dp
            bannerMessage != null -> BANNER_ALLOWANCE
            else -> 0.dp
        }

        // 表盘尺寸取「宽度允许」「高度允许」「绝对上限」三者中的最小值，再抬到下限。
        //
        // 高度这一项是必需的：只看宽度的话，竖屏窄机上能算出 320dp，
        // 而 320dp 表盘加上状态栏、场景条、读数、磁场卡、主按钮远超一屏高，
        // 会把读数挤出可视区——表盘越大反而越难用。
        // 下限则保证任何情况下表盘都不会缩到让方位字母挤成一团（design-gui.md §13）。
        val dialFits = if (landscape) {
            minOf(maxWidth / 2 - 40.dp, maxHeight - topReserve - 16.dp, MAX_DIAL)
        } else {
            minOf(maxWidth - 32.dp, (maxHeight - topReserve) * 0.45f, MAX_DIAL)
        }
        val dialSize = dialFits.coerceAtLeast(MIN_DIAL)

        // 表盘统一降到 50% 透明度，横竖屏两条分支一致（design-gui.md §11）
        val dialAlpha = if (calibrationMode) CALIBRATION_DIAL_ALPHA else 1f

        Column(Modifier.fillMaxSize()) {
            if (calibrationMode) {
                CalibrationPanel(
                    state = state,
                    onFinish = viewModel::finishCalibration,
                    modifier = Modifier.heightIn(max = panelMaxHeight),
                )
            } else if (bannerMessage != null) {
                // 整条可点。文案里自带动词（「· 点此校准」「· 查看」），
                // 因为一个没有任何可点击线索的矩形，用户不会去点它
                Banner(message = bannerMessage, onClick = viewModel::startCalibration)
            }

            Box(Modifier.weight(1f)) {
                if (landscape) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CompassDial(
                                azimuthDeg = state.displayAzimuthDeg,
                                headingName = state.displayHeadingName,
                                modifier = Modifier
                                    .size(dialSize)
                                    .alpha(dialAlpha),
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Dashboard(
                                state = state,
                                recordCount = recordCount,
                                onOpenRecords = onOpenRecords,
                                onSceneBarClick = { showScenePicker = true },
                                onCalibrate = viewModel::startCalibration,
                                onToggleTrueNorth = viewModel::setTrueNorth,
                                onEnableLocation = ::acquireFromHome,
                                onUpgradeToFine = ::upgradeToFine,
                                onOpenSatellite = onOpenSatellite,
                                showDial = false,
                                onSave = onSaveClick,
                            )
                        }
                    }
                } else {
                    Dashboard(
                        state = state,
                        recordCount = recordCount,
                        onOpenRecords = onOpenRecords,
                        onSceneBarClick = { showScenePicker = true },
                        onCalibrate = viewModel::startCalibration,
                        onToggleTrueNorth = viewModel::setTrueNorth,
                        onEnableLocation = ::acquireFromHome,
                        onUpgradeToFine = ::upgradeToFine,
                        onOpenSatellite = onOpenSatellite,
                        showDial = true,
                        dialSize = dialSize,
                        dialAlpha = dialAlpha,
                        onSave = onSaveClick,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    )
                }

                // 主页没有 Scaffold（它自带 windowInsetsPadding，见上面那段注释），
                // 因此失败提示要自己挂一个宿主。悬浮在内容之上，不占布局。
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // ————————————————————— 浮层 —————————————————————

    if (showScenePicker) {
        ScenePickerSheet(
            scenes = scenes,
            activeSceneId = state.activeScene?.id,
            onSelect = { id -> if (id == null) viewModel.leaveScene() else viewModel.enterScene(id) },
            onCreate = {
                showScenePicker = false
                onOpenScenes()
            },
            onManage = {
                showScenePicker = false
                onOpenScenes()
            },
            onDismiss = { showScenePicker = false },
        )
    }

    pendingDraft?.let { draft ->
        SaveRecordSheet(
            draft = draft,
            // 场景归属在快照时刻确定，之后主页切换场景不影响已打开的面板（§5.5）
            initialScene = pendingScene,
            scenes = scenes,
            recentTags = recentTags,
            lastKnownLocation = state.lastKnownLocation,
            acquireLocation = viewModel::acquireLocation,
            onCreateTag = viewModel::createTag,
            onSave = viewModel::saveRecord,
            onSaved = { outcome ->
                pendingDraft = null
                pendingScene = null
                onRecordSaved(outcome)
            },
            onManageScenes = {
                pendingDraft = null
                pendingScene = null
                onOpenScenes()
            },
            onDismiss = {
                pendingDraft = null
                pendingScene = null
            },
        )
    }
}

/**
 * 纵向布局的仪表盘。横屏时 [showDial] 为 false——表盘由外层分栏单独摆放。
 */
@Composable
private fun Dashboard(
    state: CompassUiState,
    recordCount: Int,
    onOpenRecords: () -> Unit,
    onSceneBarClick: () -> Unit,
    onCalibrate: () -> Unit,
    onToggleTrueNorth: (Boolean) -> Unit,
    onEnableLocation: () -> Unit,
    onUpgradeToFine: () -> Unit,
    onOpenSatellite: () -> Unit,
    showDial: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 320.dp,
    /** 校准模式下表盘降到 50% 透明度，让视线落在顶部的校准面板上（design-gui.md §11）。 */
    dialAlpha: Float = 1f,
) {
    Column(modifier) {
        StatusBar(
            state = state,
            recordCount = recordCount,
            onOpenRecords = onOpenRecords,
            onCalibrate = onCalibrate,
        )

        Spacer(Modifier.height(8.dp))

        SceneBar(activeScene = state.activeScene, onClick = onSceneBarClick)

        if (showDial) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CompassDial(
                    azimuthDeg = state.displayAzimuthDeg,
                    headingName = state.displayHeadingName,
                    modifier = Modifier
                        .size(dialSize)
                        .alpha(dialAlpha),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        AzimuthReadout(state)

        Spacer(Modifier.height(12.dp))

        // 倾斜提示原先散在读数下面单起一行，现在收纳进姿态卡片底部——
        // 它讲的是姿态，长在姿态卡片里才不会被读成磁场卡片的第二条警告
        TiltCard(
            pitchDeg = state.pitchDeg,
            rollDeg = state.rollDeg,
            tiltDeg = state.tiltDeg,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        MagneticFieldCard(
            magnitudeUt = state.magnitudeUt,
            magX = state.magX,
            magY = state.magY,
            magZ = state.magZ,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        MetaRow(
            state = state,
            onToggleTrueNorth = onToggleTrueNorth,
            onEnableLocation = onEnableLocation,
            onUpgradeToFine = onUpgradeToFine,
        )

        Spacer(Modifier.height(4.dp))

        SatelliteEntry(onClick = onOpenSatellite)

        Spacer(Modifier.height(12.dp))

        SaveButton(scene = state.activeScene, onClick = onSave)

        Spacer(Modifier.height(16.dp))
    }
}

/** ① 状态栏：左精度、右记录数。精度徽标在需要校准时才可点。 */
@Composable
private fun StatusBar(
    state: CompassUiState,
    recordCount: Int,
    onOpenRecords: () -> Unit,
    onCalibrate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = state.needsCalibration, onClick = onCalibrate)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccuracyBadge(level = state.accuracy)
        }

        Row(
            modifier = Modifier
                .clickable(onClick = onOpenRecords)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = recordCount.toString(),
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ④ 方位角读数。数字不做滚动动画——20Hz 下滚动动画永远播不完（§13）。 */
@Composable
private fun AzimuthReadout(state: CompassUiState) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${state.displayAzimuthDeg.toInt()}°",
            style = AzimuthReadoutStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.displayHeadingName,
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 卫星信号入口。
 *
 * **刻意不显示「可见 N 颗 / 已用 M 颗」。** 那需要主页常驻订阅 GNSS 流，
 * 而订阅会带一个保活定位请求——GNSS 引擎一旦转起来就不便宜，主页却要一直开着。
 * 主页现有的预热是 60s/50m 的低频注册，两者完全不是一个量级。
 * 为一行随时可查的实时数字付这个电费不划算，而用户要它的时候，
 * 点一下就有一个专门的页面把全部细节摆出来。
 */
@Composable
private fun SatelliteEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MagnateIcons.Satellite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "查看卫星信号",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        Text(text = "›", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 磁偏角状态行的四种形态（§3.2）。
 *
 * @param label 显示文字。
 * @param actionable 是否可点。可点的那三种都是**用户点出来才有手势**的入口，
 *   所以它们可以走到权限门那边去。
 * @param warning 是否用警告色。只有「还差一步才能用」的三种是警告；
 *   真北关掉只是没启用，不是问题。
 */
private class DeclinationStatus(
    val label: String,
    val actionable: Boolean,
    val warning: Boolean,
)

/**
 * ⑥ 元信息行：磁偏角状态 + 真北开关。坐标状态已移入场景条（§3.2）。
 *
 * **这一行原先只有两种形态**：有效值，或者一句「磁偏角需要坐标」。而后者把
 * 「没权限」「只有粗略定位」「有权限但还没取到坐标」三件完全不同的事压成了一句，
 * 用户看到它不知道该去做什么——尤其在没有权限时，旁边没有任何入口能让他补救。
 */
@Composable
private fun MetaRow(
    state: CompassUiState,
    onToggleTrueNorth: (Boolean) -> Unit,
    onEnableLocation: () -> Unit,
    onUpgradeToFine: () -> Unit,
) {
    val semanticColors = LocalMagnateSemanticColors.current

    val status = when {
        !state.trueNorth -> DeclinationStatus("磁偏角未启用", actionable = false, warning = false)

        state.locationPermission == LocationPermissionLevel.NONE ->
            DeclinationStatus("未开启定位 · 开启", actionable = true, warning = true)

        // Android 12+ 选了「大致位置」时只给约 ±2km 的坐标。不说明白，
        // 用户会以为磁偏角不准是应用算错了（§7）
        state.locationPermission == LocationPermissionLevel.COARSE_ONLY ->
            DeclinationStatus("粗略定位 · 磁偏角精度受限", actionable = true, warning = true)

        state.declination == null ->
            DeclinationStatus("磁偏角需要坐标 · 去取坐标", actionable = true, warning = true)

        else -> DeclinationStatus(
            label = "磁偏角 ${GeoFormat.formatSigned(state.declination!!)}°",
            actionable = false,
            warning = false,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.clickable(enabled = status.actionable) {
                when {
                    state.locationPermission == LocationPermissionLevel.COARSE_ONLY -> onUpgradeToFine()
                    // 「未开启定位」与「需要坐标」在实现上是同一条路：
                    // 先确保有权限，再拿一个坐标
                    else -> onEnableLocation()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MagnateIcons.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status.label,
                style = LabelStyle,
                color = if (status.warning) {
                    semanticColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // 可点时补一个箭头。文案里的「开启 / 去取坐标」是动词，但没有视觉上的
            // 可点击线索，用户不一定会去点它
            if (status.actionable) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "›",
                    style = LabelStyle,
                    color = semanticColors.warning,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("真北", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = state.trueNorth,
                onCheckedChange = onToggleTrueNorth,
            )
        }
    }
}

/**
 * ⑦ 主按钮：至少 64dp 的双行按钮（字体放大时随之长高，不裁字）。
 *
 * 副标题是**防误存的关键设计**。用户举起手机、对准方向、点保存，
 * 这个动作在实地工作中会重复几十次；一旦场景选错而未被察觉，
 * 因为 FIXED 是引用语义，整批数据都会带上错误的坐标，而且错得「很一致」，
 * 事后极难发现（§4.2）。
 */
@Composable
private fun SaveButton(scene: SceneEntity?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        // 用 heightIn 而不是 height：固定 64dp 在系统字体放大后会裁掉副标题，
        // 而副标题正是防误存的那行字，绝不能是第一个消失的
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("保存当前读数", style = BodyStyle)
            Text(
                text = saveTargetLabel(scene),
                style = ButtonSubtitleStyle,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 表盘边长上限：再大也不会更容易读，只会把下面的读数挤出屏幕。 */
private val MAX_DIAL = 320.dp

/**
 * 表盘边长下限。
 *
 * 低于这个尺寸，盘面元素就开始互相侵占：方位字母的环半径收窄、
 * 刻度内端逼近中心。180dp 是「八个方位字母仍能各自站开」的实测下限，
 * 再小 `CompassDial` 会主动降级成只画 N/E/S/W（见其三级降级注释）。
 */
private val MIN_DIAL = 180.dp

/**
 * 校准面板最多占屏高的比例。
 *
 * 上限来自 design-gui.md §11——校准期间**表盘必须仍然可见**，
 * 面板吃掉整屏就等于把校准做成了独立路由，而用户需要一边画 8 字一边看读数。
 */
private const val CALIBRATION_PANEL_MAX_FRACTION = 0.55f

/** 大屏上的绝对上限：再高也不会更好读，只会让视线离开表盘更远。 */
private val CALIBRATION_PANEL_ABS_MAX = 380.dp

/** 校准模式下表盘的透明度（design-gui.md §11）。 */
private const val CALIBRATION_DIAL_ALPHA = 0.5f

/**
 * 常驻提示条给表盘让出的高度。
 *
 * 提示条是**常驻**的，不预留的话它会把下方内容整体下推，
 * 而 design-gui.md §3 明确反对挤压罗盘空间。宁可让表盘小一点。
 */
private val BANNER_ALLOWANCE = 64.dp
