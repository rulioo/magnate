package com.magnate.compass.ui.records

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.location
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.sensor.accuracyLevelOf
import com.magnate.compass.ui.common.AccuracyBadge
import com.magnate.compass.ui.common.AxisValuesRow
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.LevelBubble
import com.magnate.compass.ui.common.LocalLocationPermissionGate
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.common.MagneticBar
import com.magnate.compass.ui.common.acquireLocationWithPermission
import com.magnate.compass.ui.common.openInMap
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.SectionTitle
import com.magnate.compass.ui.common.TagChip
import com.magnate.compass.ui.theme.AzimuthDetailStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.ui.theme.MagnitudeDetailStyle
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat
import com.magnate.compass.util.locationFailureMessage
import com.magnate.compass.util.offersSettings
import kotlinx.coroutines.launch

/**
 * 记录详情（design-gui.md §7）。
 *
 * **测量值不可编辑**（§7.4）——没有改方位角、磁场、时间的入口。可编辑的只有
 * 备注、标签、归属场景（仅移出）与坐标（补录）。这条约束同时简化了实现：
 * 测量值不可变 → 无需并发控制、无需审计日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    viewModel: RecordDetailViewModel,
    onBack: () -> Unit,
    onOpenScene: (Long) -> Unit,
) {
    val item by viewModel.record.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gate = LocalLocationPermissionGate.current

    var showMenu by remember { mutableStateOf(false) }
    var showMoveOutDialog by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var acquiring by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("记录详情", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (item != null) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                            }
                            RecordMenu(
                                expanded = showMenu,
                                onDismiss = { showMenu = false },
                                onCopy = {
                                    showMenu = false
                                    val text = formatRecordAsText(item!!)
                                    copyToClipboard(context, text)
                                    scope.launch {
                                        // Android 13 起系统自带复制确认气泡，再弹一次是重复打扰
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            snackbarHostState.showSnackbar("已复制到剪贴板")
                                        }
                                    }
                                },
                                onShare = {
                                    showMenu = false
                                    shareText(context, formatRecordAsText(item!!))
                                },
                                onDelete = {
                                    showMenu = false
                                    scope.launch {
                                        val snapshot = viewModel.delete() ?: return@launch
                                        deleted = true
                                        val result = snackbarHostState.showSnackbar(
                                            message = "已删除记录",
                                            actionLabel = "撤销",
                                            withDismissAction = true,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.restore(snapshot)
                                            deleted = false
                                        } else {
                                            onBack()
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        val current = item
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                current != null -> DetailContent(
                    item = current,
                    onOpenScene = onOpenScene,
                    onMoveOut = { showMoveOutDialog = true },
                    onEditTags = { showTagPicker = true },
                    onNoteCommit = viewModel::updateNote,
                    acquiring = acquiring,
                    onBackfill = {
                        acquiring = true
                        acquireLocationWithPermission(
                            scope = scope,
                            gate = gate,
                            acquire = viewModel::acquireLocation,
                            onSuccess = { point ->
                                acquiring = false
                                viewModel.applyBackfilledLocation(point)
                                scope.launch { snackbarHostState.showSnackbar("已补录坐标") }
                            },
                            onFailure = { failure ->
                                acquiring = false
                                val message = locationFailureMessage(
                                    failure.result,
                                    failure.permissionState,
                                ) ?: return@acquireLocationWithPermission
                                val settings = offersSettings(
                                    failure.result,
                                    failure.permissionState,
                                )
                                scope.launch {
                                    val outcome = snackbarHostState.showSnackbar(
                                        message = message,
                                        actionLabel = if (settings) "去设置" else null,
                                    )
                                    if (outcome == SnackbarResult.ActionPerformed) {
                                        gate.openAppSettings()
                                    }
                                }
                            },
                        )
                    },
                )

                deleted -> EmptyState(
                    icon = Icons.Outlined.Delete,
                    title = "记录已删除",
                    message = "撤销可恢复，包括它的标签关联",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "返回列表",
                    onAction = onBack,
                )

                else -> EmptyState(
                    icon = MagnateIcons.LocationOff,
                    title = "记录不存在",
                    message = "这条记录可能已被删除",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "返回列表",
                    onAction = onBack,
                )
            }
        }
    }

    // —— 移出场景：必须说明后果（§7.3）——
    if (showMoveOutDialog && item != null) {
        val scene = item!!.scene!!
        val becomesNoCoordinate = item!!.record.latitude == null
        MoveOutDialog(
            sceneName = scene.name,
            becomesNoCoordinate = becomesNoCoordinate,
            onConfirm = {
                showMoveOutDialog = false
                viewModel.moveOutOfScene()
                scope.launch { snackbarHostState.showSnackbar("已移出场景") }
            },
            onDismiss = { showMoveOutDialog = false },
        )
    }

    if (showTagPicker && item != null) {
        TagEditorDialog(
            allTagNames = allTags.map { it.tag.name to it.tag.colorIndex },
            selected = item!!.tags.map { it.name },
            onConfirm = { names ->
                showTagPicker = false
                viewModel.setTags(names)
            },
            onCreate = viewModel::createTag,
            onDismiss = { showTagPicker = false },
        )
    }
}

// ————————————————————————— 主体 —————————————————————————

@Composable
private fun DetailContent(
    item: RecordWithRelations,
    onOpenScene: (Long) -> Unit,
    onMoveOut: () -> Unit,
    onEditTags: () -> Unit,
    onNoteCommit: (String?) -> Unit,
    acquiring: Boolean,
    onBackfill: () -> Unit,
) {
    val record = item.record

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 方位角 ——
        Text(
            text = "${record.azimuthDeg.toInt()}°",
            style = AzimuthDetailStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = CompassMath.headingName(record.azimuthDeg),
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.isTrueNorth) {
            Text(
                text = "真北方位",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = TimeFormat.formatDateTime(record.timestamp),
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        MiniCompass(azimuthDeg = record.azimuthDeg)
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 磁场 ——
        SectionTitle("磁场", Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("总强度", style = BodyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = GeoFormat.formatMagnitude(record.magnitude),
                style = MagnitudeDetailStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        MagneticBar(record.magnitude, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        AxisValuesRow(magX = record.magX, magY = record.magY, magZ = record.magZ)
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 姿态 ——
        SectionTitle("姿态", Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 存的是测量当时那一帧的姿态，气泡是**静态**的——它记录的是
            // 「按下保存时手机有多平」，不是此刻的实时姿态（§7.2）
            LevelBubble(
                pitchDeg = record.pitchDeg,
                rollDeg = record.rollDeg,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.width(16.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AttitudeValue("俯仰", GeoFormat.formatSigned(record.pitchDeg) + "°", Modifier.weight(1f))
                AttitudeValue("翻滚", GeoFormat.formatSigned(record.rollDeg) + "°", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        val recordTilt = CompassMath.tiltFromHorizontal(record.pitchDeg, record.rollDeg)
        val recordLevel = recordTilt <= CompassMath.LEVEL_TOLERANCE_DEGREES
        Text(
            // 合成倾角恒为非负，不用 formatSigned——那个函数会强制带符号，
            // 在「离水平多少度」这个量上，前面的 `+` 只会让人以为还有负的一侧
            text = "合成倾角 ${recordTilt.toInt()}°" + if (recordLevel) "（已水平）" else "",
            style = LabelStyle,
            color = if (recordLevel) {
                LocalMagnateSemanticColors.current.success
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "传感器精度",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            AccuracyBadge(accuracyLevelOf(record.sensorAccuracy))
        }
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 坐标（四种形态，§7.2）——
        SectionTitle("坐标", Modifier.fillMaxWidth())
        CoordinateSection(
            item = item,
            acquiring = acquiring,
            onOpenScene = onOpenScene,
            onBackfill = onBackfill,
        )
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 归属场景 ——
        SectionTitle("归属场景", Modifier.fillMaxWidth())
        SceneSection(item = item, onOpenScene = onOpenScene, onMoveOut = onMoveOut)
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 标签 ——
        SectionTitle("标签", Modifier.fillMaxWidth())
        TagsSection(item = item, onEditTags = onEditTags)
        Spacer(Modifier.height(16.dp))
        SectionDivider()

        // —— 备注 ——
        SectionTitle("备注", Modifier.fillMaxWidth())
        NoteSection(recordId = record.id, initial = record.note, onCommit = onNoteCommit)

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AttitudeValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = BodyStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 坐标区。
 *
 * 「场景坐标获取于 …」这一行是必要的：FIXED 模式的坐标继承是**引用语义**——
 * 场景坐标后来被改了，这条记录的坐标就跟着变了。显示获取时间，用户才知道
 * 这个坐标有多旧、以及它是否在本次测量之前取得（§7.2）。
 */
