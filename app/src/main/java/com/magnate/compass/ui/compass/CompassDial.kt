package com.magnate.compass.ui.compass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 八个方位。索引即 45° 的倍数。 */
private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** 表盘缩到很小时退化成只画四个正方位——宁可少给信息，也不给一团糊字。 */
private val PRINCIPALS = arrayOf("N", "E", "S", "W")

/**
 * 罗盘表盘。
 *
 * **两层结构**：随方位旋转的刻度盘 + 固定不动的顶部指标与中心十字。
 * 转的是盘而不是指针——这与实体罗盘一致，用户的视线始终落在顶部指标上，
 * 读数的位置不会在屏幕上乱跑。
 *
 * ## 尺寸由调用方决定
 *
 * 这个组件**不自带尺寸**，`modifier` 传进来多大就是多大。早先这里写的是
 * `Box(modifier.size(320.dp))`，于是尺寸有了两个主人：调用方给一个、组件内再给一个，
 * 谁生效取决于修饰符链的顺序。表现上不出错，但让「表盘到底多大」变成一件要读
 * Compose 源码才能回答的事，也把下面这些退化保护的价值抵消掉了。
 *
 * ## 退化保护（这一版的重点）
 *
 * 表盘上所有元素的位置都由半径推出。半径一旦偏小，元素不会「挤一点」，
 * 而是会**成片地叠到一起**：八个方位字母同处圆心、刻度糊成一圈。
 * 因此这里的每个尺寸都有下限判断——画不下就不画，而不是画成一团。
 * 宁可看到一个干净的刻度盘，也不要看到一堆认不出的字。
 *
 * 旋转动画用 [LinearEasing]：方位是匀速变化的物理量，
 * `EaseOut` 会让盘在停下来时过冲回弹，看起来像仪器不准（design-gui.md §13）。
 *
 * `Canvas` 对读屏完全不可见，因此必须补 [semantics]——这是手绘组件最常见的遗漏
 * （design-gui.md §15）。
 */
@Composable
fun CompassDial(
    azimuthDeg: Float,
    headingName: String,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val tickColor = MaterialTheme.colorScheme.outline
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = semanticColors.needle
    val textMeasurer = rememberTextMeasurer()

    // 表盘旋转的连续角度。方位角是归一化过的，359°→1° 会表现为一次 358° 的反向跳变，
    // 因此动画前必须把目标角展开到当前角附近（CompassMath.unwrapNear）。
    var unwrapped by remember { mutableFloatStateOf(-azimuthDeg) }
    LaunchedEffect(azimuthDeg) {
        unwrapped = CompassMath.unwrapNear(unwrapped, -azimuthDeg)
    }
    val rotation by animateFloatAsState(
        targetValue = unwrapped,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "dialRotation",
    )

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "罗盘表盘，方位角 ${azimuthDeg.toInt()} 度，$headingName"
                }
        ) {
            // 尺寸退化时整个盘都不画。DrawScope 的 size 非负，但半径减去几层内缩
            // 之后完全可能变成 0 或负数，那时所有元素都会落到同一个点
            val outerRadius = size.minDimension / 2f - 2.dp.toPx()
            if (outerRadius <= 1.dp.toPx()) return@Canvas

            val center = Offset(size.width / 2f, size.height / 2f)
            val ringStroke = 2.dp.toPx()

            // —— 静态外圈 ——
            drawCircle(
                color = ringColor,
                radius = outerRadius,
                center = center,
                style = Stroke(width = ringStroke),
            )

            // —— 旋转层：刻度与方位文字 ——
            rotate(degrees = rotation, pivot = center) {
                drawTicks(center, outerRadius, tickColor)
                drawCardinalLabels(
                    center = center,
                    radius = outerRadius,
                    textMeasurer = textMeasurer,
                    labelColor = labelColor,
                    northColor = needleColor,
                )
            }

            // —— 静态层：顶部指标与中心十字 ——
            drawTopIndex(center, outerRadius, needleColor)
            drawCrosshair(center, labelColor)
        }
    }
}

/**
 * 每 5° 一根刻度，45° 的整数倍加长。
 *
 * 刻度从**正上方**（0° = 北）开始按顺时针分布，与方位角的定义一致；
 * 画布坐标里正上方是 -90°，所以这里统一减 90°。
 */
private fun DrawScope.drawTicks(center: Offset, radius: Float, color: Color) {
    val shortLen = 5.dp.toPx()
    val mediumLen = 9.dp.toPx()
    val longLen = 14.dp.toPx()
    val stroke = 1.5.dp.toPx()

    for (degree in 0 until 360 step 5) {
        val (dirX, dirY) = unitVector(degree)
        val len = when {
            degree % 45 == 0 -> longLen
            degree % 15 == 0 -> mediumLen
            else -> shortLen
        }
        val inner = radius - len
        if (inner <= 0f) continue

        drawLine(
            color = color,
            start = Offset(center.x + dirX * inner, center.y + dirY * inner),
            end = Offset(center.x + dirX * radius, center.y + dirY * radius),
            strokeWidth = if (degree % 45 == 0) stroke * 1.6f else stroke,
        )
    }
}

