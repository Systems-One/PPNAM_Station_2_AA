package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.joblookup.countsLine
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import com.mitas.ppnam.station2aa.ui.theme.brandButtonColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var partSheet by remember { mutableStateOf(false) }
    var manualCode by rememberSaveable { mutableStateOf("") }

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
    val collecting = state.stage == PrepStage.Collecting
    val staged = state.staged

    // A dialog or sheet opened for one state must not outlive it.
    LaunchedEffect(state.canConfirm) { if (!state.canConfirm) confirming = false }
    LaunchedEffect(state.stage) { partSheet = false }
    LaunchedEffect(staged == null) { if (staged == null) partSheet = false }
    val sheetOpen = partSheet && staged != null && collecting
    // While the sheet is open, a bus scan must not silently replace the picked material and quantity.
    LaunchedEffect(sheetOpen) { viewModel.setPartSheetOpen(sheetOpen) }

    state.offer?.let { offer -> ReplacementDialog(offer, state, viewModel::acceptOffer, viewModel::declineOffer) }

    if (confirming && state.canConfirm) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Confirm collection?", color = TextPrimary) },
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

    if (sheetOpen) {
        PartBagSheet(
            state = state,
            onDismiss = { partSheet = false },
            onPickMaterial = viewModel::stagePart,
            onQuantity = viewModel::onPartQuantity,
            onRecord = { partSheet = false; viewModel.recordStaged() },
        )
    }

    AppScaffold(
        title = shortPrepId(prepId),
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.loading || state.inFlight,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(prepId, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    if (prep != null) {
                        val detail = state.snapshot?.lookup?.detail
                        Text(
                            listOf(prep.jobId, detail?.product.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(onClick = {}, label = { Text(prep.stageRaw) })
                            Text(
                                "${prep.mixCount} ${if (prep.mixCount == 1) "mix" else "mixes"}",
                                color = TextMuted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        detail?.mixProgress?.let {
                            Text(it.countsLine(), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }
            state.readError?.let { error ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = DangerRed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Retry") }
                    }
                }
            }
            items(state.blocking, key = { "blocking-${it.messageId}" }) { command ->
                val ownSession = command.status == PendingStatus.Unresolved &&
                    command.sessionId == session?.operatorSessionId
                BlockingBanner(command, ownSession, !state.inFlight) { viewModel.retry(command) }
            }
            state.message?.let { message -> item { MessageBanner(message) } }

            if (prep == null) {
                if (state.loading) item { CircularProgressIndicator() }
            } else {
                item { HorizontalDivider() }
                items(prep.materials, key = { "material-${it.code}" }) { material ->
                    MaterialRow(
                        material,
                        onClick = if (state.canScan && !material.excluded) ({ viewModel.stagePart(material.code) }) else null,
                        picked = (staged as? Staged.PartBag)?.materialCode == material.code,
                    )
                }
                when (state.stage) {
                    PrepStage.Collecting -> item {
                        ScanCard(
                            staged = staged,
                            enabled = state.canScan,
                            manualCode = manualCode,
                            onManualCode = { manualCode = it.trim() },
                            onUseCode = { viewModel.onScan(manualCode); manualCode = "" },
                            onRecordBag = { viewModel.stageWholeBag(); viewModel.recordStaged() },
                            onRecordPart = { partSheet = true },
                            onClear = viewModel::clearStaged,
                        )
                    }
                    PrepStage.Completed, PrepStage.Cancelled -> item {
                        Text("${prep.stageRaw} — history only", color = TextMuted)
                    }
                    PrepStage.AwaitingConfirmation, null, PrepStage.Unknown -> Unit
                    else -> item {
                        Text(
                            "Ingredients confirmed — next: scan a permitted mixer (coming in a later release)",
                            color = TextMuted,
                        )
                    }
                }
                if (state.canConfirm) {
                    item {
                        Button(
                            onClick = { confirming = true },
                            colors = brandButtonColors(),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("Confirm collection") }
                    }
                }
                val exceptions = state.snapshot?.collectionExceptions.orEmpty()
                if (exceptions.isNotEmpty()) {
                    item { Text("Exceptions", style = MaterialTheme.typography.titleSmall, color = TextPrimary) }
                    items(exceptions, key = { "exception-${it.id}" }) { ExceptionCard(it) }
                }
            }
        }
    }
}

/**
 * Spec §6: Station 2 never ran a capture/confirm and never will. Only an explicit tap sends a
 * replacement (a new command); it is enabled only while a tap of its own kind would be.
 */
@Composable
private fun ReplacementDialog(
    offer: ReplacementOffer,
    state: PreparationUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val (accept, decline, enabled) = when (offer) {
        is ReplacementOffer.Capture -> Triple("Record bag", "Don't record", state.canScan)
        is ReplacementOffer.Confirm -> Triple("Confirm collection", "Don't confirm", state.canConfirm)
    }
    val stillToCollect = state.snapshot?.preparation?.materials.orEmpty()
        .filter { !it.excluded && !it.isComplete }
        .joinToString(", ") { "${it.name} ${it.remaining.plainText()} ${it.unit}".trim() }
    AlertDialog(
        // An accidental tap outside must neither send nor dismiss: the operator chooses.
        onDismissRequest = {},
        title = { Text("Not received by Station 2", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(offer.prompt(), color = TextPrimary)
                if (offer is ReplacementOffer.Capture && stillToCollect.isNotEmpty()) {
                    Text("Still to collect: $stillToCollect", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (!enabled) {
                    Text(
                        "Not possible right now — resolve the unanswered command or wait for Station 2.",
                        color = WarningOrange, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept, enabled = enabled) { Text(accept) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(decline) } },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun BlockingBanner(command: PendingCommand, retryable: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = GraphiteSurface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (retryable) {
                Text(
                    "Station 2 has not confirmed the last ${command.action}. Do not scan again — Retry.",
                    color = WarningOrange,
                )
                Button(onClick = onRetry, enabled = enabled) { Text("Retry") }
            } else {
                Text("A manager must reconcile this at the station before you continue.", color = WarningOrange)
            }
        }
    }
}

@Composable
private fun MessageBanner(message: OutcomeMessage) {
    when (message) {
        is OutcomeMessage.Recorded -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.text, color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
            message.warning?.let { Text(it, color = WarningOrange, style = MaterialTheme.typography.bodyMedium) }
        }
        is OutcomeMessage.Rejected -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.text, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            message.records.forEach { record ->
                record.lines().forEach { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        is OutcomeMessage.Info -> Text(message.text, color = WarningOrange, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MaterialRow(material: PrepMaterial, onClick: (() -> Unit)?, picked: Boolean) {
    val muted = material.excluded
    Card(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                material.name + if (picked) " ✓" else "",
                color = if (muted) TextMuted else TextPrimary, style = MaterialTheme.typography.bodyLarge,
            )
            Text(material.code, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Text(
                material.progressLine(),
                color = if (muted) TextMuted else TextPrimary, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ScanCard(
    staged: Staged?,
    enabled: Boolean,
    manualCode: String,
    onManualCode: (String) -> Unit,
    onUseCode: () -> Unit,
    onRecordBag: () -> Unit,
    onRecordPart: () -> Unit,
    onClear: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = GraphiteSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan a pallet tag or label", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            if (staged != null) {
                Text(staged.code, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRecordBag, enabled = enabled, colors = brandButtonColors()) { Text("Record bag") }
                    OutlinedButton(onClick = onRecordPart, enabled = enabled) { Text("Record part bag") }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = onManualCode,
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (enabled && manualCode.isNotBlank()) onUseCode() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onUseCode, enabled = enabled && manualCode.isNotBlank()) { Text("Use code") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PartBagSheet(
    state: PreparationUiState,
    onDismiss: () -> Unit,
    onPickMaterial: (String) -> Unit,
    onQuantity: (String) -> Unit,
    onRecord: () -> Unit,
) {
    val part = state.staged as? Staged.PartBag
    val materials = state.snapshot?.preparation?.materials.orEmpty().filter { !it.excluded && !it.isComplete }
    val material = materials.firstOrNull { it.code == part?.materialCode }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GraphiteSurface) {
        Column(Modifier.padding(16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Record part bag — ${state.staged?.code.orEmpty()}", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                materials.forEach { m ->
                    FilterChip(
                        selected = m.code == part?.materialCode,
                        onClick = { onPickMaterial(m.code) },
                        label = { Text(m.name) },
                    )
                }
            }
            if (material != null && part != null) {
                OutlinedTextField(
                    value = part.quantityText,
                    onValueChange = onQuantity,
                    label = { Text("Quantity (${material.unit})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                val remaining = material.remaining.plainText()
                SuggestionChip(
                    onClick = { onQuantity(remaining) },
                    label = { Text("Remaining: $remaining ${material.unit}".trim()) },
                )
                Button(
                    onClick = onRecord,
                    enabled = state.canScan && part.quantityText.isNotBlank(),
                    colors = brandButtonColors(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Record part bag") }
            } else {
                Text("Pick the ingredient this bag is for", color = TextMuted)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExceptionCard(record: CollectionExceptionRecord) {
    Card(colors = CardDefaults.cardColors(containerColor = GraphiteSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            record.lines().forEachIndexed { i, line ->
                Text(
                    line,
                    color = if (i == 0) TextPrimary else TextMuted,
                    style = if (i == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
