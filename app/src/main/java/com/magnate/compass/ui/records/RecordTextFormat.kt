package com.magnate.compass.ui.records

import com.magnate.compass.data.EffectiveLocation
import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.location
import com.magnate.compass.location.GeoPoint
import com.magnate.compass.sensor.CompassMath
import com.magnate.compass.util.GeoFormat
import com.magnate.compass.util.TimeFormat

/**
 * 「复制为文本」的格式（design-gui.md §7.5）。
 *
 * 这是一个**零成本的导出替代品**：用户想分享或存档时，复制一段文本就够了，
 * 不必等 CSV 功能。纯文本也是最容易被其他工具消费的格式——
 * 粘进聊天窗口、记事本、表格软件都能用。
 *
 * 纯函数，可 JVM 单测。
 */
fun formatRecordAsText(item: RecordWithRelations): String {
    val record = item.record
    val location = item.location
    val builder = StringBuilder()

    builder.appendLine("磁测记录 ${TimeFormat.formatDateTime(record.timestamp)}")

    item.scene?.let { builder.appendLine("场景 ${it.name}") }

    builder.append("方位角 ${record.azimuthDeg.toInt()}°")
    builder.append("(${CompassMath.headingName(record.azimuthDeg)})")
    if (record.isTrueNorth) builder.append("[真北]")
    builder.appendLine("  磁场 ${GeoFormat.formatMagnitude(record.magnitude)}")

    builder.appendLine(
        "X ${GeoFormat.formatSigned(record.magX)}  " +
            "Y ${GeoFormat.formatSigned(record.magY)}  " +
            "Z ${GeoFormat.formatSigned(record.magZ)} μT"
    )

    builder.appendLine("姿态 俯仰 ${GeoFormat.formatSigned(record.pitchDeg)}°  " +
        "翻滚 ${GeoFormat.formatSigned(record.rollDeg)}°")

    builder.appendLine(location.toTextLine())

    if (item.tags.isNotEmpty()) {
        builder.appendLine("标签 " + item.tags.joinToString(" ") { "#${it.name}" })
    }

    if (!record.note.isNullOrBlank()) {
        builder.appendLine("备注 ${record.note}")
    }

    return builder.toString().trimEnd()
}

/**
 * 坐标行。**来源必须写进文本**——这份文本会脱离应用独立流传，
 * 若只留一串经纬度，「这是实测的还是场景继承的」这个关键区别就永久丢失了。
 */
private fun EffectiveLocation.toTextLine(): String = when (this) {
    is EffectiveLocation.Measured -> coordinateLine(point, "本机实测")
    is EffectiveLocation.FromScene -> coordinateLine(point, "场景坐标「$sceneName」")
    is EffectiveLocation.SceneWithoutCoordinate -> "坐标 无（场景「$sceneName」未设置坐标）"
    EffectiveLocation.None -> "坐标 无"
}

/**
 * 坐标行的公共部分。
 *
 * 括号里的三项各自独立：海拔可能拿不到（设备或定位方式不支持），
 * 来源是文字标签。用 [listOfNotNull] 拼而不是套字符串模板，
 * 缺哪一项都不会在文本里留下 `, ,` 这种空档——
 * 这段文本是给人读、也可能被别的工具解析的，空档比少一项更难解释。
 */
private fun coordinateLine(point: GeoPoint, sourceLabel: String): String {
    val details = listOfNotNull(
        GeoFormat.formatAltitude(point.altitude),
        GeoFormat.formatAccuracy(point.accuracyMeters),
        sourceLabel,
    )
    return "坐标 ${GeoFormat.formatCoordinates(point.latitude, point.longitude)} " +
        "(${details.joinToString(", ")})"
}
