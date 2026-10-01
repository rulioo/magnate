package com.magnate.compass.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors

/**
 * 页面顶部横幅：一句诊断 + 可选的补救动作。
 *
 * **用警告色而不是错误色**：它承载的都是**可修复的配置问题**——没有精确定位权限、
 * 附近有铁磁物、磁力计该校准了——不是故障。画成红色会让用户以为设备坏了，
 * 而实际上他只要走三步就能解决。
 *
 * 从 `ui/satellite/SatelliteScreen.kt` 上移而来。搬家的理由不是「两处长得像」，
 * 而是**同一个东西在两处各写一份，迟早会各自演化出不同的语气与形状**，
 * 而这两处说的是同一件事：当前这个读数为什么不可信。
 *
 * @param message 一句话。**必须自带动词**（「· 查看」「· 开启」）——
 *   整条可点但没有文字线索时，用户不会去点它。
 * @param onAction 给了就渲染一个实心按钮。用于**必须立刻处理**的那一种，
 *   例如缺少精确定位权限。不需要按钮的场景传 null，比放一个按了没反应的按钮好。
 * @param onClick 整条横幅可点。与 [onAction] 可以同时存在：前者是「带我去看看」，
 *   后者是「就在这里把它修好」。
 */
@Composable
fun Banner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String = "开启精确定位",
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val warning = LocalMagnateSemanticColors.current.warning

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(warning.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp),
    ) {
        Text(text = message, style = BodyStyle, color = MaterialTheme.colorScheme.onSurface)
        if (onAction != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
