package com.magnate.compass.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.AccuracyLevel
import com.magnate.compass.ui.theme.ChipStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.ui.theme.tagColor

/** 传感器精度徽标。低精度记录**仍允许保存**，只是带上这个徽标（design.md §5.9）。 */
@Composable
fun AccuracyBadge(
    level: AccuracyLevel,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val (text, color) = when (level) {
        AccuracyLevel.HIGH -> "高精度" to semanticColors.success
        AccuracyLevel.MEDIUM -> "中精度" to Color(0xFFFFCA28)
        AccuracyLevel.LOW -> "低精度 · 建议校准" to semanticColors.warning
        AccuracyLevel.UNRELIABLE -> "不可信 · 请做 8 字校准" to MaterialTheme.colorScheme.error
        AccuracyLevel.UNKNOWN -> "精度未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = LabelStyle, color = color)
    }
}

/**
 * 标签 chip。
 *
 * 选中态用标签色作底（20% alpha）+ 同色描边 + 同色文字；
 * 未选中用 `surfaceVariant` 底色 + `onSurfaceVariant` 文字。
 * **文字始终保留**，颜色只是辅助——色弱用户同样能区分（design-gui.md §12.2）。
 */
@Composable
fun TagChip(
    name: String,
    colorIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    trailingText: String? = null,
) {
    val accent = tagColor(colorIndex, darkTheme)
    val background = if (selected) accent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (selected) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .background(background, RoundedCornerShape(999.dp))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            // 标签 chip 没有长按语义，用 clickable 即可——
            // combinedClickable 是实验 API，不给这里引入额外的 opt-in 负担
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#$name",
            style = ChipStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingText != null) {
            Spacer(Modifier.width(6.dp))
            Text(text = trailingText, style = ChipStyle, color = contentColor.copy(alpha = 0.75f))
        }
    }
}

/**
 * 通用胶囊 chip。
 *
 * 前缀符号（`📍` / `#`）由调用方通过 [leadingText] 传入——
 * 场景与标签的概念区分靠「前缀 + 配色」共同强化，用户不该觉得两者是同一类东西。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingText: String? = null,
    /** 右侧的计数等附加文字，如快捷筛选 chip 上的记录条数。 */
    trailingText: String? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val background = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .background(background, RoundedCornerShape(999.dp))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(999.dp),
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingText != null) {
            Text(text = leadingText, style = ChipStyle, color = contentColor)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = ChipStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingText != null) {
            Spacer(Modifier.width(5.dp))
            Text(text = trailingText, style = ChipStyle, color = contentColor.copy(alpha = 0.7f))
        }
        if (trailingIcon != null && onTrailingClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = "移除该筛选条件",
                tint = contentColor,
                modifier = Modifier
                    .size(16.dp)
                    .combinedClickable(onClick = onTrailingClick),
            )
        }
    }
}

/** 详情页区块小标题（磁场 / 姿态 / 坐标 / 归属场景 / 标签 / 备注）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** 区块之间的细分隔线。 */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    )
}
