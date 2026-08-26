package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

/**
 * A ready mix waiting is the strongest signal (act now); active cycles alone mean the area is
 * working but has nothing new for the operator yet; neither means nothing to report.
 */
internal fun areaTone(mixes: Int, cycles: Int): StatusTone = when {
    mixes > 0 -> StatusTone.Ready
    cycles > 0 -> StatusTone.Running
    else -> StatusTone.Idle
}

@Composable
fun MixingAreaPickerScreen(
    pendingCollectionId: String?,
    onAreaChosen: (MixingArea) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    // Re-fetches on first entry AND every time this screen becomes visible again -
    // including the app being backgrounded (interrupted, screen-locked) and resumed later,
    // which a plain LaunchedEffect(Unit) would miss since the composable never leaves
    // composition in that case.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, pendingCollectionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadAreaPicker(pendingCollectionId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }

    AppScaffold(
        title = "Mixing",
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = uiState is MixingBoardUiState.Loading,
    ) { padding ->
        when (val state = uiState) {
            // The scaffold's bar is the whole loading affordance — nothing to draw here.
            is MixingBoardUiState.Loading -> Unit

            is MixingBoardUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.message, color = DangerRed)
                Button(onClick = { viewModel.loadAreaPicker(pendingCollectionId) }) { Text("Retry") }
            }

            is MixingBoardUiState.AreaPicker -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.pendingCollectionId?.let { pending ->
                    item {
                        StatusCard(tone = StatusTone.Running) {
                            Text(
                                "$pending ready to mix — pick an area",
                                color = AmberPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(MixingArea.entries) { area ->
                    val equipment = state.overview.equipment.filter { it.area == area }
                    val available = equipment.count { it.isEnabled && it.status == "Available" }
                    val cycles = state.overview.activeCycles.count { it.area == area }
                    val mixes = state.overview.readyMixes.count { it.area == area }
                    StatusCard(
                        tone = areaTone(mixes = mixes, cycles = cycles),
                        onClick = { onAreaChosen(area) },
                    ) { accent ->
                        Text(area.display, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(area.description, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$available machine(s) available · $cycles active cycle(s) · $mixes ready mix(es)",
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                        )
                    }
                }
            }

            // The Board state belongs to the area-board route; nothing to render here.
            is MixingBoardUiState.Board -> Unit
        }
    }
}
