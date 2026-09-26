package com.magnate.compass.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.SceneLocationChange
import com.magnate.compass.data.SceneWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.SectionTitle
import com.magnate.compass.ui.common.openInMap
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 场景详情（design-gui.md §9.2）。
 *
 * 两处必须显式告知后果的操作：
 * - **改坐标**（§9.4）：FIXED 模式下坐标是引用语义，改场景坐标 = 改所有记录的坐标。
 *   这是用户要的行为，但必须说清影响范围，并给 5 秒撤销。
 * - **删场景**（§9.5）：默认「保留坐标到记录」，否则删一个场景会让十几条记录的坐标凭空消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneDetailScreen(
    viewModel: SceneDetailViewModel,
    onBack: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenScene: (Long) -> Unit,
    onViewAllRecords: () -> Unit,
) {
    val scene by viewModel.scene.collectAsStateWithLifecycle()
    val recordCount by viewModel.recordCount.collectAsStateWithLifecycle()
    val recentRecords by viewModel.recentRecords.collectAsStateWithLifecycle()
    val isActive by viewModel.isActive.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showMenu by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var acquiring by remember { mutableStateOf(false) }
    var pendingPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var duplicateNameId by remember { mutableStateOf<Long?>(null) }

    /**
     * 落坐标的唯一入口。
     *
     * 不论走没走确认框，都从这一条路出去——撤销提示必须**永远**跟着一次坐标变更，
     * 而不是只在某条分支上出现。FOLLOW 模式下记录自身有坐标时改场景坐标看似没影响，
     * 但那些**没**测到坐标的记录会跟着变，同样需要可撤销。
     */
    fun applyLocation(point: GeoPoint) {
        viewModel.updateLocation(point) { change ->
            if (change != null) scope.launch { showUndoSnackbar(snackbarHostState, viewModel, change) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(scene?.name ?: "场景", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("编辑场景", style = BodyStyle) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showEditSheet = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "删除场景",
                                        style = BodyStyle,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val current = scene
        if (current == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    icon = Icons.Outlined.Warning,
                    title = "场景不存在",
                    message = "它可能已被删除",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "返回",
                    onAction = onBack,
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // —— 进入 / 当前 ——
            // 这是本页最重要的交互：用户从列表点进来，最常见的意图就是「我要在这个场景里测量」
            if (isActive) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "✓ 当前场景",
                        style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "已在此场景中，主页的测量都会归属到这里",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::leave) { Text("退出该场景") }
            } else {
                Button(onClick = viewModel::enter, modifier = Modifier.fillMaxWidth()) {
                    Text("进入此场景")
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 场景坐标 ——
            SectionTitle("场景坐标")
            if (current.hasCoordinate) {
                Text(
                    text = GeoFormat.formatCoordinates(current.latitude!!, current.longitude!!),
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        GeoFormat.formatAltitude(current.altitude),
                        "定位精度 ${GeoFormat.formatAccuracy(current.locationAccuracy ?: Float.MAX_VALUE)}",
                        current.locationProvider ?: "unknown",
                    ).joinToString(" · "),
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "获取于 ${TimeFormat.formatDateTime(current.locatedAt ?: current.createdAt)}",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = LocalMagnateSemanticColors.current.warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "未设置坐标。该场景下的记录不会继承到坐标。",
                        style = BodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        acquiring = true
                        scope.launch {
                            val result = viewModel.acquireLocation()
                            acquiring = false
                            if (result == null) {
                                snackbarHostState.showSnackbar("定位失败，请到窗边或室外再试")
                            } else if (current.coordMode == CoordMode.FIXED && recordCount > 0) {
                                // 影响面先算清楚，再让用户决定（§9.4）
                                pendingPoint = result
                            } else {
                                applyLocation(result)
                            }
                        }
                    },
                    enabled = !acquiring,
                ) {
                    if (acquiring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("定位中…")
                    } else {
                        Text(if (current.hasCoordinate) "重新获取坐标" else "获取坐标")
                    }
                }
                if (current.hasCoordinate) {
                    OutlinedButton(
                        onClick = {
                            openInMap(context, current.latitude!!, current.longitude!!, current.name)
                        }
                    ) {
                        Text("在地图中打开")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 坐标模式 ——
            SectionTitle("坐标模式")
            ModeOption(
                selected = current.coordMode == CoordMode.FIXED,
                title = "固定坐标",
                subtitle = "场景内所有记录共用此坐标\n保存时不定位，瞬时完成",
                onSelect = { viewModel.setCoordMode(CoordMode.FIXED) },
            )
            ModeOption(
                selected = current.coordMode == CoordMode.FOLLOW,
                title = "跟随本机定位",
                subtitle = "每条记录尝试定位，失败则用场景坐标",
                onSelect = { viewModel.setCoordMode(CoordMode.FOLLOW) },
            )

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 备注 ——
            SectionTitle("备注")
            NoteEditor(
                sceneId = current.id,
                initial = current.note,
                onCommit = viewModel::updateNote,
            )

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 该场景下的记录 ——
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "该场景下的记录 · $recordCount 条",
                    style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (recordCount > 0) {
                    TextButton(onClick = onViewAllRecords) {
                        Text("查看全部")
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (recentRecords.isEmpty()) {
                Text(
                    text = "还没有记录。进入该场景后回主页测量即可。",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentRecords.forEach { item ->
                        CompactRecordRow(item = item, onClick = { onOpenRecord(item.record.id) })
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // —— 改坐标的确认（FIXED + 有记录时）——
    pendingPoint?.let { point ->
        ConfirmCoordinateDialog(
            recordCount = recordCount,
            point = point,
            onConfirm = {
                pendingPoint = null
                applyLocation(point)
            },
            onDismiss = { pendingPoint = null },
        )
    }

    if (showEditSheet && scene != null) {
        val current = scene!!
        SceneEditSheet(
            title = "编辑场景",
            initialName = current.name,
            initialNote = current.note,
            initialMode = current.coordMode,
            initialPoint = current.takeIf { it.hasCoordinate }?.let {
                GeoPoint(
                    latitude = it.latitude!!,
                    longitude = it.longitude!!,
                    altitude = it.altitude,
                    accuracyMeters = it.locationAccuracy ?: Float.MAX_VALUE,
                    provider = it.locationProvider ?: "unknown",
                    locatedAt = it.locatedAt ?: it.createdAt,
                )
            },
            autoAcquireOnOpen = false,
            recentlyLocated = emptyList(),
            onAcquire = viewModel::acquireLocation,
            onOpenRecord = onOpenRecord,
            onSubmit = { draft ->
                val result = viewModel.rename(draft.name)
                when (result) {
                    is SceneWriteResult.Success -> {
                        viewModel.updateNote(draft.note)
                        viewModel.setCoordMode(draft.coordMode)
                        // 面板里真的换了坐标才落库。原样提交不该触发一次
                        // 「已更新 N 条记录的坐标」——那会让用户以为出了什么事
                        val submitted = draft.point
                        if (submitted != null && !submitted.samePlaceAs(current)) {
                            if (current.coordMode == CoordMode.FIXED && recordCount > 0) {
                                pendingPoint = submitted
                            } else {
                                applyLocation(submitted)
                            }
                        }
                        showEditSheet = false
                    }

                    is SceneWriteResult.DuplicateName -> duplicateNameId = result.existingSceneId

                    SceneWriteResult.NotFound -> Unit
                }
                result
            },
            onDismiss = { showEditSheet = false },
        )
    }

    duplicateNameId?.let { existingId ->
        DuplicateNameDialog(
            onViewScene = {
                duplicateNameId = null
                showEditSheet = false
                // 就地切到那个已有场景，而不是退回列表让用户自己再找一遍
                onOpenScene(existingId)
            },
            onDismiss = { duplicateNameId = null },
        )
    }

    if (showDeleteDialog && scene != null) {
        DeleteSceneDialog(
            sceneName = scene!!.name,
            recordCount = recordCount,
            onConfirm = { keepCoordinates ->
                showDeleteDialog = false
                viewModel.delete(keepCoordinates) { onBack() }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * 更新后的撤销条（§9.4）。
 *
 * 5 秒是 Snackbar 的默认时长，也是这里刻意不延长的原因：撤销窗口太长会诱使用户
 * 先点「更新」再看结果，反而更容易误操作。5 秒足够发现「我点错了」。
 */
private suspend fun showUndoSnackbar(
    hostState: SnackbarHostState,
    viewModel: SceneDetailViewModel,
    change: SceneLocationChange,
) {
    if (change.affectedRecordCount == 0) {
        // 没有记录受影响时不提「撤销」二字：「已更新场景坐标」+ 撤销按钮会让人
        // 以为撤销的是场景坐标本身，而这一页本来就能再改一次
        hostState.showSnackbar("已更新场景坐标")
        return
    }
    val result = hostState.showSnackbar(
        message = "已更新 ${change.affectedRecordCount} 条记录的坐标",
        actionLabel = "撤销",
        withDismissAction = true,
    )
    if (result == SnackbarResult.ActionPerformed) {
        viewModel.revertLocation(change)
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = BodyStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactRecordRow(item: RecordWithRelations, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TimeFormat.formatTime(item.record.timestamp),
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${item.record.azimuthDeg.toInt()}°",
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = GeoFormat.formatMagnitude(item.record.magnitude),
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 与记录详情页同一套「失焦即存」的备注编辑，理由见那里。 */
@Composable
private fun NoteEditor(sceneId: Long, initial: String?, onCommit: (String?) -> Unit) {
    var text by rememberSaveable(sceneId) { mutableStateOf(initial.orEmpty()) }
    var lastCommitted by rememberSaveable(sceneId) { mutableStateOf(initial.orEmpty()) }

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
        placeholder = { Text("地下二层，靠近配电房", style = BodyStyle) },
        minLines = 2,
        textStyle = BodyStyle,
    )
}

/** 改坐标前的确认（§9.4）。影响范围必须写在正文里，不能只放在小字里。 */
@Composable
private fun ConfirmCoordinateDialog(
    recordCount: Int,
    point: GeoPoint,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新场景坐标？", style = BodyStyle) },
        text = {
            Column {
                Text(
                    text = "该场景下的 $recordCount 条记录坐标将同步更新为新坐标。",
                    style = BodyStyle,
                )
                Spacer(Modifier.height(12.dp))
                Text("新坐标", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = GeoFormat.formatCoordinates(point.latitude, point.longitude),
                    style = BodyStyle,
                )
                Text(
                    text = listOfNotNull(
                        GeoFormat.formatAltitude(point.altitude),
                        "定位精度 ${GeoFormat.formatAccuracy(point.accuracyMeters)}",
                    ).joinToString(" · "),
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("更新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 删除场景（§9.5）。默认「保留坐标到记录」。
 *
 * FIXED 模式下记录自身不存坐标，直接解除关联会让这些记录的坐标**凭空消失**。
 * 用户很可能只是想整理场景列表，并不想丢数据。
 */
@Composable
private fun DeleteSceneDialog(
    sceneName: String,
    recordCount: Int,
    onConfirm: (keepCoordinates: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var keep by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除场景「$sceneName」？", style = BodyStyle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "该场景下的 $recordCount 条记录不会被删除。",
                    style = BodyStyle,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "请选择这些记录的坐标如何处理：",
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                DeleteOption(
                    selected = keep,
                    title = "保留坐标到记录（推荐）",
                    subtitle = "把场景坐标写入这 $recordCount 条记录，" +
                        "它们仍带有坐标，只是不再属于任何场景。",
                    onSelect = { keep = true },
                )
                DeleteOption(
                    selected = !keep,
                    title = "仅解除关联",
                    subtitle = "这些记录将变为「无坐标」。",
                    onSelect = { keep = false },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(keep) }) {
                Text("删除场景", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = BodyStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 是否与场景当前坐标是同一个点。
 *
 * 比的是**双精度原值**而不是格式化后的字符串：展示时会四舍五入到 6 位小数，
 * 用格式化结果比较会把「改了但幅度小于 0.0000005°」误判成没变。
 * 这里要的恰恰相反——只要动了就按动了处理，宁可多问一次也不静默丢改动。
 *
 * **比较的是整个坐标元组，不只是经纬度。** 这个函数是上面 `onSubmit` 里
 * 「要不要落库」的唯一闸门：先前只比经纬度，于是「经纬度没动、只补了海拔」
 * 或者「只修了精度」的编辑会被判定为「原样提交」而**静默丢弃**——
 * 手动坐标对话框新加的海拔输入框正好会踩中这一条。
 *
 * 刻意**不比 `locatedAt`**：手动输入取的是 `System.currentTimeMillis()`，
 * 把它算进来的话，每次打开面板点确定都会被当成一次坐标变更，
 * 弹出「将同步更新 N 条记录」的确认框——一个永远为真的警告等于没有警告。
 */
private fun GeoPoint.samePlaceAs(scene: SceneEntity): Boolean =
    scene.latitude == latitude &&
        scene.longitude == longitude &&
        scene.altitude == altitude &&
        // 这两项要按「面板打开时怎么把 SceneEntity 变成 GeoPoint」的同一套兜底来比
        // （见本文件构造 initial 的那几行）：缺精度补 Float.MAX_VALUE、缺 provider 补 "unknown"。
        // 直接用 `scene.locationAccuracy == accuracyMeters` 比，null 与哨兵值不相等，
        // 于是「坐标一个字段都没动」的老场景每次提交都会被当成改动。
        (scene.locationAccuracy ?: Float.MAX_VALUE) == accuracyMeters &&
        (scene.locationProvider ?: "unknown") == provider
