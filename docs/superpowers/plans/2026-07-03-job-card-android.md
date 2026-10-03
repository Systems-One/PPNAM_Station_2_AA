# Job Card Lifecycle — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Active job list with tap-to-load on `JobLookupScreen`, per-line allocation status on `IngredientScanScreen`, and a cancel flow that requires manager/admin approval for Worker-role operators and waits for backend confirmation before closing.

**Architecture:** Extends the existing `MixingUseCase` → `MixingViewModel` → Compose screen layering already used for `lookupJob`. New MQTT contract calls (`active_job_cards_requested`/`active_job_cards_list`, extended `premix_cancelled`/`premix_cancel_result`) go through the same `MqttRepository.sendTyped` request/response path `lookupJob` already uses — no new transport machinery.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, kotlinx-coroutines, Gson, JUnit4 + Mockito-Kotlin.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-03-job-card-lifecycle-design.md` — every requirement below traces to §B1/§B2/§B3 there.
- Backend dependency: `docs/superpowers/plans/2026-07-03-job-card-backend.md` (in the sibling `PPNAM-Station-2` repo) implements the server side these calls target. This plan's own tests all mock `MqttRepository`, so it does not require the backend plan to be executed first — but end-to-end manual verification (noted per task) does.
- Test command: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.<ClassName>"` (run from `C:\Dev\PPNAM_Station_2_AA`, Git Bash).
- This project has no Compose UI test harness today (confirmed: only unit tests exist under `app/src/test`). UI-only steps (screen composition) are verified manually per-task rather than with an automated test — noted explicitly where that applies, matching how the companion reconnection-fix plan already handles its one untestable step.
- Badge-based manager approval is explicitly **out of scope this pass** (per spec §B3) — only username/password approval UI is built. The `managerBadgeTag` wire field exists for forward-compatibility but the app always sends it blank.

---

### Task 1: Per-line allocation status (§B2)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt` (new)
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Produces: `BomLine.remainingQty: Double` (new field, default `0.0`) and `BomLine.isFullyAllocated: Boolean` (computed, `remainingQty <= 0.0`). Consumed by `IngredientScanScreen`.
- Consumes: `BomLineResponse.remainingQuantity` (already exists on the DTO, currently unused — `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt:18`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.*
import org.junit.Test

class BomLineTest {

    @Test
    fun `isFullyAllocated is true when remainingQty is zero`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = 0.0)
        assertTrue(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated is true when remainingQty is negative`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = -1.5)
        assertTrue(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated is false when remainingQty is positive`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = 12.0)
        assertFalse(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated defaults to true when remainingQty is not specified`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0)
        assertTrue(line.isFullyAllocated)
    }
}
```

Add to `MixingUseCaseTest.kt`, in the `lookupJob` section (after `` `lookupJob success caches bom and returns ProductionOrder` ``):

```kotlin
    @Test
    fun `lookupJob carries remainingQty through for every manual line`() = runTest {
        val response = BomLoadedResponse(
            accepted = true,
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            preMixId = "premix-1",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 50.0, remainingQuantity = 0.0,
                    issueType = "im_Manual"
                ),
                BomLineResponse(
                    materialCode = "MAT-002", materialName = "Colorant",
                    plannedQuantity = 10.0, issuedQuantity = 3.0, remainingQuantity = 7.0,
                    issueType = "im_Manual"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val order = useCase.lookupJob("510019068").getOrThrow()

        val resin = order.lines.single { it.itemCode == "MAT-001" }
        val colorant = order.lines.single { it.itemCode == "MAT-002" }
        assertEquals(0.0, resin.remainingQty, 0.0001)
        assertTrue(resin.isFullyAllocated)
        assertEquals(7.0, colorant.remainingQty, 0.0001)
        assertFalse(colorant.isFullyAllocated)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.BomLineTest"`
Expected: FAIL to compile — `BomLine` has no `remainingQty` parameter or `isFullyAllocated` member yet.

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: FAIL — same compile error propagates.

- [ ] **Step 3: Extend `BomLine`**

In `ProductionOrder.kt`, change:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val valid: Boolean = true,
    val reason: String? = null
)
```

to:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val remainingQty: Double = 0.0,
    val valid: Boolean = true,
    val reason: String? = null
) {
    val isFullyAllocated: Boolean get() = remainingQty <= 0.0
}
```

- [ ] **Step 4: Carry `remainingQty` through the `lookupJob` mapping**

In `MixingUseCase.kt`, in `lookupJob`, change:

```kotlin
                        lines = response.ingredients
                            .filter { it.issueType != "im_Backflush" }
                            .map { line ->
                                BomLine(
                                    itemCode = line.materialCode,
                                    itemName = line.materialName,
                                    requiredQty = line.plannedQuantity,
                                    scannedQty = line.issuedQuantity
                                )
                            }
```

to:

```kotlin
                        lines = response.ingredients
                            .filter { it.issueType != "im_Backflush" }
                            .map { line ->
                                BomLine(
                                    itemCode = line.materialCode,
                                    itemName = line.materialName,
                                    requiredQty = line.plannedQuantity,
                                    scannedQty = line.issuedQuantity,
                                    remainingQty = line.remainingQuantity
                                )
                            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.BomLineTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: PASS (all tests, including the two pre-existing backflush-separation tests, which don't set `remainingQuantity` so default to `0.0` — i.e. fully allocated by default, which doesn't break their assertions since they don't check `remainingQty`).

- [ ] **Step 6: Update `IngredientScanScreen` to treat fully-allocated lines as satisfied**

In `IngredientScanScreen.kt`, change the header's satisfied-count computation from:

```kotlin
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine ->
                            scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                        }
                        val allSatisfied = satisfiedCount == order.lines.size
