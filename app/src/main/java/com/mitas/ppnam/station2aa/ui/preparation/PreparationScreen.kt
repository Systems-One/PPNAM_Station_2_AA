package com.mitas.ppnam.station2aa.ui.preparation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

/**
 * The PREP screen. While collecting it is the simplified collect flow: [CollectHome] (ingredient
 * list + scan zone) and, once a pallet is scanned, [CollectSheet]. Later stages are the isolated
 * [NonCollectingStage].
 */
@Composable
fun PreparationScreen(
    prepId: String,
    onBack: () -> Unit,
    viewModel: PreparationViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    var confirming by remember { mutableStateOf(false) }

    // Scans act on this screen only while it is resumed; re-read on every resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { viewModel.setScreenActive(true); viewModel.refresh() }
                Lifecycle.Event.ON_PAUSE -> viewModel.setScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setScreenActive(false)
        }
    }

    val prep = state.snapshot?.preparation
    val collectFlow = state.stage == PrepStage.Collecting || state.stage == PrepStage.AwaitingConfirmation
    val inSheet = collectFlow && state.step != CollectStep.Idle

    // A dialog opened for one state must not outlive it.
    LaunchedEffect(state.canConfirm) { if (!state.canConfirm) confirming = false }

    state.offer?.let { offer -> ReplacementDialog(offer, state, viewModel::acceptOffer, viewModel::declineOffer) }

    if (confirming && state.canConfirm) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Mix complete?", color = TextPrimary) },
            text = {
                Text(
                    "Every ingredient is collected. Confirming tells Station 2 this preparation is ready for a mixer.",
                    color = TextMuted,
                )
            },
            confirmButton = { TextButton(onClick = { confirming = false; viewModel.confirm() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            containerColor = GraphiteSurface,
        )
    }

    // Back from the Collect screen cancels the pallet; it never leaves the PREP with a pallet staged.
    BackHandler(enabled = inSheet) { viewModel.cancelCollect() }

    val detail = state.snapshot?.lookup?.detail
    val title = when {
        inSheet -> "Collect"
        prep != null -> listOf(prep.jobId, detail?.product.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { shortPrepId(prepId) }
        else -> shortPrepId(prepId)
    }

    AppScaffold(
        title = title,
        status = connectionStatus,
        onBack = if (inSheet) viewModel::cancelCollect else onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.loading || state.inFlight,
    ) { padding ->
        val body = Modifier.padding(padding)
        val sessionId = session?.operatorSessionId
        when {
            inSheet -> CollectSheet(
                state, sessionId,
                CollectActions(
                    onAmount = viewModel::onAmountChange,
                    onReason = viewModel::onReasonChange,
                    onSubmit = viewModel::submit,
                    onAddToMix = viewModel::addToMix,
                    onCancel = viewModel::cancelCollect,
                    onPickMaterial = viewModel::pickFallbackMaterial,
                    onRetry = viewModel::retry,
                ),
                body,
            )
            collectFlow || prep == null -> CollectHome(
                state, sessionId,
                onRetry = viewModel::retry,
                onRefresh = viewModel::refresh,
                onUseCode = viewModel::onScan,
                onMixComplete = { confirming = true },
                modifier = body,
            )
            else -> NonCollectingStage(state, sessionId, viewModel::retry, viewModel::refresh, body)
        }
    }
}
