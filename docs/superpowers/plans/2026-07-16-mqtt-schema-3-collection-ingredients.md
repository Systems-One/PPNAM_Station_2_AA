# MQTT Schema 3.0 Collection & Ingredients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver v3's ingredient collection — `lineNumber` as line identity, bulk-line semantics, inline manager approval replacing the `approvalId` handshake, the short-bag waiver, and Station 2's over-collection tolerance.

**Architecture:** One `BomLineResponse` shape serves both `bom_loaded` and `ingredient_scan_result` (the contract returns the same refreshed `ingredients[]` in both). Approval is not a handshake: a rejected scan is resubmitted whole with credentials inline, and the transport's per-call UUID makes it a new operation for free.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson, JUnit4 + mockito-kotlin + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-16-mqtt-schema-3-collection-ingredients-design.md`
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Builds on:** SP1 (`0c3dd9e`), SP2 (`f970531`), both merged to master

## Global Constraints

- **`lineNumber` is the line identity, not `materialCode`.** *"Duplicate SAP BOM material rows remain separate using `lineNumber`."*
- **`null` ≠ `0.0` on bag fields.** On a bulk line (`bagSize == null`), every `*Bags` field is `null`, `overCollectionToleranceBags` is `null`, and **no automatic tolerance applies** — *any* over-collection needs approval.
- **Every `*Bags` field in a RESPONSE is in full-bag equivalents.** `3 × "1/2"` sent → `scannedBags: 1.5` back. Request and response units differ deliberately.
- **`overCollectionToleranceBags` comes from Station 2. Never hardcode or re-derive it.**
- **An approval retry needs a FRESH `messageId`.** Reusing the rejected one is `message_id_reused` and does **not** approve. SP1's transport mints a new UUID per `request()` call, so a resubmit is automatically new — do not cache or reuse a messageId.
- **Never check an action id yourself.** Station 2 checks it against the *approver's* account. Collect credentials and send.
- **Every field in a Gson response DTO MUST keep a default.** Kotlin only emits the no-arg ctor Gson needs when ALL params have defaults; otherwise Gson Unsafe-allocates and EVERY field goes null at runtime with no compile error.
- **mockito-kotlin's `any()` EXCLUDES nulls** — use `anyOrNull()` where a call site passes null.
- `hoppers[]` is **required** in both `bom_loaded` and `ingredient_scan_result`. `HopperBoardEntry` already exists from SP1.
- Run: `./gradlew.bat testDebugUnitTest` / `./gradlew.bat assembleDebug`; you may need `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- **Never `git add -A`.** Stage explicit paths.
- `C:\Dev\PPNAM-Station-2` is read-only. Never edit it; never push anywhere.

## Sequencing

Tasks 1-2 reshape the data. Task 3 reshapes the scan result. Tasks 4-5 are the approval workflows. Task 6 is ViewModel orchestration. Task 7 is UI. Do not reorder — 4 depends on 3's `requiresManagerApproval` shape.

---

### Task 1: Unify the BOM line shape and add lineNumber

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt`

**Interfaces:**
- Consumes: `HopperBoardEntry` (SP1).
- Produces: `BomLineResponse` (unified, full v3 shape); `CollectionSummaryResponse`; `BomLoadedResponse` with `collectionStatus`/`collectionSummary`/`hoppers`; `BomLine` with `lineNumber` and nullable bag fields. **`BomProgressLineResponse` is deleted** — it and `BomLineResponse` are the same shape.