```

to:

```kotlin
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine ->
                            bomLine.isFullyAllocated ||
                                scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                        }
                        val allSatisfied = satisfiedCount == order.lines.size
```

Then, inside the `LazyColumn`'s `items(order.lines)` block, change:

```kotlin
                            items(order.lines) { bomLine ->
                                val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                                val required = bomLine.requiredQty.toInt().coerceAtLeast(1)
                                val satisfied = scannedCount >= required
                                val fraction = (scannedCount.toFloat() / required.toFloat()).coerceIn(0f, 1f)
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.10f) else GraphiteSurface
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (satisfied) SuccessGreen.copy(alpha = 0.30f) else GraphiteBorder
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (satisfied) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Satisfied",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = "$scannedCount / $required",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
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
                                }
                            }
```

to:

```kotlin
                            items(order.lines) { bomLine ->
                                val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                                val required = bomLine.requiredQty.toInt().coerceAtLeast(1)
                                val satisfied = bomLine.isFullyAllocated || scannedCount >= required
                                val fraction = (scannedCount.toFloat() / required.toFloat()).coerceIn(0f, 1f)
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.10f) else GraphiteSurface
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (satisfied) SuccessGreen.copy(alpha = 0.30f) else GraphiteBorder
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (satisfied) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Satisfied",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = if (bomLine.isFullyAllocated) "Fully Allocated" else "$scannedCount / $required",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
                                        }
                                        if (!bomLine.isFullyAllocated) {
                                            Spacer(Modifier.height(8.dp))
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
                                    }
                                }
                            }
```

- [ ] **Step 7: Manual verification (no Compose test harness in this project)**

Run the app, look up a job whose backend response includes a line with `remainingQuantity: 0` (or, until the backend plan ships, temporarily hardcode a test `BomLoadedResponse` locally to confirm the UI): confirm that line shows a "Fully Allocated" label instead of a progress bar and a scan counter, shows the green satisfied styling immediately with zero scans, and that "Proceed to Hopper Scan" enables once every other line is satisfied — without ever needing to scan that line.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): surface per-line allocation status from bom_loaded"
```

---

### Task 2: Active job list — DTOs, use case, view model (§B1)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Produces: `MixingUseCase.fetchActiveJobCards(): Result<List<ActiveJobCardSummary>>`. `MixingViewModel.activeJobs: StateFlow<List<ActiveJobCardSummary>>`, `MixingViewModel.activeJobsError: StateFlow<String?>`, `MixingViewModel.loadActiveJobs(): Unit`.
- Consumes: `MqttRepository.sendTyped` (existing), `SettingsRepository.current()` (existing), `OperatorSessionHolder.currentSessionIdOrEmpty()` (existing).

