package com.magnate.compass.ui.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.SceneDraft
import com.magnate.compass.data.SceneWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.SectionTitle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 创建 / 编辑场景（design-gui.md §9.3）。
 *
 * 三条贯穿始终的规则：
 * 1. **坐标获取失败不阻塞创建**——场景可以无坐标创建，此时它只是个分组容器，后续补录。
 *    创建按钮**始终可点**。
 * 2. **场景名唯一**，重名时由调用方弹「查看该场景」——用户往往只是想进入已有场景。
 * 3. 坐标有三种来源：自动获取、手动输入、从记录导入。第三种是 v1.1 补录功能的闭环：
 *    用户走进楼里才想起要建场景，而 GPS 已失效，但他刚才在门口恰好测过一条记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditSheet(
    title: String,
    initialName: String,
    initialNote: String?,
    initialMode: CoordMode,
    initialPoint: GeoPoint?,
    /** 新建场景时默认自动取一次坐标（§9.3）——用户多半就站在那个点上。 */
    autoAcquireOnOpen: Boolean,
    recentlyLocated: List<RecordWithRelations>,
    onAcquire: suspend () -> GeoPoint?,
    onOpenRecord: (Long) -> Unit,
    onSubmit: suspend (SceneDraft) -> SceneWriteResult,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
    var mode by remember { mutableStateOf(initialMode) }
    var point by remember { mutableStateOf(initialPoint) }

    var acquiring by remember { mutableStateOf(false) }
    var acquiringFailed by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    fun acquire() {
        acquiring = true
        acquiringFailed = false
        scope.launch {
            val result = onAcquire()
            acquiring = false
            if (result == null) acquiringFailed = true else point = result
        }
    }

    LaunchedEffect(Unit) {
        if (autoAcquireOnOpen && point == null) acquire()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            // —— 名称 ——
            SectionTitle("场景名称")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("阳光大厦 B2层", style = BodyStyle) },
                singleLine = true,
                textStyle = BodyStyle,
            )

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 坐标 ——
            SectionTitle("场景坐标")
            CoordinatePreview(
                point = point,
                acquiring = acquiring,
                acquiringFailed = acquiringFailed,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::acquire, enabled = !acquiring) {
                    Text("重新获取")
                }
                OutlinedButton(onClick = { manualEntry = true }) { Text("手动输入") }
                OutlinedButton(
                    onClick = { importing = true },
                    enabled = recentlyLocated.isNotEmpty(),
                ) {
                    Text("从记录导入")
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 坐标模式 ——
            SectionTitle("坐标模式")
            CoordModeOption(
                selected = mode == CoordMode.FIXED,
                title = "固定坐标",
                subtitle = "场景内所有记录共用此坐标，保存时不定位，瞬时完成",
                onSelect = { mode = CoordMode.FIXED },
            )
            CoordModeOption(
                selected = mode == CoordMode.FOLLOW,
                title = "跟随本机定位",
                subtitle = "每条记录尝试定位，失败则回退到场景坐标",
                onSelect = { mode = CoordMode.FOLLOW },
            )

            Spacer(Modifier.height(16.dp))
            SectionDivider()

            // —— 备注 ——
            SectionTitle("备注（可选）")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("地下二层，靠近配电房", style = BodyStyle) },
                minLines = 2,
                textStyle = BodyStyle,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    submitting = true
                    scope.launch {
                        onSubmit(
                            SceneDraft(
                                name = name,
                                note = note,
                                coordMode = mode,
                                point = point,
                            )
                        )
                        submitting = false
                    }
                },
                // 名称空着才禁用。**坐标缺失不禁用**——建一个无坐标的场景是合法操作。
                enabled = name.isNotBlank() && !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (initialName.isEmpty()) "创建场景" else "保存修改")
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    if (manualEntry) {
        ManualCoordinateDialog(
            initial = point,
            onConfirm = {
                point = it
                manualEntry = false
                acquiringFailed = false
            },
            onDismiss = { manualEntry = false },
        )
    }

    if (importing) {
        ImportCoordinateDialog(
            candidates = recentlyLocated,
            onPick = {
                point = GeoPoint(
                    latitude = it.record.latitude!!,
                    longitude = it.record.longitude!!,
                    altitude = it.record.altitude,
                    accuracyMeters = it.record.locationAccuracy ?: Float.MAX_VALUE,
                    provider = it.record.locationProvider ?: "unknown",
                    locatedAt = it.record.locatedAt ?: it.record.timestamp,
                )
                importing = false
                acquiringFailed = false
            },
            onOpenRecord = onOpenRecord,
            onDismiss = { importing = false },
        )
    }
}

/**
 * 场景重名。
 *
 * 提供「查看该场景」而不只是「换个名字」——用户多半是想进入那个已存在的场景，
 * 只是一时想不起来自己建过（§9.3）。列表页与详情页共用。
 */
@Composable
internal fun DuplicateNameDialog(onViewScene: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("场景名已存在", style = BodyStyle) },
        text = {
            Text(
                text = "已经有一个同名场景了。你可能只是想进入它，而不是再建一个。",
                style = BodyStyle,
            )
        },
        confirmButton = { TextButton(onClick = onViewScene) { Text("查看该场景") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("换个名字") } },
    )
}

