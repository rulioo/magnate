package com.magnate.compass.sensor

/**
 * 校准相关的全部文案。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测——沿用 `util/LocationMessages.kt` 的做法。
 *
 * **文案放在这里而不是写在 `@Composable` 里，是因为它有回归风险。**
 * 最要命的一条是「磁吸配件的提示不能劝用户去画 8 字」：
 * 那正是用户抱怨「指南针经常不准」时最常见的真实原因，而画 8 字消不掉
 * 贴在手机背面的磁铁。给错办法会让用户反复做一件永远不会生效的事，
 * 然后把结论落在「这个应用不准」上。这类断言只能在纯函数上钉。
 */
object CalibrationMessages {

    /**
     * 主页顶部常驻提示条上的那一句话。返回 null 表示**不显示提示条**。
     *
     * 每条都以「· 查看」或「· 点此校准」收尾：提示条整条可点，
     * 但没有动词的话用户不一定会去点它（`MetaRow` 的磁偏角行踩过同一个坑）。
     */
    fun banner(cause: CalibrationCause): String? = when (cause) {
        CalibrationCause.OK -> null
        CalibrationCause.NEEDS_CALIBRATION -> "罗盘精度偏低 · 点此校准"
        CalibrationCause.INTERFERENCE -> "附近有铁磁物或强电流 · 查看"
        CalibrationCause.MAGNETIC_ACCESSORY -> "检测到强磁场，可能装了磁吸壳或车架 · 查看"
        CalibrationCause.NO_SIGNAL -> "没有读到磁力计数据 · 查看"
    }

    /** 校准面板的标题。 */
    fun title(cause: CalibrationCause): String = when (cause) {
        CalibrationCause.OK -> "已校准"
        CalibrationCause.NEEDS_CALIBRATION -> "磁力计需要校准"
        CalibrationCause.INTERFERENCE -> "附近有磁场干扰"
        CalibrationCause.MAGNETIC_ACCESSORY -> "检测到强磁场"
        CalibrationCause.NO_SIGNAL -> "没有磁场读数"
    }

    /**
     * 校准面板的正文：告诉用户**具体该做什么**。
     *
     * 四段互不相同，这是本次改动相对于「一句通用的画 8 字说明」的实质区别。
     */
    fun guidance(cause: CalibrationCause): String = when (cause) {
        CalibrationCause.OK ->
            "精度已达标，可以正常使用。\n\n" +
                "若日后又出现偏差，重新做一次即可。"

        CalibrationCause.NEEDS_CALIBRATION ->
            "手持手机，在空中慢慢地画「8」字，重复 5–10 次。\n\n" +
                "画的时候下方会实时显示精度，回升到「高精度」就可以停。"

        CalibrationCause.INTERFERENCE ->
            "这里测到的磁场强度超出了地磁的正常范围。铁桌腿、钢筋墙、音箱、" +
                "通电的电机都会造成这种情况。\n\n" +
                "换一个位置再测。在原地画 8 字不会有用——问题不在校准，在这里。"

        CalibrationCause.MAGNETIC_ACCESSORY ->
            "磁场强度远超正常地磁，同时硬磁偏置也很大。这通常意味着" +
                "有一块磁铁正贴在手机上：磁吸手机壳、车载磁吸支架、MagSafe 配件。\n\n" +
                "取下配件后再测。校准消不掉贴在背面的磁铁。"

        CalibrationCause.NO_SIGNAL ->
            "暂时读不到磁力计数据。\n\n" +
                "若持续如此，可能是传感器被系统占用或设备异常，重启应用通常可以恢复。"
    }

    /**
     * 方位角来源降级时的补充说明，正常（旋转矢量）与无来源时返回 null。
     *
     * 这解释了**校准治不好的另一回事**：B、C 两级来源本身就比旋转矢量抖。
     * 用户在这些机型上做多少次 8 字校准都不会得到旋转矢量的平滑度，
     * 不如如实说明，免得他把机型差异当成自己的操作问题。
     */
    fun sourceNote(source: SensorSource?): String? = when (source) {
        null, SensorSource.ROTATION_VECTOR -> null
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR ->
            "本机方位角来自地磁旋转矢量（无陀螺仪），读数抖动本来就比旋转矢量略大。"

        SensorSource.ACCEL_MAG ->
            "本机方位角由加速度计与磁力计合成（无旋转矢量传感器），读数抖动本来就偏大。"
    }

    /**
     * 固定说明：**恒定偏差不是校准问题**。
     *
     * 用户说「确保指向是正确的」，而最常见的那种「不对」其实是磁北与真北之差
     * （国内约 −10°~+5°）。关着真北开关去对地图，那个偏差是**正确行为**，
     * 画多少 8 字都不会消——不说清楚，用户会朝错误的方向使劲。
     */
    const val DECLINATION_NOTE: String =
        "另外：如果偏差是恒定几度、换到哪儿都一样，那不是校准问题，" +
            "而是磁北与真北之差（磁偏角）。打开主页的「真北」开关即可换算。"

    /** 校准达标时的确认语。这是原先完全缺失的反馈回路——画完 8 字没有任何回音。 */
    const val CALIBRATED_CONFIRMATION: String = "✓ 已校准"
}
