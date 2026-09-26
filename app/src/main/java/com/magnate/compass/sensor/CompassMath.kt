package com.magnate.compass.sensor

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 罗盘相关的全部纯函数。
 *
 * **本文件不 import 任何 `android.*` 类**——包括 [AxisRemap] 用到的轴常量与屏幕旋转常量，
 * 都在此重新声明。这样 [CompassMath] 可以在 JVM 上直接单测，
 * 无需 Robolectric 或仪器测试（design.md §8「可测性设计」）。
 *
 * 重新声明的常量与 Android 的对应关系（取值一致，仅为避免 import）：
 * - `SensorManager.AXIS_X/Y/Z` = 1/2/3
 * - `SensorManager.AXIS_MINUS_X/Y/Z` = 129/130/131（即 `AXIS_X or 0x80`）
 * - `Surface.ROTATION_0/90/180/270` = 0/1/2/3
 */
object CompassMath {

    // —— android.hardware.SensorManager 轴常量 ——
    const val AXIS_X = 1
    const val AXIS_Y = 2
    const val AXIS_Z = 3
    const val AXIS_MINUS_X = AXIS_X or 0x80
    const val AXIS_MINUS_Y = AXIS_Y or 0x80
    const val AXIS_MINUS_Z = AXIS_Z or 0x80

    // —— android.view.Surface 屏幕旋转常量 ——
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** 方位角低通滤波系数（design.md §5.8d） */
    const val AZIMUTH_ALPHA = 0.15f

    /** 磁场强度低通滤波系数——磁场变化缓慢，取更小的 alpha */
    const val MAGNITUDE_ALPHA = 0.08f

    /** 俯仰/翻滚低通滤波系数。与方位角同量级即可，倾角读数不需要更快的跟随。 */
    const val TILT_ALPHA = 0.15f

    /**
     * 合成倾角超过这个值就提示用户「手机太斜了」。
     *
     * 30° 沿用原实现的意图：原判据写作 `abs(pitchDeg + 90f) > 30f`，
     * 即「离水平面超过 30°」。那个式子本身是反的（见 [tiltFromHorizontal]），
     * 但阈值取 30 是作者的本意，此处只纠正语义、不动这个数。
     *
     * 放在这里而不是 ViewModel：阈值与屏幕上的数字必须出自同一定义，
     * 否则会再次出现「提示说很斜、读数显示很平」这种自相矛盾。
     */
    const val TILT_WARNING_DEGREES = 30f

    /** 合成倾角小于这个值就算「已水平」，气泡变绿。 */
    const val LEVEL_TOLERANCE_DEGREES = 1f

    /** 地磁参考范围下限，μT（赤道附近） */
    const val MAGNETIC_FIELD_MIN_UT = 25f

    /** 地磁参考范围上限，μT（两极附近） */
    const val MAGNETIC_FIELD_MAX_UT = 65f

    /**
     * 磁场进度条的固定量程上限。
     * 不做自适应量程——那会让用户失去「多大算大」的直觉（design.md §6）。
     */
    const val MAGNETIC_BAR_FULL_SCALE_UT = 100f

    private val HEADING_NAMES_16 = arrayOf(
        "北", "北偏东", "东北", "东北偏东",
        "东", "东南偏东", "东南", "东南偏南",
        "南", "西南偏南", "西南", "西南偏西",
        "西", "西北偏西", "西北", "西北偏北",
    )

    /** 把任意角度归一到 `[0, 360)`。 */
    fun normalizeDegrees(degrees: Float): Float {
        val r = degrees % 360f
        return if (r < 0f) r + 360f else r
    }

    /**
     * 在最短弧上对角度做低通滤波。
     *
     * **关键陷阱**：直接对角度值滤波，会在 359° 与 1° 之间跳变时让指针转 358°。
     * 因此先把差值归一化到 `[-180, 180)`，再滤波（design.md §5.8d）。
     */
    fun smoothAngle(prev: Float, next: Float, alpha: Float): Float {
        val p = normalizeDegrees(prev)
        val n = normalizeDegrees(next)
        // p、n 均已归一化，n - p 落在 (-360, 360)，+540 后必为正，取模结果落在 [0, 360)
        val delta = ((n - p + 540f) % 360f) - 180f
        return normalizeDegrees(p + alpha * delta)
    }

