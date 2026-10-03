package com.mitas.ppnam.station2aa

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mitas.ppnam.station2aa.data.rfid.DataWedgeReceiver
import com.mitas.ppnam.station2aa.data.session.SessionGuard
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PpnamApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dataWedgeReceiver: DataWedgeReceiver
    @Inject lateinit var sessionGuard: SessionGuard

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(DataWedgeReceiver.ACTION_SCAN)
            addAction(DataWedgeReceiver.ACTION_CHAINWAY_BARCODE)
            addAction(DataWedgeReceiver.ACTION_CHAINWAY_RFID)
        }
        ContextCompat.registerReceiver(this, dataWedgeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        sessionGuard.install()
    }
}
