# JC-Driven Mixing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the Android handheld's Mixing feature from the withdrawn mixer-plan / destination-assignment model to the JC-driven model, where a completed collection plus equipment scans plus server-issued cycle IDs drive everything.

**Architecture:** Hard cutover — plan and reservation code is deleted, not flagged off. The wire layer (`MixingMessages.kt`) is reshaped first, then the domain and use case, then the ViewModel and Compose screen. The one safety property worth naming: `MachineCycleStartPayload` gains six optional fields that could be combined illegally, so nothing constructs it directly — six named use-case functions are the only builders.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson, Coroutines, JUnit4 + Mockito-Kotlin. Package `com.mitas.ppnam.station2aa`.

**Spec:** `docs/superpowers/specs/2026-07-28-jc-driven-mixing-design.md`

## Global Constraints

- **Schema stays `4.1`.** No envelope, SCRAM, paging or Station-3 changes — those are already compliant. Do not touch `RequestEnvelope.kt`, `ResponseEnvelope.kt`, `MqttSchema.kt`, `MqttTopics.kt`, `AuthMessages.kt`, `PalletMessages.kt`, `IngredientMessages.kt`.
- **Every DTO constructor parameter keeps a default value.** Kotlin only emits the no-arg constructor Gson needs when every parameter has a default. Drop one and Gson silently falls back to `UnsafeAllocator`, every field deserializes to null regardless of declared type, and there is no compile error. This is documented in `ResponseEnvelope.kt` and is not optional.
- **Never infer server state locally.** Availability, scan permission and valid destinations come only from `scanAllowed`, `validMixerCodes`, `validNextMachineCodes`. No local predicate may gate a scan affordance.
- **`ErrorCode` and `NextAction` stay value classes.** Unknown codes must pass through intact, never fail a parse.
- **Omit, never null.** Optional request fields are absent from the JSON when unused; Gson's default omits nulls, so model them as `null`-defaulted properties.
- **Out of scope, do not edit:** `tools/backend-sim/`, `tools/test-harness/`, `docs/TEST_PLAN.md`, and everything in `C:\Dev\PPNAM-Station-2`.
- **Test command:** `./gradlew test` (full suite). Single class: `./gradlew testDebugUnitTest --tests "*ClassName*"` — `test` is a lifecycle task on this project and rejects `--tests`.
- **Java is not on PATH.** In Bash, before any Gradle command: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- **Baseline:** the full suite is green before Task 1 and must be green at the end of every task.

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `data/mqtt/MqttVocabulary.kt` | `ErrorCode` / `NextAction` catalogues | 1 |
| `data/mqtt/dto/MixingMessages.kt` | Mixing wire shapes | 3, 4, 5, 6 |
| `data/mqtt/dto/JobCardMessages.kt` | `CollectionResumePayload` only | 4 |
| `domain/model/MixingBoard.kt` | Mixing domain types | 3, 4, 5, 6 |
| `domain/usecase/MixingBoardUseCase.kt` | Board server operations, DTO→domain mapping | 3, 4, 5, 6 |
| `ui/mixing/board/MixingBoardViewModel.kt` | Board state, selection, dispatch | 1, 2, 3, 5, 7 |
| `ui/mixing/board/MixingBoardScreen.kt` | Board Compose UI | 2, 3, 7, 8 |
| `ui/mixing/MixingViewModel.kt` | Collection flow — one `nextAction` call site | 1 |

Test files, modified alongside: `MqttVocabularyTest`, `MixingBoardUseCaseTest`, `MixingBoardViewModelTest`, `MixingUseCaseTest`.

**Task ordering rationale:** Kotlin couples layers at compile time, so tasks are vertical slices, not layers. Each task ends with `./gradlew test` green. Deletions come before additions so no task builds on a symbol another task is about to remove.

---

### Task 1: Vocabulary cutover

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt:736`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt:436-459`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt:60,135`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt:698`

**Interfaces:**
- Produces: `ErrorCode.COLLECTION_NOT_READY`, `.COLLECTION_ALREADY_MIXED`, `.ROUTE_REQUIRED`, `.WRONG_SCAN_SEQUENCE`, `.INVALID_DESTINATION`, `.RAJOO_DESTINATION_FORBIDDEN`, `.JANDI_DRUM_REQUIRED`, `.JANDI_DRUM_BUSY`, `.JANDI_MAIN_MIX_REQUIRED`, `.AMBIGUOUS_MAIN_MIX`, `.AUTHORIZATION_REQUIRED`, `.AUTHORIZATION_EXPIRED`; `NextAction.OPEN_MIXING`, `.SELECT_COLLECTION`, `.SELECT_JANDI_ROUTE`, `.SCAN_JANDI_DRUM_TO_START`, `.SCAN_JANDI_DRUM_TO_FINISH`, `.SELECT_MAIN_DESTINATION`, `.SCAN_DESTINATION_TO_START`, `.SELECT_JANDI4_MAIN_SOURCE`, `.SCAN_JANDI4_TO_START`, `.SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER`, `.REFRESH_MIXING_OVERVIEW`, `.COMPLETED`

- [ ] **Step 1: Write the failing test**

Replace the body of `MqttVocabularyTest.kt` between the class braces with:

```kotlin
    @Test
    fun `retired plan and two-phase codes are gone and JC-driven codes exist`() {
        assertEquals("collection_not_ready", ErrorCode.COLLECTION_NOT_READY.raw)
        assertEquals("collection_already_mixed", ErrorCode.COLLECTION_ALREADY_MIXED.raw)
        assertEquals("route_required", ErrorCode.ROUTE_REQUIRED.raw)
        assertEquals("wrong_scan_sequence", ErrorCode.WRONG_SCAN_SEQUENCE.raw)
        assertEquals("invalid_destination", ErrorCode.INVALID_DESTINATION.raw)
        assertEquals("rajoo_destination_forbidden", ErrorCode.RAJOO_DESTINATION_FORBIDDEN.raw)
        assertEquals("jandi_drum_required", ErrorCode.JANDI_DRUM_REQUIRED.raw)
        assertEquals("jandi_drum_busy", ErrorCode.JANDI_DRUM_BUSY.raw)
        assertEquals("jandi_main_mix_required", ErrorCode.JANDI_MAIN_MIX_REQUIRED.raw)
        assertEquals("ambiguous_main_mix", ErrorCode.AMBIGUOUS_MAIN_MIX.raw)
        assertEquals("authorization_required", ErrorCode.AUTHORIZATION_REQUIRED.raw)
        assertEquals("authorization_expired", ErrorCode.AUTHORIZATION_EXPIRED.raw)
    }

    @Test
    fun `JC-driven next actions exist and are plain constants`() {
        assertEquals("open_mixing", NextAction.OPEN_MIXING.raw)
        assertEquals("select_collection", NextAction.SELECT_COLLECTION.raw)
        assertEquals("select_jandi_route", NextAction.SELECT_JANDI_ROUTE.raw)
        assertEquals("scan_same_machine_to_finish", NextAction.SCAN_SAME_MACHINE_TO_FINISH.raw)
        assertEquals("scan_jandi_drum_to_start", NextAction.SCAN_JANDI_DRUM_TO_START.raw)
        assertEquals("scan_jandi_drum_to_finish", NextAction.SCAN_JANDI_DRUM_TO_FINISH.raw)
        assertEquals("select_main_destination", NextAction.SELECT_MAIN_DESTINATION.raw)
        assertEquals("scan_destination_to_start", NextAction.SCAN_DESTINATION_TO_START.raw)
        assertEquals("select_jandi4_main_source", NextAction.SELECT_JANDI4_MAIN_SOURCE.raw)
        assertEquals("scan_jandi4_to_start", NextAction.SCAN_JANDI4_TO_START.raw)
        assertEquals(
            "scan_additional_rajoo_layer_or_finish_active_layer",
            NextAction.SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER.raw)
        assertEquals("refresh_mixing_overview", NextAction.REFRESH_MIXING_OVERVIEW.raw)
        assertEquals("completed", NextAction.COMPLETED.raw)
    }

    @Test
    fun `an unrecognised code still passes through intact`() {
        // The server may add codes we have no constant for. Nothing downstream may treat an
        // unknown code as a parse failure.
        assertEquals("some_future_code", ErrorCode("some_future_code").raw)
        assertEquals("some_future_action", NextAction("some_future_action").raw)
    }
```