The contract returns *"the full refreshed `ingredients[]`"* in `ingredient_scan_result`, identical in shape to `bom_loaded`'s. Two types would drift.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BomLineTest {

    private fun bagged(lineNumber: Int = 0, remainingQty: Double = 0.0, remainingBags: Double? = 0.0) =
        BomLine(
            lineNumber = lineNumber,
            itemCode = "1600000301",
            itemName = "HD WHITE",
            requiredQty = 557.049,
            remainingQty = remainingQty,
            bagSize = "25.000 kg",
            remainingBags = remainingBags,
        )

    @Test
    fun `two rows sharing a material code stay distinct by line number`() {
        // The contract keeps duplicate SAP BOM rows separate using lineNumber. Keying on itemCode
        // would silently merge them and corrupt progress on both.
        val first = bagged(lineNumber = 0, remainingQty = 100.0)
        val second = bagged(lineNumber = 1, remainingQty = 50.0)

        assertEquals(first.itemCode, second.itemCode)
        assertFalse("lines with the same material must not be equal", first == second)
        assertEquals(setOf(0, 1), listOf(first, second).map { it.lineNumber }.toSet())
    }

    @Test
    fun `a bulk line has no bag figures at all`() {
        val bulk = BomLine(
            lineNumber = 0,
            itemCode = "BULK-1",
            itemName = "Bulk Resin",
            requiredQty = 500.0,
            remainingQty = 500.0,
            bagSize = null,
            expectedBags = null,
            scannedBags = null,
            remainingBags = null,
        )

        assertNull(bulk.bagSize)
        assertNull(bulk.expectedBags)
        assertNull(bulk.remainingBags)
        assertFalse(bulk.isBagged)
    }

    @Test
    fun `a bulk line completes on quantity alone`() {
        // remainingBags is null, not 0 — bag completion is meaningless here and must not gate.
        val bulk = BomLine(
            lineNumber = 0, itemCode = "BULK-1", itemName = "Bulk Resin",
            requiredQty = 500.0, remainingQty = 0.0,
            bagSize = null, expectedBags = null, scannedBags = null, remainingBags = null,
        )

        assertTrue(bulk.isFullyAllocated)
        assertTrue("a satisfied bulk line must not be blocked by absent bag figures", bulk.isSatisfied)
    }

    @Test
    fun `a bagged line needs both quantity and bags satisfied`() {
        assertFalse(bagged(remainingQty = 0.0, remainingBags = 2.0).isSatisfied)
        assertFalse(bagged(remainingQty = 10.0, remainingBags = 0.0).isSatisfied)
        assertTrue(bagged(remainingQty = 0.0, remainingBags = 0.0).isSatisfied)
    }

    @Test
    fun `a bagged line is identified by having a bag size`() {
        assertTrue(bagged().isBagged)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.BomLineTest"`
Expected: FAIL — `BomLine` has no `lineNumber`, no `bagSize`, no `isBagged`, no `isSatisfied`; bag fields are non-null.

- [ ] **Step 3: Rewrite the BOM line DTO**

In `JobCardMessages.kt`, replace `BomLineResponse` with the full v3 shape and add the summary:

```kotlin
/**
 * One BOM line, as returned by BOTH `bom_loaded` and `ingredient_scan_result` — the contract returns
 * "the full refreshed ingredients[]" in the scan result, identical in shape.
 *
 * `lineNumber` is the identity: duplicate SAP material rows remain separate by it.
 *
 * On a bulk line `bagSize` is null and EVERY *Bags field is null — "no bags" is a different fact
 * from "zero bags", and a bulk line has no automatic over-collection tolerance at all.
 *
 * `collectedQuantity` and `weightReceived` legitimately differ: a scan inside tolerance credits only
 * the remaining required amount to collectedQuantity while recording the full weightReceived.
 */
data class BomLineResponse(
    val lineNumber: Int = 0,
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val requiredQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    val weightReceived: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val availableQuantity: Double = 0.0,
    val bagSize: String? = null,
    val expectedBags: Double? = null,
    val scannedBags: Double? = null,
    val approvedExtraBags: Double? = null,
    val approvedShortBags: Double? = null,
    val remainingBags: Double? = null,
    val action: String = "",
    val collected: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val issueType: String = "",
    val requiresIngredientCollection: Boolean = false,
    val uomCode: String = "",
    val unit: String = "",
    val warehouse: String = "",
)

data class CollectionSummaryResponse(
    val waitingProductCount: Int = 0,
    val collectedProductCount: Int = 0,
    val waitingQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    /** Station 2's own human-readable line, e.g. "1 product waiting for collection." */
    val summary: String = "",
)
```

Replace `BomLoadedResponse`:

```kotlin
data class BomLoadedResponse(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val resumed: Boolean = false,
    /** Collecting | ReadyForRouting | Routed | Cancelled */
    val collectionStatus: String = "",
    val bomSnapshotCapturedAtUtc: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
    /** Required by the contract in every bom_loaded — the operator chooses equipment from it. */
    val hoppers: List<HopperBoardEntry> = emptyList(),
)
```

Add `import com.mitas.ppnam.station2aa.domain.model.HopperBoardEntry`.

In `IngredientMessages.kt`, **delete `BomProgressLineResponse` entirely** — `BomLineResponse` replaces it.

- [ ] **Step 4: Rewrite the domain model**

Replace `BomLine` in `ProductionOrder.kt`:

```kotlin
/**
 * A BOM line the operator collects.
 *
 * Identity is [lineNumber], NOT [itemCode] — the contract keeps duplicate SAP material rows separate
 * by line number, so two lines may legitimately share a material code.
 *
 * Bag fields are nullable because a bulk line has none. `null` means "bags are meaningless for this
 * material"; `0.0` means "zero bags". Conflating them makes a bulk line display "0 of 0 bags" and
 * risks treating it as bag-complete.
 */
data class BomLine(
    val lineNumber: Int,
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val collectedQty: Double = 0.0,
    /** May exceed [collectedQty]: a scan inside tolerance records full weight, credits only what was required. */
    val weightReceived: Double = 0.0,
    val remainingQty: Double = 0.0,
    val availableQty: Double = 0.0,
    val uom: String = "",
    /** e.g. "25.000 kg". Null on a bulk line — see [isBagged]. */
    val bagSize: String? = null,
    val expectedBags: Double? = null,
    val scannedBags: Double? = null,
    val approvedExtraBags: Double? = null,
    val approvedShortBags: Double? = null,
    val remainingBags: Double? = null,
    val valid: Boolean = true,
    val reason: String? = null,
) {
    /** A bulk material has no bag size, so no bag arithmetic applies to it. */
    val isBagged: Boolean get() = bagSize != null

    val isFullyAllocated: Boolean get() = remainingQty <= 0.0

    /**
     * A bagged line needs both quantity and bags satisfied; a bulk line completes on quantity alone,
     * because its bag figures are absent rather than zero.
     */
    val isSatisfied: Boolean
        get() = isFullyAllocated && (!isBagged || (remainingBags ?: 0.0) <= 0.0)
}
```

Delete the old `isBagFullyAllocated` and `scannedQty`. Follow the compiler to every call site; report each. `ProductionOrder` also gains `collectionStatus: String = ""` and `summary: String = ""` — map them in Task 2.

- [ ] **Step 5: Run, then commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.BomLineTest"` then the full suite. Callers will break — Task 2 fixes `MixingUseCase`; fix any other call site minimally to compile and report it.

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
        app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt \
        app/src/test/java/com/ppnam/station2aa/domain/model/BomLineTest.kt
# plus any call sites you had to fix
git commit -m "feat(bom): add lineNumber identity and bulk-line semantics to BomLine

Duplicate SAP material rows are kept separate by lineNumber, not
materialCode. Bag fields are nullable: null means bags are meaningless
for a bulk material, which is a different fact from zero bags."
```

---

### Task 2: Map the full bom_loaded shape

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt` (extend)

**Interfaces:**
- Consumes: Task 1's `BomLineResponse`, `BomLoadedResponse`, `BomLine`, `ProductionOrder`.
- Produces: `lookupJob` returns a `ProductionOrder` carrying `lineNumber`, bulk-aware bag fields, `availableQty`, `collectionStatus`, and the summary string.

- [ ] **Step 1: Write the failing tests**

Add to `MixingUseCaseTest`:

```kotlin
    @Test
    fun `duplicate material rows survive the mapping as separate lines`() {
        // Keyed on materialCode, these would collapse into one and corrupt both lines' progress.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionId = "COL_000123",
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 100.0, remainingQuantity = 100.0, issueType = "im_Manual"),
                BomLineResponse(lineNumber = 1, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 50.0, remainingQuantity = 50.0, issueType = "im_Manual"),
            ),
        )
        whenever(mqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = runBlocking { useCase.lookupJob("510019068").getOrThrow() }

        assertEquals(2, order.lines.size)
        assertEquals(listOf(0, 1), order.lines.map { it.lineNumber })
        assertEquals(listOf(100.0, 50.0), order.lines.map { it.requiredQty })
    }

    @Test
    fun `a bulk line maps with null bag fields, not zeroes`() {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "BULK-1", materialName = "Bulk Resin",
                    plannedQuantity = 500.0, remainingQuantity = 500.0, issueType = "im_Manual",
                    bagSize = null, expectedBags = null, scannedBags = null, remainingBags = null),
            ),
        )
        whenever(mqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val line = runBlocking { useCase.lookupJob("510019068").getOrThrow() }.lines.single()

        assertFalse(line.isBagged)
        assertNull(line.expectedBags)
        assertNull(line.remainingBags)
    }

    @Test
    fun `bom_loaded carries availableQuantity, bagSize and the collection summary through`() {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionStatus = "Collecting",
            collectionSummary = CollectionSummaryResponse(
                waitingProductCount = 1, waitingQuantity = 557.049, summary = "1 product waiting for collection."
            ),
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 557.049, remainingQuantity = 557.049, availableQuantity = 625.0,
                    bagSize = "25.000 kg", expectedBags = 22.282, remainingBags = 22.282,
                    issueType = "im_Manual", unit = "kg"),
            ),
        )
        whenever(mqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = runBlocking { useCase.lookupJob("510019068").getOrThrow() }

        assertEquals("Collecting", order.collectionStatus)
        assertEquals("1 product waiting for collection.", order.summary)
        val line = order.lines.single()
        assertEquals(625.0, line.availableQty, 0.001)
        assertEquals("25.000 kg", line.bagSize)
        assertEquals(22.282, line.expectedBags!!, 0.001)
    }
```

Adapt to the file's existing setup (`mqtt`, `useCase`, its stubbing style). Add imports as needed.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: FAIL — `lineNumber`/`bagSize`/`availableQty` unresolved on the mapping.

- [ ] **Step 3: Rewrite the mapping**

In `MixingUseCase`, replace `BomLoadedResponse.toProductionOrder()`:

```kotlin
    private fun BomLoadedResponse.toProductionOrder() = ProductionOrder(
        docNo = jobCardNumber,
        collectionId = collectionId,
        collectionStatus = collectionStatus,
        summary = collectionSummary.summary,
        // im_Backflush lines stay in Station 2's snapshot but are excluded from the handheld's
        // collection array — the one such line names the product being made.
        productBeingMade = ingredients.firstOrNull { it.issueType == "im_Backflush" }?.materialName,
        lines = ingredients
            .filter { it.issueType != "im_Backflush" }
            .map { it.toBomLine() },
    )

    private fun BomLineResponse.toBomLine() = BomLine(
        // Identity. Two lines may legitimately share a materialCode.
        lineNumber = lineNumber,
        itemCode = materialCode,
        itemName = materialName,
        requiredQty = requiredQuantity,
        collectedQty = collectedQuantity,
        weightReceived = weightReceived,
        remainingQty = remainingQuantity,
        availableQty = availableQuantity,
        // SAP UoM 269 displays as kg and 268 as each; unknown values pass through.
        uom = unit.ifBlank { uomCode },
        // Null on a bulk line, and null is meaningful — do NOT coalesce to 0.0.
        bagSize = bagSize,
        expectedBags = expectedBags,
        scannedBags = scannedBags,
        approvedExtraBags = approvedExtraBags,
        approvedShortBags = approvedShortBags,
        remainingBags = remainingBags,
    )
```

**Note the change from SP1:** `requiredQty` now maps from `requiredQuantity`, not `plannedQuantity`. The contract distinguishes them — `plannedQuantity` is SAP's original, `requiredQuantity` is what remains required after any approved waiver adjusts the line. A ledger entry from a prior project flagged the dual-sourcing as a bug that made the progress bar jump.

- [ ] **Step 4: Reuse the mapper for scan progress — and filter backflush there too**

`scanIngredient`'s `toBomLines()` now maps the same `BomLineResponse` type. Delete it and map through
`toBomLine()` — **but keep the `im_Backflush` filter**, which `toBomLines()` currently lacks:

```kotlin
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    outcome.body.ingredients
                        // Same rule as the initial load: the backflush line is the product being
                        // made, not a component to collect. Without this filter a scan response
                        // reintroduces it as a collectible line, because MixingViewModel replaces
                        // the whole line list wholesale with this output.
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() }
                )
            )
```

**Why this matters:** `MixingViewModel.handleScanOutcome()` does `order.copy(lines = outcome.updatedLines)`
— a wholesale replacement. So an unfiltered scan response would contradict the initial load's own
behaviour and put the product being made into the operator's collect list. This was found by Task 1's
review as a pre-existing bug; the same filter must apply on both paths.

**Also verify the bag fields actually land on the load path.** Task 1's review found
`toProductionOrder()` mapping no bag fields at all — they defaulted to `null`, so every line read as
*unbagged* after a job load until its first scan refreshed it. On a resumed collection with a bagged
line whose quantity is satisfied but whose bags are not, that reports "Fully Allocated" — a false
positive on the exact axis this sub-project exists to fix, and no test caught it. Step 3's
`toBomLine()` fixes this by construction; **add a test proving a bagged line survives a job load with
its `bagSize` and bag figures intact**, because nothing else pins it.

- [ ] **Step 5: Run, build, commit**

Run: `./gradlew.bat testDebugUnitTest && ./gradlew.bat assembleDebug`

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(bom): map the full v3 bom_loaded shape

requiredQty now sources from requiredQuantity rather than
plannedQuantity — the contract distinguishes SAP's original from what
remains required after an approved waiver."
```

---

### Task 3: Reshape ingredient_scan_result

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt`

**Interfaces:**
- Consumes: Task 1's `BomLineResponse`, `CollectionSummaryResponse`; `HopperBoardEntry`.
- Produces: `IngredientScanResultResponse` with `overCollectionToleranceBags`, approver fields, `collectionSummary`, `hoppers`, `ingredients` (renamed from `ingredientProgress`). **`exceptionId`/`consumedApprovalId` deleted** — v3 has no exceptions or approval tokens.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientScanResultTest {

    private val gson = Gson()

    @Test
    fun `a bagged line reports the tolerance Station 2 applied`() {
        val json = """
            {"collectionId":"COL_000123","overCollectionToleranceBags":1.0,
             "ingredients":[{"lineNumber":0,"materialCode":"1600000301","bagSize":"25.000 kg","scannedBags":1.5}]}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals(1.0, r.overCollectionToleranceBags!!, 0.001)
        assertEquals(1.5, r.ingredients.single().scannedBags!!, 0.001)
    }

    @Test
    fun `a bulk line reports no tolerance at all`() {
        // null means no automatic tolerance applies and ANY over-collection needs approval —
        // materially different from a tolerance of zero.
        val json = """
            {"collectionId":"COL_000123","overCollectionToleranceBags":null,
             "ingredients":[{"lineNumber":0,"materialCode":"BULK-1","bagSize":null,"scannedBags":null}]}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertNull(r.overCollectionToleranceBags)
        assertNull(r.ingredients.single().bagSize)
        assertNull(r.ingredients.single().scannedBags)
    }

    @Test
    fun `an approved scan names the approver`() {
        val json = """
            {"collectionId":"COL_000123","approverUserId":"OP-012",
             "approverDisplayName":"Manager One","approverRole":"Manager"}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals("OP-012", r.approverUserId)
        assertEquals("Manager One", r.approverDisplayName)
    }

    @Test
    fun `an ordinary scan names no approver`() {
        val r = gson.fromJson("""{"collectionId":"COL_000123"}""", IngredientScanResultResponse::class.java)

        assertNull(r.approverUserId)
        assertNull(r.approverDisplayName)
    }

    @Test
    fun `the result carries the refreshed summary and hopper board`() {
        // Both are required by the contract: the scan result is a workflow decision point.
        val json = """
            {"collectionId":"COL_000123",
             "collectionSummary":{"waitingProductCount":1,"summary":"1 product waiting for collection."},
             "hoppers":[{"displayName":"Hopper 1","machineCode":"MXR-01","status":"Available","isAvailable":true}]}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals("1 product waiting for collection.", r.collectionSummary.summary)
        assertEquals("MXR-01", r.hoppers.single().machineCode)
        assertTrue(r.hoppers.single().isAvailable)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultTest"`
Expected: FAIL — `overCollectionToleranceBags`, `approverUserId`, `ingredients`, `hoppers` unresolved.

- [ ] **Step 3: Rewrite the response**

In `IngredientMessages.kt`:

```kotlin
/**
 * `ingredient_scan_result`.
 *
 * Returns the FULL refreshed `ingredients[]` — not just the line this scan touched — so the scanner
 * always holds the current picture. Every scan on one collection shares a correlationKey, so
 * `inResponseToMessageId` is the only way to tell which scan a result belongs to (the transport
 * handles that).
 *
 * `overCollectionToleranceBags` is the tolerance Station 2 ACTUALLY APPLIED — never hardcode it.
 * It is null on a bulk line, where no automatic tolerance applies and any over-collection needs
 * approval.
 *
 * The approver fields are null on an ordinary scan and name the account that authorised an override or
 * waiver. `approverRole` is informational only.
 */
data class IngredientScanResultResponse(
    val collectionId: String = "",
    val requiresManagerApproval: Boolean = false,
    /** Null on a bulk line: no automatic tolerance applies there. */
    val overCollectionToleranceBags: Double? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
    /** Required by the contract in every scan result — including the ingredient-ready scan. */
    val hoppers: List<HopperBoardEntry> = emptyList(),
)
```

Delete `exceptionId`, `consumedApprovalId`, `scannedQuantity`, `isRequirementSatisfied`, `hasApprovedException` — v3 has none of them; the refreshed `ingredients[]` carries the truth.

Add imports for `BomLineResponse`, `CollectionSummaryResponse`, `HopperBoardEntry`.

- [ ] **Step 4: Run, commit**

Follow the compiler to `MixingUseCase.scanIngredient` (Task 4 reworks it properly — do the minimum to compile here and report it).

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt
git commit -m "feat(ingredients): reshape ingredient_scan_result to v3

Adds overCollectionToleranceBags (Station 2's number, never ours),
approver identity, the refreshed summary and hopper board. Removes
exceptionId/consumedApprovalId — v3 has no exceptions or approval tokens."
```

---

### Task 4: Inline manager approval

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt` (extend)

**Interfaces:**
- Produces: `IngredientScanPayload` with `managerUsername`/`managerPassword`/`auditReason`; `IngredientScanOutcome.NeedsManagerApproval` carrying the **whole original scan**; `MixingUseCase.scanIngredient(..., managerUsername, managerPassword, auditReason)`. **`approveManagerException` is DELETED.**

**This is the sub-project's centrepiece.** v3 deletes the `manager_approval_requested` topic and the `approvalId` concept. A rejected scan is resubmitted whole with credentials inline and a **fresh `messageId`**.

**The fresh messageId is free** — SP1's transport mints a new UUID inside every `request()` call, so a resubmit is automatically a new operation. Do not cache or reuse one.

- [ ] **Step 1: Write the failing tests**

Add to `MixingUseCaseTest`:

```kotlin
    @Test
    fun `a scan needing approval returns everything required to resubmit it`() {
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Rejected(
                    IngredientScanResultResponse(collectionId = "COL_000123", requiresManagerApproval = true),
                    null, "Over tolerance", NextAction.RETRY_WITH_MANAGER_APPROVAL,
                )
            )

        val outcome = runBlocking {
            useCase.scanIngredient("COL_000123", "TAG-1", "1/2", 3.0, "1600000301")
        }.getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        val needs = outcome as IngredientScanOutcome.NeedsManagerApproval
        // Without these, the retry cannot rebuild the scan.
        assertEquals("COL_000123", needs.collectionId)
        assertEquals("TAG-1", needs.palletRfidTag)
        assertEquals("1600000301", needs.requestedMaterialCode)
        assertEquals("1/2", needs.bagSizeOption)
        assertEquals(3.0, needs.bagCount!!, 0.001)
    }

    @Test
    fun `an approved retry sends the original scan plus credentials`() {
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(collectionId = "COL_000123"), NextAction.SCAN_INGREDIENT))

        runBlocking {
            useCase.scanIngredient(
                collectionId = "COL_000123", palletRfidTag = "TAG-1",
                bagSizeOption = "1/2", bagCount = 3.0, requestedMaterialCode = "1600000301",
                managerUsername = "manager1", managerPassword = "secret",
                auditReason = "Approved additional bag after verified spillage.",
            )
        }

        verify(mqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is IngredientScanPayload &&
                    palletRfidTag == "TAG-1" && bagSizeOption == "1/2" && bagCount == 3.0 &&
                    managerUsername == "manager1" && managerPassword == "secret" &&
                    auditReason == "Approved additional bag after verified spillage."
            },
            eq("COL_000123"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `an ordinary scan omits the credential fields entirely`() {
        // The contract forbids sending null or "" as a stand-in for absence; Gson omits nulls.
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(), NextAction.SCAN_INGREDIENT))

        runBlocking { useCase.scanIngredient("COL_000123", "TAG-1", "full", 1.0, "1600000301") }

        verify(mqtt).request(
            any(), any(),
            argThat<Any> {
                this is IngredientScanPayload &&
                    managerUsername == null && managerPassword == null && auditReason == null
            },
            any(), any(),
        )
    }
```

**Add a test proving the retry is a distinct operation.** The transport mints the messageId, so assert it at that level — two `request()` calls produce two different `messageId`s on the wire. If `MqttRequestCorrelationTest` already proves per-call uniqueness, reference it in your report instead of duplicating; state which.

- [ ] **Step 2: Run to verify failure**

Expected: FAIL — `scanIngredient` has no credential params; `NeedsManagerApproval` has no `collectionId`/`palletRfidTag`/`bagSizeOption`/`bagCount`.

- [ ] **Step 3: Extend the payload**

In `IngredientMessages.kt`:

```kotlin
/**
 * `ingredient_scan_requested`.
 *
 * Manager credentials travel INLINE on a resubmitted scan — v3 has no separate approval message and
 * no approval token. The resubmit MUST carry a fresh messageId: reusing the rejected one is
 * rejected as `message_id_reused` and does NOT perform the approval. The transport mints a new UUID
 * per request() call, so a resubmit is automatically a new operation.
 *
 * `auditReason` is the operator's justification for the audit trail — not the same field as a
 * response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientScanPayload(
    val collectionId: String,
    val palletRfidTag: String,
    val requestedMaterialCode: String? = null,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double? = null,
    val managerUsername: String? = null,
    val managerPassword: String? = null,
    val auditReason: String? = null,
)
```

- [ ] **Step 4: Rework the outcome model**

Replace `IngredientScanOutcome.NeedsManagerApproval`:

```kotlin
    /**
     * Station 2 rejected the scan pending manager approval. Carries the whole original scan, because
     * v3's approval is a RESUBMIT of it with credentials attached — there is no approval token to
     * carry instead.
     */
    data class NeedsManagerApproval(
        val collectionId: String,
        val palletRfidTag: String,
        val requestedMaterialCode: String,
        val bagSizeOption: String?,
        val bagCount: Double?,
        val reason: String,
    ) : IngredientScanOutcome()
```

Delete `exceptionId`.

- [ ] **Step 5: Rework the use case**

Add the credential params to `scanIngredient` and pass them through; build `NeedsManagerApproval` from the *request's own* fields (the response does not echo them):

```kotlin
    suspend fun scanIngredient(
        collectionId: String,
        palletRfidTag: String,
        bagSizeOption: String,
        bagCount: Double,
        requestedMaterialCode: String,
        managerUsername: String? = null,
        managerPassword: String? = null,
        auditReason: String? = null,
    ): Result<IngredientScanOutcome> {
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                requestedMaterialCode = requestedMaterialCode,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(outcome.body.ingredients.map { it.toBomLine() })
            )
            is MqttOutcome.Rejected -> Result.success(
                when {
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        // Rebuilt from the REQUEST — the response doesn't echo these back.
                        collectionId = collectionId,
                        palletRfidTag = palletRfidTag,
                        requestedMaterialCode = requestedMaterialCode,
                        bagSizeOption = bagSizeOption,
                        bagCount = bagCount,
                        reason = outcome.reason ?: "Manager approval required",
                    )
                    outcome.nextAction == NextAction.RECOVER_HOLDING ->
                        IngredientScanOutcome.NeedsRecovery(outcome.reason)
                    else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Ingredient scan rejected")
                }
            )
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }
```

**Delete `approveManagerException` entirely.** Delete any test for it.

- [ ] **Step 6: Run, build, commit**

Run: `./gradlew.bat testDebugUnitTest && ./gradlew.bat assembleDebug`

Task 6 fixes `MixingViewModel`. If it won't compile in between, do the minimum and report.

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(ingredients): inline manager approval, replacing the approvalId handshake

v3 deletes manager_approval_requested and the approval token. A rejected
scan is resubmitted whole with credentials inline and a fresh messageId
— reusing the old id is message_id_reused and does not approve. The
transport's per-call UUID makes the resubmit a new operation for free."
```

---

### Task 5: The short-bag waiver

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt` (extend)

**Interfaces:**
- Produces: `ShortBagWaiverPayload`; `MixingUseCase.waiveShortBags(collectionId, requestedMaterialCode, shortBagCount, managerUsername, managerPassword, auditReason): Result<IngredientScanOutcome>`.

**A waiver is not a reject-then-retry.** It carries credentials on its **first** submission — there is no scan to attempt and fail; the operator is declaring up front that a line will be short. It shares the `ingredient_scan_requested` topic but is a **distinct operation with a distinct payload**: no pallet, no bag size, `shortBagCount` instead. Modelling it as "a scan with extra fields" would be wrong.

The approver needs `ingredient_approve_short_bag` — a *different* action id from an override's `ingredient_approve_override`. **We never check it; Station 2 checks it against the approver's account.**

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a waiver sends credentials on its first submission and carries no pallet`() {
        // Not a reject-then-retry: there is no scan to fail first.
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(collectionId = "COL_000123"), NextAction.SCAN_INGREDIENT))

        runBlocking {
            useCase.waiveShortBags(
                collectionId = "COL_000123", requestedMaterialCode = "1600000301", shortBagCount = 1.0,
                managerUsername = "manager1", managerPassword = "secret",
                auditReason = "One damaged bag unavailable.",
            )
        }

        verify(mqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is ShortBagWaiverPayload &&
                    collectionId == "COL_000123" &&
                    requestedMaterialCode == "1600000301" &&
                    shortBagCount == 1.0 &&
                    managerUsername == "manager1" && managerPassword == "secret" &&
                    auditReason == "One damaged bag unavailable."
            },
            eq("COL_000123"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `a waiver without credentials surfaces as needing approval, not a generic failure`() {
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Rejected(
                    IngredientScanResultResponse(requiresManagerApproval = true),
                    null, "Manager approval required.", NextAction.RETRY_WITH_MANAGER_APPROVAL,
                )
            )

        val outcome = runBlocking {
            useCase.waiveShortBags("COL_000123", "1600000301", 1.0, "", "", "One damaged bag unavailable.")
        }.getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
    }

    @Test
    fun `an accepted waiver returns the refreshed lines`() {
        whenever(mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Accepted(
                    IngredientScanResultResponse(
                        collectionId = "COL_000123",
                        ingredients = listOf(
                            BomLineResponse(lineNumber = 0, materialCode = "1600000301",
                                requiredQuantity = 532.049, approvedShortBags = 1.0, bagSize = "25.000 kg")
                        ),
                    ),
                    NextAction.SCAN_INGREDIENT,
                )
            )

        val outcome = runBlocking {
            useCase.waiveShortBags("COL_000123", "1600000301", 1.0, "manager1", "secret", "One damaged bag unavailable.")
        }.getOrThrow()

        // A waiver adjusts the line's requirement directly; it never produces a scanned line.
        val lines = (outcome as IngredientScanOutcome.Accepted).updatedLines
        assertEquals(1.0, lines.single().approvedShortBags!!, 0.001)
    }
```

- [ ] **Step 2: Run to verify failure** — `Unresolved reference: waiveShortBags`.

- [ ] **Step 3: Add the payload**

```kotlin
/**
 * A short-bag waiver. Shares the `ingredient_scan_requested` topic but is a DISTINCT operation:
 * there is no pallet and no bag size — the operator is declaring up front that a line will be short.
 *
 * Credentials go on the FIRST submission, not a retry: there is no scan to attempt and fail. Sent
 * without them it is rejected outright with requiresManagerApproval.
 *
 * `requestedMaterialCode` is REQUIRED — there is no pallet to identify the line.
 *
 * The approver must hold `ingredient_approve_short_bag` — a different action id from an override's
 * `ingredient_approve_override`. Station 2 checks that against the approver's account; we never do.
 */
data class ShortBagWaiverPayload(
    val collectionId: String,
    val requestedMaterialCode: String,
    val shortBagCount: Double,
    val managerUsername: String,
    val managerPassword: String,
    val auditReason: String,
)
```

- [ ] **Step 4: Add the use case method**

Mirror `scanIngredient`'s outcome mapping. `NeedsManagerApproval` needs the scan fields, which a waiver lacks — pass `palletRfidTag = ""` and `bagSizeOption = null`, and note in your report that a waiver's `NeedsManagerApproval` is informational (the UI re-collects credentials into a fresh `waiveShortBags` call, it does not "retry" a stored scan). **If that feels wrong, say so** — an alternative is a distinct `NeedsApprovalForWaiver` outcome. Use your judgement and explain it.

- [ ] **Step 5: Run, build, commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(ingredients): add the short-bag waiver

Credentials on first submission, not a retry — there is no scan to fail
first. Adjusts the line's requirement directly and never produces a
scanned line."
```

---

### Task 6: ViewModel — approval retry, waiver, and the Error trap state

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: Tasks 4-5's `scanIngredient(..., credentials)`, `waiveShortBags`, reshaped `NeedsManagerApproval`.
- Produces: `submitManagerApproval(username, password, auditReason)` that **resubmits the pending scan**; `submitShortBagWaiver(...)`; `dismissError()`.

**Three things land here:**

1. **`submitManagerApproval` currently dead-ends** on the deleted `approveManagerException`. It must now call `scanIngredient` again with the pending scan's fields plus credentials. Store the pending `NeedsManagerApproval` in the ViewModel — **not** in Room. It is short-lived (an operator standing at a pallet with a manager beside them), and persisting it would invite resuming a stale approval hours later against a moved-on collection. If the app dies mid-approval, re-scan.

2. **`MixingUiState.Error` is a trap state.** `clearError()` has **zero callers** and the screen has no dismiss. SP2's scan guard nearly shipped a permanently-dead reader because of it, and was only saved by letting scans through in `Error`. Give it a real exit: `dismissError()` returns to `OrderLoaded` when an order is loaded, `Idle` otherwise. Delete `clearError()` or make `dismissError()` its replacement.

3. **Per-state scan-guard decisions for every new state.** Fail-closed is the right default but must be *decided* per state, not inherited — that is exactly how the trap state happened. **List your decision for every `MixingUiState` member in your report.**

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `submitting approval resubmits the pending scan with credentials`() {
        // Set up a scan that gets rejected pending approval, then approve it.
        // ... (stub lookupJob, then scanIngredient returning NeedsManagerApproval)

        viewModel.submitManagerApproval("manager1", "secret", "Approved after verified spillage.")
        advanceUntilIdle()

        verify(mockUseCase).scanIngredient(
            eq("COL_000123"), eq("TAG-1"), eq("1/2"), eq(3.0), eq("1600000301"),
            eq("manager1"), eq("secret"), eq("Approved after verified spillage."),
        )
    }

    @Test
    fun `submitting approval with no pending scan is a no-op`() {
        viewModel.submitManagerApproval("manager1", "secret", "reason")
        advanceUntilIdle()

        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `dismissing an error returns to the loaded order rather than stranding the operator`() {
        // MixingUiState.Error had no exit at all: clearError() has zero callers and the screen has
        // no dismiss button, so one timed-out scan left the reader permanently dead.
        // ... (stub lookupJob, drive a scan to Error)

        viewModel.dismissError()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }
```

Adapt to the file's setup. Fill in the `...` — the existing tests show the stubbing patterns.

**`yield()` does not advance `runTest`'s virtual clock** — an earlier task hit a deterministic livelock. Use `advanceUntilIdle()`/`runCurrent()`.

- [ ] **Step 2: Run to verify failure**, then implement, then run again.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): resubmit-based manager approval, waiver, and an exit from Error

Error was a trap state: clearError() had zero callers and the screen no
dismiss, so one timed-out scan left the reader permanently dead."
```

---

### Task 7: The ingredient screen

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: Task 6's `submitManagerApproval`, `submitShortBagWaiver`, `dismissError`; Task 1's `BomLine`.

No Compose test infrastructure exists here — `./gradlew.bat assembleDebug` is the only signal. **Say so in your report; do not claim coverage you don't have.**

Deliver:

1. **An error dismiss.** The card currently has none. This is the fix for the trap state — without it Task 6's `dismissError()` has no caller and the whole thing is theatre.
2. **Bulk lines display a weight, not "0 of 0 bags".** Gate every bag element on `line.isBagged`.
3. **Bag progress in full-bag equivalents, with the unit explicit** — e.g. "1.5 / 22.3 full bags". The operator scans 3 half-bags and sees 1.5; that is correct and must be legible rather than look like a bug. This is the contract's only unit in which `remainingBags = expectedBags − scannedBags` survives mixed sizes.
4. **`availableQuantity` and `bagSize` shown per line** — they change what an operator does next.
5. **`collectionSummary.summary`** shown as Station 2 wrote it.
6. **The approval dialog collects `auditReason`**, not just credentials. v3 requires it, and it is the operator's justification for the audit trail.
7. **A short-bag waiver entry point** per line: material code, short bag count, credentials, audit reason.
8. **Duplicate material rows must be visually distinguishable** — two lines can share a material code and name. Show `lineNumber`, or the operator cannot tell which row their scan landed on. Use `lineNumber` as the `LazyColumn` key, never `itemCode`.

Match the file's existing Compose style and theme (`AmberPrimary`, `TextMuted`, `GraphiteSurface`, `DangerRed`, `SuccessGreen`).

- [ ] **Step 1: Build, then commit**

Run: `./gradlew.bat testDebugUnitTest && ./gradlew.bat assembleDebug`

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(mixing): v3 ingredient screen — bulk lines, tolerance, waiver, error exit"
```

---

## Definition of Done

- [ ] `./gradlew.bat testDebugUnitTest` passes; `./gradlew.bat assembleDebug` succeeds.
- [ ] `grep -rn "approveManagerException\|exceptionId\|consumedApprovalId\|approvalId\|BomProgressLineResponse" app/src --include=*.kt` returns nothing.
- [ ] Two BOM rows sharing a material code track progress independently.
- [ ] A bulk line shows no bag figures and is never treated as bag-incomplete.
- [ ] `overCollectionToleranceBags` is read from the response — `grep` finds no hardcoded tolerance.
- [ ] An approval retry is a fresh `request()` call (hence a fresh `messageId`).
- [ ] `MixingUiState.Error` has a working exit.

## Handoff to sub-project 4

- SP4's spec is already written: `docs/superpowers/specs/2026-07-16-mqtt-schema-3-hopper-cycles-design.md`. It records the user's hopper flow (see the board, scan each hopper, submit once) and why batching is the only safe option — `machineCodes[]` is claimed atomically.
- `hoppers[]` now arrives in both `bom_loaded` and `ingredient_scan_result` and is mapped. SP4 consumes it; it does not need a new lookup to show the board.
- `nextAction: "choose_destination"` on the ingredient-ready scan is SP4's entry point. `IngredientScanScreen`'s routing button is still permanently disabled with honest copy (SP1) — **SP4 re-enables it.**
- The scan-guard allow-list needs another per-state pass as SP4 adds states.

## Open questions for the Station 2 developer

1. **Acceptance window** — answered: not implemented, contradicting the contract. Escalated in `docs/backend/2026-07-16-timestamp-acceptance-window.md`.
2. **Is `station_2` the literal presence-topic device id?** Open; SP2's banner depends on it.
3. Message-specific `errorCode` values beyond the 14 shared codes?
4. **SP3-specific:** with duplicate BOM rows, `ingredient_scan_requested` targets by `requestedMaterialCode` only, so Station 2 chooses which `lineNumber` a scan lands on. Is that deterministic? The operator watches progress land on one of two identical-looking rows and we would like to explain why.
