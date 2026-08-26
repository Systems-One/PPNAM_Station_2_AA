package com.mitas.ppnam.station2aa.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bom_cache")
data class BomCacheEntity(
    @PrimaryKey val orderNo: String,
    val bomJson: String,
    val fetchedAt: Long
)
