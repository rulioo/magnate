package com.magnate.compass.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准文案的契约。
 *
 * 这些断言看着像在测字符串，实际上测的是**行为**：
 * 用户抱怨「指南针经常不准」，而最常见的原因（磁吸壳 / 车架）画 8 字是治不好的。
 * 若那条提示把用户指去画 8 字，他会认真做上十几遍、发现毫无变化，
 * 然后把结论落在「这个应用不准」上——而真相是他手里那块磁铁从来没被提起过。
 * 这条回归只能在文案这一层钉住，因为它是纯文本。
 */
class CalibrationMessagesTest {

    private val allCauses = CalibrationCause.entries

    /** 会走到用户眼前的四种成因；OK 是「没有提示」。 */
    private val complaining = allCauses - CalibrationCause.OK

    @Test
    fun `无需处理时不显示提示条`() {
        assertNull(CalibrationMessages.banner(CalibrationCause.OK))
    }

    @Test
    fun `每一种需要处理的成因都有一句提示条`() {
        complaining.forEach { cause ->
            val banner = CalibrationMessages.banner(cause)
            assertNotNull("成因 $cause 没有提示条，用户就永远看不到它", banner)
            assertTrue("成因 $cause 的提示条是空的", banner!!.isNotBlank())
        }
    }

    @Test
    fun `四种成因的提示条互不相同`() {
        // 文案相同意味着用户无法从界面上分辨自己遇到的是哪一种，
        // 而分辨恰恰是这个功能存在的全部意义
        val banners = complaining.map { CalibrationMessages.banner(it) }

        assertEquals(banners.size, banners.toSet().size)
    }

    @Test
    fun `磁吸配件的提示不得建议用户去画 8 字`() {
        // 本次的核心回归。磁铁贴在手机背面时，硬磁偏置会一直很大，
        // 画多少遍 8 字都不会变小——给错办法比不给办法更伤人
        val text = CalibrationMessages.guidance(CalibrationCause.MAGNETIC_ACCESSORY) +
            CalibrationMessages.title(CalibrationCause.MAGNETIC_ACCESSORY) +
            CalibrationMessages.banner(CalibrationCause.MAGNETIC_ACCESSORY)

        assertTrue("磁吸配件的文案里不该出现「8 字」：$text", !text.contains("8 字"))
        assertTrue("磁吸配件的文案必须告诉用户取下配件：$text", text.contains("取下配件"))
    }

    @Test
    fun `环境干扰的提示建议换位置而不是画 8 字`() {
        val text = CalibrationMessages.guidance(CalibrationCause.INTERFERENCE)

        assertTrue("应建议换位置：$text", text.contains("换一个位置"))
        assertTrue("还应说明在原地画 8 字没有用：$text", text.contains("画 8 字不会有用"))
    }

    @Test
    fun `需要校准的指引必须给出画 8 字这个动作`() {
        // 与上面两条正好相反：这一种**只有**画 8 字能解决。
        // 三档文案如果都写成一句泛泛的「请校准」，用户就失去了分辨能力
        val text = CalibrationMessages.guidance(CalibrationCause.NEEDS_CALIBRATION)

        assertTrue("缺少画 8 字的指引：$text", text.contains("画「8」字"))
    }

    @Test
    fun `四种成因的正文互不相同`() {
        val texts = allCauses.map { CalibrationMessages.guidance(it) }

        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun `四种成因的标题互不相同`() {
        val titles = allCauses.map { CalibrationMessages.title(it) }

        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `每一条提示都自带动词`() {
        // 提示条整条可点，但没有任何视觉上的可点击线索。
        // 文案里不带动词时，用户只会读它，不会点它——
        // 主页的磁偏角行踩过同一个坑，那次是靠补一个「›」才解决的
        complaining.forEach { cause ->
            val banner = CalibrationMessages.banner(cause)!!
            assertTrue("提示条「$banner」没有告诉用户要做什么", banner.contains("· "))
        }
    }

    // —— 来源降级 ——

    @Test
    fun `旋转矢量与未知来源都不需要补充说明`() {
        assertNull(CalibrationMessages.sourceNote(SensorSource.ROTATION_VECTOR))
        assertNull(CalibrationMessages.sourceNote(null))
    }

    @Test
    fun `降级来源各有说明且互不相同`() {
        // B、C 两级本来就比旋转矢量抖，这是**校准治不好的另一回事**。
        // 不说明白，这些机型的用户会把机型差异当成自己没校准好
        val geomagnetic = CalibrationMessages.sourceNote(SensorSource.GEOMAGNETIC_ROTATION_VECTOR)
        val accelMag = CalibrationMessages.sourceNote(SensorSource.ACCEL_MAG)

        assertNotNull(geomagnetic)
        assertNotNull(accelMag)
        assertTrue(geomagnetic != accelMag)
    }

    @Test
    fun `磁偏角说明必须点明那不是校准问题`() {
        // 用户说的「指向不对」里，恒定几度的那一种占了很大比例，
        // 而它靠画 8 字永远消不掉——那是磁北与真北之差
        val note = CalibrationMessages.DECLINATION_NOTE

        assertTrue(note.contains("不是校准问题"))
        assertTrue(note.contains("真北"))
    }
}
