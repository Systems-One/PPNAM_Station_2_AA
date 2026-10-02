package com.mitas.ppnam.station2aa.di

import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.PrefsPinLockoutStore
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMqttRepository(impl: MqttRepositoryImpl): MqttRepository = impl

    @Provides
    @Singleton
    fun providePinLockoutStore(impl: PrefsPinLockoutStore): PinLockoutStore = impl
}