- [ ] **Step 1: Write the failing tests**

Add to `MixingUseCaseTest.kt`, in a new section after `notifyJobCardCancelled`:

```kotlin
    // --- fetchActiveJobCards ---

    @Test
    fun `fetchActiveJobCards returns the job list on success`() = runTest {
        val response = ActiveJobCardsListResponse(
            accepted = true,
            jobs = listOf(
                ActiveJobCardSummary(
                    jobCardNumber = "510019068",
                    productionOrderDocumentNumber = "510019068",
                    preMixId = "premix-1",
                    productName = "Layer Mash",
                    status = "Open"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("510019068", result.getOrThrow().first().jobCardNumber)
        assertEquals("Layer Mash", result.getOrThrow().first().productName)
    }

    @Test
    fun `fetchActiveJobCards returns failure when backend rejects`() = runTest {
        val response = ActiveJobCardsListResponse(accepted = false, reason = "Operator session is not active for this RFID device. Log in again on this reader.")
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Operator session is not active for this RFID device. Log in again on this reader.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchActiveJobCards returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("active_job_cards_requested"), eq("active_job_cards_list"), any(),
                eq(ActiveJobCardsListResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }
```

Add to `MixingViewModelTest.kt`, after the `cancelJob` test:

```kotlin
    @Test
    fun `loadActiveJobs populates activeJobs on success`() = runTest {
        val jobs = listOf(
            com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary(
                jobCardNumber = "510019068", productName = "Layer Mash", status = "Open"
            )
        )
        whenever(mockUseCase.fetchActiveJobCards()).thenReturn(Result.success(jobs))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertEquals(jobs, viewModel.activeJobs.value)
        assertEquals(null, viewModel.activeJobsError.value)
    }

    @Test
    fun `loadActiveJobs sets activeJobsError on failure and leaves list untouched`() = runTest {
        whenever(mockUseCase.fetchActiveJobCards()).thenReturn(Result.failure(Exception("Not connected to Station 2")))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertTrue(viewModel.activeJobs.value.isEmpty())
        assertEquals("Not connected to Station 2", viewModel.activeJobsError.value)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: FAIL to compile — `ActiveJobCardsListResponse`/`ActiveJobCardSummary` and `fetchActiveJobCards` don't exist yet.

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: FAIL to compile — `activeJobs`/`activeJobsError`/`loadActiveJobs` don't exist yet.

- [ ] **Step 3: Add the DTOs**

In `JobCardMessages.kt`, add after `BomLoadedResponse`:

```kotlin
data class ActiveJobCardsRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String
)

data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val preMixId: String = "",
    val productName: String = "",
    val status: String = ""
)

