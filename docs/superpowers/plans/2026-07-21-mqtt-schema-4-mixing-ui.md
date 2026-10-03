# MQTT Schema 4.0 — Five-Area Mixing UI (SP4b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the five-area Mixing board UI and cycle flows on the SP4a foundation — area picker, per-area board, source-first start of mixer/Rajoo/drum/production cycles, cycle-sheet finish/force-close, and navigation from `start_mixing` — completing the app's v4.0 surface.

**Architecture:** A new vertical slice (`MixingMessages` DTOs → `MixingBoard` domain models → `MixingBoardUseCase` → `MixingBoardViewModel` → `ui/mixing/board/` screens) behind a new `MIXING_BOARD` nested nav graph. The SP4a capture flow gains only a navigation event and an enabled button. Every `machine_cycle_result` embeds a refreshed `areaStatus`, which is the board's primary refresh mechanism.

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + Gson + HiveMQ (app); JUnit4 + mockito-kotlin + kotlinx-coroutines-test; Python 3.14 simulator (`tools/backend-sim/`) as integration harness.

**Spec:** `docs/superpowers/specs/2026-07-21-mqtt-schema-4-mixing-ui-design.md`. Contract: `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v4.0 (READ-ONLY — nothing under `C:\Dev\PPNAM-Station-2` is ever edited).

## Global Constraints

- Source-first everywhere: select the collection or mix(es) first; valid machines highlight; scan or tap fires the start. Scanning selects **any** machine (trusted intent, server-authoritative rejection after confirm); tapping works only on highlighted cards.
- Machine availability is rendered **strictly** from `areaStatus.equipment` (`status` / `isAvailable`) — never inferred locally (§13.7). Destination choices render only from `readyMixes[].validNextMachineCodes` (§13.8).
- Start requests carry exactly one of `collectionId` / `mixBatchIds` (+ `layerInputs` only on a Rajoo mixer). Never the retired v3 array fields (`machineCodes`, `collectionIds`, `preMixId`, `preMixIds`).
- Finish/force-close send the exact `machineCode` + stored `cycleId` (§13.6 — never invented). `alreadyFinished: true` replies are success. Force-close requires manager username + password + audit reason; blanks fail closed client-side, nothing sent.
- Refresh the overview on board entry, on MQTT reconnect, and after every machine result via the embedded `areaStatus` (§13.11). No polling.
- The board ViewModel ignores scan events while a sheet is open or a request is in flight.
- A collection is destination-neutral: auto-navigation lands on the **area picker**, never guesses an area.
- Exact UI copy: area picker banner `"<collectionId> ready to mix — pick an area"`; snackbar when scanning with no selection: `"Select a collection or mix to start this machine."`
- Both SAP flags remain false and are never surfaced as SAP activity. `session_required` / `client_upgrade_required` stay globally handled by the transport.
- Dark-graphite/amber design system (`com.mitas.ppnam.station2aa.ui.theme.*`), `AppScaffold`, `AlertDialog` patterns as in existing screens.
- Gradle (PowerShell, repo root; JAVA_HOME may need `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`): `.\gradlew.bat :app:testDebugUnitTest`, `.\gradlew.bat :app:assembleDebug`. Simulator: `python selftest.py --direct` from `tools/backend-sim/` (109 checks green at branch point).
- After each code task run `graphify update .` (AST-only). Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

**Create:**
- `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt` — overview/start/finish/force-close wire shapes; `MachineCycleResultResponse.areaStatus` reuses `MixingOverviewResponse`.
- `app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt` — `MixingArea` (five fixed values), `Equipment`, `ReadyMix`, `ActiveCycle`, `ActiveRun`, `AreaOverview`, `ReadyCollection`, `CollectedMaterial`, `LayerInput`, `MachineCycleOutcome`.
- `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt` — overview/collections/materials fetches + the five cycle operations; DTO→domain mapping lives here (the `MixingUseCase` pattern).
- `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt` — sealed `MixingBoardUiState` (`AreaPicker` / `Board` with selection, highlights, sheet, busy), scan handling, reconnect refresh.
- `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`
- `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt` — board sections + the three dialogs (start-confirm with Rajoo doses, active-cycle, force-close).
- `app/src/main/java/com/ppnam/station2aa/ui/components/UpgradeGate.kt` — app-level `client_upgrade_required` blocking dialog + its tiny ViewModel.
- Tests: `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt`, `.../domain/usecase/MixingBoardUseCaseTest.kt`, `.../ui/mixing/board/MixingBoardViewModelTest.kt`.

**Modify:** `navigation/NavRoutes.kt`, `navigation/AppNavGraph.kt`, `ui/mixing/JobLookupScreen.kt` (Mixing button), `ui/mixing/MixingViewModel.kt` (START_MIXING navigation event only), `ui/mixing/IngredientScanScreen.kt` (enabled button, nav collection, upgrade dialog removed), `domain/repository/MqttRepository.kt` untouched; `tools/backend-sim/handlers/mixing.py` + `tools/backend-sim/selftest.py` (vestigial `accepted` strip only).

**Delete:** `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt` (dead since the Jul 16 nav restructure).

---

### Task 1: Branch + simulator cleanup — strip the vestigial nested `accepted` from `area_overview()`

**Files:**
- Modify: `tools/backend-sim/handlers/mixing.py:97-98` (payload dict), `:165-166` (`overview()`)
- Modify: `tools/backend-sim/selftest.py` (one assertion)

**Interfaces:**
- Produces: `area_overview(world, area=None, po=None)` returns only `mixingArea`, `productionOrderDocumentNumber`, `equipment`, `activeCycles`, `readyMixes`, `activeRuns` — no `accepted` key. This is the shape Task 2's `MixingOverviewResponse` models; the embedded `areaStatus` in every machine result has the same shape.

- [ ] **Step 1: Create the branch**

```bash
git checkout master
git checkout -b mqtt-schema-4-mixing-ui
```

- [ ] **Step 2: Remove the nested `accepted`**

In `tools/backend-sim/handlers/mixing.py`, `area_overview()`'s returned dict starts:

```python
    return {
        "accepted": True,
        "mixingArea": area,
```

Delete the `"accepted": True,` line. In `overview()`, delete the now-dead line:

```python
    del extras["accepted"]  # build_response owns the envelope's accepted flag
```

and change the preceding `extras = dict(ov)` to keep its meaning obvious:

```python
    extras = dict(ov)
```

(no other change — `build_response` already owns the envelope's `accepted`).

- [ ] **Step 3: Fix the one selftest assertion**

In `tools/backend-sim/selftest.py`, the mixer-start check asserts on the embedded areaStatus:

```python
          and r["areaStatus"]["accepted"] is True,
```

Replace that line with:

```python
          and "equipment" in r["areaStatus"],
```

- [ ] **Step 4: Run the selftest**

```bash
python selftest.py --direct
```

Expected: `ALL 109 CHECKS PASSED — simulator is v4.0 contract-conformant`, exit 0. (Never commit the `logs/` directory it creates.)

- [ ] **Step 5: Commit**

```bash
git add tools/backend-sim/handlers/mixing.py tools/backend-sim/selftest.py
git commit -m "refactor(sim): drop vestigial nested accepted from area overview payloads"
```

---
### Task 2: Wire DTOs and domain models

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt`

**Interfaces:**
- Produces (Task 3 maps these): `MixingOverviewPayload(mixingArea: String?, productionOrderDocumentNumber: String?)`, `MixingOverviewResponse(mixingArea, productionOrderDocumentNumber, equipment: List<EquipmentDto>, activeCycles: List<ActiveCycleDto>, readyMixes: List<ReadyMixDto>, activeRuns: List<ActiveRunDto>)`, `MachineCycleStartPayload(machineCode, productionOrderDocumentNumber, collectionId?, mixBatchIds?, layerInputs?)`, `LayerInputDto(materialCode, dosingQuantity)`, `MachineCycleFinishPayload(machineCode, cycleId)`, `MachineCycleForceClosePayload(machineCode, cycleId, managerUsername, managerPassword, auditReason)`, `MachineCycleResultResponse(..., areaStatus: MixingOverviewResponse)`.
- Domain (Tasks 3–7 consume): `MixingArea` enum (`wire`, `display`, `fromWire`), `Equipment`, `ReadyMix`, `ActiveCycle`, `ActiveRun`, `AreaOverview` (+ `AreaOverview.EMPTY`), `ReadyCollection`, `CollectedMaterial`, `LayerInput`, sealed `MachineCycleOutcome { Accepted, Rejected, Failed }`.

- [ ] **Step 1: Write the failing parse test**

`app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MixingMessagesTest {

    private val gson = Gson()

    // Field names copied from the simulator's payload builders (handlers/mixing.py),
    // which mirror the contract §7/§8 examples.
    private val overviewJson = """
        {
          "mixingArea": "JandiBulkMixing",
          "productionOrderDocumentNumber": "510019068",
          "equipment": [{
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "machineCode": "JAN-MIX-01", "displayName": "JANDI 2/3 Shared Bulk Mixer",
            "isEnabled": true, "isAvailable": true, "status": "Available",
            "productLayer": null, "currentCycleId": null,
            "currentProductionOrderDocumentNumber": null, "currentMixBatchIds": [],
            "validDestinationMachineCodes": ["JAN-02", "JAN-03", "JAN-04"],
            "routeDescription": "JANDI 2 or JANDI 3 direct; use the drum cycle before JANDI 4."
          }],
          "activeCycles": [{
            "cycleId": "CYC_000007", "machineCode": "JAN-MIX-01",
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "productionOrderDocumentNumber": "510019068", "collectionId": "COL_000003",
            "mixBatchIds": ["MIX_000003"], "productionRunId": null,
            "startedAtUtc": "2026-07-21T08:20:00Z", "startedByOperatorId": "OP-001"
          }],
          "readyMixes": [{
            "mixBatchId": "MIX_000001", "collectionId": "COL_000001",
            "mixingArea": "JandiBulkMixing", "productionOrderDocumentNumber": "510019068",
            "mixerCode": "JAN-MIX-01", "mixerDisplayName": "JANDI 2/3 Shared Bulk Mixer",
            "productLayer": null, "status": "ReadyForProduction",
            "plannedDestinationMachineCode": null,
            "validNextMachineCodes": ["JAN-DRUM-01", "JAN-02", "JAN-03"],
            "nextStepDescription": "Start one of: JAN-DRUM-01, JAN-02, JAN-03."
          }],
          "activeRuns": [{
            "productionRunId": "RUN_000001", "machineCode": "EXT-03",
            "productionOrderDocumentNumber": "510019068",
            "mixBatchIds": ["MIX_000001"], "startedAtUtc": "2026-07-21T08:30:00Z"
          }]
        }
    """.trimIndent()

    @Test
    fun `overview response parses the simulator shape`() {
        val r = gson.fromJson(overviewJson, MixingOverviewResponse::class.java)
        assertEquals("JandiBulkMixing", r.mixingArea)
        val eq = r.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertTrue(eq.isEnabled && eq.isAvailable)
        assertNull(eq.productLayer)
        assertEquals(listOf("JAN-02", "JAN-03", "JAN-04"), eq.validDestinationMachineCodes)
        assertEquals("CYC_000007", r.activeCycles.single().cycleId)
        assertEquals(listOf("JAN-DRUM-01", "JAN-02", "JAN-03"), r.readyMixes.single().validNextMachineCodes)
        assertEquals("RUN_000001", r.activeRuns.single().productionRunId)
    }

    @Test
    fun `machine cycle result parses with embedded areaStatus and nullable ids`() {
        val json = """
            {
              "action": "Started", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer",
              "machineCode": "MXR-01", "cycleId": "CYC_000001",
              "productionOrderDocumentNumber": "510019068", "collectionId": "COL_000001",
              "mixBatchId": "MIX_000001", "productionRunId": null,
              "affectedMixBatchIds": ["MIX_000001"], "alreadyFinished": false,
              "forceClosed": false, "approverUserId": null, "approverDisplayName": null,
              "approverRole": null, "sapIssueQueued": false, "sapProductionOrderChanged": false,
              "areaStatus": $overviewJson
            }
        """.trimIndent()
        val r = gson.fromJson(json, MachineCycleResultResponse::class.java)
        assertEquals("Started", r.action)
        assertEquals("CYC_000001", r.cycleId)
        assertNull(r.productionRunId)
        assertFalse(r.alreadyFinished)
        assertEquals(1, r.areaStatus.equipment.size)
    }

    @Test
    fun `start payload omits absent optional fields when serialized`() {
        val json = gson.toJson(MachineCycleStartPayload(
            machineCode = "MXR-01", productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001"))
        assertFalse("mixBatchIds must be omitted, not null", json.contains("mixBatchIds"))
        assertFalse("layerInputs must be omitted, not null", json.contains("layerInputs"))
        assertTrue(json.contains("\"collectionId\":\"COL_000001\""))
    }

    @Test
    fun `mixing area maps wire values both ways`() {
        assertEquals(com.mitas.ppnam.station2aa.domain.model.MixingArea.Jandi,
            com.mitas.ppnam.station2aa.domain.model.MixingArea.fromWire("JandiBulkMixing"))
        assertNull(com.mitas.ppnam.station2aa.domain.model.MixingArea.fromWire("Atlantis"))
        assertEquals(5, com.mitas.ppnam.station2aa.domain.model.MixingArea.entries.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.MixingMessagesTest"`
Expected: FAIL (compile — classes don't exist).

- [ ] **Step 3: Write the DTOs**

`app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/** `mixing_overview_requested` — both filters optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
)

data class EquipmentDto(
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val machineCode: String = "",
    val displayName: String = "",
    val isEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    /** Available | InUse | Disabled — rendered verbatim, never inferred locally (§13.7). */
    val status: String = "",
    val productLayer: Int? = null,
    val currentCycleId: String? = null,
    val currentProductionOrderDocumentNumber: String? = null,
    val currentMixBatchIds: List<String> = emptyList(),
    val validDestinationMachineCodes: List<String> = emptyList(),
    val routeDescription: String = "",
)

data class ReadyMixDto(
    val mixBatchId: String = "",
    val collectionId: String = "",
    val mixingArea: String = "",
    val productionOrderDocumentNumber: String = "",
    val mixerCode: String = "",
    val mixerDisplayName: String = "",
    val productLayer: Int? = null,
    val status: String = "",
    val plannedDestinationMachineCode: String? = null,
    /** The ONLY legitimate source of destination choices (§13.8). */
    val validNextMachineCodes: List<String> = emptyList(),
    val nextStepDescription: String = "",
)

data class ActiveCycleDto(
    val cycleId: String = "",
    val machineCode: String = "",
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String? = null,
    val mixBatchIds: List<String> = emptyList(),
    val productionRunId: String? = null,
    val startedAtUtc: String = "",
    val startedByOperatorId: String = "",
)

data class ActiveRunDto(
    val productionRunId: String = "",
    val machineCode: String = "",
    val productionOrderDocumentNumber: String = "",
    val mixBatchIds: List<String> = emptyList(),
    val startedAtUtc: String = "",
)

/** `mixing_overview_result`, and the `areaStatus` embedded in every machine result (§8). */
data class MixingOverviewResponse(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
    val equipment: List<EquipmentDto> = emptyList(),
    val activeCycles: List<ActiveCycleDto> = emptyList(),
    val readyMixes: List<ReadyMixDto> = emptyList(),
    val activeRuns: List<ActiveRunDto> = emptyList(),
)

data class LayerInputDto(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * `machine_cycle_start_requested`. Exactly one of [collectionId] (mixer start) or
 * [mixBatchIds] (drum/production start) travels; [layerInputs] only on a Rajoo mixer.
 * The retired v3 array fields never appear here by construction.
 */
data class MachineCycleStartPayload(
    val machineCode: String,
    val productionOrderDocumentNumber: String,
    val collectionId: String? = null,
    val mixBatchIds: List<String>? = null,
    val layerInputs: List<LayerInputDto>? = null,
)

/** `machine_cycle_finish_requested` — the exact scanned machine plus the server-issued cycle id (§9). */
data class MachineCycleFinishPayload(
    val machineCode: String,
    val cycleId: String,
)

/** `machine_cycle_force_close_requested` — same identity plus inline Manager/Admin approval. */
data class MachineCycleForceClosePayload(
    val machineCode: String,
    val cycleId: String,
    val managerUsername: String,
    val managerPassword: String,
    val auditReason: String,
)

/** `machine_cycle_result` — the unified §8 result for start, finish, and force-close. */
data class MachineCycleResultResponse(
    val action: String? = null,
    val mixingArea: String? = null,
    val equipmentRole: String? = null,
    val machineCode: String? = null,
    val cycleId: String? = null,
    val productionOrderDocumentNumber: String? = null,
    val collectionId: String? = null,
    val mixBatchId: String? = null,
    val productionRunId: String? = null,
    val affectedMixBatchIds: List<String> = emptyList(),
    val alreadyFinished: Boolean = false,
    val forceClosed: Boolean = false,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val sapIssueQueued: Boolean = false,
    val sapProductionOrderChanged: Boolean = false,
    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
)
```

- [ ] **Step 4: Write the domain models**

`app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode

/** The five fixed v4.0 mixing areas (§6). Server-authoritative; never extended locally. */
enum class MixingArea(val wire: String, val display: String) {
    Dolci("DolciBulkMixing", "DOLCI"),
    Main("MainMixingRoom", "Main Mixing Room"),
    Jandi("JandiBulkMixing", "JANDI"),
    Mackie("MackieBulkMixing", "Mackie"),
    Rajoo("RajooMachineMixing", "Rajoo");

    companion object {
        fun fromWire(value: String?): MixingArea? = entries.firstOrNull { it.wire == value }
    }
}

data class Equipment(
    val machineCode: String,
    val displayName: String,
    val area: MixingArea?,
    /** Mixer | Transfer | ProductionMachine — pass-through; unknown roles tolerated. */
    val role: String,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val status: String,
    val productLayer: Int?,
    val currentCycleId: String?,
    val currentJobCardNumber: String?,
    val currentMixBatchIds: List<String>,
    val validDestinationMachineCodes: List<String>,
    val routeDescription: String,
)

data class ReadyMix(
    val mixBatchId: String,
    val collectionId: String,
    val area: MixingArea?,
    val jobCardNumber: String,
    val mixerCode: String,
    val mixerDisplayName: String,
    val status: String,
    val validNextMachineCodes: List<String>,
    val nextStepDescription: String,
)

data class ActiveCycle(
    val cycleId: String,
    val machineCode: String,
    val area: MixingArea?,
    val role: String,
    val jobCardNumber: String,
    val collectionId: String?,
    val mixBatchIds: List<String>,
    val productionRunId: String?,
    val startedAtUtc: String,
    val startedByOperatorId: String,
)

data class ActiveRun(
    val productionRunId: String,
    val machineCode: String,
    val jobCardNumber: String,
    val mixBatchIds: List<String>,
    val startedAtUtc: String,
)

data class AreaOverview(
    val equipment: List<Equipment>,
    val activeCycles: List<ActiveCycle>,
    val readyMixes: List<ReadyMix>,
    val activeRuns: List<ActiveRun>,
) {
    companion object {
        val EMPTY = AreaOverview(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** A ReadyForMixing collection from the active-job-cards list — a mixer start's source. */
data class ReadyCollection(
    val collectionId: String,
    val jobCardNumber: String,
    val productName: String,
)

/** One collected manual line of a collection — the Rajoo dose sheet's row source. */
data class CollectedMaterial(
    val materialCode: String,
    val materialName: String,
    val collectedQty: Double,
)

data class LayerInput(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * The outcome of one machine-cycle operation. Both decided outcomes carry the embedded
 * areaStatus — a rejected start still refreshes the board (§8); [Failed] means Station 2
 * never decided (timeout/transport) and carries nothing.
 */
sealed class MachineCycleOutcome {
    data class Accepted(
        val action: String?,
        val machineCode: String,
        val cycleId: String?,
        val mixBatchId: String?,
        val productionRunId: String?,
        val affectedMixBatchIds: List<String>,
        val alreadyFinished: Boolean,
        val forceClosed: Boolean,
        val approverDisplayName: String?,
        val areaStatus: AreaOverview,
    ) : MachineCycleOutcome()

    data class Rejected(
        val errorCode: ErrorCode?,
        val reason: String,
        val areaStatus: AreaOverview,
    ) : MachineCycleOutcome()

    data class Failed(val message: String) : MachineCycleOutcome()
}
```

- [ ] **Step 5: Run the tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.MixingMessagesTest"`
Expected: PASS (4/4). (If the project's Kotlin version rejects `MixingArea.entries`, use `MixingArea.values()` in both the enum's `fromWire` and the tests — same semantics.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt app/src/main/java/com/ppnam/station2aa/domain/model/MixingBoard.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt
git commit -m "feat(mixing-board): v4 overview/cycle wire DTOs and board domain models"
```

---
### Task 3: MixingBoardUseCase

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`

**Interfaces:**
- Consumes: Task 2's DTOs/models; `MqttRepository.request(requestType, responseType, payload, correlationKey, responseClass)`; existing `ActiveJobCardsListResponse`, `CollectionResumePayload`, `BomLoadedResponse`, `EmptyPayload`, `MqttOutcome`.
- Produces (Tasks 4–5 consume — exact signatures):
  - `suspend fun fetchOverview(area: MixingArea? = null, jobCardNumber: String? = null): Result<AreaOverview>`
  - `suspend fun fetchReadyCollections(): Result<List<ReadyCollection>>`
  - `suspend fun fetchCollectedMaterials(jobCardNumber: String, collectionId: String): Result<List<CollectedMaterial>>`
  - `suspend fun startMixer(machineCode: String, jobCardNumber: String, collectionId: String): MachineCycleOutcome`
  - `suspend fun startRajoo(machineCode: String, jobCardNumber: String, collectionId: String, doses: List<LayerInput>): MachineCycleOutcome`
  - `suspend fun startDownstream(machineCode: String, jobCardNumber: String, mixBatchIds: List<String>): MachineCycleOutcome`
  - `suspend fun finish(machineCode: String, cycleId: String): MachineCycleOutcome`
  - `suspend fun forceClose(machineCode: String, cycleId: String, managerUsername: String, managerPassword: String, auditReason: String): MachineCycleOutcome`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingBoardUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var useCase: MixingBoardUseCase

    @Before
    fun setup() {
        mockMqtt = mock()
        useCase = MixingBoardUseCase(mockMqtt)
    }

    @Test
    fun `fetchOverview sends the area wire value and maps equipment`() = runTest {
        val response = MixingOverviewResponse(
            mixingArea = "JandiBulkMixing",
            equipment = listOf(EquipmentDto(
                mixingArea = "JandiBulkMixing", equipmentRole = "Mixer",
                machineCode = "JAN-MIX-01", displayName = "JANDI Mixer",
                isEnabled = true, isAvailable = true, status = "Available",
                validDestinationMachineCodes = listOf("JAN-02"))))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION_MIX_OR_MACHINE))

        val overview = useCase.fetchOverview(MixingArea.Jandi).getOrThrow()

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MixingOverviewResponse::class.java))
        }.firstValue as MixingOverviewPayload
        assertEquals("JandiBulkMixing", payload.mixingArea)
        val eq = overview.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertEquals(MixingArea.Jandi, eq.area)
        assertEquals("Mixer", eq.role)
    }

    @Test
    fun `fetchReadyCollections keeps only ReadyForMixing`() = runTest {
        val response = ActiveJobCardsListResponse(jobs = listOf(
            ActiveJobCardSummary(jobCardNumber = "510019068", collectionId = "COL_1",
                productName = "HD Film", status = "ReadyForMixing"),
            ActiveJobCardSummary(jobCardNumber = "510019068", collectionId = "COL_2",
                productName = "HD Film", status = "Collecting"),
            ActiveJobCardSummary(jobCardNumber = "510018531", collectionId = "COL_3",
                productName = "LD Film", status = "Mixing"),
        ))
        whenever(mockMqtt.request(
            eq("active_job_cards_requested"), eq("active_job_cards_list"), any(), anyOrNull(),
            eq(ActiveJobCardsListResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val ready = useCase.fetchReadyCollections().getOrThrow()

        assertEquals(listOf("COL_1"), ready.map { it.collectionId })
        assertEquals("510019068", ready.single().jobCardNumber)
    }

    @Test
    fun `fetchCollectedMaterials resumes the collection and keeps collected manual lines`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068", collectionId = "COL_1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-1", materialName = "Resin",
                    collectedQuantity = 550.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-2", materialName = "Uncollected",
                    collectedQuantity = 0.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-3", materialName = "Product",
                    collectedQuantity = 5.0, issueType = "im_Backflush"),
            ))
        whenever(mockMqtt.request(
            eq("collection_resume_requested"), eq("bom_loaded"), any(), anyOrNull(),
            eq(BomLoadedResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.START_MIXING))

        val materials = useCase.fetchCollectedMaterials("510019068", "COL_1").getOrThrow()

        assertEquals(listOf("MAT-1"), materials.map { it.materialCode })
        assertEquals(550.0, materials.single().collectedQty, 0.0)
    }

    @Test
    fun `startMixer sends collectionId and no mixBatchIds`() = runTest {
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(
            MachineCycleResultResponse(action = "Started", machineCode = "MXR-01",
                cycleId = "CYC_1", mixBatchId = "MIX_1"),
            NextAction.SCAN_SAME_MACHINE_TO_FINISH))

        val outcome = useCase.startMixer("MXR-01", "510019068", "COL_1")

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals("COL_1", payload.collectionId)
        assertNull(payload.mixBatchIds)
        assertNull(payload.layerInputs)
        assertTrue(outcome is MachineCycleOutcome.Accepted)
        assertEquals("CYC_1", (outcome as MachineCycleOutcome.Accepted).cycleId)
    }

    @Test
    fun `startRajoo carries layer inputs`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(action = "Started"), NextAction.NONE))

        useCase.startRajoo("RAJ-GM-01", "510019068", "COL_1",
            listOf(LayerInput("MAT-1", 12.5), LayerInput("MAT-2", 3.0)))

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals(2, payload.layerInputs!!.size)
        assertEquals("MAT-1", payload.layerInputs!![0].materialCode)
        assertEquals(12.5, payload.layerInputs!![0].dosingQuantity, 0.0)
        assertEquals("COL_1", payload.collectionId)
    }

    @Test
    fun `startDownstream sends mixBatchIds and no collectionId`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(action = "Started"), NextAction.NONE))

        useCase.startDownstream("EXT-03", "510019068", listOf("MIX_1", "MIX_2"))

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MachineCycleResultResponse::class.java))
        }.firstValue as MachineCycleStartPayload
        assertEquals(listOf("MIX_1", "MIX_2"), payload.mixBatchIds)
        assertNull(payload.collectionId)
    }

    @Test
    fun `a rejected start still carries the embedded areaStatus`() = runTest {
        val response = MachineCycleResultResponse(
            areaStatus = MixingOverviewResponse(equipment = listOf(EquipmentDto(machineCode = "MXR-01"))))
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(response, ErrorCode.EQUIPMENT_IN_USE, "Busy.", NextAction.NONE))

        val outcome = useCase.startMixer("MXR-01", "510019068", "COL_1")

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        val rejected = outcome as MachineCycleOutcome.Rejected
        assertEquals(ErrorCode.EQUIPMENT_IN_USE, rejected.errorCode)
        assertEquals("Busy.", rejected.reason)
        assertEquals(1, rejected.areaStatus.equipment.size)
    }

    @Test
    fun `no response maps to Failed`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        val outcome = useCase.finish("MXR-01", "CYC_1")

        assertTrue(outcome is MachineCycleOutcome.Failed)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCaseTest"`