@Composable
private fun CoordinatePreview(
    point: GeoPoint?,
    acquiring: Boolean,
    acquiringFailed: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            acquiring -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("正在获取定位…", style = BodyStyle)
            }

            point != null -> {
                Text(
                    text = GeoFormat.formatCoordinates(point.latitude, point.longitude),
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        GeoFormat.formatAltitude(point.altitude),
                        "定位精度 ${GeoFormat.formatAccuracy(point.accuracyMeters)}",
                        point.provider,
                    ).joinToString(" · "),
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            acquiringFailed -> Text(
                text = "定位失败。可以先创建场景，之后再补录坐标。",
                style = BodyStyle,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Text(
                text = "尚未设置坐标。没有坐标的场景只是一个分组容器，" +
                    "场景内的记录不会继承到坐标。",
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoordModeOption(
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
            Text(
                text = title,
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 手动输入：已知精确坐标（从地图查得、从另一台设备抄录）。 */
@Composable
private fun ManualCoordinateDialog(
    initial: GeoPoint?,
    onConfirm: (GeoPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    var lat by remember { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    // 海拔预填原值。**之前这一栏根本不存在**，而 430 行又把 initial?.altitude 原样透传，
    // 于是「手动输入坐标」永远改不动海拔：从定位结果切到手动模式，海拔会悄悄沿用旧的，
    // 用户既看不到它、也改不了它。
    var alt by remember { mutableStateOf(initial?.altitude?.toString().orEmpty()) }
    var acc by remember {
        mutableStateOf(
            initial?.accuracyMeters
                ?.takeIf { it < Float.MAX_VALUE }
                ?.toString()
                .orEmpty()
        )
    }

    val parsedLat = lat.trim().toDoubleOrNull()
    val parsedLon = lon.trim().toDoubleOrNull()
    val parsedAlt = alt.trim().toDoubleOrNull()
    // 手动抄来的坐标没有 provider，也没有「获取时刻」——这两项如实留空/取当下，
    // 不要编造一个 "gps" 出来，那会让日后排查数据来源时被误导
    val latValid = parsedLat != null && parsedLat in -90.0..90.0
    val lonValid = parsedLon != null && parsedLon in -180.0..180.0
    // 海拔留空是**合法输入**，表示「不知道」——很多设备与定位方式拿不到海拔，
    // 强迫用户填一个数只会逼出编造。填了才要求它是数字。
    val altValid = alt.isBlank() || parsedAlt != null
    val valid = latValid && lonValid && altValid

    // 空表单不是错误，是初始状态，此时不报错
    val showRangeError = (lat.isNotBlank() && !latValid) || (lon.isNotBlank() && !lonValid)
    val showAltError = alt.isNotBlank() && !altValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入坐标", style = BodyStyle) },
        text = {
            Column {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("纬度", style = LabelStyle) },
                    placeholder = { Text("30.123456", style = BodyStyle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("经度", style = LabelStyle) },
                    placeholder = { Text("120.567890", style = BodyStyle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = alt,
                    onValueChange = { alt = it },
                    label = { Text("海拔（米，可留空）", style = LabelStyle) },
                    placeholder = { Text("42", style = BodyStyle) },
                    singleLine = true,
                    // 海拔可以为负（吐鲁番盆地、死海），但 Decimal 键盘不给负号——
                    // 这与上面纬度/经度两栏的处境完全一样（南纬西经同样要负号），
                    // 是既有限制，不为海拔单独引入一套输入控件
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = acc,
                    onValueChange = { acc = it },
                    label = { Text("定位精度（米，可留空）", style = LabelStyle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showRangeError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "纬度需在 -90~90，经度需在 -180~180",
                        style = LabelStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showAltError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "海拔需要是一个数字，或留空表示未知",
                        style = LabelStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onConfirm(
                            GeoPoint(
                                latitude = parsedLat!!,
                                longitude = parsedLon!!,
                                // 留空即 null（未知），不是沿用旧值——用户清空这一栏
                                // 就是在说「我不知道海拔」，不该被一个他看不见的值覆盖
                                altitude = parsedAlt,
                                // 精度留空时用哨兵值，绝不用 0——0 会被「精度优于 N 米」误判为极精确
                                accuracyMeters = acc.trim().toFloatOrNull() ?: Float.MAX_VALUE,
                                provider = "manual",
                                locatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                },
                enabled = valid,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 从记录导入坐标（§9.3）。
 *
 * 列出最近 20 条**自身有坐标**的记录。这个功能解决的是一个真实场景：
 * 用户走进楼里才想起要建场景，此时 GPS 已失效，而他刚才在门口恰好测过一条。
 */
@Composable
private fun ImportCoordinateDialog(
    candidates: List<RecordWithRelations>,
    onPick: (RecordWithRelations) -> Unit,
    onOpenRecord: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从记录导入坐标", style = BodyStyle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "选择一条记录，把它的坐标（含精度）复制到场景。",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                candidates.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = TimeFormat.formatShortDateTime(item.record.timestamp),
                                style = BodyStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = GeoFormat.formatCoordinatesShort(
                                    item.record.latitude!!,
                                    item.record.longitude!!,
                                ) + " · " +
                                    GeoFormat.formatAccuracy(
                                        item.record.locationAccuracy ?: Float.MAX_VALUE
                                    ),
                                style = LabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onPick(item) }) { Text("导入") }
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenRecord(item.record.id)
                            }
                        ) {
                            Text("查看")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