Keep the file's existing imports and the existing envelope-code assertions; delete only the two assertions naming `START_MIXING` and `SELECT_COLLECTION_MIX_OR_MACHINE`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MqttVocabularyTest*"`
Expected: FAIL — compilation error, `Unresolved reference: COLLECTION_NOT_READY`.

- [ ] **Step 3: Update the vocabulary**

In `MqttVocabulary.kt`, inside `ErrorCode.Companion`:

Delete these twelve lines and their comments: `MIXER_PLAN_REQUIRED`, `MIXER_NOT_IN_PLAN`, `MIXER_RESERVED`, `MIX_PLAN_LOCKED`, `INVALID_PLANNED_LAYER_INPUTS`, `INVALID_PLANNED_DESTINATION`, `MIX_CYCLE_NOT_ACTIVE`, `DESTINATION_ASSIGNMENT_LOCKED`, `DESTINATION_ASSIGNMENT_REQUIRED`, `DRUM_CYCLE_REQUIRED`, `JOB_CARD_MISMATCH`, `LEGACY_REQUEST_SHAPE`.

Add, replacing the deleted `// v4.1 cross-area mixer plans` block:

```kotlin
        // JC-driven Mixing (2026-07-28). Mixing is driven by a completed collection, its job
        // card, equipment scans and server-issued cycle IDs — there are no plans to violate.
        val COLLECTION_NOT_READY = ErrorCode("collection_not_ready")
        val COLLECTION_ALREADY_MIXED = ErrorCode("collection_already_mixed")
        val ROUTE_REQUIRED = ErrorCode("route_required")
        val WRONG_SCAN_SEQUENCE = ErrorCode("wrong_scan_sequence")
        val INVALID_DESTINATION = ErrorCode("invalid_destination")
        /** Main output may never be allocated to a Rajoo machine. Always rejected server-side. */
        val RAJOO_DESTINATION_FORBIDDEN = ErrorCode("rajoo_destination_forbidden")
        val JANDI_DRUM_REQUIRED = ErrorCode("jandi_drum_required")
        val JANDI_DRUM_BUSY = ErrorCode("jandi_drum_busy")
        val JANDI_MAIN_MIX_REQUIRED = ErrorCode("jandi_main_mix_required")
        /** A JANDI 4 start named a Main mixer code that resolves to more than one eligible mix. */
        val AMBIGUOUS_MAIN_MIX = ErrorCode("ambiguous_main_mix")
        val AUTHORIZATION_REQUIRED = ErrorCode("authorization_required")
        val AUTHORIZATION_EXPIRED = ErrorCode("authorization_expired")
```

Keep `DESTINATION_BUSY` — it survives into the new catalogue.

In `NextAction`: delete `START_MIXING`, `SELECT_COLLECTION_MIX_OR_MACHINE`, `SAVE_MIXER_PLAN_IN_STATION_2`, `SCAN_SAME_MACHINE_TO_FINISH_OR_SCAN_NEXT_PLANNED_MIXER`, the `SCAN_RESERVED_MIXER_PREFIX` constant, the whole `scanReservedMixerCodes` property, and the paragraph of the KDoc describing the parameterised action. Add:

```kotlin
        // JC-driven Mixing (2026-07-28). Every value is a plain constant — the parameterised
        // `scan_reserved_mixer:` form went with the plans.
        val OPEN_MIXING = NextAction("open_mixing")
        val SELECT_COLLECTION = NextAction("select_collection")
        val SELECT_JANDI_ROUTE = NextAction("select_jandi_route")
        val SCAN_JANDI_DRUM_TO_START = NextAction("scan_jandi_drum_to_start")
        val SCAN_JANDI_DRUM_TO_FINISH = NextAction("scan_jandi_drum_to_finish")
        val SELECT_MAIN_DESTINATION = NextAction("select_main_destination")
        val SCAN_DESTINATION_TO_START = NextAction("scan_destination_to_start")
        val SELECT_JANDI4_MAIN_SOURCE = NextAction("select_jandi4_main_source")
        val SCAN_JANDI4_TO_START = NextAction("scan_jandi4_to_start")
        val SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER =
            NextAction("scan_additional_rajoo_layer_or_finish_active_layer")
        val REFRESH_MIXING_OVERVIEW = NextAction("refresh_mixing_overview")
        val COMPLETED = NextAction("completed")
```

- [ ] **Step 4: Fix the three call sites the deletions break**

`ui/mixing/MixingViewModel.kt:736` — this is the collection flow's "collection complete → open Mixing" navigation. The comparison is against a value class, so a stale constant would never match and navigation would break silently rather than at compile time:

```kotlin
                if (outcome.nextAction == NextAction.OPEN_MIXING) {
```

`ui/mixing/board/MixingBoardViewModel.kt` — in `assignOrStartDownstream`, delete the `DESTINATION_ASSIGNMENT_REQUIRED` fallback. The function becomes:

```kotlin
    private suspend fun assignOrStartDownstream(
        machine: Equipment,
        selection: BoardSelection.Mixes,
    ): MachineCycleOutcome =
        if (machine.role == EquipmentRole.PRODUCTION_MACHINE) {
            useCase.assignDestinations(selection.mixBatchIds, listOf(machine.machineCode))
        } else {
            useCase.startDownstream(machine.machineCode, selection.jobCardNumber, selection.mixBatchIds)
        }
```

Also delete the now-unused `import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode` from that file if nothing else references it. (This function is deleted entirely in Task 5; it is kept compiling here only so this task ends green.)

In the two test files, replace `NextAction.START_MIXING` with `NextAction.OPEN_MIXING` and `NextAction.SELECT_COLLECTION_MIX_OR_MACHINE` with `NextAction.SELECT_COLLECTION` (stub values — the assertions do not depend on which action it is).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS, all classes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mqtt): swap the Mixing vocabulary to the JC-driven catalogue"
```

---

### Task 2: One mix per destination start

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt:177-203`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `BoardSelection.Mix(val mixBatchId: String, val jobCardNumber: String)` replacing `BoardSelection.Mixes`; `MixingBoardViewModel.selectMix(mixBatchId: String)` replacing `toggleMix`.

Why: the new contract's destination start takes a singular `mixBatchId` — "The first destination-machine scan assigns and starts exactly one Main mix." Multi-select can no longer be expressed on the wire.

- [ ] **Step 1: Write the failing test**

This test file mocks the use case, so its fixtures are **domain** objects, not DTOs. It uses the
shared `viewModel` field built in `setup()`, plus the existing `equipment()`, `readyMix()` and
`plannedCollection()` helpers and the `mainOverview` / `readyCollections` values. Follow that
pattern — do not introduce a new builder.

Add to `MixingBoardViewModelTest.kt`:

```kotlin
    private val twoMixOverview = mainOverview.copy(
        readyMixes = listOf(
            readyMix("MIX_1", validNext = listOf("EXT-03")),
            readyMix("MIX_2", validNext = listOf("EXT-03")),
        ))

    @Test
    fun `selecting a second mix replaces the first rather than adding to it`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(twoMixOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        viewModel.selectMix("MIX_1")
        advanceUntilIdle()
        assertEquals(
            BoardSelection.Mix("MIX_1", "510019068"),
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)

        viewModel.selectMix("MIX_2")
        advanceUntilIdle()
        assertEquals(
            BoardSelection.Mix("MIX_2", "510019068"),
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)
    }

    @Test
    fun `selecting the same mix twice clears the selection`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(twoMixOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        viewModel.selectMix("MIX_1")
        viewModel.selectMix("MIX_1")
        advanceUntilIdle()

        assertEquals(
            BoardSelection.None,
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)
    }
```

Delete any existing test asserting `toggleMix` accumulates a set or rejects an other-JC mix — both
behaviours are gone.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardViewModelTest*"`
Expected: FAIL — `Unresolved reference: selectMix`.

- [ ] **Step 3: Replace the selection type**

In `MixingBoardViewModel.kt`, change the sealed class:

```kotlin
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
```

Replace `toggleMix` with:

```kotlin
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
```

