package com.magnate.compass.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准成因的判断契约。
 *
 * **钉住的是边界语义，不是阈值的数值。** `BIAS_RATIO_WARN` 是一个启发式常量，
 * 需要在真机上按观察调整；若测试写死「0.5 就是分界」，那么每次调阈值都要改测试，
 * 测试就从「保护行为」退化成了「复述实现」。因此边界用例一律引用常量本身，
 * 断言的是**闭区间、缺失值落到哪一档、迟滞往哪个方向**这些不该变的东西——
 * 与 `GnssFormatTest.cn0Tier` 的写法一致。
 */
class CalibrationMathTest {

    /** 地磁正常范围的中值附近，怎么判都落在「正常」里。 */
    private val normalStrength = 50f

    private fun biasOf(magnitude: Float): MagneticBias =
        MagneticBias(x = magnitude, y = 0f, z = 0f)

    private fun assess(
        bias: MagneticBias? = null,
        magnitudeUt: Float = normalStrength,
        accuracy: AccuracyLevel = AccuracyLevel.HIGH,
        previousCause: CalibrationCause? = null,
    ) = CalibrationMath.assessCalibration(
        bias = bias,
        magnitudeUt = magnitudeUt,
        accuracy = accuracy,
        previousCause = previousCause,
    )

    // —— 正常路径 ——

    @Test
    fun `强度正常且偏置很小时判为无需处理`() {
        val quality = assess(bias = biasOf(5f))

        assertEquals(CalibrationCause.OK, quality.cause)
        assertEquals(0.1f, quality.biasRatio!!, 1e-4f)
    }

    @Test
    fun `没有未校准磁力计时偏置占比为 null 而不是零`() {
        // 0 的意思是「偏置很小」，「测不了」与「很好」恰好相反。
        // 界面上这两者必须长得不一样，否则读到这一项的用户会被反向误导
        val quality = assess(bias = null)

        assertEquals(CalibrationCause.OK, quality.cause)
        assertNull(quality.biasRatio)
        assertNotNull(quality.fieldStrengthUt)
    }

    // —— 偏置 ——

    @Test
    fun `偏置占比达到阈值即判为需要校准`() {
        // 闭区间：恰好等于阈值属于「报警」那一侧
        val quality = assess(bias = biasOf(normalStrength * CalibrationMath.BIAS_RATIO_WARN))

        assertEquals(CalibrationCause.NEEDS_CALIBRATION, quality.cause)
    }

    @Test
    fun `偏置占比略低于阈值时不报警`() {
        val quality = assess(bias = biasOf(normalStrength * CalibrationMath.BIAS_RATIO_WARN - 0.01f))

        assertEquals(CalibrationCause.OK, quality.cause)
    }

    @Test
    fun `偏置看不出问题但设备自报精度低时仍然要求校准`() {
        // 这一条是 v1.3 及以前唯一的判据。删掉它会让老设备（没有未校准磁力计、
        // 但精度读数有效）彻底失去校准提示
        val quality = assess(bias = null, accuracy = AccuracyLevel.UNRELIABLE)

        assertEquals(CalibrationCause.NEEDS_CALIBRATION, quality.cause)
    }

    // —— 环境与配件 ——

    @Test
    fun `强度超出地磁范围而偏置很小时判为环境干扰`() {
        val quality = assess(bias = biasOf(5f), magnitudeUt = 120f)

        assertEquals(CalibrationCause.INTERFERENCE, quality.cause)
    }

    @Test
    fun `强度异常且偏置同时很大时判为磁吸配件`() {
        // 与 INTERFERENCE 必须分开：前者让用户「换个位置」，后者让用户「取下手机壳」。
        // 合成一档就等于把其中一半的用户指去做一件永远没用的事
        val quality = assess(bias = biasOf(80f), magnitudeUt = 120f)

        assertEquals(CalibrationCause.MAGNETIC_ACCESSORY, quality.cause)
    }

    @Test
    fun `强度低于地磁下限同样判为干扰`() {
        // 铁磁屏蔽（比如电梯轿厢）会把场压得比地磁还低，
        // 只判上界的话这种最常见的干扰反而漏掉
        val quality = assess(bias = biasOf(1f), magnitudeUt = 10f)

        assertEquals(CalibrationCause.INTERFERENCE, quality.cause)
    }

    // —— 缺失必须表现为最差 ——

    @Test
    fun `强度为 NaN 时判为没有信号而不是无需处理`() {
        val quality = assess(magnitudeUt = Float.NaN)

        assertEquals(CalibrationCause.NO_SIGNAL, quality.cause)
        assertNull(quality.fieldStrengthUt)
    }

    @Test
    fun `强度为零时判为没有信号`() {
        // 0 不是「磁场极弱」而是「没读到」——地磁场最弱处也有 25μT。
        // 把它当成正常值，主页就会在传感器失效时亮着绿灯
        val quality = assess(magnitudeUt = 0f)

        assertEquals(CalibrationCause.NO_SIGNAL, quality.cause)
    }

