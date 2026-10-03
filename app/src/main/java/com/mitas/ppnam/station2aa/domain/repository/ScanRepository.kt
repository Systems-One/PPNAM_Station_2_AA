package com.mitas.ppnam.station2aa.domain.repository

import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import kotlinx.coroutines.flow.SharedFlow

interface ScanRepository {
    val scanEvents: SharedFlow<ScanEvent>
}
