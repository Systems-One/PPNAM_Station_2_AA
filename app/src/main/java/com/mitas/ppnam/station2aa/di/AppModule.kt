package com.mitas.ppnam.station2aa.di

import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
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
}