Expected: FAIL (compile — `MixingBoardUseCase` doesn't exist).

- [ ] **Step 3: Write the use case**

`app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveCycleDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveRunDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.LayerInputDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleFinishPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleForceClosePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyMixDto
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.ActiveRun
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.CollectedMaterial
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.model.ReadyMix
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The board's server operations (§7-§9). Every machine-cycle outcome carries the embedded
 * areaStatus so the board refreshes from the response itself — accepted or rejected —
 * with no extra overview round-trip.
 */
@Singleton
class MixingBoardUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    suspend fun fetchOverview(
        area: MixingArea? = null,
        jobCardNumber: String? = null,
    ): Result<AreaOverview> =
        when (
            val outcome = mqttRepository.request(
                requestType = "mixing_overview_requested",
                responseType = "mixing_overview_result",
                payload = MixingOverviewPayload(
                    mixingArea = area?.wire,
                    productionOrderDocumentNumber = jobCardNumber,
                ),
                correlationKey = jobCardNumber,
                responseClass = MixingOverviewResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body.toAreaOverview())
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Overview rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    suspend fun fetchReadyCollections(): Result<List<ReadyCollection>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = EmptyPayload,
                correlationKey = null,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                outcome.body.jobs
                    .filter { it.status == "ReadyForMixing" }
                    .map { ReadyCollection(it.collectionId, it.jobCardNumber, it.productName) }
            )
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load collections"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /**
     * The Rajoo dose sheet's rows: the collection's collected manual lines. Uses the §12
     * capture action `collection_resume_requested` — resuming a ReadyForMixing collection
     * replays its stored snapshot without touching state.
     */
    suspend fun fetchCollectedMaterials(
        jobCardNumber: String,
        collectionId: String,
    ): Result<List<CollectedMaterial>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "collection_resume_requested",
                responseType = "bom_loaded",
                payload = CollectionResumePayload(jobCardNumber = jobCardNumber, collectionId = collectionId),
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

    suspend fun startMixer(machineCode: String, jobCardNumber: String, collectionId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                collectionId = collectionId,
            ),
            correlationKey = collectionId,
        )

    suspend fun startRajoo(
        machineCode: String,
        jobCardNumber: String,
        collectionId: String,
        doses: List<LayerInput>,
    ): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                collectionId = collectionId,
                layerInputs = doses.map { LayerInputDto(it.materialCode, it.dosingQuantity) },
            ),
            correlationKey = collectionId,
        )

    suspend fun startDownstream(machineCode: String, jobCardNumber: String, mixBatchIds: List<String>): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                mixBatchIds = mixBatchIds,
            ),
            correlationKey = mixBatchIds.firstOrNull(),
        )

    suspend fun finish(machineCode: String, cycleId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_finish_requested",
            MachineCycleFinishPayload(machineCode = machineCode, cycleId = cycleId),
            correlationKey = cycleId,
        )

    suspend fun forceClose(
        machineCode: String,
        cycleId: String,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_force_close_requested",
            MachineCycleForceClosePayload(
                machineCode = machineCode,
                cycleId = cycleId,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = cycleId,
        )

    private suspend fun cycleRequest(requestType: String, payload: Any, correlationKey: String?): MachineCycleOutcome =
        when (
            val outcome = mqttRepository.request(
                requestType = requestType,
                responseType = "machine_cycle_result",
                payload = payload,
                correlationKey = correlationKey,
                responseClass = MachineCycleResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> MachineCycleOutcome.Accepted(
                action = outcome.body.action,
                machineCode = outcome.body.machineCode.orEmpty(),
                cycleId = outcome.body.cycleId,
                mixBatchId = outcome.body.mixBatchId,
                productionRunId = outcome.body.productionRunId,
                affectedMixBatchIds = outcome.body.affectedMixBatchIds,
                alreadyFinished = outcome.body.alreadyFinished,
                forceClosed = outcome.body.forceClosed,
                approverDisplayName = outcome.body.approverDisplayName,
                areaStatus = outcome.body.areaStatus.toAreaOverview(),
            )
            is MqttOutcome.Rejected -> MachineCycleOutcome.Rejected(
                errorCode = outcome.errorCode,
                reason = outcome.reason ?: "Machine cycle rejected",
                areaStatus = outcome.body.areaStatus.toAreaOverview(),
            )
            is MqttOutcome.NoResponse -> MachineCycleOutcome.Failed(outcome.kind.message())
        }

    // ---- DTO -> domain -------------------------------------------------------

    private fun MixingOverviewResponse.toAreaOverview() = AreaOverview(
        equipment = equipment.map { it.toEquipment() },
        activeCycles = activeCycles.map { it.toActiveCycle() },
        readyMixes = readyMixes.map { it.toReadyMix() },
        activeRuns = activeRuns.map { it.toActiveRun() },
    )

    private fun EquipmentDto.toEquipment() = Equipment(
        machineCode = machineCode,
        displayName = displayName,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        isEnabled = isEnabled,
        isAvailable = isAvailable,
        status = status,
        productLayer = productLayer,
        currentCycleId = currentCycleId,
        currentJobCardNumber = currentProductionOrderDocumentNumber,
        currentMixBatchIds = currentMixBatchIds,
        validDestinationMachineCodes = validDestinationMachineCodes,
        routeDescription = routeDescription,
    )

    private fun ReadyMixDto.toReadyMix() = ReadyMix(
        mixBatchId = mixBatchId,
        collectionId = collectionId,
        area = MixingArea.fromWire(mixingArea),
        jobCardNumber = productionOrderDocumentNumber,
        mixerCode = mixerCode,
        mixerDisplayName = mixerDisplayName,
        status = status,
        validNextMachineCodes = validNextMachineCodes,
        nextStepDescription = nextStepDescription,
    )

    private fun ActiveCycleDto.toActiveCycle() = ActiveCycle(
        cycleId = cycleId,
        machineCode = machineCode,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        jobCardNumber = productionOrderDocumentNumber,
        collectionId = collectionId,
        mixBatchIds = mixBatchIds,
        productionRunId = productionRunId,
        startedAtUtc = startedAtUtc,
        startedByOperatorId = startedByOperatorId,
    )

    private fun ActiveRunDto.toActiveRun() = ActiveRun(
        productionRunId = productionRunId,
        machineCode = machineCode,
        jobCardNumber = productionOrderDocumentNumber,
        mixBatchIds = mixBatchIds,
        startedAtUtc = startedAtUtc,
    )
}
```

Note: `FailureKind.message()` already exists (used by `MixingUseCase`) — if the compiler disagrees, check its actual location in `data/mqtt` and match the existing call sites.

- [ ] **Step 4: Run the tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCaseTest"`
Expected: PASS (8/8).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt
git commit -m "feat(mixing-board): MixingBoardUseCase — overview, sources, family starts, finish, force-close"
```

---
### Task 4: MixingBoardViewModel — states, loading, refresh

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3's `MixingBoardUseCase`; `ScanEventBus`, `MqttRepository`, `AuthUseCase`, `OperatorSessionHolder`, `resolveConnectionStatus` (all existing).
- Produces (Task 5 extends the same file; Tasks 6–7 consume): sealed `MixingBoardUiState { Loading; Error(message); AreaPicker(overview, pendingCollectionId); Board(area, overview, readyCollections, selection, highlightedMachineCodes, sheet, busy) }`; sealed `BoardSelection { None; Collection(collectionId, jobCardNumber); Mixes(mixBatchIds, jobCardNumber) }`; `DoseRow(materialCode, materialName, collectedQty, doseText)`; sealed `BoardSheet { None; StartConfirm(machine, doseRows, validationError); CycleSheet(machine, cycle); ForceCloseDialog(machine, cycle, validationError) }`; pure `internal fun computeHighlightedMachines(overview, selection): Set<String>`; VM functions `loadAreaPicker(pendingCollectionId)`, `openArea(area)`, `refresh()`, `logout()`, flows `uiState`, `connectionStatus`, `session`, `logoutEvent`, `messages`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.ActiveRun
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.model.ReadyMix
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class MixingBoardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingBoardUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var connectionState: MutableStateFlow<MqttConnectionState>
    private lateinit var viewModel: MixingBoardViewModel

    // Test fixtures shared with Task 5's tests.
    private fun equipment(
        code: String, role: String = "Mixer", status: String = "Available",
        area: MixingArea? = MixingArea.Main, enabled: Boolean = true,
        currentCycleId: String? = null, currentJc: String? = null,
    ) = Equipment(
        machineCode = code, displayName = code, area = area, role = role,
        isEnabled = enabled, isAvailable = enabled && status == "Available", status = status,
        productLayer = null, currentCycleId = currentCycleId, currentJobCardNumber = currentJc,
        currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
        routeDescription = "",
    )

    private fun readyMix(id: String, jc: String = "510019068", validNext: List<String>) = ReadyMix(
        mixBatchId = id, collectionId = "COL_$id", area = MixingArea.Main, jobCardNumber = jc,
        mixerCode = "MXR-01", mixerDisplayName = "Main Mixer 1", status = "ReadyForProduction",
        validNextMachineCodes = validNext, nextStepDescription = "",
    )

    private val mainOverview = AreaOverview(
        equipment = listOf(
            equipment("MXR-01"), equipment("MXR-02", status = "InUse", currentCycleId = "CYC_9"),
            equipment("EXT-03", role = "ProductionMachine"),
            equipment("EXT-04", role = "ProductionMachine"),
        ),
        activeCycles = listOf(ActiveCycle(
            cycleId = "CYC_9", machineCode = "MXR-02", area = MixingArea.Main, role = "Mixer",
            jobCardNumber = "510019068", collectionId = "COL_9", mixBatchIds = listOf("MIX_9"),
            productionRunId = null, startedAtUtc = "2026-07-21T08:00:00Z", startedByOperatorId = "OP-001")),
        readyMixes = listOf(readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04"))),
        activeRuns = emptyList(),
    )

    private val readyCollections = listOf(ReadyCollection("COL_1", "510019068", "HD Film"))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        connectionState = MutableStateFlow(MqttConnectionState.CONNECTED)

        whenever(mockMqttRepository.connectionState).thenReturn(connectionState)
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))

        viewModel = MixingBoardViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadAreaPicker success carries the overview and the pending collection`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingBoardUiState.AreaPicker)
        assertEquals("COL_1", (state as MixingBoardUiState.AreaPicker).pendingCollectionId)
        verify(mockUseCase).fetchOverview(isNull(), anyOrNull())
    }

    @Test
    fun `loadAreaPicker failure sets Error`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(Exception("boom")))
        viewModel.loadAreaPicker(null)
        advanceUntilIdle()
        assertEquals("boom", (viewModel.uiState.value as MixingBoardUiState.Error).message)
    }

    @Test
    fun `openArea loads the filtered overview and collections and pre-selects the pending collection`() = runTest {
        // Stub BOTH shapes before any call — the eager dispatcher runs launches immediately.
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()

        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(MixingArea.Main, board.area)
        assertEquals(readyCollections, board.readyCollections)
        val sel = board.selection
        assertTrue(sel is BoardSelection.Collection)
        assertEquals("COL_1", (sel as BoardSelection.Collection).collectionId)
        // Collection selected -> available, enabled mixers highlight (MXR-01, not InUse MXR-02)
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)
    }

    @Test
    fun `reconnect triggers a refresh of the current board`() = runTest {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        connectionState.value = MqttConnectionState.DISCONNECTED
        connectionState.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        // openArea's overview fetch ran twice: once on entry, once on reconnect
        verify(mockUseCase, times(2)).fetchOverview(eq(MixingArea.Main), anyOrNull())
    }

    @Test
    fun `computeHighlightedMachines for mixes uses the validNext intersection and same-JC accumulation`() {
        val overview = mainOverview.copy(
            equipment = listOf(
                equipment("EXT-03", role = "ProductionMachine"),
                equipment("EXT-04", role = "ProductionMachine", status = "InUse", currentJc = "510019068"),
                equipment("EXT-05", role = "ProductionMachine", status = "InUse", currentJc = "510018531"),
            ),
            readyMixes = listOf(
                readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04", "EXT-05")),
                readyMix("MIX_2", validNext = listOf("EXT-03", "EXT-04")),
            ),
            activeRuns = listOf(
                ActiveRun("RUN_1", "EXT-04", "510019068", listOf("MIX_0"), "2026-07-21T08:00:00Z"),
                ActiveRun("RUN_2", "EXT-05", "510018531", listOf("MIX_8"), "2026-07-21T08:00:00Z"),
            ),
        )
        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mixes(listOf("MIX_1", "MIX_2"), "510019068"))
        // EXT-03 available+in intersection; EXT-04 accumulating same JC; EXT-05 other JC excluded
        assertEquals(setOf("EXT-03", "EXT-04"), highlights)
    }

    @Test
    fun `computeHighlightedMachines for a collection highlights only enabled available mixers`() {
        val overview = mainOverview.copy(equipment = listOf(
            equipment("MXR-01"),
            equipment("MXR-02", status = "InUse"),
            equipment("MXR-05", enabled = false, status = "Disabled"),
            equipment("EXT-03", role = "ProductionMachine"),
        ))
        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Collection("COL_1", "510019068"))
        assertEquals(setOf("MXR-01"), highlights)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModelTest"`
