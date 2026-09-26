package com.magnate.compass.ui.compass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.common.MagneticBar
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.MagnitudeReadoutStyle
import com.magnate.compass.util.GeoFormat

/**
 * 磁场卡片（design-gui.md §3.1 ⑤）。
 *
 * 总强度用大号等宽字，三分量各自成格。三分量之间用细分隔线而不是留白——
 * 数值本身带正负号、宽度不定，靠留白分组会让「哪三个数是一组」变得含糊。
 */
@Composable
fun MagneticFieldCard(
    magnitudeUt: Float,
    magX: Float,
    magY: Float,
    magZ: Float,
    modifier: Modifier = Modifier,
) {
    val isNormal = CompassMath.isMagneticFieldNormal(magnitudeUt)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "磁场强度",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = GeoFormat.formatMagnitude(magnitudeUt),
                style = MagnitudeReadoutStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(12.dp))

        AxisGrid(magX = magX, magY = magY, magZ = magZ)

        Spacer(Modifier.height(12.dp))

        MagneticBar(magnitudeUt = magnitudeUt, modifier = Modifier.fillMaxWidth())

        if (!isNormal) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "读数超出地磁正常范围（${CompassMath.MAGNETIC_FIELD_MIN_UT.toInt()}–" +
                    "${CompassMath.MAGNETIC_FIELD_MAX_UT.toInt()} μT），附近可能有铁磁物体或强电流。",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 三分量网格。
 *
 * 窄屏（< 360dp）下改为两行，避免每个格子被压到放不下 `-12.4` 这样的五字符数字
 * （design-gui.md §14）。
 */
@Composable
private fun AxisGrid(magX: Float, magY: Float, magZ: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 300.dp

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AxisCell("X", magX, Modifier.weight(1f))
                    AxisCell("Y", magY, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AxisCell("Z", magZ, Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AxisCell("X", magX, Modifier.weight(1f))
                AxisCell("Y", magY, Modifier.weight(1f))
                AxisCell("Z", magZ, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AxisCell(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GeoFormat.formatSigned(value),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
