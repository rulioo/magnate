package com.magnate.compass.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 「这个值刚刚变了，闪一次高亮」（design-gui.md §13）。
 *
 * 存在的理由是**引用语义的副作用**：修改一次 FIXED 场景的坐标，会静默改变
 * 该场景下所有历史记录的坐标。十几条记录的值同时变了，如果没有任何提示，
 * 用户会以为数据出了问题——或者更糟，根本没注意到它变了。
 *
 * [key] 变化即触发一次高亮。若直接用坐标值做 key，**首次组合也会闪一次**，
 * 因此调用方应传一个「变更序号」之类的信号，而非值本身。
 */
@Composable
fun Modifier.flashHighlight(
    key: Any?,
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    // 动画作用在 alpha 上而不是 Color 上：Animatable(Float) 是 core 里现成的，
    // Color 版本需要额外的 TwoWayConverter，没必要为此引入
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        alpha.snapTo(0.28f)
        alpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 400))
    }

    return background(color.copy(alpha = alpha.value), shape)
}
