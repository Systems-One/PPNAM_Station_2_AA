# Ingredient Scanning Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android app's legacy, non-contract ingredient-scanning flow with the real MQTT contract (`ingredient_scanned`/`ingredient_scan_result`, `manager_approval_requested`/`manager_approval_result`, `holding_recovery_requested`/`holding_recovery_result`), so scanning a pallet and entering a bag size/count drives a live, decimal-accurate BOM progress bar instead of counting RFID taps.

**Architecture:** Add new contract-shaped DTOs and `MixingUseCase` methods alongside the existing legacy ones first (non-breaking), then migrate `MixingViewModel` and `IngredientScanScreen` over to them, then delete the legacy methods/models/tests once nothing references them. Every task leaves the build green and the full test suite passing.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson, JUnit + Mockito-Kotlin, Gradle.

## Global Constraints

- Only `RFID_MQTT_CONTRACT.md` may be edited under `C:\Dev\PPNAM-Station-2` — every other file there is read-only (see this repo's `CLAUDE.md`). This plan does not need to touch the contract at all — it's already documented.
- Ingredient scanning requires a live connection: every new `sendTyped` call in this plan uses `allowOfflineQueue = false`.
- `BomLine`'s existing `Double` fields (`requiredQty`, `scannedQty`, `remainingQty`, `uom`) are never renamed — only new fields are added, so the display work from the prior session (`IngredientScanScreen`'s fraction/label) keeps compiling untouched.
- Run tests with: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"` (swap the test class name per task).
- Run the full suite before the final commit of each task: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest`

---

### Task 1: Ingredient-scan contract DTOs and BomLine bag-progress fields

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt:13-24` (`BomLine`)

**Interfaces:**
- Produces: `IngredientScannedRequest`, `BomProgressLineResponse`, `IngredientScanResultResponse`, `ManagerApprovalRequest`, `ManagerApprovalResultResponse`, `HoldingRecoveryRequest`, `HoldingRecoveryResultResponse` (exact field names below — Task 2 constructs/consumes these). `BomLine.expectedBags`/`scannedBags`/`remainingBags: Double` (Task 2 populates these).

This task is data classes only (no branching logic), matching the codebase's existing convention that DTO files like `JobCardMessages.kt` have no dedicated tests — they're exercised indirectly through the use-case tests that construct and serialize them. There is no red/green cycle here; verify by building.

- [ ] **Step 1: Create the DTO file**

Write `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

data class IngredientScannedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val palletRfidTag: String,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double = 0.0,
    val requestedMaterialCode: String = "",
    val approvalId: String = ""
)

data class BomProgressLineResponse(
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val requiredQuantity: Double = 0.0,
    val scannedQuantity: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val expectedBags: Double = 0.0,
    val scannedBags: Double = 0.0,
    val approvedExtraBags: Double = 0.0,
    val approvedShortBags: Double = 0.0,
    val remainingBags: Double = 0.0,
    val requiresManagerApproval: Boolean = false,
    val uomCode: String = "",
    val unit: String = ""
)

data class IngredientScanResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val preMixId: String = "",
    val scannedQuantity: Double = 0.0,
    val isRequirementSatisfied: Boolean = false,
    val hasApprovedException: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val exceptionId: String = "",
    val consumedApprovalId: String = "",
    val nextAction: String = "",
    val ingredientProgress: List<BomProgressLineResponse> = emptyList()
)

data class ManagerApprovalRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val managerUsername: String,
    val managerPassword: String,
    val approvalTargetType: String = "Exception",
    val approvalTargetId: String,
    val preMixId: String,
    val palletRfidTag: String = "",
    val requestedMaterialCode: String = "",
    val actualMaterialCode: String = "",
    val quantityDelta: Double = 0.0,
    val bagCountDelta: Double = 0.0,
    val reason: String = ""
)

data class ManagerApprovalResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val managerUserId: String = "",
    val managerDisplayName: String = "",
    val role: String = "",
    val roleLabel: String = "",
    val approvalTargetType: String = "",
    val approvalTargetId: String = "",
    val approvalType: String = "",
    val approvalId: String = "",
    val expiresAtUtc: String? = null
)

data class HoldingRecoveryRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val preMixId: String,
    val palletRfidTag: String,
    val productCode: String = "",
    val quantity: Double = 0.0
)

data class HoldingRecoveryResultResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val preMixId: String = "",
    val palletRfidTag: String = "",
    val productCode: String = "",
    val exceptionId: String = "",
    val nextAction: String = ""
)
```

- [ ] **Step 2: Add bag-progress fields to BomLine**

In `app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt`, replace:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val remainingQty: Double = 0.0,
    val uom: String = "",
    val valid: Boolean = true,
    val reason: String? = null
) {
    val isFullyAllocated: Boolean get() = remainingQty <= 0.0
}
```

with:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val remainingQty: Double = 0.0,
    val uom: String = "",
    val expectedBags: Double = 0.0,
    val scannedBags: Double = 0.0,
    val remainingBags: Double = 0.0,
    val valid: Boolean = true,
    val reason: String? = null
) {
    val isFullyAllocated: Boolean get() = remainingQty <= 0.0
    val isBagFullyAllocated: Boolean get() = remainingQty <= 0.0 && remainingBags <= 0.0
}
```

