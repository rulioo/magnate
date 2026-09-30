package com.magnate.compass.ui.common

import com.magnate.compass.location.GeoPoint
import com.magnate.compass.location.LocationResult
import com.magnate.compass.util.LocationPermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一次取坐标失败的完整结论：**原始原因** + **权限分类**。
 *
 * 两者都要带：文案由 `locationFailureMessage(result, permissionState)` 决定，
 * 而「是否该给一个去设置的按钮」由 `offersSettings` 决定——两个问题都答不了只看其中之一。
 */
data class LocationAttemptFailure(
    val result: LocationResult,
    val permissionState: LocationPermissionState,
)

/**
 * 取一次坐标，顺手把权限处理掉。
 *
 * 未授权 → 说明框 → 系统弹窗 → 授权成功后**自动重试一次**。
 *
 * **只重试一次**是刻意的：若允许无限重试，一个「授权了但仍旧取不到定位」的机器
 * 会陷入「授权 → 失败 → 再问 → 再失败」，而第二次问根本没有意义——
 * 权限已经给了，问题在别处（服务没开、室内定不上位），再问一次只是骚扰。
 *
 * @param scope 调用方的作用域。**这一点承担了关键的语义**：面板被划掉后，
 *   它的 `rememberCoroutineScope` 随之取消，于是授权回调里的重试变成一次静默的空操作——
 *   正是想要的结果，且不需要任何额外的存活判断。
 */
fun acquireLocationWithPermission(
    scope: CoroutineScope,
    gate: LocationPermissionGate,
    acquire: suspend () -> LocationResult,
    onSuccess: (GeoPoint) -> Unit,
    onFailure: (LocationAttemptFailure) -> Unit,
) {
    fun attempt(retried: Boolean) {
        scope.launch {
            when (val result = acquire()) {
                is LocationResult.Success -> onSuccess(result.point)

                LocationResult.NoPermission ->
                    if (retried) {
                        // 授权之后仍然说没权限：不再追问，直接如实报告
                        onFailure(LocationAttemptFailure(result, gate.state))
                    } else {
                        gate.request(
                            onGranted = { attempt(retried = true) },
                            onDenied = {
                                onFailure(
                                    LocationAttemptFailure(LocationResult.NoPermission, it),
                                )
                            },
                        )
                    }

                // 服务关闭 / 超时与权限无关，弹框解决不了，如实报告
                else -> onFailure(LocationAttemptFailure(result, gate.state))
            }
        }
    }

    attempt(retried = false)
}
