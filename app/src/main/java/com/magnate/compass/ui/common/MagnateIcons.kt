package com.magnate.compass.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘图标集。
 *
 * **为什么不用 `material-icons-extended`**：那个包为了凑齐两千多个图标，体积超过 35 MB。
 * 本项目一共只用到二十几个图标，为每个图标付 1.5 MB 的代价毫无道理——而 Google 也正因为
 * 体积问题把它标记为废弃。常用图标改由 `material-icons-core` 提供（随 material3 传递依赖，
 * 只有约四十个），本文件只补齐 core 里没有的那几个。
 *
 * 全部按 Material 的 24×24 视口、1.7 描边绘制，与 core 图标视觉重量一致。
 * 颜色写死黑色——`Icon` 会用 `tint` 以 `BlendMode.SrcIn` 整体覆盖，
 * 画成什么颜色无所谓，但必须**不透明**，否则 tint 保留下来的 alpha 会让图标变淡。
 */
object MagnateIcons {

    /** 罗盘玫瑰——「记录」入口。比通用列表图标更贴合本应用。 */
    val Explore: ImageVector = icon("MagnateExplore") {
        stroked {
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
            close()
        }
        filled {
            // 指向东北的菱形指针，对角线与圆同心
            moveTo(16.8f, 7.2f)
            lineTo(13.6f, 13.6f)
            lineTo(7.2f, 16.8f)
            lineTo(10.4f, 10.4f)
            close()
        }
    }

    /** 标签——标签管理入口与空状态。 */
    val Sell: ImageVector = icon("MagnateSell") {
        stroked {
            moveTo(4.5f, 3f)
            lineTo(10.8f, 3f)
            lineTo(21f, 13.2f)
            lineTo(13.2f, 21f)
            lineTo(3f, 10.8f)
            lineTo(3f, 4.5f)
            close()
        }
        filled { circle(6.4f, 6.4f, 1.4f) }
    }

