package com.magnate.compass.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.location
import com.magnate.compass.ui.common.LocationBadge
import com.magnate.compass.ui.common.TagChip
import com.magnate.compass.ui.common.spoken
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.AzimuthListItemStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.ChipStyle
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat

/** 列表项最多显示几个标签，其余折叠成 `+N`（design-gui.md §6.2）。 */
private const val MAX_VISIBLE_TAGS = 3

/**
 * 记录列表项（design-gui.md §6.2）。
 *
 * 方位角**右对齐且等宽**：整列数字垂直对齐，用户扫视时能快速比对。
 * 这是列表可读性的关键，也是 §12.3「等宽原则」最直接的一处应用。
 */
@Composable
fun RecordListItem(
    item: RecordWithRelations,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    val record = item.record
    val location = item.location

    // 合并为单个语义节点：逐元素朗读会把一条记录读成十几段碎片，
    // 顺序固定为 时间 → 方位角 → 磁场强度 → 坐标来源 → 标签（§15）。
    //
    // 这里用 mergeDescendants 而不是 clearAndSetSemantics——后者会把链上
    // 更早的 .clickable 一并清掉，读屏用户就再也点不开这条记录了。
    val description = buildString {
        append(TimeFormat.formatDateTime(record.timestamp))
        append("，方位角 ${record.azimuthDeg.toInt()} 度")
        append("，磁场强度 ${GeoFormat.formatMagnitude(record.magnitude)}")
        append("，")
        append(location.spoken())
        if (item.tags.isNotEmpty()) {
            append("，标签 ")
            append(item.tags.joinToString("、") { it.name })
        }
        if (!record.note.isNullOrBlank()) {
            append("，备注 ${record.note}")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(12.dp),
    ) {
        // —— 第 1 行：时间 · 方位角 ——
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (showDate) {
                    TimeFormat.formatShortDateTime(record.timestamp)
                } else {
                    TimeFormat.formatTime(record.timestamp)
                },
                style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${record.azimuthDeg.toInt()}°",
                style = AzimuthListItemStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // —— 第 2 行：磁场强度 · 坐标来源 ——
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = GeoFormat.formatMagnitude(record.magnitude),
                style = AxisValueStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LocationBadge(location = location)
        }

        // —— 第 3 行：标签 ——
        if (item.tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.tags.take(MAX_VISIBLE_TAGS).forEach { tag ->
                    // 点击 chip 与点击整行是同一个动作——整行已经是可点区域，
                    // chip 只是展示，不该引入第二种行为
                    TagChip(
                        name = tag.name,
                        colorIndex = tag.colorIndex,
                        selected = true,
                        onClick = onClick,
                    )
                }
                val overflow = item.tags.size - MAX_VISIBLE_TAGS
                if (overflow > 0) {
                    Text(
                        text = "+$overflow",
                        style = ChipStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
