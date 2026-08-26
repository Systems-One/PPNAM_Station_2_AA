package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.StationAction
import com.mitas.ppnam.station2aa.data.session.canShow
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.DialogFormColumn
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurfaceVariant
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.util.formatElapsedSince
import com.mitas.ppnam.station2aa.ui.util.formatStationTimestamp

@Composable
fun MixingBoardScreen(
    area: MixingArea,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(area) { viewModel.openArea(area) }
    LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val board = uiState as? MixingBoardUiState.Board

    // --- Dialogs (one at a time, owned by the ViewModel's sheet state) ---
    when (val sheet = board?.sheet) {
        is BoardSheet.StartConfirm -> StartConfirmDialog(sheet, board.selection, viewModel)
        is BoardSheet.CycleSheet -> CycleSheetDialog(sheet, session, viewModel)
        is BoardSheet.ForceCloseDialog -> ForceCloseDialog(sheet, viewModel)
        else -> Unit
    }

    AppScaffold(
        title = area.display,
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = uiState is MixingBoardUiState.Loading || board?.busy == true,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                // The scaffold's bar is the whole loading affordance — nothing to draw here.
                is MixingBoardUiState.Loading -> Unit

                is MixingBoardUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, color = DangerRed)
                    Button(onClick = { viewModel.openArea(area) }) { Text("Retry") }
                }

                is MixingBoardUiState.Board -> BoardContent(state, viewModel)

                else -> Unit
            }

            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter)) { data ->
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

@Composable
private fun BoardContent(board: MixingBoardUiState.Board, viewModel: MixingBoardViewModel) {
    // An area carries far more machines than fit one screen, so the grid shows one side at a
    // time: what a collection can start on, or what a finished mix moves to.
    var machineTab by rememberSaveable { mutableStateOf(MachineTab.Collections) }
    // Picking a source jumps to the tab holding its highlighted machines — otherwise the
    // operator selects a mix and stares at a grid of mixers that will never light up.
    LaunchedEffect(board.selection) {
        when (board.selection) {
            is BoardSelection.Collection -> machineTab = MachineTab.Collections
            is BoardSelection.Mix -> machineTab = MachineTab.Mixing
            is BoardSelection.None -> Unit
        }
    }
    val tabMachines = board.overview.equipment.filter { machineTabOf(it) == machineTab }

    Column(Modifier.fillMaxSize()) {
        // board.busy raises the scaffold's bar under the title — see MixingBoardScreen().
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (val sel = board.selection) {
                    is BoardSelection.None -> "Select a collection or mix, then scan a machine"
                    is BoardSelection.Collection -> "Selected: JC ${sel.jobCardNumber}"
                    is BoardSelection.Mix -> "Selected: JC ${sel.jobCardNumber}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (board.selection is BoardSelection.None) TextMuted else AmberPrimary,
                modifier = Modifier.weight(1f),
            )
            if (board.selection !is BoardSelection.None) {
                TextButton(onClick = viewModel::clearSelection) { Text("Clear", color = TextPrimary) }
            }
            TextButton(onClick = viewModel::refresh, enabled = !board.busy) {
                Text("Refresh", color = TextPrimary)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (board.readyCollections.isNotEmpty()) {
                item { SectionHeader("Collections ready to mix") }
                items(board.readyCollections, key = { it.collectionId }) { collection ->
                    val selected = (board.selection as? BoardSelection.Collection)
                        ?.collectionId == collection.collectionId
                    StatusCard(
                        highlighted = selected,
                        onClick = { viewModel.selectCollection(collection.collectionId) },
                        enabled = !board.busy,
                    ) {
                        Text("JC ${collection.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text(collection.collectionId,
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        if (collection.productName.isNotBlank()) {
                            Text(collection.productName,
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }

            if (board.overview.readyMixes.isNotEmpty()) {
                item { SectionHeader("Ready mixes") }
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val selected =
                        (board.selection as? BoardSelection.Mix)?.mixBatchId == mix.mixBatchId
                    StatusCard(
                        highlighted = selected,
                        onClick = { viewModel.selectMix(mix.mixBatchId) },
                        enabled = !board.busy,
                    ) {
                        Text("JC ${mix.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        val mixSecondary = secondaryLine(
                            mix.mixBatchId,
                            mix.collectionId,
                            mix.sourceMixerCode.takeIf { it.isNotBlank() }?.let { "from $it" },
                        )
                        if (mixSecondary.isNotBlank()) {
                            Text(mixSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        // Destinations render ONLY from validNextMachineCodes (§13.8).
                        Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen)
                    }
                }
            }

            board.overview.jandiDrum?.let { drum ->
                item { SectionHeader("JANDI drum") }
                item {
                    // The drum's exact status vocabulary isn't fully enumerated in this codebase
                    // today (it's rendered verbatim, never matched against known values) — a
                    // single fixed Warning tone matches its current always-amber presentation
                    // without guessing at status strings this task can't verify.
                    StatusCard(tone = StatusTone.Warning) { accent ->
                        // An idle drum has no batch on it, so there is no JC to lead with —
                        // naming the drum itself beats printing an empty JC line.
                        Text(
                            drum.jobCardNumber?.let { "JC $it" } ?: "JANDI Transfer Drum",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        val drumSecondary = secondaryLine(drum.mixBatchId, drum.collectionId)
                        if (drumSecondary.isNotBlank()) {
                            Text(drumSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        Text(drum.status,
                            style = MaterialTheme.typography.bodyMedium, color = accent)
                        if (drum.scanGuidance.isNotBlank()) {
                            Text(drum.scanGuidance,
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }

            item { SectionHeader("Machines") }
            item {
                MachineTabs(
                    selected = machineTab,
                    equipment = board.overview.equipment,
                    highlighted = board.highlightedMachineCodes,
                    onSelect = { machineTab = it },
                )
            }
            if (tabMachines.isEmpty()) {
                item {
                    Text(
                        "No machines in this area take a " +
                            if (machineTab == MachineTab.Collections) "collection." else "mix.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
            items(
                tabMachines.chunked(2),
                key = { pair -> pair.joinToString("|") { it.machineCode } },
            ) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { machine ->
                        MachineCard(
                            machine = machine,
                            highlighted = machine.machineCode in board.highlightedMachineCodes,
                            hasCycle = board.overview.activeCycles.any { it.machineCode == machine.machineCode },
                            noSelection = board.selection is BoardSelection.None,
                            busy = board.busy,
                            onChosen = viewModel::machineChosen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (board.overview.activeCycles.isNotEmpty()) {
                item { SectionHeader("Active cycles") }
                items(board.overview.activeCycles, key = { it.cycleId }) { cycle ->
                    StatusCard(
                        onClick = { viewModel.machineChosen(cycle.machineCode) },
                        enabled = !board.busy && board.selection is BoardSelection.None,
                    ) {
                        Text("JC ${cycle.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text("${cycle.cycleId} on ${cycle.machineCode}",
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text(
                            "Started " + (formatElapsedSince(cycle.startedAtUtc)
                                ?: formatStationTimestamp(cycle.startedAtUtc)),
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }

            if (board.overview.activeRuns.isNotEmpty()) {
                item { SectionHeader("Active runs") }
                items(board.overview.activeRuns, key = { it.productionRunId }) { run ->
                    StatusCard {
                        Text(run.machineCode,
                            style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        val runSecondary = secondaryLine(run.productionRunId, run.status)
                        if (runSecondary.isNotBlank()) {
                            Text(runSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        // A JANDI 4 or Rajoo run carries several inputs whose job cards may
                        // differ. Listing them is the only way that is visible to an operator.
                        run.inputs.forEach { input ->
                            // "JC …" is the leading identifier (never conditionally hidden,
                            // matching every other card's JC line); only the traits after it
                            // — layer and role — are optional and joined so a blank one
                            // can't leave a dangling " · " behind.
                            val inputTraits = secondaryLine(
                                input.productLayer?.let { "layer $it" },
                                input.inputRole,
                            )
                            Text(
                                "JC ${input.jobCardNumber}" +
                                    (inputTraits.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text(input.mixBatchId,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MachineTabs(
    selected: MachineTab,
    equipment: List<Equipment>,
    highlighted: Set<String>,
    onSelect: (MachineTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = GraphiteSurface,
        contentColor = AmberPrimary,
    ) {
        MachineTab.entries.forEach { tab ->
            val machines = equipment.filter { machineTabOf(it) == tab }
            val ready = machines.count { it.machineCode in highlighted }
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                selectedContentColor = AmberPrimary,
                unselectedContentColor = TextMuted,
            ) {
                Column(
                    Modifier.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge)
                    // The ready count keeps highlighted machines findable behind the other
                    // tab — a hidden highlight the operator can't see is a dead end.
                    Text(
                        if (ready > 0) "$ready ready" else "${machines.size} machine(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready > 0) SuccessGreen else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = TextMuted,
        modifier = Modifier.padding(top = 8.dp))
}

/**
 * Rendered verbatim from areaStatus.equipment — never inferred locally (§13.7).
 *
 * "InUse" deliberately maps to [StatusTone.Warning], not [StatusTone.Running]/blue, even though
 * Running is used for "in progress" elsewhere in this app: on this screen Running/blue is reserved
 * for [StatusCard]'s `highlighted` axis (the scan/tap target), so an in-use machine's border must
 * stay visually distinct from a highlighted one.
 */
internal fun machineStatusTone(status: String): StatusTone = when (status) {
    "Available" -> StatusTone.Ready
    "InUse" -> StatusTone.Warning
    else -> StatusTone.Danger
}

@Composable
private fun MachineCard(
    machine: Equipment,
    highlighted: Boolean,
    hasCycle: Boolean,
    noSelection: Boolean,
    busy: Boolean,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Taps work on highlighted machines (start) or, with no selection, on busy
    // machines (cycle sheet). A SCAN reaches any machine via the ViewModel.
    val clickable = !busy && (highlighted || (noSelection && hasCycle))
    StatusCard(
        tone = machineStatusTone(machine.status),
        highlighted = highlighted,
        onClick = { onChosen(machine.machineCode) },
        enabled = clickable,
        modifier = modifier,
    ) { accent ->
        Text(machine.displayName, style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(machine.machineCode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(4.dp))
        Text(machine.status, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

private fun routeLabel(route: String) = when (route) {
    JandiRoute.JANDI_2 -> "JANDI 2 (direct feed)"
    JandiRoute.JANDI_3 -> "JANDI 3 (direct feed)"
    JandiRoute.DRUM -> "Drum (decant, then JANDI 4)"
    else -> route
}

/**
 * Builds a secondary/traceability line from parts that may individually be blank — several of
 * these fields (mixerCode, inputRole, run status…) are inferred and unconfirmed against the
 * backend, so a blank value is a real runtime case, not a hypothetical. Joining only the
 * non-blank parts makes a dangling or doubled " · " structurally unrepresentable, rather than
 * guarding each field in place.
 */
private fun secondaryLine(vararg parts: String?): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

@Composable
private fun StartConfirmDialog(
    sheet: BoardSheet.StartConfirm,
    selection: BoardSelection,
    viewModel: MixingBoardViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = {
            Text("Start ${sheet.machine.displayName}", color = TextPrimary)
        },
        text = {
            // Up to five dose fields plus the IME — the buttons need the scrollable body.
            DialogFormColumn {
                Text(
                    when (selection) {
                        is BoardSelection.Collection -> "Collection ${selection.collectionId} · JC ${selection.jobCardNumber}"
                        is BoardSelection.Mix -> "Mix ${selection.mixBatchId} · JC ${selection.jobCardNumber}"
                        is BoardSelection.None -> ""
                    },
                    color = TextMuted,
                )
                // Directly under the header, ABOVE the dose rows. It used to sit after them, and
                // a Rajoo sheet carries up to seven scrollable fields while the Start button stays
                // pinned in view — so tapping Start with a bad dose refused the start and put the
                // reason somewhere off-screen. From the operator's seat the button simply did
                // nothing. Same failure as the action buttons that sat under the IME: the message
                // existed, just not where the person who needed it was looking.
                sheet.validationError?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                }
                if (sheet.routeOptions.isNotEmpty()) {
                    Text("Route", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    sheet.routeOptions.forEach { route ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.selectRoute(route) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sheet.selectedRoute == route,
                                onClick = { viewModel.selectRoute(route) },
                                colors = RadioButtonDefaults.colors(selectedColor = AmberPrimary),
                            )
                            Text(routeLabel(route), color = TextPrimary)
                        }
                    }
                }

                if (sheet.mainSourceOptions.isNotEmpty()) {
                    Text("Main mix", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    sheet.mainSourceOptions.forEach { mix ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.selectMainSource(mix.mixBatchId) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sheet.selectedMainSource == mix.mixBatchId,
                                onClick = { viewModel.selectMainSource(mix.mixBatchId) },
                                colors = RadioButtonDefaults.colors(selectedColor = AmberPrimary),
                            )
                            Column {
                                Text("JC ${mix.jobCardNumber}",
                                    style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                Text("${mix.mixBatchId} · from ${mix.sourceMixerCode}",
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
                if (sheet.doseRows != null) {
                    // A Rajoo start takes at most five dose lines (validateDoses' own rule) — cap
                    // it here too instead of only rejecting on submit. Once five are filled,
                    // further still-empty fields disable rather than letting the operator fill in
                    // a sixth only to have the whole submission bounce.
                    val filledCount = sheet.doseRows.count { it.doseText.isNotBlank() }
                    Text(
                        "$filledCount / 5 doses entered",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    sheet.doseRows.forEach { row ->
                        val capReached = row.doseText.isBlank() && filledCount >= 5
                        OutlinedTextField(
                            value = row.doseText,
                            onValueChange = { viewModel.updateDose(row.materialCode, it) },
                            label = { Text("${row.materialName} (≤ %.2f kg)".format(row.collectedQty)) },
                            singleLine = true,
                            enabled = !capReached,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                focusedLabelColor = AmberPrimary,
                                cursorColor = AmberPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmStart) { Text("Start", color = AmberPrimary) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun CycleSheetDialog(
    sheet: BoardSheet.CycleSheet,
    session: OperatorSession?,
    viewModel: MixingBoardViewModel,
) {
    val cycle: ActiveCycle = sheet.cycle
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Active cycle on ${sheet.machine.displayName}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cycle: ${cycle.cycleId}", color = TextPrimary)
                Text("Job card: ${cycle.jobCardNumber}", color = TextMuted)
                if (cycle.mixBatchIds.isNotEmpty()) {
                    Text("Mixes: ${cycle.mixBatchIds.joinToString()}", color = TextMuted)
                }
                // Was: "Started 2026-07-23T11:38:28.2733333+00:00 by " — a raw ISO-8601 string no
                // shop-floor operator can read, followed by "by" and nothing, because the live
                // backend does not send startedByOperatorId at all on this payload. Formatted
                // local time now, with the elapsed reading alongside it, and the "by" clause only
                // appears when there is actually a name to put in it.
                Text(
                    buildString {
                        append("Started ")
                        append(formatStationTimestamp(cycle.startedAtUtc))
                        formatElapsedSince(cycle.startedAtUtc)?.let { append(" ($it)") }
                        if (cycle.startedByOperatorId.isNotBlank()) {
                            append(" by ${cycle.startedByOperatorId}")
                        }
                    },
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                // machine_force_close is an Admin privilege the Operator's allowedActions does
                // not carry. Display gate only — Station 2 still authorises on the manager
                // credentials the dialog collects, never on the sender's session.
                if (session.canShow(StationAction.MACHINE_FORCE_CLOSE)) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = viewModel::openForceClose) {
                        Text("Force close…", color = DangerRed)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::finishCycle) { Text("Finish cycle", color = AmberPrimary) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun ForceCloseDialog(sheet: BoardSheet.ForceCloseDialog, viewModel: MixingBoardViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var auditReason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Force close ${sheet.cycle.cycleId}", color = TextPrimary) },
        text = {
            // DialogFormColumn, not a plain Column: with the IME open the action buttons sat at
            // y=1167 underneath the keyboard, so taps aimed at "Force close" landed on keys and
            // typed stray characters into the audit-reason field.
            DialogFormColumn {
                Text("Requires Manager/Admin approval; the cycle is released without completing.",
                    color = TextMuted)
                sheet.validationError?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Manager/Admin Username") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = auditReason, onValueChange = { auditReason = it },
                    label = { Text("Audit reason") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank() && auditReason.isNotBlank(),
                onClick = { viewModel.submitForceClose(username, password, auditReason) },
            ) { Text("Force close", color = DangerRed) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}
