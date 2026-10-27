package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import com.mitas.ppnam.station2aa.domain.model.RajooJob
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.preparation.BannerGreenBg
import com.mitas.ppnam.station2aa.ui.preparation.CollectActions
import com.mitas.ppnam.station2aa.ui.preparation.CollectSheet
import com.mitas.ppnam.station2aa.ui.preparation.CollectStep
import com.mitas.ppnam.station2aa.ui.preparation.DecisionActions
import com.mitas.ppnam.station2aa.ui.preparation.DecisionScope
import com.mitas.ppnam.station2aa.ui.preparation.HoldBanners
import com.mitas.ppnam.station2aa.ui.preparation.InfoLine
import com.mitas.ppnam.station2aa.ui.preparation.IngredientDecisionDialog
import com.mitas.ppnam.station2aa.ui.preparation.IngredientRow
import com.mitas.ppnam.station2aa.ui.preparation.ResultBannerView
import com.mitas.ppnam.station2aa.ui.preparation.ScanBox
import com.mitas.ppnam.station2aa.ui.preparation.StatusBanner
import com.mitas.ppnam.station2aa.ui.preparation.plainText
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/**
 * One Rajoo job card: the cumulative full-job rows, the session on its machine, and the one next
 * action — answer the first-session choices, scan the machine to start, scan pallets while the
 * session runs, finish the session, close the job once everything is collected.
 */
