package com.magnate.compass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.magnatePrefs: DataStore<Preferences> by preferencesDataStore(
    name = "magnate_preferences",
)

/**
 * 应用级偏好。
 *
 * 与 [ActiveSceneStore] 分文件存放：激活场景是**会话状态**，真北开关是**用户偏好**，
 * 两者的生命周期与重置语义不同。
 */
class SettingsStore(private val context: Context) {

    private val trueNorthKey = booleanPreferencesKey("true_north_enabled")

    private val locationAskedKey = booleanPreferencesKey("location_permission_requested")

    /** 真北模式。默认关闭——磁北是传感器直接给出的值，不做任何换算，最诚实。 */
    val trueNorthEnabled: Flow<Boolean> = context.magnatePrefs.data.map { it[trueNorthKey] ?: false }

    suspend fun setTrueNorthEnabled(enabled: Boolean) {
        context.magnatePrefs.edit { it[trueNorthKey] = enabled }
    }

    /**
     * 是否已经为定位权限弹过一次申请框。
     *
     * 这个标记不是可有可无的记录，而是权限判定的**必需输入**：
     * `shouldShowRequestPermissionRationale` 在「从未申请过」与「永久拒绝」两种情况下
     * 都返回 false，只有结合本标记才能把二者分开——
     * 否则全新安装的设备上第一次点「获取坐标」会被直接送去系统设置
     * （见 [com.magnate.compass.util.classifyLocationPermission]）。
     *
     * 同时它也决定应用启动后是否还要自动申请：只在**从未问过**时问一次。
     * 之后不再自动重试——Android 本身在两次拒绝后就不再弹框了，
     * 无脑每次启动都申请，用户会看到一个点了没反应的按钮，比不申请更糟。
     */
    val locationPermissionRequested: Flow<Boolean> =
        context.magnatePrefs.data.map { it[locationAskedKey] ?: false }

    suspend fun setLocationPermissionRequested() {
        context.magnatePrefs.edit { it[locationAskedKey] = true }
    }
}
