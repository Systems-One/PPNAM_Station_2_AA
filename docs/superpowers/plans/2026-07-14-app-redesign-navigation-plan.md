# Job Card Lookup as Landing Screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After login, operators land directly on Job Lookup (no Home/mode-select screen); RFID Pallet Lookup becomes a top-bar button reachable from anywhere in the job/mixing flow and returns to the exact prior screen and state when closed.

**Architecture:** Reuse the existing `MIXING` nested nav graph (already starting at `JOB_LOOKUP`) as the post-login destination instead of `HOME`. Add an `onRfidLookup` action to `AppScaffold` that every mixing screen wires to push `RFID_RECOVERY` on top of the current back stack and pop back to it on close — Navigation-Compose's existing dispose/recompose behavior on push/pop handles "return to prior screen" for free, once local UI state is moved to `rememberSaveable` and the shared scan listener is explicitly paused before navigating away.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation-Compose 2.7.7, Hilt, JUnit + Mockito-Kotlin for ViewModel tests.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-14-app-redesign-navigation-design.md` (approved).
- `HomeScreen.kt`, `HomeViewModel.kt`, `HomeViewModelTest.kt`, `RajooViewModel.kt`, `MachineSelectScreen.kt`, `PalletAllocScreen.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt` are left untouched on disk — only their nav-graph wiring is removed.
- No MQTT contract changes. No changes to `IngredientScanScreen`/`HopperScanScreen`/`PreMixCompleteScreen` functional behavior beyond the `onRfidLookup` button and `rememberSaveable` fixes.
- `C:\Dev\PPNAM-Station-2` is read-only except `RFID_MQTT_CONTRACT.md` — not touched in this plan.

---

### Task 1: `MixingViewModel` gains `pauseScanning()`, `session`, and `logout()`

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Consumes: `AuthUseCase` (`app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt`, already has `suspend fun logout(): Result<Unit>`, used identically by `HomeViewModel`), `OperatorSession` (`app/src/main/java/com/ppnam/station2aa/data/session/OperatorSession.kt`).
- Produces: `MixingViewModel.session: StateFlow<OperatorSession?>`, `MixingViewModel.logoutEvent: Flow<Unit>`, `MixingViewModel.logout(): Unit`, `MixingViewModel.pauseScanning(): Unit`. Task 3 (JobLookupScreen) consumes all four; Task 7 (AppNavGraph) consumes `pauseScanning()` from every mixing screen's RFID-lookup button handler.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`, replacing the import block at the top with (adding the `AuthUseCase` import):

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.domain.model.HopperAvailability
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
```

Add a `mockAuthUseCase` field next to the other mocks:

```kotlin
    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: MixingViewModel
```

In `setup()`, initialize and stub it, and pass it into the `viewModel` construction:

```kotlin
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.hopperStatusUpdates)
            .thenReturn(MutableSharedFlow())
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockAuthUseCase.logout()).thenReturn(Result.success(Unit))
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(sessionWithActions("cancel_premix")))

        viewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
        )
    }
```

Now fix up every other direct `MixingViewModel(...)` construction in the same file (there are three identical single-line calls and one more multi-line call further down) with a single find-and-replace across the file: replace every occurrence of

```
mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockSessionHolder
```

with

```
mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
```

(This exact substring appears identically in `setup()` and in the `startListeningForPalletScans` tests, the `cancelBagEntry` test, and the `operatorCanCancelDirectly` test — five occurrences total including the one just added above.)

Finally, append these three new tests at the end of the class, just before the closing `}`:

```kotlin
    @Test
    fun `session reflects the operator session holder`() = runTest {
        assertEquals("Test Operator", viewModel.session.value?.operatorName)
    }

    @Test
    fun `logout calls AuthUseCase and fires logoutEvent`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun `pauseScanning cancels the active scan job so further scans are ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder
        )
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        vm.pauseScanning()
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: compilation FAILURE — `MixingViewModel` constructor doesn't accept `mockAuthUseCase`, and `session`/`logoutEvent`/`logout()`/`pauseScanning()` don't exist yet.

- [ ] **Step 3: Implement `MixingViewModel` changes**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`, update the imports (add two lines to the existing import block):

```kotlin
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
```

and

```kotlin
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
```

Change the constructor:

```kotlin
@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository,
    private val authUseCase: AuthUseCase,
    private val sessionHolder: OperatorSessionHolder
) : ViewModel() {
```

