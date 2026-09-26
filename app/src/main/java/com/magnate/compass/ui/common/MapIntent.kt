package com.magnate.compass.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `geo:` 意图交给系统地图应用。
 *
 * 用 `q` 参数带上名称，地图会显示一个标注点而不是一根落针——
 * 对「这个读数是在哪面墙测的」这类用途，标注比落针有用。
 *
 * 设备上没有地图应用时抛 [ActivityNotFoundException]。这里**静默忽略**：
 * 用户点「在地图中打开」却没装地图，弹一个「未找到应用」的框帮不上任何忙，
 * 而这条记录本身完好无损。真要装，用户自己知道去装。
 */
fun openInMap(context: Context, latitude: Double, longitude: Double, label: String? = null) {
    val query = buildString {
        append("geo:").append(latitude).append(',').append(longitude)
        append("?q=").append(latitude).append(',').append(longitude)
        if (!label.isNullOrBlank()) append("(").append(Uri.encode(label)).append(")")
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(query)))
    } catch (_: ActivityNotFoundException) {
        // 设备上没有地图应用
    }
}
