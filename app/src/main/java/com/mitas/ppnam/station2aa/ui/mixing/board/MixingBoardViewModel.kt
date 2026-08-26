package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.EquipmentRole
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.model.ReadyMix
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** JANDI 4 is reached with no board selection — its sources are the drum plus a Main mix. */
private const val JANDI_4_CODE = "JAN-04"

/** What the operator has picked as the START source (source-first, user decision 4). */
sealed class BoardSelection {
    object None : BoardSelection()
    data class Collection(val collectionId: String, val jobCardNumber: String) : BoardSelection()

    /**
     * Exactly one finished mix. The JC-driven contract's destination start carries a singular
     * `mixBatchId` — "the first destination-machine scan assigns and starts exactly one Main
     * mix" — so a multi-mix selection can no longer be expressed on the wire.
     */
    data class Mix(val mixBatchId: String, val jobCardNumber: String) : BoardSelection()
}

/** One Rajoo dose-entry row; [doseText] is the raw operator input, validated on confirm. */
data class DoseRow(
    val materialCode: String,
    val materialName: String,
    val collectedQty: Double,
    val doseText: String = "",
)

/** The one dialog the board may own at a time. The scan guard blocks scans while != None. */
sealed class BoardSheet {
    object None : BoardSheet()

    /**
     * Start confirmation. [doseRows] is non-null only for a Rajoo mixer start, [routeOptions] is
     * non-empty only for the JANDI shared mixer, and [mainSourceOptions] only for JANDI 4.
     */
    data class StartConfirm(
        val machine: Equipment,
        val doseRows: List<DoseRow>?,
        val routeOptions: List<String> = emptyList(),
        val selectedRoute: String? = null,
        val mainSourceOptions: List<ReadyMix> = emptyList(),
        val selectedMainSource: String? = null,
        val validationError: String? = null,
    ) : BoardSheet()

    data class CycleSheet(val machine: Equipment, val cycle: ActiveCycle) : BoardSheet()

    data class ForceCloseDialog(
        val machine: Equipment,
        val cycle: ActiveCycle,
        val validationError: String? = null,
    ) : BoardSheet()
}

sealed class MixingBoardUiState {
    object Loading : MixingBoardUiState()
    data class Error(val message: String) : MixingBoardUiState()

    /** The five-area entry screen. [pendingCollectionId] fills the "ready to mix" banner. */
    data class AreaPicker(
        val overview: AreaOverview,
        val pendingCollectionId: String?,
    ) : MixingBoardUiState()

    data class Board(
        val area: MixingArea,
        val overview: AreaOverview,
        val readyCollections: List<ReadyCollection>,
        val selection: BoardSelection = BoardSelection.None,
        val highlightedMachineCodes: Set<String> = emptySet(),
        val sheet: BoardSheet = BoardSheet.None,
        /** A cycle request or dose fetch is in flight; scans and taps are ignored. */
        val busy: Boolean = false,
    ) : MixingBoardUiState()
}

/**
 * The machine grid splits by what a machine can be started FROM: a collection starts on a
 * mixer, a finished mix moves downstream. An area carries more machines than fit one screen,
 * so the grid shows one side at a time.
 */
enum class MachineTab(val label: String) {
    Collections("Collections"),
    Mixing("Mixing"),
}

/**
 * Every machine lands in exactly one tab, so none is unreachable. Downstream is the default:
 * an unknown or blank role (§13.7 tolerates roles we don't model) still shows up somewhere.
 */
internal fun machineTabOf(machine: Equipment): MachineTab =
    if (machine.role == EquipmentRole.MIXER) MachineTab.Collections else MachineTab.Mixing

/**
 * The pure highlight rule, unit-testable without the ViewModel. Highlights guide TAPS only —
 * a SCAN of any machine is trusted intent and goes to the server regardless (§13.7/§13.8:
 * availability and destinations render from server data; the server stays authoritative).
 */
internal fun computeHighlightedMachines(overview: AreaOverview, selection: BoardSelection): Set<String> =
    when (selection) {
        is BoardSelection.None -> emptySet()

        is BoardSelection.Collection -> {
            val collection = overview.readyCollections.firstOrNull {
                it.collectionId == selection.collectionId
            }
            if (collection == null) {
                emptySet()
            } else {
                val scannable = overview.equipment
                    .filter { it.isEnabled && it.scanAllowed }
                    .map { it.machineCode }
                    .toSet()
                collection.validMixerCodes.toSet() intersect scannable
            }
        }

        is BoardSelection.Mix -> {
            val mix = overview.readyMixes.firstOrNull { it.mixBatchId == selection.mixBatchId }
            // 4.1/B2: a force-closed mix is Quarantined and never assignable until an audited
            // Manager/Admin Release or Discard. Offering a destination for one would let the
            // operator send quarantined material to production — the exact defect B2 reported.
            if (mix == null || !mix.isAssignable) {
                emptySet()
            } else {
                val valid = mix.validNextMachineCodes.toSet()
                val available = overview.equipment
                    .filter { it.machineCode in valid && it.isEnabled && it.scanAllowed }
                    .map { it.machineCode }
                available.toSet()
            }
        }
    }

