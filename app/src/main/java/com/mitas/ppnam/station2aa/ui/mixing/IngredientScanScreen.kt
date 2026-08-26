package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.data.session.StationAction
import com.mitas.ppnam.station2aa.data.session.canShow
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.DialogFormColumn
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.*
import kotlin.math.ceil

/**
 * Maps a BOM line's satisfied/armed/pending state to the shared color language. Pending (a
 * request in flight for this specific line) takes priority — an operator watching the list should
 * see "this one's working" over any other signal. `internal`, not `private`, so
 * `IngredientScanScreenKtTest` can verify it directly.
 */
internal fun BomLine.checklistTone(armed: Boolean, pending: Boolean): StatusTone = when {
    pending -> StatusTone.Running
    isSatisfied -> StatusTone.Ready
    armed -> StatusTone.Running
    else -> StatusTone.Idle
}

/**
 * CONFIRM/REJECT are the platform's semantic success/failure patterns but only exist from API 30;
 * older readers get the closest primitive (a light tap / the long-press buzz). Whether anything
 * actually vibrates stays under the system haptics setting — deliberately not overridden.
 */
private fun ScanFeedback.hapticConstant(): Int = when (this) {
    ScanFeedback.Armed -> HapticFeedbackConstants.KEYBOARD_TAP
    ScanFeedback.Accepted ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
    ScanFeedback.Rejected ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
}

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onStartMixing: (collectionId: String) -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    val toleranceBags by viewModel.overCollectionToleranceBags.collectAsState()
    // Display hints, never authorisation — Station 2 re-checks every request regardless. See
    // OperatorSession.canShow. An Operator carries neither of these; an Admin carries both.
    val mayCancelCollection = session.canShow(StationAction.INGREDIENT_COLLECTION_CANCEL)
    val mayWaiveShortBags = session.canShow(StationAction.INGREDIENT_APPROVE_SHORT_BAG)
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showBackConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }
    var selectedBagFraction by rememberSaveable { mutableStateOf(0.0) }
    var bagCountText by rememberSaveable { mutableStateOf("1") }
    var quantityText by rememberSaveable { mutableStateOf("") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }
    var exceptionAuditReason by remember { mutableStateOf("") }
    var waiverShortBagCountText by rememberSaveable { mutableStateOf("") }
    var waiverUsername by remember { mutableStateOf("") }
    var waiverPassword by remember { mutableStateOf("") }
    var waiverAuditReason by remember { mutableStateOf("") }
    var rejectedWaiverUsername by remember { mutableStateOf("") }
    var rejectedWaiverPassword by remember { mutableStateOf("") }
    var rejectedWaiverAuditReason by remember { mutableStateOf("") }

    val allIngredientsSatisfied = (uiState as? MixingUiState.OrderLoaded)?.order?.lines?.all { bomLine ->
        bomLine.isSatisfied
    } ?: false

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.supervisorError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // The physical cue rides the same event as the visual state change — an operator holding
    // the reader at a pallet learns the result without looking up.
    val view = LocalView.current
    LaunchedEffect(Unit) {
        viewModel.scanFeedback.collect { feedback ->
            view.performHapticFeedback(feedback.hapticConstant())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.cancelOutcome.collect { outcome ->
            when (outcome) {
                is CancelOutcome.Confirmed -> onBack()
                is CancelOutcome.Failed -> {
                    // Close the dialog before surfacing why: leaving it open over a snackbar it
                    // renders on top of means the operator never sees the reason.
                    showCancelDialog = false
                    managerUsername = ""
                    managerPassword = ""
                    snackbarHostState.showSnackbar(outcome.reason)
                }
            }
        }
    }

    // DisposableEffect, not LaunchedEffect: this screen's ViewModel outlives it (shared across the
    // whole Mixing nav graph), so leaving via ordinary back-navigation — not just the explicit RFID
    // Lookup detour — must also stop the listener. Otherwise it keeps running for the rest of the
    // operator's session and every later scan (e.g. at the Mixing Board) is silently double-delivered
    // to this screen's now-stale ViewModel state too.
    DisposableEffect(orderNo) {
        viewModel.startListeningForPalletScans(orderNo)
        onDispose { viewModel.pauseScanning() }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.MIXING_BOARD) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)
                    ?.order?.collectionId?.takeIf { it.isNotBlank() }
                    ?.let(onStartMixing)
            }
        }
    }

    val isCancelling = uiState is MixingUiState.Cancelling

    if (showBackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBackConfirmDialog = false },
            title = { Text("Go back?", color = TextPrimary) },
            text = { Text("You'll leave the ingredient scanning screen. Your progress is saved.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirmDialog = false
                    onBack()
                }) { Text("Go Back", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirmDialog = false }) { Text("Stay") }
            },
            containerColor = GraphiteSurface
        )
    }

    // ONE dialog, not two. It used to open a confirmation reading "closes the job card if it
    // hasn't had any activity yet… you'll be notified if it can't be cancelled" and then, on
    // confirm, a second one reading "cancelling a job card ALWAYS needs a manager's approval".
    // The first implied it might just succeed; the second said approval was unconditional. The
    // app already knew it was unconditional — it prompted locally without sending anything. State
    // it once, up front, and collect the credentials in the same step.
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isCancelling) {
                    showCancelDialog = false
                    managerUsername = ""
                    managerPassword = ""
                }
            },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text(
                        "Cancelling a job card always needs a manager's or admin's approval. Ask one to enter their credentials — this is recorded against their name in the audit trail.",
                        color = TextMuted
                    )
                    Text(
                        "Station 2 refuses the cancel if the collection has already been routed to a mixer.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    OutlinedTextField(
                        value = managerUsername,
                        onValueChange = { managerUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        enabled = !isCancelling,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = managerPassword,
                        onValueChange = { managerPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !isCancelling,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isCancelling && managerUsername.isNotBlank() && managerPassword.isNotBlank(),
                    onClick = { viewModel.cancelJob(managerUsername, managerPassword) }
                ) {
                    if (isCancelling) CircularProgressIndicator(Modifier.size(16.dp), color = AmberPrimary)
                    else Text("Confirm Cancel", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        showCancelDialog = false
                        managerUsername = ""
                        managerPassword = ""
                    }
                ) { Text("Keep Scanning") }
            },
            containerColor = GraphiteSurface
        )
    }

    val bagFractionOptions = listOf("0" to 0.0, "1/4" to 0.25, "1/2" to 0.5, "3/4" to 0.75)

    (uiState as? MixingUiState.EnteringBagDetails)?.let { bagState ->
        val palletTag = bagState.palletTag
        val remainingBags = bagState.remainingBags
        // Pre-fill the rounded-UP whole-bag count. BOM lines require fractional bags (89.03,
        // 10.99, 2.97, 0.74) and the picker only offers 0 / ¼ / ½ / ¾, so most requirements are
        // simply not expressible. Under-collection has NO tolerance — 89.00 against 89.03 comes
        // back isRequirementSatisfied: false — while over-collection has a whole-bag one and
        // auto-passes. That makes "always round up" the only workable rule at the pallet, and
        // nothing in the UI said so. Now it is the default the operator has to actively override.
        val suggestedBags = remainingBags
            ?.takeIf { it > 0.0 }
            ?.let { ceil(it).toInt().coerceAtLeast(1) }
        // Re-prime per dialog opening (keyed on the pallet), not per recomposition, so the
        // operator's own edits survive while they are typing.
        LaunchedEffect(palletTag, bagState.lineNumber) {
            bagCountText = (suggestedBags ?: 1).toString()
            selectedBagFraction = 0.0
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelBagEntry() },
            title = { Text("Bag size & count", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text(
                        "Line ${bagState.lineNumber} · ${bagState.materialName}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (remainingBags != null) {
                        Text(
                            "Still required: %.2f bags".format(remainingBags) +
                                (bagState.bagSize?.let { " of $it" } ?: ""),
                            color = AmberPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        buildString {
                            append("Round UP — a short line is rejected outright")
                            val tolerance = toleranceBags
                            if (tolerance != null && tolerance > 0.0) {
                                append(", while up to %.0f bag over is accepted automatically".format(tolerance))
                            }
                            append(".")
                        },
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        bagFractionOptions.forEach { (label, value) ->
                            val selected = selectedBagFraction == value
                            Text(
                                text = label,
                                color = if (selected) GraphiteSurface else TextMuted,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (selected) AmberPrimary else GraphiteSurfaceVariant)
                                    .clickable { selectedBagFraction = value }
                                    .padding(vertical = 10.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    OutlinedTextField(
                        value = bagCountText,
                        onValueChange = { bagCountText = it },
                        label = { Text("Full bags") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val entered = (bagCountText.toDoubleOrNull() ?: 0.0) + selectedBagFraction
                    if (remainingBags != null && entered > 0.0 && entered < remainingBags) {
                        Text(
                            "%.2f bags is short of the %.2f required — Station 2 will reject this.".format(entered, remainingBags),
                            color = DangerRed,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = bagCountText.toDoubleOrNull()?.let { it + selectedBagFraction > 0.0 } == true,
                    onClick = {
                        val count = bagCountText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmIngredientScan(palletTag, "full", count + selectedBagFraction)
                    }
                ) { Text("Confirm Scan", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBagEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    (uiState as? MixingUiState.EnteringQuantityDetails)?.let { qtyState ->
        val palletTag = qtyState.palletTag
        AlertDialog(
            onDismissRequest = { viewModel.cancelQuantityEntry() },
            title = { Text("Weight received", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text(
                        "Line ${qtyState.lineNumber} · ${qtyState.materialName}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Still required: %.2f %s".format(qtyState.remainingQty, qtyState.uom),
                        color = AmberPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Text("Bulk material — enter the exact weight received.", color = TextMuted,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = quantityText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        val qty = quantityText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmQuantityScan(palletTag, qty)
                        quantityText = ""
                    }
                ) { Text("Confirm Weight", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelQuantityEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.IngredientExceptionApproval) {
        val exceptionState = uiState as MixingUiState.IngredientExceptionApproval
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelManagerApproval()
                exceptionUsername = ""
                exceptionPassword = ""
                exceptionAuditReason = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text(exceptionState.reason, color = TextMuted)
                    exceptionState.validationError?.let { validationError ->
                        Text(validationError, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedTextField(
                        value = exceptionUsername,
                        onValueChange = { exceptionUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = exceptionPassword,
                        onValueChange = { exceptionPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = exceptionAuditReason,
                        onValueChange = { exceptionAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exceptionUsername.isNotBlank() && exceptionPassword.isNotBlank() && exceptionAuditReason.isNotBlank(),
                    onClick = {
                        viewModel.submitManagerApproval(exceptionUsername, exceptionPassword, exceptionAuditReason)
                        exceptionUsername = ""
                        exceptionPassword = ""
                        exceptionAuditReason = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelManagerApproval()
                        exceptionUsername = ""
                        exceptionPassword = ""
                        exceptionAuditReason = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.PalletRecoveryPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPalletRecovery() },
            title = { Text("Pallet not in Holding", color = TextPrimary) },
            text = { Text("This pallet isn't currently in Holding or Mixing. Recover it into Holding?", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPalletRecovery() }) { Text("Recover", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPalletRecovery() }) { Text("No", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    // First-attempt short-bag waiver entry: ViewModel state (SP3 gap 2), opened from the "Short
    // bags" button on a line. Credentials travel on this first submission (unlike a scan there is
    // no preceding attempt to reject first) — see MixingViewModel.submitShortBagWaiver.
    if (uiState is MixingUiState.ShortBagWaiverEntry) {
        val waiverMaterialCode = (uiState as MixingUiState.ShortBagWaiverEntry).requestedMaterialCode
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissShortBagWaiverEntry()
                waiverShortBagCountText = ""
                waiverUsername = ""
                waiverPassword = ""
                waiverAuditReason = ""
            },
            title = { Text("Waive short bags", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text("Material: $waiverMaterialCode", color = TextMuted)
                    OutlinedTextField(
                        value = waiverShortBagCountText,
                        onValueChange = { waiverShortBagCountText = it },
                        label = { Text("Short bag count") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = waiverUsername,
                        onValueChange = { waiverUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = waiverPassword,
                        onValueChange = { waiverPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = waiverAuditReason,
                        onValueChange = { waiverAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = waiverShortBagCountText.toDoubleOrNull()?.let { it > 0.0 } == true &&
                        waiverUsername.isNotBlank() && waiverPassword.isNotBlank() && waiverAuditReason.isNotBlank(),
                    onClick = {
                        val count = waiverShortBagCountText.toDoubleOrNull() ?: return@TextButton
                        viewModel.submitShortBagWaiver(waiverMaterialCode, count, waiverUsername, waiverPassword, waiverAuditReason)
                        waiverShortBagCountText = ""
                        waiverUsername = ""
                        waiverPassword = ""
                        waiverAuditReason = ""
                    }
                ) { Text("Submit", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissShortBagWaiverEntry()
                        waiverShortBagCountText = ""
                        waiverUsername = ""
                        waiverPassword = ""
                        waiverAuditReason = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.ShortBagWaiverNeedsApproval) {
        val waiverState = uiState as MixingUiState.ShortBagWaiverNeedsApproval
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelShortBagWaiver()
                rejectedWaiverUsername = ""
                rejectedWaiverPassword = ""
                rejectedWaiverAuditReason = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                DialogFormColumn {
                    Text("Material: ${waiverState.requestedMaterialCode}", color = TextMuted)
                    Text("Short by %.2f bags".format(waiverState.shortBagCount), color = TextMuted)
                    Text(waiverState.reason, color = TextMuted)
                    OutlinedTextField(
                        value = rejectedWaiverUsername,
                        onValueChange = { rejectedWaiverUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = rejectedWaiverPassword,
                        onValueChange = { rejectedWaiverPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = rejectedWaiverAuditReason,
                        onValueChange = { rejectedWaiverAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rejectedWaiverUsername.isNotBlank() && rejectedWaiverPassword.isNotBlank() && rejectedWaiverAuditReason.isNotBlank(),
                    onClick = {
                        viewModel.submitShortBagWaiver(
                            waiverState.requestedMaterialCode,
                            waiverState.shortBagCount,
                            rejectedWaiverUsername,
                            rejectedWaiverPassword,
                            rejectedWaiverAuditReason
                        )
                        rejectedWaiverUsername = ""
                        rejectedWaiverPassword = ""
                        rejectedWaiverAuditReason = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelShortBagWaiver()
                        rejectedWaiverUsername = ""
                        rejectedWaiverPassword = ""
                        rejectedWaiverAuditReason = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Scan Ingredients",
        status = connectionStatus,
        onBack = { showBackConfirmDialog = true },
        onRfidLookup = onRfidLookup,
        actions = if (mayCancelCollection) {
            {
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More actions",
                            tint = TextMuted
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cancel Job", color = DangerRed) },
                            onClick = {
                                showOverflowMenu = false
                                showCancelDialog = true
                            }
                        )
                    }
                }
            }
        } else null,
        loading = uiState is MixingUiState.Loading
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (val state = uiState) {
                    is MixingUiState.Loading -> {
                        // The scaffold's bar is the whole loading affordance; the weighted spacer
                        // keeps anything below this branch in the position it will settle into.
                        Box(Modifier.weight(1f).fillMaxWidth())
                    }
                    is MixingUiState.Error -> {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    state.message,
                                    color = DangerRed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.dismissError() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Dismiss") }
                        }
                    }
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isSatisfied }
                        val allSatisfied = satisfiedCount == order.lines.size

                        // Auto-arm the first unsatisfied line so operators can scan immediately
                        // without an extra tap for the common case. Tapping a different line in
                        // the checklist below (Task 4) still re-arms it — operators grab whatever
                        // pallet is physically nearby first, not strictly in BOM order, so jumping
                        // the queue must stay first-class, not a fallback.
                        LaunchedEffect(state.selectedLineNumber, order.lines, state.isBusy) {
                            if (state.selectedLineNumber == null && !state.isBusy) {
                                order.lines.firstOrNull { !it.isSatisfied }
                                    ?.let { viewModel.selectLine(it.lineNumber) }
                            }
                        }

                        val nextLine = order.lines.firstOrNull { it.lineNumber == state.selectedLineNumber }
                            ?.takeIf { !it.isSatisfied }
                        if (!allSatisfied && nextLine != null) {
                            StatusCard(
                                tone = StatusTone.Running,
                                modifier = Modifier.fillMaxWidth(),
                            ) { accent ->
                                Text(
                                    "SCAN THIS NEXT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    nextLine.itemName.ifBlank { nextLine.itemCode },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = TextPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "%.2f %s needed".format(nextLine.remainingQty, nextLine.uom),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (allSatisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allSatisfied) SuccessGreen.copy(alpha = 0.35f) else GraphiteBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                order.productBeingMade?.let { productName ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(productName, style = MaterialTheme.typography.bodyMedium, color = AmberPrimary)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$satisfiedCount of ${order.lines.size} lines satisfied",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (allSatisfied) SuccessGreen else TextMuted
                                )
                                if (order.summary.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        order.summary,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // The whole-screen spinner this replaces cost the operator ~30 s per
                        // six-line BOM with nothing to look at. A thin bar keeps the list readable
                        // and scrollable while a request is out; the pending line marks itself.
                        if (state.isBusy) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = AmberPrimary,
                                trackColor = GraphiteBorder
                            )
                            state.pendingLabel?.let { label ->
                                Spacer(Modifier.height(4.dp))
                                Text(label, style = MaterialTheme.typography.labelSmall, color = AmberPrimary)
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            // Without this the last card's "Short bags" action rendered half-cut
                            // behind the fixed Cancel / Start Mixing bar below.
                            contentPadding = PaddingValues(bottom = 12.dp)
                        ) {
                            items(order.lines, key = { it.lineNumber }) { bomLine ->
                                val satisfied = bomLine.isSatisfied
                                val armed = state.selectedLineNumber == bomLine.lineNumber
                                val pending = state.pendingLineNumber == bomLine.lineNumber
                                val fraction = if (bomLine.requiredQty > 0.0) {
                                    (bomLine.collectedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                StatusCard(
                                    tone = bomLine.checklistTone(armed = armed, pending = pending),
                                    // Arming during an in-flight request would leave the
                                    // operator unsure which line the response applies to.
                                    onClick = { viewModel.selectLine(bomLine.lineNumber) },
                                    enabled = !state.isBusy,
                                ) { accent ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "Line ${bomLine.lineNumber}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                if (armed) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        text = "ARMED",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = accent
                                                    )
                                                }
                                            }
                                            // maxLines + ellipsis: an unconstrained name
                                            // ("MASTERBATCH BLACK ME 9200 ME") wrapped under
                                            // the right-aligned kg value and the two overlapped.
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (pending) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = AmberPrimary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        } else if (satisfied) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Satisfied",
                                                tint = SuccessGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        // The value column gets its own floor so the name can
                                        // never squeeze it to nothing, and stays right-aligned.
                                        Text(
                                            text = if (bomLine.isSatisfied) {
                                                "Fully Allocated"
                                            } else {
                                                "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (satisfied) SuccessGreen else TextMuted,
                                            textAlign = TextAlign.End,
                                            maxLines = 2,
                                            modifier = Modifier.widthIn(min = 72.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = buildString {
                                            append("Available: %.2f %s".format(bomLine.availableQty, bomLine.uom))
                                            if (bomLine.isBagged) {
                                                append(" · Bag size: ${bomLine.bagSize}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    if (!bomLine.isSatisfied) {
                                        Spacer(Modifier.height(8.dp))
                                        // Captioned: a bagged line renders two visually
                                        // identical bars (weight, then bags) and neither said
                                        // which was which.
                                        Text(
                                            text = "Weight  %.2f / %.2f %s".format(
                                                bomLine.collectedQty, bomLine.requiredQty, bomLine.uom
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (satisfied) SuccessGreen else AmberPrimary,
                                            trackColor = GraphiteBorder
                                        )
                                    }
                                    // Every element below is gated on isBagged: a bulk line has no
                                    // bag arithmetic (its bag fields are null, not zero) and must
                                    // never render bag figures or be treated as bag-incomplete.
                                    if (bomLine.isBagged) {
                                        val expectedBags = bomLine.expectedBags ?: 0.0
                                        val scannedBags = bomLine.scannedBags ?: 0.0
                                        val bagFraction = if (expectedBags > 0.0) {
                                            (scannedBags / expectedBags).toFloat().coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "Bags  %.2f / %.2f full bags".format(scannedBags, expectedBags),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { bagFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (satisfied) SuccessGreen else AmberPrimary,
                                            trackColor = GraphiteBorder
                                        )
                                        // Waiving short bags is an Admin privilege the
                                        // Operator's allowedActions does not carry. Offering it
                                        // regardless meant discovering that only after a
                                        // multi-second round trip and a generic rejection.
                                        if (!satisfied && mayWaiveShortBags) {
                                            Spacer(Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    enabled = !state.isBusy,
                                                    onClick = { viewModel.openShortBagWaiver(bomLine.itemCode) }
                                                ) { Text("Short bags", color = AmberPrimary) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Spacer(Modifier.weight(1f))
                }

                val readyForMixing = (uiState as? MixingUiState.OrderLoaded)
                    ?.order?.collectionStatus == "ReadyForMixing"
                if ((readyForMixing || allIngredientsSatisfied) && uiState is MixingUiState.OrderLoaded) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Collection complete — ready for mixing.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        (uiState as? MixingUiState.OrderLoaded)
                            ?.order?.collectionId?.takeIf { it.isNotBlank() }
                            ?.let(onStartMixing)
                    },
                    enabled = readyForMixing,
                    colors = if (readyForMixing) {
                        ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = GraphiteBackground)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (readyForMixing) "Start Mixing →" else "Mixing after collection")
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) { data ->
                // The default Material snackbar is light-on-dark — a bright grey panel in an
                // otherwise dark UI, harsh in a dim plant and the one surface that breaks the
                // theme. Rendered on the app's own surface colours instead.
                Snackbar(
                    snackbarData = data,
                    containerColor = GraphiteSurfaceVariant,
                    contentColor = TextPrimary,
                    actionColor = AmberPrimary,
                )
            }
        }
    }
}
