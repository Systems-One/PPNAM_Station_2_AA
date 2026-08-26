package com.mitas.ppnam.station2aa.ui.rfid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.components.ScanPromptCard
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun RfidRecoveryScreen(
    onDone: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: RfidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListening() }

    AppScaffold(
        title = "RFID Pallet Lookup",
        status = connectionStatus,
        onBack = onBack,
        loading = uiState is RfidUiState.Loading || uiState is RfidUiState.Recovering
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = uiState) {
                    is RfidUiState.Idle -> {
                        ScanPromptCard(message = "Scan an RFID tag to look up a pallet")
                    }
                    is RfidUiState.Loading, is RfidUiState.Recovering -> {
                        // The scaffold's bar carries the "working" signal; the caption stays
                        // because lookup and recovery are different waits with different stakes.
                        Text(
                            text = if (state is RfidUiState.Recovering) "Recovering pallet…" else "Looking up pallet…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                    is RfidUiState.Result -> {
                        val pallet = state.pallet
                        val accent = when {
                            !pallet.found -> DangerRed
                            pallet.usable -> SuccessGreen
                            else -> AmberPrimary
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(accent)
                                )
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (pallet.usable) Icons.Filled.CheckCircle else Icons.Filled.Info,
                                            null,
                                            tint = accent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = when {
                                                !pallet.found -> "Pallet Not Found"
                                                pallet.usable -> "Pallet Ready"
                                                else -> "Pallet Not Usable"
                                            },
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = accent
                                        )
                                    }
                                    if (pallet.found) {
                                        Spacer(Modifier.height(12.dp))
                                        LabelValueRow("Tag ID", pallet.palletRfidTag)
                                        LabelValueRow("Pallet ID", pallet.palletId)
                                        LabelValueRow("Product", pallet.productName)
                                        LabelValueRow("Batch No", pallet.batchNumber)
                                        LabelValueRow("Remaining", "${pallet.remainingQuantity} ${pallet.unit}")
                                        // The response has always carried remainingBags and the
                                        // screen showed only kg — in a bag-driven workflow the
                                        // bag count is the more actionable of the two.
                                        LabelValueRow("Remaining bags", "%.2f".format(pallet.remainingBags))
                                        LabelValueRow("Location", pallet.localLocation)
                                        LabelValueRow("State", pallet.palletState.name)
                                        if (pallet.blocked) {
                                            LabelValueRow("Blocked", "Yes")
                                        }
                                    } else {
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            "This tag is not a known pallet. Resolve it at Station 1 first.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is RfidUiState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.10f)),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.30f))
                        ) {
                            Text(
                                text = state.message,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            when (val state = uiState) {
                is RfidUiState.Result -> {
                    // Station 2 decides recoverability. Offer the action only when it says so, and
                    // note a successful recovery still won't clear a block — the result will say.
                    if (state.pallet.recoverable) {
                        Button(
                            onClick = { viewModel.recoverCurrentPallet() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("Recover to Holding") }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Scan Another") }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                is RfidUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Try Again") }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                else -> Unit
            }
        }
    }
}