Right after the existing `pendingCount` block:

```kotlin
    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
```

add:

```kotlin

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
```

Right before `fun startListeningForPalletScans(orderNo: String) {`, add:

```kotlin
    fun pauseScanning() {
        scanJob?.cancel()
    }

```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: PASS (all existing tests plus the three new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): add pauseScanning, session, and logout to MixingViewModel"
```

---

### Task 2: `AppScaffold` gains an RFID Pallet Lookup top-bar action

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `AppScaffold(..., onRfidLookup: (() -> Unit)? = null, ...)`. Tasks 3–6 pass this from `JobLookupScreen`, `IngredientScanScreen`, `HopperScanScreen`, `PreMixCompleteScreen`.

- [ ] **Step 1: Add the import**

In `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`, add to the imports:

```kotlin
import androidx.compose.material.icons.filled.WifiTethering
```

- [ ] **Step 2: Add the parameter and the button**

Change the function signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onBack: (() -> Unit)? = null,
    onRfidLookup: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    operatorName: String? = null,
    operatorRole: String? = null,
    onLogout: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
```

In the `actions` block, insert the new button between the operator-name `TextButton` and the existing `onSettings` block:

```kotlin
                actions = {
                    if (operatorName != null) {
                        TextButton(onClick = { showLogoutDialog = true }) {
                            Text(
                                text = if (!operatorRole.isNullOrBlank()) "$operatorName · $operatorRole" else operatorName,
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (onRfidLookup != null) {
                        IconButton(onClick = onRfidLookup) {
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = "RFID Pallet Lookup",
                                tint = TextMuted
                            )
                        }
                    }
                    if (onSettings != null) {
```

(leave everything else in `actions` unchanged).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No dedicated Compose UI test harness exists in this repo for `AppScaffold` — compilation plus the manual pass in Task 8 is the verification gate.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt
git commit -m "feat(ui): add RFID Pallet Lookup action to AppScaffold top bar"
```

---

### Task 3: `JobLookupScreen` becomes the landing screen (session, logout, settings, RFID button, saveable input)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`

**Interfaces:**
- Consumes: `MixingViewModel.session`, `.logoutEvent`, `.logout()`, `.pauseScanning()` (Task 1); `AppScaffold(onRfidLookup=...)` (Task 2).
- Produces: new `JobLookupScreen` signature —
  ```kotlin
  fun JobLookupScreen(
      onJobFound: (orderNo: String) -> Unit,
      onSettings: () -> Unit = {},
      onLogout: () -> Unit = {},
      onRfidLookup: () -> Unit = {},
      viewModel: MixingViewModel = hiltViewModel()
  )
  ```
  (the `onBack` parameter is removed). The three new callbacks default to no-ops so this task compiles standalone; Task 7 (AppNavGraph) wires all three to real navigation.

- [ ] **Step 1: Update imports**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`, add:

```kotlin
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 2: Change the signature and wire session/logout**

Replace:

```kotlin
@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val activeJobs by viewModel.activeJobs.collectAsState()
    val activeJobsError by viewModel.activeJobsError.collectAsState()
    var orderInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadActiveJobs() }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.JOB_LOADED) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)?.let { onJobFound(it.order.docNo) }
            }
        }
    }
```

with:

```kotlin
@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onRfidLookup: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val activeJobs by viewModel.activeJobs.collectAsState()
    val activeJobsError by viewModel.activeJobsError.collectAsState()
    val session by viewModel.session.collectAsState()
    var orderInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadActiveJobs() }

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
```

- [ ] **Step 3: Wire the new `AppScaffold` parameters**

Replace:

```kotlin
    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null,
        onRfidLookup = onRfidLookup,
        onSettings = onSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout
    ) { padding ->
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `AppNavGraph.kt` still calls `JobLookupScreen` positionally with just `onJobFound`/`onBack`-style args from before — since `onBack` no longer exists as a parameter, check whether this call site now fails; if `AppNavGraph.kt` passes `onBack = { ... }` by name, that argument is simply unresolved and IS a real compile error (not one masked by defaults), because `onBack` doesn't exist on the new signature at all. Confirm that specific error (unresolved `onBack` reference in `AppNavGraph.kt`'s `JobLookupScreen(...)` call) is the *only* error — it is fixed in Task 7. No errors should appear inside `JobLookupScreen.kt` itself.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt
git commit -m "feat(mixing): JobLookupScreen carries operator/logout/settings/RFID lookup"
```

