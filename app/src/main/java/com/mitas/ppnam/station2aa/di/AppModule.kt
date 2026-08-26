package com.mitas.ppnam.station2aa.di

import android.content.Context
import androidx.room.Room
import com.mitas.ppnam.station2aa.data.local.AppDatabase
import com.mitas.ppnam.station2aa.data.local.BomCacheDao
import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "ppnam_station2.db")
            // The only surviving table is a BOM cache, which is disposable by definition, and the
            // dropped offline_queue held nothing but permanently undeliverable legacy rows. There
            // are no live users to migrate (the v3 cutover is big-bang), so a rebuild is correct.
            // Note: the no-arg overload — Room here is 2.6.1, and the dropAllTables parameter only
            // exists from 2.7 onward.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideBomCacheDao(db: AppDatabase): BomCacheDao = db.bomCacheDao()

    @Provides
    @Singleton
    fun provideMqttRepository(impl: MqttRepositoryImpl): MqttRepository = impl
}
