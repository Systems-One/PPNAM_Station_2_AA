package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.domain.usecase.PREPARE_REASON_MAX
import com.mitas.ppnam.station2aa.domain.usecase.PrepareForm
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.joblookup.countsLine
import com.mitas.ppnam.station2aa.ui.joblookup.summaryLine
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(
    onBack: () -> Unit,
    onOpenPrep: (String) -> Unit,
    onPrepared: (String) -> Unit,
    viewModel: PrepareViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.navigateToPrep.collect(onPrepared) }

    val form = state.form
    if (confirming && form != null) {
        val excluded = form.prompts.map { it.code }.filter { form.answers[it] == false }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Prepare ${form.mixCount} ${if (form.mixCount == 1) "mix" else "mixes"}?", color = TextPrimary) },
            text = {
                Text(
                    if (excluded.isEmpty()) "Job ${form.jobCard}. Nothing excluded."
                    else "Job ${form.jobCard}. Excluded: ${excluded.joinToString(", ")}.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; viewModel.submit() }) { Text("Prepare") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            containerColor = GraphiteSurface,
        )
    }

    AppScaffold(
        title = "Prepare ${viewModel.jobCard}",
        status = connectionStatus,
        onBack = onBack,
        loading = state.loading || state.sending,
    ) { padding ->
        if (form == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.loading) {
                    CircularProgressIndicator()
                } else {
                    Text(state.message ?: "Station 2 sent no job to prepare", color = DangerRed)
                    OutlinedButton(onClick = viewModel::reload, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
            }
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        listOf(form.jobCard, state.product).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                    )
                    state.progress?.let { Text(it.countsLine(), style = MaterialTheme.typography.bodySmall, color = TextMuted) }
                }
            }
            if (state.pending == null && state.blocking != null) {
                item {
                    Text(
                        if (state.managerHold) "A manager must reconcile this at the station before you prepare again."
                        else "An earlier prepare for this job is being recovered — check Home",
                        color = WarningOrange,
                    )
                }
            }
            state.pending?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = GraphiteSurface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Station 2 has not confirmed this prepare. Don't prepare again — Retry.",
                                color = WarningOrange,
                            )
                            Button(onClick = viewModel::retryPending, enabled = !state.sending) { Text("Retry") }
                        }
                    }
                }
            }
            state.message?.let { item { Text(it, color = WarningOrange, style = MaterialTheme.typography.bodyMedium) } }
            if (state.activePreparations.isNotEmpty()) {
                item {
                    Text(
                        "This job already has ${state.activePreparations.size} active preparation(s). " +
                            "Open one to continue it — Prepare creates a new allocation.",
                        color = WarningOrange, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(state.activePreparations, key = { "active-${it.id}" }) { prep ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenPrep(prep.id) },
                        headlineContent = { Text(prep.id, color = TextPrimary) },
                        supportingContent = { Text(prep.summaryLine(), color = TextMuted) },
                    )
                }
            }
            item { MixCountField(form, viewModel::onMixCount) }
            item { HorizontalDivider() }
            if (form.prompts.isEmpty()) {
                item { Text("No ingredient choices for this job.", color = TextMuted) }
            }
            items(form.prompts, key = { "choice-${it.code}" }) { prompt ->
                val answer = form.answers[prompt.code]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(prompt.code, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                    if (prompt.description.isNotBlank()) Text(prompt.description, color = TextMuted)
                    if (prompt.note.isNotBlank()) Text(prompt.note, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    // Neither segment is selected until the operator chooses (contract §7: explicit).
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = answer == true,
                            onClick = { viewModel.onAnswer(prompt.code, true) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Include") }
                        SegmentedButton(
                            selected = answer == false,
                            onClick = { viewModel.onAnswer(prompt.code, false) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Exclude") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = viewModel::onReason,
                    label = { Text("Reason (optional)") },
                    supportingText = { Text("${form.reason.length}/$PREPARE_REASON_MAX") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.problems.forEach {
                        Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    val n = form.mixCount
                    Button(
                        onClick = { confirming = true },
                        enabled = form.problems.isEmpty() && !state.sending && state.blocking == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (n != null) "Prepare $n ${if (n == 1) "mix" else "mixes"}" else "Prepare mixes") }
                }
            }
        }
    }
}

@Composable
private fun MixCountField(form: PrepareForm, onMixCount: (String) -> Unit) {
    val current = form.mixCount
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mixes to prepare (1 to ${form.available})", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onMixCount(((current ?: 2) - 1).coerceAtLeast(1).toString()) },
                enabled = form.available > 0 && (current ?: 2) > 1,
            ) { Text("−") }
            OutlinedTextField(
                value = form.mixCountText,
                onValueChange = { onMixCount(it.filter(Char::isDigit).take(9)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(96.dp),
            )
            OutlinedButton(
                onClick = { onMixCount(((current ?: 0) + 1).coerceAtMost(form.available).toString()) },
                enabled = form.available > 0 && (current ?: 0) < form.available,
            ) { Text("+") }
        }
    }
}