Expected: FAIL (compile — package doesn't exist).

- [ ] **Step 3: Write the ViewModel (loading half)**

`app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.resolveConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the operator has picked as the START source (source-first, user decision 4). */
sealed class BoardSelection {
    object None : BoardSelection()
    data class Collection(val collectionId: String, val jobCardNumber: String) : BoardSelection()
    data class Mixes(val mixBatchIds: List<String>, val jobCardNumber: String) : BoardSelection()
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

    /** Start confirmation. [doseRows] is non-null only for a Rajoo mixer start. */
    data class StartConfirm(
        val machine: Equipment,
        val doseRows: List<DoseRow>?,
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
 * The pure highlight rule, unit-testable without the ViewModel. Highlights guide TAPS only —
 * a SCAN of any machine is trusted intent and goes to the server regardless (§13.7/§13.8:
 * availability and destinations render from server data; the server stays authoritative).
 */
internal fun computeHighlightedMachines(overview: AreaOverview, selection: BoardSelection): Set<String> =
    when (selection) {
        is BoardSelection.None -> emptySet()
        is BoardSelection.Collection -> overview.equipment
            .filter { it.role == "Mixer" && it.isEnabled && it.status == "Available" }
            .map { it.machineCode }
            .toSet()
        is BoardSelection.Mixes -> {
            val chosen = overview.readyMixes.filter { it.mixBatchId in selection.mixBatchIds }
            val intersection = chosen
                .map { it.validNextMachineCodes.toSet() }
                .reduceOrNull { a, b -> a intersect b }
                .orEmpty()
            val available = overview.equipment
                .filter { it.machineCode in intersection && it.isEnabled && it.status == "Available" }
                .map { it.machineCode }
            // Run accumulation (§8): a production machine busy on the SAME JC with an
            // active run accepts additional completed mixes into that run.
            val accumulating = overview.activeRuns
                .filter { it.jobCardNumber == selection.jobCardNumber && it.machineCode in intersection }
                .map { it.machineCode }
            (available + accumulating).toSet()
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

    val connectionStatus: StateFlow<ConnectionStatus> = combine(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ) { state, stationOnline, skew ->
        resolveConnectionStatus(state, stationOnline, skew)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

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

    init {
        // Reconnect refresh (§13.11): the board is stale after any transport drop.
        viewModelScope.launch {
            mqttRepository.connectionState
                .drop(1) // the value at subscribe time is not a transition
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { refresh() }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            _uiState.value = MixingBoardUiState.Loading
            val overview = useCase.fetchOverview(area).getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load $area")
                return@launch
            }
            val collections = useCase.fetchReadyCollections().getOrElse {
                _uiState.value = MixingBoardUiState.Error(it.message ?: "Could not load collections")
                return@launch
            }
            // Auto-navigation context: pre-select the pending collection while it is still ready.
            val selection = pendingCollectionId
                ?.let { pending -> collections.firstOrNull { it.collectionId == pending } }
                ?.let { BoardSelection.Collection(it.collectionId, it.jobCardNumber) }
                ?: BoardSelection.None
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
}
```

- [ ] **Step 4: Run the tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModelTest"`
Expected: PASS (6/6).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt
git commit -m "feat(mixing-board): board ViewModel — states, area loading, highlight rule, reconnect refresh"
```

---

### Task 5: MixingBoardViewModel — selection, scan/tap dispatch, start/finish/force-close

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt` (add the functions below; extend `init`)
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt` (add tests)

**Interfaces:**
- Produces (Task 7's screen calls exactly these): `selectCollection(collectionId: String)`, `toggleMix(mixBatchId: String)`, `clearSelection()`, `machineChosen(machineCode: String)`, `updateDose(materialCode: String, text: String)`, `confirmStart()`, `dismissSheet()`, `finishCycle()`, `openForceClose()`, `submitForceClose(managerUsername: String, managerPassword: String, auditReason: String)`. Scan events feed `machineChosen` automatically (guarded).

- [ ] **Step 1: Write the failing tests**

Append to `MixingBoardViewModelTest.kt` (uses the Task 4 fixtures):

```kotlin
    private suspend fun openMainBoard() {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
    }

    @Test
    fun `selectCollection highlights mixers and toggleMix respects the same-JC rule`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        var board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)

        viewModel.clearSelection()
        viewModel.toggleMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.Mixes)
        assertEquals(setOf("EXT-03", "EXT-04"), board.highlightedMachineCodes)

        // a second toggle removes it -> selection collapses to None
        viewModel.toggleMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
    }

    @Test
    fun `machineChosen with no selection opens the cycle sheet for a busy machine`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        val sheet = board.sheet
        assertTrue(sheet is BoardSheet.CycleSheet)
        assertEquals("CYC_9", (sheet as BoardSheet.CycleSheet).cycle.cycleId)
    }

    @Test
    fun `machineChosen with no selection on an idle machine only messages`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-01")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `machineChosen with a collection on a Rajoo mixer fetches dose rows`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials("510019068", "COL_1")).thenReturn(
            Result.success(listOf(
                com.mitas.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 550.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue(sheet is BoardSheet.StartConfirm)
        val rows = (sheet as BoardSheet.StartConfirm).doseRows
        assertEquals("MAT-1", rows!!.single().materialCode)
        assertEquals(550.0, rows.single().collectedQty, 0.0)
    }

    @Test
    fun `confirmStart for a collection on a plain mixer calls startMixer`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startMixer(any(), any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "MXR-01", cycleId = "CYC_1",
                mixBatchId = "MIX_5", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_5"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startMixer("MXR-01", "510019068", "COL_1")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
        assertTrue(board.sheet is BoardSheet.None)
        assertFalse(board.busy)
    }

    @Test
    fun `confirmStart for mixes calls startDownstream with the selected ids`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startDownstream(any(), any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "EXT-03", cycleId = "RUN_1",
                mixBatchId = null, productionRunId = "RUN_1",
                affectedMixBatchIds = listOf("MIX_1"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.toggleMix("MIX_1")
        viewModel.machineChosen("EXT-03")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startDownstream("EXT-03", "510019068", listOf("MIX_1"))
    }

    @Test
    fun `a rejected start applies the embedded areaStatus and keeps the selection`() = runTest {
        openMainBoard(); advanceUntilIdle()
        val refreshed = mainOverview.copy(equipment = listOf(equipment("MXR-01", status = "InUse")))
        whenever(mockUseCase.startMixer(any(), any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Rejected(
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.EQUIPMENT_IN_USE,
                reason = "Busy on another cycle.", areaStatus = refreshed))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals("InUse", board.overview.equipment.single { it.machineCode == "MXR-01" }.status)
        assertTrue("selection survives a rejection so the operator can retry elsewhere",
            board.selection is BoardSelection.Collection)
        // and highlights were recomputed against the refreshed overview
        assertTrue(board.highlightedMachineCodes.isEmpty())
    }

    @Test
    fun `rajoo confirm validates doses fail-closed`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials(any(), any())).thenReturn(
            Result.success(listOf(
                com.mitas.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 100.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        viewModel.updateDose("MAT-1", "150.0") // above collected
        viewModel.confirmStart()
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.StartConfirm).validationError)
        verify(mockUseCase, never()).startRajoo(any(), any(), any(), any())
    }

    @Test
    fun `a scan while a sheet is open is ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()
        vm.machineChosen("MXR-02") // opens the cycle sheet

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-01", java.time.Instant.now()))
        advanceUntilIdle()

        val sheet = (vm.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue("cycle sheet must survive a stray scan", sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `a scan on the board dispatches machineChosen`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections()).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-02", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue((vm.uiState.value as MixingBoardUiState.Board).sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `finishCycle sends the stored cycle id and messages alreadyFinished as success`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.finish("MXR-02", "CYC_9")).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Finished", machineCode = "MXR-02", cycleId = "CYC_9",
                mixBatchId = "MIX_9", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_9"), alreadyFinished = true,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.machineChosen("MXR-02")
        viewModel.finishCycle()
        advanceUntilIdle()

        verify(mockUseCase).finish("MXR-02", "CYC_9")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `submitForceClose refuses blank credentials without touching the wire`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        viewModel.openForceClose()
        viewModel.submitForceClose("", "", "")
        advanceUntilIdle()

        verify(mockUseCase, never()).forceClose(any(), any(), any(), any(), any())
        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.ForceCloseDialog).validationError)
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModelTest"`
Expected: FAIL (unresolved `selectCollection` etc.).

- [ ] **Step 3: Add the interaction half of the ViewModel**

Add these imports to `MixingBoardViewModel.kt`:

```kotlin
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
```

Extend `init` with the scan collector (below the reconnect collector):

```kotlin
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
```

Add the interaction functions to the class body:

```kotlin
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

    fun toggleMix(mixBatchId: String) {
        val board = board() ?: return
        if (board.busy || board.sheet != BoardSheet.None) return
        val mix = board.overview.readyMixes.firstOrNull { it.mixBatchId == mixBatchId } ?: return
        val current = board.selection as? BoardSelection.Mixes
        // Same-JC rule (client mirror of job_card_mismatch): ignore taps on other-JC mixes.
        if (current != null && current.jobCardNumber != mix.jobCardNumber) return
        val ids = when {
            current == null -> listOf(mixBatchId)
            mixBatchId in current.mixBatchIds -> current.mixBatchIds - mixBatchId
            else -> current.mixBatchIds + mixBatchId
        }
        val selection = if (ids.isEmpty()) BoardSelection.None
        else BoardSelection.Mixes(ids, mix.jobCardNumber)
        setBoard(board.copy(selection = selection))
    }

    fun clearSelection() {
        val board = board() ?: return
        if (board.busy) return
        setBoard(board.copy(selection = BoardSelection.None))
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
                val cycle = board.overview.activeCycles.firstOrNull { it.machineCode == machineCode }
                if (cycle != null) {
                    setBoard(board.copy(sheet = BoardSheet.CycleSheet(machine, cycle)))
                } else {
                    _messages.trySend("Select a collection or mix to start this machine.")
                }
            }
            is BoardSelection.Collection -> {
                if (machine.area == MixingArea.Rajoo && machine.role == "Mixer") {
                    // Rajoo dose rows come from the collection's collected lines.
                    viewModelScope.launch {
                        setBoard(board.copy(busy = true))
                        useCase.fetchCollectedMaterials(selection.jobCardNumber, selection.collectionId)
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
                    setBoard(board.copy(sheet = BoardSheet.StartConfirm(machine, doseRows = null)))
                }
            }
            is BoardSelection.Mixes ->
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
        actionJob = viewModelScope.launch {
            val outcome: MachineCycleOutcome = when (val selection = board.selection) {
                is BoardSelection.Collection -> {
                    if (sheet.doseRows != null) {
                        val doses = validateDoses(sheet.doseRows)
                        if (doses == null) return@launch // validationError already set
                        setBoard(board.copy(busy = true))
                        useCase.startRajoo(machine.machineCode, selection.jobCardNumber,
                            selection.collectionId, doses)
                    } else {
                        setBoard(board.copy(busy = true))
                        useCase.startMixer(machine.machineCode, selection.jobCardNumber, selection.collectionId)
                    }
                }
                is BoardSelection.Mixes -> {
                    setBoard(board.copy(busy = true))
                    useCase.startDownstream(machine.machineCode, selection.jobCardNumber, selection.mixBatchIds)
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
     * Applies a machine-cycle outcome. Both decided outcomes carry areaStatus (§8) — the
     * board refreshes from the response itself. Accepted clears the selection and re-fetches
     * ready collections (a mixer start consumes one); Rejected keeps the selection so the
     * operator can retry another machine against the refreshed board.
     */
    private suspend fun applyOutcome(
        outcome: MachineCycleOutcome,
        successMessage: (MachineCycleOutcome.Accepted) -> String,
    ) {
        val board = board() ?: return
        when (outcome) {
            is MachineCycleOutcome.Accepted -> {
                val collections = useCase.fetchReadyCollections().getOrElse { board.readyCollections }
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
                    overview = outcome.areaStatus,
                    sheet = BoardSheet.None,
                    busy = false,
                ))
                _messages.trySend(outcome.reason)
            }
            is MachineCycleOutcome.Failed -> {
                setBoard(board.copy(sheet = BoardSheet.None, busy = false))
                _messages.trySend(outcome.message)
            }
        }
    }
```

- [ ] **Step 4: Run the full ViewModel test class**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModelTest"`
Expected: PASS (18/18).

- [ ] **Step 5: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt
git commit -m "feat(mixing-board): source-first selection, scan dispatch, start/finish/force-close flows"
```

---
### Task 6: Navigation, area picker screen, and the three entry points

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt` (navigation event only)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt` (button + event collection)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt` (persistent Mixing button)
- Create: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (one new test)

**Interfaces:**
- Consumes: Tasks 4–5's `MixingBoardViewModel` (`loadAreaPicker`, `uiState`, `connectionStatus`, `session`, `logoutEvent`, `logout`); existing `MixingViewModel` navigation channel; `NextAction.START_MIXING`.
- Produces: `NavRoutes.MIXING_BOARD`, `NavRoutes.MIXING_AREAS` (pattern with optional `pendingCollectionId`), `NavRoutes.MIXING_AREA_BOARD` (pattern with `{area}` wire value), helpers `mixingAreas(pendingCollectionId: String? = null)` and `mixingAreaBoard(areaWire: String)`; `MixingNavDestination.MIXING_BOARD`; `IngredientScanScreen(onStartMixing: (collectionId: String) -> Unit, ...)` (replaces `onProceedToMixing`); `JobLookupScreen(onOpenMixing: () -> Unit = {}, ...)`; `MixingAreaPickerScreen(pendingCollectionId, onAreaChosen, onBack, onLogout, viewModel)`. Task 7's board screen plugs into the `MIXING_AREA_BOARD` composable.

- [ ] **Step 1: Write the failing navigation-event test**

Add to `MixingViewModelTest.kt` (uses the file's existing fixtures/mocks; the enriched-Accepted construction matches SP4a's shape):

```kotlin
    @Test
    fun `an accepted outcome with START_MIXING emits the mixing-board navigation event`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                updatedLines = sampleOrder.lines,
                collectionSummary = "All products collected.",
                collectionStatus = "ReadyForMixing",
                overCollectionToleranceBags = 1.0,
                nextAction = com.mitas.ppnam.station2aa.data.mqtt.NextAction.START_MIXING,
            )))

        val events = mutableListOf<String>()
        val collector = launch { viewModel.navigationEvent.collect { events.add(it) } }
        viewModel.confirmIngredientScan("TAG-1", "full", 2.0)
        advanceUntilIdle()
        collector.cancel()

        assertTrue(events.contains(MixingNavDestination.MIXING_BOARD))
    }
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: FAIL (`MIXING_BOARD` unresolved).

- [ ] **Step 2: Emit the event from the capture ViewModel**

In `MixingViewModel.kt`, extend the destination object:

```kotlin
object MixingNavDestination {
    const val JOB_LOADED = "job_loaded"
    const val HOME = "home"
    const val MIXING_BOARD = "mixing_board"
}
```

In `handleScanOutcome`'s `Accepted` branch, after `_uiState.value = orderLoadedState(updatedOrder)`, add:

```kotlin
                // Auto-navigate to Mixing when the server says the collection is ready
                // (user decision 1). Guidance, not permission — the board re-verifies
                // everything server-side.
                if (outcome.nextAction == NextAction.START_MIXING) {
                    _navigationEvent.trySend(MixingNavDestination.MIXING_BOARD)
                }
```

(`NextAction` is already imported.) Run the Step 1 test again. Expected: PASS.

- [ ] **Step 3: Routes**

`NavRoutes.kt` becomes:

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val MIXING = "mixing"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val RFID_RECOVERY = "rfid/recovery"
    const val MIXING_BOARD = "mixing_board"
    const val MIXING_AREAS = "mixing_board/areas?pendingCollectionId={pendingCollectionId}"
    const val MIXING_AREA_BOARD = "mixing_board/area/{area}"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"

    fun mixingAreas(pendingCollectionId: String? = null) =
        if (pendingCollectionId.isNullOrBlank()) "mixing_board/areas"
        else "mixing_board/areas?pendingCollectionId=$pendingCollectionId"

    fun mixingAreaBoard(areaWire: String) = "mixing_board/area/$areaWire"
}
```

(`HOME` is deleted — Task 8 removes its dead screen; nothing references the constant after this task. If the compiler disagrees, the referencing file is dead code that Task 8 deletes — delete the reference now.)

- [ ] **Step 4: The area picker screen**

`app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun MixingAreaPickerScreen(
    pendingCollectionId: String?,
    onAreaChosen: (MixingArea) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadAreaPicker(pendingCollectionId) }
    LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }

    AppScaffold(
        title = "Mixing",
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
    ) { padding ->
        when (val state = uiState) {
            is MixingBoardUiState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = AmberPrimary) }

            is MixingBoardUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.message, color = DangerRed)
                Button(onClick = { viewModel.loadAreaPicker(pendingCollectionId) }) { Text("Retry") }
            }

            is MixingBoardUiState.AreaPicker -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.pendingCollectionId?.let { pending ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, AmberPrimary),
                        ) {
                            Text(
                                "$pending ready to mix — pick an area",
                                Modifier.padding(12.dp),
                                color = AmberPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(MixingArea.entries) { area ->
                    val equipment = state.overview.equipment.filter { it.area == area }
                    val available = equipment.count { it.isEnabled && it.status == "Available" }
                    val cycles = state.overview.activeCycles.count { it.area == area }
                    val mixes = state.overview.readyMixes.count { it.area == area }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onAreaChosen(area) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(area.display, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$available machine(s) available · $cycles active cycle(s) · $mixes ready mix(es)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mixes > 0) SuccessGreen else TextMuted,
                            )
                        }
                    }
                }
            }

            // The Board state belongs to the area-board route; nothing to render here.
            is MixingBoardUiState.Board -> Unit
        }
    }
}
```

- [ ] **Step 5: Wire the nav graph and the entry points**

`AppNavGraph.kt` — add imports:

```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModel
```

Inside `NavHost`, after the existing `MIXING` nested graph, add:

```kotlin
        navigation(startDestination = NavRoutes.MIXING_AREAS, route = NavRoutes.MIXING_BOARD) {
            composable(
                NavRoutes.MIXING_AREAS,
                arguments = listOf(navArgument("pendingCollectionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                MixingAreaPickerScreen(
                    pendingCollectionId = backStackEntry.arguments?.getString("pendingCollectionId"),
                    onAreaChosen = { area -> navController.navigate(NavRoutes.mixingAreaBoard(area.wire)) },
                    onBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(NavRoutes.LOGIN) { popUpTo(0) }
                    },
                    viewModel = viewModel,
                )
            }
            composable(NavRoutes.MIXING_AREA_BOARD) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                val area = MixingArea.fromWire(backStackEntry.arguments?.getString("area"))
                if (area == null) {
                    // Only our own navigate() calls mint this route; a bad value is a bug.
                    navController.popBackStack()
                } else {
                    MixingBoardScreen(
                        area = area,
                        onBack = { navController.popBackStack() },
                        onLogout = {
                            navController.navigate(NavRoutes.LOGIN) { popUpTo(0) }
                        },
                        viewModel = viewModel,
                    )
                }
            }
        }
```

Until Task 7 lands, add a temporary compile stub at the bottom of `MixingAreaPickerScreen.kt` (Task 7 replaces it with the real file):

```kotlin
// Replaced by the real board screen in the next task; nav wiring compiles today.
@Composable
fun MixingBoardScreen(
    area: MixingArea,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    Text("Board for ${area.display}", color = TextPrimary)
}
```

In the `INGREDIENT_SCAN` composable, replace `onProceedToMixing = { },` (and its SP4b comment) with:

```kotlin
                    onStartMixing = { collectionId ->
                        navController.navigate(NavRoutes.mixingAreas(collectionId))
                    },
```

In the `JOB_LOOKUP` composable, add to the `JobLookupScreen(...)` call:

```kotlin
                    onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) },
```

- [ ] **Step 6: IngredientScanScreen — enabled button + event collection**

In `IngredientScanScreen.kt`:

1. Rename the parameter `onProceedToMixing: () -> Unit` to `onStartMixing: (collectionId: String) -> Unit`.
2. Next to the screen's other `LaunchedEffect`s, add:

```kotlin
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.MIXING_BOARD) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)
                    ?.order?.collectionId?.takeIf { it.isNotBlank() }
                    ?.let(onStartMixing)
            }
        }
    }
```

3. Replace the disabled button block (the one labeled "Mixing available in the next update") with:

```kotlin
                    Button(
                        onClick = {
                            (uiState as? MixingUiState.OrderLoaded)
                                ?.order?.collectionId?.takeIf { it.isNotBlank() }
                                ?.let(onStartMixing)
                        },
                        enabled = readyForMixing,
                        modifier = Modifier.weight(2f).height(56.dp)
                    ) {
                        Text(if (readyForMixing) "Start Mixing" else "Mixing after collection")
                    }
```

(`readyForMixing` already exists from SP4a — it is computed just above the placeholder text. If its `val` is declared below this button in the current file, move the declaration above the button block.) Also update the placeholder text line to drop the "next update" claim:

```kotlin
                        "Collection complete — ready for mixing.",
```

- [ ] **Step 7: JobLookupScreen — persistent Mixing entry**

Add the parameter (after `onRfidLookup`):

```kotlin
    onOpenMixing: () -> Unit = {},
```

and after the "Look Up" `Button(...)` block (before `errorMessage?.let`):

```kotlin
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenMixing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberPrimary),
                border = BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.5f)),
            ) {
                Text("Mixing")
            }
```

- [ ] **Step 8: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git add -A app/src/main/java/com/ppnam/station2aa/navigation app/src/main/java/com/ppnam/station2aa/ui/mixing app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing-board): nav graph, area picker, and the three mixing entry points"
```

---
### Task 7: MixingBoardScreen — sections, machine grid, and the three dialogs

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt` (delete the temporary `MixingBoardScreen` stub at the bottom)

**Interfaces:**
- Consumes: Tasks 4–5's ViewModel API exactly — `openArea`, `refresh`, `selectCollection`, `toggleMix`, `clearSelection`, `machineChosen`, `updateDose`, `confirmStart`, `dismissSheet`, `finishCycle`, `openForceClose`, `submitForceClose`, `messages`, `logoutEvent`; the `Board` state's `overview/readyCollections/selection/highlightedMachineCodes/sheet/busy`.
- Produces: `MixingBoardScreen(area: MixingArea, onBack: () -> Unit, onLogout: () -> Unit, viewModel: MixingBoardViewModel)` — the signature Task 6's nav graph already calls.

- [ ] **Step 1: Delete the stub**

Remove the temporary `MixingBoardScreen` composable (and its comment) from the bottom of `MixingAreaPickerScreen.kt`.

- [ ] **Step 2: Write the board screen**

`app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

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
        is BoardSheet.CycleSheet -> CycleSheetDialog(sheet, viewModel)
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is MixingBoardUiState.Loading -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = AmberPrimary) }

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

            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BoardContent(board: MixingBoardUiState.Board, viewModel: MixingBoardViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (board.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(), color = AmberPrimary, trackColor = GraphiteBorder)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (val sel = board.selection) {
                    is BoardSelection.None -> "Select a collection or mix, then scan a machine"
                    is BoardSelection.Collection -> "Selected: ${sel.collectionId}"
                    is BoardSelection.Mixes -> "Selected: ${sel.mixBatchIds.joinToString()}"
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
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy) { viewModel.selectCollection(collection.collectionId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${collection.collectionId} · JC ${collection.jobCardNumber}",
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            if (collection.productName.isNotBlank()) {
                                Text(collection.productName,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                    }
                }
            }

            if (board.overview.readyMixes.isNotEmpty()) {
                item { SectionHeader("Ready mixes") }
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val mixesSelection = board.selection as? BoardSelection.Mixes
                    val selected = mixesSelection?.mixBatchIds?.contains(mix.mixBatchId) == true
                    // Same-JC rule: once a mix is selected, other JCs grey out.
                    val selectable = mixesSelection == null || mixesSelection.jobCardNumber == mix.jobCardNumber
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = selectable && !board.busy) { viewModel.toggleMix(mix.mixBatchId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${mix.mixBatchId} · JC ${mix.jobCardNumber}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selectable) TextPrimary else TextMuted)
                            Text("From ${mix.mixerDisplayName}",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            // Destinations render ONLY from validNextMachineCodes (§13.8).
                            Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectable) SuccessGreen else TextMuted)
                        }
                    }
                }
            }

            item { SectionHeader("Machines") }
            items(board.overview.equipment.chunked(2)) { pair ->
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
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy && board.selection is BoardSelection.None) {
                                viewModel.machineChosen(cycle.machineCode)
                            },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${cycle.cycleId} on ${cycle.machineCode}",
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("JC ${cycle.jobCardNumber} · started ${cycle.startedAtUtc}",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
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
    val statusColor = when (machine.status) {
        "Available" -> SuccessGreen
        "InUse" -> WarningOrange
        else -> DangerRed
    }
    // Taps work on highlighted machines (start) or, with no selection, on busy
    // machines (cycle sheet). A SCAN reaches any machine via the ViewModel.
    val clickable = !busy && (highlighted || (noSelection && hasCycle))
    Card(
        modifier = modifier.clickable(enabled = clickable) { onChosen(machine.machineCode) },
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp,
            if (highlighted) AmberPrimary else GraphiteBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(machine.displayName, style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary, maxLines = 1)
            Text(machine.machineCode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            // Rendered verbatim from areaStatus.equipment — never inferred locally (§13.7).
            Text(machine.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
    }
}

@Composable
private fun StartConfirmDialog(
    sheet: BoardSheet.StartConfirm,
    selection: BoardSelection,
    viewModel: MixingBoardViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Start ${sheet.machine.displayName}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (selection) {
                        is BoardSelection.Collection -> "Collection ${selection.collectionId} · JC ${selection.jobCardNumber}"
                        is BoardSelection.Mixes -> "Mixes ${selection.mixBatchIds.joinToString()} · JC ${selection.jobCardNumber}"
                        is BoardSelection.None -> ""
                    },
                    color = TextMuted,
                )
                sheet.doseRows?.forEach { row ->
                    OutlinedTextField(
                        value = row.doseText,
                        onValueChange = { viewModel.updateDose(row.materialCode, it) },
                        label = { Text("${row.materialName} (≤ %.2f kg)".format(row.collectedQty)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                sheet.validationError?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.labelMedium)
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
private fun CycleSheetDialog(sheet: BoardSheet.CycleSheet, viewModel: MixingBoardViewModel) {
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
                Text("Started ${cycle.startedAtUtc} by ${cycle.startedByOperatorId}", color = TextMuted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::openForceClose) {
                    Text("Force close…", color = DangerRed)
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
```

- [ ] **Step 3: Full suite + build**

Run: `.\gradlew.bat :app:testDebugUnitTest` then `.\gradlew.bat :app:assembleDebug`
Expected: tests PASS, BUILD SUCCESSFUL (screens have no unit tests — the ViewModel carries the logic; this matches the project's testing pattern).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/
git commit -m "feat(mixing-board): area board screen — sections, machine grid, start/cycle/force-close dialogs"
```

---
### Task 8: Upgrade-gate hoist, dead-code deletion, deferred SP4a tests

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/components/UpgradeGate.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`, `.../ui/mixing/IngredientScanScreen.kt`, `.../ui/mixing/MixingViewModel.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt`, `.../ui/home/HomeViewModel.kt`, `app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (three new tests, one stub removed)

**Interfaces:**
- Produces: `UpgradeRequiredGate()` composable rendered once in `AppNavGraph` above every screen; `MixingViewModel.upgradeRequired` is **removed** (the transport's `MqttRepository.upgradeRequired` latch is unchanged — only its UI consumer moves).

- [ ] **Step 1: Write the failing deferred tests**

Add to `MixingViewModelTest.kt` (SP4a carry-ins — quantity-shaped approval resubmit; waiver refusal branches):

```kotlin
    @Test
    fun `submitManagerApproval resubmits a QUANTITY-shaped scan with the quantity intact`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.NeedsManagerApproval(
                collectionId = "COL_1", palletRfidTag = "EPC:1",
                requestedMaterialCode = "MAT-BULK",
                bagSizeOption = null, bagCount = null, quantity = 42.5,
                reason = "over-collection")))
        viewModel.confirmQuantityScan("EPC:1", 42.5)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.IngredientExceptionApproval)

        viewModel.submitManagerApproval("manager1", "secret", "verified")
        advanceUntilIdle()

        verify(mockUseCase).scanIngredient(
            eq("COL_1"), eq("EPC:1"), eq("MAT-BULK"),
            isNull(), isNull(), eq(42.5),
            eq("manager1"), eq("secret"), eq("verified"))
    }

    @Test
    fun `openShortBagWaiver refuses a bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.openShortBagWaiver("MAT-BULK")
        assertTrue("a bulk line has no bag arithmetic to waive",
            viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `openShortBagWaiver refuses outside OrderLoaded`() = runTest {
        // Nothing loaded: Idle state, no cached order.
        viewModel.openShortBagWaiver("MAT-001")
        assertTrue(viewModel.uiState.value is MixingUiState.Idle)
    }
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: the two waiver tests PASS already (the guards exist since SP4a — these are the deferred regression pins); the resubmit test PASSES too if the SP4a pass-through is correct. A FAILURE here is a real bug — fix the production code, not the test.

- [ ] **Step 2: Create the app-level gate**

`app/src/main/java/com/ppnam/station2aa/ui/components/UpgradeGate.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class UpgradeGateViewModel @Inject constructor(
    mqttRepository: MqttRepository,
) : ViewModel() {
    val upgradeRequired: StateFlow<Boolean> = mqttRepository.upgradeRequired
}