---

### Task 4: `IngredientScanScreen` gets the RFID button and saveable local state

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: `AppScaffold(onRfidLookup=...)` (Task 2).
- Produces: new signature `fun IngredientScanScreen(orderNo: String, onProceedToHopperScan: () -> Unit, onRfidLookup: () -> Unit = {}, onBack: () -> Unit = {}, viewModel: MixingViewModel = hiltViewModel())`. `onRfidLookup` defaults to a no-op so this task compiles standalone; Task 7 wires it to real navigation.

- [ ] **Step 1: Update imports**

Add:

```kotlin
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 2: Convert local state to `rememberSaveable`**

Replace:

```kotlin
    var showCancelDialog by remember { mutableStateOf(false) }
    var showBackConfirmDialog by remember { mutableStateOf(false) }
    var showApprovalDialog by remember { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }
    var selectedBagFraction by remember { mutableStateOf(0.0) }
    var bagCountText by remember { mutableStateOf("1") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }
```

with:

```kotlin
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showBackConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showApprovalDialog by rememberSaveable { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }
    var selectedBagFraction by rememberSaveable { mutableStateOf(0.0) }
    var bagCountText by rememberSaveable { mutableStateOf("1") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }
```

**Amended post-implementation (security review):** `managerUsername`, `managerPassword`, `exceptionUsername`, and `exceptionPassword` stay on plain `remember`, not `rememberSaveable` — `rememberSaveable` persists into Android's saved-instance-state Bundle, which can be written to disk across process death, and these are credential fields. Only the 5 non-credential fields above (`showCancelDialog`, `showBackConfirmDialog`, `showApprovalDialog`, `selectedBagFraction`, `bagCountText`) use `rememberSaveable`. This means a partially-typed manager/exception username or password resets if the operator opens RFID Pallet Lookup mid-dialog — an accepted, deliberate tradeoff against persisting credentials.

- [ ] **Step 3: Add the `onRfidLookup` parameter and wire it**

Replace:

```kotlin
@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToHopperScan: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
```

with:

```kotlin
@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToHopperScan: () -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
```

Replace:

```kotlin
    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = { showBackConfirmDialog = true }
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = { showBackConfirmDialog = true },
        onRfidLookup = onRfidLookup
    ) { padding ->
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `onRfidLookup` defaults to a no-op, so `AppNavGraph.kt`'s existing `IngredientScanScreen(...)` call (which doesn't pass it yet) still compiles; it will actually pass the real callback once Task 7 wires it up.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(mixing): IngredientScanScreen gains RFID lookup button and saveable dialog state"
```

---

### Task 5: `HopperScanScreen` gets the RFID button

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt`

**Interfaces:**
- Consumes: `AppScaffold(onRfidLookup=...)` (Task 2).
- Produces: new signature `fun HopperScanScreen(orderNo: String, onProceed: () -> Unit, onRfidLookup: () -> Unit = {}, onBack: () -> Unit = {}, viewModel: MixingViewModel = hiltViewModel())`. `onRfidLookup` defaults to a no-op so this task compiles standalone; Task 7 wires it to real navigation.

- [ ] **Step 1: Add the parameter and wire it**

Replace:

```kotlin
@Composable
fun HopperScanScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
```

with:

```kotlin
@Composable
fun HopperScanScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
```

Replace:

```kotlin
    AppScaffold(
        title = "Scan Hopper",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Scan Hopper",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack,
        onRfidLookup = onRfidLookup
    ) { padding ->
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `onRfidLookup` defaults to a no-op, so `AppNavGraph.kt`'s existing `HopperScanScreen(...)` call still compiles; Task 7 wires it to real navigation.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt
git commit -m "feat(mixing): HopperScanScreen gains RFID lookup button"
```

---

### Task 6: `PreMixCompleteScreen` gets the RFID button and saveable confirmation state

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt`

**Interfaces:**
- Consumes: `AppScaffold(onRfidLookup=...)` (Task 2).
- Produces: new signature `fun PreMixCompleteScreen(orderNo: String, onCompleted: () -> Unit, onRfidLookup: () -> Unit = {}, onBack: () -> Unit = {}, viewModel: MixingViewModel = hiltViewModel())`; the private `PremixConfirmedContent` also gains `onRfidLookup: () -> Unit` (no default needed — its only caller, `PreMixCompleteScreen`, is updated in this same task). `PreMixCompleteScreen.onRfidLookup` defaults to a no-op so this task compiles standalone; Task 7 wires it to real navigation.

- [ ] **Step 1: Update imports**

Add:

```kotlin
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 2: Convert `showConfirmation` to `rememberSaveable` and add the parameter**

