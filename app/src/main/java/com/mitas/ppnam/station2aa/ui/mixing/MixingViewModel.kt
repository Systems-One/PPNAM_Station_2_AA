package com.mitas.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.MqttTopics
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsInvalidatedResponse
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActiveJobsPage
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    object Cancelling : MixingUiState()

    /**
     * [selectedLineNumber] is the tap-line-to-arm target (SP3 Task 6): the BOM line whose
     * itemCode becomes requestedMaterialCode on the next ordinary pallet scan. Null means no line
     * is armed.
     *
     * [pendingLineNumber] is the line whose request is in flight, or null when nothing is. It
     * replaces flipping the whole screen to [Loading] for every scan: at 2.5–7.6 s of backend
     * latency per scan, a six-line BOM meant ~30 s staring at a spinner with no way to see what
     * had already been collected. The list stays rendered and scrollable; only the one line
     * being submitted shows as busy, and the scan guard treats a pending request exactly as it
     * treated Loading.
     *
     * [pendingLabel] describes a request with no line to attach to (a waiver approval, a
     * recovery). Non-null implies the screen is busy just as [pendingLineNumber] does — use
     * [isBusy] rather than testing either field.
     */
    data class OrderLoaded(
        val order: ProductionOrder,
        val selectedLineNumber: Int? = null,
        val pendingLineNumber: Int? = null,
        val pendingLabel: String? = null,
    ) : MixingUiState() {
        val isBusy: Boolean get() = pendingLineNumber != null || pendingLabel != null
    }

    /**
     * Bag entry for a scanned pallet.
     *
     * Carries the armed line, not just the pallet: the dialog previously opened for ANY scan
     * regardless of arming and then discarded the entry on confirm, and it showed the operator no
     * idea how many bags were still required — they had to remember it from the card behind the
     * dialog. Both facts now travel with the state, and the dialog cannot open without a line.
     */
    data class EnteringBagDetails(
        val palletTag: String,
        val lineNumber: Int,
        val materialCode: String,
        val materialName: String,
        val remainingBags: Double?,
        val bagSize: String?,
    ) : MixingUiState()

    /** Direct-weight entry for a bulk line (SP3 gap 1) — the bag picker never opens for bulk. */
    data class EnteringQuantityDetails(
        val palletTag: String,
        val lineNumber: Int,
        val materialCode: String,
        val materialName: String,
        val remainingQty: Double,
        val uom: String,
    ) : MixingUiState()

    /**
     * v3 has no exception id or approval token — approval is an inline resubmit of the pending scan
     * (held in the ViewModel, see [MixingViewModel.submitManagerApproval]), so this state carries
     * only the reason to show the operator. [validationError] is set when a submission was refused
     * client-side (blank credentials/audit reason) without ever reaching the wire — distinct from
     * [reason], which is why approval was needed in the first place.
     */
    data class IngredientExceptionApproval(val reason: String, val validationError: String? = null) : MixingUiState()
    data class PalletRecoveryPrompt(val palletTag: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()

    /**
     * A rejected short-bag waiver. Deliberately distinct from [IngredientExceptionApproval]: a
     * waiver has no pallet and is never resubmitted through the scan-resubmit path — the UI
     * re-collects credentials into a fresh [MixingViewModel.submitShortBagWaiver] call.
     */
    data class ShortBagWaiverNeedsApproval(
        val requestedMaterialCode: String,
        val shortBagCount: Double,
        val reason: String,
    ) : MixingUiState()

    /**
     * The FIRST-ATTEMPT short-bag waiver entry dialog (SP3 gap 2). ViewModel state, not local
     * Compose state, so the scan guard sees it and swallows stray RFID reads while it is open.
     * Distinct from [ShortBagWaiverNeedsApproval], which is a REJECTED waiver being re-approved.
     */
    data class ShortBagWaiverEntry(val requestedMaterialCode: String) : MixingUiState()
}

