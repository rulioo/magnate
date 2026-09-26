package com.magnate.compass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoFormatTest {

    // —— 经纬度 ——

    @Test
    fun `纬度六位小数带半球后缀`() {
        assertEquals("30.123457°N", GeoFormat.formatLatitude(30.1234567))
        assertEquals("0.000000°N", GeoFormat.formatLatitude(0.0))
    }

    @Test
    fun `南纬与西经用 S 和 W`() {
        assertEquals("33.868820°S", GeoFormat.formatLatitude(-33.86882))
        assertEquals("118.243680°W", GeoFormat.formatLongitude(-118.24368))
    }

    @Test
    fun `零度算北纬与东经`() {
        // 格林尼治与本初子午线上不该出现 -0.000000 这种写法
        assertEquals("0.000000°N", GeoFormat.formatLatitude(-0.0))
        assertEquals("0.000000°E", GeoFormat.formatLongitude(-0.0))
    }

    @Test
    fun `完整坐标用逗号分隔`() {
        assertEquals("30.123456°N, 120.567890°E", GeoFormat.formatCoordinates(30.123456, 120.56789))
    }

    @Test
    fun `短坐标四位小数`() {
        assertEquals("30.1235°N, 120.5679°E", GeoFormat.formatCoordinatesShort(30.123456, 120.56789))
        assertEquals("33.8688°S, 118.2437°W", GeoFormat.formatCoordinatesShort(-33.86882, -118.24368))
    }

    // —— 精度：哨兵值必须显示为「未知」而不是天文数字 ——

    @Test
    fun `Float MAX_VALUE 是缺失精度的哨兵 显示为精度未知`() {
        // resolveLocation 对缺失精度填的是 Float.MAX_VALUE，
        // 直接按数值格式化会显示 ±340282346638528859811704183484516925440m
        assertEquals("精度未知", GeoFormat.formatAccuracy(Float.MAX_VALUE))
    }

    @Test
    fun `NaN 精度同样显示为未知`() {
        assertEquals("精度未知", GeoFormat.formatAccuracy(Float.NaN))
    }

    @Test
    fun `正常精度四舍五入到米`() {
        assertEquals("±8m", GeoFormat.formatAccuracy(8.4f))
        assertEquals("±9m", GeoFormat.formatAccuracy(8.6f))
        assertEquals("±50m", GeoFormat.formatAccuracy(50f))
    }

    @Test
    fun `亚米级精度显示为小于一米`() {
        assertEquals("±<1m", GeoFormat.formatAccuracy(0.4f))
        assertEquals("±<1m", GeoFormat.formatAccuracy(0f))
    }

    // —— 精度分档 ——

    @Test
    fun `精度分档的边界是闭区间`() {
        assertEquals(AccuracyTier.GOOD, GeoFormat.accuracyTier(10f))
        assertEquals(AccuracyTier.MEDIUM, GeoFormat.accuracyTier(10.1f))
        assertEquals(AccuracyTier.MEDIUM, GeoFormat.accuracyTier(50f))
        assertEquals(AccuracyTier.POOR, GeoFormat.accuracyTier(50.1f))
    }

    @Test
    fun `分档对极好与极差的处理`() {
        assertEquals(AccuracyTier.GOOD, GeoFormat.accuracyTier(0f))
        assertEquals(AccuracyTier.POOR, GeoFormat.accuracyTier(2000f))
    }

    @Test
    fun `缺失精度分档为 UNKNOWN 而不是 GOOD`() {
        // 分档若回落到 GOOD，缺失精度的坐标会被染成「可信」的绿色
        assertEquals(AccuracyTier.UNKNOWN, GeoFormat.accuracyTier(Float.MAX_VALUE))
        assertEquals(AccuracyTier.UNKNOWN, GeoFormat.accuracyTier(Float.NaN))
    }

    // —— 海拔 ——

    @Test
    fun `海拔取整到米`() {
        assertEquals("海拔 42m", GeoFormat.formatAltitude(42.4))
        assertEquals("海拔 -7m", GeoFormat.formatAltitude(-7.2))
    }

    @Test
    fun `海拔缺失时返回 null 交给调用方决定不显示`() {
        assertNull(GeoFormat.formatAltitude(null))
    }

    // —— 符号占位：磁场三分量等宽的关键 ——

    @Test
    fun `正数也带加号 保证数字宽度稳定`() {
        // 若负号只在为负时出现，数字左右边界会随读数跳动，整列 X/Y/Z 看起来在抖
        assertEquals("+8.7", GeoFormat.formatSigned(8.7f))
        assertEquals("-12.4", GeoFormat.formatSigned(-12.4f))
    }

    @Test
    fun `零带加号`() {
        assertEquals("+0.0", GeoFormat.formatSigned(0f))
    }

    @Test
    fun `同一个数取正负后宽度完全一致`() {
        // 这是等宽原则真正要保证的东西：读数在正负之间穿越时数字不左右跳动
        listOf(0f, 1f, 12.3f, 100f, 1234.5f).forEach { v ->
            assertEquals(
                "值 $v 与其相反数的宽度不一致",
                GeoFormat.formatSigned(v).length,
                GeoFormat.formatSigned(-v).length,
            )
        }
    }

    // —— 磁场总强度 ——

    @Test
    fun `磁场强度保留一位小数并带单位`() {
        assertEquals("51.3 μT", GeoFormat.formatMagnitude(51.34f))
        assertEquals("0.0 μT", GeoFormat.formatMagnitude(0f))
    }

    @Test
    fun `磁场强度恒为正 不加符号占位`() {
        assertEquals("12.0 μT", GeoFormat.formatMagnitude(12f))
    }
}