Replace:

```kotlin
@Composable
fun PreMixCompleteScreen(
    orderNo: String,
    onCompleted: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hopperCode by viewModel.hopperCode.collectAsState()
    val isQueuedOffline by viewModel.isQueuedOffline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }
```

with:

```kotlin
@Composable
fun PreMixCompleteScreen(
    orderNo: String,
    onCompleted: () -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hopperCode by viewModel.hopperCode.collectAsState()
    val isQueuedOffline by viewModel.isQueuedOffline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
```

- [ ] **Step 3: Thread `onRfidLookup` into both scaffold calls**

Replace:

```kotlin
    if (showConfirmation) {
        PremixConfirmedContent(
            isQueuedOffline = isQueuedOffline,
            connectionState = connectionState,
            pendingCount = pendingCount,
            onDone = onCompleted
        )
        return
    }
```

with:

```kotlin
    if (showConfirmation) {
        PremixConfirmedContent(
            isQueuedOffline = isQueuedOffline,
            connectionState = connectionState,
            pendingCount = pendingCount,
            onDone = onCompleted,
            onRfidLookup = onRfidLookup
        )
        return
    }
```

Replace:

```kotlin
    AppScaffold(
        title = "Review Pre-Mix",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Review Pre-Mix",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack,
        onRfidLookup = onRfidLookup
    ) { padding ->
```

Replace:

```kotlin
@Composable
private fun PremixConfirmedContent(
    isQueuedOffline: Boolean,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onDone: () -> Unit
) {
```

with:

```kotlin
@Composable
private fun PremixConfirmedContent(
    isQueuedOffline: Boolean,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onDone: () -> Unit,
    onRfidLookup: () -> Unit
) {
```

Replace:

```kotlin
    AppScaffold(
        title = "Pre-Mix Complete",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Pre-Mix Complete",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null,
        onRfidLookup = onRfidLookup
    ) { padding ->
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `onRfidLookup` defaults to a no-op, so `AppNavGraph.kt`'s existing `PreMixCompleteScreen(...)` call still compiles; Task 7 wires it to real navigation.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt
git commit -m "feat(mixing): PreMixCompleteScreen gains RFID lookup button and saveable confirmation state"
```

---

### Task 7: Navigation graph restructure — login lands on Job Lookup, Home/Rajoo/Dashboard removed, RFID lookup wired everywhere

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `JobLookupScreen(onJobFound, onSettings, onLogout, onRfidLookup, viewModel)` (Task 3), `IngredientScanScreen(orderNo, onProceedToHopperScan, onRfidLookup, onBack, viewModel)` (Task 4), `HopperScanScreen(orderNo, onProceed, onRfidLookup, onBack, viewModel)` (Task 5), `PreMixCompleteScreen(orderNo, onCompleted, onRfidLookup, onBack, viewModel)` (Task 6), `MixingViewModel.pauseScanning()` (Task 1).
- Produces: the finished navigation graph — no further tasks depend on this one.

- [ ] **Step 1: Trim `NavRoutes.kt`**

