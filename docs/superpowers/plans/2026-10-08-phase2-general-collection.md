# Contract 2026-10-01: Phase 2, General preparation and collection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** an operator can:
- open a General job card (JC) and answer the Include/Exclude choices;
- `prepare` N available mixes;
- resume any saved preparation (PREP) by `read`;
- `capture` one physical bag or fraction per deliberate scan, using exact BigDecimal quantities;
- `confirm` the collection.

Every mutation goes through the Phase 1 outbox. A lost reply never permits a second physical capture. A recovery that comes back `not_executed` asks the operator to confirm before anything replaces it.

**Architecture:**
- The request DTOs `Rev2PrepareRequest`, `Rev2CaptureRequest` and `Rev2ConfirmRequest` are sent via `MqttRepository.sendCommand`.
- `GeneralCollectionUseCase` maps every `CommandOutcome` to a `CollectionResult`.
- New domain model `Preparation`/`PrepMaterial` with `BigDecimal` quantities.
- Two new screens, each with its own ViewModel, inside the `jobs` nav graph:
  - `PrepareScreen` / `PrepareViewModel`;
  - `PreparationScreen` / `PreparationViewModel`.
- The repository gains a multi-subscriber `serverHints` flow.
- The Python simulator gains receipts, `recover`, the catalog, `prepare`, `capture` and `confirm`.

**Tech stack:** as in Phase 1 (Kotlin 2.0, Hilt, HiveMQ 1.3.3, Gson 2.10.1, Compose, JUnit 4, mockito-kotlin 5, coroutines-test, Python 3 simulator).

**Spec:** `docs/superpowers/specs/2026-10-08-phase2-general-collection-design.md`. Its parent spec is `2026-10-05-contract-2026-10-01-adoption-design.md`, and the authority is `PPNAM-Station-2/RFID_MQTT_CONTRACT.md` (rev2.1, 2026-10-01).

## Global Constraints

- The sibling repo `C:\Dev\Clients\PPNAM\Station_2\PPNAM-Station-2` is **read-only**. Copying fixtures out of it is allowed. They are already copied to `app/src/test/resources/contract/2026-10-01/`.
- Wire schema stays exactly `rev2.1`. Branch on `error` only; never on `nextAction` or `operatorMessage`.
- Every mutation (`prepare`, `capture`, `confirm`) goes through `sendCommand`; reads go through `request`. Retrying uses `retryCommand(command)`, which republishes identical bytes and the same id. **Never** build a new request to retry an unresolved one.
- Each deliberate bag tap gets a new messageId, which `sendCommand` generates. A replacement after `not_executed` is a new `sendCommand` that the operator explicitly confirmed.
- `quantity` is omitted for a whole bag. When present it is a JSON number written in plain notation (no exponent), with at most 3 decimal places (spec Q3). `mixCount` and `exceptionListRevision` are JSON integers. Never send `includeTackifier`, and never send a null choice.
- Compare quantities with `compareTo`, never `equals` and never `Double`.
- Every Gson **response** DTO constructor parameter keeps a default (see `ResponseEnvelope` KDoc). Request DTOs are serialized only.
- Never log session ids, proofs or credentials (`Redact.kt`).
- Unit tests: `.\gradlew.bat :app:testDebugUnitTest --offline [--tests "<fqcn>"]`. Build: `.\gradlew.bat :app:assembleDebug --offline`. Run both in PowerShell from the repo root `C:\Dev\Clients\PPNAM\Station_2\PPNAM_Station_2_AA`.
- Simulator selftest: `cd tools/backend-sim; python selftest.py --direct`.
- After code changes, run `graphify update .`.
- Branch `feat/contract-2026-10-01-phase2-collection`, taken from `master` **after** the Phase 2 entry gate (`fix/phase2-entry-gate`) has merged.
- Spec open questions Q1–Q9 are implemented with their **proposed defaults**. Keep each default in one named constant or function so a different answer is a one-line change: `CaptureMode`, `QuantityInput.MAX_SCALE`, `PREPARE_REASON_MAX`, `STATION_ZONE`.

## Review Focus

1. **A lost capture reply must never lead to a second physical capture.** The PREP screen blocks scans and confirm while an Unresolved/ManagerReconcile entry targets the PREP, and Retry republishes the identical bytes. Pinned in Task 8 by `an unresolved capture blocks the next scan` and `Retry republishes the identical command`.
2. **A read or hint never clears an unresolved capture** (contract §9: a quantity read is not proof). Pinned in Task 8 by `a read after an unresolved capture keeps it blocked`.
3. **A stale reply must never roll back the stage.** Confirm does not bump `collectionRevision`, so ordering is by local request sequence. Pinned in Task 8 by `an older read arriving after confirm does not regress the stage`.
4. **Quantities are exact.** No `Double` on the capture path and no `1E+1` on the wire. Pinned in Task 1 (`WireDecimalTest`) and Task 2 (`capture with a fraction writes a plain decimal`).
5. **Choices are never defaulted.** The Prepare button stays disabled until every code is explicitly answered, and the request echoes the read's catalog revision. Pinned in Task 6.
6. **A replacement after `not_executed` is sent only after an explicit tap, with a new id.** A `not_executed` prepare is never auto-resent. Pinned in Task 10.

---

## File map

| File | Responsibility |
|---|---|
| `data/mqtt/dto/Rev2GeneralMessages.kt` | `Rev2Material` → BigDecimal; + `Rev2PrepareRequest`, `Rev2ChoiceAnswer`, `Rev2CaptureRequest`, `Rev2ConfirmRequest` |
| `domain/model/Quantity.kt` | `WireDecimal`, `QuantityInput` |
| `domain/model/Preparation.kt` | `Preparation`, `PrepMaterial`, `PrepStage`, `PrepSnapshot`, `ChoicePrompt`, `CollectionExceptionRecord`, `CaptureIntent` |
| `domain/usecase/Rev2Mapping.kt` | DTO → domain mapping (moved out of `JobLookupUseCase` and extended) |
| `domain/usecase/GeneralCollectionUseCase.kt` | read / prepare / capture / confirm / retry → `CollectionResult` |
| `domain/usecase/PrepareForm.kt` | Pure validation of the prepare form |
| `domain/usecase/ReplacementOffer.kt` | A `not_executed` recovery → an offer the operator must confirm |
| `domain/repository/MqttRepository.kt`, `data/mqtt/MqttRepositoryImpl.kt` | + `serverHints: SharedFlow<ServerHint>` |
| `ui/joblookup/JobLookupViewModel.kt`, `JobLookupScreen.kt`, `JobDetailScreen.kt` | Hints via the flow; `PREP_` input; Prepare button; clickable PREPs; pending-prepare banner |
| `ui/preparation/PrepareViewModel.kt`, `PrepareScreen.kt` | Prepare form |
| `ui/preparation/PreparationViewModel.kt`, `PreparationScreen.kt`, `PreparationFormat.kt` | PREP screen |
| `ui/util/TimeFormat.kt` | + `formatStationDateTime` (always date + time, SAST) |
| `ui/home/HomeScreen.kt`, `RecoveryNoticeText.kt` | "Open PREP_…" / "Open job …" action on `NotExecuted` notices |
| `navigation/NavRoutes.kt`, `AppNavGraph.kt` | `jobs/prepare/{jobCard}`, `jobs/prep/{prepId}` |
| `tools/backend-sim/state.py`, `sim.py`, `handlers/rev2_general.py`, `seed/seed.json`, `selftest.py`, `README.md` | Receipts, recover, catalog, prepare, capture, confirm, `drop_reply` fault |

In the table above, `data/`, `domain/`, `ui/` and `navigation/` stand for `app/src/main/java/com/mitas/ppnam/station2aa/`. Tests mirror the same paths under `app/src/test/java/com/mitas/ppnam/station2aa/`.

---

### Task 0: Branch and entry-gate check

- [ ] **Step 1: Create the branch**

```powershell
git checkout master
git pull --ff-only
git checkout -b feat/contract-2026-10-01-phase2-collection
```

- [ ] **Step 2: Confirm the entry gate is on master**

Run, in Git Bash:

```bash
grep -n "data class NotSaved\|data class NoLongerPending" app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/CommandOutcome.kt
grep -n "val exceptionListRevision: Int? = null" app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/Rev2GeneralMessages.kt
grep -n "commandOutbox: CommandOutbox," app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt
```

Expected: every grep prints a line. In the last one, the constructor parameter has **no** `= InMemoryCommandOutbox()` default. If any grep prints nothing, stop: the entry gate has not merged.

- [ ] **Step 3: The baseline is green**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL.

---

### Task 1: Exact quantities (`BigDecimal` on the wire DTO, `WireDecimal`, `QuantityInput`)

**Acceptance:** GEN-07 (exact quantities), GEN-09 (remaining).

**Files:**
- Modify: `data/mqtt/dto/Rev2GeneralMessages.kt` (`Rev2Material`)
- Modify: `domain/usecase/JobLookupUseCase.kt` (map with `.toDouble()`)
- Create: `domain/model/Quantity.kt`
- Test: `domain/model/QuantityTest.kt`
- Modify tests: `domain/usecase/JobLookupUseCaseTest.kt:41-42`, `data/mqtt/dto/Rev2SnapshotFixtureTest.kt:76,107`, `data/mqtt/Rev2GeneralWireShapeTest.kt:155-156`

