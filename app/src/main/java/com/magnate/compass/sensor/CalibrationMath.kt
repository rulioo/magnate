package com.magnate.compass.sensor

/**
 * 罗盘「为什么不准」的判断。
 *
 * **本文件不 import 任何 `android.*` 类**，与 [CompassMath]、[SensorModels] 一致——
 * 这是本项目的既有约定，也是这些判断能在 JVM 上被单测覆盖的前提（design.md §8）。
 *
 * ## 为什么要分成因，而不是只说「请校准」
 *
 * 用户看到的都是「指向不对」，但能解决它的事情有三件完全不同的：
 * 画 8 字、换个位置、把手机壳取下来。原先只有一句「请画 8 字」，
 * 于是**最常见的那一种**（磁吸壳 / 车架）用户照着做多少遍都不会好，
 * 而他会以为是应用坏了。
 *
 * ## 两个指标必须分开用
 *
 * | 指标 | 取自 | 回答的问题 |
 * | --- | --- | --- |
 * | 磁场强度是否落在 25–65 μT | **已校准**磁力计 | 附近有没有铁磁物 / 强电流 |
 * | 硬磁偏置占比 | **未校准**磁力计的 `values[3..5]` | 校准本身够不够 |
 *
 * 拿反了就会自相矛盾：偏置已经被系统从校准值里减掉了，所以判「环境里有没有铁」
 * 只能看校准后的强度；而判「校准做得好不好」只能看被减掉的那部分有多大。
 */
object CalibrationMath {

    /**
     * 硬磁偏置占磁场强度的比例超过它，就认为校准不足。
     *
     * **这是一个启发式阈值，AOSP 没有公开任何对应数值。**
     * 它需要在真机上按观察调整，因此测试钉住的是**边界语义**
     * （闭区间、缺失值落到哪一档、迟滞的方向），不是这个数的神圣性——
     * 与 `GnssFormat.cn0Tier` 的测试写法一致。
     */
    const val BIAS_RATIO_WARN = 0.5f

    /**
     * 已经在抱怨偏置时的**清除**阈值，比 [BIAS_RATIO_WARN] 低一档。
     *
     * 迟滞的下沿。没有它，偏置在 0.5 上下漂时提示条会一闪一闪。
     */
    const val BIAS_RATIO_CLEAR = 0.4f

    /**
     * 已经在抱怨磁场强度时，要回到离上下界各这么远才算好（μT）。
     *
     * 与 [BIAS_RATIO_CLEAR] 同理，但作用在强度上：
     * 一台在 64.8 μT 与 65.2 μT 之间飘的设备不该让提示条反复开关。
     */
    const val STRENGTH_CLEAR_MARGIN_UT = 3f