    /**
     * 把 [target] 平移到离 [reference] 最近的那个等价角（相差 360° 的整数倍）。
     *
     * 表盘旋转动画需要**连续角度**：方位角从 359° 变到 1° 时，表盘只该转 2°，
     * 而不是反向扫过 358°。归一化后的角度丢掉了「转了几圈」的信息，
     * 因此动画前必须先把目标角展开到参考角附近（design-gui.md §13）。
     */
    fun unwrapNear(reference: Float, target: Float): Float {
        var t = target
        while (t - reference > 180f) t -= 360f
        while (t - reference < -180f) t += 360f
        return t
    }

    /** 标量低通滤波，用于磁场强度。 */
    fun smoothScalar(prev: Float, next: Float, alpha: Float): Float =
        prev + alpha * (next - prev)

    /** 磁场总强度，μT（design.md §5.8c）。 */
    fun magneticMagnitude(x: Float, y: Float, z: Float): Float =
        sqrt(x * x + y * y + z * z)

    /** 读数是否落在地磁正常范围内。 */
    fun isMagneticFieldNormal(magnitudeUt: Float): Boolean =
        magnitudeUt in MAGNETIC_FIELD_MIN_UT..MAGNETIC_FIELD_MAX_UT

    /**
     * 十六方位中文名，每 22.5° 一档。
     *
     * 命名沿用 `design-gui.md` 的写法（247° → 「西南偏西」，即 WSW），
     * 而非 GB/T 的「西南西」转写。此处用查表而非推导——22.5° 的整数倍
     * 在每个方位边界上都是等距的，任何「就近取整」的推导都要处理平局，
     * 查表更直白也无歧义。
     */
    fun headingName(azimuthDeg: Float): String {
        val index = (normalizeDegrees(azimuthDeg) / 22.5f).roundToInt() % 16
        return HEADING_NAMES_16[index]
    }

    /** `getOrientation` 输出的弧度转角度制方位角。 */
    fun azimuthDegreesFrom(azimuthRad: Float): Float =
        normalizeDegrees(Math.toDegrees(azimuthRad.toDouble()).toFloat())

    fun pitchDegreesFrom(pitchRad: Float): Float =
        Math.toDegrees(pitchRad.toDouble()).toFloat()

    fun rollDegreesFrom(rollRad: Float): Float =
        Math.toDegrees(rollRad.toDouble()).toFloat()

    /**
     * 把带符号角度归一到 `(-180, 180]`。
     *
     * 与 [normalizeDegrees]（`[0, 360)`）的区别就是**保号**。俯仰与翻滚都是有符号量，
     * 一旦被归到 `[0, 360)`，一个平放手机的 -0.8° 会变成 359.2°，
     * 既与 `formatSigned` 的输出对不上，也让历史数据与新数据落在两个量程里。
     */
    fun normalizeSignedDegrees(degrees: Float): Float {
        val r = normalizeDegrees(degrees)
        return if (r > 180f) r - 360f else r
    }