    @Test
    fun `偏置为 NaN 时占比为 null 而不是判成最好或最差`() {
        val quality = assess(bias = biasOf(Float.NaN))

        // 偏置不可用只是少一项判据，强度仍然够用，因此结论落在 OK 而不是 NO_SIGNAL
        assertNull(quality.biasRatio)
        assertEquals(CalibrationCause.OK, quality.cause)
    }

    @Test
    fun `没有信号时偏置占比不参与计算`() {
        val quality = assess(bias = biasOf(80f), magnitudeUt = Float.NaN)

        assertEquals(CalibrationCause.NO_SIGNAL, quality.cause)
        assertNull(quality.biasRatio)
    }

    // —— 边界 ——

    @Test
    fun `磁场强度恰为上界时算正常`() {
        val quality = assess(bias = biasOf(1f), magnitudeUt = CompassMath.MAGNETIC_FIELD_MAX_UT)

        assertEquals(CalibrationCause.OK, quality.cause)
    }

    @Test
    fun `磁场强度恰为下界时算正常`() {
        val quality = assess(bias = biasOf(1f), magnitudeUt = CompassMath.MAGNETIC_FIELD_MIN_UT)

        assertEquals(CalibrationCause.OK, quality.cause)
    }

    @Test
    fun `磁场强度刚超出上界时判为干扰`() {
        val quality = assess(
            bias = biasOf(1f),
            magnitudeUt = CompassMath.MAGNETIC_FIELD_MAX_UT + 0.1f,
        )

        assertEquals(CalibrationCause.INTERFERENCE, quality.cause)
    }

    // —— 迟滞 ——

    @Test
    fun `已在抱怨强度时必须回到更窄的区间才算好`() {
        // 64.5μT 在正常范围内（≤65），但只差一点点。
        // 若就此放行，设备在 64.9 与 65.1 之间飘时提示条会一闪一闪
        val stillBad = assess(
            bias = biasOf(1f),
            magnitudeUt = CompassMath.MAGNETIC_FIELD_MAX_UT - 0.5f,
            previousCause = CalibrationCause.INTERFERENCE,
        )
        assertEquals(CalibrationCause.INTERFERENCE, stillBad.cause)

        // 回落得足够深才放行
        val cleared = assess(
            bias = biasOf(1f),
            magnitudeUt = CompassMath.MAGNETIC_FIELD_MAX_UT - CalibrationMath.STRENGTH_CLEAR_MARGIN_UT,
            previousCause = CalibrationCause.INTERFERENCE,
        )
        assertEquals(CalibrationCause.OK, cleared.cause)
    }

    @Test
    fun `已在抱怨偏置时必须降得更低才清除`() {
        val ratio = CalibrationMath.BIAS_RATIO_WARN - 0.01f   // 0.49，未收紧时算 OK

        val stillBad = assess(
            bias = biasOf(normalStrength * ratio),
            previousCause = CalibrationCause.NEEDS_CALIBRATION,
        )
        assertEquals(CalibrationCause.NEEDS_CALIBRATION, stillBad.cause)

        val cleared = assess(
            bias = biasOf(normalStrength * (CalibrationMath.BIAS_RATIO_CLEAR - 0.01f)),
            previousCause = CalibrationCause.NEEDS_CALIBRATION,
        )
        assertEquals(CalibrationCause.OK, cleared.cause)
    }

    @Test
    fun `偏置引起的报警不会被迟滞误升级成环境干扰`() {
        // 迟滞若同时收紧两条判据，一个纯粹的偏置问题会在强度 26μT（正常，
        // 只是低于收紧后的下沿）时被改判成「环境干扰」，从此再也退不出来——
        // 用户会被指去「换个位置」，而换到哪儿都一样
        val quality = assess(
            bias = biasOf(normalStrength * 0.9f),
            magnitudeUt = CompassMath.MAGNETIC_FIELD_MIN_UT + 1f,
            previousCause = CalibrationCause.NEEDS_CALIBRATION,
        )

        assertEquals(CalibrationCause.NEEDS_CALIBRATION, quality.cause)
    }

    @Test
    fun `首次评估不带迟滞`() {
        // previousCause 为 null 表示还没有历史，此时应当用正常的、更宽的区间判断，
        // 否则应用刚启动就要用户在两个阈值之间猜
        val quality = assess(
            bias = biasOf(normalStrength * (CalibrationMath.BIAS_RATIO_WARN - 0.01f)),
            previousCause = null,
        )

        assertEquals(CalibrationCause.OK, quality.cause)
    }

    @Test
    fun `无需处理时不带需要用户处理的标记`() {
        assertTrue(assess(bias = biasOf(1f)).needsAttention.not())
        assertTrue(assess(bias = biasOf(normalStrength)).needsAttention)
    }

    @Test
    fun `偏置向量的模长按三分量合成`() {
        // 只看单轴会漏掉「三轴各有一点」的偏置，而那正是软磁环境下的常见形状
        val bias = MagneticBias(x = 3f, y = 4f, z = 12f)

        assertEquals(13f, bias.magnitude, 1e-4f)
    }
}
