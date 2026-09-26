package com.magnate.compass.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.CoordinatePreset
import com.magnate.compass.data.MagnitudePreset
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SearchCriteria
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TimePreset
import com.magnate.compass.ui.common.Pill
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.SectionTitle
import com.magnate.compass.ui.common.TagChip
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.util.TimeFormat

/**
 * 筛选面板（design-gui.md §8.4）。
 *
 * 面板操作的是**草稿**条件：底部按钮上的「查看 N 条结果」随草稿实时变化，
 * 但结果列表要等用户点了确认才动。用户可以在面板里反复试条件、看数字，
 * 而不必每改一次就关掉面板去列表里确认（§8.4 明确反对这种试错）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    draft: SearchCriteria,
    scenes: List<SceneWithCount>,
    tags: List<TagWithCount>,
    resultCount: Int,
    onDraftChange: ((SearchCriteria) -> SearchCriteria) -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "筛选",
                    style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("重置") }
            }

            // —— 场景：多选 OR ——
            SectionTitle("场景")
            Text(
                text = "场景是「在哪儿测的」，一条记录至多属于一个，所以多选是「任一」",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = "不限",
                    selected = draft.sceneIds.isEmpty(),
                    onClick = { onDraftChange { it.copy(sceneIds = emptySet()) } },
                )
                scenes.forEach { item ->
                    val id = item.scene.id
                    Pill(
                        text = item.scene.name,
                        leadingText = "📍",
                        selected = id in draft.sceneIds,
                        onClick = {
                            onDraftChange {
                                it.copy(
                                    sceneIds = if (id in it.sceneIds) it.sceneIds - id else it.sceneIds + id
                                )
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 时间范围 ——
            SectionTitle("时间范围")
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimePreset.entries.forEach { preset ->
                    Pill(
                        text = preset.label(),
                        selected = draft.timePreset == preset,
                        onClick = { onDraftChange { it.copy(timePreset = preset) } },
                    )
                }
            }

            if (draft.timePreset == TimePreset.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DateField(
                        label = "起始",
                        millis = draft.customTimeFrom,
                        modifier = Modifier.weight(1f),
                        onPick = { onDraftChange { c -> c.copy(customTimeFrom = it) } },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("~", style = BodyStyle)
                    Spacer(Modifier.width(12.dp))
                    DateField(
                        label = "结束",
                        millis = draft.customTimeTo,
                        modifier = Modifier.weight(1f),
                        onPick = { onDraftChange { c -> c.copy(customTimeTo = it) } },
                    )
                }
                Text(
                    text = "结束日期含当天 23:59:59",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 磁场强度：预设区间与地磁正常范围一致 ——
            SectionTitle("磁场强度 (μT)")
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MagnitudePreset.entries.forEach { preset ->
                    Pill(
                        text = preset.label(),
                        selected = draft.magnitudePreset == preset,
                        onClick = { onDraftChange { it.copy(magnitudePreset = preset) } },
                    )
                }
            }

            if (draft.magnitudePreset == MagnitudePreset.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(
                        label = "最小",
                        value = draft.customMagnitudeMin,
                        modifier = Modifier.weight(1f),
                        onChange = { onDraftChange { c -> c.copy(customMagnitudeMin = it) } },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("~", style = BodyStyle)
                    Spacer(Modifier.width(12.dp))
                    NumberField(
                        label = "最大",
                        value = draft.customMagnitudeMax,
                        modifier = Modifier.weight(1f),
                        onChange = { onDraftChange { c -> c.copy(customMagnitudeMax = it) } },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 坐标 ——
            SectionTitle("坐标")
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoordinatePreset.entries.forEach { preset ->
                    Pill(
                        text = preset.label(),
                        selected = draft.coordinatePreset == preset,
                        onClick = { onDraftChange { it.copy(coordinatePreset = preset) } },
                    )
                }
            }
            Text(
                text = "「有坐标」包含从场景继承的坐标",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.goodAccuracyOnly,
                    onCheckedChange = { onDraftChange { c -> c.copy(goodAccuracyOnly = it) } },
                )
                Text(
                    text = "仅显示定位精度优于 50m 的记录",
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 标签 ——
            SectionTitle("标签")
            Text(
                text = "标签是「怎么分类的」，可以叠加，所以能选「同时包含」或「任一包含」",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TagMatchModeSelector(
                matchAll = draft.tagMatchAll,
                onChange = { onDraftChange { c -> c.copy(tagMatchAll = it) } },
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tags.isEmpty()) {
                    Text(
                        text = "还没有标签",
                        style = LabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tags.forEach { item ->
                    val id = item.tag.id
                    TagChip(
                        name = item.tag.name,
                        colorIndex = item.tag.colorIndex,
                        selected = id in draft.tagIds,
                        onClick = {
                            onDraftChange {
                                it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("查看 $resultCount 条结果")
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

/**
 * 匹配方式用下拉而非两个开关——「同时包含」与「任一包含」是互斥的，
 * 两个独立开关会允许「既 AND 又 OR」这种无意义的状态（§8.4）。
 */
@Composable
private fun TagMatchModeSelector(matchAll: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("匹配方式", style = BodyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box {
            Pill(
                text = if (matchAll) "同时包含" else "任一包含",
                trailingIcon = Icons.Outlined.ArrowDropDown,
                onTrailingClick = { expanded = true },
                selected = true,
                onClick = { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("同时包含", style = BodyStyle) },
                    onClick = {
                        onChange(true)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("任一包含", style = BodyStyle) },
                    onClick = {
                        onChange(false)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    millis: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = millis?.let { TimeFormat.formatDate(it) }.orEmpty(),
        onValueChange = { },
        readOnly = true,
        label = { Text(label, style = LabelStyle) },
        singleLine = true,
        textStyle = BodyStyle,
        modifier = modifier,
        trailingIcon = {
            TextButton(onClick = { showPicker = true }) { Text("选择") }
        },
    )

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let(onPick)
                        showPicker = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Float?,
    onChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }.orEmpty(),
        onValueChange = { raw ->
            // 只允许数字与一个小数点；空串表示「不限制」而不是 0
            val cleaned = raw.filter { it.isDigit() || it == '.' }
            onChange(cleaned.toFloatOrNull())
        },
        label = { Text(label, style = LabelStyle) },
        singleLine = true,
        textStyle = BodyStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun TimePreset.label(): String = when (this) {
    TimePreset.ALL -> "不限"
    TimePreset.TODAY -> "今天"
    TimePreset.LAST_7 -> "近 7 天"
    TimePreset.LAST_30 -> "近 30 天"
    TimePreset.CUSTOM -> "自定义"
}

private fun MagnitudePreset.label(): String = when (this) {
    MagnitudePreset.ALL -> "不限"
    MagnitudePreset.LOW -> "< 25"
    MagnitudePreset.NORMAL -> "25 – 65"
    MagnitudePreset.HIGH -> "> 65"
    MagnitudePreset.CUSTOM -> "自定义"
}

private fun CoordinatePreset.label(): String = when (this) {
    CoordinatePreset.ALL -> "不限"
    CoordinatePreset.HAS -> "有坐标"
    CoordinatePreset.NONE -> "无坐标"
}
