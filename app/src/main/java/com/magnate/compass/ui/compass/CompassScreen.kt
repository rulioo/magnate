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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.data.RecordDraft
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.sensor.AccuracyLevel
import com.magnate.compass.ui.common.AccuracyBadge
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.scenes.ScenePickerSheet
import com.magnate.compass.ui.theme.AzimuthReadoutStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.ButtonSubtitleStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

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
    onRecordSaved: (SaveOutcome) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val recordCount by viewModel.recordCount.collectAsStateWithLifecycle()
    val recentTags by viewModel.observeRecentTags()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showScenePicker by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }

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

        // 表盘尺寸取「宽度允许」「高度允许」「绝对上限」三者中的最小值，再抬到下限。
        //
        // 高度这一项是必需的：只看宽度的话，竖屏窄机上能算出 320dp，
        // 而 320dp 表盘加上状态栏、场景条、读数、磁场卡、主按钮远超一屏高，
        // 会把读数挤出可视区——表盘越大反而越难用。
        // 下限则保证任何情况下表盘都不会缩到让方位字母挤成一团（design-gui.md §13）。
        val dialFits = if (landscape) {
            minOf(maxWidth / 2 - 40.dp, maxHeight - 16.dp, MAX_DIAL)
        } else {
            minOf(maxWidth - 32.dp, maxHeight * 0.45f, MAX_DIAL)
        }
        val dialSize = dialFits.coerceAtLeast(MIN_DIAL)

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
                        modifier = Modifier.size(dialSize),
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
                        onCalibrate = { showCalibration = true },
                        onToggleTrueNorth = viewModel::setTrueNorth,
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
                onCalibrate = { showCalibration = true },
                onToggleTrueNorth = viewModel::setTrueNorth,
                showDial = true,
                dialSize = dialSize,
                onSave = onSaveClick,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            )
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

    if (showCalibration) {
        CalibrationDialog(
            accuracy = state.accuracy,
            onDismiss = { showCalibration = false },
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
    showDial: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 320.dp,
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
                    modifier = Modifier.size(dialSize),
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

        MetaRow(state = state, onToggleTrueNorth = onToggleTrueNorth)

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

/** ⑥ 元信息行：磁偏角 + 真北开关。坐标状态已移入场景条（§3.2）。 */
@Composable
private fun MetaRow(
    state: CompassUiState,
    onToggleTrueNorth: (Boolean) -> Unit,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val declinationMissing = state.trueNorth && state.declination == null

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MagnateIcons.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    !state.trueNorth -> "磁偏角未启用"
                    declinationMissing -> "磁偏角需要坐标"
                    else -> "磁偏角 ${GeoFormat.formatSigned(state.declination!!)}°"
                },
                style = LabelStyle,
                color = if (declinationMissing) {
                    semanticColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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

/** 校准引导。低精度与不可信都会走到这里（design.md §5.9）。 */
@Composable
private fun CalibrationDialog(
    accuracy: AccuracyLevel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("磁力计需要校准", style = BodyStyle) },
        text = {
            Text(
                text = "手持手机在空中画几个「8」字，重复 5–10 次。\n\n" +
                    "当前精度：${accuracy.label()}。附近有铁磁物体或强电流时读数也会偏差，" +
                    "可以换个位置再测。",
                style = BodyStyle,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

private fun AccuracyLevel.label(): String = when (this) {
    AccuracyLevel.HIGH -> "高"
    AccuracyLevel.MEDIUM -> "中"
    AccuracyLevel.LOW -> "低"
    AccuracyLevel.UNRELIABLE -> "不可信"
    AccuracyLevel.UNKNOWN -> "未知"
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
