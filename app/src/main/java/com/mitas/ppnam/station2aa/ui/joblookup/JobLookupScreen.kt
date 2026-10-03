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
import androidx.compose.ui.Alignment
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
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.OnBrandPrimary
import com.mitas.ppnam.station2aa.ui.theme.brandButtonColors
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
        val submit = {
            focusManager.clearFocus()
            keyboard?.hide()
            viewModel.lookup(input)
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                // Digits only, capped: a 60-digit value was accepted and sent to the station
                // (audit S2-13). SAP production order numbers are at most 12 digits.
                onValueChange = { v -> if (v.length <= JOB_CARD_MAX_DIGITS && v.all { it in '0'..'9' }) input = v },
                label = { Text("Production order number") },
                supportingText = { Text("Scan the job card or type the number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandTint, focusedLabelColor = BrandTint, cursorColor = BrandTint,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = submit,
                colors = brandButtonColors(),
                enabled = input.isNotBlank() && !state.lookupInFlight,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.lookupInFlight) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = OnBrandPrimary)
                } else {
                    Text("Look up")
                }
            }
            state.lookupError?.let {
                Spacer(Modifier.height(8.dp))
                ErrorWithRetry(
                    message = it, enabled = !state.lookupInFlight, onRetry = viewModel::retryLookup,
                    showRetry = state.lookupRetryable,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("Jobs on Station 2", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            // With jobs showing, a failed refresh would otherwise be invisible: the list is the
            // last good one. One muted line says so without displacing it.
            if (state.jobs.isNotEmpty() && state.listError != null) {
                ErrorWithRetry(
                    message = state.listError!!, enabled = !state.listLoading,
                    onRetry = viewModel::refreshList, color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                state.jobs.isNotEmpty() -> LazyColumn(
                    // weight(fill = false): the list takes what is left, never squeezes to a strip.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    ErrorWithRetry(message = state.listError!!, enabled = !state.listLoading, onRetry = viewModel::refreshList)
                !state.listLoading ->
                    Text("No jobs yet. Look one up to load it.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Job cards are SAP production order numbers: never more than 12 digits. */
internal const val JOB_CARD_MAX_DIGITS = 12

/**
 * An error line with the S3-style explicit Retry. Timeouts used to leave only "re-tap the
 * button" as a hint, and after a scan there was nothing to re-tap (audit S2-05, static-08).
 */
@Composable
private fun ErrorWithRetry(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
    showRetry: Boolean = true,
    color: androidx.compose.ui.graphics.Color = DangerRed,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message, color = color, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (showRetry) OutlinedButton(onClick = onRetry, enabled = enabled) { Text("Retry") }
    }
}