- [ ] **Step 3: Build to verify**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. `BomLine`'s new fields default to `0.0`, so every existing constructor call (which only sets the original fields) keeps compiling unchanged — no other file needs touching yet.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt
git commit -m "feat(mixing): add ingredient-scan contract DTOs and BomLine bag fields"
```

---

### Task 2: MixingUseCase.scanIngredient

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt` (add method; the file currently ends at line 261 with the closing brace of the class)
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `IngredientScannedRequest`, `IngredientScanResultResponse`, `BomProgressLineResponse` (Task 1); `BomLine` with bag fields (Task 1); `mqttRepository.sendTyped(requestType, responseType, requestJson, responseClass, allowOfflineQueue): MqttTypedResult<T>` (existing).
- Produces: `sealed class IngredientScanOutcome` with `Accepted(updatedLines: List<BomLine>)`, `NeedsManagerApproval(exceptionId: String, reason: String)`, `NeedsRecovery(reason: String?)`, `Rejected(reason: String)`. `MixingUseCase.scanIngredient(preMixId: String, palletRfidTag: String, bagSizeOption: String, bagCount: Double, approvalId: String = ""): Result<IngredientScanOutcome>` — Task 6 (`MixingViewModel`) calls this exact signature.

- [ ] **Step 1: Create the outcome sealed class**

Write `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

sealed class IngredientScanOutcome {
    data class Accepted(val updatedLines: List<BomLine>) : IngredientScanOutcome()
    data class NeedsManagerApproval(val exceptionId: String, val reason: String) : IngredientScanOutcome()
    data class NeedsRecovery(val reason: String?) : IngredientScanOutcome()
    data class Rejected(val reason: String) : IngredientScanOutcome()
}
```

- [ ] **Step 2: Write the failing tests**

Add to `MixingUseCaseTest.kt`, after the `// --- fetchActiveJobCards ---` block's last test (after the `fetchActiveJobCards returns failure when disconnected` test, before `// --- validateIngredient ---`):

```kotlin
    // --- scanIngredient ---

    @Test
    fun `scanIngredient accepted maps ingredientProgress into updated BomLine list`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = true,
            preMixId = "premix-1",
            ingredientProgress = listOf(
                BomProgressLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 20.0, requiredQuantity = 50.0,
                    scannedQuantity = 20.0, remainingQuantity = 30.0,
                    expectedBags = 5.0, scannedBags = 2.0, remainingBags = 3.0,
                    uomCode = "kg", unit = "kg"
                )
            )
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue(outcome is IngredientScanOutcome.Accepted)
        val line = (outcome as IngredientScanOutcome.Accepted).updatedLines.single()
        assertEquals("MAT-001", line.itemCode)
        assertEquals(50.0, line.requiredQty, 0.0001)
        assertEquals(30.0, line.remainingQty, 0.0001)
        assertEquals(3.0, line.remainingBags, 0.0001)
        assertEquals("kg", line.uom)
    }

    @Test
    fun `scanIngredient sends palletRfidTag, bagSizeOption and bagCount in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("ingredient_scanned"), eq("ingredient_scan_result"), captor.capture(),
            eq(IngredientScanResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
        assertTrue(captor.firstValue.contains("\"bagSizeOption\":\"full\""))
        assertTrue(captor.firstValue.contains("\"bagCount\":2.0"))
    }

    @Test
    fun `scanIngredient includes approvalId when retrying after an approved exception`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, approvalId = "approval-1")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("ingredient_scanned"), eq("ingredient_scan_result"), captor.capture(),
            eq(IngredientScanResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"approvalId\":\"approval-1\""))
    }

    @Test
    fun `scanIngredient rejected with requiresManagerApproval returns NeedsManagerApproval`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = false,
            reason = "Wrong material for this pallet",
            requiresManagerApproval = true,
            exceptionId = "exception-1",
            nextAction = "manager_approval"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        assertEquals("exception-1", (outcome as IngredientScanOutcome.NeedsManagerApproval).exceptionId)
        assertEquals("Wrong material for this pallet", outcome.reason)
    }

    @Test
    fun `scanIngredient rejected with recover_holding returns NeedsRecovery`() = runTest {
        val response = IngredientScanResultResponse(
            accepted = false,
            reason = "Pallet not in Holding or Mixing",
            nextAction = "recover_holding"
        )
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsRecovery)
        assertEquals("Pallet not in Holding or Mixing", (outcome as IngredientScanOutcome.NeedsRecovery).reason)
    }

    @Test
    fun `scanIngredient plainly rejected returns Rejected`() = runTest {
        val response = IngredientScanResultResponse(accepted = false, reason = "Unknown pallet")
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val outcome = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.Rejected)
        assertEquals("Unknown pallet", (outcome as IngredientScanOutcome.Rejected).reason)
    }

    @Test
    fun `scanIngredient returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("ingredient_scanned"), eq("ingredient_scan_result"), any(),
                eq(IngredientScanResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }
```

Add these imports to `MixingUseCaseTest.kt`'s import block: `com.mitas.ppnam.station2aa.data.mqtt.dto.BomProgressLineResponse`, `com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse`, `com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: FAIL to compile — `useCase.scanIngredient(...)` does not exist yet.

- [ ] **Step 4: Implement scanIngredient**

In `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`, add these imports:

```kotlin
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScannedRequest
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
```

Add this method to the `MixingUseCase` class, directly after `cancelJob` (before `suspend fun validateIngredient`):

