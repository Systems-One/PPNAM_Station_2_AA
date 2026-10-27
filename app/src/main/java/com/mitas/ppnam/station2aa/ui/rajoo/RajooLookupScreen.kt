package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.preparation.ScanBox
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBackground
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.brandButtonColors

/**
 * Rajoo entry: look up (or resume) a job card on the Rajoo machine. The only enabled machine is
 * used without a scan; only when Station 2 lists several does the operator scan which one.
 */
@Composable
fun RajooLookupScreen(
    onOpenJob: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: RajooLookupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(viewModel) { viewModel.opened.collect(onOpenJob) }
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

    AppScaffold(
        title = "Rajoo",
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.loading || state.looking,
    ) { padding ->
        val machine = state.machine
        if (machine == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading && state.machines.isEmpty()) {
                    Text("Loading Rajoo machines…", color = TextMuted)
                    state.error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium) }
                    return@Column
                }
                val usable = state.machines.any { it.enabled && it.code.isNotBlank() }
                if (!usable) {
                    Text(state.error ?: RajooLookupViewModel.NO_MACHINE, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Retry") }
                    return@Column
                }
                // Several Rajoo machines are enabled: the operator says which one by scanning it.
                Text("Which Rajoo machine?", color = TextPrimary, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text("Station 2 has more than one Rajoo machine enabled. Scan the machine code before loading a job card.",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium) }
                ScanBox("Scan the Rajoo machine code", null, enabled = true, paused = false,
                    onUseCode = viewModel::onMachineScan)
            }
            return@AppScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (state.machineAutoSelected) {
                        Text("Machine: ${machine.name}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("✓ At ${machine.name}", color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = viewModel::changeMachine) { Text("Rescan") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Look up the job card", color = TextPrimary, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                    Text("Rajoo collects the full Production Order BOM; one job card may take several sessions.",
                        color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.jobCard, onValueChange = viewModel::onJobCard, label = { Text("Job card") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium) }
                    Button(
                        onClick = viewModel::lookup, enabled = state.canLookup, colors = brandButtonColors(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(if (state.looking) "Loading…" else "Look up job card", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                }
            }
            // Only open job cards are listed; a closed one can still be opened by looking it up.
            val openJobs = state.jobs.filterNot { it.closed }
            if (openJobs.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Open Rajoo job cards — tap to resume", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                items(openJobs, key = { it.jobCard }) { job ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.looking) { viewModel.open(job) },
                        headlineContent = { Text(job.jobCard, color = TextPrimary) },
                        supportingContent = { Text(job.product.ifBlank { "—" }, color = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = GraphiteBackground),
                    )
                }
            }
        }
    }
}
