package com.magnate.compass.ui.compass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.ui.common.LevelBubble
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

/**
 * 倾角卡片（主页实时姿态）。
 *
 * 左边气泡、右边数字。两者**不是冗余**：气泡回答「是不是平的」，
 * 一眼就能看出来；数字回答「差多少度」，气泡在 1° 附近根本分辨不出 0.4° 与 0.9°。
 * 用户按压传感器时需要的正是后者，而判断「可以按了吗」需要的是前者。
 *
 * 数值沿用磁场卡片里 X/Y/Z 那三格的视觉（[AxisValueStyle] 等宽数字）——
 * 姿态读数和磁场分量一样是**会不停跳动的有符号小数**，等宽是让它不抖的前提
 * （design-gui.md §12.3）。
 *
 * 倾斜提示收纳在卡片底部，而不是像先前那样散在读数下面单独一行：
 * 它讲的是姿态，理应长在姿态卡片里，也才不会和磁场卡片的异常提示
 * 挤成上下两条看起来毫无关联的黄字。
 */
@Composable
fun TiltCard(
    pitchDeg: Float,
    rollDeg: Float,
    tiltDeg: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val isLevel = tiltDeg <= CompassMath.LEVEL_TOLERANCE_DEGREES
    val tooTilted = tiltDeg > CompassMath.TILT_WARNING_DEGREES

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelBubble(
                pitchDeg = pitchDeg,
                rollDeg = rollDeg,
                modifier = Modifier.size(BUBBLE_SIZE),
            )

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "倾角",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${tiltDeg.toInt()}°",
                    style = AxisValueStyle,
                    color = if (isLevel) {
                        semanticColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = if (isLevel) "已水平" else "偏离水平",
                    style = LabelStyle,
                    color = if (isLevel) semanticColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AttitudeCell("俯仰", pitchDeg)
                AttitudeCell("翻滚", rollDeg)
            }
        }

        if (tooTilted) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "手机接近竖直，方位角噪声偏大——平放测量更准",
                style = LabelStyle,
                color = semanticColors.warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 俯仰/翻滚的小格。
 *
 * 与 [LevelBubble] 的容差同理，**显示的是原始带符号角**，不是「离水平多少度」：
 * 俯仰与翻滚各自有方向，把它们都折成正数会丢掉「往哪边斜」这个信息，
 * 而气泡只给得出一个合成方向，分不出是哪一项贡献的。
 *
 * 读屏用户听不到气泡，因此这里由数字本身承担全部的语义——
 * 标签用中文全称而不是 P/R 缩写，朗读出来才有意义。
 */
@Composable
private fun AttitudeCell(label: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${GeoFormat.formatSigned(value)}°",
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 气泡直径。
 *
 * 96dp 是「容差环仍能被看见」的下限：环半径是盘半径的约 3.4%
 * （见 LevelBubble 的比例尺说明），再小就细到看不见了。
 */
private val BUBBLE_SIZE = 96.dp