```kotlin
    suspend fun scanIngredient(
        preMixId: String,
        palletRfidTag: String,
        bagSizeOption: String,
        bagCount: Double,
        approvalId: String = ""
    ): Result<IngredientScanOutcome> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            IngredientScannedRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = preMixId,
                preMixId = preMixId,
                palletRfidTag = palletRfidTag,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                approvalId = approvalId
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "ingredient_scanned",
            responseType = "ingredient_scan_result",
            requestJson = requestJson,
            responseClass = IngredientScanResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                val outcome = when {
                    response.accepted -> IngredientScanOutcome.Accepted(
                        response.ingredientProgress.map { line ->
                            BomLine(
                                itemCode = line.materialCode,
                                itemName = line.materialName,
                                requiredQty = line.requiredQuantity,
                                scannedQty = line.scannedQuantity,
                                remainingQty = line.remainingQuantity,
                                uom = line.uomCode,
                                expectedBags = line.expectedBags,
                                scannedBags = line.scannedBags,
                                remainingBags = line.remainingBags
                            )
                        }
                    )
                    response.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        response.exceptionId, response.reason ?: "Manager approval required"
                    )
                    response.nextAction == "recover_holding" -> IngredientScanOutcome.NeedsRecovery(response.reason)
                    else -> IngredientScanOutcome.Rejected(response.reason ?: "Ingredient scan rejected")
                }
                Result.success(outcome)
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }
```

Note: `requiredQty` is mapped from `line.requiredQuantity`, not `line.plannedQuantity` — unlike `lookupJob`'s mapping from `bom_loaded` (which has no `requiredQuantity` field, only `plannedQuantity`). `requiredQuantity` is the backend's post-adjustment "true" requirement after any approved extra/short-bag exceptions; `plannedQuantity` stays the original SAP-planned amount. Using `requiredQuantity` here keeps the progress bar accurate once exceptions have been approved.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: PASS — all tests green, including the 7 new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): add MixingUseCase.scanIngredient against the real ingredient_scanned contract"
```

---

### Task 3: MixingUseCase.approveManagerException

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `ManagerApprovalRequest`, `ManagerApprovalResultResponse` (Task 1).
- Produces: `MixingUseCase.approveManagerException(exceptionId: String, preMixId: String, palletRfidTag: String, managerUsername: String, managerPassword: String, reason: String): Result<String>` (the `String` is the returned `approvalId`) — Task 6 calls this exact signature.

- [ ] **Step 1: Write the failing tests**

Add to `MixingUseCaseTest.kt`, directly after the `scanIngredient` tests block (before `// --- validateIngredient ---`):

```kotlin
    // --- approveManagerException ---

    @Test
    fun `approveManagerException returns the approvalId on success`() = runTest {
        val response = ManagerApprovalResultResponse(accepted = true, approvalId = "approval-1")
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "Operator requested override"
        )

        assertTrue(result.isSuccess)
        assertEquals("approval-1", result.getOrThrow())
    }

    @Test
    fun `approveManagerException sends the exception and pallet in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "Operator requested override"
        )

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("manager_approval_requested"), eq("manager_approval_result"), captor.capture(),
            eq(ManagerApprovalResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"approvalTargetId\":\"exception-1\""))
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
        assertTrue(captor.firstValue.contains("\"managerUsername\":\"manager1\""))
        assertTrue(captor.firstValue.contains("\"managerPassword\":\"5678\""))
    }

    @Test
    fun `approveManagerException returns failure with backend reason when denied`() = runTest {
        val response = ManagerApprovalResultResponse(accepted = false, reason = "Invalid manager credentials")
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "baduser", "badpass", "reason"
        )

        assertTrue(result.isFailure)
        assertEquals("Invalid manager credentials", result.exceptionOrNull()?.message)
    }

    @Test
    fun `approveManagerException returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("manager_approval_requested"), eq("manager_approval_result"), any(),
                eq(ManagerApprovalResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.approveManagerException(
            "exception-1", "premix-1", "EPC:300833", "manager1", "5678", "reason"
        )

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }
```

Add `com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerApprovalResultResponse` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: FAIL to compile — `useCase.approveManagerException(...)` does not exist yet.

- [ ] **Step 3: Implement approveManagerException**

Add this import to `MixingUseCase.kt`:

```kotlin
import com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerApprovalRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerApprovalResultResponse
```

Add this method directly after `scanIngredient`:

```kotlin
    suspend fun approveManagerException(
        exceptionId: String,
        preMixId: String,
        palletRfidTag: String,
        managerUsername: String,
        managerPassword: String,
        reason: String
    ): Result<String> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            ManagerApprovalRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = exceptionId,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                approvalTargetId = exceptionId,
                preMixId = preMixId,
                palletRfidTag = palletRfidTag,
                reason = reason
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "manager_approval_requested",
            responseType = "manager_approval_result",
            requestJson = requestJson,
            responseClass = ManagerApprovalResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(response.approvalId)
                else Result.failure(Exception(response.reason ?: "Approval denied"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: PASS — all tests green, including the 4 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): add MixingUseCase.approveManagerException against manager_approval_requested"
```

---

### Task 4: MixingUseCase.recoverHolding

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `HoldingRecoveryRequest`, `HoldingRecoveryResultResponse` (Task 1).
- Produces: `MixingUseCase.recoverHolding(preMixId: String, palletRfidTag: String): Result<Unit>` — Task 6 calls this exact signature.

