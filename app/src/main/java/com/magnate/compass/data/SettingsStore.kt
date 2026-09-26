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

    /** 真北模式。默认关闭——磁北是传感器直接给出的值，不做任何换算，最诚实。 */
    val trueNorthEnabled: Flow<Boolean> = context.magnatePrefs.data.map { it[trueNorthKey] ?: false }

    suspend fun setTrueNorthEnabled(enabled: Boolean) {
        context.magnatePrefs.edit { it[trueNorthKey] = enabled }
    }
}
