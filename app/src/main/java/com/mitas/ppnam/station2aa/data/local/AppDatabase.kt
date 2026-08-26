package com.mitas.ppnam.station2aa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BomCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bomCacheDao(): BomCacheDao
}