/**
 * 方位字母。**字号、环半径、画几个都是按实际半径算出来的**，不是写死的——
 * 写死的那一版在表盘偏小时会让八个字母直接叠在圆心。
 *
 * 三级降级：
 * 1. 半径放得下八个 → 全画，字号随半径缩放；
 * 2. 放不下八个但放得下四个 → 只画 N/E/S/W（少了东北、西南这类中间方位，
 *    但每个字都清楚，信息量损失远小于一坨糊字）；
 * 3. 半径小到连四个都放不下 → 一个都不画，只留刻度。读屏仍能拿到完整读数。
 */
private fun DrawScope.drawCardinalLabels(
    center: Offset,
    radius: Float,
    textMeasurer: TextMeasurer,
    labelColor: Color,
    northColor: Color,
) {
    // 字母环比刻度内端再收进来一些：14dp 是最长刻度，再留 10dp 空隙
    val labelRadius = radius - 14.dp.toPx() - 10.dp.toPx()
    if (labelRadius <= 1.dp.toPx()) return

    // 字号随半径缩放，并夹在一个可读区间内。
    // px → sp 要同时除掉 density 和 fontScale，否则系统字体调大后会反过来撑爆表盘
    val scale = density * fontScale
    val fontSize = (labelRadius * 0.17f / scale).coerceIn(9f, 15f).sp

    val cardinalStyle = LabelStyle.copy(fontSize = fontSize, fontWeight = FontWeight.Medium)
    val northStyle = LabelStyle.copy(fontSize = fontSize * 1.15f, fontWeight = FontWeight.Bold)

    // 先量一遍最宽的那个字母，用它判断相邻标签会不会撞上
    val widest = textMeasurer.measure(text = "NE", style = cardinalStyle).size.width
    val arcStep = (2.0 * PI * labelRadius / CARDINALS.size).toFloat()

    val labels: List<Pair<Int, String>> = when {
        // 留 1.6 倍余量：中文字体下的拉丁字母宽度不总是可预测的
        arcStep > widest * 1.6f -> CARDINALS.mapIndexed { index, label -> index * 45 to label }
        arcStep * 2f > widest * 1.6f -> PRINCIPALS.mapIndexed { index, label -> index * 90 to label }
        else -> emptyList()
    }

    labels.forEach { (degree, label) ->
        val isNorth = degree == 0
        val layout = textMeasurer.measure(
            text = label,
            style = if (isNorth) northStyle else cardinalStyle,
        )
        val (dirX, dirY) = unitVector(degree)

        drawText(
            textLayoutResult = layout,
            color = if (isNorth) northColor else labelColor,
            topLeft = Offset(
                center.x + dirX * labelRadius - layout.size.width / 2f,
                center.y + dirY * labelRadius - layout.size.height / 2f,
            ),
        )
    }
}

/** 顶部固定指标：从外圈向圆心指的三角，方位读数就落在它的尖端处。 */
private fun DrawScope.drawTopIndex(center: Offset, radius: Float, color: Color) {
    val halfWidth = 9.dp.toPx()
    val height = 16.dp.toPx()
    val top = center.y - radius - 1.dp.toPx()

    val path = Path().apply {
        moveTo(center.x, top + height)
        lineTo(center.x - halfWidth, top)
        lineTo(center.x + halfWidth, top)
        close()
    }
    drawPath(path, color)
}

/** 中心十字：给读数一个视觉锚点，否则盘旋转时眼睛没有落点。 */
private fun DrawScope.drawCrosshair(center: Offset, color: Color) {
    val arm = 8.dp.toPx()
    val stroke = 1.5.dp.toPx()
    val faded = color.copy(alpha = 0.5f)

    drawLine(
        color = faded,
        start = Offset(center.x - arm, center.y),
        end = Offset(center.x + arm, center.y),
        strokeWidth = stroke,
    )
    drawLine(
        color = faded,
        start = Offset(center.x, center.y - arm),
        end = Offset(center.x, center.y + arm),
        strokeWidth = stroke,
    )
}

/** 画布坐标下的单位方向向量：0° 指向正上方，角度顺时针增长。 */
private fun unitVector(degree: Int): Pair<Float, Float> {
    val rad = Math.toRadians(degree - 90.0)
    return cos(rad).toFloat() to sin(rad).toFloat()
}
