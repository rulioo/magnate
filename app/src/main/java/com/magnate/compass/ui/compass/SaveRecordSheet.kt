package com.magnate.compass.ui.compass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.data.LocationPolicy
import com.magnate.compass.data.RecordDraft
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.SaveOutcome
import com.magnate.compass.data.SaveRecordRequest
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TagWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.data.locationPolicyFor
import com.magnate.compass.data.previewLocation
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.common.LocationBadge
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.TagChip
import com.magnate.compass.ui.scenes.ScenePickerSheet
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.AzimuthDetailStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.ChipStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.ui.theme.MagnitudeDetailStyle
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 保存面板（design-gui.md §5）。
 *
 * 两条不可动摇的规则：
 *
 * 1. **数值来自快照**。传入的 [draft] 在点击「保存当前读数」的瞬间就已冻结，
 *    之后传感器怎么跳都不影响这里显示的内容。用户看到的数就是他存下的数。
 * 2. **保存按钮永不禁用**。定位中、定位失败、场景没坐标——都能存。
 *    GPS 冷启动可能 30 秒以上，为一个可选字段锁死保存按钮，
 *    等于逼用户在户外举着手机干等（§5.4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveRecordSheet(
    draft: RecordDraft,
    initialScene: SceneEntity?,
    scenes: List<SceneWithCount>,
    recentTags: List<TagWithCount>,
    lastKnownLocation: GeoPoint?,
    acquireLocation: suspend (timeoutMs: Long) -> GeoPoint?,
    onCreateTag: suspend (String) -> TagWriteResult,
    onSave: suspend (SaveRecordRequest) -> SaveOutcome,
    onSaved: (SaveOutcome) -> Unit,
    onManageScenes: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val semanticColors = LocalMagnateSemanticColors.current

    // 归属场景在快照时刻确定，但允许用户在最后一刻改变——
    // 这避免了一个恼人的往返：「存错场景了 → 取消 → 切场景 → 重新测」（§5.3）
    var scene by remember { mutableStateOf(initialScene) }
    var note by remember { mutableStateOf("") }
    val selectedTags = remember { mutableStateListOf<String>() }
    var measured by remember { mutableStateOf(lastKnownLocation) }
    var acquiring by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showNewTag by remember { mutableStateOf(false) }

    val noteFocus = remember { FocusRequester() }

    val policy = locationPolicyFor(
        scene = scene,
        noSceneTimeoutMs = LocationProvider.DEFAULT_TIMEOUT_MS,
        followSceneTimeoutMs = LocationProvider.FOLLOW_SCENE_TIMEOUT_MS,
    )

    // 归属场景一变就重新定位。FIXED 场景直接跳过——它的坐标来自场景，本机定位毫无意义。
    LaunchedEffect(scene?.id, policy) {
        when (policy) {
            LocationPolicy.Skip -> {
                acquiring = false
                measured = null
            }

            is LocationPolicy.Acquire -> {
                acquiring = true
                measured = acquireLocation(policy.timeoutMs)
                acquiring = false
            }
        }
    }

    // 焦点自动落在备注输入框（design-gui.md §15）
    LaunchedEffect(Unit) {
        runCatching { noteFocus.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            // —— 标题 ——
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("保存测试记录", style = BodyStyle, color = MaterialTheme.colorScheme.onSurface)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "取消保存",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // —— 冻结快照 ——
            FrozenSnapshot(draft)

            Spacer(Modifier.height(12.dp))
            SectionDivider()

            // —— 归属场景 ——
            Spacer(Modifier.height(12.dp))
            Text("归属场景", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SceneSection(
                scene = scene,
                acquiring = acquiring,
                location = previewLocation(scene, measured),
                onClick = { showPicker = true },
            )

            Spacer(Modifier.height(12.dp))
            SectionDivider()

            // —— 备注 ——
            Spacer(Modifier.height(12.dp))
            Text("备注", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(noteFocus),
                placeholder = { Text("配电房门口", style = BodyStyle) },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            // —— 标签 ——
            Spacer(Modifier.height(12.dp))
            Text("标签", style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            TagSelector(
                tags = recentTags,
                selected = selectedTags,
                onToggle = { name ->
                    if (name in selectedTags) selectedTags.remove(name) else selectedTags.add(name)
                },
                onNewTag = { showNewTag = true },
            )

            Spacer(Modifier.height(20.dp))

            // —— 操作 ——
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        // 保存按钮永远 enabled——这里只是在写入期间拦住重复点击（§5.4）
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            val outcome = onSave(
                                SaveRecordRequest(
                                    draft = draft,
                                    sceneId = scene?.id,
                                    note = note,
                                    tagNames = selectedTags.toList(),
                                    measuredLocation = measured,
                                )
                            )
                            saving = false
                            onSaved(outcome)
                        }
                    },
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("保存")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "保存后仍可补录坐标、改备注与标签",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }

    if (showPicker) {
        ScenePickerSheet(
            scenes = scenes,
            activeSceneId = scene?.id,
            onSelect = { id ->
                // 快照时刻的场景已在 initialScene 里定下；这里改的只是「将归属」，
                // 而它是随记录一起落库的，所以必须用选择器返回的最新值覆盖。
                scene = id?.let { selected -> scenes.firstOrNull { it.scene.id == selected }?.scene }
                showPicker = false
            },
            onCreate = {
                showPicker = false
                onManageScenes()
            },
            onManage = {
                showPicker = false
                onManageScenes()
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showNewTag) {
        val existingNames = recentTags.map { it.tag.name }
        NewTagDialog(
            existingNames = existingNames,
            onConfirm = { name ->
                showNewTag = false
                scope.launch {
                    val typed = name.trim()
                    val result = onCreateTag(typed)
                    // 重名时不报错，直接选中那个已有标签。用**库里那个名字**而不是用户刚输入的，
                    // 否则「室外」与「室外 」会在 chip 上显示成两个样子（§5.6）。
                    val resolved = when (result) {
                        is TagWriteResult.Success -> typed
                        is TagWriteResult.Duplicate ->
                            existingNames.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
                    }
                    if (resolved.isNotEmpty() &&
                        resolved !in selectedTags &&
                        selectedTags.size < RecordRepository.MAX_TAGS_PER_RECORD
                    ) {
                        selectedTags.add(resolved)
                    }
                }
            },
            onDismiss = { showNewTag = false },
        )
    }
}

/** 冻结快照区：数值、朝向名、时间。**不做任何动画**——数字滚动会让用户怀疑存的是哪个值。 */
@Composable
private fun FrozenSnapshot(draft: RecordDraft) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${draft.azimuthDeg.toInt()}°",
                style = AzimuthDetailStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = CompassMath.headingName(draft.azimuthDeg),
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (draft.isTrueNorth) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "真北",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = GeoFormat.formatMagnitude(draft.magnitude),
                style = MagnitudeDetailStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            AxisInline("X", draft.magX)
            Spacer(Modifier.width(10.dp))
            AxisInline("Y", draft.magY)
            Spacer(Modifier.width(10.dp))
            AxisInline("Z", draft.magZ)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = TimeFormat.formatDateTime(draft.timestamp),
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AxisInline(label: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(
            text = GeoFormat.formatSigned(value),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 归属场景区（§5.3 的四种形态）。
 *
 * 整块可点，弹出的选择器与主页场景条完全一致。
 */
@Composable
private fun SceneSection(
    scene: SceneEntity?,
    acquiring: Boolean,
    location: EffectiveLocation,
    onClick: () -> Unit,
) {
    val semanticColors = LocalMagnateSemanticColors.current

    val leading: String
    val title: String
    val subtitle: String
    val accent = when {
        scene == null -> {
            leading = "⚪"
            title = "不使用场景"
            subtitle = "使用本机定位"
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        !scene.hasCoordinate -> {
            leading = "⚠"
            title = scene.name
            subtitle = "场景未设置坐标"
            semanticColors.warning
        }

        scene.coordMode == CoordMode.FIXED -> {
            leading = "📍"
            title = scene.name
            subtitle = "场景坐标 · ${GeoFormat.formatAccuracy(scene.locationAccuracy ?: Float.MAX_VALUE)}"
            MaterialTheme.colorScheme.primary
        }

        else -> {
            leading = "📍"
            title = scene.name
            subtitle = if (acquiring) "正在获取本机定位…" else "跟随本机定位"
            MaterialTheme.colorScheme.primary
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(leading, style = BodyStyle, color = accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (acquiring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = subtitle,
                        style = LabelStyle,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("更改 ▸", style = LabelStyle, color = MaterialTheme.colorScheme.primary)
        }

        // 定位中不显示预览——此时还没有结论，显示上一帧的坐标会误导用户
        if (!acquiring) {
            Spacer(Modifier.height(6.dp))
            LocationBadge(location = location)
            // 海拔取自**解析后的** location，不是刚测到的那个点：
            // FIXED 且场景有坐标时，记录自身不存坐标，落库写的是场景的海拔。
            // 图省事用实测点的话，这里会显示一个永远不会被保存的数字。
            GeoFormat.formatAltitude(location.pointOrNull?.altitude)?.let { altitude ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = altitude,
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 标签 chip 流：最近使用的 8 个 + 新建。达到上限后未选中的 chip 置灰（§5.6）。 */
@Composable
private fun TagSelector(
    tags: List<TagWithCount>,
    selected: MutableList<String>,
    onToggle: (String) -> Unit,
    onNewTag: () -> Unit,
) {
    val atLimit = selected.size >= RecordRepository.MAX_TAGS_PER_RECORD

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { item ->
            val name = item.tag.name
            val isSelected = name in selected
            TagChip(
                name = name,
                colorIndex = item.tag.colorIndex,
                selected = isSelected,
                enabled = isSelected || !atLimit,
                onClick = { onToggle(name) },
            )
        }

        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                .clickable(enabled = !atLimit, onClick = onNewTag)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("新建", style = ChipStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (atLimit) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "已达单条记录 ${RecordRepository.MAX_TAGS_PER_RECORD} 个标签的上限",
            style = LabelStyle,
            color = LocalMagnateSemanticColors.current.warning,
        )
    }
}

/**
 * 新建标签对话框，**带已有标签名的自动补全**。
 *
 * 补全不是便利功能而是数据质量功能：没有它，「室外」与「室外 」会并存成两个标签，
 * 之后按标签筛选就再也筛不全（§5.6）。
 */
@Composable
private fun NewTagDialog(
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    val trimmed = text.trim()
    val exactExists = existingNames.any { it.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签", style = BodyStyle) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("标签名", style = BodyStyle) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (exactExists) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已存在同名标签，将直接使用它",
                        style = LabelStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 「保存到哪个场景」——主按钮副标题的文案，抽出来是为了让主页与面板共用同一套措辞。 */
internal fun saveTargetLabel(scene: SceneEntity?, maxChars: Int = 10): String =
    if (scene == null) {
        "将保存到：本机坐标"
    } else {
        val name = if (scene.name.length <= maxChars) scene.name else scene.name.take(maxChars) + "…"
        "将保存到「$name」"
    }
