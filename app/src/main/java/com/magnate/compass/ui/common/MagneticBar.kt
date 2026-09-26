package com.magnate.compass.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

/**
 * 磁场强度条。
 *
 * 量程**固定 0–100 μT**，不做自适应——自适应量程会让用户失去「多大算大」的直觉
 * （design.md §6）。25–65 μT 的地磁正常区间以淡色标出，读数的位置一眼可判。
 */
@Composable
fun MagneticBar(
    magnitudeUt: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val normalBandColor = semanticColors.success.copy(alpha = 0.22f)
    val isNormal = CompassMath.isMagneticFieldNormal(magnitudeUt)
    val valueColor = if (isNormal) semanticColors.success else MaterialTheme.colorScheme.error

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
        ) {
            drawMagneticBar(
                magnitudeUt = magnitudeUt,
                trackColor = trackColor,
                normalBandColor = normalBandColor,
                valueColor = valueColor,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(8.dp)
                .background(valueColor, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isNormal) "正常范围" else "检测到异常磁场",
            style = LabelStyle,
            color = valueColor,
        )
    }
}

private fun DrawScope.drawMagneticBar(
    magnitudeUt: Float,
    trackColor: Color,
    normalBandColor: Color,
    valueColor: Color,
) {
    val fullScale = CompassMath.MAGNETIC_BAR_FULL_SCALE_UT
    val radius = size.height / 2f

    // 轨道
    drawRoundRect(
        color = trackColor,
        size = size,
        cornerRadius = CornerRadius(radius, radius),
    )

    // 地磁正常区间 25–65 μT
    val bandStart = (CompassMath.MAGNETIC_FIELD_MIN_UT / fullScale).coerceIn(0f, 1f) * size.width
    val bandEnd = (CompassMath.MAGNETIC_FIELD_MAX_UT / fullScale).coerceIn(0f, 1f) * size.width
    drawRect(
        color = normalBandColor,
        topLeft = Offset(bandStart, 0f),
        size = Size(bandEnd - bandStart, size.height),
    )

    // 当前读数
    val fraction = (magnitudeUt / fullScale).coerceIn(0f, 1f)
    drawRoundRect(
        color = valueColor,
        size = Size(fraction * size.width, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

/** 磁场三分量的等宽展示。 */
@Composable
fun AxisValuesRow(
    magX: Float,
    magY: Float,
    magZ: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AxisValue("X", magX)
        AxisValue("Y", magY)
        AxisValue("Z", magZ)
    }
}

@Composable
private fun AxisValue(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GeoFormat.formatSigned(value),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