/**
 * One-shot haptic cues for the scan loop. An operator holding the handheld at a pallet shouldn't
 * need eyes on the screen to know a scan registered, so each cue fires on the actual causal
 * event — the same state transition the visual change rides on, never a delayed approximation:
 *  - [Armed]: a physical scan was attributed to a line and the entry dialog is opening.
 *  - [Accepted]: Station 2 booked the collection.
 *  - [Rejected]: the scan needs attention — refused locally, rejected by Station 2, or parked
 *    behind an approval/recovery prompt. One distinct "look at the screen" cue for all of them;
 *    finer-grained buzz vocabulary would just be noise the operator learns to ignore.
 */
enum class ScanFeedback { Armed, Accepted, Rejected }

object MixingNavDestination {
    const val JOB_LOADED = "job_loaded"
    const val MIXING_BOARD = "mixing_board"
}

sealed class CancelOutcome {
    object Confirmed : CancelOutcome()
    data class Failed(val reason: String) : CancelOutcome()
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    private val sessionHolder: OperatorSessionHolder
) : ViewModel() {

    private val gson = WireJson.gson

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    init {
        // 4.1: Station 2 pushes `active_job_cards_invalidated` after a collection mutation. It is
        // a hint, not a list and — the contract is explicit — "never permission for a workflow
        // mutation", so the only thing done with it is discarding the cursor and re-reading page
        // one. Guarded on the revision actually changing so a redundant push costs nothing.
        mqttRepository.setServerPushHandler { topic, envelope, raw ->
            if (MqttTopics.responseTypeOf(topic) != "active_job_cards_invalidated") return@setServerPushHandler
            val revision = runCatching {
                gson.fromJson(raw, ActiveJobCardsInvalidatedResponse::class.java)?.snapshotRevision
            }.getOrNull()
            if (revision != null && revision == activeJobsRevision) return@setServerPushHandler
            // Only reload when something is actually displaying the queue; otherwise just drop the
            // cursor so the next open starts clean.
            activeJobsToken = null
            if (_activeJobs.value.isNotEmpty()) loadActiveJobs()
        }
    }

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }

    /**
     * The over-collection allowance Station 2 last reported, in whole bags, or null before any
     * scan result has arrived. Display only — the server applies it, the handheld just tells the
     * operator it exists. Without it the only workable rule at the pallet ("always round up",
     * because under-collection has no tolerance at all) was undocumented anywhere in the UI.
     */
    private val _overCollectionToleranceBags = MutableStateFlow<Double?>(null)
    val overCollectionToleranceBags: StateFlow<Double?> = _overCollectionToleranceBags.asStateFlow()

    private val _activeJobs = MutableStateFlow<List<ActiveJobCardSummary>>(emptyList())
    val activeJobs: StateFlow<List<ActiveJobCardSummary>> = _activeJobs.asStateFlow()

    private val _activeJobsError = MutableStateFlow<String?>(null)
    val activeJobsError: StateFlow<String?> = _activeJobsError.asStateFlow()

    /** Whether another page of the 4.1-paged active-jobs queue can be requested. */
    private val _activeJobsHasMore = MutableStateFlow(false)
    val activeJobsHasMore: StateFlow<Boolean> = _activeJobsHasMore.asStateFlow()

    /** Total rows across all pages, when Station 2 supplies one — null means "not told". */
    private val _activeJobsTotal = MutableStateFlow<Int?>(null)
    val activeJobsTotal: StateFlow<Int?> = _activeJobsTotal.asStateFlow()

    // Paging cursor state. Private: a continuation token is meaningless outside this class and
    // must never be rendered.
    private var activeJobsToken: String? = null
    private var activeJobsRevision: String? = null
    private var activeJobsJob: Job? = null

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private val _supervisorError = Channel<String>(Channel.BUFFERED)
    val supervisorError: Flow<String> = _supervisorError.receiveAsFlow()

    private val _cancelOutcome = Channel<CancelOutcome>(Channel.BUFFERED)
    val cancelOutcome: Flow<CancelOutcome> = _cancelOutcome.receiveAsFlow()

    private val _scanFeedback = Channel<ScanFeedback>(Channel.BUFFERED)
    val scanFeedback: Flow<ScanFeedback> = _scanFeedback.receiveAsFlow()

    private var scanJob: Job? = null
    private var currentOrderNo: String = ""
    private var cachedOrder: ProductionOrder? = null

    /** Tap-line-to-arm target. ViewModel-only, like [pendingScan]/[pendingApproval] below — not Room. */
    private var armedLineNumber: Int? = null

    private fun orderLoadedState(
        order: ProductionOrder,
        pendingLineNumber: Int? = null,
        pendingLabel: String? = null,
    ) = MixingUiState.OrderLoaded(order, armedLineNumber, pendingLineNumber, pendingLabel)

    fun lookupJob(orderNo: String, collectionId: String = "") {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo, collectionId)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    // A fresh load/resume is a different BOM (possibly a different job entirely) —
                    // a line number armed against the previous order must not silently carry over.
                    armedLineNumber = null
                    _uiState.value = orderLoadedState(order)
                    _navigationEvent.send(MixingNavDestination.JOB_LOADED)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    /**
     * Loads the FIRST page of the active collection queue, discarding anything already held.
     *
     * 4.1 pages this list, so "load" and "load more" are genuinely different operations: merging a
     * fresh page-one response into an older accumulated list would duplicate rows and resurrect
     * collections that have since left the queue. The contract says as much — Android must replace
     * its displayed list rather than merge rows from an older response.
     */
    fun loadActiveJobs() {
        activeJobsJob?.cancel()
        activeJobsJob = viewModelScope.launch {
            useCase.fetchActiveJobCards(pageSize = ActiveJobsPage.DEFAULT_PAGE_SIZE)
                .onSuccess { page ->
                    if (page.cursorStale) {
                        // Can't happen on a first-page request (we sent no cursor), but if Station 2
                        // says the cursor is stale the honest state is "empty, try again" rather
                        // than pretending we have a page.
                        _activeJobs.value = emptyList()
                    } else {
                        _activeJobs.value = page.jobs
                    }
                    activeJobsToken = page.nextContinuationToken
                    activeJobsRevision = page.snapshotRevision
                    _activeJobsHasMore.value = page.canLoadMore
                    _activeJobsTotal.value = page.totalCount
                    _activeJobsError.value = null
                }
                .onFailure { e -> _activeJobsError.value = e.message ?: "Could not load active jobs" }
        }
    }

    /**
     * Appends the next page. No-op when there is nothing more or a load is already in flight.
     *
     * A `page_cursor_stale` answer means the queue moved under us, so the accumulated pages are no
     * longer a coherent snapshot — the only correct recovery is to start again from page one.
     */
    fun loadMoreActiveJobs() {
        val token = activeJobsToken
        if (token.isNullOrBlank() || activeJobsJob?.isActive == true) return
        activeJobsJob = viewModelScope.launch {
            useCase.fetchActiveJobCards(
                pageSize = ActiveJobsPage.DEFAULT_PAGE_SIZE,
                continuationToken = token,
            )
                .onSuccess { page ->
                    if (page.cursorStale) {
                        loadActiveJobs()
                        return@onSuccess
                    }
                    // Guard against a duplicate response re-appending the same rows: collectionId
                    // identifies a row, NOT jobCardNumber — several concurrent collections per job
                    // card is intended behaviour.
                    val seen = _activeJobs.value.mapTo(mutableSetOf()) { it.collectionId }
                    _activeJobs.value = _activeJobs.value + page.jobs.filter { seen.add(it.collectionId) }
                    activeJobsToken = page.nextContinuationToken
                    activeJobsRevision = page.snapshotRevision
                    _activeJobsHasMore.value = page.canLoadMore
                    _activeJobsTotal.value = page.totalCount
                    _activeJobsError.value = null
                }
                .onFailure { e -> _activeJobsError.value = e.message ?: "Could not load more jobs" }
        }
    }

    private data class PendingIngredientScan(
        /**
         * 4.1's canonical `sourceBarcode`: a pallet RFID tag OR a Station 3 master-batch label.
         * Not named for pallets any more, because it is no longer always one.
         */
        val sourceBarcode: String,
        val bagSizeOption: String?,
        val bagCount: Double?,
        val quantity: Double?,
        val requestedMaterialCode: String,
    )

    /** The scan pending a Holding-recovery retry. Distinct from [pendingApproval] below. */
    private var pendingScan: PendingIngredientScan? = null

    /**
     * The scan pending manager approval, stored ViewModel-only — never Room. It is short-lived (an
     * operator standing at a pallet with a manager beside them); persisting it would invite
     * resuming a stale approval hours later against a collection that has moved on. If the app
     * dies mid-approval, the recovery path is to re-scan, not to resume.
     */
    private var pendingApproval: IngredientScanOutcome.NeedsManagerApproval? = null

    /**
     * Track the in-flight privileged submission, mirroring [scanJob]'s discipline: an in-flight
     * guard (ignore re-entry — a fast double-tap on Approve/Waive must not fire two concurrent
     * credentialed requests, each minting its own messageId and each processed as a distinct
     * privileged action by Station 2) plus a cancellable handle so [cancelManagerApproval] can
     * kill a still-running submission rather than let its late response silently overwrite
     * whatever state the operator has since moved to.
     */
    private var approvalJob: Job? = null
    private var waiverJob: Job? = null

    /** Same discipline as [approvalJob]/[waiverJob] — see [confirmPalletRecovery]. */
    private var recoveryJob: Job? = null

    /** Fail-closed: refuse to put a blank credential or a blank audit trail entry on the wire. */
    private fun blankCredentialsMessage(managerUsername: String, managerPassword: String, auditReason: String): String? =
        when {
            managerUsername.isBlank() || managerPassword.isBlank() -> "Manager username and password are required."
            auditReason.isBlank() -> "Audit reason is required."
            else -> null
        }

    fun pauseScanning() {
        scanJob?.cancel()
    }

    fun startListeningForPalletScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // Barcode scans are accepted here too as a stand-in for RFID tags until
            // real RFID hardware is available on this handheld.
            scanEventBus.events.collect { event ->
                // Per-state scan-guard decision (SP3 Task 6), decided per state rather than
                // inherited — fail-closed is the default, with two deliberate exceptions:
                //  - OrderLoaded: ALLOWED — the normal scanning state; this is the point of the screen.
                //  - Error: ALLOWED — a settled state, not an in-flight request or an open dialog.
                //    dismissError() is now a real exit too, but rescanning must keep working as a
                //    second recovery path — Error must never trap the operator behind a dead reader.
                //  - OrderLoaded with a request in flight, Loading, Cancelling: BLOCKED — a
                //    request/cancel is in flight; a scan here would race or clobber it.
                //  - Idle: BLOCKED — nothing loaded to scan against.
                //  - EnteringBagDetails, EnteringQuantityDetails, IngredientExceptionApproval,
                //    PalletRecoveryPrompt, ShortBagWaiverNeedsApproval, ShortBagWaiverEntry:
                //    BLOCKED — each owns the screen with a dialog the operator is mid-interaction
                //    with; a stray scan must not clobber it.
                val state = _uiState.value
                if (state is MixingUiState.OrderLoaded && state.isBusy) return@collect
                when (state) {
                    is MixingUiState.OrderLoaded, is MixingUiState.Error -> {
                        val palletTag = when (event) {
                            is ScanEvent.RfidTag -> event.tagId
                            is ScanEvent.Barcode -> event.value
                        }
                        val order = cachedOrder ?: return@collect
                        val line = resolveScanTarget(order)
                        if (line == null) {
                            // Refuse UP FRONT rather than opening the dialog. The old behaviour
                            // showed the bag dialog for any scan whether or not a line was armed,
                            // let the operator pick a bag size and type a count, and only then —
                            // on Confirm — discarded the whole entry with a snackbar that auto-
                            // dismissed. Nothing reached the wire and the work was silently lost.
                            _supervisorError.trySend("Tap the material line you're collecting, then scan the pallet.")
                            _scanFeedback.trySend(ScanFeedback.Rejected)
                            return@collect
                        }
                        // Arm whatever we resolved: an unambiguous scan should not also require a
                        // tap, and the confirm handlers read armedLineNumber.
                        armedLineNumber = line.lineNumber
                        _scanFeedback.trySend(ScanFeedback.Armed)
                        // A bulk line arms direct-weight entry; arming a bulk line for a
                        // bag scan is impossible by construction (gap 1).
                        _uiState.value = if (!line.isBagged) {
                            MixingUiState.EnteringQuantityDetails(
                                palletTag = palletTag,
                                lineNumber = line.lineNumber,
                                materialCode = line.itemCode,
                                materialName = line.itemName.ifBlank { line.itemCode },
                                remainingQty = line.remainingQty,
                                uom = line.uom,
                            )
                        } else {
                            MixingUiState.EnteringBagDetails(
                                palletTag = palletTag,
                                lineNumber = line.lineNumber,
                                materialCode = line.itemCode,
                                materialName = line.itemName.ifBlank { line.itemCode },
                                remainingBags = line.remainingBags,
                                bagSize = line.bagSize,
                            )
                        }
                    }
                    else -> return@collect
                }
            }
        }
    }

    /**
     * The line a scan should be attributed to, or null when the operator has to say.
     *
     * An explicitly armed line always wins. Failing that, auto-arm — but only when the choice is
     * unambiguous: exactly one line still needs collecting. Two open lines and the app would be
     * guessing which material is on the pallet, and guessing wrong books stock against the wrong
     * BOM line, so it asks instead.
     *
     * The fallback to [ProductionOrder.lines] covers a fully-collected order: a single-line job
     * whose line is already satisfied still has one unambiguous target, and letting the scan
     * through means Station 2 gets to decide (over-collection tolerance, approval) rather than
     * the handheld pre-empting it.
     */
    private fun resolveScanTarget(order: ProductionOrder) =
        armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
            ?: order.lines.filterNot { it.isSatisfied }.singleOrNull()
            ?: order.lines.singleOrNull()

    fun cancelBagEntry() {
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    /**
     * Arms [lineNumber] as the scan target (tap-line-to-arm). Its itemCode becomes
     * requestedMaterialCode on the next ordinary pallet scan. Arming survives a successful scan —
     * several bags against the same line is the common case — and is cleared automatically once
     * that line is satisfied (see [handleScanOutcome]), or explicitly when the operator arms a
     * different line. Silently ignored when no order is loaded or [lineNumber] doesn't exist on it.
     */
    fun selectLine(lineNumber: Int) {
        val order = cachedOrder ?: return
        if (order.lines.none { it.lineNumber == lineNumber }) return
        armedLineNumber = lineNumber
        if (_uiState.value is MixingUiState.OrderLoaded) {
            _uiState.value = orderLoadedState(order)
        }
    }

    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
        if (line == null) {
            // No line armed: never put requestedMaterialCode = "" on the wire. Surface a clear
            // prompt instead and let the operator pick a line before retrying the scan.
            _supervisorError.trySend("Select a material line before scanning a pallet.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (!line.isBagged) {
            _supervisorError.trySend("${line.itemCode} is a bulk material — enter its weight instead.")
            _uiState.value = orderLoadedState(order)
            return
        }
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount, null, line.itemCode)
        viewModelScope.launch {
            // Inline pending, not MixingUiState.Loading: the list stays on screen and readable
            // while the request is out. See MixingUiState.OrderLoaded.pendingLineNumber.
            _uiState.value = orderLoadedState(order, pendingLineNumber = line.lineNumber)
            useCase.scanIngredient(order.collectionId, palletTag, line.itemCode,
                bagSizeOption = bagSizeOption, bagCount = bagCount)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun cancelQuantityEntry() {
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    fun confirmQuantityScan(palletTag: String, quantity: Double) {
        val order = cachedOrder ?: return
        val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
        if (line == null) {
            _supervisorError.trySend("Select a material line before scanning a pallet.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (line.isBagged) {
            _supervisorError.trySend("${line.itemCode} is a bagged material — scan bags instead.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (quantity <= 0.0) {
            _supervisorError.trySend("Quantity must be a positive number.")
            return
        }
        pendingScan = PendingIngredientScan(palletTag, null, null, quantity, line.itemCode)
        viewModelScope.launch {
            _uiState.value = orderLoadedState(order, pendingLineNumber = line.lineNumber)
            useCase.scanIngredient(order.collectionId, palletTag, line.itemCode, quantity = quantity)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    /**
     * Resubmits the pending [IngredientScanOutcome.NeedsManagerApproval] scan with manager
     * credentials attached — a fresh scanIngredient() call (the transport mints a new messageId;
     * there is no approval token to retry against). No-op when nothing is pending, e.g. called
     * after the dialog was already dismissed via [cancelManagerApproval] or already resolved.
     *
     * Ignores re-entry while a submission is already in flight ([approvalJob]), and refuses
     * (fail-closed, nothing sent) blank credentials or a blank audit reason — surfaced as
     * [MixingUiState.IngredientExceptionApproval.validationError] so the dialog can show why,
     * rather than silently doing nothing.
     */
    fun submitManagerApproval(managerUsername: String, managerPassword: String, auditReason: String) {
        if (approvalJob?.isActive == true) return
        val approval = pendingApproval ?: return
        val order = cachedOrder ?: return
        val validationMessage = blankCredentialsMessage(managerUsername, managerPassword, auditReason)
        if (validationMessage != null) {
            _uiState.value = MixingUiState.IngredientExceptionApproval(approval.reason, validationMessage)
            return
        }
        approvalJob = viewModelScope.launch {
            // The approval dialog is gone the moment this fires, so the operator lands back on
            // the line list with that line marked pending rather than on a blank spinner.
            _uiState.value = orderLoadedState(
                order,
                pendingLineNumber = order.lines
                    .firstOrNull { it.itemCode == approval.requestedMaterialCode }?.lineNumber,
                pendingLabel = "Submitting approval…",
            )
            useCase.scanIngredient(
                approval.collectionId,
                approval.sourceBarcode,
                approval.requestedMaterialCode,
                bagSizeOption = approval.bagSizeOption,
                bagCount = approval.bagCount,
                quantity = approval.quantity,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e ->
                    pendingApproval = null
                    _uiState.value = MixingUiState.Error(e.message ?: "Approval failed")
                }
        }
    }

    /**
     * Opens the first-attempt waiver dialog for [requestedMaterialCode]. Only from OrderLoaded
     * (the dialog owns the screen; opening it over another dialog or an in-flight request would
     * fight the scan guard's whole point), and only for a real bagged line — a bulk line has no
     * bag arithmetic to waive.
     */
    fun openShortBagWaiver(requestedMaterialCode: String) {
        val order = cachedOrder ?: return
        if (_uiState.value !is MixingUiState.OrderLoaded) return
        if (order.lines.none { it.itemCode == requestedMaterialCode && it.isBagged }) return
        _uiState.value = MixingUiState.ShortBagWaiverEntry(requestedMaterialCode)
    }

    fun dismissShortBagWaiverEntry() {
        if (_uiState.value !is MixingUiState.ShortBagWaiverEntry) return
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    /**
     * Waives short bags on [requestedMaterialCode]. Credentials travel on this first submission —
     * unlike a scan there is no preceding attempt to reject first; the operator is declaring up
     * front that a line will be short. requestedMaterialCode is passed explicitly rather than read
     * from the armed line: Task 7's per-line waiver button targets whichever line the operator
     * taps "waive" on, independent of which line (if any) is currently armed for scanning.
     *
     * Same discipline as [submitManagerApproval]: ignores re-entry while [waiverJob] is already
     * running, and refuses (fail-closed, nothing sent) blank credentials or a blank audit reason —
     * surfaced via [supervisorError] since, unlike the approval dialog, there is no pre-existing
     * waiver-dialog state to attach a validation message to on a first submission.
     *
     * Also refuses a blank [requestedMaterialCode] fail-closed: the UI's waiver dialog is opened
     * with the target line's material code held in `rememberSaveable` Compose state, which can in
     * principle still arrive here blank (e.g. a caller bug), and this would otherwise put
     * `waiveShortBags(collectionId, "", ...)` on the wire — a manager-approved waiver logged
     * against no material.
     */
    fun submitShortBagWaiver(
        requestedMaterialCode: String,
        shortBagCount: Double,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ) {
        if (waiverJob?.isActive == true) return
        val order = cachedOrder ?: return
        if (requestedMaterialCode.isBlank()) {
            _supervisorError.trySend("Select a material line before submitting a waiver.")
            return
        }
        val validationMessage = blankCredentialsMessage(managerUsername, managerPassword, auditReason)
        if (validationMessage != null) {
            _supervisorError.trySend(validationMessage)
            return
        }
        waiverJob = viewModelScope.launch {
            _uiState.value = orderLoadedState(
                order,
                pendingLineNumber = order.lines
                    .firstOrNull { it.itemCode == requestedMaterialCode }?.lineNumber,
                pendingLabel = "Submitting waiver…",
            )
            useCase.waiveShortBags(
                order.collectionId,
                requestedMaterialCode,
                shortBagCount,
                managerUsername,
                managerPassword,
                auditReason,
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Waiver failed") }
        }
    }

    fun cancelManagerApproval() {
        // Kill any in-flight resubmit too — otherwise its late response lands after the dialog is
        // gone and silently overwrites whatever state the operator has since moved to.
        approvalJob?.cancel()
        approvalJob = null
        pendingScan = null
        pendingApproval = null
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    /**
     * Dismisses a rejected short-bag waiver ([MixingUiState.ShortBagWaiverNeedsApproval]),
     * mirroring [cancelManagerApproval]: kills any still-in-flight resubmit ([waiverJob]) so a
     * late response landing after the operator has moved on cannot silently overwrite whatever
     * state they're now in. Unlike [cancelManagerApproval] there is no pendingScan/pendingApproval
     * to clear here — the waiver flow never populates them (see [handleScanOutcome]'s
     * NeedsApprovalForWaiver branch).
     */
    fun cancelShortBagWaiver() {
        waiverJob?.cancel()
        waiverJob = null
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    /**
     * Ignores re-entry while [recoveryJob] is already running — otherwise a fast double-tap on
     * "Recover" fires two concurrent recoverHolding+scanIngredient sequences for the same pallet,
     * each a fresh, independently-processed scan Station 2 has no way to dedupe, double-crediting
     * the collected quantity. Sets Loading synchronously (same discipline as every other confirm
     * action in this file) so the prompt's dialog unmounts before a second tap can land.
     */
    fun confirmPalletRecovery() {
        if (recoveryJob?.isActive == true) return
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        recoveryJob = viewModelScope.launch {
            _uiState.value = orderLoadedState(order, pendingLabel = "Recovering pallet…")
            // Recovery is a pallet operation and is only ever offered after Station 2 rejected a
            // scan with recover_holding, which it does only for a pallet — so the source barcode
            // here is a pallet RFID tag.
            useCase.recoverHolding(order.collectionId, scan.sourceBarcode)
                .onSuccess { retryPendingScan(order) }
                .onFailure { e ->
                    pendingScan = null
                    _supervisorError.trySend(e.message ?: "Recovery failed")
                    _uiState.value = orderLoadedState(order)
                }
        }
    }

    fun dismissPalletRecovery() {
        // Kill any in-flight recovery too — mirrors cancelManagerApproval so a late response
        // landing after the operator has dismissed the prompt cannot silently overwrite whatever
        // state they've since moved to.
        recoveryJob?.cancel()
        recoveryJob = null
        pendingScan = null
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    private fun handleScanOutcome(order: ProductionOrder, outcome: IngredientScanOutcome) {
        _scanFeedback.trySend(
            if (outcome is IngredientScanOutcome.Accepted) ScanFeedback.Accepted else ScanFeedback.Rejected
        )
        when (outcome) {
            is IngredientScanOutcome.Accepted -> {
                val updatedOrder = order.copy(
                    lines = outcome.updatedLines,
                    collectionStatus = outcome.collectionStatus,
                    summary = outcome.collectionSummary,
                )
                cachedOrder = updatedOrder
                pendingScan = null
                pendingApproval = null
                outcome.overCollectionToleranceBags?.let { _overCollectionToleranceBags.value = it }
                // Decision: an armed line survives a successful scan and stays armed until it is
                // fully satisfied, or the operator arms a different line — repeated bags against
                // the same material are the common case, so re-arming after every scan would be
                // needless friction.
                val armed = armedLineNumber?.let { ln -> updatedOrder.lines.firstOrNull { it.lineNumber == ln } }
                if (armed != null && armed.isSatisfied) armedLineNumber = null
                _uiState.value = orderLoadedState(updatedOrder)
                // Auto-navigate to Mixing when the server says the collection is ready
                // (user decision 1). Guidance, not permission — the board re-verifies
                // everything server-side.
                if (outcome.nextAction == NextAction.OPEN_MIXING) {
                    _navigationEvent.trySend(MixingNavDestination.MIXING_BOARD)
                }
            }
            is IngredientScanOutcome.NeedsManagerApproval -> {
                pendingApproval = outcome
                _uiState.value = MixingUiState.IngredientExceptionApproval(outcome.reason)
            }
            is IngredientScanOutcome.NeedsRecovery -> {
                pendingApproval = null
                _uiState.value = MixingUiState.PalletRecoveryPrompt(pendingScan?.sourceBarcode ?: "")
            }
            is IngredientScanOutcome.Rejected -> {
                pendingScan = null
                pendingApproval = null
                _supervisorError.trySend(outcome.reason)
                _uiState.value = orderLoadedState(order)
            }
            is IngredientScanOutcome.NeedsApprovalForWaiver -> {
                // Distinct from NeedsManagerApproval BY USER DECISION: a waiver is never
                // resubmitted through the scan path (it has no pallet), so this must NOT populate
                // pendingScan/pendingApproval — those exist only for the scan-resubmit flow. The UI
                // re-collects credentials into a fresh submitShortBagWaiver() call instead.
                pendingScan = null
                pendingApproval = null
                _uiState.value = MixingUiState.ShortBagWaiverNeedsApproval(
                    outcome.requestedMaterialCode, outcome.shortBagCount, outcome.reason
                )
            }
        }
    }

    private fun retryPendingScan(order: ProductionOrder) {
        val scan = pendingScan
        if (scan == null) {
            _uiState.value = orderLoadedState(order)
            return
        }
        viewModelScope.launch {
            _uiState.value = orderLoadedState(
                order,
                pendingLineNumber = order.lines
                    .firstOrNull { it.itemCode == scan.requestedMaterialCode }?.lineNumber,
                pendingLabel = "Retrying scan…",
            )
            useCase.scanIngredient(
                order.collectionId, scan.sourceBarcode, scan.requestedMaterialCode,
                bagSizeOption = scan.bagSizeOption, bagCount = scan.bagCount,
                quantity = scan.quantity,
            )
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    /**
     * The only exit from [MixingUiState.Error] — replaces clearError(), which had zero callers and
     * no dismiss button on screen, leaving Error a trap state (SP2's scan guard nearly shipped a
     * permanently-dead reader over exactly this; it was only saved by letting scans through in
     * Error, which remains true above). Returns to OrderLoaded, preserving the armed line, when an
     * order is loaded; otherwise Idle.
     */
    fun dismissError() {
        if (_uiState.value !is MixingUiState.Error) return
        val order = cachedOrder
        _uiState.value = if (order != null) orderLoadedState(order) else MixingUiState.Idle
    }

    // Waits for ingredient_collection_cancel_result before touching any local state — a
    // rejected cancel (e.g. the collection was already claimed by a mixer, or the manager
    // approval was denied) must leave the job exactly as it was.
    fun cancelJob(managerUsername: String = "", managerPassword: String = "") {
        if (_uiState.value is MixingUiState.Cancelling) return
        // v3 authorises a privileged action solely by the manager credentials carried in the
        // request, checked against the approver's account — never by the sender's session. There is
        // no direct-cancel path, even for a Manager on their own handheld.
        if (managerUsername.isBlank() || managerPassword.isBlank()) return
        val jobCardNumber = currentOrderNo
        val collectionId = cachedOrder?.collectionId ?: ""
        if (jobCardNumber.isBlank()) return
        scanJob?.cancel()
        val orderBeforeCancel = cachedOrder
        viewModelScope.launch {
            _uiState.value = MixingUiState.Cancelling
            useCase.cancelJob(
                collectionId,
                jobCardNumber,
                "Operator cancelled — incorrect job card",
                managerUsername,
                managerPassword
            )
                .onSuccess {
                    currentOrderNo = ""
                    cachedOrder = null
                    armedLineNumber = null
                    _uiState.value = MixingUiState.Idle
                    _cancelOutcome.send(CancelOutcome.Confirmed)
                }
                .onFailure { e ->
                    _uiState.value = orderBeforeCancel?.let { orderLoadedState(it) } ?: MixingUiState.Idle
                    if (orderBeforeCancel != null) startListeningForPalletScans(jobCardNumber)
                    _cancelOutcome.send(CancelOutcome.Failed(e.message ?: "Cancel failed"))
                }
        }
    }
}
