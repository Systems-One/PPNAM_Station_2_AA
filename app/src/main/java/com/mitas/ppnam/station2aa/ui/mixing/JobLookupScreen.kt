package com.mitas.ppnam.station2aa.ui.mixing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/**
 * Maps a job's wire status (plus its pending-approval flag) to the shared color language.
 * Pending approval takes priority over the base status — an operator scanning for what needs
 * attention should see amber immediately, not read a secondary line. `internal`, not `private`,
 * so `JobLookupScreenKtTest` can verify it directly.
 */
internal fun ActiveJobCardSummary.cardTone(): StatusTone = when {
    (pendingApprovalCount ?: 0) > 0 -> StatusTone.Warning
    status == "ReadyForMixing" -> StatusTone.Ready
    status == "Collecting" || status == "Mixing" -> StatusTone.Running
    else -> StatusTone.Idle
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onRfidLookup: () -> Unit = {},
    onOpenMixing: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val activeJobs by viewModel.activeJobs.collectAsState()
    val activeJobsError by viewModel.activeJobsError.collectAsState()
    val session by viewModel.session.collectAsState()
    var orderInput by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Job Lookup now sits below Home on the post-login back stack, so Back has somewhere real to
    // pop to — the accidental-exit hazard (and its dialog) moved to HomeScreen, which is the
    // actual root now. What's left here is purely local: with the keyboard up, Back should close
    // it rather than immediately navigate away and lose the in-progress order number.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else {
            onBack()
        }
    }

    // Re-fetches on first entry, on in-app back navigation, AND every time the screen becomes
    // visible again after the app was merely backgrounded (screen locked, operator took a call,
    // switched apps). A plain LaunchedEffect(Unit) covers the first two — it reruns on fresh
    // composition — but never fires for the third, because the composable does not leave
    // composition when the activity is backgrounded. The result was an Active Jobs list showing
    // last-fetched progress with no new request on the wire, which on a shared handheld is the
    // list an operator picks their collection from. Same mechanism as MixingAreaPickerScreen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadActiveJobs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.JOB_LOADED) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)?.let { onJobFound(it.order.docNo) }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Cards",
        status = connectionStatus,
        onBack = onBack,
        onRfidLookup = onRfidLookup,
        onSettings = onSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = isLoading
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (activeJobs.isNotEmpty()) {
                Text(
                    "Active Jobs",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // collectionId, not jobCardNumber, is the identity: several concurrent
                    // collections per job card is intended behaviour, and duplicate keys would
                    // make LazyColumn throw.
                    items(activeJobs, key = { it.collectionId.ifBlank { it.jobCardNumber } }) { job ->
                        StatusCard(
                            tone = job.cardTone(),
                            onClick = { viewModel.lookupJob(job.jobCardNumber, job.collectionId) },
                            enabled = !isLoading,
                        ) { accent ->
                            Text(job.jobCardNumber, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            // Four collections of the same job card previously rendered as
                            // four visually identical rows — order number plus product name —
                            // while actually holding 0%, 75%, 75% and 0% progress, so the
                            // operator had no way to pick the one they were working on. Every
                            // field below was already in the response and simply unread.
                            // Every progress figure here is optional, because the backend
                            // genuinely omits them. Rendering a missing percentage as "0%"
                            // told the operator a ReadyForMixing collection had no progress
                            // at all. `status` is the one field Station 2 fills in reliably,
                            // so it leads and the numbers embellish it only when they exist.
                            Text(
                                buildString {
                                    if (job.collectionId.isNotBlank()) append(job.collectionId)
                                    if (job.status.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(job.statusLabel)
                                    }
                                    val required = job.requiredIngredientCount ?: 0
                                    if (required > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${job.completedIngredientCount ?: 0} of $required lines")
                                    }
                                    job.progressPercent?.let { percent ->
                                        if (isNotEmpty()) append(" · ")
                                        append("%.0f%%".format(percent))
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                            if (job.productName.isNotBlank()) {
                                Text(
                                    job.productName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if ((job.pendingApprovalCount ?: 0) > 0) {
                                Text(
                                    "${job.pendingApprovalCount} line(s) awaiting approval",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningOrange
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (activeJobsError != null) {
                Text(
                    text = activeJobsError ?: "",
                    color = DangerRed,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = orderInput,
                onValueChange = { orderInput = it },
                label = { Text("Production Order No.") },
                singleLine = true,
                // A digits-only field opened the full QWERTY keyboard while the bag-count field
                // correctly opened a keypad. Matched to the keypad.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookupJob(orderInput) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    focusedLabelColor = AmberPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.lookupJob(orderInput) },
                enabled = orderInput.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Look Up")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenMixing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberPrimary),
                border = BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.5f)),
            ) {
                Text("Mixing")
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(text = err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