**Interfaces:**
- Produces: `WireDecimal.of(BigDecimal): BigDecimal`. It strips trailing zeros and never returns a negative scale.
- Produces: `QuantityInput.parse(String): BigDecimal?`. It accepts `.` or `,` as the separator, must be > 0, at most `MAX_SCALE = 3` decimal places, at most 999 999 999, with no sign and no exponent.
- Produces: `Rev2Material.perMix/required/collected/remaining: BigDecimal` (default `BigDecimal.ZERO`) and `originalRequired: BigDecimal?`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/domain/model/QuantityTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class QuantityTest {

    @Test
    fun `wire form is plain and minimal`() {
        assertEquals("10", WireDecimal.of(BigDecimal("10.000")).toString())
        assertEquals("100", WireDecimal.of(BigDecimal("1E+2")).toString())
        assertEquals("0.25", WireDecimal.of(BigDecimal("0.250")).toString())
        assertEquals("0.001", WireDecimal.of(BigDecimal("0.001")).toString())
    }

    @Test
    fun `operator input accepts a dot or a comma`() {
        assertEquals(0, BigDecimal("2.5").compareTo(QuantityInput.parse("2,5")))
        assertEquals(0, BigDecimal("2.5").compareTo(QuantityInput.parse(" 2.50 ")))
        assertEquals("12", QuantityInput.parse("12")!!.toString())
    }

    @Test
    fun `operator input rejects what Station 2 would reject or we cannot send exactly`() {
        listOf("", "0", "0.0", "-1", "+1", "1e3", "1.2345", "abc", "1.", ".5", "1000000000", "1,2,3")
            .forEach { assertNull(it, QuantityInput.parse(it)) }
    }

    @Test
    fun `comparison ignores scale`() {
        val remaining = BigDecimal("40")
        assertEquals(0, remaining.compareTo(BigDecimal("40.000")))
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.domain.model.QuantityTest"`
Expected: compilation FAILS: `Unresolved reference: WireDecimal`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/mitas/ppnam/station2aa/domain/model/Quantity.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import java.math.BigDecimal

/**
 * Contract §2: quantities are compared exactly and sent as JSON numbers. Gson writes a BigDecimal
 * through toString(), which uses exponent notation for a negative scale (1E+2) or for an adjusted
 * exponent below -6. A scale of 0..[QuantityInput.MAX_SCALE] avoids both.
 */
object WireDecimal {
    fun of(value: BigDecimal): BigDecimal =
        value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
}

/** Parses what an operator typed for a part bag. Null means "not a quantity we may send". */
object QuantityInput {
    /** Spec open question Q3: grams, for kg materials. */
    const val MAX_SCALE = 3
    private val MAX = BigDecimal("999999999")
    private val PATTERN = Regex("^[0-9]+([.,][0-9]+)?$")

    fun parse(text: String): BigDecimal? {
        val trimmed = text.trim()
        if (!PATTERN.matches(trimmed)) return null
        val value = WireDecimal.of(BigDecimal(trimmed.replace(',', '.')))
        if (value.signum() <= 0 || value > MAX || value.scale() > MAX_SCALE) return null
        return value
    }
}
```

In `Rev2GeneralMessages.kt`, change `Rev2Material` to:

```kotlin
data class Rev2Material(
    val code: String = "",
    val name: String = "",
    val unit: String = "",
    val perMix: BigDecimal = BigDecimal.ZERO,
    val required: BigDecimal = BigDecimal.ZERO,
    val collected: BigDecimal = BigDecimal.ZERO,
    /** Excluded (initial choice, omit, or substituted away): shown, never collected. */
    val excluded: Boolean = false,
    /** Original scope target; 0 for an operator-added row; null when a legacy baseline is unknown. */
    val originalRequired: BigDecimal? = null,
    val remaining: BigDecimal = BigDecimal.ZERO,
)
```

Add `import java.math.BigDecimal`. Gson binds a JSON number to `BigDecimal` from its literal text.

In `JobLookupUseCase.kt` (the `materials = job.materials.map { … }` line), map for display:

```kotlin
JobMaterial(it.code, it.name, it.unit, it.perMix.toDouble(), it.required.toDouble(), it.collected.toDouble(),
    it.excluded, it.originalRequired?.toDouble())
```

- [ ] **Step 4: Fix the existing tests that used doubles**

- `JobLookupUseCaseTest.kt:41-42`: build `Rev2Material` with `BigDecimal("69.631")`, `BigDecimal("557.049")`, `BigDecimal("100")`, `BigDecimal("1.5")`, `BigDecimal.ZERO`.
- `Rev2SnapshotFixtureTest.kt:76`: `assertEquals(0, BigDecimal("4").compareTo(added.required))`.
- `Rev2SnapshotFixtureTest.kt:107`: `s.job!!.materials.all { it.perMix.signum() == 0 }`.
- `Rev2GeneralWireShapeTest.kt:155-156`: compare with `BigDecimal("12.5")` / `BigDecimal("100")` using `compareTo`.

Add one assertion to `Rev2SnapshotFixtureTest`. It pins that the server's `remaining` equals our formula for every material in `final_capture_response.json`:

```kotlin
@Test
fun `remaining is max(0, required - collected) exactly`() {
    val s = snapshot("final_capture")
    for (m in s.preparation!!.materials) {
        assertEquals(m.code, 0, m.remaining.compareTo((m.required - m.collected).max(BigDecimal.ZERO)))
    }
}
```

(Use the existing fixture-loading helper in that test class; it is named `snapshot(...)` or similar. Read the file first.)

- [ ] **Step 5: Run everything**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(quantity): exact BigDecimal material quantities and operator quantity input"
```

---

### Task 2: Request DTOs for prepare, capture and confirm, checked against the golden requests

**Acceptance:** GEN-02, GEN-03, GEN-05, GEN-07, GEN-09.

**Files:**
- Modify: `data/mqtt/dto/Rev2GeneralMessages.kt`
- Test: `data/mqtt/dto/Rev2CollectionRequestTest.kt`

**Interfaces:**
- Produces:
  - `Rev2PrepareRequest(targetId, mixCount: Int, exceptionListRevision: Int?, ingredientChoices: List<Rev2ChoiceAnswer>, reason: String? = null)`;
  - `Rev2ChoiceAnswer(code: String, include: Boolean)`;
  - `Rev2CaptureRequest(targetId, code, quantity: BigDecimal? = null)`;
  - `Rev2ConfirmRequest(targetId)`.
- Each request DTO carries `action` as its last parameter, with a fixed default.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/Rev2CollectionRequestTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.RequestEnvelope
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Our serialization of each mutation equals Station 2's own generated request, field for field.
 * Byte equality is impossible: RequestEnvelope appends the envelope after the payload, so field
 * order differs, and the fingerprint is always of OUR bytes.
 */
class Rev2CollectionRequestTest {

    private fun built(payload: Any, fixture: String): Pair<JsonObject, JsonObject> {
        val expected = JsonParser.parseString(ContractFixtures.text("${fixture}_request.json")).asJsonObject
        val json = RequestEnvelope.build(
            gson = WireJson.gson, payload = payload,
            messageId = expected["messageId"].asString, deviceId = expected["deviceId"].asString,
            sessionId = expected["sessionId"].asString, timestampUtc = expected["timestampUtc"].asString,
        )
        return JsonParser.parseString(json).asJsonObject to expected
    }

    @Test
    fun `prepare matches the generated request`() {
        val (actual, expected) = built(
            Rev2PrepareRequest(
                targetId = "1", mixCount = 4, exceptionListRevision = 1,
                ingredientChoices = listOf(Rev2ChoiceAnswer("OPTIONAL", false)),
                reason = "Prepare four mixes without the optional ingredient",
            ),
            "general_prepare",
        )
        assertEquals(expected, actual)
        assertTrue(actual["mixCount"].asJsonPrimitive.isNumber)
        assertFalse(actual.has("includeTackifier"))
    }

    @Test
    fun `prepare with no choices still sends an empty list and omits a blank reason`() {
        val (actual, _) = built(
            Rev2PrepareRequest("1", 4, 1, emptyList(), reason = null), "general_prepare",
        )
        assertEquals(0, actual["ingredientChoices"].asJsonArray.size())
        assertFalse(actual.has("reason"))
    }

    @Test
    fun `capture matches the generated request`() {
        val (actual, expected) = built(
            Rev2CaptureRequest("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", "TAG-RESIN", BigDecimal("10")),
            "general_capture",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a whole bag omits quantity`() {
        val (actual, _) = built(Rev2CaptureRequest("PREP_1", "TAG-1"), "general_capture")
        assertFalse(actual.has("quantity"))
    }

    @Test
    fun `capture with a fraction writes a plain decimal`() {
        val json = RequestEnvelope.build(
            WireJson.gson, Rev2CaptureRequest("PREP_1", "TAG-1", BigDecimal("0.25")),
            "m-1", "scanner_1", "s", "2026-10-01T08:00:00.000000Z",
        )
        assertTrue(json, json.contains("\"quantity\":0.25"))
        assertFalse(json, json.contains("E"))
    }

    @Test
    fun `confirm matches the generated request`() {
        val (actual, expected) = built(Rev2ConfirmRequest("PREP_440fbd8f9cd3411e9be943a33f1a3ceb"), "general_confirm")
        assertEquals(expected, actual)
    }
}
```

`assertFalse(json.contains("E"))` is safe: the fixture-free payload contains no uppercase `E` except in a number.

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CollectionRequestTest"`
Expected: compilation FAILS: `Unresolved reference: Rev2PrepareRequest`.

- [ ] **Step 3: Implement** (append to `Rev2GeneralMessages.kt`)

```kotlin
/**
 * Contract §4/§7 `prepare`. [ingredientChoices] is never null: `[]` when no code needs a choice.
 * [exceptionListRevision] is the READ's top-level catalog revision (`data.exceptionListRevision`),
 * never the job's. A blank [reason] must be passed as null so it is omitted.
 */
data class Rev2PrepareRequest(
    val targetId: String,
    val mixCount: Int,
    val exceptionListRevision: Int?,
    val ingredientChoices: List<Rev2ChoiceAnswer>,
    val reason: String? = null,
    val action: String = "prepare",
)

/** One explicit Include/Exclude answer. No default: an unanswered choice must not compile. */
data class Rev2ChoiceAnswer(val code: String, val include: Boolean)

/**
 * Contract §4 `capture`: one physical bag ([quantity] null, so it is omitted) or one fraction.
 * [quantity] must already be [com.mitas.ppnam.station2aa.domain.model.WireDecimal]-normalised.
 */
data class Rev2CaptureRequest(
    val targetId: String,
    val code: String,
    val quantity: BigDecimal? = null,
    val action: String = "capture",
)

data class Rev2ConfirmRequest(
    val targetId: String,
    val action: String = "confirm",
)
```

- [ ] **Step 4: Run the test; it passes.** Then run the whole suite.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): prepare/capture/confirm request DTOs matching Station 2's generated requests"
```

---

### Task 3: The domain preparation model and snapshot mapping

**Acceptance:** GEN-06, GEN-09, GEN-11, GEN-12 (server counts shown, never derived).

**Files:**
- Create: `domain/model/Preparation.kt`
- Create: `domain/usecase/Rev2Mapping.kt` (move `Rev2Snapshot.toDomain()` and `Rev2MixProgress.toDomain()` here from `JobLookupUseCase.kt`; make them `internal`; rename the first to `toJobLookupSnapshot()`)
- Modify: `domain/usecase/JobLookupUseCase.kt` (call the moved functions)
- Test: `domain/usecase/Rev2MappingTest.kt`

**Interfaces:**
- Produces: `enum class PrepStage { Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, ReadyForNextMix, ReadyForProduction, Producing, Completed, Cancelled, Unknown; companion fun fromWire(String) }`.
- Produces: `PrepMaterial(code, name, unit, perMix, required, collected, excluded, originalRequired)`, with `remaining`, `isComplete` and `isAdded`.
- Produces: `Preparation(id, jobId, mixCount, mixed, produced, stage, stageRaw, collectionRevision, startedAtUtc, startedBy, confirmedAtUtc, materials)`, with `allIncludedCollected`.
- Produces: `ChoicePrompt(code, description, note)` and `CollectionExceptionRecord(id, timestampUtc, operatorId, device, messageId, jobId, batchId, kind, details, reason, reviewedAtUtc, reviewedBy, reviewReason)`.
- Produces: `PrepSnapshot(lookup: JobLookupSnapshot, preparation: Preparation?, requiredChoices: List<ChoicePrompt>, exceptionListRevision: Int?, collectionExceptions, commandExceptions, resultTargetId: String?, resultMessage: String)`.
- Produces: `CaptureIntent(code: String, quantity: BigDecimal?)` with `companion fun fromPayload(json: String): CaptureIntent?`.
- Produces: `internal fun Rev2Snapshot.toPrepSnapshot(): PrepSnapshot`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/Rev2MappingTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class Rev2MappingTest {

    private fun data(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    )

    @Test
    fun `lookup carries the choices and the catalog revision to echo`() {
        val s = data("general_lookup").toPrepSnapshot()
        assertEquals(listOf("OPTIONAL"), s.requiredChoices.map { it.code })
        assertEquals(1, s.exceptionListRevision)
        assertNull(s.preparation)
        assertEquals(60, s.lookup.detail!!.mixProgress!!.availableToPrepareMixes)
    }

    @Test
    fun `prepare gives the new PREP with effective rows and the omission record`() {
        val s = data("general_prepare").toPrepSnapshot()
        val p = s.preparation!!
        assertEquals("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", s.resultTargetId)
        assertEquals(PrepStage.Collecting, p.stage)
        val optional = p.materials.single { it.code == "OPTIONAL" }
        assertTrue(optional.excluded)
        assertEquals(0, BigDecimal("8").compareTo(optional.originalRequired))
        assertEquals("Ingredient omitted", s.commandExceptions.single().kind)
        assertTrue(s.requiredChoices.isEmpty())
    }

    @Test
    fun `final capture is awaiting confirmation with every included row complete`() {
        val p = data("final_capture").toPrepSnapshot().preparation!!
        assertEquals(PrepStage.AwaitingConfirmation, p.stage)
        assertEquals(4, p.collectionRevision)
        assertTrue(p.allIncludedCollected)
    }

    @Test
    fun `confirm is ReadyForMixer and keeps the revision`() {
        val p = data("general_confirm").toPrepSnapshot().preparation!!
        assertEquals(PrepStage.ReadyForMixer, p.stage)
        assertEquals(4, p.collectionRevision)
    }

    @Test
    fun `operator-added and substituted rows survive the mapping`() {
        val p = data("general_recover_not_executed").toPrepSnapshot().preparation!!
        val alt = p.materials.single { it.code == "ALT" }
        assertTrue(alt.isAdded)
        assertFalse(alt.excluded)
        assertEquals(0, BigDecimal("8").compareTo(alt.remaining))
    }

    @Test
    fun `an unknown stage is kept, not crashed on`() {
        assertEquals(PrepStage.Unknown, PrepStage.fromWire("Teleporting"))
    }

    @Test
    fun `the original capture intent is read back from the saved bytes`() {
        val intent = CaptureIntent.fromPayload(ContractFixtures.text("general_capture_request.json"))!!
        assertEquals("TAG-RESIN", intent.code)
        assertEquals(0, BigDecimal("10").compareTo(intent.quantity))
        assertNull(CaptureIntent.fromPayload("{\"action\":\"confirm\",\"targetId\":\"P\"}"))
    }
}
```

- [ ] **Step 2: Run it and see it fail** (unresolved references).

- [ ] **Step 3: Implement `Preparation.kt`**

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import com.google.gson.JsonParser
import java.math.BigDecimal

enum class PrepStage {
    Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, ReadyForNextMix,
    ReadyForProduction, Producing, Completed, Cancelled, Unknown;

    companion object {
        fun fromWire(value: String): PrepStage = entries.firstOrNull { it.name == value } ?: Unknown
    }
}

/** One effective collection row (contract §8). Quantities are exact; compare with compareTo. */
data class PrepMaterial(
    val code: String,
    val name: String,
    val unit: String,
    val perMix: BigDecimal,
    val required: BigDecimal,
    val collected: BigDecimal,
    val excluded: Boolean,
    val originalRequired: BigDecimal?,
) {
    /** The server's own formula (Rev2MixingModels.cs:20). */
    val remaining: BigDecimal get() = (required - collected).max(BigDecimal.ZERO)
    val isComplete: Boolean get() = remaining.signum() == 0
    /** Added by an operator decision: not on the original BOM. */
    val isAdded: Boolean get() = originalRequired?.signum() == 0 && perMix.signum() == 0
    fun fits(quantity: BigDecimal): Boolean = quantity.signum() > 0 && quantity <= remaining
}

data class Preparation(
    val id: String,
    val jobId: String,
    val mixCount: Int,
    val mixed: Int,
    val produced: Int,
    val stage: PrepStage,
    val stageRaw: String,
    val collectionRevision: Int,
    val startedAtUtc: String?,
    val startedBy: String?,
    val confirmedAtUtc: String?,
    val materials: List<PrepMaterial>,
) {
    val allIncludedCollected: Boolean get() = materials.filterNot { it.excluded }.all { it.isComplete }
}

data class ChoicePrompt(val code: String, val description: String, val note: String)

data class CollectionExceptionRecord(
    val id: String,
    val timestampUtc: String,
    val operatorId: String,
    val device: String,
    val messageId: String,
    val jobId: String,
    val batchId: String?,
    val kind: String,
    val details: String,
    val reason: String,
    val reviewedAtUtc: String?,
    val reviewedBy: String?,
    val reviewReason: String?,
)

data class PrepSnapshot(
    val lookup: JobLookupSnapshot,
    val preparation: Preparation?,
    val requiredChoices: List<ChoicePrompt>,
    /** The catalog revision to echo in `prepare` (top-level `data.exceptionListRevision`). */
    val exceptionListRevision: Int?,
    val collectionExceptions: List<CollectionExceptionRecord>,
    val commandExceptions: List<CollectionExceptionRecord>,
    val resultTargetId: String?,
    val resultMessage: String,
)

/** What an outboxed `capture` asked for, read back from its exact saved JSON (for a replacement). */
data class CaptureIntent(val code: String, val quantity: BigDecimal?) {
    companion object {
        fun fromPayload(json: String): CaptureIntent? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj["action"]?.asString != "capture") return null
            val code = obj["code"]?.asString?.takeIf { it.isNotBlank() } ?: return null
            CaptureIntent(code, obj["quantity"]?.takeIf { it.isJsonPrimitive }?.asBigDecimal)
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Implement `Rev2Mapping.kt`**

Move the private `toDomain` functions from `JobLookupUseCase.kt` here as `internal fun Rev2Snapshot.toJobLookupSnapshot()` and `internal fun Rev2MixProgress.toDomain()`. Delete them from `JobLookupUseCase.kt` and update its two call sites. Then add:

```kotlin
internal fun Rev2Snapshot.toPrepSnapshot(): PrepSnapshot = PrepSnapshot(
    lookup = toJobLookupSnapshot(),
    preparation = preparation?.let { p ->
        Preparation(
            id = p.id, jobId = p.jobId, mixCount = p.mixCount, mixed = p.mixed, produced = p.produced,
            stage = PrepStage.fromWire(p.stage), stageRaw = p.stage, collectionRevision = p.collectionRevision,
            startedAtUtc = p.startedAtUtc, startedBy = p.startedBy, confirmedAtUtc = p.confirmedAtUtc,
            materials = p.materials.map {
                PrepMaterial(it.code, it.name, it.unit, it.perMix, it.required, it.collected, it.excluded, it.originalRequired)
            },
        )
    },
    requiredChoices = requiredIngredientChoices.map { ChoicePrompt(it.code, it.description, it.note) },
    exceptionListRevision = exceptionListRevision,
    collectionExceptions = collectionExceptions.map { it.toDomain() },
    commandExceptions = commandExceptions.map { it.toDomain() },
    resultTargetId = result?.targetId,
    resultMessage = result?.message.orEmpty(),
)

private fun Rev2CollectionException.toDomain() = CollectionExceptionRecord(
    id, timestampUtc, operatorId, device, messageId, jobId, batchId, kind, details, reason,
    reviewedAtUtc, reviewedBy, reviewReason,
)
```

- [ ] **Step 5: Run the test, then the whole suite.** `JobLookupUseCaseTest` must still pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(domain): preparation model with exact effective rows, stages and exception records"
```

---

### Task 4: A multi-subscriber server-hint stream

**Acceptance:** PUSH-02 (client side). This is needed because `setServerPushHandler` is single-slot and `JobLookupViewModel` already owns it (`JobLookupViewModel.kt:83`).

**Files:**
- Modify: `domain/repository/MqttRepository.kt`, `data/mqtt/MqttRepositoryImpl.kt` (the push branch, around line 766)
- Modify: `ui/joblookup/JobLookupViewModel.kt` (collect `serverHints` instead of setting the handler)
- Test: `data/mqtt/Rev2TransportTest.kt` (add a case); `ui/joblookup/JobLookupViewModelTest.kt` (stub `serverHints`)

**Interfaces:**
- Produces: `data class ServerHint(val mode: String, val reason: String)` (in `domain/repository/MqttRepository.kt`).
- Produces: `val serverHints: SharedFlow<ServerHint>` on `MqttRepository`. The implementation is a `MutableSharedFlow(extraBufferCapacity = 16)`, emitted with `tryEmit` after the existing duplicate-push suppression. `setServerPushHandler` stays, and is now deprecated.

- [ ] **Step 1: Write the failing transport test**

```kotlin
@Test
fun `a push is delivered to every hint subscriber and never touches a pending command`() = runTest {
    val seen = mutableListOf<ServerHint>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.serverHints.toList(seen) }
    repo.handleIncomingResponse(
        "PPNAM/station_2/$device/res/active_job_cards_invalidated",
        ContractFixtures.bytes("active_job_cards_invalidated.json"),
    )
    assertEquals(listOf(ServerHint("General", "preparation_created")), seen)
    job.cancel()
}
```

Follow the fixture setup that already exists in `Rev2TransportTest`. The fixture's `reason` is `preparation_created`. The existing test `a hint arriving mid-command does not resolve it` in `CommandTransportTest` already covers the outbox side.

- [ ] **Step 2: Run it; it fails to compile.**

- [ ] **Step 3: Implement**

Interface:

```kotlin
/** A rev2.1 `active_job_cards_invalidated` push: a reason to re-read, never data, never a command result. */
data class ServerHint(val mode: String, val reason: String)

// in MqttRepository:
/** Every server push, for every screen that shows server state. Replaces [setServerPushHandler]. */
val serverHints: SharedFlow<ServerHint>
```

Implementation: `private val _serverHints = MutableSharedFlow<ServerHint>(extraBufferCapacity = 16)` and `override val serverHints = _serverHints.asSharedFlow()`. In the push branch, before the handler, add `_serverHints.tryEmit(ServerHint(envelope.mode, envelope.reason))`. Remove the "no handler registered" warning: a subscriber-less hint is normal now.

`JobLookupViewModel.init`: replace `mqttRepository.setServerPushHandler { … }` with:

```kotlin
viewModelScope.launch {
    mqttRepository.serverHints.collect { hint ->
        if (hint.mode.isNotBlank() && !hint.mode.equals("General", ignoreCase = true)) return@collect
        if (sessionHolder.session.value == null) return@collect
        val target = viewedJobCard
        if (target != null) loadDetail(target) else refreshList()
    }
}
```

In `JobLookupViewModelTest` (and any other VM test that mocks `MqttRepository` and builds `JobLookupViewModel`), add `whenever(repo.serverHints).thenReturn(MutableSharedFlow())`. Migrate any test that captured the push handler so it emits into that flow instead.

- [ ] **Step 4: Run the whole suite; it passes.**

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): serverHints flow so several screens can refresh on a push"
```

---

### Task 5: `GeneralCollectionUseCase`

**Acceptance:** GEN-02, GEN-04, GEN-06, GEN-07, GEN-08, GEN-09, REC-01.

**Files:**
- Create: `domain/usecase/GeneralCollectionUseCase.kt`
- Test: `domain/usecase/GeneralCollectionUseCaseTest.kt`

**Interfaces:**
- Consumes: `MqttRepository.request/sendCommand/retryCommand`, `toPrepSnapshot()`.
- Produces:

```kotlin
sealed interface ReadResult {
    data class Loaded(val snapshot: PrepSnapshot) : ReadResult
    data class Failed(val message: String, val snapshot: PrepSnapshot?, val retryable: Boolean) : ReadResult
}

sealed interface CollectionResult {
    /** Station 2 did it; [snapshot] is the fresh saved state. */
    data class Done(val snapshot: PrepSnapshot) : CollectionResult
    /** Station 2 definitely did not do it (rev2_rejected, receipt_sealed, …). Show [message] and [snapshot]'s commandExceptions. */
    data class Rejected(val error: ErrorCode?, val message: String, val snapshot: PrepSnapshot?) : CollectionResult
    /** Outcome unknown: the command is in the outbox. Never send a new one for this intent. */
    data class Unresolved(val command: PendingCommand, val reason: UnresolvedReason, val message: String) : CollectionResult
    /** Nothing was published (not connected, or the outbox could not save). A new tap is a new intent. */
    data class NotSent(val message: String) : CollectionResult
    /** A retry found the command already settled elsewhere. Re-read. */
    data class AlreadySettled(val messageId: String) : CollectionResult
}

class GeneralCollectionUseCase {
    suspend fun read(targetId: String): ReadResult
    suspend fun prepare(jobCard: String, mixCount: Int, exceptionListRevision: Int?,
                        answers: List<Rev2ChoiceAnswer>, reason: String?): CollectionResult
    suspend fun capture(prepId: String, code: String, quantity: BigDecimal?): CollectionResult
    suspend fun confirm(prepId: String): CollectionResult
    suspend fun retry(command: PendingCommand): CollectionResult
}
```

- [ ] **Step 1: Write the failing tests.** Use `mock<MqttRepository>()`, in the same style as `CommandRecoveryUseCaseTest`. Snapshots come from the fixtures (`fixture("general_capture")`). One `@Test` per row:

| Test name | Stub | Expect |
|---|---|---|
| `capture sends one command with the code and an omitted quantity` | `sendCommand` → `Settled(Accepted(capture))` | `Done`; the captured payload is `Rev2CaptureRequest("PREP_1","TAG-1",null)`; requestType `rev2_general_requested`; responseType `rev2_general_result`; `request` never called |
| `a fraction is normalised before sending` | `capture("P", "T", BigDecimal("2.500"))` | the payload's `quantity.toString() == "2.5"` |
| `rev2_rejected keeps the snapshot and the server message` | `Settled(Rejected(body, REV2_REJECTED, "This material is not outstanding…"))` | `Rejected` with the message and a snapshot |
| `receipt_sealed is a definite rejection` | `Settled(Rejected(body, RECEIPT_SEALED, msg))` | `Rejected(error = RECEIPT_SEALED)` |
| `no reply is unresolved with the saved command` | `Unresolved(cmd, RetryIdentical, null, null)` | `Unresolved(cmd, RetryIdentical, "Station 2 has not confirmed this yet. Don't repeat it — retry.")` |
| `not connected sends nothing` | `Settled(NoResponse(NotConnected))` | `NotSent("Not connected — nothing was sent")` |
| `a failed save sends nothing` | `NotSaved(IOException())` | `NotSent("The scanner couldn't save this, so nothing was sent. Try again.")` |
| `retry republishes via retryCommand only` | `retryCommand(cmd, …)` → `Settled(Accepted)` | `Done`; `sendCommand` never called |
| `retry of a settled command is AlreadySettled` | `NoLongerPending("m")` | `AlreadySettled("m")` |
| `prepare sends the explicit answers and revision` | — | the payload equals `Rev2PrepareRequest("1",4,1,listOf(Rev2ChoiceAnswer("OPTIONAL",false)),"r")` |
| `prepare trims a blank reason to null` | `reason = "   "` | `payload.reason == null` |
| `read of a PREP returns its snapshot` | `request` → `Accepted(fixture("general_read_preparation"))` | `Loaded`; the preparation id matches |
| `read of an unknown target fails without a crash` | `Rejected(body, REV2_REJECTED, "The selected target…")` | `Failed(message, snapshot, retryable=false)` |

- [ ] **Step 2: Run them and see them fail.**

- [ ] **Step 3: Implement**

```kotlin
@Singleton
class GeneralCollectionUseCase @Inject constructor(
    private val mqtt: MqttRepository,
) {
    suspend fun read(targetId: String): ReadResult =
        when (val o = mqtt.request(REQUEST, RESPONSE, Rev2GeneralRequest(action = "read", targetId = targetId.trim()),
            Rev2Snapshot::class.java)) {
            is MqttOutcome.Accepted -> ReadResult.Loaded(o.body.toPrepSnapshot())
            is MqttOutcome.Rejected -> ReadResult.Failed(o.operatorMessage ?: "Station 2 rejected the read",
                o.body?.toPrepSnapshot(), retryable = false)
            is MqttOutcome.NoResponse -> ReadResult.Failed(o.kind.message(), null,
                retryable = o.kind == FailureKind.Timeout || o.kind == FailureKind.NotConnected)
        }

    suspend fun prepare(jobCard: String, mixCount: Int, exceptionListRevision: Int?,
                        answers: List<Rev2ChoiceAnswer>, reason: String?): CollectionResult =
        send(Rev2PrepareRequest(jobCard, mixCount, exceptionListRevision, answers,
            reason?.trim()?.takeIf { it.isNotEmpty() }?.take(PREPARE_REASON_MAX)))

    suspend fun capture(prepId: String, code: String, quantity: BigDecimal?): CollectionResult {
        require(code.isNotBlank()) { "blank source code" }
        require(quantity == null || quantity.signum() > 0) { "quantity must be positive" }
        return send(Rev2CaptureRequest(prepId, code.trim(), quantity?.let(WireDecimal::of)))
    }

    suspend fun confirm(prepId: String): CollectionResult = send(Rev2ConfirmRequest(prepId))

    suspend fun retry(command: PendingCommand): CollectionResult =
        mqtt.retryCommand(command, Rev2Snapshot::class.java).toCollectionResult()

    private suspend fun send(payload: Any): CollectionResult =
        mqtt.sendCommand(REQUEST, RESPONSE, payload, Rev2Snapshot::class.java).toCollectionResult()

    companion object {
        const val REQUEST = "rev2_general_requested"
        const val RESPONSE = "rev2_general_result"
        /** Spec open question Q5. */
        const val PREPARE_REASON_MAX = 1000
    }
}

internal fun CommandOutcome<Rev2Snapshot>.toCollectionResult(): CollectionResult = when (this) {
    is CommandOutcome.Settled -> when (val o = outcome) {
        is MqttOutcome.Accepted -> CollectionResult.Done(o.body.toPrepSnapshot())
        is MqttOutcome.Rejected -> CollectionResult.Rejected(o.error, o.operatorMessage ?: rejectedText(o.error),
            o.body?.toPrepSnapshot())
        is MqttOutcome.NoResponse -> CollectionResult.NotSent("Not connected — nothing was sent")
    }
    is CommandOutcome.Unresolved -> CollectionResult.Unresolved(command, reason, unresolvedText(reason))
    is CommandOutcome.NotSaved -> CollectionResult.NotSent("The scanner couldn't save this, so nothing was sent. Try again.")
    is CommandOutcome.NoLongerPending -> CollectionResult.AlreadySettled(messageId)
}

private fun rejectedText(error: ErrorCode?): String = when (error) {
    ErrorCode.INVALID_ENVELOPE, ErrorCode.PASSWORD_FIELD_FORBIDDEN ->
        "Station 2 rejected the message format (${error.raw}). Report this."
    ErrorCode.ACTION_NOT_ALLOWED -> "Station 2 doesn't allow this here."
    ErrorCode.RECEIPT_SEALED -> "Station 2 never ran this, and it can no longer run."
    ErrorCode.CLIENT_UPGRADE_REQUIRED -> "This app needs updating before it can talk to Station 2"
    else -> "Station 2 rejected the request"
}

private fun unresolvedText(reason: UnresolvedReason): String = when (reason) {
    UnresolvedReason.RetryIdentical -> "Station 2 has not confirmed this yet. Don't repeat it — retry."
    UnresolvedReason.LoginThenRecover -> "Your session ended. Sign in again; the scanner will ask Station 2 what happened."
    UnresolvedReason.Recover -> "Station 2 couldn't match this command. Checking what happened…"
    UnresolvedReason.ManagerReconcile -> "A manager must reconcile this at the station before you scan again."
}
```

`FailureKind.message()` is the existing `internal` extension in `AuthUseCase.kt:107`. `ErrorCode.raw` is the value-class field (`MqttVocabulary.kt:12`).

- [ ] **Step 4: Run the tests until they pass, then run the whole suite.**

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(collection): prepare/capture/confirm/retry use case over the durable outbox"
```

---

### Task 6: Validating the prepare form (pure)

**Acceptance:** GEN-02, GEN-03, GEN-04, GEN-05.

**Files:**
- Create: `domain/usecase/PrepareForm.kt`
- Test: `domain/usecase/PrepareFormTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class PrepareForm(
    val jobCard: String,
    val available: Int,
    val prompts: List<ChoicePrompt>,
    /** null = unanswered. Never pre-filled (contract §7: explicit). */
    val answers: Map<String, Boolean?>,
    val mixCountText: String,
    val reason: String,
    val exceptionListRevision: Int?,
    /** Non-excluded job material codes, to block an all-excluded recipe before sending. */
    val materialCodes: List<String>,
) {
    val mixCount: Int?
    val problems: List<String>   // empty = may send
    fun answersForWire(): List<Rev2ChoiceAnswer>   // in prompt order; only valid when problems is empty
    /** Keep answers for codes still prompted; drop the rest (after a re-read). */
    fun rebasedOn(snapshot: PrepSnapshot): PrepareForm
    companion object { fun from(snapshot: PrepSnapshot): PrepareForm? }  // null without job detail
}
```

- [ ] **Step 1: Write the failing tests:**
  - `N must be a whole number from 1 to available`: inputs `"0"`, `"57"` (with available 56), `"4.0"`, `""` and `"abc"` each give a problem; `"4"` gives none.
  - `every prompted code must be answered explicitly`: an unanswered `OPTIONAL` gives the problem "Choose Include or Exclude for OPTIONAL".
  - `excluding every material is refused before sending`.
  - `prompts without a revision are refused` gives "Reload the job — Station 2 sent no ingredient list revision".
  - `no prompts sends an empty list`: `answersForWire()` is empty and there are no problems (GEN-05).
  - `nothing to prepare when available is 0`.
  - `reason over 1000 characters is a problem`.
  - `rebase keeps still-prompted answers, drops vanished codes, clamps nothing silently`: if the new available is smaller than the typed N, that becomes a problem; the text is not rewritten.
  - `from builds the form from general_lookup`: available 60, one prompt `OPTIONAL`, its answer null.

- [ ] **Step 2: Fail. Step 3: Implement. Step 4: Pass.** The rule for all-excluded: it is a problem if every code in `materialCodes` has an answer of `false`.

- [ ] **Step 5: Commit** `feat(prepare): explicit choice and mix-count validation`.

---

### Task 7: The Prepare screen, routes and job-detail entry points

**Acceptance:** GEN-02, GEN-03, GEN-04, GEN-05, GEN-13.

**Files:**
- Create: `ui/preparation/PrepareViewModel.kt`, `ui/preparation/PrepareScreen.kt`
- Modify: `navigation/NavRoutes.kt`, `navigation/AppNavGraph.kt`
- Modify: `ui/joblookup/JobDetailScreen.kt`, `ui/joblookup/JobLookupViewModel.kt` (pending prepare for the viewed JC)
- Test: `ui/preparation/PrepareViewModelTest.kt`

**Interfaces:**
- Routes: `NavRoutes.PREPARE = "jobs/prepare/{jobCard}"`, `fun prepare(jobCard)`; `NavRoutes.PREP = "jobs/prep/{prepId}"`, `fun prep(prepId)`.
- `PrepareViewModel(useCase: GeneralCollectionUseCase, outbox: CommandOutbox, sessionHolder, mqttRepository, savedStateHandle)`:
  - `uiState: StateFlow<PrepareUiState>`, holding `form`, `loading`, `sending`, `message`, `pending: PendingCommand?` and `activePreparations`;
  - `navigateToPrep: Flow<String>`;
  - `onMixCount(text)`, `onAnswer(code, include)`, `onReason(text)`, `reload()`, `submit()`, `retryPending()`.

- [ ] **Step 1: Write the failing VM tests** (`StandardTestDispatcher` with `Dispatchers.setMain`, as `JobLookupViewModelTest` does):
  - `opening reads the job fresh even if detail was on screen`: `useCase.read("1")` is called exactly once on init.
  - `submit is impossible until every code is answered`: `submit()` with OPTIONAL unanswered never calls `useCase.prepare`.
  - `submit sends the explicit answers and the read's revision`: after `onAnswer("OPTIONAL", false)` and `onMixCount("4")`, it calls `prepare("1", 4, 1, [OPTIONAL=false], null)`.
  - `success navigates to the new PREP`: `Done(fixture general_prepare)` makes `navigateToPrep` emit `PREP_440f…`.
  - `a rejection re-applies the snapshot and keeps the answers`: `Rejected(REV2_REJECTED, "Choose a positive whole number up to 52…", snapshot with available 52)` gives `form.available == 52`; the answers are kept; a message is shown; no navigation.
  - `an unresolved prepare is shown with Retry, and Retry republishes it`: `Unresolved(cmd)` sets `pending == cmd`; `retryPending()` calls `useCase.retry(cmd)` and never `prepare`.
  - `opening with a same-session unresolved prepare for this JC offers Retry instead of a new form submit`: the outbox holds an Unresolved `prepare` with `targetId "1"` and the current session id, so `pending` is set and submit is disabled.
  - `double tap sends once`: two quick `submit()` calls give one `prepare`.

- [ ] **Step 2: Fail. Step 3: Implement the VM.** Hold a `sending` flag so a second tap is ignored. Re-read when the connection returns to CONNECTED. On a `serverHints` push in General mode, re-read *only* while not sending, and keep the answers through `rebasedOn`.

- [ ] **Step 4: Implement `PrepareScreen`** following spec §8, "Prepare screen":
  - Use `AppScaffold` with the title "Prepare {jobCard}".
  - The counts line uses `MixProgress.countsLine()` (`JobFormat.kt:33`).
  - Active-PREP warning rows call `onOpenPrep(id)`.
  - Mix-count field: `KeyboardType.Number`, plus +/− buttons.
  - One row per prompt, with a `SingleChoiceSegmentedButtonRow` for Include/Exclude and **no initial selection**.
  - A reason `OutlinedTextField` with a `maxLength` counter.
  - The problems list shows in muted text above the button.
  - The **Prepare N mixes** button is enabled when `problems.isEmpty() && !sending && pending == null`. It opens a confirm `AlertDialog` that lists N and the excluded codes.
  - Pending banner: "Station 2 has not confirmed this prepare. Don't prepare again — Retry." with a Retry button.

- [ ] **Step 5: Wire navigation.** In `AppNavGraph` inside `navigation(route = JOBS)`, add a `composable(NavRoutes.PREPARE)` that reads `jobCard` and passes `hiltViewModel<PrepareViewModel>()`, its own instance. On `navigateToPrep(id)`, call `navController.navigate(NavRoutes.prep(id)) { popUpTo(NavRoutes.PREPARE) { inclusive = true } }`. Add a placeholder `composable(NavRoutes.PREP)` that Task 9 fills in.

- [ ] **Step 6: Job detail entry points** (`JobDetailScreen.kt`):
  - Add `onPrepare: (String) -> Unit` and `onOpenPrep: (String) -> Unit` parameters.
  - The "Prepare mixes" `Button` is enabled when `!detail.closed && (detail.mixProgress?.availableToPrepareMixes ?: 0) > 0 && state.pendingPrepare == null`.
  - Active-preparation and history `ListItem`s get `Modifier.clickable { onOpenPrep(prep.id) }`.
  - In `JobLookupViewModel`, expose `pendingPrepare`: the outbox entry with `action == "prepare"`, `targetId == viewedJobCard` and status Unresolved. It needs `CommandOutbox` injected; Hilt already binds it. Show it as a banner with the text "A prepare for this job is waiting for Station 2" plus an "Open" action to the Prepare screen, which offers Retry.

- [ ] **Step 7: Run the whole suite, then the build.**

- [ ] **Step 8: Commit** `feat(prepare): prepare screen with explicit choices, job-detail entry points`.

---

### Task 8: `PreparationViewModel`: open, stage, scan, capture, confirm and unresolved handling

**Acceptance:** GEN-06, GEN-07, GEN-08, GEN-09, GEN-12, REC-01, PUSH-02.

**Files:**
- Create: `ui/preparation/PreparationViewModel.kt`
- Test: `ui/preparation/PreparationViewModelTest.kt`

**Interfaces:**
- Consumes:
  - `GeneralCollectionUseCase`;
  - `CommandRecoveryUseCase`;
  - `PendingCommandCoordinator` (`pending`, `notices`, `dismiss`);
  - `MqttRepository` (`connectionState`, `serverHints`, `connectionStatusIn`);
  - `ScanEventBus`;
  - `OperatorSessionHolder`;
  - `AuthUseCase`;
  - `SavedStateHandle["prepId"]`.
- Produces:

```kotlin
sealed interface Staged {
    val code: String
    data class WholeBag(override val code: String) : Staged
    data class PartBag(override val code: String, val materialCode: String, val quantityText: String) : Staged
}

data class PreparationUiState(
    val prepId: String,
    val snapshot: PrepSnapshot? = null,
    val loading: Boolean = false,
    val readError: String? = null,
    val inFlight: Boolean = false,
    val staged: Staged? = null,
    /** Unresolved/ManagerReconcile outbox entries whose targetId is this PREP. */
    val blocking: List<PendingCommand> = emptyList(),
    val message: OutcomeMessage? = null,
    val offer: ReplacementOffer? = null,   // Task 10
) {
    val stage: PrepStage? get() = snapshot?.preparation?.stage
    val canScan: Boolean get() = stage == PrepStage.Collecting && !inFlight && blocking.isEmpty()
    val canConfirm: Boolean get() = stage == PrepStage.AwaitingConfirmation &&
        snapshot?.preparation?.allIncludedCollected == true && !inFlight && blocking.isEmpty()
}

sealed interface OutcomeMessage {
    data class Recorded(val text: String, val warning: String? = null) : OutcomeMessage
    data class Rejected(val text: String, val records: List<CollectionExceptionRecord>) : OutcomeMessage
    data class Info(val text: String) : OutcomeMessage
}
```

- Functions:
  - `setScreenActive(Boolean)` and `refresh()`;
  - `onScan(code)` (from the bus, or manual entry);
  - `stageWholeBag()`, `stagePart(materialCode)`, `onPartQuantity(text)`, `clearStaged()`;
  - `recordStaged()`;
  - `confirm()`;
  - `retry(command)`.

**Rules to implement** (each has a test, below):

1. **Sequence.** Every `read`/`capture`/`confirm`/`retry` takes `val seq = ++issued` before it suspends. A result is applied only `if (seq > applied) { applied = seq; … }`. This applies to snapshots; outcome messages for this request's own result are still shown.
2. **`blocking`** is derived from `coordinator.pending` (the outbox flow), filtered on `targetId == prepId && (status == ManagerReconcile || status == Unresolved)`. It is derived from the outbox only, never from a read.
3. **On open,** read the PREP. For each blocking entry with `sessionId == current session && status == Unresolved`, call `retry(entry)` once (the Phase 1 Home text says "reopen the job to retry it"). An entry from an earlier session is left to the coordinator.
4. **On each transition back to CONNECTED,** repeat rule 3's retry, then read.
5. **On a General `serverHints` push,** read. It must never touch `blocking`.
6. **`onScan(code)`:**
   - trim it; ignore it if blank;
   - if `UserTagPolicy.isUserTag`, show `Info("That is a user badge, not a pallet")`;
   - if `!canScan`, show `Info` explaining why (in flight, blocked, or not Collecting);
   - otherwise set `staged = WholeBag(code)`, keeping the mode if the same code is already staged as part.
   - Scanning never sends anything.
7. **`recordStaged()`:**
   - Ignore it if `inFlight || !canScan`.
   - For `PartBag`, `QuantityInput.parse(quantityText)` must be non-null and `material.fits(q)`. Otherwise show `Info("Enter up to {remaining} {unit}")` and do not send.
   - Set `inFlight` and call `useCase.capture(prepId, code, q)`.
   - `Done`: apply the snapshot. Diff the old and new `collected` per code to get the recorded row and amount, then show `Recorded("Recorded 10 kg Resin — 30 kg to go")`. If it is a part bag and the recorded row ≠ the picked material, add `warning = "Station 2 recorded this against {name}, not {picked}"`. Keep `staged` so the next bag from the same pallet is one tap away; reset `PartBag` to `WholeBag`.
   - `Rejected`: apply the snapshot if present, then show `Rejected(message, snapshot.commandExceptions)`. If `error == RECEIPT_SEALED`, show the replacement offer instead (Task 10).
   - `Unresolved`: no local change; `blocking` updates from the outbox. If `reason == Recover`, call `recovery.recover(command)` at once and turn its `RecoveryResult` into a message or an offer.
   - `NotSent`: show `Info(message)`; `staged` stays.
   - `AlreadySettled`: read.
8. **`confirm()`** is the same shape, using `useCase.confirm`, and is guarded by `canConfirm`.
9. **Scans reach the VM only while `setScreenActive(true)`,** the same pattern as `JobLookupViewModel.setLookupScreenActive`.

- [ ] **Step 1: Write the failing tests.** Mock the use case, the recovery use case, the coordinator flows and the repository. Use an `InMemoryCommandOutbox` behind a coordinator built from mocks, or stub `coordinator.pending` with a `MutableStateFlow`.
  - `opening reads the exact PREP id` (GEN-06): `read("PREP_440f…")`; `prepare` never called.
  - `a scan stages and never sends`.
  - `record sends one capture with an omitted quantity, and the same code stays staged`.
  - `two taps while in flight send one capture`.
  - `a part bag above the picked row's remaining is refused locally`: RESIN remaining 30, `"30.001"`, so no send.
  - `a part bag equal to remaining is sent exactly`: `"30"` gives `capture(prepId, code, BigDecimal("30"))`.
  - `a part bag recorded against another material warns`.
  - `a rejected capture shows the server's exception record` (GEN-08): `Rejected` with a snapshot whose `commandExceptions` holds one record gives a `Rejected` message carrying that record.
  - `an unresolved capture blocks the next scan`: after `Unresolved`, the outbox flow emits the entry, so `canScan == false` and `onScan` gives an `Info` and no capture.
  - `a read after an unresolved capture keeps it blocked`: a subsequent `Loaded` read showing collected increased leaves `blocking` unchanged and `canScan` false.
  - `Retry republishes the identical command`: `retry(cmd)` calls `useCase.retry(cmd)` and never `capture`.
  - `reconnect retries a same-session unresolved capture once`.
  - `an earlier-session unresolved capture is not retried here`.
  - `a manager-reconcile entry blocks scanning and confirm`.
  - `message_id_conflict triggers recovery immediately`.
  - `confirm is offered only in AwaitingConfirmation with every included row complete` (GEN-09): use the `final_capture` fixture, so `canConfirm` is true; with `general_capture`, it is false.
  - `an older read arriving after confirm does not regress the stage`: start read A (seq 1), held with a `CompletableDeferred`; confirm (seq 2) completes with ReadyForMixer; then complete A with `final_capture` (AwaitingConfirmation). The state is still ReadyForMixer.
  - `a hint re-reads and leaves pending commands alone` (PUSH-02).
  - `a badge scan is refused`.

- [ ] **Step 2: Fail. Step 3: Implement. Step 4: Pass, then run the whole suite.**

- [ ] **Step 5: Commit** `feat(collection): preparation screen state — scan, capture, confirm, retry, blocking`.

---

### Task 9: The PREP screen UI, the exception list, routes and `PREP_` lookup

**Acceptance:** GEN-06, GEN-08, GEN-09, GEN-11 (display); the EXC-10 display groundwork.

**Files:**
- Create: `ui/preparation/PreparationScreen.kt`, `ui/preparation/PreparationFormat.kt`
- Modify: `ui/util/TimeFormat.kt` (+ `formatStationDateTime`)
- Modify: `navigation/AppNavGraph.kt` (the real `PREP` composable)
- Modify: `domain/usecase/JobLookupUseCase.kt`, `ui/joblookup/JobLookupViewModel.kt`, `JobLookupScreen.kt` (`PREP_` input)
- Test: `ui/preparation/PreparationFormatTest.kt`, `ui/util/TimeFormatTest.kt`, `ui/joblookup/JobLookupViewModelTest.kt`

**Interfaces:**
- `fun formatStationDateTime(raw: String, zone: ZoneId = STATION_ZONE): String` with `val STATION_ZONE: ZoneId = ZoneId.of("Africa/Johannesburg")` (Q6). It always formats as `d MMM yyyy HH:mm`, and falls back to the raw string when it cannot parse.
- `PrepMaterial.progressLine()`:
  - "10 of 40 kg · 30 kg to go";
  - "excluded — original 8 kg";
  - "added by operator · 0 of 8 kg".
- `CollectionExceptionRecord.lines()`: "1 Oct 2026 15:40 · operator 65cf… · Ingredient omitted", then the details, then the reason (if any), then "Open" or "Reviewed by X on …".
- Quantities are formatted with `BigDecimal.stripTrailingZeros().toPlainString()`. Do not route them through the Double-based `formatQuantity`.
- `JobLookupUseCase.lookup` returns a new `JobLookupResult.OpenPreparation(prepId)` when the trimmed input starts with `PREP_`. The VM sends it on a new `navigateToPrep` channel.

- [ ] **Step 1: Write the failing tests:**
  - `formatStationDateTime` renders `2026-10-01T13:40:59.547851Z` as `1 Oct 2026 15:40` (SAST, UTC+2), regardless of the device zone.
  - `progressLine` cases for normal, excluded, added and complete rows, using exact decimals such as `0.25`.
  - `lines()` for an open record and a reviewed record. Use `general_read_reviewed_response.json` for the reviewed one.
  - `lookup("PREP_440f…")` gives `OpenPreparation` and sends no MQTT request. `lookup(" 12a")` still gives the digits error.

- [ ] **Step 2: Fail. Step 3: Implement.**

- [ ] **Step 4: Implement `PreparationScreen`** following spec §8, "PREP screen":
  - `AppScaffold`, with the title set to the PREP id shortened to `PREP_…last 6` and the full id in the header.
  - Lifecycle `ON_RESUME` calls `setScreenActive(true)` and `refresh()`; `ON_PAUSE` calls `setScreenActive(false)`.
  - The stage chip.
  - Banners: each blocking entry, with Retry when it is from the same session and Unresolved, otherwise the manager text; the outcome message, with exception lines for `Rejected`.
  - Material rows. While Collecting, a row tap calls `stagePart(code)`.
  - Scan card: "Scan a pallet tag or label". After a scan it shows the code and two buttons: **Record bag** and **Record part bag**. The second opens a bottom sheet with a quantity field (decimal keyboard) and a "Remaining: X unit" chip that fills in the exact remaining. There is also a manual code `OutlinedTextField` with an "Use code" button.
  - **Confirm collection**, shown only when `canConfirm`, behind a confirm dialog.
  - For ReadyForMixer and later stages: "Ingredients confirmed — next: scan a permitted mixer (coming in a later release)". Completed or Cancelled shows as history.
  - The exceptions list.

- [ ] **Step 5: Route.** `composable(NavRoutes.PREP)` → `PreparationScreen(prepId, onBack = { navController.popBackStack() })` with `hiltViewModel<PreparationViewModel>()`. The `prepId` comes from `SavedStateHandle`. Wire the job detail's `onOpenPrep` and `onPrepare`, and the lookup's `navigateToPrep`.

- [ ] **Step 6: Run the whole suite and the build.**

- [ ] **Step 7: Commit** `feat(collection): preparation screen, SAST exception records, open PREP by id`.

---

### Task 10: The not-executed "confirm replacement" prompt

**Acceptance:** REC-05 (client: "one newly confirmed intent may execute"); contract §9, "Require operator confirmation before a replacement physical action".

**Files:**
- Create: `domain/usecase/ReplacementOffer.kt`
- Modify: `ui/preparation/PreparationViewModel.kt`, `PreparationScreen.kt`
- Modify: `ui/home/RecoveryNoticeText.kt`, `ui/home/HomeScreen.kt`, `navigation/AppNavGraph.kt` (Home → PREP/Prepare navigation)
- Test: `domain/usecase/ReplacementOfferTest.kt`, `ui/preparation/PreparationViewModelTest.kt`, `ui/home/RecoveryNoticeTextTest.kt`

**Interfaces:**

```kotlin
sealed interface ReplacementOffer {
    val notice: RecoveryResult?            // null when it came from a direct receipt_sealed reply
    data class Capture(override val notice: RecoveryResult?, val prepId: String, val intent: CaptureIntent) : ReplacementOffer
    data class Confirm(override val notice: RecoveryResult?, val prepId: String) : ReplacementOffer
    companion object {
        /** Only NotExecuted capture/confirm become offers. A prepare never does (spec §6). */
        fun from(result: RecoveryResult): ReplacementOffer?
        fun fromSealed(command: PendingCommand?): ReplacementOffer?
    }
}

/** Home: where a NotExecuted notice's "Open" goes. */
sealed interface NoticeTarget { data class Prep(val id: String) : NoticeTarget; data class Job(val jobCard: String) : NoticeTarget }
fun RecoveryResult.openTarget(): NoticeTarget?   // NotExecuted only: capture/confirm → Prep; prepare → Job
```

- [ ] **Step 1: Write the failing tests:**
  - `a not_executed capture becomes a capture offer with the original code and quantity`. Build a `Resolved(cmd(payload = general_delayed_sealed_request.json), NotExecuted, …)`; the offer's intent is `TAG-RESIN` / `10`.
  - `committed and rejected never become offers`.
  - `a not_executed prepare is not an offer but opens its job` (`openTarget() == Job("1")`).
  - VM: `a notice for this PREP shows the offer after a fresh read`. The coordinator notices emit; then `read` is called; then `offer != null`.
  - VM: `accepting the offer sends a NEW capture with the same code and quantity and dismisses the notice`. `useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))` is called, `retry` is not called, and `coordinator.dismiss(notice)` is called.
  - VM: `the offer cannot be accepted while scanning is blocked`. With `blocking` non-empty, accepting sends nothing.
  - VM: `declining dismisses without sending`.
  - VM: `a direct receipt_sealed reply re-reads, then offers the replacement`.
  - Home text: a `NotExecuted` capture notice gets an "Open PREP_…" action label.

- [ ] **Step 2: Fail. Step 3: Implement.** The PREP VM collects `coordinator.notices`, keeping `Resolved` notices whose `outcome == NotExecuted` and `command.targetId == prepId`. For each, it calls `read()` first and sets `offer` only after the read has applied. The dialog text:
  - capture: "Station 2 never received the scan of {code} ({quantity or "whole bag"}) at {createdAt SAST}. It has been cancelled and can never happen later. Record this bag now?" with **[Record bag]** and **[Don't record]**;
  - confirm: "Confirm collection now?"
  - Accept goes through `recordStaged`'s path with that code and quantity, and is guarded by `canScan` / `canConfirm`.

- [ ] **Step 4: Home.** In the notice row for `NotExecuted`, add a `TextButton("Open PREP_…")` (or "Open job …") that calls a new `onOpenPrep` / `onOpenPrepare` parameter, wired in `AppNavGraph` to `navController.navigate(NavRoutes.prep(id))` / `NavRoutes.prepare(jc)`. Nesting into the `jobs` graph from Home is allowed: the route is inside it.

- [ ] **Step 5: Run the whole suite and the build. Step 6: Commit** `feat(recovery): operator-confirmed replacement after a not-executed capture or confirm`.

---

### Task 11: Simulator: receipts, identical replay, `recover`, sealing and the `drop_reply` fault

**Acceptance:** REC-01 to REC-05 (simulated). This is a precondition for the device check.

**Files:**
- Modify: `tools/backend-sim/state.py` (`self.rev2_receipts = {}`)
- Modify: `tools/backend-sim/sim.py` (`_dispatch`, `_apply_control`, `handle_request`)
- Modify: `tools/backend-sim/handlers/rev2_general.py` (`ACTIONS`, `SLICE_ACTIONS`, a `recover` handler, and an `errorCode` on the result)
- Modify: `tools/backend-sim/selftest.py`, `README.md`

**Rules** (these mirror `Rev2MixingService.cs:156-215` and `Rev2MixingService.Recovery.cs:11-47`):
- Mutating actions are `prepare`, `capture` and `confirm`. A receipt key is `(device_id, request_type, message_id)`. A receipt holds `{"hash": SHA256(raw).upper(), "result": result, "operatorId": op, "sealed": False}`.
- On a mutation, check the prior receipt first:
  - prior sealed and hash equal: return the sealed result (`errorCode: receipt_sealed`);
  - prior hash equal: replay `prior["result"]` with a **fresh** snapshot, and do not execute again;
  - otherwise: `Rejection("message_id_conflict", "This message ID was already used with a different body.")`.
- After execution, store the receipt, whether the result succeeded or failed.
- `recover` (rejected with `invalid_envelope` if `originalMessageId` is missing or the same as `messageId`, or if the fingerprint is not 64 hex characters) looks up the key `(device, request_type, originalMessageId)`:
  - receipt found: if the operator differs, reply `receipt_owner_mismatch`; if the hash differs, reply `message_id_conflict`; otherwise the outcome is `not_executed` if sealed, `committed` if `result.success`, else `rejected`;
  - no receipt: store a sealed receipt `{"hash": orig_fp.upper(), "result": {"success": False, "message": "The unanswered command did not execute and its message ID is now sealed. Reload before submitting a new intent.", "targetId": None, "cycleId": None, "errorCode": "receipt_sealed"}, "operatorId": op, "sealed": True}` with the outcome `not_executed`.
  - The reply is `success: true`, `nextAction: "review_recovered_outcome"`, with `data.recovery = {originalMessageId, outcome, result}` and a snapshot for `targetId` or the saved result's target.
- `error` for a business result is `result.get("errorCode") or ("" if result["success"] else "rev2_rejected")`.
- New fault `drop_reply`: `handle_request` processes the request normally (the receipt is saved) but does not publish the reply. Arm it with `{"cmd":"drop_reply","match":"rev2_general_requested","count":1}`.

- [ ] **Step 1: Selftest first.** Add these cases to `selftest.py` (direct mode). They fail before the implementation:
  - an identical capture replay gives the same `result`, and the pallet stock is deducted once;
  - the same id with a changed body gives `message_id_conflict`;
  - `recover` of a committed capture gives `committed`;
  - `recover` of an unknown id gives `not_executed`, and later delivering that id gives `receipt_sealed`;
  - `recover` by another operator gives `receipt_owner_mismatch`;
  - `recover` with `originalMessageId == messageId` gives `invalid_envelope`;
  - `drop_reply`: no reply is published, yet the receipt exists.

  Replace the old check `prepare is outside the slice -> action_not_allowed` (`selftest.py:280-281`).

- [ ] **Step 2: Implement. Step 3:** `python selftest.py --direct`. Expected: no FAIL lines; exit 0.

- [ ] **Step 4: Commit** `chore(sim): receipts, identical replay, recover with sealing, drop_reply fault`.

---

### Task 12: Simulator: catalog, choices, `prepare`, `capture` and `confirm`

**Acceptance:** GEN-02 to GEN-09 and GEN-11 to GEN-13 (simulated).

**Files:** `tools/backend-sim/handlers/rev2_general.py`, `state.py`, `seed/seed.json`, `selftest.py`, `README.md`.

**Rules:**
- **Catalog.** Add `world.ingredient_exceptions = {"revision": 1, "products": [{"code": "1500000331", "description": "Additive (example exception)", "note": ""}]}` to the seed as a `"ingredientExceptions"` key. The snapshot returns `exceptionListRevision` and `ingredientExceptions` from it. `requiredIngredientChoices` holds the catalog rows whose code matches a job material case-insensitively, and only when `job is not None and preparation is None`.
- **`prepare`** (`Rev2MixingService.cs:232-263`, `IngredientExceptionRules.cs:21-49`):
  - the job must exist; `allocated` is the sum of `mixCount` over non-cancelled PREPs; `1 ≤ mixCount ≤ requiredMixes − allocated`, else fail with "Choose a positive whole number up to {n} mixes remaining to prepare.";
  - choice validation in the server's order;
  - `includeTackifier is False` rejects;
  - the effective rows use Python `Decimal(str(perMix)) * mixCount`, with `originalRequired` the same value, and excluded rows getting `required 0`;
  - stage `Collecting`, `collectionRevision 0`, `startedBy` = the operator, and an "Ingredient omitted" exception per excluded row (the shape in contract §6);
  - the hint reason is `preparation_created`.
- **`capture`** (`Rev2MixingService.cs:265-293`):
  - find the pallet by `palletRfidTag` or `palletId` (trimmed, case-insensitive);
  - it is usable when `palletState in (Holding, Mixing)` and not blocked;
  - the material is the non-excluded row whose code equals the pallet's `productCode` and whose remaining is > 0;
  - the unit must match;
  - `bag = Decimal(str(fullBagWeight or config.defaultFullBagWeightKg))`;
  - `q = bag if quantity in (None, 0) else Decimal(str(quantity))`;
  - the bounds of F5 apply;
  - deduct from `remainingQuantity`, add to `collected`, and increment `collectionRevision`;
  - when everything is collected, the stage becomes `AwaitingConfirmation`.
  - A failure against a loaded PREP records a `Rejected collection action` exception and the hint reason `collection_exception_recorded`.
- **`confirm`** (`Rev2MixingService.cs:295-303`). On a failure, record an exception.
- **Wire output.** Serialize `Decimal` values as JSON numbers: convert to `float` only when the value is exactly representable, otherwise use `json.dumps` with a `default` that writes `str(d)` as a raw number. A small custom encoder is fine.
- **`mix_progress`:** `collectedMixes` is the sum of `mixCount` over non-cancelled PREPs that have at least one positive included row and in which every included row has `collected ≥ required` (`Rev2JobProgress.cs:26-28`). `collectionRevision` is taken from the PREP.
- **Snapshot exceptions:** `collectionExceptions` holds the selected PREP's records, and is empty when no PREP is selected. `commandExceptions` holds those records whose `messageId` is this request's id, or the original id for a recover.
- **Seed** `PREP_demo0001` with real effective rows (`mixCount 2`) computed from job `510019068`'s materials, so a resume can collect against it.
- **Hints:** `publish_invalidations(reason)` takes the action reason (`capture`, `confirm`, `preparation_created`, `collection_exception_recorded`) and is called after each successful or exception-recording mutation.

- [ ] **Step 1: Selftest cases first:**
  - lookup gives `requiredIngredientChoices == [1500000331]`;
  - prepare without choices is rejected;
  - prepare with a stale revision is rejected;
  - prepare excluding the additive succeeds, the exception is recorded, available drops by N, and a second prepare above available is rejected;
  - capture a whole bag of `PAL-001` (`1600000301`) collects 25;
  - a part bag of 0.5 is exact;
  - an over-remaining part bag is rejected and its exception appears in `commandExceptions`;
  - a blocked pallet (`PAL-003`) is rejected;
  - an excluded material's pallet (`PAL-007`, `1500000331`) is rejected;
  - capture to completion gives `AwaitingConfirmation`; confirm gives `ReadyForMixer`, and `confirmedMixes` and `collectedMixes` are updated;
  - read `PREP_demo0001` has materials.

- [ ] **Step 2: Implement. Step 3:** `python selftest.py --direct` passes with exit 0.

- [ ] **Step 4: README.** Document the new actions, the pallet tags that the seed BOM uses, the catalog code and the `drop_reply` fault.

- [ ] **Step 5: Commit** `chore(sim): ingredient choices, prepare, capture and confirm with exact decimals`.

---

### Task 13: Verify, run the device check, refresh the graph and hand off

- [ ] **Step 1: Full suite and build**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline`, then `.\gradlew.bat :app:assembleDebug --offline`.
Expected: both BUILD SUCCESSFUL, with zero failing tests.

- [ ] **Step 2: Device check against the simulator** (it needs a device and a broker). Start `python tools/backend-sim/sim.py`, install the debug APK, then sign in as `operator1`.

| # | Do | Expect | IDs |
|---|---|---|---|
| 1 | Jobs → 510019068 → Prepare mixes | Fresh read; one choice `1500000331`, unanswered; Prepare is disabled | GEN-03 |
| 2 | Answer Exclude, N = 2, Prepare, confirm | Lands on the new PREP, Collecting; the excluded row is greyed with its original quantity; the "Ingredient omitted" record shows date, time and operator | GEN-02, GEN-03 |
| 3 | Back to the job | Both PREPs are listed; Available has dropped by 2 | GEN-11, GEN-13 |
| 4 | Scan `300833B2DDD9014000000001` (PAL-001), Record bag | "Recorded 25 kg HD WHITE — … to go" | GEN-07 |
| 5 | Scan the blocked `…0003` and record | Rejected, with the server exception record | GEN-08 |
| 6 | Publish `{"cmd":"drop_reply","match":"rev2_general_requested","count":1}` to `PPNAM/_sim/control`, then record a bag | "not confirmed" banner; the next scan is refused | REC-01 |
| 7 | Tap Retry | Same messageId in `wire.jsonl`; replayed result; stock deducted once | REC-01 |
| 8 | Arm `withhold` for one request, record a bag, then sign out and back in | Home notice reports not executed, with "Open PREP_…"; the PREP screen offers the replacement; Record bag sends a **new** messageId | REC-05 (client) |
| 9 | Collect to completion; Confirm collection | AwaitingConfirmation, then ReadyForMixer; read-only "next: mixer" | GEN-09 |
| 10 | Lookup screen: type the PREP id | Opens that PREP directly | GEN-06 |

Record the results, with screenshot paths and the simulator log folder, in the PR description.

- [ ] **Step 3:** `graphify update .`

- [ ] **Step 4:** In `docs/superpowers/specs/2026-10-08-phase2-general-collection-design.md`, set `**Status:** Implemented (branch feat/contract-2026-10-01-phase2-collection)`. Record the user's answers to spec §12 Q1–Q9, or "defaults kept".

- [ ] **Step 5: Commit**

```bash
git add -A docs graphify-out
git commit -m "docs: mark contract 2026-10-01 phase 2 implemented"
```

Release tagging is the user's decision after review.
