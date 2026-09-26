package com.magnate.compass.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors

/** 气泡贴到盘边时，圆心离盘边的额外留白，避免气泡被裁掉一半。 */
private const val RIM_PADDING_FRACTION = 0.14f

/**
 * 水平仪气泡——倾角的图形读数。
 *
 * ## 为什么气泡比容差环还大
 *
 * 这是**靶心式水平仪**的读法：气泡浮在中心时盖住了容差环，才算水平。
 * 容差 1° 在盘面上只有半径的 3%（见下），若把气泡画得比它小，
 * 那圈环就细到看不见，用户只能靠猜。反过来把气泡画大，
 * 「盖住 / 没盖住」就成了一个不需要读数的判断。
 *
 * ## 为什么用 sin 而不是线性映射
 *
 * 气泡在真实水平仪里沿**盘面**滑动，位移正比于重力方向在盘面上的投影，
 * 即 `sin(倾角)`；而容差环画在 `sin(1°) / sin(30°)` 处，
 * 于是「圆心落进环内」与 `isLevel` 的判据**在几何上完全等价**，
 * 不是两套各自调出来的阈值。用线性映射的话，两者会差 5% 左右——
 * 小角度下这点差别恰好落在「看着水平、数字说不水平」的窗口里。
 *
 * 量程取 [CompassMath.TILT_WARNING_DEGREES]：到了这个角度气泡正好贴边，
 * 而盘边本身就是「太斜了」的提示，不需要再画一圈。
 *
 * ## 可访问性
 *
 * `Canvas` 对读屏完全不可见，必须自己补 [semantics]（design-gui.md §15）。
 * 方位读数由旁边的数字文本承担，这里描述的是**姿态**，不重复方位角。
 */
@Composable
fun LevelBubble(
    pitchDeg: Float,
    rollDeg: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    // 颜色由倾角自己算，不由调用方传入：几何位置和变色条件必须同源，
    // 否则会出现「气泡明显偏着、颜色却是绿的」这种自相矛盾的画面
    val tiltDeg = CompassMath.tiltFromHorizontal(pitchDeg, rollDeg)
    val isLevel = tiltDeg <= CompassMath.LEVEL_TOLERANCE_DEGREES
    val rimColor = if (isLevel) semanticColors.success else MaterialTheme.colorScheme.outline
    val ringColor = if (isLevel) semanticColors.success else MaterialTheme.colorScheme.outline
    val crossColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val bubbleColor = if (isLevel) semanticColors.success else semanticColors.needle

    val description = buildString {
        append("水平仪，合成倾角 ")
        append(tiltDeg.toInt())
        append(" 度，")
        append(if (isLevel) "已水平" else "未水平")
    }

    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 1.dp.toPx()
            if (radius <= 1.dp.toPx()) return@Canvas

            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = 1.5.dp.toPx()

            drawCircle(
                color = rimColor,
                radius = radius,
                center = center,
                style = Stroke(width = stroke),
            )

            drawCross(center = center, radius = radius, color = crossColor)

            // 容差环。半径由 sin 映射直接推出，与气泡同一个比例尺——
            // 见类文档：这一圈的位置不是调出来的，是算出来的。
            val sinRange = sinDeg(CompassMath.TILT_WARNING_DEGREES)
            val toleranceRadius = radius * sinDeg(CompassMath.LEVEL_TOLERANCE_DEGREES) / sinRange
            drawCircle(
                color = ringColor,
                radius = toleranceRadius,
                center = center,
                style = Stroke(width = stroke),
            )

            drawBubble(
                center = center,
                radius = radius,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg,
                color = bubbleColor,
                sinRange = sinRange,
            )
        }
    }
}

/**
 * 气泡本体。位置由 [CompassMath.bubbleOffset] 给出，量纲是 `sin(倾角)`，
 * 除以量程的 `sin` 就得到「占半径的几分之几」。
 */
private fun DrawScope.drawBubble(
    center: Offset,
    radius: Float,
    pitchDeg: Float,
    rollDeg: Float,
    color: Color,
    sinRange: Float,
) {
    val offset = CompassMath.bubbleOffset(pitchDeg, rollDeg)
    val bubbleRadius = radius * RIM_PADDING_FRACTION * 0.9f
    // 夹住而不是溢出：让气泡在最斜的姿态下仍然完整可见，
    // 贴边本身就是「已经到量程尽头」的视觉信号
    val maxOffset = radius - bubbleRadius
    val scale = radius / sinRange
    val dx = (offset.x * scale).coerceIn(-maxOffset, maxOffset)
    val dy = (offset.y * scale).coerceIn(-maxOffset, maxOffset)

    drawCircle(
        color = color,
        radius = bubbleRadius,
        center = Offset(center.x + dx, center.y + dy),
    )
}

/** 十字参考线。给气泡一个落点，否则盘面上只有一个孤零零的圆在飘。 */
private fun DrawScope.drawCross(center: Offset, radius: Float, color: Color) {
    val stroke = 1.dp.toPx()
    val gap = radius * 0.08f
    val arm = radius * 0.55f

    listOf(
        Offset(center.x - arm, center.y) to Offset(center.x - gap, center.y),
        Offset(center.x + gap, center.y) to Offset(center.x + arm, center.y),
        Offset(center.x, center.y - arm) to Offset(center.x, center.y - gap),
        Offset(center.x, center.y + gap) to Offset(center.x, center.y + arm),
    ).forEach { (start, end) ->
        drawLine(color = color, start = start, end = end, strokeWidth = stroke)
    }
}

private fun sinDeg(degrees: Float): Float =
    kotlin.math.sin(Math.toRadians(degrees.toDouble())).toFloat()
