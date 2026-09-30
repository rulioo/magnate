package com.magnate.compass.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 权限分类的契约。
 *
 * 这一组断言存在的理由很具体：`shouldShowRequestPermissionRationale` 在「用户永久拒绝」
 * 与「应用从未申请过」两种情况下**都**返回 false，仅凭它分类会让全新安装的设备
 * 第一次点「获取坐标」就被判成永久拒绝、被直接送去系统设置——功能一样用不了。
 * 所以 `hasAskedBefore` 这个入参以及它参与的每一条分支都必须被钉住。
 */
class LocationPermissionTest {

    // —— classifyLocationPermission ——

    @Test
    fun `已授权时返回 GRANTED 且与其余入参无关`() {
        assertEquals(
            LocationPermissionState.GRANTED,
            classifyLocationPermission(granted = true, shouldShowRationale = false, hasAskedBefore = false),
        )
        assertEquals(
            LocationPermissionState.GRANTED,
            classifyLocationPermission(granted = true, shouldShowRationale = true, hasAskedBefore = true),
        )
    }

    @Test
    fun `未授权但可以再次申请时返回 DENIED_CAN_ASK`() {
        assertEquals(
            LocationPermissionState.DENIED_CAN_ASK,
            classifyLocationPermission(granted = false, shouldShowRationale = true, hasAskedBefore = true),
        )
    }

    @Test
    fun `从未申请过时返回 DENIED_CAN_ASK 而不是永久拒绝`() {
        // 本次修掉的那个缺陷。首次申请前系统同样说「不需要说明理由」，
        // 但那不等于永久拒绝——此时弹申请框是完全可以成功的
        assertEquals(
            LocationPermissionState.DENIED_CAN_ASK,
            classifyLocationPermission(granted = false, shouldShowRationale = false, hasAskedBefore = false),
        )
    }

    @Test
    fun `申请过且系统不再允许弹窗时返回 DENIED_PERMANENTLY`() {
        assertEquals(
            LocationPermissionState.DENIED_PERMANENTLY,
            classifyLocationPermission(granted = false, shouldShowRationale = false, hasAskedBefore = true),
        )
    }

    // —— locationPermissionLevelOf ——

    @Test
    fun `FINE 已授予时档位是 FINE 而不是 COARSE_ONLY`() {
        // Android 12+ 下授予精确位置会**同时**授予两个权限，
        // 若先判 COARSE 就会把精确位置误报成「仅大致位置」
        assertEquals(
            LocationPermissionLevel.FINE,
            locationPermissionLevelOf(fineGranted = true, coarseGranted = true),
        )
    }

    @Test
    fun `FINE 未授予而 COARSE 已授予时档位是 COARSE_ONLY`() {
        assertEquals(
            LocationPermissionLevel.COARSE_ONLY,
            locationPermissionLevelOf(fineGranted = false, coarseGranted = true),
        )
    }

    @Test
    fun `两个权限都未授予时档位是 NONE`() {
        assertEquals(
            LocationPermissionLevel.NONE,
            locationPermissionLevelOf(fineGranted = false, coarseGranted = false),
        )
    }
}