data class ActiveJobCardsListResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val jobs: List<ActiveJobCardSummary> = emptyList()
)
```

- [ ] **Step 4: Add `fetchActiveJobCards` to `MixingUseCase`**

Add the import `import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse`, `import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsRequest`, and `import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary` to `MixingUseCase.kt`.

Add this method (after `lookupJob`):

```kotlin
    suspend fun fetchActiveJobCards(): Result<List<ActiveJobCardSummary>> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            ActiveJobCardsRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = UUID.randomUUID().toString()
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "active_job_cards_requested",
            responseType = "active_job_cards_list",
            requestJson = requestJson,
            responseClass = ActiveJobCardsListResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response.jobs)
                else Result.failure(Exception(response.reason ?: "Could not load active jobs"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }
```

- [ ] **Step 5: Add `activeJobs`/`loadActiveJobs` to `MixingViewModel`**

Add the import `import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary` to `MixingViewModel.kt`.

Add these members (after `pendingCount`):

```kotlin
    private val _activeJobs = MutableStateFlow<List<ActiveJobCardSummary>>(emptyList())
    val activeJobs: StateFlow<List<ActiveJobCardSummary>> = _activeJobs.asStateFlow()

    private val _activeJobsError = MutableStateFlow<String?>(null)
    val activeJobsError: StateFlow<String?> = _activeJobsError.asStateFlow()
```

Add this method (after `lookupJob`):

```kotlin
    fun loadActiveJobs() {
        viewModelScope.launch {
            useCase.fetchActiveJobCards()
                .onSuccess { jobs ->
                    _activeJobs.value = jobs
                    _activeJobsError.value = null
                }
                .onFailure { e -> _activeJobsError.value = e.message ?: "Could not load active jobs" }
        }
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: PASS.

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): add active_job_cards_requested fetch to use case and view model"
```

---

### Task 3: `JobLookupScreen` — render active jobs, tap-to-load (§B1)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`

**Interfaces:**
- Consumes: `MixingViewModel.activeJobs`, `MixingViewModel.activeJobsError`, `MixingViewModel.loadActiveJobs()` (Task 2), `MixingViewModel.lookupJob(String)` (existing).

No new automated test — this project has no Compose UI test harness (see Global Constraints). Verify manually per Step 3.

- [ ] **Step 1: Add the active jobs list to the screen**

In `JobLookupScreen.kt`, change:

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
    var orderInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is MixingUiState.OrderLoaded) {
            onJobFound((uiState as MixingUiState.OrderLoaded).order.docNo)
        }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            OutlinedTextField(
                value = orderInput,
                onValueChange = { orderInput = it },
                label = { Text("Production Order No.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(text = err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

to:

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

    LaunchedEffect(uiState) {
        if (uiState is MixingUiState.OrderLoaded) {
            onJobFound((uiState as MixingUiState.OrderLoaded).order.docNo)
        }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
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
                    items(activeJobs) { job ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber) },
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, GraphiteBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(job.jobCardNumber, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                if (job.productName.isNotBlank()) {
                                    Text(job.productName, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(text = err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

Note the removed `verticalArrangement = Arrangement.Center` on the outer `Column` — with a variable-height job list above the form, centering the whole column would jump the form around as the list loads; top-aligned (the `Column` default) keeps the form in a stable position.

Add the missing imports at the top of the file: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.heightIn`, `androidx.compose.foundation.lazy.LazyColumn`, `androidx.compose.foundation.lazy.items`, `androidx.compose.material3.Card`, `androidx.compose.material3.CardDefaults`, `com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface`, `com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder`, `com.mitas.ppnam.station2aa.ui.theme.TextPrimary`, `com.mitas.ppnam.station2aa.ui.theme.TextMuted`.

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification**

Run the app, navigate to Job Lookup: confirm the active jobs list loads on screen entry (spinner/list appears without user action), tapping a job row navigates straight into `IngredientScanScreen` for that job (no extra tap needed), and that a disconnected/error state shows the inline error text without blocking manual entry in the text field below it.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt
git commit -m "feat(mixing): show active job list with tap-to-load on Job Lookup screen"
```

---

### Task 4: Cancel DTOs and use case (§B3)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Produces: `MixingUseCase.cancelJob(preMixId: String, jobCardNumber: String, reason: String, managerUsername: String = "", managerPassword: String = ""): Result<PreMixCancelResultResponse>`. Replaces `notifyJobCardCancelled` (deleted).
- Consumes: `MqttRepository.sendTyped` (existing).

- [ ] **Step 1: Write the failing tests**

In `MixingUseCaseTest.kt`, delete the two existing `notifyJobCardCancelled` tests (`` `notifyJobCardCancelled publishes with preMixId as correlationKey when present` `` and `` `notifyJobCardCancelled falls back to jobCardNumber as correlationKey when no preMixId` ``) and the `// --- notifyJobCardCancelled ---` comment — the method they test is being removed. Replace that section with:

```kotlin
    // --- cancelJob ---

    @Test
    fun `cancelJob succeeds without manager credentials when not required`() = runTest {
        val response = PreMixCancelResultResponse(
            accepted = true,
            preMixId = "premix-1",
            jobCardNumber = "510019068",
            preMixStatus = "Cancelled",
            nextAction = "scan_job_card"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.cancelJob("premix-1", "510019068", "Operator cancelled — incorrect job card")

        assertTrue(result.isSuccess)
        assertEquals("Cancelled", result.getOrThrow().preMixStatus)
    }

    @Test
    fun `cancelJob sends manager credentials in the request payload when provided`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(PreMixCancelResultResponse(accepted = true)))

        useCase.cancelJob("premix-1", "510019068", "reason", managerUsername = "Manager1", managerPassword = "5678")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("premix_cancelled"), eq("premix_cancel_result"), captor.capture(),
            eq(PreMixCancelResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"managerUsername\":\"Manager1\""))
        assertTrue(captor.firstValue.contains("\"managerPassword\":\"5678\""))
    }

    @Test
    fun `cancelJob returns failure with backend reason when rejected`() = runTest {
        val response = PreMixCancelResultResponse(accepted = false, reason = "Manager or admin approval is required.")
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.cancelJob("premix-1", "510019068", "reason")

        assertTrue(result.isFailure)
        assertEquals("Manager or admin approval is required.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancelJob returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("premix_cancelled"), eq("premix_cancel_result"), any(),
                eq(PreMixCancelResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.cancelJob("premix-1", "510019068", "reason")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: FAIL to compile — `PreMixCancelResultResponse` doesn't exist and `cancelJob` doesn't exist yet (the old `notifyJobCardCancelled` tests were just deleted, so no conflicting failures from those).

- [ ] **Step 3: Extend `PreMixCancelledRequest` and add `PreMixCancelResultResponse`**

In `JobCardMessages.kt`, change:

```kotlin
// Not part of the current RFID_MQTT_CONTRACT — the backend has a PreMixStatus.Cancelled
// value but nothing sets it yet, and RfidWorkflowMessageProcessor has no handler for this
// request type, so it is silently dropped for now. Sent best-effort so the app is ready
// the moment the backend adds a handler.
data class PreMixCancelledRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val jobCardNumber: String,
    val reason: String = "Operator cancelled — incorrect job card"
)
```

to:

```kotlin
data class PreMixCancelledRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val jobCardNumber: String,
    val reason: String = "Operator cancelled — incorrect job card",
    val managerUsername: String = "",
    val managerPassword: String = "",
    val managerBadgeTag: String = ""
)

data class PreMixCancelResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val preMixId: String = "",
    val jobCardNumber: String = "",
    val preMixStatus: String = "",
    val nextAction: String = "",
    val approverUserId: String = "",
    val approverDisplayName: String = "",
    val approverRole: String = ""
)
```

- [ ] **Step 4: Replace `notifyJobCardCancelled` with `cancelJob`**

In `MixingUseCase.kt`, add the import `import com.mitas.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse`.

Change:

```kotlin
    suspend fun notifyJobCardCancelled(jobCardNumber: String, preMixId: String) {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            PreMixCancelledRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = preMixId.ifBlank { jobCardNumber },
                preMixId = preMixId,
                jobCardNumber = jobCardNumber
            )
        )
        mqttRepository.publishTyped("premix_cancelled", requestJson)
    }
```

to:

```kotlin
    suspend fun cancelJob(
        preMixId: String,
        jobCardNumber: String,
        reason: String,
        managerUsername: String = "",
        managerPassword: String = ""
    ): Result<PreMixCancelResultResponse> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            PreMixCancelledRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = preMixId.ifBlank { jobCardNumber },
                preMixId = preMixId,
                jobCardNumber = jobCardNumber,
                reason = reason,
                managerUsername = managerUsername,
                managerPassword = managerPassword
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "premix_cancelled",
            responseType = "premix_cancel_result",
            requestJson = requestJson,
            responseClass = PreMixCancelResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response)
                else Result.failure(Exception(response.reason ?: "Cancel rejected"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): replace fire-and-forget premix_cancelled with a real request/response"
```

---

### Task 5: `MixingViewModel` cancel state machine and role gate (§B3)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Produces: `MixingUiState.Cancelling` (new sealed subtype). `CancelOutcome` sealed class (`Confirmed`, `Failed(reason: String)`). `MixingViewModel.cancelOutcome: Flow<CancelOutcome>`. `MixingViewModel.operatorCanCancelDirectly(): Boolean`. `MixingViewModel.cancelJob(managerUsername: String = "", managerPassword: String = "")` — replaces the old synchronous `cancelJob()` (same name, new signature and behavior: now suspends, waits for backend confirmation, and does not clear state on failure).
- Consumes: `MixingUseCase.cancelJob(...)` (Task 4), `OperatorSessionHolder.session` (existing — this task adds it as a new constructor dependency).

- [ ] **Step 1: Write the failing tests**

In `MixingViewModelTest.kt`, add the import `import com.mitas.ppnam.station2aa.data.session.OperatorSession` and `import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder`.

Change the `setup()` method from:

```kotlin
    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        preMixId = "premix-1",
        lines = listOf(BomLine("MAT-001", "Resin", 1.0))
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.hopperStatusUpdates)
            .thenReturn(MutableSharedFlow())
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())

        viewModel = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository)
    }
