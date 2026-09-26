package com.magnate.compass.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.AccuracyTier
import com.magnate.compass.util.GeoFormat

/**
 * 坐标来源徽标——v1.2 新增的核心视觉元素（design-gui.md §6.3）。
 *
 * **两种来源必须用不同图标与颜色，这不是装饰。**
 * `📡 本机 · ±8m` 意味着「测量点就在这个坐标 8 米范围内」；
 * `📐 场景 · B2层` 意味着「测量点在这个场景的某处，可能是几十米外的另一个房间」。
 * 两者在空间精度上差了一个数量级，用户必须能一眼区分。
 *
 * 读屏用户看不到图标，因此语义描述里必须带上来源文字（design-gui.md §15）。
 */
@Composable
fun LocationBadge(
    location: EffectiveLocation,
    modifier: Modifier = Modifier,
    sceneNameMaxChars: Int = 8,
) {
    val semanticColors = LocalMagnateSemanticColors.current

    val (icon, text, tint, description) = when (location) {
        is EffectiveLocation.Measured -> {
            val tier = GeoFormat.accuracyTier(location.point.accuracyMeters)
            val color = when (tier) {
                AccuracyTier.GOOD -> semanticColors.success
                AccuracyTier.MEDIUM -> MaterialTheme.colorScheme.onSurfaceVariant
                AccuracyTier.POOR, AccuracyTier.UNKNOWN -> semanticColors.warning
            }
            BadgeVisual(
                icon = MagnateIcons.MyLocation,
                text = "本机 · ${GeoFormat.formatAccuracy(location.point.accuracyMeters)}",
                tint = color,
                description = "坐标来源 本机实测",
            )
        }

        is EffectiveLocation.FromScene -> BadgeVisual(
            icon = Icons.Outlined.Place,
            text = "场景 · ${location.sceneName.truncate(sceneNameMaxChars)}",
            tint = MaterialTheme.colorScheme.primary,
            description = "坐标来源 场景 ${location.sceneName}",
        )

        is EffectiveLocation.SceneWithoutCoordinate -> BadgeVisual(
            icon = MagnateIcons.LocationOff,
            text = "场景未设坐标",
            tint = semanticColors.warning,
            description = "坐标来源 场景 ${location.sceneName}，该场景未设置坐标",
        )

        EffectiveLocation.None -> BadgeVisual(
            icon = MagnateIcons.LocationOff,
            text = "无坐标",
            tint = semanticColors.warning,
            description = "无坐标",
        )
    }

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = LabelStyle,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 坐标来源的口语化描述，供读屏使用。
 *
 * 与 [LocationBadge] 的视觉文案分开维护：视觉上「本机 · ±8m」足够，
 * 但朗读时需要把「坐标来源」四个字补全——只读「本机 ±8 米」，
 * 用户不知道这个数字是精度还是距离（design-gui.md §15）。
 */
fun EffectiveLocation.spoken(): String = when (this) {
    is EffectiveLocation.Measured ->
        "坐标来源 本机实测，精度 ${GeoFormat.formatAccuracy(point.accuracyMeters)}"

    is EffectiveLocation.FromScene -> "坐标来源 场景 $sceneName"

    is EffectiveLocation.SceneWithoutCoordinate -> "场景 $sceneName 未设置坐标"

    EffectiveLocation.None -> "无坐标"
}

private data class BadgeVisual(
    val icon: ImageVector,
    val text: String,
    val tint: Color,
    val description: String,
)

/** 场景名过长时截断为 `B2层…`，避免把整行挤爆。 */
private fun String.truncate(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars) + "…"