In `computeHighlightedMachines`, replace the `is BoardSelection.Mixes ->` branch with:

```kotlin
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
```

Note this also drops the local `status == "Available"` predicate in favour of the server's `scanAllowed`, and drops the run-accumulation branch — a destination start now takes one mix into one run.

Update the two remaining `BoardSelection.Mixes` references: in `machineChosen`'s `when`, rename the branch to `is BoardSelection.Mix`; in `confirmStart`, rename it and pass `selection.mixBatchId` through `assignOrStartDownstream` (change its parameter type to `BoardSelection.Mix` and wrap in `listOf(selection.mixBatchId)` at the two call sites — Task 5 removes this function).

- [ ] **Step 4: Update the screen**

In `MixingBoardScreen.kt`, in the "Ready mixes" `items` block, replace the multi-select logic:

```kotlin
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val selected =
                        (board.selection as? BoardSelection.Mix)?.mixBatchId == mix.mixBatchId
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy) { viewModel.selectMix(mix.mixBatchId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
```

Inside that card, drop the `selectable` conditional colouring — every mix is now selectable, since there is no same-JC set to constrain. Use `TextPrimary` and `SuccessGreen` unconditionally.

In `BoardContent`'s `LaunchedEffect(board.selection)` and the selection summary `when`, rename `is BoardSelection.Mixes` to `is BoardSelection.Mix` and render `sel.mixBatchId` instead of `sel.mixBatchIds.joinToString()`.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/ \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt
git commit -m "feat(mixing): a destination start takes exactly one mix"
```

---

### Task 3: Delete the plan and reservation surface

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`

**Interfaces:**
- Produces: `ReadyCollection` reduced to `(collectionId, jobCardNumber, productName, productCode, status, validMixerCodes, nextAction)`; `Equipment` without plan fields; `MachineCycleOutcome.Accepted` without `planProgress`.

This is a deletion sweep. Nothing is added.

- [ ] **Step 1: Write the failing test**

Replace the `fetchReadyCollections` test in `MixingBoardUseCaseTest.kt` with:

```kotlin
    @Test
    fun `fetchReadyCollections reads the mixing overview and carries no plan data`() = runTest {
        val response = MixingOverviewResponse(
            readyCollections = listOf(
                ReadyCollectionDto(
                    jobCardNumber = "JC-24001", collectionId = "COL_000123",
                    productName = "HD Film", status = "IngredientsCollected",
                    validMixerCodes = listOf("MXR-01", "MXR-02"),
                    nextAction = "scan_same_machine_to_finish"),
            ))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION))

        val ready = useCase.fetchReadyCollections().getOrThrow()

        val collection = ready.single()
        assertEquals("COL_000123", collection.collectionId)
        assertEquals("JC-24001", collection.jobCardNumber)
        // One completed collection creates exactly one mix; the mixers it may start come from
        // the server, not from a saved plan.
        assertEquals(listOf("MXR-01", "MXR-02"), collection.validMixerCodes)
    }
```

