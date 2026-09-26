package com.magnate.compass.ui.records

import com.magnate.compass.data.RecordWithRelations
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.record
import com.magnate.compass.scene
import com.magnate.compass.sceneWithCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复制为文本」的输出契约。
 *
 * 这个函数在 v1.2 里已经存在，却一直没有测试——而它的输出会**脱离应用独立流传**
 * （粘进聊天、记事本、表格），一旦格式漂了没人会立刻发现。
 * 本次改动恰好动了坐标行，正好把它护住。
 */
class RecordTextFormatTest {

    private fun item(
        record: com.magnate.compass.data.entity.RecordEntity,
        scene: com.magnate.compass.data.entity.SceneEntity? = null,
    ) = RecordWithRelations(record = record, tags = emptyList(), scene = scene)

    // —— 坐标行 ——

    @Test
    fun `实测坐标写明来源与精度`() {
        val text = formatRecordAsText(
            item(record(latitude = 30.111111, longitude = 120.222222, locationAccuracy = 8f))
        )

        assertTrue(text, text.contains("坐标 30.111111°N, 120.222222°E"))
        assertTrue(text, text.contains("本机实测"))
        assertTrue(text, text.contains("±8m"))
    }

    @Test
    fun `场景继承的坐标写明是哪个场景`() {
        val text = formatRecordAsText(
            item(
                record = record(sceneId = 100L),
                scene = sceneWithCoordinate(name = "阳光大厦 B2层"),
            )
        )

        // FIXED 模式下记录自身不存坐标，坐标来自场景——这个区别必须写进文本
        assertTrue(text, text.contains("场景坐标「阳光大厦 B2层」"))
        assertTrue(text, text.contains("坐标 30.123456°N, 120.567890°E"))
    }

    @Test
    fun `没有坐标时明确写「无」而不是留空`() {
        val noScene = formatRecordAsText(item(record()))
        assertTrue(noScene, noScene.contains("坐标 无"))
        assertFalse(noScene, noScene.contains("坐标 无（"))

        val sceneWithout = formatRecordAsText(
            item(record(sceneId = 100L), scene = scene(name = "未设坐标的场景"))
        )
        assertTrue(sceneWithout, sceneWithout.contains("坐标 无（场景「未设坐标的场景」未设置坐标）"))
    }

    // —— 海拔 ——

    @Test
    fun `有海拔时写进坐标行`() {
        val text = formatRecordAsText(
            item(
                record(
                    latitude = 30.111111,
                    longitude = 120.222222,
                    altitude = 42.4,
                    locationAccuracy = 8f,
                )
            )
        )

        assertTrue(text, text.contains("海拔 42m"))
    }

    @Test
    fun `海拔为负时照常写出`() {
        // 吐鲁番盆地在海平面以下，负海拔是正常数据而不是异常值
        val text = formatRecordAsText(
            item(record(latitude = 42.0, longitude = 89.0, altitude = -154.3, locationAccuracy = 8f))
        )

        assertTrue(text, text.contains("海拔 -154m"))
    }

    @Test
    fun `没有海拔时不留空档`() {
        // listOfNotNull 拼接的意义就在这里：缺一项就少一项，
        // 而不是留下 `(海拔 , ±8m, 本机实测)` 这种读不通、也不好被别的工具解析的字符串
        val text = formatRecordAsText(
            item(record(latitude = 30.111111, longitude = 120.222222, locationAccuracy = 8f))
        )
        val line = text.lineSequence().first { it.startsWith("坐标 ") }

        assertFalse(line, line.contains("海拔"))
        assertFalse(line, line.contains(", ,"))
        assertFalse(line, line.contains("(,"))
        assertFalse(line, line.contains(",)"))
        assertEquals("坐标 30.111111°N, 120.222222°E (±8m, 本机实测)", line)
    }

    @Test
    fun `场景坐标也能带上海拔`() {
        val text = formatRecordAsText(
            item(
                record = record(sceneId = 100L),
                scene = sceneWithCoordinate(altitude = 12.0, coordMode = CoordMode.FIXED),
            )
        )

        assertTrue(text, text.contains("海拔 12m"))
    }

    // —— 姿态 ——

    @Test
    fun `姿态写的是带符号的原始俯仰与翻滚`() {
        val text = formatRecordAsText(item(record()))

        // 存的是 getOrientation 的原值：平放就是 0°，不是 -90°。
        // 这里不做任何「离水平多少度」的换算——原始值是可追溯的，
        // 换算过的值一旦口径改变，历史文本就再也解释不了
        assertTrue(text, text.contains("姿态 俯仰 +0.0°  翻滚 +0.0°"))
    }

    // —— 整体 ——

    @Test
    fun `末尾不残留空行`() {
        val text = formatRecordAsText(item(record(note = "测试备注")))

        assertEquals(text.trimEnd(), text)
        assertTrue(text, text.endsWith("备注 测试备注"))
    }

    @Test
    fun `可选段落缺失时整行不出现`() {
        val text = formatRecordAsText(item(record()))

        assertFalse(text, text.contains("标签"))
        assertFalse(text, text.contains("备注"))
        assertFalse(text, text.contains("场景 "))
    }

    @Test
    fun `有场景与标签时逐行列出`() {
        val text = formatRecordAsText(
            item(record(sceneId = 100L, note = "地砖"), scene = sceneWithCoordinate())
        )

        assertTrue(text, text.contains("场景 阳光大厦 B2层"))
        assertTrue(text, text.contains("备注 地砖"))
    }

    @Test
    fun `经纬度按六位小数输出并带半球`() {
        // 南纬西经必须体现为 S/W——若只按数值格式化，
        // 一份来自南半球的记录会被读成完全相反的位置
        val southWest = formatRecordAsText(
            item(record(latitude = -33.868800, longitude = -70.669300, locationAccuracy = 5f))
        )

        assertTrue(southWest, southWest.contains("33.868800°S"))
        assertTrue(southWest, southWest.contains("70.669300°W"))
    }
}
