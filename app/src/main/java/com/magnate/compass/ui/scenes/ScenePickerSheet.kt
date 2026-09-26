package com.magnate.compass.ui.scenes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

/**
 * 场景选择器（design-gui.md §4.3）。
 *
 * **选择即生效**：点击某一项立即关闭并切换，不需要额外的「确定」。
 * 场景切换是低风险操作，随时可以切回来——为它加一道确认只会让高频操作变慢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenePickerSheet(
    scenes: List<SceneWithCount>,
    activeSceneId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "选择场景",
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onCreate) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "新建场景",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionDivider(Modifier.padding(horizontal = 20.dp))

        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item {
                NoSceneRow(selected = activeSceneId == null, onClick = { onSelect(null) })
            }

            items(scenes, key = { it.scene.id }) { item ->
                SceneRow(
                    item = item,
                    selected = item.scene.id == activeSceneId,
                    onClick = { onSelect(item.scene.id) },
                )
            }
        }

        SectionDivider(Modifier.padding(horizontal = 20.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onManage) {
                Text("管理场景 ▸", style = LabelStyle)
            }
        }

        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun NoSceneRow(selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "⚪", style = BodyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "不使用场景",
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "使用本机定位获取每条记录的坐标",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) CheckMark()
    }
}

@Composable
private fun SceneRow(item: SceneWithCount, selected: Boolean, onClick: () -> Unit) {
    val scene = item.scene
    val semanticColors = LocalMagnateSemanticColors.current
    val hasCoordinate = scene.hasCoordinate

    val leading = if (hasCoordinate) "📍" else "⚠"
    val leadingColor = if (hasCoordinate) {
        MaterialTheme.colorScheme.primary
    } else {
        semanticColors.warning
    }

    val summary = if (hasCoordinate) {
        "${GeoFormat.formatCoordinatesShort(scene.latitude!!, scene.longitude!!)} · " +
            GeoFormat.formatAccuracy(scene.locationAccuracy ?: Float.MAX_VALUE)
    } else {
        "未设置坐标 · ${scene.coordMode.label()}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(scene.name)
                    append("，")
                    append(summary)
                    append("，${item.recordCount} 条记录")
                    if (selected) append("，当前选中")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = leading, style = BodyStyle, color = leadingColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = scene.name,
                style = BodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = LabelStyle,
                color = if (hasCoordinate) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    semanticColors.warning
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${item.recordCount} 条",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) CheckMark()
    }
}

@Composable
private fun CheckMark() {
    Spacer(Modifier.width(8.dp))
    Icon(
        imageVector = Icons.Outlined.Check,
        contentDescription = "当前场景",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}

/** 坐标模式的中文名。UI 上出现处都要带上它——「固定坐标」与「跟随定位」是两种数据语义。 */
internal fun CoordMode.label(): String = when (this) {
    CoordMode.FIXED -> "固定坐标"
    CoordMode.FOLLOW -> "跟随定位"
}