`app/src/main/java/com/ppnam/station2aa/ui/rajoo/PalletAllocScreen.kt:37` reads `if (destination == NavRoutes.HOME) onDone()` — since that file is left untouched (per the design's decision to keep Rajoo/Dashboard code in place, just unreachable), `NavRoutes.HOME` must keep existing even though the rewritten nav graph in Step 2 no longer uses it itself. `MACHINE_SELECT`, `PALLET_ALLOC`, `DASHBOARD`, and `palletAlloc()` have no references outside `AppNavGraph.kt`, so those are safe to remove.

Replace the full contents of `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt` with:

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MIXING = "mixing"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val HOPPER_SCAN = "mixing/hopper_scan/{orderNo}"
    const val PREMIX_COMPLETE = "mixing/premix_complete/{orderNo}"
    const val RFID_RECOVERY = "rfid/recovery"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"
    fun hopperScan(orderNo: String) = "mixing/hopper_scan/$orderNo"
    fun premixComplete(orderNo: String) = "mixing/premix_complete/$orderNo"
}
```

`NavRoutes.HOME` becomes unused dead-code string outside of `PalletAllocScreen.kt`'s own (now unreachable) navigation event check — harmless, and matches "leave Rajoo/Dashboard code untouched."

- [ ] **Step 2: Rewrite `AppNavGraph.kt`**

Replace the full contents of `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt` with:

```kotlin
package com.mitas.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.mitas.ppnam.station2aa.ui.login.LoginScreen
import com.mitas.ppnam.station2aa.ui.mixing.HopperScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.mitas.ppnam.station2aa.ui.mixing.MixingViewModel
import com.mitas.ppnam.station2aa.ui.mixing.PreMixCompleteScreen
import com.mitas.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.mitas.ppnam.station2aa.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.MIXING) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.MIXING) {
            composable(NavRoutes.JOB_LOOKUP) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                JobLookupScreen(
                    onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    onLogout = {
                        navController.navigate(NavRoutes.LOGIN) {
                            popUpTo(0)
                        }
                    },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.INGREDIENT_SCAN) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                IngredientScanScreen(
                    orderNo = orderNo,
                    onProceedToHopperScan = { navController.navigate(NavRoutes.hopperScan(orderNo)) },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.HOPPER_SCAN) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                HopperScanScreen(
                    orderNo = orderNo,
                    onProceed = { navController.navigate(NavRoutes.premixComplete(orderNo)) },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.PREMIX_COMPLETE) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                PreMixCompleteScreen(
                    orderNo = orderNo,
                    onCompleted = {
                        navController.navigate(NavRoutes.MIXING) {
                            popUpTo(NavRoutes.MIXING) { inclusive = true }
                        }
                    },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        composable(NavRoutes.RFID_RECOVERY) {
            RfidRecoveryScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

- [ ] **Step 3: Run the full unit test suite and the debug build**

Run: `./gradlew.bat testDebugUnitTest`
Expected: PASS — includes the `MixingViewModelTest` cases from Task 1 plus every previously-passing test (`HomeViewModelTest`, `DashboardViewModelTest`, `RajooViewModel` tests etc. are untouched and still compile since their source files are untouched; they're just no longer reachable from the nav graph).

Run: `./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(nav): land on Job Lookup after login, remove Home/Rajoo/Dashboard routes, wire RFID Pallet Lookup everywhere"
```

---

### Task 8: Manual end-to-end verification

**Files:** none (verification only).

**Interfaces:**
- Consumes: the fully wired app from Tasks 1–7.
- Produces: nothing further depends on this.

- [ ] **Step 1: Install and launch the debug build**

Run: `./gradlew.bat installDebug`
Then launch the app on the connected device/emulator.

- [ ] **Step 2: Verify the landing flow**

Log in with valid credentials. Expected: the app goes straight to the Job Lookup screen (no Home/mode-select screen appears). The top bar shows the operator name badge and a Settings icon.

- [ ] **Step 3: Verify RFID Pallet Lookup preserves state — Job Lookup**

On Job Lookup, type a partial production order number into the text field (don't submit). Tap the RFID Pallet Lookup icon in the top bar. Tap "Done" (or back) to return. Expected: back on Job Lookup with the partially-typed order number still in the field.

- [ ] **Step 4: Verify RFID Pallet Lookup preserves state — Ingredient Scan**

Look up a job card to reach Ingredient Scan. Open one of the dialogs (e.g. tap "Cancel" to open the cancel-job confirmation, or trigger the bag-details dialog via a pallet scan). Tap the RFID Pallet Lookup icon in the top bar, then return. Expected: the dialog that was open is still open, and any typed values (e.g. bag count) are unchanged. Confirm a fresh scan on the pallet-scan listener still resumes normally after returning (scan a pallet tag and see the bag-details dialog appear).

- [ ] **Step 5: Verify RFID Pallet Lookup on Hopper Scan and Pre-Mix Complete**

From Hopper Scan and from Pre-Mix Complete (Review Pre-Mix), open RFID Pallet Lookup and return each time. Expected: same screen, same state, each time.

- [ ] **Step 6: Verify pre-mix completion returns to a fresh Job Lookup**

Complete a pre-mix through to "Pre-Mix Complete" and tap "Done". Expected: lands back on a fresh Job Lookup (empty order-number field, no residual state from the completed job).

- [ ] **Step 7: Verify logout and Settings from Job Lookup**

From Job Lookup, tap Settings, confirm it opens and its back arrow returns to Job Lookup. From Job Lookup, tap the operator name badge, confirm the logout dialog appears, confirm logging out returns to the Login screen.
