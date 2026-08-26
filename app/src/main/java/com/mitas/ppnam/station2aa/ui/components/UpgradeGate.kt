package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
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
    val upgradeRequired: StateFlow<Boolean> = mqttRepository.upgradeRequired
}

/**
 * The app-level `client_upgrade_required` gate (contract §10: "Block Mixing and require
 * the 4.0 reader build"). Rendered once above the NavHost so it blocks EVERY screen —
 * the transport's latch never resets, so neither does this dialog; only a new build
 * clears the condition.
 */
@Composable
fun UpgradeRequiredGate(viewModel: UpgradeGateViewModel = hiltViewModel()) {
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "Station 2 requires the 4.0 reader build for this workflow. " +
                        "Install the update, then log in again.",
                    color = TextMuted,
                )
            },
            confirmButton = {},
            containerColor = GraphiteSurface,
        )
    }
}