@HiltViewModel
class MixingBoardViewModel @Inject constructor(
    private val useCase: MixingBoardUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingBoardUiState>(MixingBoardUiState.Loading)
    val uiState: StateFlow<MixingBoardUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    /** Operator-facing snackbar lines: server reasons, start/finish confirmations. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The collection that triggered auto-navigation; pre-selected when an area opens. */
    private var pendingCollectionId: String? = null

    /** Task 5: in-flight cycle-operation guard (the capture VM's approvalJob discipline). */
    private var actionJob: Job? = null

    /** Cancels a superseded area load so a late response can't overwrite a newer one. */
    private var loadJob: Job? = null

    init {
        // Reconnect refresh (§13.11): the board is stale after any transport drop.
        viewModelScope.launch {
            mqttRepository.connectionState
                .drop(1) // the value at subscribe time is not a transition
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { refresh() }
        }

        // Scan-first machine selection (user decision 2). Guarded exactly like the capture
        // screen: a scan lands only on a quiet board — never over a sheet or an in-flight request.
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                val board = _uiState.value as? MixingBoardUiState.Board ?: return@collect
                if (board.sheet != BoardSheet.None || board.busy) return@collect
                val code = when (event) {
                    is ScanEvent.RfidTag -> event.tagId
                    is ScanEvent.Barcode -> event.value
                }
                machineChosen(code)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }

    fun loadAreaPicker(pendingCollectionId: String?) {
        this.pendingCollectionId = pendingCollectionId?.takeIf { it.isNotBlank() }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = MixingBoardUiState.Loading
            useCase.fetchOverview()
                .onSuccess {
                    _uiState.value = MixingBoardUiState.AreaPicker(
                        it, this@MixingBoardViewModel.pendingCollectionId)
                }
                .onFailure {
                    _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load mixing overview")
                }
        }
    }

    fun openArea(area: MixingArea) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = MixingBoardUiState.Loading
            val overview = useCase.fetchOverview(area).getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load $area")
                return@launch
            }
            val collections = useCase.fetchReadyCollections(area).getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load collections")
                return@launch
            }
            // Auto-navigation context: pre-select the pending collection while it is still ready.
            val selection = pendingCollectionId
                ?.let { pending -> collections.firstOrNull { it.collectionId == pending } }
                ?.let { BoardSelection.Collection(it.collectionId, it.jobCardNumber) }
                ?: BoardSelection.None
            // One-shot: the auto-nav hint must not re-assert itself over the operator's
            // later manual choices when a reconnect refresh re-derives this board.
            pendingCollectionId = null
            _uiState.value = MixingBoardUiState.Board(
                area = area,
                overview = overview,
                readyCollections = collections,
                selection = selection,
                highlightedMachineCodes = computeHighlightedMachines(overview, selection),
            )
        }
    }

    /**
     * Re-fetches whatever is on screen. A refresh resets an in-progress selection —
     * server state has moved, so stale selections must not survive it.
     */
    fun refresh() {
        when (val state = _uiState.value) {
            is MixingBoardUiState.AreaPicker -> loadAreaPicker(pendingCollectionId)
            is MixingBoardUiState.Board -> if (!state.busy) openArea(state.area)
            else -> Unit
        }
    }

    private fun board(): MixingBoardUiState.Board? = _uiState.value as? MixingBoardUiState.Board

    private fun setBoard(board: MixingBoardUiState.Board) {
        _uiState.value = board.copy(
            highlightedMachineCodes = computeHighlightedMachines(board.overview, board.selection))
    }

    fun selectCollection(collectionId: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val collection = board.readyCollections.firstOrNull { it.collectionId == collectionId } ?: return
        setBoard(board.copy(selection = BoardSelection.Collection(collection.collectionId, collection.jobCardNumber)))
    }

    fun selectMix(mixBatchId: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val mix = board.overview.readyMixes.firstOrNull { it.mixBatchId == mixBatchId } ?: return
        // Tapping the selected mix again clears it; tapping another replaces it outright.
        val selection = if ((board.selection as? BoardSelection.Mix)?.mixBatchId == mixBatchId) {
            BoardSelection.None
        } else {
            BoardSelection.Mix(mix.mixBatchId, mix.jobCardNumber)
        }
        setBoard(board.copy(selection = selection))
    }

    fun clearSelection() {
        val board = board() ?: return
        if (board.busy) return
        setBoard(board.copy(selection = BoardSelection.None))
    }

    /**
     * A Main mixer code scanned ahead of a JANDI 4 start. The contract is explicit that this is
     * client-side only: "There is no separate source-selection MQTT mutation." Cleared once a
     * JANDI 4 start is accepted, so it cannot leak into the next run.
     */
    private var cachedMainSourceMixerCode: String? = null

    fun cacheMainSourceMixerCode(machineCode: String) {
        cachedMainSourceMixerCode = machineCode.takeIf { it.isNotBlank() }
    }

    fun selectRoute(route: String) {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        setBoard(board.copy(sheet = sheet.copy(selectedRoute = route, validationError = null)))
    }

    fun selectMainSource(mixBatchId: String) {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        setBoard(board.copy(sheet = sheet.copy(selectedMainSource = mixBatchId, validationError = null)))
    }

    /**
     * A machine was scanned or a highlighted card tapped. With a selection this opens the
     * start-confirm sheet; without one it opens the machine's active-cycle sheet, or just
     * explains. An unknown scanned code still proceeds with a stub — trusted intent,
     * server-authoritative rejection after confirm.
     */
    fun machineChosen(machineCode: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val machine = board.overview.equipment.firstOrNull { it.machineCode == machineCode }
            ?: Equipment(
                machineCode = machineCode, displayName = machineCode, area = board.area,
                role = "", isEnabled = true, isAvailable = false, status = "",
                productLayer = null, currentCycleId = null, currentJobCardNumber = null,
                currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
                routeDescription = "",
            )
        when (val selection = board.selection) {
            is BoardSelection.None -> {
                // JANDI 4 takes the current drum plus one ready Main mix, so it starts without a
                // board selection. Its Main input is chosen in the sheet or supplied by a Main
                // mixer code scanned earlier.
                if (machine.machineCode == JANDI_4_CODE) {
                    // The JANDI-scoped overview this board was fetched with may or may not carry
                    // Main-area readyMixes (finding 2) — fetch Main explicitly and union with
                    // whatever is already on hand, so the sheet is correct either way.
                    viewModelScope.launch {
                        setBoard(board.copy(busy = true))
                        val fetchedMainMixes = useCase.fetchOverview(MixingArea.Main)
                            .onFailure {
                                _messages.trySend(it.message ?: "Could not load Main mixes")
                            }
                            .getOrNull()
                            ?.readyMixes
                            .orEmpty()
                        val existingMainMixes = board.overview.readyMixes.filter { it.area == MixingArea.Main }
                        val options = (existingMainMixes + fetchedMainMixes)
                            .filter { it.isAssignable }
                            .distinctBy { it.mixBatchId }
                        setBoard(board.copy(
                            busy = false,
                            sheet = BoardSheet.StartConfirm(
                                machine = machine,
                                doseRows = null,
                                mainSourceOptions = options,
                            ),
                        ))
                    }
                    return
                }
                val cycle = board.overview.activeCycles.firstOrNull { it.machineCode == machineCode }
                if (cycle != null) {
                    setBoard(board.copy(sheet = BoardSheet.CycleSheet(machine, cycle)))
                } else if (machine.area == MixingArea.Main && machine.role == EquipmentRole.MIXER) {
                    // The design intends the operator to scan a Main mixer code ahead of time and
                    // have the app cache it locally until the JANDI 4 start (finding 1): "There is
                    // no separate source-selection MQTT mutation."
                    cacheMainSourceMixerCode(machine.machineCode)
                    _messages.trySend(
                        "Cached ${machine.machineCode} as the Main source for the next JANDI 4 start.")
                } else {
                    _messages.trySend("Select a collection or mix to start this machine.")
                }
            }
            is BoardSelection.Collection -> {
                if (machine.area == MixingArea.Rajoo && machine.role == "Mixer") {
                    // Rajoo dose rows come from the collection's collected lines.
                    viewModelScope.launch {
                        setBoard(board.copy(busy = true))
                        useCase.fetchCollectedMaterials(selection.collectionId)
                            .onSuccess { materials ->
                                val rows = materials.map { DoseRow(it.materialCode, it.materialName, it.collectedQty) }
                                setBoard(board.copy(busy = false,
                                    sheet = BoardSheet.StartConfirm(machine, doseRows = rows)))
                            }
                            .onFailure {
                                setBoard(board.copy(busy = false))
                                _messages.trySend(it.message ?: "Could not load the collection's materials")
                            }
                    }
                } else {
                    val routes = if (machine.area == MixingArea.Jandi &&
                        machine.role == EquipmentRole.MIXER) JandiRoute.ALL else emptyList()
                    setBoard(board.copy(sheet = BoardSheet.StartConfirm(
                        machine = machine, doseRows = null, routeOptions = routes)))
                }
            }
            is BoardSelection.Mix ->
                setBoard(board.copy(sheet = BoardSheet.StartConfirm(machine, doseRows = null)))
        }
    }

    fun updateDose(materialCode: String, text: String) {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        val rows = sheet.doseRows ?: return
        setBoard(board.copy(sheet = sheet.copy(
            doseRows = rows.map { if (it.materialCode == materialCode) it.copy(doseText = text) else it },
            validationError = null)))
    }

    fun dismissSheet() {
        val board = board() ?: return
        if (board.busy) return
        setBoard(board.copy(sheet = BoardSheet.None))
    }

    fun confirmStart() {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.StartConfirm ?: return
        val machine = sheet.machine
        if (machine.machineCode == JANDI_4_CODE) {
            // Pre-flight affordance only — the use case stays the authority (finding 3). Without
            // it a rejected outcome's Rejected branch unconditionally closes the sheet, losing all
            // sheet state and forcing a re-scan of JAN-04 to try again.
            if (sheet.selectedMainSource == null && cachedMainSourceMixerCode == null) {
                setBoard(board.copy(sheet = sheet.copy(
                    validationError = "Select a Main mix or scan a Main mixer code before starting.")))
                return
            }
            actionJob = viewModelScope.launch {
                setBoard(board.copy(busy = true))
                val outcome = useCase.startJandi4(
                    machineCode = machine.machineCode,
                    mainSourceMixBatchId = sheet.selectedMainSource,
                    mainSourceMixerCode = if (sheet.selectedMainSource == null) cachedMainSourceMixerCode else null,
                )
                if (outcome is MachineCycleOutcome.Accepted) cachedMainSourceMixerCode = null
                applyOutcome(outcome) { accepted ->
                    "Started ${accepted.productionRunId ?: accepted.cycleId ?: ""} on ${accepted.machineCode}"
                }
            }
            return
        }
        actionJob = viewModelScope.launch {
            val outcome: MachineCycleOutcome = when (val selection = board.selection) {
                is BoardSelection.Collection -> when {
                    machine.area == MixingArea.Rajoo && machine.role == EquipmentRole.MIXER -> {
                        val doses = validateDoses(sheet.doseRows.orEmpty())
                        if (doses == null) return@launch // validationError already set
                        setBoard(board.copy(busy = true))
                        useCase.startRajooLayer(machine.machineCode, selection.collectionId, doses)
                    }
                    machine.area == MixingArea.Jandi && machine.role == EquipmentRole.MIXER -> {
                        val route = sheet.selectedRoute
                        if (route == null) {
                            setBoard(board.copy(sheet = sheet.copy(
                                validationError = "Select JANDI 2, JANDI 3 or the drum.")))
                            return@launch
                        }
                        setBoard(board.copy(busy = true))
                        useCase.startJandiMixer(machine.machineCode, selection.collectionId, route)
                    }
                    else -> {
                        setBoard(board.copy(busy = true))
                        useCase.startMixerFromCollection(machine.machineCode, selection.collectionId)
                    }
                }
                is BoardSelection.Mix -> {
                    setBoard(board.copy(busy = true))
                    if (machine.role == EquipmentRole.TRANSFER) {
                        useCase.startDrumTransfer(machine.machineCode, selection.mixBatchId)
                    } else {
                        useCase.startProductionDestination(machine.machineCode, selection.mixBatchId)
                    }
                }
                is BoardSelection.None -> return@launch
            }
            applyOutcome(outcome) { accepted ->
                val id = accepted.productionRunId ?: accepted.cycleId ?: ""
                "Started $id on ${accepted.machineCode}"
            }
        }
    }

    /** Returns null and surfaces a validation error when the rows are not sendable. */
    private fun validateDoses(rows: List<DoseRow>): List<LayerInput>? {
        val entered = rows.filter { it.doseText.isNotBlank() }
        val error = when {
            entered.isEmpty() -> "Enter at least one dose."
            entered.size > 5 -> "A Rajoo start takes at most five dose lines."
            entered.any { it.doseText.toDoubleOrNull()?.let { d -> d > 0.0 } != true } ->
                "Every dose must be a positive number."
            entered.any { it.doseText.toDouble() > it.collectedQty + 0.001 } ->
                "A dose cannot exceed the collected quantity."
            else -> null
        }
        if (error != null) {
            val board = board() ?: return null
            val sheet = board.sheet as? BoardSheet.StartConfirm ?: return null
            setBoard(board.copy(sheet = sheet.copy(validationError = error)))
            return null
        }
        return entered.map { LayerInput(it.materialCode, it.doseText.toDouble()) }
    }

    fun finishCycle() {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.CycleSheet ?: return
        actionJob = viewModelScope.launch {
            setBoard(board.copy(busy = true))
            val outcome = useCase.finish(sheet.machine.machineCode, sheet.cycle.cycleId)
            applyOutcome(outcome) { accepted ->
                if (accepted.alreadyFinished) "Cycle ${sheet.cycle.cycleId} was already finished"
                else "Cycle ${sheet.cycle.cycleId} finished"
            }
        }
    }

    fun openForceClose() {
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.CycleSheet ?: return
        setBoard(board.copy(sheet = BoardSheet.ForceCloseDialog(sheet.machine, sheet.cycle)))
    }

    fun submitForceClose(managerUsername: String, managerPassword: String, auditReason: String) {
        if (actionJob?.isActive == true) return
        val board = board() ?: return
        val sheet = board.sheet as? BoardSheet.ForceCloseDialog ?: return
        // Fail-closed: never put a blank credential or audit-trail entry on the wire.
        val validation = when {
            managerUsername.isBlank() || managerPassword.isBlank() ->
                "Manager username and password are required."
            auditReason.isBlank() -> "Audit reason is required."
            else -> null
        }
        if (validation != null) {
            setBoard(board.copy(sheet = sheet.copy(validationError = validation)))
            return
        }
        actionJob = viewModelScope.launch {
            setBoard(board.copy(busy = true))
            val outcome = useCase.forceClose(
                sheet.machine.machineCode, sheet.cycle.cycleId,
                managerUsername, managerPassword, auditReason)
            applyOutcome(outcome) { accepted ->
                "Cycle ${sheet.cycle.cycleId} force-closed" +
                    (accepted.approverDisplayName?.let { " (approved by $it)" } ?: "")
            }
        }
    }

    /**
     * Applies a machine-cycle outcome. Accepted always carries areaStatus (§8) — the board
     * refreshes from the response itself, clears the selection, and re-fetches ready
     * collections (a mixer start consumes one). Rejected carries areaStatus for business
     * rejections too, but null for envelope/session-level rejections — in that case the
     * board's current overview is kept as-is. Rejected keeps the selection so the operator
     * can retry another machine against the refreshed board.
     */
    private suspend fun applyOutcome(
        outcome: MachineCycleOutcome,
        successMessage: (MachineCycleOutcome.Accepted) -> String,
    ) {
        val board = board() ?: return
        when (outcome) {
            is MachineCycleOutcome.Accepted -> {
                val collections = useCase.fetchReadyCollections(board.area).getOrElse { board.readyCollections }
                setBoard(board.copy(
                    overview = outcome.areaStatus,
                    readyCollections = collections,
                    selection = BoardSelection.None,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(successMessage(outcome))
            }
            is MachineCycleOutcome.Rejected -> {
                setBoard(board.copy(
                    overview = outcome.areaStatus ?: board.overview,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(outcome.reason)
            }
            is MachineCycleOutcome.Failed -> {
                // No response reached us (timeout, drop, disconnect) — Station 2 may still have
                // applied the change server-side. Trusting our stale cache here is exactly the bug:
                // the board would keep showing a machine as free/occupied when it no longer is, and
                // e.g. a just-started cycle would never appear to finish against. Re-sync from the
                // server instead of guessing. Same reasoning as Accepted (§8): clear the selection
                // and re-fetch ready collections too, since a "no response" start/finish may have
                // actually landed server-side and consumed exactly what's still selected.
                val resynced = useCase.fetchOverview(board.area).getOrNull()
                val collections = useCase.fetchReadyCollections(board.area).getOrElse { board.readyCollections }
                setBoard(board.copy(
                    overview = resynced ?: board.overview,
                    readyCollections = collections,
                    selection = BoardSelection.None,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(outcome.message)
            }
        }
    }
}
