package com.magnate.compass.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ————————————————————————————————————————————————————————————
//  design-gui.md §12.3 字体
//
//  等宽原则：所有随时间跳动或需要纵向对齐的数字一律用等宽字体——
//  主页读数防止抖动，列表页保证整列对齐。
// ————————————————————————————————————————————————————————————

/** 主页方位角读数 56sp Medium */
val AzimuthReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 56.sp,
)

/** 详情页方位角 40sp Medium */
val AzimuthDetailStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 40.sp,
)

/** 列表项方位角 22sp Medium */
val AzimuthListItemStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
)

/** 磁场总强度（主页）28sp Medium */
val MagnitudeReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 28.sp,
)

/** 磁场总强度（详情）20sp Medium */
val MagnitudeDetailStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 20.sp,
)

/** 三分量数值 16sp Normal */
val AxisValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
)

/** 主按钮副标题 13sp */
val ButtonSubtitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
)

/** 正文 / 列表时间 15sp */
val BodyStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
)

/** 场景条 / 徽标 / 状态文字 13sp */
val LabelStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
)

/** 标签 chip / 轴标签 11sp */
val ChipStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
)

internal val MagnateTypography = Typography(
    headlineLarge = AzimuthReadoutStyle,
    headlineMedium = AzimuthDetailStyle,
    titleLarge = MagnitudeReadoutStyle,
    titleMedium = MagnitudeDetailStyle,
    bodyLarge = BodyStyle,
    bodyMedium = BodyStyle,
    labelLarge = LabelStyle,
    labelMedium = LabelStyle,
    labelSmall = ChipStyle,
)
