package com.magnate.compass.data.db

import androidx.room.migration.Migration

/**
 * 显式迁移列表。
 *
 * v1 是首个版本，尚无迁移。后续每次改动 schema 都必须：
 * 1. 提升 [MagnateDatabase] 的 `version`；
 * 2. 在此追加一个显式 [Migration]；
 * 3. 在 `androidTest` 的 `MigrationTest` 中验证迁移后数据完整。
 *
 * **禁止 `fallbackToDestructiveMigration()`**——它会在升级时静默删光用户记录（design.md §5.10）。
 */
val MIGRATIONS: Array<Migration> = emptyArray()