- [ ] **Step 1: Write the failing tests**

Add to `MixingUseCaseTest.kt`, directly after the `approveManagerException` tests block:

```kotlin
    // --- recoverHolding ---

    @Test
    fun `recoverHolding succeeds when the pallet is recovered`() = runTest {
        val response = HoldingRecoveryResultResponse(accepted = true, nextAction = "scan_ingredient")
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `recoverHolding sends preMixId and palletRfidTag in the request`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.recoverHolding("premix-1", "EPC:300833")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("holding_recovery_requested"), eq("holding_recovery_result"), captor.capture(),
            eq(HoldingRecoveryResultResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
        assertTrue(captor.firstValue.contains("\"palletRfidTag\":\"EPC:300833\""))
    }

    @Test
    fun `recoverHolding returns failure with backend reason when rejected`() = runTest {
        val response = HoldingRecoveryResultResponse(accepted = false, reason = "Pallet is blocked", nextAction = "retry_recovery")
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

        assertTrue(result.isFailure)
        assertEquals("Pallet is blocked", result.exceptionOrNull()?.message)
    }

    @Test
    fun `recoverHolding returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("holding_recovery_requested"), eq("holding_recovery_result"), any(),
                eq(HoldingRecoveryResultResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.recoverHolding("premix-1", "EPC:300833")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }
```

Add `com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryResultResponse` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: FAIL to compile — `useCase.recoverHolding(...)` does not exist yet.

- [ ] **Step 3: Implement recoverHolding**

Add this import to `MixingUseCase.kt`:

```kotlin
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryResultResponse
```

Add this method directly after `approveManagerException`:

```kotlin
    suspend fun recoverHolding(preMixId: String, palletRfidTag: String): Result<Unit> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            HoldingRecoveryRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = preMixId,
                preMixId = preMixId,
                palletRfidTag = palletRfidTag
            )
        )

        val result = mqttRepository.sendTyped(
            requestType = "holding_recovery_requested",
            responseType = "holding_recovery_result",
            requestJson = requestJson,
            responseClass = HoldingRecoveryResultResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                if (response.accepted) Result.success(Unit)
                else Result.failure(Exception(response.reason ?: "Recovery rejected"))
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: PASS — all tests green, including the 4 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): add MixingUseCase.recoverHolding against holding_recovery_requested"
```

---

### Task 5: MixingViewModel — pallet-scan-driven ingredient flow

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Consumes: `MixingUseCase.scanIngredient`, `approveManagerException`, `recoverHolding` (Tasks 2-4); `IngredientScanOutcome` (Task 2); `ScanEvent.RfidTag` (existing).
- Produces: New `MixingUiState` variants `EnteringBagDetails(palletTag: String)`, `IngredientExceptionApproval(exceptionId: String, reason: String)`, `PalletRecoveryPrompt(palletTag: String)`. New `MixingViewModel` methods: `startListeningForPalletScans(orderNo: String)`, `cancelBagEntry()`, `confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double)`, `submitManagerApproval(managerUsername: String, managerPassword: String)`, `cancelManagerApproval()`, `confirmPalletRecovery()`, `dismissPalletRecovery()` — Task 6 (`IngredientScanScreen`) calls these exact signatures.

**Sequencing correction:** this task ADDS the new pallet-scan-driven states/methods *alongside* the existing RFID-tag-scan-count-based ones — it does **not** remove `IngredientInvalid`/`WaitingForSupervisor` or `startListeningForScans`/`discardInvalidIngredient`/`requestSupervisorOverride`/`submitSupervisorTag` yet. `IngredientScanScreen` (not rewired until Task 6) still calls the old methods and reads the old states; deleting them here would leave the module non-compiling between Task 5 and Task 6, violating this plan's own "every task stays green" constraint. The old flow, `_scannedIngredients`, `IngredientValidationResult`, and `completePremix`'s signature are all removed together in Task 7, once Task 6 has fully swapped `IngredientScanScreen` onto the new API. Leave `_scannedIngredients`/`scannedIngredients` and `completePremix` completely untouched in this task.

- [ ] **Step 1: Write the failing tests**

Add these 8 new tests to `MixingViewModelTest.kt` (append after the existing `lookupJob forwards preMixId to the use case` test, before `discardInvalidIngredient resets state to OrderLoaded`). Do **not** remove or modify any existing test in this file — `discardInvalidIngredient resets state to OrderLoaded`, `requestSupervisorOverride sets WaitingForSupervisor state`, the two `submitSupervisorTag` tests, and `cancelJob resets state and scanned ingredients on backend confirmation` all stay exactly as they are; they're removed in Task 7 alongside the methods they exercise:

```kotlin
    @Test
    fun `startListeningForPalletScans opens EnteringBagDetails on a pallet scan`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `cancelBagEntry returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        vm.cancelBagEntry()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `confirmIngredientScan on Accepted replaces order lines and returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0))
            .thenReturn(Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine))))

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(listOf(updatedLine), (state as MixingUiState.OrderLoaded).order.lines)
    }

    @Test
    fun `confirmIngredientScan on NeedsManagerApproval sets IngredientExceptionApproval state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsManagerApproval("exception-1", "Wrong material"))
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.IngredientExceptionApproval)
        assertEquals("exception-1", (state as MixingUiState.IngredientExceptionApproval).exceptionId)
        assertEquals("Wrong material", state.reason)
    }

    @Test
    fun `confirmIngredientScan on NeedsRecovery sets PalletRecoveryPrompt state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.PalletRecoveryPrompt)
        assertEquals("EPC:300833", (state as MixingUiState.PalletRecoveryPrompt).palletTag)
    }

    @Test
    fun `submitManagerApproval on success retries the pending scan with the approvalId`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsManagerApproval("exception-1", "Wrong material"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        whenever(mockUseCase.approveManagerException("exception-1", "premix-1", "EPC:300833", "manager1", "5678", any()))
            .thenReturn(Result.success("approval-1"))
        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, "approval-1")).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine)))
        )

        viewModel.submitManagerApproval("manager1", "5678")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase).scanIngredient("premix-1", "EPC:300833", "full", 2.0, "approval-1")
    }

    @Test
    fun `confirmPalletRecovery on success retries the pending scan`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        whenever(mockUseCase.recoverHolding("premix-1", "EPC:300833")).thenReturn(Result.success(Unit))
        val updatedLine = BomLine("MAT-001", "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0, "")).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.Accepted(listOf(updatedLine)))
        )

        viewModel.confirmPalletRecovery()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase).recoverHolding("premix-1", "EPC:300833")
    }

    @Test
    fun `dismissPalletRecovery returns to OrderLoaded without retrying`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.scanIngredient("premix-1", "EPC:300833", "full", 2.0)).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        viewModel.dismissPalletRecovery()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase, never()).recoverHolding(any(), any())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"`
