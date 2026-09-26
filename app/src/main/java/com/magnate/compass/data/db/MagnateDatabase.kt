package com.magnate.compass.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.data.entity.RecordEntity
import com.magnate.compass.data.entity.RecordTagCrossRef
import com.magnate.compass.data.entity.SceneEntity
import com.magnate.compass.data.entity.TagEntity

/** `CoordMode` 以枚举名落库——比序号稳定，插入新枚举值时不会错位。 */
class Converters {
    @TypeConverter
    fun fromCoordMode(mode: CoordMode): String = mode.name

    @TypeConverter
    fun toCoordMode(value: String): CoordMode = CoordMode.valueOf(value)
}

@Database(
    entities = [
        RecordEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class,
        SceneEntity::class,
    ],
    version = 1,
    // 必须导出 schema：v1.0 就做好，后续加字段才有迁移基线（design.md §5.10）
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MagnateDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao

    abstract fun tagDao(): TagDao

    abstract fun sceneDao(): SceneDao

    companion object {
        private const val DATABASE_NAME = "magnate.db"

        fun create(context: Context): MagnateDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MagnateDatabase::class.java,
                DATABASE_NAME,
            )
                // 外键约束默认关闭，SET_NULL / CASCADE 不会生效
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .addMigrations(*MIGRATIONS)
                // 刻意不调用 fallbackToDestructiveMigration()——
                // 那会在升级时静默删光用户数据（design.md §5.10）
                .build()
    }
}
