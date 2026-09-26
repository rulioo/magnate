package com.magnate.compass.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 定位权限的状态判定。
 *
 * 权限策略见 design.md §7：**不在启动时索取**，首次「获取场景坐标」「补录坐标」
 * 或「开启真北」时申请；拒绝后测量与保存完全正常，且保留后悔的入口。
 */
object PermissionHelper {

    /** Android 12+ 必须同时申请 COARSE 与 FINE，只申请 FINE 会被系统忽略。 */
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    enum class LocationPermissionState {
        GRANTED,

        /** 被拒但还可以再次申请——应附说明理由的对话框 */
        DENIED_CAN_ASK,

        /** 永久拒绝——只能引导到系统设置，不再弹窗骚扰 */
        DENIED_PERMANENTLY,
    }

    fun hasLocationPermission(context: Context): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun stateOf(activity: Activity): LocationPermissionState = when {
        hasLocationPermission(activity) -> LocationPermissionState.GRANTED
        LOCATION_PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        } -> LocationPermissionState.DENIED_CAN_ASK

        else -> LocationPermissionState.DENIED_PERMANENTLY
    }
}
