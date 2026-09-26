package com.magnate.compass.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 详情页的迷你罗盘（design-gui.md §7.1）。
 *
 * **静态**，不订阅传感器：详情页展示的是一条历史记录，让它跟着手机转
 * 只会让人误以为这个指针代表「现在的朝向」。表盘固定，指针指向记录的方位角。
 */
@Composable
fun MiniCompass(
    azimuthDeg: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val ringColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = semanticColors.needle
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember { LabelStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium) }

    Box(modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        "方位指示，${azimuthDeg.toInt()} 度，${CompassMath.headingName(azimuthDeg)}"
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 10.dp.toPx()
            val stroke = 1.5.dp.toPx()

            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(stroke),
            )

            // 四个方位字，不随指针旋转——迷你罗盘是静态的，字保持正立更易读
            listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (label, degree) ->
                val (dx, dy) = unitVector(degree)
                val layout = textMeasurer.measure(label, style = labelStyle)
                drawText(
                    textLayoutResult = layout,
                    color = if (degree == 0) needleColor else labelColor,
                    topLeft = Offset(
                        center.x + dx * (radius - 16.dp.toPx()) - layout.size.width / 2f,
                        center.y + dy * (radius - 16.dp.toPx()) - layout.size.height / 2f,
                    ),
                )
            }

            // 指针：红端指向记录的方位角
            rotate(degrees = -azimuthDeg, pivot = center) {
                val tip = Offset(center.x, center.y - radius + 22.dp.toPx())
                val tail = Offset(center.x, center.y + 14.dp.toPx())
                val halfWidth = 5.dp.toPx()

                drawPath(
                    Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(center.x - halfWidth, center.y)
                        lineTo(center.x + halfWidth, center.y)
                        close()
                    },
                    color = needleColor,
                )
                drawLine(
                    color = labelColor,
                    start = center,
                    end = tail,
                    strokeWidth = stroke * 2f,
                )
            }

            drawCircle(color = labelColor, radius = 3.dp.toPx(), center = center)
        }
    }
}

private fun unitVector(degree: Int): Pair<Float, Float> {
    val rad = Math.toRadians(degree - 90.0)
    return cos(rad).toFloat() to sin(rad).toFloat()
}