/**
 * The app-level `client_upgrade_required` gate (contract §10: "Block Mixing and require
 * the 4.0 reader build"). Rendered once above the NavHost so it blocks EVERY screen —
 * the transport's latch never resets, so neither does this dialog; only a new build
 * clears the condition.
 */
@Composable
fun UpgradeRequiredGate(viewModel: UpgradeGateViewModel = hiltViewModel()) {
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "Station 2 requires the 4.0 reader build for this workflow. " +
                        "Install the update, then log in again.",
                    color = TextMuted,
                )
            },
            confirmButton = {},
            containerColor = GraphiteSurface,
        )
    }
}
```

In `AppNavGraph.kt`, add the import `com.mitas.ppnam.station2aa.ui.components.UpgradeRequiredGate` and render the gate directly after the `NavHost(...) { ... }` block (still inside `AppNavGraph`'s body):

```kotlin
    UpgradeRequiredGate()
```

- [ ] **Step 3: Remove the screen-local gate**

- `IngredientScanScreen.kt`: delete `val upgradeRequired by viewModel.upgradeRequired.collectAsState()` and the whole `if (upgradeRequired) { AlertDialog(...) }` block.
- `MixingViewModel.kt`: delete `val upgradeRequired: StateFlow<Boolean> = mqttRepository.upgradeRequired`.
- `MixingViewModelTest.kt`: delete the `whenever(mockMqttRepository.upgradeRequired).thenReturn(MutableStateFlow(false))` setup stub.

Verify the latch's consumers are now exactly the repository pair, the gate, and the transport tests:

```bash
grep -rn "upgradeRequired" app/src --include="*.kt"
```

Expected: `MqttRepository.kt`, `MqttRepositoryImpl.kt` (4 hits), `UpgradeGate.kt` (2 hits), `MqttRequestCorrelationTest.kt` (4 hits) — nothing else.

- [ ] **Step 4: Delete the dead home screen**

```bash
git rm app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt
grep -rn "HomeScreen\|HomeViewModel\|NavRoutes.HOME" app/src --include="*.kt"
```

Expected: no matches (nothing has navigated to it since the Jul 16 restructure; `NavRoutes.HOME` went in Task 6). `MixingNavDestination.HOME` (the string constant in MixingViewModel) is unrelated wiring history — if the grep shows it is also unreferenced, delete that constant too.

- [ ] **Step 5: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git add -A app/src
git commit -m "feat(app): app-level upgrade gate, dead home screen removed, deferred SP4a test pins"
```

