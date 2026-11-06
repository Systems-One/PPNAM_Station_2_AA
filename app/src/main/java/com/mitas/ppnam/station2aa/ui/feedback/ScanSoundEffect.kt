package com.mitas.ppnam.station2aa.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mitas.ppnam.station2aa.domain.feedback.ScanSound
import kotlinx.coroutines.flow.Flow

/**
 * Plays the scan sounds a ViewModel emits while this screen is composed (suite rule S2). Draws
 * nothing. Sounds emitted while the screen is not showing are dropped, never replayed later.
 */
@Composable
fun ScanSoundEffect(sounds: Flow<ScanSound>) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(sounds) {
        ScanFeedback.warmUp(context)
        sounds.collect { ScanFeedback.play(context, it) }
    }
}
