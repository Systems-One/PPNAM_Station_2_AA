package com.mitas.ppnam.station2aa

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mitas.ppnam.station2aa.data.rfid.DataWedgeReceiver
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.session.SessionGuard
import com.mitas.ppnam.station2aa.data.session.SessionPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PpnamApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dataWedgeReceiver: DataWedgeReceiver
    @Inject lateinit var sessionGuard: SessionGuard
    @Inject lateinit var sessionHolder: OperatorSessionHolder
    @Inject lateinit var sessionPersistence: SessionPersistence

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
        // A crash or restart mid-preparation must not sign the operator out: bring back a
        // still-valid session before any screen reads it, then keep the saved copy in step.
        sessionPersistence.restoreInto(sessionHolder)
        sessionPersistence.install(sessionHolder, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        sessionGuard.install()
    }
}