    /**
     * @param bias 未校准磁力计的硬磁偏置估计；设备没有该传感器时传 null。
     * @param magnitudeUt **已校准**磁场的总强度（[CompassMath.magneticMagnitude]）。
     * @param accuracy 已校准磁力计报出的精度。
     * @param previousCause 上一帧的结论，用于迟滞；首次评估传 null。
     *   **迟滞只在离开某条成因时生效**，因此这个参数不会让「已经很糟」变得更糟。
     */
    fun assessCalibration(
        bias: MagneticBias?,
        magnitudeUt: Float,
        accuracy: AccuracyLevel,
        previousCause: CalibrationCause? = null,
    ): CalibrationQuality {
        // 缺失必须表现为**最差**，不能表现为「最好」——NaN 与 0 都当作没有读数。
        // 0 不是「磁场极弱」而是「没读到」：地磁场最弱的地方也有 25μT。
        // 这条教训来自 GnssFormat.cn0Tier：缺失被当成满分，屏幕上就会显示一片虚假的绿色。
        val strength = magnitudeUt.takeIf { it.isFinite() && it > 0f }
            ?: return CalibrationQuality(cause = CalibrationCause.NO_SIGNAL)

        val biasRatio = bias?.magnitude
            ?.takeIf { it.isFinite() }
            ?.let { it / strength }
            ?.takeIf { it.isFinite() }

        // —— 迟滞 ——
        // 余量**只作用在正在抱怨的那一条上**。若不分青红皂白地收紧两条，
        // 一个纯粹由偏置引起的问题会在强度 26μT（正常，只是低于收紧后的下沿）时
        // 被改判成「环境干扰」，从此再也退不出来。
        val strengthComplaining = previousCause == CalibrationCause.INTERFERENCE ||
            previousCause == CalibrationCause.MAGNETIC_ACCESSORY
        val strengthNormal = if (strengthComplaining) {
            strength >= CompassMath.MAGNETIC_FIELD_MIN_UT + STRENGTH_CLEAR_MARGIN_UT &&
                strength <= CompassMath.MAGNETIC_FIELD_MAX_UT - STRENGTH_CLEAR_MARGIN_UT
        } else {
            CompassMath.isMagneticFieldNormal(strength)
        }

        val biasComplaining = previousCause == CalibrationCause.NEEDS_CALIBRATION ||
            previousCause == CalibrationCause.MAGNETIC_ACCESSORY
        val biasHigh = biasRatio != null &&
            biasRatio >= if (biasComplaining) BIAS_RATIO_CLEAR else BIAS_RATIO_WARN

        val cause = when {
            // 强度异常**且**偏置同时很大 → 磁铁贴在手机上。它与「环境干扰」的区别是
            // 那块磁铁会跟着手机一起转，所以系统把它算进了硬磁偏置里。
            // 这条分支直接决定文案说的是「取下配件」还是「换个位置」，两者不能混。
            !strengthNormal && biasHigh -> CalibrationCause.MAGNETIC_ACCESSORY

            !strengthNormal -> CalibrationCause.INTERFERENCE

            biasHigh -> CalibrationCause.NEEDS_CALIBRATION

            // 偏置看不出问题，但设备自己说精度低——仍然要引导校准。
            // 这条是 v1.3 及以前唯一的判据，保留它才不会让老设备失去提示。
            accuracy.needsCalibration -> CalibrationCause.NEEDS_CALIBRATION

            else -> CalibrationCause.OK
        }

        return CalibrationQuality(
            biasRatio = biasRatio,
            fieldStrengthUt = strength,
            cause = cause,
        )
    }
}

/**
 * 罗盘当前为什么不准。
 *
 * 分成四类而不是一个布尔值，因为**每一类对应的动作不同**，
 * 而给错动作比不给更糟：用户会照着做、发现没用、然后认为应用坏了。
 */
enum class CalibrationCause {
    /** 无需处理。 */
    OK,

    /** 硬磁偏置偏大，或设备自报精度低 → 画 8 字。 */
    NEEDS_CALIBRATION,

    /** 磁场强度超出地磁范围 → 附近有铁磁物或强电流 → 换位置。 */
    INTERFERENCE,

    /** 强度异常**且**偏置很大 → 磁吸壳 / 车架 / 磁吸配件 → 取下配件。 */
    MAGNETIC_ACCESSORY,

    /** 读不到磁场。既不是校准问题也不是环境问题，无从下手。 */
    NO_SIGNAL,
}

/**
 * 一次评估的结果。
 *
 * @param biasRatio `|硬磁偏置| / |磁场|`；设备没有未校准磁力计时为 null，
 *   此时界面**不显示**这一项，而不是显示 0——0 的意思是「偏置很小」，与「测不了」正好相反。
 * @param fieldStrengthUt 已校准的磁场总强度；没有读数时为 null。
 */
data class CalibrationQuality(
    val biasRatio: Float? = null,
    val fieldStrengthUt: Float? = null,
    val cause: CalibrationCause = CalibrationCause.OK,
) {
    /** 是否该在主页顶部挂一条提示。 */
    val needsAttention: Boolean
        get() = cause != CalibrationCause.OK
}