```

to:

```kotlin
    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        preMixId = "premix-1",
        lines = listOf(BomLine("MAT-001", "Resin", 1.0))
    )

    private fun sessionWithActions(vararg actions: String) = OperatorSession(
        operatorSessionId = "session-id",
        operatorId = "op-1",
        operatorName = "Test Operator",
        role = "Worker",
        allowedActions = actions.toList()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.hopperStatusUpdates)
            .thenReturn(MutableSharedFlow())
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(sessionWithActions("cancel_premix")))

        viewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockSessionHolder
        )
    }
```

Change the existing `` `cancelJob resets state and scanned ingredients so a new job can be looked up` `` test (which calls the old synchronous `viewModel.cancelJob()`) to match the new async signature:

```kotlin
    @Test
    fun `cancelJob resets state and scanned ingredients on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val exceptionIngredient = ScannedIngredient("TAG-001", "MAT-001", 1.0)
        whenever(mockUseCase.approveIngredientException(any(), any(), any()))
            .thenReturn(Result.success(exceptionIngredient))
        viewModel.requestSupervisorOverride("TAG-001", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-001", "SUP-001")
        advanceUntilIdle()
        assertTrue(viewModel.scannedIngredients.value.isNotEmpty())

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse(accepted = true))
        )
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob()
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
        assertTrue(viewModel.scannedIngredients.value.isEmpty())
        assertEquals("", viewModel.hopperCode.value)
        assertTrue(outcomes.contains(CancelOutcome.Confirmed))
        job.cancel()
    }

    @Test
    fun `cancelJob on rejection keeps the order loaded and emits Failed`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(Exception("Pre-mix has ingredient activity and cannot be closed.")))
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        assertEquals(
            listOf(CancelOutcome.Failed("Pre-mix has ingredient activity and cannot be closed.")),
            outcomes
        )
        job.cancel()
    }

    @Test
    fun `cancelJob passes manager credentials through to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse(accepted = true))
        )

        viewModel.cancelJob(managerUsername = "Manager1", managerPassword = "5678")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(
            eq("premix-1"), eq("510019068"), any(), eq("Manager1"), eq("5678")
        )
    }

    @Test
    fun `operatorCanCancelDirectly reflects the cancel_premix_direct allowed action`() = runTest {
        whenever(mockSessionHolder.session).thenReturn(
            MutableStateFlow(sessionWithActions("cancel_premix", "cancel_premix_direct"))
        )
        val directViewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockSessionHolder
        )

        assertTrue(directViewModel.operatorCanCancelDirectly())
    }

    @Test
    fun `operatorCanCancelDirectly is false without the capability`() = runTest {
        assertFalse(viewModel.operatorCanCancelDirectly())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: FAIL to compile — `MixingViewModel`'s constructor doesn't take a fifth `OperatorSessionHolder` parameter yet, `MixingUiState.Cancelling`/`CancelOutcome`/`cancelOutcome`/`operatorCanCancelDirectly` don't exist, and `useCase.cancelJob(...)`'s 5-arg signature doesn't match the old `notifyJobCardCancelled`.

- [ ] **Step 3: Add `Cancelling` state and `CancelOutcome`**

In `MixingViewModel.kt`, change:

```kotlin
sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}
```

to:

```kotlin
sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    object Cancelling : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}
```

- [ ] **Step 4: Add `OperatorSessionHolder` dependency and the cancel state machine**

Add the imports:

```kotlin
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
```

Change the constructor from:

```kotlin
@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {
```

to:

```kotlin
@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository,
    private val sessionHolder: OperatorSessionHolder
) : ViewModel() {
```

Add this sealed class near `MixingNavDestination`:

```kotlin
sealed class CancelOutcome {
    object Confirmed : CancelOutcome()
    data class Failed(val reason: String) : CancelOutcome()
}
```

Add these members (after `_supervisorError`):

```kotlin
    private val _cancelOutcome = Channel<CancelOutcome>(Channel.BUFFERED)
    val cancelOutcome: Flow<CancelOutcome> = _cancelOutcome.receiveAsFlow()
```

Replace the existing `cancelJob()` method:

```kotlin
    // Lets an operator back out of a wrongly-loaded job card. Resets local state and
    // notifies the backend best-effort (there's no handler for this yet — see
    // MixingUseCase.notifyJobCardCancelled — so this is forward-looking, not a
    // guarantee the server does anything with it today).
    fun cancelJob() {
        scanJob?.cancel()
        val jobCardNumber = currentOrderNo
        val preMixId = cachedOrder?.preMixId ?: ""
        if (jobCardNumber.isNotBlank()) {
            viewModelScope.launch { useCase.notifyJobCardCancelled(jobCardNumber, preMixId) }
        }
        currentOrderNo = ""
        cachedOrder = null
        _scannedIngredients.value = emptyList()
        _hopperCode.value = ""
        _isQueuedOffline.value = false
        _uiState.value = MixingUiState.Idle
    }
```

with:

```kotlin
    fun operatorCanCancelDirectly(): Boolean =
        sessionHolder.session.value?.allowedActions?.contains("cancel_premix_direct") == true

    // Waits for premix_cancel_result before touching any local state — a rejected
    // cancel (e.g. the pre-mix already has scanned ingredients, or the manager
    // approval was denied) must leave the job exactly as it was, per the backend's
    // "only an untouched JC load can be closed" rule.
    fun cancelJob(managerUsername: String = "", managerPassword: String = "") {
        val jobCardNumber = currentOrderNo
        val preMixId = cachedOrder?.preMixId ?: ""
        if (jobCardNumber.isBlank()) return
        scanJob?.cancel()
        val orderBeforeCancel = cachedOrder
        viewModelScope.launch {
            _uiState.value = MixingUiState.Cancelling
            useCase.cancelJob(
                preMixId,
                jobCardNumber,
                "Operator cancelled — incorrect job card",
                managerUsername,
                managerPassword
            )
                .onSuccess {
                    currentOrderNo = ""
                    cachedOrder = null
                    _scannedIngredients.value = emptyList()
                    _hopperCode.value = ""
                    _isQueuedOffline.value = false
                    _uiState.value = MixingUiState.Idle
                    _cancelOutcome.send(CancelOutcome.Confirmed)
                }
                .onFailure { e ->
                    _uiState.value = orderBeforeCancel?.let { MixingUiState.OrderLoaded(it) } ?: MixingUiState.Idle
                    if (orderBeforeCancel != null) startListeningForScans(jobCardNumber)
                    _cancelOutcome.send(CancelOutcome.Failed(e.message ?: "Cancel failed"))
                }
        }
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Fix the DI graph and any other constructor call sites**

`MixingViewModel` is `@HiltViewModel`-annotated with `@Inject constructor`, so Hilt resolves the new `OperatorSessionHolder` parameter automatically (it's already a `@Singleton @Inject constructor()` — no `AppModule` changes needed). Search for any other direct instantiation:

Run: `grep -rn "MixingViewModel(" app/src/main app/src/test`

Expected: only the `@Inject constructor` declaration itself and the test file just updated. If any other call site turns up, update it to pass a mocked/real `OperatorSessionHolder` the same way.

- [ ] **Step 7: Compile and run the full app test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): cancelJob waits for backend confirmation and gates on cancel_premix_direct"
```

---

### Task 6: `IngredientScanScreen` — approval dialog and outcome handling (§B3)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: `MixingViewModel.operatorCanCancelDirectly()`, `MixingViewModel.cancelJob(managerUsername, managerPassword)`, `MixingViewModel.cancelOutcome` (Task 5), `MixingUiState.Cancelling` (Task 5).

No new automated test — see Global Constraints. Verify manually per Step 3.

- [ ] **Step 1: Replace the cancel dialog with the two-step confirm/approval flow**

In `IngredientScanScreen.kt`, change:

```kotlin
    var showCancelDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.supervisorError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                Text(
                    "Any scanned ingredients on this job will be discarded. You can look up the correct job card afterwards.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancelJob()
                    onBack()
                }) { Text("Cancel Job", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Keep Scanning") }
            },
            containerColor = GraphiteSurface
        )
    }
```

to:

```kotlin
    var showCancelDialog by remember { mutableStateOf(false) }
    var showApprovalDialog by remember { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.supervisorError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.cancelOutcome.collect { outcome ->
            when (outcome) {
                is CancelOutcome.Confirmed -> onBack()
                is CancelOutcome.Failed -> {
                    managerUsername = ""
                    managerPassword = ""
                    snackbarHostState.showSnackbar(outcome.reason)
                }
            }
        }
    }

    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }

    val isCancelling = uiState is MixingUiState.Cancelling

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCancelling) showCancelDialog = false },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                Text(
                    "This closes the job card if it hasn't had any activity yet (ingredients scanned, hopper assigned, SAP issue, etc). You'll be notified if it can't be cancelled.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        if (viewModel.operatorCanCancelDirectly()) {
                            showCancelDialog = false
                            viewModel.cancelJob()
                        } else {
                            showCancelDialog = false
                            showApprovalDialog = true
                        }
                    }
                ) { Text("Cancel Job", color = DangerRed) }
            },
            dismissButton = {
                TextButton(enabled = !isCancelling, onClick = { showCancelDialog = false }) { Text("Keep Scanning") }
            },
            containerColor = GraphiteSurface
        )
    }

    if (showApprovalDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCancelling) showApprovalDialog = false },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your role can't cancel a job card directly. Ask a manager or admin to enter their credentials to approve this cancellation.",
                        color = TextMuted
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
                TextButton(enabled = !isCancelling, onClick = { showApprovalDialog = false }) { Text("Back") }
            },
            containerColor = GraphiteSurface
        )
    }
```

Add the missing imports: `androidx.compose.ui.text.input.PasswordVisualTransformation`, `com.mitas.ppnam.station2aa.ui.mixing.MixingViewModel` (may already be implicitly in scope since this file is in the same package — confirm no import is actually needed since `MixingViewModel`/`MixingUiState` are already same-package types used elsewhere in this file).

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification**

Two scenarios, both against a real or locally-stubbed backend:

1. **Direct cancel (Manager/Admin session):** log in as a Manager/Administrator badge, load a job, tap Cancel Job → confirm. Dialog should show a brief loading state then close straight back to Job Lookup, with no approval dialog shown at any point.
2. **Approval-gated cancel (Worker session):** log in as a Worker badge, load a job, tap Cancel Job → confirm. The approval dialog should appear; entering wrong credentials should show the rejection reason in a snackbar and leave the job card still loaded (still on `IngredientScanScreen`, ingredients not cleared); entering valid manager credentials should close the screen back to Job Lookup, and Job Lookup's active list should reflect the job no longer being active (Task 3's `LaunchedEffect(Unit)` re-running on screen re-entry already covers the refresh — confirm it actually fires by observing the list on return).

Also confirm: scanning ingredients first, then attempting a cancel, surfaces the backend's real rejection reason ("only an untouched JC load can be closed" wording, exact text is backend-owned — see `docs/superpowers/plans/2026-07-03-job-card-backend.md` Task 3) via the snackbar, and the job remains loaded with its scanned ingredients intact.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(mixing): approval dialog for role-gated job card cancellation"
```
