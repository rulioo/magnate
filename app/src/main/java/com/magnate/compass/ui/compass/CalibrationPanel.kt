package com.magnate.compass.ui.compass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.magnate.compass.sensor.CalibrationCause
import com.magnate.compass.sensor.CalibrationMessages
import com.magnate.compass.ui.common.AccuracyBadge
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 校准模式的面板（design-gui.md §11「校准中」、§13 双纽线动画）。
 *
 * ## 它是诊断面板，不只是操作指引
 *
 * 用户按下提示条进来时，他并不知道自己遇到的是三种成因里的哪一种。
 * 因此这个面板做两件事：**当场给出判断**（标题与正文按成因分支），
 * 以及**当场给出证据**（精度、磁场强度、硬磁偏置占比三个实时读数）。
 * 没有这两个，用户画完 8 字之后仍然不知道自己有没有成功——
 * 那正是这一版要修的：原先只有一个静态弹窗，画完点掉，毫无回音。
 *
 * ## 达标即确认
 *
 * 判据转为 [CalibrationCause.OK] 时立刻显示「✓ 已校准」。
 * 用户不需要理解任何指标，只需要看到这句话出现。
 */
@Composable
fun CalibrationPanel(
    state: CompassUiState,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalMagnateSemanticColors.current
    val cause = state.quality.cause
    val calibrated = cause == CalibrationCause.OK

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = if (calibrated) {
                    semanticColors.success.copy(alpha = 0.12f)
                } else {
                    semanticColors.warning.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp)
            // 面板内部可滚：横屏时它最多占屏高的一半，正文放不下就滚，
            // 而不是把整块内容顶出屏幕（外面再套一层滚动会与主页的 verticalScroll 打架）
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.calibrationTitle,
                style = BodyStyle,
                color = if (calibrated) semanticColors.success else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onFinish) { Text("完成") }
        }

        // 画 8 字的过程才有动画；已经校准好或问题是环境/配件时，
        // 播一个「请照此画」的动画等于在给错误的建议配图。
        if (cause == CalibrationCause.NEEDS_CALIBRATION) {
            FigureEightGuide()
            Spacer(Modifier.height(8.dp))
        }

        LiveReadings(state)

        Spacer(Modifier.height(10.dp))

        Text(
            text = state.calibrationGuidance,
            style = BodyStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 来源降级是**校准治不好的另一回事**：B、C 两级来源本来就比旋转矢量抖，
        // 不说明白，这些机型的用户会把机型差异当成自己没校准好，反复做无用功。
        state.calibrationSourceNote?.let { note ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = note,
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        // 恒定偏差 ≠ 校准问题。用户说「确保指向是正确的」，
        // 而最常见的那种「不对」其实是磁北与真北之差，画多少 8 字都不会消。
        Text(
            text = CalibrationMessages.DECLINATION_NOTE,
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 实时读数：精度、磁场强度、硬磁偏置占比。
 *
 * 三项的分工在界面上就是分开的——精度是设备自报的结论，
 * 强度回答「附近有没有铁」，偏置占比回答「校准够不够」。
 * 合成一个「校准进度条」会好看，但会把三个不同的物理量压成一个用户无法追问的数，
 * 而用户此刻恰恰需要知道是哪一项还不行。
 */
@Composable
private fun LiveReadings(state: CompassUiState) {
    val semanticColors = LocalMagnateSemanticColors.current

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AccuracyBadge(level = state.accuracy)
        Text(
            text = GeoFormat.formatMagnitude(state.magnitudeUt),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    // 没有未校准磁力计的设备不显示这一项。**显示 0% 是错的**——
    // 0 的意思是「偏置很小」，「测不了」与「很好」恰好相反。
    state.quality.biasRatio?.let { ratio ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = "硬磁偏置 ${(ratio * 100f).roundToInt()}% · 越小越好",
            style = LabelStyle,
            color = if (state.quality.needsAttention) {
                semanticColors.warning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 双纽线引导动画：一个圆点沿「8」字匀速绕行。
 *
 * 这是本项目第一处 [rememberInfiniteTransition]。选它而不是 `animateFloatAsState`，
 * 是因为这里要的是**首尾相接的循环**，而那段动画没有终点状态可依。
 *
 * **相位只在绘制阶段读取**（`phase.value` 写在 `Canvas` 的 lambda 里，而不是用 `by`
 * 委托在组合阶段读）：否则每帧都会触发一次重组。20Hz 的传感器刷新已经够忙了，
 * 一个装饰性动画不该再往组合里加一帧一次的工作量。
 *
 * 匀速（Linear）。与表盘旋转同理——引导动画的作用是让用户的眼睛跟得住，
 * 加减速只会让它看起来时快时慢（design-gui.md §13）。
 */
@Composable
private fun FigureEightGuide() {
    val transition = rememberInfiniteTransition(label = "figureEight")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FIGURE_EIGHT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "figureEightPhase",
    )

    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor = MaterialTheme.colorScheme.primary

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(FIGURE_EIGHT_HEIGHT)
    ) {
        val pad = 14.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Gerono 双纽线：x = a·cos t，y = (a/2)·sin 2t。
        // 纵向振幅只有横向的一半，所以 a 受两个方向夹逼——只看宽度会让曲线上下出界。
        val a = minOf(size.width / 2f - pad, size.height - 2f * pad)
        if (a <= 0f) return@Canvas

        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val t = i.toFloat() / steps * TWO_PI
            val x = cx + a * cos(t)
            val y = cy + a / 2f * sin(2f * t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            color = guideColor,
            alpha = 0.35f,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        val t = phase.value * TWO_PI
        drawCircle(
            color = dotColor,
            radius = 5.dp.toPx(),
            center = Offset(cx + a * cos(t), cy + a / 2f * sin(2f * t)),
        )
    }
}

private const val TWO_PI = (2.0 * PI).toFloat()

/** 一圈的时长。慢到眼睛跟得住轨迹，快到不用等（design-gui.md §13）。 */
private const val FIGURE_EIGHT_PERIOD_MS = 2_400

private val FIGURE_EIGHT_HEIGHT = 108.dp