Expected: FAIL to compile — the new tests reference `MixingUiState.EnteringBagDetails`/`IngredientExceptionApproval`/`PalletRecoveryPrompt` and `viewModel.startListeningForPalletScans`/`confirmIngredientScan`/`submitManagerApproval`/`confirmPalletRecovery`/`dismissPalletRecovery`/`cancelBagEntry`, none of which exist yet.

- [ ] **Step 3: Update MixingUiState and add the new ViewModel methods**

In `MixingViewModel.kt`, replace the `sealed class MixingUiState` block:

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

with (this ADDS the three new variants; `IngredientInvalid` and `WaitingForSupervisor` stay — `IngredientScanScreen` still constructs and matches on them until Task 6):

```kotlin
sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    object Cancelling : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class EnteringBagDetails(val palletTag: String) : MixingUiState()
    data class IngredientExceptionApproval(val exceptionId: String, val reason: String) : MixingUiState()
    data class PalletRecoveryPrompt(val palletTag: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}
```

Add these imports:

```kotlin
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
```

(`ProductionOrder` is likely already imported — leave the existing import line as-is if so.)

Add this new code directly after the existing `fun submitSupervisorTag(...)` method's closing brace (before `fun checkAndAllocateHopper`). Do **not** remove or modify `startListeningForScans`, `discardInvalidIngredient`, `requestSupervisorOverride`, or `submitSupervisorTag` — they stay exactly as they are, unused by any new code, until Task 7 deletes them once `IngredientScanScreen` no longer calls them:

```kotlin
    private data class PendingIngredientScan(
        val palletRfidTag: String,
        val bagSizeOption: String,
        val bagCount: Double
    )

    private var pendingScan: PendingIngredientScan? = null
    private var pendingExceptionId: String = ""

    fun startListeningForPalletScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                _uiState.value = MixingUiState.EnteringBagDetails(event.tagId)
            }
        }
    }

    fun cancelBagEntry() {
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.preMixId, palletTag, bagSizeOption, bagCount)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun submitManagerApproval(managerUsername: String, managerPassword: String) {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.approveManagerException(
                exceptionId = pendingExceptionId,
                preMixId = order.preMixId,
                palletRfidTag = scan.palletRfidTag,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                reason = "Operator-requested exception approval"
            )
                .onSuccess { approvalId -> retryPendingScan(order, approvalId) }
                .onFailure { e -> _supervisorError.trySend(e.message ?: "Approval failed") }
        }
    }

    fun cancelManagerApproval() {
        pendingScan = null
        pendingExceptionId = ""
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmPalletRecovery() {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.recoverHolding(order.preMixId, scan.palletRfidTag)
                .onSuccess { retryPendingScan(order, "") }
                .onFailure { e ->
                    pendingScan = null
                    _supervisorError.trySend(e.message ?: "Recovery failed")
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
        }
    }

    fun dismissPalletRecovery() {
        pendingScan = null
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    private fun handleScanOutcome(order: ProductionOrder, outcome: IngredientScanOutcome) {
        when (outcome) {
            is IngredientScanOutcome.Accepted -> {
                val updatedOrder = order.copy(lines = outcome.updatedLines)
                cachedOrder = updatedOrder
                pendingScan = null
                _uiState.value = MixingUiState.OrderLoaded(updatedOrder)
            }
            is IngredientScanOutcome.NeedsManagerApproval -> {
                pendingExceptionId = outcome.exceptionId
                _uiState.value = MixingUiState.IngredientExceptionApproval(outcome.exceptionId, outcome.reason)
            }
            is IngredientScanOutcome.NeedsRecovery -> {
                _uiState.value = MixingUiState.PalletRecoveryPrompt(pendingScan?.palletRfidTag ?: "")
            }
            is IngredientScanOutcome.Rejected -> {
                pendingScan = null
                _supervisorError.trySend(outcome.reason)
                _uiState.value = MixingUiState.OrderLoaded(order)
            }
        }
    }

    private fun retryPendingScan(order: ProductionOrder, approvalId: String) {
        val scan = pendingScan
        if (scan == null) {
            _uiState.value = MixingUiState.OrderLoaded(order)
            return
        }
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.preMixId, scan.palletRfidTag, scan.bagSizeOption, scan.bagCount, approvalId)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"`
Expected: PASS — all tests green: the 8 new tests plus every pre-existing test (including the 4 old supervisor-tag tests, unchanged and still exercising the still-present old methods).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): drive ingredient scanning through pallet scan + bag entry in MixingViewModel"
```

---

### Task 6: IngredientScanScreen — bag-entry sheet and new dialogs

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: `MixingUiState.EnteringBagDetails`, `IngredientExceptionApproval`, `PalletRecoveryPrompt` (Task 5); `MixingViewModel.startListeningForPalletScans`, `cancelBagEntry`, `confirmIngredientScan`, `submitManagerApproval`, `cancelManagerApproval`, `confirmPalletRecovery`, `dismissPalletRecovery` (Task 5); `BomLine.isBagFullyAllocated`, `remainingBags` (Task 1).

No new automated tests — Compose UI, consistent with every other screen in this codebase (none have dedicated test files). Verified by build + a manual run-through.

- [ ] **Step 1: Replace scan-count satisfied logic with quantity+bag logic**

In `IngredientScanScreen.kt`, replace:

```kotlin
    val allIngredientsSatisfied = (uiState as? MixingUiState.OrderLoaded)?.order?.lines?.all { bomLine ->
        bomLine.isFullyAllocated ||
            scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
    } ?: false