@Composable
private fun CoordinateSection(
    item: RecordWithRelations,
    acquiring: Boolean,
    onOpenScene: (Long) -> Unit,
    onBackfill: () -> Unit,
) {
    val location = item.location

    Column(Modifier.fillMaxWidth()) {
        when (location) {
            is EffectiveLocation.Measured -> {
                CoordinateLines(location.point.latitude, location.point.longitude, location)
                SourceLine("📡", "来源：本机实测")
                Spacer(Modifier.height(12.dp))
                MapButton(location)
            }

            is EffectiveLocation.FromScene -> {
                CoordinateLines(location.point.latitude, location.point.longitude, location)
                SourceLine("📐", "来源：场景「${location.sceneName}」")
                Text(
                    text = "场景坐标获取于 ${TimeFormat.formatDateTime(location.point.locatedAt)}",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                MapButton(location)
            }

            is EffectiveLocation.SceneWithoutCoordinate -> {
                NoCoordinateLines()
                Text(
                    text = "归属场景「${location.sceneName}」尚未设置坐标",
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 「为场景设置坐标」优于「补录坐标」：它一次性解决该场景下**所有**记录
                // 的坐标问题，而不是只修这一条（§7.2）
                Button(
                    onClick = { onOpenScene(location.sceneId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("为场景设置坐标")
                }
            }

            EffectiveLocation.None -> {
                NoCoordinateLines()
                Text(
                    text = "可在室外定位良好时补录",
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onBackfill,
                    enabled = !acquiring,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (acquiring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("定位中…")
                    } else {
                        Text("补录坐标")
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateLines(
    latitude: Double,
    longitude: Double,
    location: EffectiveLocation,
) {
    val point = location.pointOrNull
    Text(
        text = GeoFormat.formatCoordinates(latitude, longitude),
        style = BodyStyle,
        color = MaterialTheme.colorScheme.onSurface,
    )
    // 三个字段各自可能缺：海拔要设备与定位方式都支持才有，provider 老数据可能为空。
    // 用 listOfNotNull 拼，缺一项就少一项，不会留下「· ·」这样的空档。
    val details = listOfNotNull(
        GeoFormat.formatAltitude(point?.altitude),
        "定位精度 ${GeoFormat.formatAccuracy(point?.accuracyMeters ?: Float.MAX_VALUE)}",
        point?.provider,
    )
    Text(
        text = details.joinToString(" · "),
        style = LabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoCoordinateLines() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⚠", style = BodyStyle)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "未记录坐标",
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SourceLine(symbol: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, style = LabelStyle)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = LabelStyle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MapButton(location: EffectiveLocation) {
    val point = location.pointOrNull ?: return
    val context = LocalContext.current
    OutlinedButton(
        onClick = { openInMap(context, point.latitude, point.longitude) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(MagnateIcons.Map, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("在地图中打开")
    }
}

/**
 * 归属场景区（§7.3）。
 *
 * **不允许在详情页直接改归属到另一个场景**——那会让用户以为坐标也跟着变，
 * 实际上只是换了分组。要保持「归属场景在测量时确定」这一语义的单纯性。
 */
@Composable
private fun SceneSection(
    item: RecordWithRelations,
    onOpenScene: (Long) -> Unit,
    onMoveOut: () -> Unit,
) {
    val scene = item.scene

    if (scene == null) {
        Text(
            text = "未归属场景",
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "归属场景在测量时确定。若要把这条记录归入某个场景，请进入该场景重新测量。",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = scene.name,
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (scene.coordMode == CoordMode.FIXED) "固定坐标" else "跟随定位",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onOpenScene(scene.id) }) { Text("查看场景") }
    }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = onMoveOut, modifier = Modifier.fillMaxWidth()) {
        Text("移出场景")
    }
}

@Composable
private fun TagsSection(item: RecordWithRelations, onEditTags: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.tags.forEach { tag ->
            TagChip(
                name = tag.name,
                colorIndex = tag.colorIndex,
                selected = true,
                onClick = onEditTags,
            )
        }
        TextButton(onClick = onEditTags) { Text("+ 添加") }
    }
    if (item.tags.isEmpty()) {
        Text(
            text = "还没有标签。标签用来横向归类，与场景的纵向归属互补。",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 备注编辑。
 *
 * 用 `rememberSaveable(recordId)` 只在记录首次加载时取初值，**之后不再跟随 record 回流**——
 * 否则用户打字打到一半，标签一改触发 Flow 重发，输入框内容会被服务端的旧备注覆盖。
 */
@Composable
private fun NoteSection(
    recordId: Long,
    initial: String?,
    onCommit: (String?) -> Unit,
) {
    var text by rememberSaveable(recordId) { mutableStateOf(initial.orEmpty()) }
    var lastCommitted by rememberSaveable(recordId) { mutableStateOf(initial.orEmpty()) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.isFocused && text != lastCommitted) {
                    lastCommitted = text
                    onCommit(text)
                }
            },
        placeholder = { Text("记录测量点、环境等", style = BodyStyle) },
        minLines = 2,
        textStyle = BodyStyle,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "离开输入框时自动保存",
        style = LabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ————————————————————————— 对话框与菜单 —————————————————————————

@Composable
private fun RecordMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("复制为文本", style = BodyStyle) },
            leadingIcon = { Icon(MagnateIcons.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
        DropdownMenuItem(
            text = { Text("分享", style = BodyStyle) },
            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
            onClick = onShare,
        )
        DropdownMenuItem(
            text = { Text("删除记录", style = BodyStyle, color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

/** 移出场景的确认框。后果必须写在正文里，而不是留给用户自己推断（§7.3）。 */
@Composable
private fun MoveOutDialog(
    sceneName: String,
    becomesNoCoordinate: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移出场景？", style = BodyStyle) },
        text = {
            Text(
                text = buildString {
                    append("该记录将不再属于「$sceneName」。")
                    if (becomesNoCoordinate) {
                        append("\n\n由于这条记录没有本机实测坐标，移出后它将变为「无坐标」。")
                    } else {
                        append("\n\n它的坐标将回退为本机实测的那一次定位结果。")
                    }
                },
                style = BodyStyle,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移出") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 标签编辑器。
 *
 * 与保存面板的标签选择器（最近使用 + 快捷新建）不同，详情页是在**整理**已有记录，
 * 所以列出全量标签，并允许新建。
 */
@Composable
private fun TagEditorDialog(
    allTagNames: List<Pair<String, Int>>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    var newTag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签", style = BodyStyle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                allTagNames.forEach { (name, colorIndex) ->
                    val isSelected = name in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TagChip(
                            name = name,
                            colorIndex = colorIndex,
                            selected = isSelected,
                            enabled = isSelected || chosen.size < MAX_TAGS,
                            onClick = {
                                if (isSelected) chosen.remove(name) else chosen.add(name)
                            },
                        )
                    }
                }

                if (allTagNames.isEmpty()) {
                    Text(
                        text = "还没有标签，在下面新建一个",
                        style = LabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("新建标签", style = LabelStyle) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val name = newTag.trim()
                            if (name.isNotEmpty() && name !in chosen) {
                                chosen.add(name)
                                onCreate(name)
                                newTag = ""
                            }
                        },
                        enabled = newTag.isNotBlank() && chosen.size < MAX_TAGS,
                    ) {
                        Text("添加")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen.toList()) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 与保存面板一致的标签上限（design-gui.md §5.6）。 */
private const val MAX_TAGS = 10

// ————————————————————————— 系统交互 —————————————————————————

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("磁测记录", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享记录"))
}