@Composable
fun RajooJobScreen(
    jobCard: String,
    onBack: () -> Unit,
    viewModel: RajooJobViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

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

    val collect = state.collect
    val inSheet = collect.step != CollectStep.Idle
    BackHandler(enabled = inSheet) { viewModel.cancelCollect() }

    state.confirm?.let { confirm -> ConfirmDialog(confirm, state, viewModel) }
    state.decision?.let { decision ->
        IngredientDecisionDialog(
            decision, DecisionScope.RajooJob, state.busy,
            DecisionActions(viewModel::onDecisionKind, viewModel::onDecisionTotal, viewModel::onDecisionReason,
                viewModel::sendDecision, viewModel::cancelDecision),
        )
    }

    val job = state.job
    AppScaffold(
        title = if (inSheet) "Collect" else listOf("Rajoo $jobCard", job?.product.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
        status = connectionStatus,
        onBack = if (inSheet) viewModel::cancelCollect else onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = collect.loading || collect.inFlight,
    ) { padding ->
        val body = Modifier.padding(padding)
        if (inSheet) {
            CollectSheet(
                collect, session?.operatorSessionId,
                CollectActions(
                    onAmount = viewModel::onAmountChange,
                    onReason = viewModel::onReasonChange,
                    onSubmit = viewModel::submit,
                    onAddToMix = viewModel::addToMix,
                    onCancel = viewModel::cancelCollect,
                    onPickMaterial = {},
                    onRetry = viewModel::retry,
                    onPickSubstitute = viewModel::pickSubstitute,
                ),
                body,
                scope = "job",
            )
        } else {
            JobHome(state, session?.operatorSessionId, viewModel, body)
        }
    }
}

@Composable
private fun JobHome(state: RajooUiState, sessionId: String?, viewModel: RajooJobViewModel, modifier: Modifier) {
    val collect = state.collect
    val job = state.job
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job != null) item { SessionHeader(job, state) }
            collect.readError?.let { error ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = DangerRed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = viewModel::refresh, enabled = !collect.loading) { Text("Retry") }
                    }
                }
            }
            if (collect.blocking.isNotEmpty()) {
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HoldBanners(collect, sessionId, viewModel::retry) } }
            } else {
                collect.banner?.let { b -> item { ResultBannerView(b, null) } }
                collect.message?.let { item { InfoLine(it) } }
            }
            if (job == null) {
                if (collect.loading) item { CircularProgressIndicator() }
            } else {
                item { JobProgress(job) }
                if (state.canDecide) {
                    item {
                        Text(DecisionScope.RajooJob.hint, color = TextMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(job.materials, key = { "material-${it.code}" }) { row ->
                    val tappable = state.canDecide && !row.excluded
                    Box(if (tappable) Modifier.clickable { viewModel.openDecision(row.code) } else Modifier) {
                        IngredientRow(row, highlighted = false)
                    }
                }
                val exceptions = state.snapshot?.collectionExceptions.orEmpty()
                if (exceptions.isNotEmpty()) {
                    item {
                        Text("Exceptions (${exceptions.size}) — logged for manager review", color = TextMuted,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (job != null) ActionArea(job, state, viewModel)
    }
}

@Composable
private fun SessionHeader(job: RajooJob, state: RajooUiState) {
    val machineName = state.machine?.name ?: job.machineId ?: "—"
    val (label, color) = when {
        job.closed -> "Closed" to TextMuted
        job.hasActiveSession -> "Session running" to SuccessGreen
        else -> "No session running" to WarningOrange
    }
    Column(
        Modifier.fillMaxWidth().background(GraphiteSurface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(machineName, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(label, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        job.activeSessionId?.let { Text("Session …${it.takeLast(8)}", color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        Text("Full job card targets — every session adds to the same totals", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JobProgress(job: RajooJob) {
    val included = job.materials.filterNot { it.excluded }
    val done = included.count { it.isComplete }
    Text(
        "$done of ${included.size} ingredient${if (included.size == 1) "" else "s"} fully collected",
        color = TextMuted, style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The one scan target, pinned at the bottom. What the Rajoo machine code does follows the job:
 * start a session, end the running one, or close the completed job (process flow page 4).
 */
@Composable
private fun ActionArea(job: RajooJob, state: RajooUiState, viewModel: RajooJobViewModel) {
    val machineName = state.machine?.name ?: "Rajoo"
    val idle = !state.busy && state.collect.step == CollectStep.Idle
    when {
        job.closed -> StatusBanner("✓", "Job card closed", SuccessGreen, BannerGreenBg,
            details = listOf("Session, bag and pallet-source history is retained" to false))
        job.hasActiveSession -> ScanBox(
            "Scan the pallet tag for each bag", "$machineName machine code ends the session",
            state.collect.canScan, state.collect.blocking.isNotEmpty(), viewModel::onScan,
        )
        job.allCollected -> ScanBox(
            "Job card work complete", "$machineName machine code closes the job card",
            idle, state.collect.blocking.isNotEmpty(), viewModel::onScan,
        )
        else -> ScanBox(
            "Scan the $machineName machine code", "to start a session",
            idle, state.collect.blocking.isNotEmpty(), viewModel::onScan,
        )
    }
}

@Composable
private fun ConfirmDialog(confirm: RajooConfirm, state: RajooUiState, viewModel: RajooJobViewModel) {
    val machineName = state.machine?.name ?: "the Rajoo machine"
    val prompts = if (confirm == RajooConfirm.Start) state.choicePrompts else emptyList()
    val (title, text, action) = when (confirm) {
        RajooConfirm.Start -> Triple(
            "Start a session on $machineName?",
            if (prompts.isEmpty()) "Scan the pallet tag for every bag you add. Bags add to the job card's running totals."
            else "First session: choose Include or Exclude for each listed ingredient. The choices are kept for every later session.",
            "Start session",
        )
        RajooConfirm.Finish -> Triple(
            "End the session on $machineName?",
            "Everything captured stays counted. The job card stays open for the next session.",
            "End session",
        )
        RajooConfirm.Close -> Triple(
            "Close job card ${state.job?.id}?",
            "All material is collected. Closing ends Rajoo capture for this job card; its history is kept.",
            "Close job card",
        )
    }
    val problems = if (confirm == RajooConfirm.Start) state.startProblems else emptyList()
    AlertDialog(
        onDismissRequest = viewModel::cancelConfirm,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, color = TextMuted)
                prompts.forEach { prompt -> ChoiceRow(prompt, state.choices[prompt.code], viewModel::onChoice) }
                // Only the all-excluded problem is worth repeating once every code is answered.
                problems.filterNot { it.startsWith("Choose ") }.forEach {
                    Text(it, color = WarningOrange, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirm, enabled = problems.isEmpty()) {
                Text(action, color = if (problems.isEmpty()) BrandTint else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelConfirm) { Text("Cancel") } },
        containerColor = GraphiteSurface,
    )
}

/** One catalog-matched code: neither segment is selected until the operator chooses (contract §7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(prompt: ChoicePrompt, answer: Boolean?, onChoice: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(prompt.code, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        if (prompt.description.isNotBlank()) Text(prompt.description, color = TextMuted)
        if (prompt.note.isNotBlank()) Text(prompt.note, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = answer == true, onClick = { onChoice(prompt.code, true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Include") }
            SegmentedButton(
                selected = answer == false, onClick = { onChoice(prompt.code, false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Exclude") }
        }
    }
}

