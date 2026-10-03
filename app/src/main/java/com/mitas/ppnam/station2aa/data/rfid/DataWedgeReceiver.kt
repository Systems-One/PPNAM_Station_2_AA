package com.mitas.ppnam.station2aa.data.rfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// Registered dynamically at runtime (see PpnamApplication.onCreate), not declared in the
// manifest. Android 11+ package-visibility filtering silently drops fully-implicit
// broadcasts (no target package/component set) sent to manifest-declared receivers from
// apps we're not "visible" to — which is exactly how the Chainway scanner service and its
// keyboardemulator companion app deliver scans. Dynamic registration bypasses that
// resolution path, since the OS delivers to live registered receivers directly rather than
// resolving candidates via a cross-package manifest query.
@Singleton
class DataWedgeReceiver @Inject constructor(
    private val scanEventBus: ScanEventBus
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action} extras=${intent.extras?.keySet()?.associateWith { intent.extras?.get(it) }}")
        when (intent.action) {
            ACTION_SCAN -> {
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
                val data = intent.getStringExtra(EXTRA_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_SCAN received with no $EXTRA_DATA extra - ignoring")
                    return
                }
                val labelType = intent.getStringExtra(EXTRA_LABEL_TYPE) ?: ""

                val event = if (source.equals("RFID", ignoreCase = true) ||
                    labelType.startsWith("LABEL-TYPE-RFID", ignoreCase = true)) {
                    ScanEvent.RfidTag(tagId = data, timestamp = Instant.now())
                } else {
                    ScanEvent.Barcode(value = data, format = labelType, timestamp = Instant.now())
                }
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            ACTION_CHAINWAY_BARCODE -> {
                val data = intent.getStringExtra(EXTRA_CHAINWAY_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_CHAINWAY_BARCODE received with no '$EXTRA_CHAINWAY_DATA' extra - ignoring")
                    return
                }
                val event = ScanEvent.Barcode(value = data, format = "", timestamp = Instant.now())
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            ACTION_CHAINWAY_RFID -> {
                val data = intent.getStringExtra(EXTRA_CHAINWAY_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_CHAINWAY_RFID received with no '$EXTRA_CHAINWAY_DATA' extra - ignoring")
                    return
                }
                val event = ScanEvent.RfidTag(tagId = data, timestamp = Instant.now())
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            else -> Log.w(TAG, "onReceive: unrecognized action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "DataWedgeReceiver"
        const val ACTION_SCAN = "com.mitas.ppnam.station2aa.ACTION_SCAN"
        const val EXTRA_DATA = "com.symbol.datawedge.data_string"
        const val EXTRA_SOURCE = "com.symbol.datawedge.source"
        const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"

        // Chainway RFID/barcode reader broadcasts — the actual hardware in use.
        const val ACTION_CHAINWAY_BARCODE = "com.scanner.broadcast"
        const val ACTION_CHAINWAY_RFID = "com.rscja.scanner.action.scanner.RFID"
        const val EXTRA_CHAINWAY_DATA = "data"
    }
}
