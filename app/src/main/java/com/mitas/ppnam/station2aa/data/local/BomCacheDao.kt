package com.mitas.ppnam.station2aa.data.local

import androidx.room.*

@Dao
interface BomCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BomCacheEntity)

    @Query("SELECT * FROM bom_cache WHERE orderNo = :orderNo")
    suspend fun get(orderNo: String): BomCacheEntity?

    @Query("DELETE FROM bom_cache WHERE orderNo = :orderNo")
    suspend fun delete(orderNo: String)
}