---

### Task 9: SP4b acceptance gate

**Files:** none created — verification only (fix-forward anything it surfaces).

- [ ] **Step 1: Simulator conformance**

```bash
cd tools/backend-sim
python selftest.py --direct
```

Expected: `ALL 109 CHECKS PASSED — simulator is v4.0 contract-conformant`, exit 0.

- [ ] **Step 2: App unit tests + debug build**

```bash
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: both succeed.

- [ ] **Step 3: Retired-token guard grep**

```bash
grep -rniE "machineCodes|collectionIds|preMixId|preMixIds" app/src --include="*.kt"
```

Expected: no matches — the new start payloads must never have grown a v3 field.

- [ ] **Step 4: Knowledge graph**

```bash
graphify update .
git add graphify-out
git commit -m "chore: refresh knowledge graph after SP4b"
```

- [ ] **Step 5: Manual §14 acceptance flow (HUMAN — do not simulate)**

Against `python sim.py` on the live broker, on the handheld/emulator:

1. Log in (`operator1`/`pass`), load JC `510019068`, collect every manual line (bulk via the quantity dialog, one waiver). Confirm auto-navigation lands on the area picker with the "ready to mix" banner.
2. Open Main Mixing Room: the pending collection is pre-selected and available mixers highlight. Start `MXR-01` (tap or scan `MXR-01` as barcode), confirm — note the returned `MIX_`/`CYC_` in the snackbar; the mixer shows InUse from the embedded areaStatus.
3. Finish via the cycle sheet; the mix appears under Ready mixes with its valid destinations. Select it; start `EXT-03`; verify `RUN_` id; finish the run.
4. JANDI: collect a second collection, mix on `JAN-MIX-01`, verify `JAN-04` start is rejected with the drum message, run the drum, then start `JAN-04`.
5. Rajoo: third collection, select it, scan `RAJ-GM-01`, enter doses (try one above collected to see the validation error), start, then force-close with `manager1`/`secret` + audit reason; verify the approver in the snackbar and `managerPassword: "***"` in `wire.jsonl`.
6. Verify every request in `wire.jsonl` carries `"schemaVersion": "4.0"` and nothing hit `res/workflow_upgrade_required`.

Then use **superpowers:finishing-a-development-branch**. Merging SP4b completes the v4 surface; the release decision (real machine codes, backend force-close semantics confirmation) stays with the Station 2 developer.

---

## Deferred / open items (unchanged from the spec)

1. Real DOLCI/Mackie/Rajoo-extruder machine codes — seed topology re-code when confirmed.
2. Backend §11 body-hash check on the 4.0 replay path — app self-polices regardless.
3. Backend confirmation of force-close semantics (sim voids the mix and releases the collection claim).
