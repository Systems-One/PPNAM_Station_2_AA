package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mitas.ppnam.station2aa.BuildConfig
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class UpgradeGateViewModel @Inject constructor(
    mqttRepository: MqttRepository,
) : ViewModel() {
    private val repo = mqttRepository
    val upgradeRequired: StateFlow<Boolean> = mqttRepository.upgradeRequired
    fun clearLatch() = repo.clearUpgradeRequired()
}

/**
 * The app-level `client_upgrade_required` gate. Rendered once above the NavHost so it blocks
 * EVERY screen — the transport's latch never resets, so neither does this dialog until "Close app" clears it.
 *
 * It is blocking, but not a trap: with no button at all the operator's only way out was Home +
 * kill the app (audit S2-06). "Close app" finishes the Activity. The text names the installed
 * version instead of a hard-coded "4.0 reader build" that was already wrong for v1.2.0.
 */
@Composable
fun UpgradeRequiredGate(
    onCloseApp: () -> Unit,
    viewModel: UpgradeGateViewModel = hiltViewModel(),
) {
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "This version of Station 2 (v${BuildConfig.VERSION_NAME}) is too old for the " +
                        "station. Ask a supervisor to install the latest version, then log in again.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // The process outlives the Activity, so the latch must be cleared or a relaunch
                    // would show the stale gate (S2-R01).
                    viewModel.clearLatch()
                    onCloseApp()
                }) { Text("Close app", color = DangerRed) }
            },
            containerColor = GraphiteSurface,
        )
    }
}
