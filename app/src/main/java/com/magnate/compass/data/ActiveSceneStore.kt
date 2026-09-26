package com.magnate.compass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.magnateSettings: DataStore<Preferences> by preferencesDataStore(
    name = "magnate_settings",
)

/**
 * 「当前激活场景」的持久化。
 *
 * 写 DataStore 而非数据库：**应用重启后必须保持**。用户进了 B2 层，
 * 接了个电话、锁屏、切后台再回来，应该还在这个场景里（design-gui.md §4.4）。
 */
class ActiveSceneStore(private val context: Context) {

    private val activeSceneKey = longPreferencesKey("active_scene_id")

    /** null = 未选择场景。 */
    val activeSceneId: Flow<Long?> = context.magnateSettings.data.map { it[activeSceneKey] }

    suspend fun setActiveScene(sceneId: Long?) {
        context.magnateSettings.edit { prefs ->
            if (sceneId == null) {
                prefs.remove(activeSceneKey)
            } else {
                prefs[activeSceneKey] = sceneId
            }
        }
    }
}