```

with:

```kotlin
    val allIngredientsSatisfied = (uiState as? MixingUiState.OrderLoaded)?.order?.lines?.all { bomLine ->
        bomLine.isBagFullyAllocated
    } ?: false
```

Remove the line `val scannedIngredients by viewModel.scannedIngredients.collectAsState()` — no longer read by this screen.

Replace, inside the `MixingUiState.OrderLoaded ->` branch:

```kotlin
                        val satisfiedCount = order.lines.count { bomLine ->
                            bomLine.isFullyAllocated ||
                                scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                        }
```

with:

```kotlin
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isBagFullyAllocated }
```

And inside the `items(order.lines) { bomLine -> ... }` block, replace:

```kotlin
                                val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                                val required = bomLine.requiredQty.toInt().coerceAtLeast(1)
                                val satisfied = bomLine.isFullyAllocated || scannedCount >= required
                                val fraction = if (bomLine.requiredQty > 0.0) {
                                    (bomLine.scannedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }
```

with:

```kotlin
                                val satisfied = bomLine.isBagFullyAllocated
                                val fraction = if (bomLine.requiredQty > 0.0) {
                                    (bomLine.scannedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }
```

And replace the label text condition:

```kotlin
                                            Text(
                                                text = if (bomLine.isFullyAllocated) {
                                                    "Fully Allocated"
                                                } else {
                                                    "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
```

with:

```kotlin
                                            Text(
                                                text = if (bomLine.isBagFullyAllocated) {
                                                    "Fully Allocated"
                                                } else {
                                                    "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
```

And the progress-bar visibility guard:

```kotlin
                                        if (!bomLine.isFullyAllocated) {
```

with:

```kotlin
                                        if (!bomLine.isBagFullyAllocated) {
```

- [ ] **Step 2: Replace the scan-start effect and remove the old invalid/supervisor UI branches**

Replace:

```kotlin
    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }
```

with:

```kotlin
    LaunchedEffect(orderNo) { viewModel.startListeningForPalletScans(orderNo) }
```

Remove the two `when (val state = uiState)` branches `is MixingUiState.IngredientInvalid -> { ... }` and `is MixingUiState.WaitingForSupervisor -> { ... }` in their entirety (they referenced the deleted states).

- [ ] **Step 3: Add bag-entry bottom sheet state and dialogs**

Add these imports:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
```

Add local state, directly after the existing `var managerPassword by remember { mutableStateOf("") }` line:

```kotlin
    var selectedBagSize by remember { mutableStateOf("full") }
    var bagCountText by remember { mutableStateOf("1") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }
```

Add these dialogs directly after the existing `showApprovalDialog` `AlertDialog` block (before `AppScaffold(`):

```kotlin
    val bagSizeOptions = listOf("1/4" to "1/4", "1/2" to "1/2", "3/4" to "3/4", "Full" to "full")

    if (uiState is MixingUiState.EnteringBagDetails) {
        val palletTag = (uiState as MixingUiState.EnteringBagDetails).palletTag
        AlertDialog(
            onDismissRequest = { viewModel.cancelBagEntry() },
            title = { Text("Bag size & count", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        bagSizeOptions.forEach { (label, value) ->
                            val selected = selectedBagSize == value
                            Text(
                                text = label,
                                color = if (selected) GraphiteSurface else TextMuted,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (selected) AmberPrimary else GraphiteSurfaceVariant)
                                    .clickable { selectedBagSize = value }
                                    .padding(vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    OutlinedTextField(
                        value = bagCountText,
                        onValueChange = { bagCountText = it },
                        label = { Text("Bag count") },
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
                    enabled = bagCountText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        val count = bagCountText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmIngredientScan(palletTag, selectedBagSize, count)
                        bagCountText = "1"
                        selectedBagSize = "full"
                    }
                ) { Text("Confirm Scan", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBagEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.IngredientExceptionApproval) {
        val exceptionReason = (uiState as MixingUiState.IngredientExceptionApproval).reason
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelManagerApproval()
                exceptionUsername = ""
                exceptionPassword = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(exceptionReason, color = TextMuted)
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
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exceptionUsername.isNotBlank() && exceptionPassword.isNotBlank(),
                    onClick = {
                        viewModel.submitManagerApproval(exceptionUsername, exceptionPassword)
                        exceptionUsername = ""
                        exceptionPassword = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelManagerApproval()
                        exceptionUsername = ""
                        exceptionPassword = ""
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
```

Add this import for `background`/`clickable` (check first — `androidx.compose.foundation.layout.*` is already imported; `background` and `clickable` need their own imports):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
```

- [ ] **Step 4: Build to verify**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests still pass (no test in this repo covers `IngredientScanScreen` directly, so no test count changes here).

- [ ] **Step 5: Manual smoke test**

Launch the app (`run` skill or `adb install`/launch), log in, load a job card with manually-collected BOM lines, and confirm: scanning an RFID tag opens the bag-size/count dialog; picking a size and count and confirming updates the ingredient list (requires a live backend that implements `ingredient_scanned` — if the backend doesn't yet, confirm at minimum that the dialog opens/closes correctly and the app doesn't crash on a failed/disconnected response).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(mixing): add bag-entry sheet, exception-approval and recovery dialogs to IngredientScanScreen"
```

---

### Task 7: Remove legacy ingredient-scanning code

**Files:**
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientValidationResult.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/PreMix.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt` (remove `validateIngredient`, `approveIngredientException`; change `completePremix` signature)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt` (remove `_scannedIngredients`/`scannedIngredients`; update `completePremix` call)
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt` (remove `validateIngredient`/`approveIngredientException` tests; update `completePremix` tests)
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (remove remaining `ScannedIngredient` references)

**Interfaces:**
- Produces: `MixingUseCase.completePremix(orderNo: String, hopperCode: String): Result<Unit>` (drops the `ingredients` parameter — nothing downstream of this plan calls the old 3-arg form, since Task 5 already stopped populating `_scannedIngredients` from scanning).

By this task, nothing in `main/` calls `validateIngredient`, `approveIngredientException`, `ScannedIngredient`, `PreMix`, or `IngredientValidationResult` — Task 5 replaced their only call sites. `completePremix`'s `ingredients` parameter is the one remaining loose end: it's part of the legacy, still-unmigrated `premix_complete_scanned` flow (out of scope per the design spec), but it must stop taking `List<ScannedIngredient>` once that type is deleted. This is a mechanical signature fix, not a functional migration of premix-complete.

- [ ] **Step 1: Write the failing tests for the new completePremix signature**

In `MixingUseCaseTest.kt`, replace the three tests under `// --- completePremix ---`:

```kotlin
    @Test
    fun `completePremix delegates to mqtt with hopperCode`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val result = useCase.completePremix(
            orderNo = "510019068",
            hopperCode = "H-01",
            ingredients = listOf(ScannedIngredient("TAG-001", "MAT-001", 50.0))
        )

        assertTrue(result.isSuccess)
        verify(mockMqtt).send(eq("complete-premix"), any())
    }

    @Test
    fun `completePremix fails when hopperCode is blank`() = runTest {
        val result = useCase.completePremix(
            orderNo = "510019068",
            hopperCode = "",
            ingredients = emptyList()
        )
        assertTrue(result.isFailure)
        assertEquals("Hopper code is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `completePremix includes exceptions array in payload`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val normal = ScannedIngredient("TAG-001", "MAT-001", 50.0)
        val exception = ScannedIngredient("TAG-BAD", "MAT-999", 1.0, isException = true, approvedBy = "Jane")
        useCase.completePremix("510019068", "H-01", listOf(normal, exception))

        val captor = argumentCaptor<String>()
        verify(mockMqtt).send(eq("complete-premix"), captor.capture())
        assertTrue(captor.firstValue.contains("\"exceptions\""))
        assertTrue(captor.firstValue.contains("TAG-BAD"))
        val occurrences = captor.firstValue.split("TAG-BAD").size - 1
        assertTrue("TAG-BAD must appear in both ingredients and exceptions arrays", occurrences >= 2)
        assertTrue(captor.firstValue.contains("TAG-001"))
    }
```

with:

```kotlin
    @Test
    fun `completePremix delegates to mqtt with hopperCode`() = runTest {
        whenever(mockMqtt.send(eq("complete-premix"), any()))
            .thenReturn(MqttResult.Success("{}"))

        val result = useCase.completePremix(orderNo = "510019068", hopperCode = "H-01")

        assertTrue(result.isSuccess)
        verify(mockMqtt).send(eq("complete-premix"), any())
    }

    @Test
    fun `completePremix fails when hopperCode is blank`() = runTest {
        val result = useCase.completePremix(orderNo = "510019068", hopperCode = "")
        assertTrue(result.isFailure)
        assertEquals("Hopper code is required", result.exceptionOrNull()?.message)
    }
```

Remove the `import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient` line and the entire `// --- validateIngredient ---` and `// --- approveIngredientException ---` test blocks (every `@Test` between the `checkHopper` tests' preceding comment and the `// --- checkHopper ---` comment — i.e. delete all tests from `validateIngredient returns Valid when WPF confirms ingredient` through `approveIngredientException fails when offline` inclusive, and their two section comments).

Also remove `import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult` from this test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: FAIL to compile — `completePremix(orderNo, hopperCode)` (2-arg) doesn't exist yet (only the 3-arg form does).

- [ ] **Step 3: Update MixingUseCase — remove legacy methods, fix completePremix**

In `MixingUseCase.kt`, remove the `validateIngredient` and `approveIngredientException` methods entirely (from `suspend fun validateIngredient(...)` through the closing brace of `approveIngredientException`, i.e. everything between `scanIngredient`/`approveManagerException`/`recoverHolding` and `checkHopper`). Remove the now-unused `import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult` and `import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient` lines, and the private `ApprovalResponse` data class (only used by the removed `approveIngredientException`).

Replace:

```kotlin
    suspend fun completePremix(
        orderNo: String,
        hopperCode: String,
        ingredients: List<ScannedIngredient>
    ): Result<Unit> {
        if (hopperCode.isBlank()) return Result.failure(Exception("Hopper code is required"))
        val exceptions = ingredients.filter { it.isException }
        val payload = gson.toJson(mapOf(
            "orderNo" to orderNo,
            "hopperCode" to hopperCode,
            "ingredients" to ingredients,
            "exceptions" to exceptions
        ))
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.failure(Exception("Queued: will send when online"))
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
```

with:

```kotlin
    suspend fun completePremix(orderNo: String, hopperCode: String): Result<Unit> {
        if (hopperCode.isBlank()) return Result.failure(Exception("Hopper code is required"))
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "hopperCode" to hopperCode))
        return when (val result = mqttRepository.send("complete-premix", payload)) {
            is MqttResult.Success -> Result.success(Unit)
            is MqttResult.Queued -> Result.failure(Exception("Queued: will send when online"))
            is MqttResult.Error -> Result.failure(Exception(result.message))
        }
    }
```

- [ ] **Step 4: Delete IngredientValidationResult.kt and PreMix.kt**

```bash
rm app/src/main/java/com/ppnam/station2aa/domain/model/IngredientValidationResult.kt
rm app/src/main/java/com/ppnam/station2aa/domain/model/PreMix.kt
```

- [ ] **Step 5: Remove the old RFID-tag-scan states/methods, _scannedIngredients, and fix completePremix call**

Task 5 deliberately left the old scan-count-based flow in place alongside the new pallet-scan flow, since `IngredientScanScreen` still depended on it until Task 6 rewired the screen. Now that Task 6 is done, remove it.

In `MixingViewModel.kt`, remove these two variants from `sealed class MixingUiState`:

```kotlin
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
```

Remove these four methods in their entirety: `startListeningForScans`, `discardInvalidIngredient`, `requestSupervisorOverride`, `submitSupervisorTag`.

Remove:

```kotlin
    private val _scannedIngredients = MutableStateFlow<List<ScannedIngredient>>(emptyList())
    val scannedIngredients: StateFlow<List<ScannedIngredient>> = _scannedIngredients.asStateFlow()
```

Remove `import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient` and `import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult` (the latter is already unused after Task 5).

Replace:

```kotlin
    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _hopperCode.value, _scannedIngredients.value)
                .onSuccess { _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE) }
```

with:

```kotlin
    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _hopperCode.value)
                .onSuccess { _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE) }
```

In `cancelJob`'s success branch, remove the line `_scannedIngredients.value = emptyList()` (there is nothing left to clear).

- [ ] **Step 6: Remove old-flow tests and fix remaining ScannedIngredient references in MixingViewModelTest**

In `MixingViewModelTest.kt`, remove these four tests in their entirety (they exercise the methods just deleted in Step 5): `discardInvalidIngredient resets state to OrderLoaded`, `requestSupervisorOverride sets WaitingForSupervisor state`, `submitSupervisorTag on approval appends exception ingredient and resets to OrderLoaded`, `submitSupervisorTag on rejection stays WaitingForSupervisor`.

Remove `import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient` and `import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult`.

Replace the `cancelJob resets state and scanned ingredients on backend confirmation` test (its `scannedIngredients` assertion no longer compiles once the field is removed from `MixingViewModel`):

```kotlin
    @Test
    fun `cancelJob resets state and scanned ingredients on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

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
```

with:

```kotlin
    @Test
    fun `cancelJob resets state on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.data.mqtt.dto.PreMixCancelResultResponse(accepted = true))
        )
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob()
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
        assertEquals("", viewModel.hopperCode.value)
        assertTrue(outcomes.contains(CancelOutcome.Confirmed))
        job.cancel()
    }
```

- [ ] **Step 7: Run the full test suite to verify everything passes**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/main/java/com/ppnam/station2aa/domain/model/IngredientValidationResult.kt app/src/main/java/com/ppnam/station2aa/domain/model/PreMix.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "refactor(mixing): remove legacy validate-ingredient/approve-ingredient-exception placeholder flow"
```
