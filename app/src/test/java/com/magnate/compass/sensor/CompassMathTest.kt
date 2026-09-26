package com.magnate.compass.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassMathTest {

    private val eps = 0.0001f

    // —— 归一化 ——

    @Test
    fun `normalizeDegrees 把任意角度收进 0 到 360`() {
        assertEquals(0f, CompassMath.normalizeDegrees(0f), eps)
        assertEquals(0f, CompassMath.normalizeDegrees(360f), eps)
        assertEquals(0f, CompassMath.normalizeDegrees(720f), eps)
        assertEquals(90f, CompassMath.normalizeDegrees(450f), eps)
        assertEquals(359f, CompassMath.normalizeDegrees(-1f), eps)
        assertEquals(350f, CompassMath.normalizeDegrees(-370f), eps)
    }

    @Test
    fun `normalizeDegrees 对 360 的整数倍一律归零而不是留在 360`() {
        // 若实现写成 `if (r < 0) r + 360` 而忘了取模，360 会原样返回，
        // 后续 headingName 的整除取下标就会越界
        assertEquals(0f, CompassMath.normalizeDegrees(-360f), eps)
        assertEquals(0f, CompassMath.normalizeDegrees(-720f), eps)
    }

    // —— 最短弧滤波：这是方位角平滑的核心陷阱 ——

    @Test
    fun `smoothAngle 在 359 与 1 之间走最短弧而不是反向扫过 358 度`() {
        // 359 → 1 的真实变化只有 +2°。若直接对角度值插值，会认为要 -358°。
        // 取 alpha = 0.5，正确结果应落在 0°（即 359 + 1），而不是 180° 附近。
        val result = CompassMath.smoothAngle(prev = 359f, next = 1f, alpha = 0.5f)
        assertEquals(0f, result, eps)
    }

    @Test
    fun `smoothAngle 在 1 与 359 之间同样走最短弧`() {
        val result = CompassMath.smoothAngle(prev = 1f, next = 359f, alpha = 0.5f)
        assertEquals(0f, result, eps)
    }

    @Test
    fun `smoothAngle 取 alpha 为 1 时直接到达目标`() {
        assertEquals(90f, CompassMath.smoothAngle(0f, 90f, 1f), eps)
        // 359 → 1 走最短弧是 +2°，alpha=1 得 361°，归一化后是 1°（不是 0°）
        assertEquals(1f, CompassMath.smoothAngle(359f, 1f, 1f), eps)
    }

    @Test
    fun `smoothAngle 取 alpha 为 0 时原地不动`() {
        assertEquals(123f, CompassMath.smoothAngle(123f, 300f, 0f), eps)
    }

    @Test
    fun `smoothAngle 结果始终落在 0 到 360`() {
        var value = 0f
        // 连续正向旋转，确认不会因为浮点累积漂出值域
        repeat(1000) { i ->
            value = CompassMath.smoothAngle(value, (i * 7).toFloat(), 0.3f)
            assertTrue("第 $i 次迭代结果越界: $value", value >= 0f && value < 360f)
        }
    }

    // —— unwrapNear：表盘动画需要连续角度 ——

    @Test
    fun `unwrapNear 把目标角展开到参考角附近`() {
        // 359 → 1 应当解释为 361（继续正向转 2°），而不是 1（反向扫 358°）
        assertEquals(361f, CompassMath.unwrapNear(reference = 359f, target = 1f), eps)
        assertEquals(-1f, CompassMath.unwrapNear(reference = 1f, target = 359f), eps)
        assertEquals(90f, CompassMath.unwrapNear(reference = 0f, target = 90f), eps)
        // 转了两整圈的目标角同样被拉回参考角附近，而不是原样返回 810
        assertEquals(90f, CompassMath.unwrapNear(reference = 0f, target = 90f + 360f * 2f), eps)
    }

    @Test
    fun `unwrapNear 在正好相差 180 度时保持原值`() {
        // 边界：180 不算「大于 180」，因此原样返回，两个方向都合法
        assertEquals(180f, CompassMath.unwrapNear(reference = 0f, target = 180f), eps)
        assertEquals(-180f, CompassMath.unwrapNear(reference = 0f, target = -180f), eps)
    }

    @Test
    fun `unwrapNear 展开后与参考角之差不超过 180 度`() {
        val references = listOf(0f, 45f, 180f, 270f, 359f)
        val targets = listOf(0f, 1f, 90f, 179f, 181f, 270f, 359f)
        references.forEach { r ->
            targets.forEach { t ->
                val unwrapped = CompassMath.unwrapNear(r, t)
                assertTrue(
                    "unwrapNear($r, $t) = $unwrapped 偏离超过 180°",
                    kotlin.math.abs(unwrapped - r) <= 180f + eps,
                )
                // 展开只允许平移 360 的整数倍，方位语义不能变
                assertEquals(t, CompassMath.normalizeDegrees(unwrapped), 0.01f)
            }
        }
    }

    // —— 磁场强度 ——

    @Test
    fun `magneticMagnitude 是三分量的模`() {
        assertEquals(5f, CompassMath.magneticMagnitude(3f, 4f, 0f), eps)
        assertEquals(0f, CompassMath.magneticMagnitude(0f, 0f, 0f), eps)
        assertEquals(50f, CompassMath.magneticMagnitude(0f, 0f, -50f), eps)
    }

    @Test
    fun `isMagneticFieldNormal 的边界是闭区间`() {
        assertTrue(CompassMath.isMagneticFieldNormal(CompassMath.MAGNETIC_FIELD_MIN_UT))
        assertTrue(CompassMath.isMagneticFieldNormal(CompassMath.MAGNETIC_FIELD_MAX_UT))
        assertTrue(CompassMath.isMagneticFieldNormal(45f))
        assertFalse(CompassMath.isMagneticFieldNormal(24.9f))
        assertFalse(CompassMath.isMagneticFieldNormal(65.1f))
        assertFalse(CompassMath.isMagneticFieldNormal(0f))
    }

    @Test
    fun `smoothScalar 按 alpha 线性逼近`() {
        assertEquals(5f, CompassMath.smoothScalar(prev = 0f, next = 10f, alpha = 0.5f), eps)
        assertEquals(0f, CompassMath.smoothScalar(prev = 0f, next = 10f, alpha = 0f), eps)
        assertEquals(10f, CompassMath.smoothScalar(prev = 0f, next = 10f, alpha = 1f), eps)
    }

    // —— 十六方位 ——

    @Test
    fun `headingName 每 22 点 5 度一档`() {
        assertEquals("北", CompassMath.headingName(0f))
        assertEquals("北", CompassMath.headingName(359f))
        assertEquals("北偏东", CompassMath.headingName(22.5f))
        assertEquals("东北", CompassMath.headingName(45f))
        assertEquals("东", CompassMath.headingName(90f))
        assertEquals("南", CompassMath.headingName(180f))
        assertEquals("西", CompassMath.headingName(270f))
    }

    @Test
    fun `headingName 与设计文档示例一致`() {
        // design-gui.md 用 247° → 「西南偏西」作为示例
        assertEquals("西南偏西", CompassMath.headingName(247f))
    }

    @Test
    fun `headingName 对负角度和超范围角度同样成立`() {
        assertEquals(CompassMath.headingName(90f), CompassMath.headingName(-270f))
        assertEquals(CompassMath.headingName(45f), CompassMath.headingName(405f))
    }

    @Test
    fun `headingName 遍历整圈不会越界`() {
        var deg = 0f
        while (deg < 360f) {
            val name = CompassMath.headingName(deg)
            assertTrue("角度 $deg 得到空名称", name.isNotEmpty())
            deg += 0.5f
        }
    }

    // —— 弧度转换与真北 ——

    @Test
    fun `弧度转角度`() {
        assertEquals(0f, CompassMath.azimuthDegreesFrom(0f), eps)
        assertEquals(90f, CompassMath.azimuthDegreesFrom((Math.PI / 2).toFloat()), 0.01f)
        assertEquals(180f, CompassMath.azimuthDegreesFrom(Math.PI.toFloat()), 0.01f)
        assertEquals(270f, CompassMath.azimuthDegreesFrom((Math.PI * 3 / 2).toFloat()), 0.01f)
        assertEquals(270f, CompassMath.azimuthDegreesFrom((-Math.PI / 2).toFloat()), 0.01f)
    }

    @Test
    fun `trueAzimuth 加磁偏角并归一化`() {
        assertEquals(10f, CompassMath.trueAzimuth(350f, 20f), eps)
        assertEquals(350f, CompassMath.trueAzimuth(10f, -20f), eps)
        assertEquals(45f, CompassMath.trueAzimuth(45f, 0f), eps)
        assertEquals(0f, CompassMath.trueAzimuth(360f, 0f), eps)
    }

    // —— 屏幕旋转重映射：错了会让全部记录偏移 90° ——

    @Test
    fun `axisRemapFor 四种屏幕旋转都给出对应轴对`() {
        assertEquals(
            CompassMath.AxisRemap(CompassMath.AXIS_X, CompassMath.AXIS_Y),
            CompassMath.axisRemapFor(CompassMath.ROTATION_0),
        )
        assertEquals(
            CompassMath.AxisRemap(CompassMath.AXIS_Y, CompassMath.AXIS_MINUS_X),
            CompassMath.axisRemapFor(CompassMath.ROTATION_90),
        )
        assertEquals(
            CompassMath.AxisRemap(CompassMath.AXIS_MINUS_X, CompassMath.AXIS_MINUS_Y),
            CompassMath.axisRemapFor(CompassMath.ROTATION_180),
        )
        assertEquals(
            CompassMath.AxisRemap(CompassMath.AXIS_MINUS_Y, CompassMath.AXIS_X),
            CompassMath.axisRemapFor(CompassMath.ROTATION_270),
        )
    }

    @Test
    fun `轴常量与 Android SensorManager 取值一致`() {
        // 这些值是从 android.hardware.SensorManager 抄来的，抄错不会有编译错误，
        // 只会让横屏方位角悄悄偏 90°——只能靠这个断言挡住。
        assertEquals(1, CompassMath.AXIS_X)
        assertEquals(2, CompassMath.AXIS_Y)
        assertEquals(3, CompassMath.AXIS_Z)
        assertEquals(129, CompassMath.AXIS_MINUS_X)
        assertEquals(130, CompassMath.AXIS_MINUS_Y)
        assertEquals(131, CompassMath.AXIS_MINUS_Z)
    }

    @Test
    fun `屏幕旋转常量与 Android Surface 取值一致`() {
        assertEquals(0, CompassMath.ROTATION_0)
        assertEquals(1, CompassMath.ROTATION_90)
        assertEquals(2, CompassMath.ROTATION_180)
        assertEquals(3, CompassMath.ROTATION_270)
    }

    @Test
    fun `未知屏幕旋转值回退到不重映射`() {
        assertEquals(
            CompassMath.AxisRemap(CompassMath.AXIS_X, CompassMath.AXIS_Y),
            CompassMath.axisRemapFor(42),
        )
    }

    // —— 带符号角度 ——

    @Test
    fun `normalizeSignedDegrees 保持符号并落在 -180 到 180`() {
        assertEquals(0f, CompassMath.normalizeSignedDegrees(0f), eps)
        assertEquals(-90f, CompassMath.normalizeSignedDegrees(-90f), eps)
        assertEquals(90f, CompassMath.normalizeSignedDegrees(90f), eps)
        assertEquals(180f, CompassMath.normalizeSignedDegrees(180f), eps)
        assertEquals(180f, CompassMath.normalizeSignedDegrees(-180f), eps)
        assertEquals(-179f, CompassMath.normalizeSignedDegrees(181f), eps)
        assertEquals(10f, CompassMath.normalizeSignedDegrees(370f), eps)
    }

    @Test
    fun `normalizeSignedDegrees 不把 -0_8 度变成 359_2 度`() {
        // 这正是不能用 normalizeDegrees 处理俯仰/翻滚的原因：
        // 一个平放手机的 -0.8° 会被它变成 359.2°，与详情页的 formatSigned 输出对不上
        assertEquals(-0.8f, CompassMath.normalizeSignedDegrees(-0.8f), eps)
    }

    // —— 倾角 ——

    @Test
    fun `平放时倾角为零无论屏幕朝上还是朝下`() {
        // getOrientation 的 pitch = asin(-R[7])，R[7] 是世界 Up 行与 deviceY 的点积。
        // 朝上平放与朝下平放，deviceY 都落在水平面内，所以两者 pitch 都是 0、roll 相差 180。
        // 早先的实现假设「平放 = pitch -90」，把这两个状态整个弄反了。
        assertEquals(0f, CompassMath.tiltFromHorizontal(0f, 0f), eps)
        assertEquals(0f, CompassMath.tiltFromHorizontal(0f, 180f), eps)
        assertEquals(0f, CompassMath.tiltFromHorizontal(0f, -180f), eps)
    }

    @Test
    fun `屏幕朝下扣着时倾角不读成 180 度`() {
        // 朝下平放时法线指向地心，法线与铅垂线的夹角是 180°，
        // 但**平面**是水平的。少了取绝对值这一步，扣在桌上的手机就会显示「倾角 180°」。
        // 这是实现过程中真实踩到的一个坑，因此单独钉一条。
        val faceDownTilted = CompassMath.tiltFromHorizontal(0f, 170f)
        assertEquals(10f, faceDownTilted, 0.01f)
    }

    @Test
    fun `竖直握持时倾角为 90 度`() {
        assertEquals(90f, CompassMath.tiltFromHorizontal(-90f, 0f), eps)
        assertEquals(90f, CompassMath.tiltFromHorizontal(90f, 0f), eps)
    }

    @Test
    fun `单轴倾斜时倾角等于该轴的角度`() {
        assertEquals(10f, CompassMath.tiltFromHorizontal(-10f, 0f), eps)
        assertEquals(10f, CompassMath.tiltFromHorizontal(10f, 0f), eps)
        assertEquals(10f, CompassMath.tiltFromHorizontal(0f, 10f), eps)
        assertEquals(10f, CompassMath.tiltFromHorizontal(0f, -10f), eps)
    }

    @Test
    fun `两轴同时倾斜时倾角大于任一单轴而不是两者之和`() {
        // 俯仰 45° 且翻滚 45° 的实际倾角是 60°，不是 90°。
        // 直接相加或只看单轴，都会把这个姿态判得比实际更斜，
        // 从而在手机其实还算平的时候弹出「太斜了」。
        val tilt = CompassMath.tiltFromHorizontal(45f, 45f)
        assertEquals(60f, tilt, 0.01f)
        assertTrue(tilt < 90f)
        assertTrue(tilt > 45f)
    }

    @Test
    fun `倾角永远落在 0 到 90 且对浮点误差不返回 NaN`() {
        // acos 的入参略超 1 会得到 NaN，而 NaN 的比较全为 false——
        // 一旦发生，tiltWarning 会静默地永远不亮，界面上只表现为「提示莫名其妙不见了」。
        // 上界是 90 而不是 180：平面之间的夹角到 90° 就到底了。
        val cases = listOf(
            0f to 0f, 90f to 0f, -90f to 0f, 0f to 180f, 0f to 170f,
            89.9999f to 89.9999f, -45f to 135f, 90f to 180f,
        )
        cases.forEach { (pitch, roll) ->
            val tilt = CompassMath.tiltFromHorizontal(pitch, roll)
            assertFalse("pitch=$pitch roll=$roll 得到 NaN", tilt.isNaN())
            assertTrue("pitch=$pitch roll=$roll 得到 $tilt", tilt in 0f..90f)
        }
    }

    @Test
    fun `isLevel 看的是合成倾角而不是单个轴`() {
        // 俯仰 0.8° 且翻滚 0.8°，合成 1.13°，已经超出 1° 容差。
        // 只看俯仰的话这里会误判为「已水平」，气泡变绿而盘面明明偏着。
        assertEquals(1.131f, CompassMath.tiltFromHorizontal(0.8f, 0.8f), 0.01f)
        assertFalse(CompassMath.isLevel(0.8f, 0.8f))

        assertTrue(CompassMath.isLevel(0f, 0f))
        assertTrue(CompassMath.isLevel(0.5f, 0f))
        assertFalse(CompassMath.isLevel(2f, 0f))
    }

    @Test
    fun `倾斜阈值沿线：刚好 30 度不报警，超过才报警`() {
        // 判据是「离水平多少度」。原作者写的 abs(pitch + 90) > 30 把方向弄反了，
        // 阈值 30 本身是对的，这里把它钉住。
        assertFalse(CompassMath.tiltFromHorizontal(30f, 0f) > CompassMath.TILT_WARNING_DEGREES)
        assertTrue(CompassMath.tiltFromHorizontal(30.5f, 0f) > CompassMath.TILT_WARNING_DEGREES)
    }

    // —— 水平仪气泡 ——

    @Test
    fun `气泡偏移的模长等于倾角的正弦`() {
        // 这不是巧合而是设计前提：LevelBubble 的容差环半径就是按这个比例尺画的，
        // 「气泡圆心落进环内」与 isLevel 因此是几何等价的，而不是两套各自调出的阈值
        val cases = listOf(
            0f to 0f, 10f to 0f, 0f to 10f, 20f to -15f, -30f to 40f, 45f to 45f,
        )
        cases.forEach { (pitch, roll) ->
            val offset = CompassMath.bubbleOffset(pitch, roll)
            val magnitude = kotlin.math.sqrt(offset.x * offset.x + offset.y * offset.y)
            val expected = kotlin.math.sin(
                Math.toRadians(CompassMath.tiltFromHorizontal(pitch, roll).toDouble())
            ).toFloat()
            assertEquals("pitch=$pitch roll=$roll", expected, magnitude, 0.0005f)
        }
    }

    @Test
    fun `气泡偏向抬起的那一侧`() {
        // 画布 y 轴向下，deviceY 指向机头（屏幕上方），因此 y 分量要取反。
        // 这是整个倾角改动里唯一依赖 getOrientation 手性、需要真机肉眼复核的地方。
        val flat = CompassMath.bubbleOffset(0f, 0f)
        assertEquals(0f, flat.x, eps)
        assertEquals(0f, flat.y, eps)

        // 机头抬起 10° → 画布上方（y 为负）
        val noseUp = CompassMath.bubbleOffset(-10f, 0f)
        assertTrue("机头抬起应让气泡上移，实得 y=${noseUp.y}", noseUp.y < 0f)
        assertEquals(0f, noseUp.x, eps)

        // 右边抬起 10° → 画布右方（x 为正）。右边抬 = 世界 Up 在 deviceX 上为正 = roll 为负
        val rightUp = CompassMath.bubbleOffset(0f, -10f)
        assertTrue("右边抬起应让气泡右移，实得 x=${rightUp.x}", rightUp.x > 0f)
        assertEquals(0f, rightUp.y, eps)
    }

    @Test
    fun `倒置平放时气泡仍回到中心`() {
        // 屏幕朝下平放，pitch = 0、roll = ±180——手机是平的，
        // 气泡就该在正中间。这里若漏了 cos(roll) 这一项，气泡会飞到盘边
        val upsideDown = CompassMath.bubbleOffset(0f, 180f)
        assertEquals(0f, upsideDown.x, 0.0001f)
        assertEquals(0f, upsideDown.y, eps)
    }
}
