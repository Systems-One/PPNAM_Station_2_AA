package com.mitas.ppnam.station2aa.di

import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.data.settings.OperatorDirectoryStore
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.PrefsOperatorDirectoryStore
import com.mitas.ppnam.station2aa.data.settings.PrefsPinLockoutStore
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import android.content.Context
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.FileCommandOutbox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    @Provides
    @Singleton
    fun provideOperatorDirectoryStore(impl: PrefsOperatorDirectoryStore): OperatorDirectoryStore = impl

    @Provides
    @Singleton
    fun provideCommandOutbox(@ApplicationContext context: Context): CommandOutbox =
        FileCommandOutbox(File(context.filesDir, "outbox"))
}