    /**
     * 屏幕平面与水平面的夹角，`[0, 90]`。**平放时为 0，无论屏幕朝上还是朝下。**
     *
     * ## 为什么不能只看 pitch
     *
     * `SensorManager.getOrientation` 的三个角是欧拉角，单看一个轴并不等于「有多斜」：
     * 俯仰 45° 同时翻滚 45°，实际倾角是 60° 而不是 90°。直接相加是这里最常见的错误。
     * 恒等式：`R[8] = cos(pitch) · cos(roll)`（R 是 `getRotationMatrix` 的输出，
     * 行 2 为世界 Up 行），而 `R[8] = Up · deviceZ` 正是屏幕法线与铅垂线的夹角余弦。
     *
     * ## 为什么最后要取绝对值
     *
     * 上一步算出来的是**法线**与铅垂线的夹角 θ ∈ [0, 180]，而我们要的是**平面**的倾角。
     * 两者的关系是 `倾角 = min(θ, 180° - θ)`：屏幕朝下平放时法线指向地心，θ = 180°，
     * 但那个平面明明就是水平的。取绝对值再 acos 正好完成这个折叠
     * （`|cos θ| = cos(min(θ, 180°-θ))`），比写 min 少一次分支。
     *
     * 漏掉这一步的表现很具体：手机朝下扣在桌上时，读数会显示 180° 而不是 0°。
     *
     * ## pitch 的零点在哪——这一版唯一需要小心的约定
     *
     * AOSP 的 `getOrientation` 是 `values[1] = asin(-R[7])`，而 `R[7] = Up · deviceY`。
     * 屏幕朝上平放时 deviceY 水平，`R[7] = 0`，**所以平放 pitch = 0**；
     * 竖直握持（屏幕面向自己、机头朝上）才是 -90°。
     * 因此**不需要**给 pitch 加 90°——早先 `CompassViewModel` 里
     * `abs(pitchDeg + 90f) > 30f` 的写法正是把它当成了「平放 = -90」，
     * 结果平放时反而报警、竖持时反而安静，与文案完全相反。
     */
    fun tiltFromHorizontal(pitchDeg: Float, rollDeg: Float): Float {
        val cosine = cos(Math.toRadians(pitchDeg.toDouble())) *
            cos(Math.toRadians(rollDeg.toDouble()))
        // 先取绝对值把 [0°, 180°] 折到 [0°, 90°]，再夹一下——浮点误差可能让余弦略超 1，
        // 而 acos(1.0000001) 是 NaN，NaN 的所有比较都是 false，
        // 会让 tiltWarning 静默地永远不亮
        return Math.toDegrees(acos(abs(cosine).coerceIn(0.0, 1.0))).toFloat()
    }

    /** 气泡在画布上的偏移，模长 = `sin(倾角)`，方向指向**抬起的一侧**。 */
    data class BubbleOffset(val x: Float, val y: Float)

    /**
     * 水平仪气泡的偏移量，取值 `[-1, 1]`，调用方按半径缩放即可。
     *
     * 推导：气泡浮向「上」在屏幕平面内的投影，即 `(Up·deviceX, Up·deviceY)`。
     * 由 `R[6] = Up·deviceX`、`R[7] = Up·deviceY = -sin(pitch)`，以及
     * `roll = atan2(-R[6], R[8])` 可得 `Up·deviceX = -cos(pitch)·sin(roll)`。
     *
     * 画布的 y 轴向下、而 deviceY 指向机头（屏幕上方），因此 y 分量要取反。
     *
     * 例：机头抬起 10° → `y` 为负（画布上方）✓；右边抬起 10° → `x` 为正（画布右方）✓。
     *
     * **符号约定依赖 `getOrientation` 的手性，是本次改动里唯一应当真机肉眼复核的地方。**
     */
    fun bubbleOffset(pitchDeg: Float, rollDeg: Float): BubbleOffset {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        return BubbleOffset(
            x = (-cos(p) * sin(r)).toFloat(),
            y = sin(p).toFloat(),
        )
    }

    /** 是否已水平。看的是**合成**倾角——只看俯仰会把「俯仰 0.8° 且翻滚 0.8°」误判为水平。 */
    fun isLevel(pitchDeg: Float, rollDeg: Float): Boolean =
        tiltFromHorizontal(pitchDeg, rollDeg) <= LEVEL_TOLERANCE_DEGREES

    /** 磁北 → 真北（design.md §5.8e）。`declination` 东偏为正。 */
    fun trueAzimuth(magneticAzimuthDeg: Float, declinationDeg: Float): Float =
        normalizeDegrees(magneticAzimuthDeg + declinationDeg)

    /**
     * 屏幕旋转 → 坐标系重映射所需的轴对。
     *
     * **横屏必须重映射**：用户平举手机采样时很多机型会转到横屏，
     * 不重映射会让方位角整体偏移 90°，导致所有记录数据错误（design.md §5.8a）。
     */
    fun axisRemapFor(rotation: Int): AxisRemap = when (rotation) {
        ROTATION_90 -> AxisRemap(AXIS_Y, AXIS_MINUS_X)
        ROTATION_180 -> AxisRemap(AXIS_MINUS_X, AXIS_MINUS_Y)
        ROTATION_270 -> AxisRemap(AXIS_MINUS_Y, AXIS_X)
        else -> AxisRemap(AXIS_X, AXIS_Y)
    }

    /** `SensorManager.remapCoordinateSystem` 的 X/Y 轴参数。 */
    data class AxisRemap(val x: Int, val y: Int)
}
