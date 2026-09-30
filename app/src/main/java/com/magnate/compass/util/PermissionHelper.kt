package com.magnate.compass.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限状态。
 *
 * 定义为**顶层类型**而不是嵌在 `PermissionHelper` 里：它现在要与纯函数 [classifyLocationPermission]、
 * 以及界面层的 Snackbar 动作共用，嵌在 object 里每次引用都要多一层前缀。
 */
enum class LocationPermissionState {
    GRANTED,

    /** 被拒但还可以再次申请——应附说明理由的对话框 */
    DENIED_CAN_ASK,

    /** 永久拒绝——只能引导到系统设置，不再弹窗骚扰 */
    DENIED_PERMANENTLY,
}

/**
 * 定位权限的**档位**。
 *
 * 必须把只授予粗略定位单独列出来：Android 12+ 起用户可以在系统弹窗里选「仅大致位置」，
 * 那样拿到的是约 ±2km 的模糊坐标。它一路流进场景坐标时若不做任何说明，
 * 用户会以为自己测的是一个点，实际是一个街区。
 */
enum class LocationPermissionLevel {
    /** 精确位置。`ACCESS_FINE_LOCATION` 已授予。 */
    FINE,

    /** 仅大致位置。卫星列表在这种情况下不会有数据（见 `GnssProvider`）。 */
    COARSE_ONLY,

    NONE,
}

/**
 * 判定权限状态。纯函数，无 Android 依赖，可 JVM 单测。
 *
 * **`hasAskedBefore` 是必需的第三个入参，不是可有可无的。**
 * `shouldShowRequestPermissionRationale` 在两种情况下都返回 false：用户永久拒绝之后，
 * **以及应用从未申请过**。只凭它分类，全新安装的设备上第一次点「获取坐标」就会被判成
 * 「永久拒绝」、被直接送去系统设置，而不是弹出申请框——功能一样是用不了的。
 * 所以「问过没有」必须由调用方从持久化偏好里带进来。
 */
fun classifyLocationPermission(
    granted: Boolean,
    shouldShowRationale: Boolean,
    hasAskedBefore: Boolean,
): LocationPermissionState = when {
    granted -> LocationPermissionState.GRANTED
    shouldShowRationale -> LocationPermissionState.DENIED_CAN_ASK

    // 从未申请过 → 当然还可以申请
    !hasAskedBefore -> LocationPermissionState.DENIED_CAN_ASK

    else -> LocationPermissionState.DENIED_PERMANENTLY
}

/** 由两个权限各自的授予情况归出档位。纯函数，可 JVM 单测。 */
fun locationPermissionLevelOf(
    fineGranted: Boolean,
    coarseGranted: Boolean,
): LocationPermissionLevel = when {
    fineGranted -> LocationPermissionLevel.FINE
    coarseGranted -> LocationPermissionLevel.COARSE_ONLY
    else -> LocationPermissionLevel.NONE
}

/**
 * 定位权限的判定与申请。
 *
 * 权限策略见 design.md §7：**启动时申请一次**（附说明理由的对话框），此后不再自动重试，
 * 由主页常驻的「启用定位」条目承担后悔入口。拒绝后测量与保存完全正常。
 */
object PermissionHelper {

    /** Android 12+ 必须同时申请 COARSE 与 FINE，只申请 FINE 会被系统忽略。 */
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * 两个权限**任一**授予即可调用定位 API。
     *
     * 用 `any` 而非要求 FINE：只授予粗略定位时定位仍然可用（只是精度差），
     * 在这里返回 false 会让界面把用户当成「完全没授权」，反而把可用的功能藏起来。
     * 精度受限这件事由 [levelOf] 表达、由界面提示，不由这里一刀切。
     */
    fun hasLocationPermission(context: Context): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 只有精确定位。卫星状态需要它——API 29+ 起只有 FINE 才能拿到有意义的 `GnssStatus`。 */
    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun levelOf(context: Context): LocationPermissionLevel = locationPermissionLevelOf(
        fineGranted = hasFineLocationPermission(context),
        coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED,
    )

    /**
     * @param activity 为空时退化为「不可再次申请」而不是崩溃——申请框本就依赖 Activity 存在。
     */
    fun stateOf(activity: Activity?, hasAskedBefore: Boolean): LocationPermissionState {
        if (activity == null) return LocationPermissionState.DENIED_PERMANENTLY
        return classifyLocationPermission(
            granted = hasLocationPermission(activity),
            shouldShowRationale = LOCATION_PERMISSIONS.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            },
            hasAskedBefore = hasAskedBefore,
        )
    }
}
