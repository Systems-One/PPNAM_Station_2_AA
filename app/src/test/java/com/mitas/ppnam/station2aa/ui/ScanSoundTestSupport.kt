package com.mitas.ppnam.station2aa.ui

import com.mitas.ppnam.station2aa.domain.feedback.ScanSound
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Collects every scan sound [sounds] emits from now on, for the rest of the test. */
fun TestScope.heard(sounds: Flow<ScanSound>): List<ScanSound> {
    val heard = mutableListOf<ScanSound>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sounds.collect { heard += it } }
    return heard
}