**Do not add a reflection-based guard test** asserting the absence of `assignDestinations`. Reflecting
over method names is brittle — a rename silently weakens it. The retired messages are guarded instead
by the source-level greps in Final Verification, which catch the wire string wherever it appears
rather than only in method names. (Ruling, 2026-07-28.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardUseCaseTest*"`
Expected: FAIL — `assignDestinations` still exists, and `ReadyCollectionDto` still accepts plan arguments.

- [ ] **Step 3: Delete from the wire layer**

In `MixingMessages.kt`, delete these declarations entirely: `PlanItemStatus`, `MixPlanStatus`, `MixerPlanItemDto`, `MixDestinationDto`, `MixDestinationAssignmentPayload`, `AssignedDestinationDto`, `MixDestinationAssignmentResultResponse`, and `EquipmentStatus.RESERVED`.

Delete these properties:
- `EquipmentDto`: `mixPlanId`, `planItemStatus`, `reservationCollectionId`, `reservationJobCardNumber`, and the `// ---- 4.1 cross-area mixer plans ----` comment banner
- `ReadyCollectionDto`: `mixPlanId`, `mixPlanStatus`, `plannedMixerCount`, `startedMixerCount`, `remainingMixerCount`, `plannedMixerCodes`, `startedMixerCodes`, `remainingMixerCodes`, `mixerPlanItems`, and the `hasSavedPlan` property; rewrite its KDoc to describe a collection that may start a mixer
- `ReadyMixDto`: `plannedDestinationMachineCode`
- `MixingOverviewResponse`: `mixDestinations`
- `MachineCycleResultResponse`: `mixPlanId`, `planItemId`, `planItemStatus`, `mixPlanStatus`, `plannedMixerCount`, `startedMixerCount`, `remainingMixerCount`, `plannedMixerCodes`, `remainingMixerCodes`, `plannedDestinationMachineCodes`, `remainingDestinationMachineCodes`, `productionRunIds`, and the `// ---- 4.1 plan identity and progress ----` banner

In `MachineCycleStartPayload`, delete the KDoc paragraph about `layerInputs` being optional under the plan model — Task 5 rewrites this type.

- [ ] **Step 4: Delete from the domain**

In `MixingBoard.kt`, delete `MixerPlanItem`, `MixDestination`, `AssignedDestination`, `MixPlanProgress`.

From `Equipment`: `mixPlanId`, `planItemStatus`, `reservationCollectionId`, `reservationJobCardNumber`. Keep `scanAllowed` and rewrite its KDoc, since the reservation rationale no longer applies:

```kotlin
    /**
     * Whether this handheld may scan this machine now. Server-decided.
     *
     * The contract forbids inferring availability or scan permission locally, so this is the only
     * thing that may gate the scan affordance.
     */
    val scanAllowed: Boolean = false,
```

Reduce `ReadyCollection` to:

```kotlin
/**
 * A completed collection that can start a mixer.
 *
 * One completed collection creates exactly one physical mix. Starting another mix or Rajoo layer
 * requires another completed collection; collections may share a job card.
 */
data class ReadyCollection(
    val jobCardNumber: String,
    val collectionId: String,
    val productName: String,
    val productCode: String = "",
    val status: String = "",
    /** The mixer codes this collection may legally start. Server-decided; never inferred. */
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
)
```

From `AreaOverview`: delete `mixDestinations`. From `MachineCycleOutcome.Accepted`: delete `planProgress` and `assignedDestinations`.

From `ReadyMix`: delete nothing yet (Task 4 renames `mixerCode`).

- [ ] **Step 5: Delete from the use case**

In `MixingBoardUseCase.kt`, delete the whole `assignDestinations` function, the `toPlanProgress` mapper, the `toMixerPlanItem` mapper, the `toMixDestination` mapper, and the now-unused imports (`MixDestinationAssignmentPayload`, `MixDestinationAssignmentResultResponse`, `MixDestinationDto`, `MixerPlanItemDto`, `MixPlanProgress`, `MixerPlanItem`, `MixDestination`, `AssignedDestination`).

In `toAreaOverview`, drop the `mixDestinations` line. In `cycleRequest`'s `Accepted` branch, drop `planProgress = outcome.body.toPlanProgress()`. In `toReadyCollection`, reduce to the seven surviving fields. Rewrite `fetchReadyCollections`'s KDoc — its explanation of why it stopped reading `active_job_cards_requested` is still true, but the reason is now that the overview is authoritative and paged, not that plans live there.

- [ ] **Step 6: Delete from the ViewModel**

In `MixingBoardViewModel.kt`, replace the `is BoardSelection.Collection ->` branch of `computeHighlightedMachines`:

```kotlin
        is BoardSelection.Collection -> {
            // NB: `when` has no implicit label, so an early `return@when` will not compile here.
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
```

Delete `assignOrStartDownstream` entirely and, in `confirmStart`, call `useCase.startDownstream(machine.machineCode, selection.jobCardNumber, listOf(selection.mixBatchId))` directly for the `is BoardSelection.Mix` branch. (Task 5 replaces this call.) Delete the `assignedDestinations` branch of the success-message lambda, leaving:

```kotlin
            applyOutcome(outcome) { accepted ->
                val id = accepted.productionRunId ?: accepted.cycleId ?: ""
                "Started $id on ${accepted.machineCode}"
            }
```

Delete `import com.mitas.ppnam.station2aa.domain.model.EquipmentRole` if unused, and the `AssignedDestination` import.

- [ ] **Step 7: Update the remaining tests**

Delete any test in `MixingBoardUseCaseTest` and `MixingBoardViewModelTest` that asserts plan progress, reservations, `hasSavedPlan`/`needsPlan`, destination assignment, or the `mixDestinations` list. Remove `AssignedDestinationDto`, `MixDestinationAssignmentPayload` and `MixDestinationAssignmentResultResponse` imports.

`MixingBoardViewModelTest`'s shared fixtures carry plan fields and must be updated or the file will not compile:

- `equipment()` (line ~43): delete the `mixPlanId` and `reservationCollectionId` parameters and the two arguments passing them. Keep `scanAllowed` but rewrite its comment — the reserved-mixer rationale is gone; it now simply means the server says this handheld may scan this machine.
- `plannedCollection()` (line ~75): rename to `readyCollection()` and reduce to the surviving fields:

```kotlin
    private fun readyCollection(
        id: String = "COL_1", jc: String = "510019068",
        validMixers: List<String> = listOf("MXR-01"),
    ) = ReadyCollection(
        jobCardNumber = jc, collectionId = id, productName = "HD Film",
        status = "IngredientsCollected", validMixerCodes = validMixers,
        nextAction = "scan_same_machine_to_finish",
    )
```

- Update `mainOverview` and `readyCollections` to call `readyCollection()`.

Any test that relied on a reserved mixer being highlighted must now assert the `validMixerCodes` intersection instead.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ app/src/test/java/com/ppnam/station2aa/
git commit -m "refactor(mixing): delete mixer plans, reservations and destination assignment"
```

---

### Task 4: Job card / production order split

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt:9-12`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt:362`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`

**Interfaces:**
- Produces: `MixingOverviewPayload(mixingArea, jobCardNumber, collectionId)`; `fetchOverview(area, jobCardNumber, collectionId)`; `fetchCollectedMaterials(collectionId)`; `ReadyMix.sourceMixerCode`.

The app currently sends `productionOrderDocumentNumber` *carrying a job-card number*. The new contract makes these two different things: `jobCardNumber` is primary at top level, and `productionOrderDocumentNumber` survives only inside `inputs[]` (Task 6), where it is the SAP order behind one input.

- [ ] **Step 1: Write the failing test**

Add to `MixingBoardUseCaseTest.kt`:

```kotlin
    @Test
    fun `fetchOverview sends jobCardNumber and collectionId, never productionOrderDocumentNumber`() = runTest {
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(MixingOverviewResponse(), NextAction.NONE))

        useCase.fetchOverview(MixingArea.Main, jobCardNumber = "JC-24001", collectionId = "COL_000123")

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(),
                eq(MixingOverviewResponse::class.java))
        }.firstValue as MixingOverviewPayload
        assertEquals("MainMixingRoom", payload.mixingArea)
        assertEquals("JC-24001", payload.jobCardNumber)
        assertEquals("COL_000123", payload.collectionId)
        // Absent, not null: the contract requires omission for unused optional fields.
        assertNull(MixingOverviewPayload().mixingArea)
        assertNull(MixingOverviewPayload().jobCardNumber)
        assertNull(MixingOverviewPayload().collectionId)
    }

    @Test
    fun `fetchCollectedMaterials resumes by collectionId alone`() = runTest {
        // The JC comes back from Station 2; the handheld does not assert it.
        val response = BomLoadedResponse(
            jobCardNumber = "JC-24001", collectionId = "COL_000123",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin",
                    collectedQuantity = 550.0, issueType = "im_Manual")))
        whenever(mockMqtt.request(
            eq("collection_resume_requested"), eq("bom_loaded"), any(), anyOrNull(),
            eq(BomLoadedResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.OPEN_MIXING))

        val materials = useCase.fetchCollectedMaterials("COL_000123").getOrThrow()

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(),
                eq(BomLoadedResponse::class.java))
        }.firstValue as CollectionResumePayload
        assertEquals("COL_000123", payload.collectionId)
        assertEquals(listOf("MAT-001"), materials.map { it.materialCode })
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardUseCaseTest*"`
Expected: FAIL — `No value passed for parameter 'jobCardNumber'` / unresolved `payload.jobCardNumber`.

- [ ] **Step 3: Rename on the wire**

In `MixingMessages.kt`:

```kotlin
/** `mixing_overview_requested` — every filter optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val jobCardNumber: String? = null,
    val collectionId: String? = null,
)
```

Rename these properties (all are `productionOrderDocumentNumber` → `jobCardNumber`, placed **first** in the constructor to match the contract's JC-first field order):
- `EquipmentDto.currentProductionOrderDocumentNumber` → `currentJobCardNumber`
- `ReadyMixDto.productionOrderDocumentNumber` → `jobCardNumber`
- `ActiveCycleDto.productionOrderDocumentNumber` → `jobCardNumber`
- `ActiveRunDto.productionOrderDocumentNumber` → `jobCardNumber`
- `MachineCycleResultResponse.productionOrderDocumentNumber` → `jobCardNumber`
- `MixingOverviewResponse.productionOrderDocumentNumber` → delete (the new overview has no such echo)

Also rename `ReadyMixDto.mixerCode` → `sourceMixerCode`, matching the contract's `readyMixes[]` field list.

In `JobCardMessages.kt`:

```kotlin
/**
 * `collection_resume_requested` — replays the stored BOM snapshot without calling SAP again.
 * Requires only the collection; Station 2 returns the job card.
 */
data class CollectionResumePayload(
    val collectionId: String,
)
```

- [ ] **Step 4: Propagate through domain and use case**

In `MixingBoard.kt`, rename `ReadyMix.mixerCode` → `sourceMixerCode`. `Equipment.currentJobCardNumber`, `ActiveCycle.jobCardNumber` and `ActiveRun.jobCardNumber` already have the right names — only their DTO sources change.

In `MixingBoardUseCase.kt`:

```kotlin
    suspend fun fetchOverview(
        area: MixingArea? = null,
        jobCardNumber: String? = null,
        collectionId: String? = null,
    ): Result<AreaOverview> =
        when (
            val outcome = mqttRepository.request(
                requestType = "mixing_overview_requested",
                responseType = "mixing_overview_result",
                payload = MixingOverviewPayload(
                    mixingArea = area?.wire,
                    jobCardNumber = jobCardNumber,
                    collectionId = collectionId,
                ),
                correlationKey = jobCardNumber ?: collectionId,
                responseClass = MixingOverviewResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body.toAreaOverview())
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Overview rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
```

```kotlin
    suspend fun fetchCollectedMaterials(collectionId: String): Result<List<CollectedMaterial>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "collection_resume_requested",
                responseType = "bom_loaded",
                payload = CollectionResumePayload(collectionId = collectionId),
                correlationKey = collectionId,
                responseClass = BomLoadedResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                outcome.body.ingredients
                    .filter { it.issueType != "im_Backflush" && it.collectedQuantity > 0.0 }
                    .map { CollectedMaterial(it.materialCode, it.materialName, it.collectedQuantity) }
            )
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load collection"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
```

Update the mappers: `currentJobCardNumber = currentJobCardNumber`, `jobCardNumber = jobCardNumber`, `sourceMixerCode = sourceMixerCode`.

In `MixingBoardViewModel.kt:362`, the Rajoo dose fetch becomes `useCase.fetchCollectedMaterials(selection.collectionId)`.

Search the whole `app/src` tree for any remaining `productionOrderDocumentNumber` reference in Mixing code and convert it. `BomLoadedResponse` and `ActiveJobCardSummary` legitimately keep both fields — do **not** touch those two.

**`fetchOverview` gained a third parameter, which breaks every existing Mockito stub.**
`MixingBoardViewModelTest` stubs and verifies it as `fetchOverview(anyOrNull(), anyOrNull())` in
roughly a dozen places; each needs a third `anyOrNull()`. Likewise `verify(mockUseCase).fetchOverview(isNull(), anyOrNull())`
becomes `fetchOverview(isNull(), anyOrNull(), anyOrNull())`, and `fetchOverview(eq(MixingArea.Main), anyOrNull())`
becomes `fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())`. Mockito reports these as
argument-count compile errors, so the compiler will find them all — but expect to touch every one.
`fetchCollectedMaterials` drops from two arguments to one in the same way.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ app/src/test/java/com/ppnam/station2aa/
git commit -m "refactor(mixing): split jobCardNumber from productionOrderDocumentNumber"
```

---

### Task 5: The six machine-start variants

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`

**Interfaces:**
- Consumes: `MixingArea`, `LayerInput`, `MachineCycleOutcome` from Tasks 3–4.
- Produces: `startMixerFromCollection(machineCode, collectionId)`, `startJandiMixer(machineCode, collectionId, route)`, `startRajooLayer(machineCode, collectionId, doses)`, `startDrumTransfer(machineCode, mixBatchId)`, `startProductionDestination(machineCode, mixBatchId)`, `startJandi4(machineCode, mainSourceMixBatchId, mainSourceMixerCode)` — all `suspend`, all returning `MachineCycleOutcome`. Also `JandiRoute` constants.

- [ ] **Step 1: Write the failing test**

Add to `MixingBoardUseCaseTest.kt`:

```kotlin
    private fun captureStart(): MachineCycleStartPayload = argumentCaptor<Any>().apply {
        verify(mockMqtt).request(eq("machine_cycle_start_requested"), any(), capture(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java))
    }.firstValue as MachineCycleStartPayload

    private suspend fun stubStartAccepted() {
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(), NextAction.SCAN_SAME_MACHINE_TO_FINISH))
    }

    @Test
    fun `a direct mixer start sends only machineCode and collectionId`() = runTest {
        stubStartAccepted()
        useCase.startMixerFromCollection("DOL-MIX-01", "COL_000123")

        val p = captureStart()
        assertEquals("DOL-MIX-01", p.machineCode)
        assertEquals("COL_000123", p.collectionId)
        assertNull(p.destinationMachineCode)
        assertNull(p.mixBatchId)
        assertNull(p.layerInputs)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a JANDI mixer start carries the route`() = runTest {
        stubStartAccepted()
        useCase.startJandiMixer("JAN-MIX-01", "COL_000124", JandiRoute.DRUM)

        val p = captureStart()
        assertEquals("JAN-MIX-01", p.machineCode)
        assertEquals("COL_000124", p.collectionId)
        assertEquals("JAN-DRUM-01", p.destinationMachineCode)
        assertNull(p.mixBatchId)
    }

    @Test
    fun `a Rajoo layer start carries its dosing lines`() = runTest {
        stubStartAccepted()
        useCase.startRajooLayer("RAJ-GM-01", "COL_000125",
            listOf(LayerInput("MAT-001", 12.5)))

        val p = captureStart()
        assertEquals("RAJ-GM-01", p.machineCode)
        assertEquals("COL_000125", p.collectionId)
        assertEquals(1, p.layerInputs?.size)
        assertEquals("MAT-001", p.layerInputs?.single()?.materialCode)
        assertEquals(12.5, p.layerInputs?.single()?.dosingQuantity ?: 0.0, 0.0)
    }

    @Test
    fun `a Rajoo layer start with no doses never reaches the wire`() = runTest {
        // 1-5 positive entries are required for each started layer. Rejecting locally tells the
        // operator immediately instead of spending a round trip on invalid_layer_inputs.
        val outcome = useCase.startRajooLayer("RAJ-GM-01", "COL_000125", emptyList())

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        // No areaStatus: nothing was attempted, so the board must keep its current picture.
        assertNull((outcome as MachineCycleOutcome.Rejected).areaStatus)
        verifyNoInteractions(mockMqtt)
    }

    @Test
    fun `a Rajoo layer start with six doses never reaches the wire`() = runTest {
        val six = (1..6).map { LayerInput("MAT-00$it", 1.0) }
        val outcome = useCase.startRajooLayer("RAJ-GM-01", "COL_000125", six)

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        verifyNoInteractions(mockMqtt)
    }

    @Test
    fun `a drum transfer start sends the mix, not a collection`() = runTest {
        stubStartAccepted()
        useCase.startDrumTransfer("JAN-DRUM-01", "MIX_000124")

        val p = captureStart()
        assertEquals("JAN-DRUM-01", p.machineCode)
        assertEquals("MIX_000124", p.mixBatchId)
        assertNull(p.collectionId)
    }

    @Test
    fun `a production destination start sends one mix`() = runTest {
        stubStartAccepted()
        useCase.startProductionDestination("EXT-03", "MIX_000126")

        val p = captureStart()
        assertEquals("EXT-03", p.machineCode)
        assertEquals("MIX_000126", p.mixBatchId)
        assertNull(p.collectionId)
    }

    @Test
    fun `a JANDI 4 start accepts an exact mix or a source mixer code, never both`() = runTest {
        stubStartAccepted()
        useCase.startJandi4("JAN-04", mainSourceMixBatchId = "MIX_000130", mainSourceMixerCode = null)
        val byMix = captureStart()
        assertEquals("MIX_000130", byMix.mainSourceMixBatchId)
        assertNull(byMix.mainSourceMixerCode)

        val both = useCase.startJandi4("JAN-04", "MIX_000130", "MXR-02")
        assertTrue(both is MachineCycleOutcome.Rejected)
        val neither = useCase.startJandi4("JAN-04", null, null)
        assertTrue(neither is MachineCycleOutcome.Rejected)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardUseCaseTest*"`
Expected: FAIL — `Unresolved reference: startMixerFromCollection`.

- [ ] **Step 3: Reshape the start payload**

In `MixingMessages.kt`, replace `MachineCycleStartPayload`:

```kotlin
/** The three JANDI routes the shared mixer must be given before it starts. */
object JandiRoute {
    const val JANDI_2 = "JAN-02"
    const val JANDI_3 = "JAN-03"
    const val DRUM = "JAN-DRUM-01"

    val ALL = listOf(JANDI_2, JANDI_3, DRUM)
}

/**
 * `machine_cycle_start_requested` — one payload covering six variants.
 *
 * The optional fields could be combined illegally, so nothing constructs this directly: the six
 * named functions on [com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase] are the only
 * builders, and each populates exactly one legal combination.
 *
 * | Variant | Fields sent |
 * |---|---|
 * | DOLCI / Mackie / Main mixer | machineCode, collectionId |
 * | JANDI shared mixer | + destinationMachineCode |
 * | Rajoo layer | + layerInputs (1-5, required) |
 * | JANDI drum transfer | machineCode, mixBatchId |
 * | Main production destination | machineCode, mixBatchId |
 * | JANDI 4 | machineCode, mainSourceMixBatchId OR mainSourceMixerCode |
 */
data class MachineCycleStartPayload(
    val machineCode: String,
    val collectionId: String? = null,
    val destinationMachineCode: String? = null,
    val mixBatchId: String? = null,
    val mainSourceMixBatchId: String? = null,
    val mainSourceMixerCode: String? = null,
    val layerInputs: List<LayerInputDto>? = null,
)
```

- [ ] **Step 4: Write the six use-case functions**

In `MixingBoardUseCase.kt`, delete `startMixer`, `startRajoo` and `startDownstream`, and add:

```kotlin
    /** DOLCI, Mackie, and Main mixers: a completed collection is the only input. */
    suspend fun startMixerFromCollection(machineCode: String, collectionId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, collectionId = collectionId),
            correlationKey = collectionId,
        )

    /**
     * The JANDI shared mixer. The route decides the whole downstream lifecycle — JANDI 2/3 feed
     * directly, the drum route makes the mix ReadyForTransfer — so it is required at start, not
     * chosen afterwards.
     */
    suspend fun startJandiMixer(
        machineCode: String,
        collectionId: String,
        route: String,
    ): MachineCycleOutcome {
        if (route !in JandiRoute.ALL) {
            return rejectedLocally("Select JANDI 2, JANDI 3 or the drum before starting.")
        }
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                collectionId = collectionId,
                destinationMachineCode = route,
            ),
            correlationKey = collectionId,
        )
    }

    /**
     * One Rajoo layer. Each gravimetric mixer is one layer and starts from its own completed
     * collection; 1-5 positive dosing lines are required for every started layer.
     */
    suspend fun startRajooLayer(
        machineCode: String,
        collectionId: String,
        doses: List<LayerInput>,
    ): MachineCycleOutcome {
        val error = when {
            doses.isEmpty() -> "A Rajoo layer needs at least one dose line."
            doses.size > 5 -> "A Rajoo layer takes at most five dose lines."
            doses.any { it.dosingQuantity <= 0.0 } -> "Every dose must be a positive quantity."
            else -> null
        }
        if (error != null) return rejectedLocally(error)
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                collectionId = collectionId,
                layerInputs = doses.map { LayerInputDto(it.materialCode, it.dosingQuantity) },
            ),
            correlationKey = collectionId,
        )
    }

    /**
     * Decanting a ReadyForTransfer JANDI mix into the single drum.
     *
     * This shares a wire shape with [startProductionDestination] but is deliberately a separate
     * function: a transfer cycle and a production run start are different operations, the call
     * sites read correctly this way, and the two can diverge without a refactor. Do not collapse
     * them. (Ruling, 2026-07-28.)
     */
    suspend fun startDrumTransfer(machineCode: String, mixBatchId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, mixBatchId = mixBatchId),
            correlationKey = mixBatchId,
        )

    /**
     * A Main destination scan: assigns and starts exactly one ready mix on one production
     * machine. This replaces the withdrawn `mix_destination_assignment_requested` entirely.
     */
    suspend fun startProductionDestination(machineCode: String, mixBatchId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, mixBatchId = mixBatchId),
            correlationKey = mixBatchId,
        )

    /**
     * JANDI 4 consumes the current drum plus exactly one ready Main mix, whose job cards may
     * differ. The Main input is named either exactly or by its source mixer code, which resolves
     * only when exactly one eligible output exists — otherwise the server answers
     * `ambiguous_main_mix`.
     */
    suspend fun startJandi4(
        machineCode: String,
        mainSourceMixBatchId: String? = null,
        mainSourceMixerCode: String? = null,
    ): MachineCycleOutcome {
        val byMix = !mainSourceMixBatchId.isNullOrBlank()
        val byMixer = !mainSourceMixerCode.isNullOrBlank()
        if (byMix == byMixer) {
            return rejectedLocally("Name the Main mix either exactly or by its source mixer, not both.")
        }
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                mainSourceMixBatchId = mainSourceMixBatchId?.takeIf { byMix },
                mainSourceMixerCode = mainSourceMixerCode?.takeIf { byMixer },
            ),
            correlationKey = mainSourceMixBatchId ?: mainSourceMixerCode,
        )
    }

    /**
     * A refusal decided here, before anything was sent. areaStatus is null precisely because
     * nothing was attempted server-side — the board must keep the picture it already has.
     */
    private fun rejectedLocally(reason: String) =
        MachineCycleOutcome.Rejected(errorCode = null, reason = reason, areaStatus = null)
```

Add `import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute`.

- [ ] **Step 5: Route the ViewModel's dispatch**

In `MixingBoardViewModel.kt`, replace `confirmStart`'s outcome `when` with a dispatch that picks the variant from the machine's area and role:

```kotlin
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
```

`sheet.selectedRoute` is added in Task 7; for this task add it to `BoardSheet.StartConfirm` as `val selectedRoute: String? = null` so this compiles, and leave the picker UI to Task 7.

Restore `import com.mitas.ppnam.station2aa.domain.model.EquipmentRole` if Task 3 removed it.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ app/src/test/java/com/ppnam/station2aa/
git commit -m "feat(mixing): six named machine-start variants replace the generic start"
```

---

### Task 6: Run inputs, drum state and the enriched result

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`

**Interfaces:**
- Produces: `RunInputDto`/`RunInput`, `JandiDrumDto`/`JandiDrum`, `AreaOverview.jandiDrum`, `AreaOverview.nextAction`, `ActiveRun.inputs`, and `MachineCycleOutcome.Accepted.{destinationMachineCode, resultingStatus, productLayer, inputs, sapIssuePrepared}`.

Composite runs are the point: JANDI 4 takes the drum plus one Main mix, and Rajoo takes up to three layers, each from its own collection. Their job cards may differ, so a run needs a list of inputs rather than one JC.

**Inferred field names.** The requirements give prose, not JSON, for this section. Every name introduced here is an assumption to confirm with the backend developer: `equipment[].fixedDestinationMachineCode`, `.currentCollectionId`, `.currentProductionRunId`; `activeCycles[].status`, `.destinationMachineCode`; `activeRuns[].status`; all of `jandiDrum`; and `machine_cycle_result.resultingStatus`, `.sapIssuePrepared`, `.sapPostingEnabled`. Gson ignores unknown fields and defaults absent ones, so a wrong guess renders blank rather than crashing.

- [ ] **Step 1: Write the failing test**

Add to `MixingBoardUseCaseTest.kt`:

```kotlin
    @Test
    fun `an active run maps every input, including inputs from different job cards`() = runTest {
        val response = MixingOverviewResponse(
            activeRuns = listOf(ActiveRunDto(
                productionRunId = "RUN_000200", machineCode = "JAN-04",
                status = "InProgress", startedAtUtc = "2026-07-28T08:40:00.000000Z",
                inputs = listOf(
                    RunInputDto(inputRole = "JandiDrum", jobCardNumber = "JC-24001",
                        productionOrderDocumentNumber = "PO-9001", collectionId = "COL_000124",
                        mixBatchId = "MIX_000124", sourceMixerCode = "JAN-MIX-01"),
                    RunInputDto(inputRole = "MainMix", jobCardNumber = "JC-24099",
                        productionOrderDocumentNumber = "PO-9099", collectionId = "COL_000130",
                        mixBatchId = "MIX_000130", sourceMixerCode = "MXR-02"),
                ))))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val run = useCase.fetchOverview().getOrThrow().activeRuns.single()

        assertEquals("RUN_000200", run.productionRunId)
        assertEquals(listOf("JandiDrum", "MainMix"), run.inputs.map { it.inputRole })
        // Mixed job cards on one run are legal and must survive the mapping intact.
        assertEquals(listOf("JC-24001", "JC-24099"), run.inputs.map { it.jobCardNumber })
        assertEquals(listOf("PO-9001", "PO-9099"), run.inputs.map { it.productionOrderDocumentNumber })
    }

    @Test
    fun `the JANDI drum state maps through`() = runTest {
        val response = MixingOverviewResponse(
            jandiDrum = JandiDrumDto(
                status = "Filled", jobCardNumber = "JC-24001", collectionId = "COL_000124",
                mixBatchId = "MIX_000124", filledAtUtc = "2026-07-28T08:25:00.000000Z",
                scanGuidance = "Scan JANDI 4 to consume the drum."))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val drum = useCase.fetchOverview().getOrThrow().jandiDrum

        assertNotNull(drum)
        assertEquals("Filled", drum?.status)
        assertEquals("MIX_000124", drum?.mixBatchId)
    }

    @Test
    fun `a machine result carries the destination, resulting status and SAP preview flags`() = runTest {
        val response = MachineCycleResultResponse(
            action = "Started", machineCode = "EXT-03", cycleId = "CYC_000140",
            productionRunId = "RUN_000140", destinationMachineCode = "EXT-03",
            jobCardNumber = "JC-24001", mixBatchId = "MIX_000126",
            resultingStatus = "ProductionInProgress", sapIssuePrepared = true)
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_SAME_MACHINE_TO_FINISH))

        val outcome = useCase.startProductionDestination("EXT-03", "MIX_000126")

        val accepted = outcome as MachineCycleOutcome.Accepted
        assertEquals("EXT-03", accepted.destinationMachineCode)
        assertEquals("ProductionInProgress", accepted.resultingStatus)
        // No Mixing action posts to SAP; the preview is local and prepared-only.
        assertTrue(accepted.sapIssuePrepared)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardUseCaseTest*"`
Expected: FAIL — `Unresolved reference: RunInputDto`.

- [ ] **Step 3: Add the wire shapes**

In `MixingMessages.kt`:

```kotlin
/**
 * One source feeding a production run.
 *
 * A JANDI 4 run takes the drum plus one Main mix, and a Rajoo run takes one layer per started
 * gravimetric mixer — each from its own completed collection. Their job cards may legitimately
 * differ, which is why a run carries a list of inputs rather than one JC.
 */
data class RunInputDto(
    val inputRole: String = "",
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val mixBatchId: String = "",
    val sourceMixerCode: String = "",
    val productLayer: Int? = null,
)

/**
 * The single JANDI drum. Once filled it stays reserved until JANDI 4 consumes it, so there is
 * exactly one of these in an area overview, not a list.
 */
data class JandiDrumDto(
    val status: String = "",
    val jobCardNumber: String = "",
    val collectionId: String = "",
    val mixBatchId: String = "",
    val activeTransferCycleId: String? = null,
    val filledAtUtc: String? = null,
    val scanGuidance: String = "",
)
```

Extend `EquipmentDto` with `val currentCollectionId: String? = null`, `val currentProductionRunId: String? = null`, `val fixedDestinationMachineCode: String? = null`.

Extend `ActiveCycleDto` with `val mixBatchId: String = ""`, `val destinationMachineCode: String? = null`, `val productLayer: Int? = null`, `val status: String = ""`.

Replace `ActiveRunDto`:

```kotlin
data class ActiveRunDto(
    val productionRunId: String = "",
    val machineCode: String = "",
    val status: String = "",
    val startedAtUtc: String = "",
    val inputs: List<RunInputDto> = emptyList(),
)
```

Extend `MixingOverviewResponse` with `val jandiDrum: JandiDrumDto? = null` and `val nextAction: String = ""`.

Extend `MachineCycleResultResponse` with:

```kotlin
    val destinationMachineCode: String? = null,
    val productLayer: Int? = null,
    val resultingStatus: String? = null,
    val inputs: List<RunInputDto> = emptyList(),
    /** A local prepared-only preview. No Mixing action posts to SAP. */
    val sapIssuePrepared: Boolean = false,
    val sapPostingEnabled: Boolean = false,
```

- [ ] **Step 4: Add the domain shapes and mappers**

In `MixingBoard.kt`:

```kotlin
/** One source feeding a production run. Composite runs carry several, with differing job cards. */
data class RunInput(
    val inputRole: String,
    val jobCardNumber: String,
    val productionOrderDocumentNumber: String,
    val collectionId: String,
    val mixBatchId: String,
    val sourceMixerCode: String,
    val productLayer: Int?,
)

/** The single JANDI drum, reserved from fill until JANDI 4 consumes it. */
data class JandiDrum(
    val status: String,
    val jobCardNumber: String,
    val collectionId: String,
    val mixBatchId: String,
    val activeTransferCycleId: String?,
    val filledAtUtc: String?,
    val scanGuidance: String,
)
```

Add to `Equipment`: `currentCollectionId: String?`, `currentProductionRunId: String?`, `fixedDestinationMachineCode: String?`. Add to `ActiveCycle`: `mixBatchId: String`, `destinationMachineCode: String?`, `productLayer: Int?`, `status: String`. Replace `ActiveRun`'s `jobCardNumber`/`mixBatchIds` with `status: String` and `inputs: List<RunInput>`.

Add to `AreaOverview`: `val jandiDrum: JandiDrum? = null`, `val nextAction: String = ""`.

Add to `MachineCycleOutcome.Accepted`: `val destinationMachineCode: String? = null`, `val resultingStatus: String? = null`, `val productLayer: Int? = null`, `val inputs: List<RunInput> = emptyList()`, `val sapIssuePrepared: Boolean = false`.

In `MixingBoardUseCase.kt`, add the mappers and wire them into `toAreaOverview` and `cycleRequest`:

```kotlin
    private fun RunInputDto.toRunInput() = RunInput(
        inputRole = inputRole,
        jobCardNumber = jobCardNumber,
        productionOrderDocumentNumber = productionOrderDocumentNumber,
        collectionId = collectionId,
        mixBatchId = mixBatchId,
        sourceMixerCode = sourceMixerCode,
        productLayer = productLayer,
    )

    private fun JandiDrumDto.toJandiDrum() = JandiDrum(
        status = status,
        jobCardNumber = jobCardNumber,
        collectionId = collectionId,
        mixBatchId = mixBatchId,
        activeTransferCycleId = activeTransferCycleId,
        filledAtUtc = filledAtUtc,
        scanGuidance = scanGuidance,
    )
```

In `toAreaOverview` add `jandiDrum = jandiDrum?.toJandiDrum()` and `nextAction = nextAction`. In `toActiveRun` map `status` and `inputs = inputs.map { it.toRunInput() }`. In `cycleRequest`'s `Accepted` branch add the five new fields.

Update `toAreaOverviewOrNull`'s emptiness check to keep working — it currently tests four lists; leave it as is, since `jandiDrum` alone is not evidence of a business rejection.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ app/src/test/java/com/ppnam/station2aa/
git commit -m "feat(mixing): run inputs, JANDI drum state and the enriched cycle result"
```

---

### Task 7: JANDI route and JANDI 4 source pickers

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`

**Interfaces:**
- Consumes: `JandiRoute`, `startJandiMixer`, `startJandi4` from Task 5.
- Produces: `BoardSheet.StartConfirm.{routeOptions, selectedRoute, mainSourceOptions, selectedMainSource}`; `MixingBoardViewModel.selectRoute(route)`, `.selectMainSource(mixBatchId)`.

- [ ] **Step 1: Write the failing test**

Domain fixtures and the shared `viewModel` field, as in Task 2. Add to `MixingBoardViewModelTest.kt`:

```kotlin
    private val jandiOverview = AreaOverview(
        equipment = listOf(
            equipment("JAN-MIX-01", role = "Mixer", area = MixingArea.Jandi),
            equipment("JAN-04", role = "ProductionMachine", area = MixingArea.Jandi),
        ),
        activeCycles = emptyList(),
        readyMixes = emptyList(),
        activeRuns = emptyList(),
        readyCollections = listOf(readyCollection("COL_000124", validMixers = listOf("JAN-MIX-01"))),
    )

    private suspend fun openJandiBoard() {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(jandiOverview))
        whenever(mockUseCase.fetchReadyCollections())
            .thenReturn(Result.success(jandiOverview.readyCollections))
        viewModel.openArea(MixingArea.Jandi)
    }

    @Test
    fun `a JANDI mixer start offers the three routes and blocks until one is chosen`() = runTest {
        openJandiBoard()
        advanceUntilIdle()
        viewModel.selectCollection("COL_000124")
        viewModel.machineChosen("JAN-MIX-01")
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
            as BoardSheet.StartConfirm
        assertEquals(listOf("JAN-02", "JAN-03", "JAN-DRUM-01"), sheet.routeOptions)
        assertNull(sheet.selectedRoute)

        // Confirming without a route must not reach the server.
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase, never()).startJandiMixer(any(), any(), any())

        viewModel.selectRoute("JAN-DRUM-01")
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase).startJandiMixer("JAN-MIX-01", "COL_000124", "JAN-DRUM-01")
    }

    @Test
    fun `a scanned Main mixer code is cached and used as the JANDI 4 source`() = runTest {
        // "The Android app may cache the scanned Main mixer code locally until the JANDI 4 start
        // request. There is no separate source-selection MQTT mutation."
        whenever(mockUseCase.startJandi4(any(), anyOrNull(), anyOrNull()))
            .thenReturn(acceptedOutcome(jandiOverview))
        openJandiBoard()
        advanceUntilIdle()

        viewModel.cacheMainSourceMixerCode("MXR-02")
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startJandi4("JAN-04", null, "MXR-02")
    }

    @Test
    fun `the cached Main mixer code is cleared once a JANDI 4 start is accepted`() = runTest {
        whenever(mockUseCase.startJandi4(any(), anyOrNull(), anyOrNull()))
            .thenReturn(acceptedOutcome(jandiOverview))
        openJandiBoard()
        advanceUntilIdle()
        viewModel.cacheMainSourceMixerCode("MXR-02")
        viewModel.machineChosen("JAN-04")
        viewModel.confirmStart()
        advanceUntilIdle()

        // A second JANDI 4 start must not silently reuse the consumed code.
        viewModel.machineChosen("JAN-04")
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase).startJandi4("JAN-04", null, null)
    }
```

Add the two helpers if the file does not already have equivalents:

```kotlin
    private fun readyCollection(
        id: String = "COL_000123", jc: String = "510019068",
        validMixers: List<String> = listOf("MXR-01"),
    ) = ReadyCollection(
        jobCardNumber = jc, collectionId = id, productName = "HD Film",
        status = "IngredientsCollected", validMixerCodes = validMixers,
        nextAction = "scan_same_machine_to_finish",
    )

    private fun acceptedOutcome(overview: AreaOverview) = MachineCycleOutcome.Accepted(
        action = "Started", machineCode = "JAN-04", cycleId = "CYC_000140",
        mixBatchId = null, productionRunId = "RUN_000140", affectedMixBatchIds = emptyList(),
        alreadyFinished = false, forceClosed = false, approverDisplayName = null,
        areaStatus = overview,
    )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*MixingBoardViewModelTest*"`
Expected: FAIL — `Unresolved reference: routeOptions`.

- [ ] **Step 3: Extend the sheet state**

In `MixingBoardViewModel.kt`:

```kotlin
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
```

Add the cache and its two mutators:

```kotlin
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
```

In `machineChosen`, populate the new fields. JANDI 4 is reached with `BoardSelection.None`, because its
sources are the drum plus a Main mix rather than anything selected on the board — so it needs its own
branch ahead of the active-cycle lookup:

```kotlin
        when (val selection = board.selection) {
            is BoardSelection.None -> {
                // JANDI 4 takes the current drum plus one ready Main mix, so it starts without a
                // board selection. Its Main input is chosen in the sheet or supplied by a Main
                // mixer code scanned earlier.
                if (machine.machineCode == JANDI_4_CODE) {
                    setBoard(board.copy(sheet = BoardSheet.StartConfirm(
                        machine = machine,
                        doseRows = null,
                        mainSourceOptions = board.overview.readyMixes
                            .filter { it.area == MixingArea.Main && it.isAssignable },
                    )))
                    return
                }
                val cycle = board.overview.activeCycles.firstOrNull { it.machineCode == machineCode }
                if (cycle != null) {
                    setBoard(board.copy(sheet = BoardSheet.CycleSheet(machine, cycle)))
                } else {
                    _messages.trySend("Select a collection or mix to start this machine.")
                }
            }
```

In the `is BoardSelection.Collection ->` branch, the non-Rajoo path supplies the routes when the
machine is the JANDI shared mixer:

```kotlin
                } else {
                    val routes = if (machine.area == MixingArea.Jandi &&
                        machine.role == EquipmentRole.MIXER) JandiRoute.ALL else emptyList()
                    setBoard(board.copy(sheet = BoardSheet.StartConfirm(
                        machine = machine, doseRows = null, routeOptions = routes)))
                }
```

Add `import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute`.

In `confirmStart`, add the JANDI 4 branch ahead of the selection `when`:

```kotlin
        if (machine.machineCode == JANDI_4_CODE) {
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
```

with `private const val JANDI_4_CODE = "JAN-04"` at file scope.

- [ ] **Step 4: Render both pickers**

In `MixingBoardScreen.kt`, inside `StartConfirmDialog`, above the dose rows:

```kotlin
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
```

with the label helper at file scope:

```kotlin
private fun routeLabel(route: String) = when (route) {
    JandiRoute.JANDI_2 -> "JANDI 2 (direct feed)"
    JandiRoute.JANDI_3 -> "JANDI 3 (direct feed)"
    JandiRoute.DRUM -> "Drum (decant, then JANDI 4)"
    else -> route
}
```

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/ \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt
git commit -m "feat(mixing): JANDI route and JANDI 4 source selection"
```

---

### Task 8: JC-first presentation

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`

**Interfaces:**
- Consumes: `AreaOverview.jandiDrum`, `ActiveRun.inputs`, `ReadyMix.sourceMixerCode` from Task 6.

The requirement is that JC is the primary reference everywhere, with `COL_ID`, `MIX_ID`, `CYC_ID` and `RUN_ID` as smaller secondary traceability references. Today's cards lead with `collectionId` and `mixBatchId`, which is exactly backwards.

This task is presentation only — no ViewModel or use-case change — so it is verified by reading the rendered composables rather than by new unit tests.

- [ ] **Step 1: Make JC primary on every card**

Ready collections:

```kotlin
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${collection.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text(collection.collectionId,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            if (collection.productName.isNotBlank()) {
                                Text(collection.productName,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
```

Ready mixes:

```kotlin
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${mix.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text("${mix.mixBatchId} · ${mix.collectionId} · from ${mix.sourceMixerCode}",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                                style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                        }
```

Active cycles:

```kotlin
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${cycle.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text("${cycle.cycleId} on ${cycle.machineCode}",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(
                                "Started " + (formatElapsedSince(cycle.startedAtUtc)
                                    ?: formatStationTimestamp(cycle.startedAtUtc)),
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
```

Keep the surrounding `Card`, `clickable` and `key` exactly as they are — only the `Column` contents change.

- [ ] **Step 2: Add the drum status card**

After the "Ready mixes" section, before "Machines":

```kotlin
            board.overview.jandiDrum?.let { drum ->
                item { SectionHeader("JANDI drum") }
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${drum.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text("${drum.mixBatchId} · ${drum.collectionId}",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(drum.status,
                                style = MaterialTheme.typography.bodyMedium, color = WarningOrange)
                            if (drum.scanGuidance.isNotBlank()) {
                                Text(drum.scanGuidance,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                    }
                }
            }
```

- [ ] **Step 3: Show composite run inputs**

The screen has sections for Collections, Ready mixes, Machines and Active cycles, but **none for
active runs** — so add one. A composite run is the only place two different job cards are visible in
one object, and the requirement that mixed-JC JANDI 4 and Rajoo runs are legal is only meaningful if
the operator can see it. Add after the "Active cycles" section, inside the same `LazyColumn`:

```kotlin
            if (board.overview.activeRuns.isNotEmpty()) {
                item { SectionHeader("Active runs") }
                items(board.overview.activeRuns, key = { it.productionRunId }) { run ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(run.machineCode,
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("${run.productionRunId} · ${run.status}",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            // A JANDI 4 or Rajoo run carries several inputs whose job cards may
                            // differ. Listing them is the only way that is visible to an operator.
                            run.inputs.forEach { input ->
                                Text(
                                    "JC ${input.jobCardNumber}" +
                                        (input.productLayer?.let { " · layer $it" } ?: "") +
                                        " · ${input.inputRole}",
                                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(input.mixBatchId,
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
            }
```

- [ ] **Step 4: Sweep for surviving plan language**

Search `MixingBoardScreen.kt` and `MixingAreaPickerScreen.kt` for the strings "plan", "Plan", "reserved", "Reserved", "Mixer plan complete", and the "save the plan at the desk" empty state. Delete every one. Replace the selection summary's collection line with `"Selected: JC ${sel.jobCardNumber}"` so the header follows the same JC-first rule.

- [ ] **Step 5: Run the full suite and build the APK**

Run: `./gradlew test`
Expected: PASS.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. This is the first step that proves the Compose layer compiles — unit tests do not cover the screen.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/
git commit -m "feat(mixing): JC-first cards, drum status and composite run inputs"
```

---

## Final verification

- [ ] `./gradlew test` — full suite green
- [ ] `./gradlew assembleDebug` — APK builds
- [ ] `grep -rn "mix_destination_assignment\|mixPlan\|planItem\|remainingMixerCodes\|MixerPlan" app/src` returns nothing
- [ ] `grep -rn "productionOrderDocumentNumber" app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt` returns only the `RunInputDto` line
- [ ] `graphify update .` to refresh the knowledge graph

## Known gaps at completion

These are stated in the spec and remain true when this plan finishes:

1. **No end-to-end verification.** `tools/backend-sim/` is out of scope and still speaks the plan contract, so no run against a live broker is possible until the backend developer ships. Every claim this plan can support is a unit-test claim.
2. **Eleven inferred field names** (listed in Task 6) need confirming against the backend implementation.
3. **`RFID_MQTT_CONTRACT.md` is still the old plan-based version**, so there is no shared written source of truth between this app and the backend.
