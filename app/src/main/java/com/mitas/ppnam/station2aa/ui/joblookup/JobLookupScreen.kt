package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.BrandPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobLookupScreen(
    onJobFound: (jobCard: String) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    viewModel: JobLookupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // With the keyboard up, Back closes it rather than leaving and losing the typed number.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) { keyboard?.hide(); focusManager.clearFocus() } else onBack()
    }

    // Re-read on every resume (first entry, back from Detail, and return from background), and
    // accept scans only while this screen is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { viewModel.setLookupScreenActive(true); viewModel.refreshList() }
                Lifecycle.Event.ON_PAUSE -> viewModel.setLookupScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setLookupScreenActive(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToDetail.collect { jobCard -> onJobFound(jobCard) }
    }

    AppScaffold(
        title = "Job Cards",
        status = connectionStatus,
        onBack = onBack,
        onSettings = onSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.lookupInFlight || state.listLoading,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Production order number") },
                supportingText = { Text("Scan the job card or type the number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookup(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandPrimary, focusedLabelColor = BrandPrimary, cursorColor = BrandPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.lookup(input) },
                enabled = input.isNotBlank() && !state.lookupInFlight,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.lookupInFlight) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Look up")
                }
            }
            state.lookupError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Text("Jobs on Station 2", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            // With jobs showing, a failed refresh would otherwise be invisible: the list is the
            // last good one. One muted line says so without displacing it.
            if (state.jobs.isNotEmpty() && state.listError != null) {
                Text(
                    state.listError!!, color = TextMuted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                state.jobs.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.jobs, key = { it.jobCard }) { job ->
                        StatusCard(
                            tone = if (job.closed) StatusTone.Idle else StatusTone.Running,
                            onClick = { onJobFound(job.jobCard) },
                            enabled = !state.lookupInFlight,
                        ) { accent ->
                            Text(job.jobCard, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text(
                                buildString {
                                    append("${job.requiredMixes} ${if (job.requiredMixes == 1) "mix" else "mixes"}")
                                    if (job.closed) append(" · closed")
                                },
                                style = MaterialTheme.typography.labelMedium, color = accent,
                            )
                            if (job.product.isNotBlank()) {
                                Text(
                                    job.product, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                state.listError != null ->
                    Text(state.listError!!, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                !state.listLoading ->
                    Text("No jobs yet. Look one up to load it.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
