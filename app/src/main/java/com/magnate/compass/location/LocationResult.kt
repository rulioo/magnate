package com.magnate.compass.location

/**
 * 一次坐标获取的**结果及其原因**。
 *
 * 替换掉原先的 `GeoPoint?`：那个可空返回把三种完全不同的失败压成了同一个 `null`，
 * 于是四条界面提示只能一律写成「请到窗边或室外再试」——而真实主因是没授权限时，
 * 用户照着提示走到室外站到天亮也不会好。原因必须一路传到界面上。
 *
 * **刻意不含「永久拒绝」这一项。** 判定能否再次申请要调用
 * `shouldShowRequestPermissionRationale`，那需要 `Activity`；而 [LocationProvider] 是拿
 * application context 构造的。硬塞进来就只有两条路：给容器塞一个 Activity，
 * 或者在这里对一个它答不上来的问题撒谎。所以分工是——
 * provider 只报「发生了什么」（本类型），界面层（手里有 Activity）用
 * [com.magnate.compass.util.classifyLocationPermission] 判定「为什么」。
 */
sealed interface LocationResult {

    data class Success(val point: GeoPoint) : LocationResult

    /** 没有定位权限。能否再次申请由界面层的权限分类判定，见类型 KDoc。 */
    data object NoPermission : LocationResult

    /**
     * 一个可用的 provider 都没有——包含系统定位总开关被关掉，
     * 也包含设备压根没有 `LocationManager`。
     *
     * 两者合并成一项是刻意的：从用户的位置看它们是同一件事（「系统定位用不了」），
     * 区分开只会多出一条谁也分不清该选哪个的分支。
     */
    data object ServicesDisabled : LocationResult

    /** 服务可用、权限也在，但没能在超时预算内拿到定位。**只有这一种才该提「到室外」。** */
    data object Timeout : LocationResult
}
