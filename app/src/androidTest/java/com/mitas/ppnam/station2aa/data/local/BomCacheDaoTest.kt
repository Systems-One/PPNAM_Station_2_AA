package com.mitas.ppnam.station2aa.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BomCacheDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BomCacheDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.bomCacheDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun putAndGet() = runTest {
        dao.put(BomCacheEntity("510019068", "{\"docNo\":\"510019068\"}", 1000L))
        val result = dao.get("510019068")
        assertNotNull(result)
        assertEquals("{\"docNo\":\"510019068\"}", result!!.bomJson)
    }

    @Test
    fun getMissingReturnsNull() = runTest {
        assertNull(dao.get("missing"))
    }

    @Test
    fun deleteRemovesRow() = runTest {
        dao.put(BomCacheEntity("510019068", "{}", 1000L))
        dao.delete("510019068")
        assertNull(dao.get("510019068"))
    }
}
