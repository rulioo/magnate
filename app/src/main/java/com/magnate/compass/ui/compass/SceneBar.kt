package com.magnate.compass.ui.compass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

/**
 * 场景条——v1.2 最重要的新增元素（design-gui.md §4）。
 *
 * 它只负责一件事：**让用户在任何时刻都清楚当前记录会归入哪个场景。**
 *
 * 四种形态的视觉区分是刻意的：未选场景时低调（`surfaceVariant`），
 * 选了场景后醒目（`primary`），场景缺坐标时告警（`warning`）。
 * 「当前有场景」是一个会改变数据归属的状态，用户必须一眼看出自己处于哪种模式。
 *
 * 读屏必须把**场景名与坐标模式都读出来**——这是防误存的关键信息，
 * 图标对读屏用户毫无意义（design-gui.md §15）。
 */
@Composable
fun SceneBar(
    activeScene: SceneEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current

    val visual = when {
        activeScene == null -> SceneBarVisual(
            leading = "⚪",
            primaryText = "未选择场景",
            secondaryText = "使用本机定位",
            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            spoken = "未选择场景，使用本机定位",
        )

        !activeScene.hasCoordinate -> SceneBarVisual(
            leading = "⚠",
            primaryText = activeScene.name,
            secondaryText = "未设置坐标",
            background = semanticColors.warning.copy(alpha = 0.12f),
            contentColor = semanticColors.warning,
            spoken = "当前场景 ${activeScene.name}，未设置坐标",
        )

        activeScene.coordMode == CoordMode.FIXED -> SceneBarVisual(
            leading = "📍",
            primaryText = activeScene.name,
            secondaryText = "场景坐标 ${GeoFormat.formatAccuracy(activeScene.locationAccuracy ?: Float.MAX_VALUE)}",
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary,
            spoken = "当前场景 ${activeScene.name}，场景坐标，" +
                "精度${GeoFormat.formatAccuracy(activeScene.locationAccuracy ?: Float.MAX_VALUE)}，双击切换场景",
        )

        else -> SceneBarVisual(
            leading = "📍",
            primaryText = activeScene.name,
            secondaryText = "跟随本机定位",
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary,
            spoken = "当前场景 ${activeScene.name}，跟随本机定位，双击切换场景",
        )
    }

    // 底色与文字交叉淡入 200ms EaseInOut（design-gui.md §13）
    val background by animateColorAsState(
        targetValue = visual.background,
        animationSpec = tween(durationMillis = 200),
        label = "sceneBarBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = visual.contentColor,
        animationSpec = tween(durationMillis = 200),
        label = "sceneBarContent",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // 合并为单个语义节点：图标与两行文字是同一件事的三个片段
            .clearAndSetSemantics { contentDescription = visual.spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标形态的场景用矢量图标而非 emoji 字符，避免不同 ROM 的 emoji 字形差异过大
        if (activeScene != null && activeScene.hasCoordinate) {
            Icon(
                imageVector = Icons.Outlined.Place,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.width(16.dp),
            )
        } else {
            Text(text = visual.leading, style = LabelStyle, color = contentColor)
        }

        Spacer(Modifier.width(6.dp))

        Text(
            text = visual.primaryText,
            style = LabelStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "· ${visual.secondaryText}",
            style = LabelStyle,
            color = contentColor.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(text = "切换", style = LabelStyle, color = contentColor)
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.width(16.dp),
            )
        }
    }
}

private data class SceneBarVisual(
    val leading: String,
    val primaryText: String,
    val secondaryText: String,
    val background: Color,
    val contentColor: Color,
    val spoken: String,
)
