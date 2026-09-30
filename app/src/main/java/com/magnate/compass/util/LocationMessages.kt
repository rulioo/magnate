package com.magnate.compass.util

import com.magnate.compass.location.LocationResult

/**
 * 取坐标失败时给用户看的那一句话。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测。
 *
 * **只有超时才允许提「到窗边或室外」。** 这正是本次要修的回归：原先四条失败路径
 * 全都写「请到窗边或室外再试」，而当时的真实主因是**应用从未申请过权限**——
 * 用户照着提示走到室外站到天亮也不会好，因为问题根本不在天上。
 * 权限、定位服务关闭、看不见天，是三件用户要做三件不同事的事，必须分开说。
 *
 * @return 失败文案；成功时返回 null（没有失败可讲）。
 */
fun locationFailureMessage(
    result: LocationResult,
    permissionState: LocationPermissionState,
): String? = when (result) {
    is LocationResult.Success -> null

    LocationResult.NoPermission -> when (permissionState) {
        // 系统不再允许弹框了，再说「需要权限」等于让用户对着一个点不动的按钮发呆
        LocationPermissionState.DENIED_PERMANENTLY ->
            "定位权限已关闭，可在系统设置中重新开启"

        // 还能再弹框（含「从未申请过」）。此时界面会先把说明框推出去，
        // 这一句是用户点了「暂不」之后才看到的。
        // GRANTED 在这里出现只可能是权限在检查与使用之间被撤销的竞态，按可再申请处理。
        LocationPermissionState.GRANTED,
        LocationPermissionState.DENIED_CAN_ASK,
        -> "需要定位权限才能记录坐标"
    }

    LocationResult.ServicesDisabled ->
        "系统定位服务已关闭，请先在系统设置中开启"

    LocationResult.Timeout ->
        "定位失败，请到窗边或室外再试"
}

/**
 * 失败提示是否该挂一个「去设置」的动作。
 *
 * 只有永久拒绝这一种情况用户必须离开应用才能解决，其余都可以在应用内重试。
 * 给不需要它的情况挂上这个动作，只会把一次重试变成一次跳转。
 */
fun offersSettings(
    result: LocationResult,
    permissionState: LocationPermissionState,
): Boolean = result == LocationResult.NoPermission &&
    permissionState == LocationPermissionState.DENIED_PERMANENTLY