    /** 复制到剪贴板。 */
    val ContentCopy: ImageVector = icon("MagnateContentCopy") {
        // 后面那张纸只画露出来的 L 形，避免与前一张的描边交叠
        stroked {
            moveTo(15.5f, 2.5f)
            lineTo(6f, 2.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
            lineTo(3.5f, 16f)
        }
        stroked {
            moveTo(8.5f, 5.5f)
            lineTo(18.5f, 5.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            lineTo(20.5f, 18.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            lineTo(8.5f, 20.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            lineTo(6.5f, 7.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
        }
    }

    /** 在地图中打开。 */
    val Map: ImageVector = icon("MagnateMap") {
        stroked {
            moveTo(3f, 6.6f)
            lineTo(9f, 4.2f)
            lineTo(15f, 6.6f)
            lineTo(21f, 4.2f)
            lineTo(21f, 17.4f)
            lineTo(15f, 19.8f)
            lineTo(9f, 17.4f)
            lineTo(3f, 19.8f)
            close()
        }
        // 两道折痕把纸分成三折
        stroked {
            moveTo(9f, 4.2f)
            lineTo(9f, 17.4f)
        }
        stroked {
            moveTo(15f, 6.6f)
            lineTo(15f, 19.8f)
        }
    }

    /** 本机实测坐标。 */
    val MyLocation: ImageVector = icon("MagnateMyLocation") {
        stroked { circle(12f, 12f, 7f) }
        stroked { circle(12f, 12f, 3.2f) }
        stroked {
            moveTo(12f, 1.4f)
            lineTo(12f, 3.6f)
            moveTo(12f, 20.4f)
            lineTo(12f, 22.6f)
            moveTo(1.4f, 12f)
            lineTo(3.6f, 12f)
            moveTo(20.4f, 12f)
            lineTo(22.6f, 12f)
        }
    }

    /** 无坐标 / 场景未设坐标。 */
    val LocationOff: ImageVector = icon("MagnateLocationOff") {
        stroked {
            moveTo(12f, 21.5f)
            curveTo(12f, 21.5f, 19f, 13.8f, 19f, 9f)
            // 顶部半圆：从 (19,9) 逆时针经 (12,2) 到 (5,9)
            arcToRelative(7f, 7f, 0f, false, false, -14f, 0f)
            curveTo(5f, 13.8f, 12f, 21.5f, 12f, 21.5f)
            close()
        }
        stroked { circle(12f, 9f, 2.4f) }
        stroked {
            moveTo(3.2f, 3.2f)
            lineTo(20.8f, 20.8f)
        }
    }

    /**
     * 搜索无结果。
     *
     * 斜杠走反对角线：手柄在 45° 方向，若斜杠也走 45°，两者会连成一条直线，
     * 看起来像一根长杆而不是「划掉」。
     */
    val SearchOff: ImageVector = icon("MagnateSearchOff") {
        stroked { circle(10f, 10f, 6f) }
        stroked {
            moveTo(14.4f, 14.4f)
            lineTo(20.2f, 20.2f)
        }
        stroked {
            moveTo(4.4f, 15.6f)
            lineTo(15.6f, 4.4f)
        }
    }

    /** 传感器不可用。同心弧 + 中心点 + 斜杠。 */
    val SensorsOff: ImageVector = icon("MagnateSensorsOff") {
        filled { circle(12f, 12f, 2.2f) }
        stroked {
            // 左右各两段弧。四段都是从大角度转向小角度（屏幕坐标下顺时针），
            // 因此 isPositiveArc 一律为 true，弧线朝外鼓出。
            moveTo(9.17f, 14.83f)
            arcTo(4f, 4f, 0f, false, true, 9.17f, 9.17f)
            moveTo(14.83f, 9.17f)
            arcTo(4f, 4f, 0f, false, true, 14.83f, 14.83f)
            moveTo(6.91f, 17.09f)
            arcTo(7.2f, 7.2f, 0f, false, true, 6.91f, 6.91f)
            moveTo(17.09f, 6.91f)
            arcTo(7.2f, 7.2f, 0f, false, true, 17.09f, 17.09f)
        }
        stroked {
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    }

    /**
     * 卫星——卫星信号页的入口与空状态。
     *
     * 星体 + 两片太阳能板 + 天线：这是卫星最无歧义的轮廓。
     * `material-icons-core` 里没有 GNSS/卫星图标（那在 extended 包里），
     * 而 extended 禁用，所以自绘。
     */
    val Satellite: ImageVector = icon("MagnateSatellite") {
        stroked {
            // 星体
            moveTo(10f, 9f)
            lineTo(14f, 9f)
            lineTo(14f, 15f)
            lineTo(10f, 15f)
            close()
            // 左右太阳能板
            moveTo(3f, 10f)
            lineTo(7.2f, 10f)
            lineTo(7.2f, 14f)
            lineTo(3f, 14f)
            close()
            moveTo(16.8f, 10f)
            lineTo(21f, 10f)
            lineTo(21f, 14f)
            lineTo(16.8f, 14f)
            close()
            // 连接杆。画到星体边缘就停，不要穿进去——穿过去会在描边交叠处
            // 积出一块深色，看起来像个多余的结点
            moveTo(7.2f, 12f)
            lineTo(10f, 12f)
            moveTo(14f, 12f)
            lineTo(16.8f, 12f)
            // 天线
            moveTo(12f, 9f)
            lineTo(12f, 5.6f)
        }
        filled { circle(12f, 4.2f, 1.3f) }
    }

    /** 筛选 / 调节。三条滑轨，滑块错开。 */
    val Tune: ImageVector = icon("MagnateTune") {
        stroked {
            moveTo(3f, 7f)
            lineTo(21f, 7f)
            moveTo(3f, 12f)
            lineTo(21f, 12f)
            moveTo(3f, 17f)
            lineTo(21f, 17f)
        }
        filled {
            circle(9f, 7f, 2.1f)
            circle(15f, 12f, 2.1f)
            circle(8f, 17f, 2.1f)
        }
    }
}

private const val VIEWPORT = 24f

/** 与 material-icons-core 的 Outlined 系列保持一致的描边宽度。 */
private const val STROKE_WIDTH = 1.7f

private val strokeBrush = SolidColor(Color.Black)
private val fillBrush = SolidColor(Color.Black)

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply(block).build()

/** 描边路径——图标主体。 */
private fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = strokeBrush,
        strokeLineWidth = STROKE_WIDTH,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { block() }
}

/** 实心路径——指针、圆点、滑块。 */
private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
    path(fill = fillBrush) { block() }
}

/**
 * 整圆。
 *
 * 用两段半圆弧而不是 `Path.addOval`——`PathBuilder` 里没有画椭圆的便捷方法，
 * 而两段 `arcToRelative` 语义明确、不引入额外的 `Path` 对象。
 */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 0f, 2 * r)
    arcToRelative(r, r, 0f, true, true, 0f, -2 * r)
    close()
}
