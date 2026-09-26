package com.magnate.compass.di

import android.content.Context
import com.magnate.compass.data.ActiveSceneStore
import com.magnate.compass.data.RecordRepository
import com.magnate.compass.data.SceneRepository
import com.magnate.compass.data.SettingsStore
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.db.MagnateDatabase
import com.magnate.compass.location.LocationProvider
import com.magnate.compass.sensor.SensorRepository

/**
 * 手写依赖容器。
 *
 * 依赖数量少，Hilt 的构建期开销不值（design.md §4「依赖注入」）。
 * 所有依赖都是 `lazy`——数据库与传感器不会在应用启动时就被初始化。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: MagnateDatabase by lazy { MagnateDatabase.create(appContext) }

    val sensorRepository: SensorRepository by lazy { SensorRepository(appContext) }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val activeSceneStore: ActiveSceneStore by lazy { ActiveSceneStore(appContext) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val recordRepository: RecordRepository by lazy {
        RecordRepository(
            db = database,
            recordDao = database.recordDao(),
            tagDao = database.tagDao(),
            sceneDao = database.sceneDao(),
        )
    }

    val sceneRepository: SceneRepository by lazy {
        SceneRepository(
            db = database,
            sceneDao = database.sceneDao(),
            recordDao = database.recordDao(),
            activeSceneStore = activeSceneStore,
        )
    }

    val tagRepository: TagRepository by lazy { TagRepository(database.tagDao()) }
}
