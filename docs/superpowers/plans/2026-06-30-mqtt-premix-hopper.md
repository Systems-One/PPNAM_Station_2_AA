# MQTT Pre-Mix & Hopper Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the mixing workflow to support richer ingredient validation (valid/invalid with reason), a supervisor-gated exception override path, hopper allocation (replacing MixerCodeScreen), and live hopper status broadcast from WPF.

**Architecture:** Surgical extension of `MixingUseCase`, `MixingViewModel`, and existing mixing screens. All scanning continues through the DataWedge → `ScanEventBus` pipeline. MQTT transport remains app → WPF only; a second subscription is added for the `station2/hopper/status` broadcast topic. No new architectural layers.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT v5, Mockito-Kotlin, `kotlinx-coroutines-test`, JUnit 4.

## Global Constraints

- Package root: `com.mitas.ppnam.station2aa`
- All new domain models go in `domain/model/`
- Tests use Mockito-Kotlin (`mock()`, `whenever()`, `verify()`) — NOT MockK
- Tests use `runTest` + `UnconfinedTestDispatcher` matching existing patterns in `SettingsViewModelTest`
- Test runner: `./gradlew testDebugUnitTest` (run from repo root on Windows: `gradlew.bat testDebugUnitTest`)
- No manual text entry on `HopperScanScreen` — barcode scan only, WPF is the authority
- `isException = true` ingredients must still appear in the `ingredients[]` array in `complete-premix`; they are also duplicated into a separate `exceptions[]` array so WPF receives both pre-separated

---

## File Map

### New files
| File | Responsibility |
|------|---------------|
| `domain/model/HopperStatus.kt` | `HopperStatus` data class + `HopperAvailability` enum |
| `domain/model/IngredientValidationResult.kt` | Sealed class: `Valid(bomLine)` / `Invalid(tagId, reason)` |
| `ui/mixing/HopperScanScreen.kt` | New screen replacing `MixerCodeScreen` — live hopper chips + barcode scan |

### Modified files
| File | What changes |
|------|-------------|
| `domain/model/ProductionOrder.kt` | Add `valid: Boolean = true`, `reason: String? = null` to `BomLine` |
| `domain/model/PreMix.kt` | Add `isException: Boolean = false`, `approvedBy: String? = null` to `ScannedIngredient`; rename `mixerCode` → `hopperCode` on `PreMix` |
| `domain/repository/MqttRepository.kt` | Add `hopperStatusUpdates: SharedFlow<HopperStatus>` |
| `data/mqtt/MqttTopics.kt` | Add `hopperStatus(stationName)` topic helper |
| `data/mqtt/MqttRepositoryImpl.kt` | Subscribe to hopper status topic; expose `hopperStatusUpdates` |
| `domain/usecase/MixingUseCase.kt` | Update `validateIngredient` return type; add `approveIngredientException`, `checkHopper`; update `completePremix` |
| `ui/mixing/MixingViewModel.kt` | New `MixingUiState` variants; rename `_mixerCode`→`_hopperCode`; new methods; expose `hopperStatusUpdates` |
| `ui/mixing/IngredientScanScreen.kt` | Add `IngredientInvalid` and `WaitingForSupervisor` overlays; rename proceed callback |
| `ui/mixing/PreMixCompleteScreen.kt` | `mixerCode` → `hopperCode`; collect `hopperCode` state |
| `navigation/NavRoutes.kt` | Replace `MIXER_CODE`/`mixerCode()` with `HOPPER_SCAN`/`hopperScan()` |
| `navigation/AppNavGraph.kt` | Replace `MixerCodeScreen` composable with `HopperScanScreen` |

### Deleted files
| File | Reason |
|------|--------|
| `ui/mixing/MixerCodeScreen.kt` | Fully replaced by `HopperScanScreen` |

### Test files
| File | What changes |
|------|-------------|
| `test/.../domain/usecase/MixingUseCaseTest.kt` | Add tests for updated `validateIngredient`, `approveIngredientException`, `checkHopper`, updated `completePremix`; update existing `completePremix` test |
| `test/.../ui/mixing/MixingViewModelTest.kt` | New file — test all new ViewModel state transitions |

---

## Task 1: Domain Models

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/PreMix.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/HopperStatus.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientValidationResult.kt`

**Interfaces:**
- Produces: `BomLine(valid, reason)`, `ScannedIngredient(isException, approvedBy)`, `PreMix(hopperCode)`, `HopperStatus`, `HopperAvailability`, `IngredientValidationResult.Valid`, `IngredientValidationResult.Invalid` — used by every subsequent task

- [ ] **Step 1: Update `BomLine` in `ProductionOrder.kt`**

Replace the existing `BomLine` data class:

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

- [ ] **Step 2: Update `PreMix.kt`**

Replace the entire file content:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import java.time.Instant

data class PreMix(
    val id: String,
    val jobCardNo: String,
    val hopperCode: String,
    val ingredients: List<ScannedIngredient>,
    val status: PreMixStatus,
    val createdAt: Instant
)

data class ScannedIngredient(
    val tagId: String,
    val itemCode: String,
    val qty: Double,
    val isException: Boolean = false,
    val approvedBy: String? = null
)

enum class PreMixStatus { IN_PROGRESS, COMPLETE, ALLOCATED }
```

- [ ] **Step 3: Create `HopperStatus.kt`**

```kotlin
package com.mitas.ppnam.station2aa.domain.model

data class HopperStatus(
    val hopperCode: String,
    val status: HopperAvailability,
    val assignedTo: String? = null
)

enum class HopperAvailability { AVAILABLE, IN_USE, OFFLINE }
```

- [ ] **Step 4: Create `IngredientValidationResult.kt`**

```kotlin
package com.mitas.ppnam.station2aa.domain.model

sealed class IngredientValidationResult {
    data class Valid(val bomLine: BomLine) : IngredientValidationResult()
    data class Invalid(val tagId: String, val reason: String) : IngredientValidationResult()
}
```

- [ ] **Step 5: Verify the project still compiles**

```
gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL (compilation errors in use case / ViewModel are acceptable at this stage — they reference `mixerCode` which no longer exists on `PreMix`)

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/domain/model/
git commit -m "feat(domain): add HopperStatus, IngredientValidationResult; extend BomLine and ScannedIngredient"
```

---

## Task 2: MQTT Hopper Broadcast Subscription

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `HopperStatus`, `HopperAvailability` from Task 1
- Produces: `MqttRepository.hopperStatusUpdates: SharedFlow<HopperStatus>` — consumed by `MixingViewModel` in Task 4

- [ ] **Step 1: Add hopper status topic helper to `MqttTopics.kt`**

Add this function to the `MqttTopics` object:

```kotlin
fun hopperStatus(stationName: String): String =
    "${stationName.trim().lowercase().replace(" ", "")}/hopper/status"
```

- [ ] **Step 2: Add `hopperStatusUpdates` to `MqttRepository` interface**

The full updated interface in `MqttRepository.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.repository

import com.mitas.ppnam.station2aa.data.mqtt.MqttResult
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    val hopperStatusUpdates: SharedFlow<HopperStatus>
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun connect()
    fun disconnect()
    suspend fun reconnectWith(settings: AppSettings): Result<Unit>
}
```

- [ ] **Step 3: Implement `hopperStatusUpdates` in `MqttRepositoryImpl`**

Add the backing field after the `_incomingResponses` declaration (line 48):

```kotlin
private val _hopperStatusUpdates = MutableSharedFlow<HopperStatus>(replay = 1, extraBufferCapacity = 16)
override val hopperStatusUpdates: SharedFlow<HopperStatus> = _hopperStatusUpdates.asSharedFlow()
```

Add the hopper status subscription inside `connect()`, immediately after the existing `subscribeWith` call (after line 73):

```kotlin
client.subscribeWith()
    .topicFilter(MqttTopics.hopperStatus(currentStationName))
    .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
    .send()
    .await()
```

Add the same subscription inside `reconnectWith()`, after the existing `subscribeWith` block (after line 101):

```kotlin
candidate.subscribeWith()
    .topicFilter(MqttTopics.hopperStatus(settings.stationName))
    .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
    .send()
    .await()
```

Add the handler function at the bottom of the class, alongside `handleIncoming`:

```kotlin
private fun handleHopperStatus(bytes: ByteArray) {
    try {
        val status = gson.fromJson(String(bytes), HopperStatus::class.java)
        _hopperStatusUpdates.tryEmit(status)
    } catch (_: Exception) { }
}
```

Add the missing import at the top of `MqttRepositoryImpl.kt`:

```kotlin
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
```

- [ ] **Step 4: Write failing tests in `MqttRepositoryImplTest.kt`**

Open `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt` and add these two tests (add to the existing test class, do not replace existing tests):

```kotlin
@Test
fun `hopperStatusUpdates emits parsed HopperStatus on hopper topic message`() = runTest {
    // Call handleHopperStatus via reflection — it's internal
    val json = """{"hopperCode":"H-01","status":"AVAILABLE","assignedTo":null}"""
    val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
    method.isAccessible = true
    method.invoke(repository, json.toByteArray())

    val emitted = repository.hopperStatusUpdates.replayCache.firstOrNull()
    assertNotNull(emitted)
    assertEquals("H-01", emitted!!.hopperCode)
    assertEquals(HopperAvailability.AVAILABLE, emitted.status)
}

@Test
fun `hopperStatusUpdates does not crash on malformed payload`() = runTest {
    val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
    method.isAccessible = true
    // Should not throw
    method.invoke(repository, "not-json".toByteArray())
    assertTrue(repository.hopperStatusUpdates.replayCache.isEmpty())
}
```

Add these imports at the top of `MqttRepositoryImplTest.kt`:

```kotlin
import com.mitas.ppnam.station2aa.domain.model.HopperAvailability
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
```

- [ ] **Step 5: Run the new tests**

```
gradlew.bat testDebugUnitTest --tests "*.MqttRepositoryImplTest"
```

Expected: new tests PASS (the reflection approach tests internal behaviour without changing the method visibility)

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt
git add app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt
git add app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "feat(mqtt): subscribe to hopper/status broadcast topic; expose hopperStatusUpdates flow"
```

---

## Task 3: MixingUseCase — Full Update

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Modify: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `IngredientValidationResult`, `ScannedIngredient(isException, approvedBy)` from Task 1; `MqttRepository.send()` unchanged
- Produces:
  - `validateIngredient(orderNo, tagId): Result<IngredientValidationResult>`
  - `approveIngredientException(orderNo, tagId, supervisorTagId): Result<ScannedIngredient>`
  - `checkHopper(orderNo, hopperCode): Result<Unit>`
  - `completePremix(orderNo, hopperCode, ingredients): Result<Unit>` (parameter `mixerCode` renamed to `hopperCode`)

- [ ] **Step 1: Write failing tests first**

Replace the entire content of `MixingUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.local.BomCacheDao
import com.mitas.ppnam.station2aa.data.mqtt.MqttResult
import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult
import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockBomCacheDao: BomCacheDao
    private lateinit var useCase: MixingUseCase

    private val bomJson = """{"docNo":"510019068","itemCode":"9000002064","plannedQty":100.0,
        "lines":[{"itemCode":"MAT-001","itemName":"Resin","requiredQty":50.0,"scannedQty":0.0}]}"""

    @Before
    fun setup() {
        mockMqtt = mock()
        mockBomCacheDao = mock()
        useCase = MixingUseCase(mockMqtt, mockBomCacheDao)
    }

    // --- lookupJob ---

    @Test
    fun `lookupJob success caches bom and returns ProductionOrder`() = runTest {
        whenever(mockMqtt.send("lookup-job", """{"orderNo":"510019068"}"""))
            .thenReturn(MqttResult.Success(bomJson))
        whenever(mockBomCacheDao.put(any())).thenReturn(Unit)

        val result = useCase.lookupJob("510019068")

        assertTrue(result.isSuccess)
        assertEquals("510019068", result.getOrThrow().docNo)
        verify(mockBomCacheDao).put(any())
    }

    @Test
    fun `lookupJob returns failure on MQTT error`() = runTest {
        whenever(mockMqtt.send("lookup-job", """{"orderNo":"510019068"}"""))
            .thenReturn(MqttResult.Error("Not found"))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
    }

    // --- validateIngredient ---

    @Test
    fun `validateIngredient returns Valid when WPF confirms ingredient`() = runTest {
        val bomLineJson = """{"itemCode":"MAT-001","itemName":"Resin","requiredQty":50.0,"valid":true}"""
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Success(bomLineJson))

        val result = useCase.validateIngredient("510019068", "TAG-001")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Valid)
        assertEquals("MAT-001", (validation as IngredientValidationResult.Valid).bomLine.itemCode)
    }

    @Test
    fun `validateIngredient returns Invalid when WPF rejects ingredient`() = runTest {
        val bomLineJson = """{"itemCode":"MAT-999","itemName":"Unknown","requiredQty":0.0,"valid":false,"reason":"Not in BOM"}"""
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Success(bomLineJson))

        val result = useCase.validateIngredient("510019068", "TAG-BAD")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Invalid)
        assertEquals("TAG-BAD", (validation as IngredientValidationResult.Invalid).tagId)
        assertEquals("Not in BOM", validation.reason)
    }

    @Test
    fun `validateIngredient returns optimistic Valid when queued offline`() = runTest {
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Queued("offline-corr-id"))

        val result = useCase.validateIngredient("510019068", "EPC-HEX-TAG")

        assertTrue(result.isSuccess)
        val validation = result.getOrThrow()
        assertTrue(validation is IngredientValidationResult.Valid)
        assertEquals("EPC-HEX-TAG", (validation as IngredientValidationResult.Valid).bomLine.itemCode)
        assertEquals("Offline scan", validation.bomLine.itemName)
    }

    @Test
    fun `validateIngredient returns failure on MQTT error`() = runTest {
        whenever(mockMqtt.send(eq("validate-ingredient"), any()))
            .thenReturn(MqttResult.Error("Server error"))

        val result = useCase.validateIngredient("510019068", "TAG-001")
        assertTrue(result.isFailure)
    }

    // --- approveIngredientException ---

    @Test
    fun `approveIngredientException returns exception ScannedIngredient on approval`() = runTest {
        val responseJson = """{"approved":true,"supervisorName":"Jane Smith","reason":null}"""
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "SUP-TAG-001")

        assertTrue(result.isSuccess)
        val ingredient = result.getOrThrow()
        assertEquals("TAG-BAD", ingredient.tagId)
        assertTrue(ingredient.isException)
        assertEquals("Jane Smith", ingredient.approvedBy)
    }

    @Test
    fun `approveIngredientException returns failure when supervisor not authorised`() = runTest {
        val responseJson = """{"approved":false,"supervisorName":null,"reason":"Tag not a supervisor"}"""
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "NOT-SUP-TAG")

        assertTrue(result.isFailure)
        assertEquals("Tag not a supervisor", result.exceptionOrNull()?.message)
    }

    @Test
    fun `approveIngredientException fails when offline`() = runTest {
        whenever(mockMqtt.send(eq("approve-ingredient-exception"), any()))
            .thenReturn(MqttResult.Queued("q-id"))

        val result = useCase.approveIngredientException("510019068", "TAG-BAD", "SUP-TAG")
        assertTrue(result.isFailure)
        assertEquals("Supervisor approval requires a connection", result.exceptionOrNull()?.message)
    }

    // --- checkHopper ---

    @Test
    fun `checkHopper returns success when hopper is available`() = runTest {
        val responseJson = """{"available":true,"hopperCode":"H-01","reason":null}"""
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkHopper returns failure when hopper is unavailable`() = runTest {
        val responseJson = """{"available":false,"hopperCode":"H-01","reason":"Already in use"}"""
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Success(responseJson))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isFailure)
        assertEquals("Already in use", result.exceptionOrNull()?.message)
    }

    @Test
    fun `checkHopper fails when offline`() = runTest {
        whenever(mockMqtt.send(eq("check-hopper"), any()))
            .thenReturn(MqttResult.Queued("q-id"))

        val result = useCase.checkHopper("510019068", "H-01")
        assertTrue(result.isFailure)
        assertEquals("Hopper check requires a connection", result.exceptionOrNull()?.message)
    }

    // --- completePremix ---

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
    }
}
```

- [ ] **Step 2: Run tests — expect failures**

```
gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"
```

Expected: FAIL — `validateIngredient` still returns `Result<BomLine>`, new methods don't exist yet.

- [ ] **Step 3: Implement the updated `MixingUseCase`**

Replace the entire file at `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.local.BomCacheDao
import com.mitas.ppnam.station2aa.data.local.BomCacheEntity
import com.mitas.ppnam.station2aa.data.mqtt.MqttResult
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao
) {
    private val gson = Gson()

    suspend fun lookupJob(orderNo: String): Result<ProductionOrder> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo))
        return when (val result = mqttRepository.send("lookup-job", payload)) {
            is MqttResult.Success -> {
                val order = gson.fromJson(result.dataJson, ProductionOrder::class.java)
                bomCacheDao.put(BomCacheEntity(orderNo, result.dataJson, Instant.now().toEpochMilli()))
                Result.success(order)
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("No connection — reconnecting"))
        }
    }

    suspend fun validateIngredient(orderNo: String, tagId: String): Result<IngredientValidationResult> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "tagId" to tagId))
        return when (val result = mqttRepository.send("validate-ingredient", payload)) {
            is MqttResult.Success -> {
                val bomLine = gson.fromJson(result.dataJson, BomLine::class.java)
                if (bomLine.valid) {
                    Result.success(IngredientValidationResult.Valid(bomLine))
                } else {
                    Result.success(IngredientValidationResult.Invalid(tagId, bomLine.reason ?: "Invalid ingredient"))
                }
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.success(
                IngredientValidationResult.Valid(
                    BomLine(itemCode = tagId, itemName = "Offline scan", requiredQty = 1.0)
                )
            )
        }
    }

    suspend fun approveIngredientException(
        orderNo: String,
        tagId: String,
        supervisorTagId: String
    ): Result<ScannedIngredient> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "tagId" to tagId, "supervisorTagId" to supervisorTagId))
        return when (val result = mqttRepository.send("approve-ingredient-exception", payload)) {
            is MqttResult.Success -> {
                val response = gson.fromJson(result.dataJson, ExceptionApprovalResponse::class.java)
                if (response.approved) {
                    Result.success(
                        ScannedIngredient(
                            tagId = tagId,
                            itemCode = tagId,
                            qty = 1.0,
                            isException = true,
                            approvedBy = response.supervisorName
                        )
                    )
                } else {
                    Result.failure(Exception(response.reason ?: "Approval denied"))
                }
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("Supervisor approval requires a connection"))
        }
    }

    suspend fun checkHopper(orderNo: String, hopperCode: String): Result<Unit> {
        val payload = gson.toJson(mapOf("orderNo" to orderNo, "hopperCode" to hopperCode))
        return when (val result = mqttRepository.send("check-hopper", payload)) {
            is MqttResult.Success -> {
                val response = gson.fromJson(result.dataJson, HopperCheckResponse::class.java)
                if (response.available) Result.success(Unit)
                else Result.failure(Exception(response.reason ?: "Hopper unavailable"))
            }
            is MqttResult.Error -> Result.failure(Exception(result.message))
            is MqttResult.Queued -> Result.failure(Exception("Hopper check requires a connection"))
        }
    }

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

    private data class ExceptionApprovalResponse(
        val approved: Boolean,
        val supervisorName: String?,
        val reason: String?
    )

    private data class HopperCheckResponse(
        val available: Boolean,
        val hopperCode: String,
        val reason: String?
    )
}
```

- [ ] **Step 4: Run tests — expect all pass**

```
gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"
```

Expected: all 13 tests PASS

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt
git add app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(usecase): update MixingUseCase — richer validation, exception approval, hopper check"
```

---

## Task 4: MixingViewModel — New States and Exception Flow

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Create: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Consumes: `IngredientValidationResult.Valid/Invalid`, `approveIngredientException`, `checkHopper`, `completePremix(hopperCode)` from Task 3; `hopperStatusUpdates: SharedFlow<HopperStatus>` from Task 2
- Produces: new `MixingUiState` variants; `hopperCode: StateFlow<String>`; `hopperStatusUpdates: SharedFlow<HopperStatus>` — consumed by screens in Tasks 5–7

- [ ] **Step 1: Write the failing ViewModel tests**

Create `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.domain.model.HopperAvailability
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        itemCode = "9000002064",
        plannedQty = 100.0,
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

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `lookupJob success sets OrderLoaded state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `lookupJob failure sets Error state`() = runTest {
        whenever(mockUseCase.lookupJob(any())).thenReturn(Result.failure(Exception("Not found")))
        viewModel.lookupJob("bad")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.Error)
        assertEquals("Not found", (state as MixingUiState.Error).message)
    }

    @Test
    fun `discardInvalidIngredient resets state to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.discardInvalidIngredient()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `requestSupervisorOverride sets WaitingForSupervisor state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.WaitingForSupervisor)
        assertEquals("TAG-BAD", (state as MixingUiState.WaitingForSupervisor).tagId)
    }

    @Test
    fun `submitSupervisorTag on approval appends exception ingredient and resets to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val exceptionIngredient = ScannedIngredient("TAG-BAD", "MAT-999", 1.0, isException = true, approvedBy = "Jane")
        whenever(mockUseCase.approveIngredientException("510019068", "TAG-BAD", "SUP-001"))
            .thenReturn(Result.success(exceptionIngredient))

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-BAD", "SUP-001")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        assertTrue(viewModel.scannedIngredients.value.any { it.isException && it.tagId == "TAG-BAD" })
    }

    @Test
    fun `submitSupervisorTag on rejection stays WaitingForSupervisor`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.approveIngredientException(any(), any(), any()))
            .thenReturn(Result.failure(Exception("Tag not a supervisor")))

        viewModel.requestSupervisorOverride("TAG-BAD", "Not in BOM")
        viewModel.submitSupervisorTag("510019068", "TAG-BAD", "NOT-SUP")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.WaitingForSupervisor)
    }

    @Test
    fun `checkAndAllocateHopper on success sets hopperCode and fires nav event`() = runTest {
        whenever(mockUseCase.checkHopper("510019068", "H-01")).thenReturn(Result.success(Unit))

        val navEvents = mutableListOf<String>()
        val job = kotlinx.coroutines.launch(testDispatcher) {
            viewModel.navigationEvent.collect { navEvents.add(it) }
        }

        viewModel.checkAndAllocateHopper("510019068", "H-01")
        advanceUntilIdle()

        assertEquals("H-01", viewModel.hopperCode.value)
        assertTrue(navEvents.contains(MixingNavDestination.PREMIX_COMPLETE))
        job.cancel()
    }

    @Test
    fun `checkAndAllocateHopper on failure sets HopperUnavailable state`() = runTest {
        whenever(mockUseCase.checkHopper(any(), any()))
            .thenReturn(Result.failure(Exception("Already in use")))

        viewModel.checkAndAllocateHopper("510019068", "H-01")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.HopperUnavailable)
        assertEquals("Already in use", (state as MixingUiState.HopperUnavailable).reason)
    }
}
```

- [ ] **Step 2: Run tests — expect failures**

```
gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"
```

Expected: FAIL — `hopperCode`, `requestSupervisorOverride`, `submitSupervisorTag`, `checkAndAllocateHopper`, `HopperUnavailable`, `WaitingForSupervisor` don't exist yet.

- [ ] **Step 3: Implement the updated `MixingViewModel`**

Replace the entire file at `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import com.mitas.ppnam.station2aa.domain.model.IngredientValidationResult
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.model.ScannedIngredient
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}

object MixingNavDestination {
    const val PREMIX_COMPLETE = "premix_complete"
    const val HOME = "home"
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    private val _scannedIngredients = MutableStateFlow<List<ScannedIngredient>>(emptyList())
    val scannedIngredients: StateFlow<List<ScannedIngredient>> = _scannedIngredients.asStateFlow()

    private val _hopperCode = MutableStateFlow("")
    val hopperCode: StateFlow<String> = _hopperCode.asStateFlow()

    private val _isQueuedOffline = MutableStateFlow(false)
    val isQueuedOffline: StateFlow<Boolean> = _isQueuedOffline.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val hopperStatusUpdates: SharedFlow<HopperStatus> = mqttRepository.hopperStatusUpdates

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private val _supervisorError = Channel<String>(Channel.BUFFERED)
    val supervisorError: Flow<String> = _supervisorError.receiveAsFlow()

    private var scanJob: Job? = null
    private var currentOrderNo: String = ""
    private var cachedOrder: ProductionOrder? = null

    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                useCase.validateIngredient(orderNo, event.tagId)
                    .onSuccess { validation ->
                        when (validation) {
                            is IngredientValidationResult.Valid -> {
                                val ingredient = ScannedIngredient(
                                    tagId = event.tagId,
                                    itemCode = validation.bomLine.itemCode,
                                    qty = 1.0
                                )
                                _scannedIngredients.update { it + ingredient }
                            }
                            is IngredientValidationResult.Invalid -> {
                                scanJob?.cancel()
                                _uiState.value = MixingUiState.IngredientInvalid(
                                    tagId = validation.tagId,
                                    reason = validation.reason
                                )
                            }
                        }
                    }
                    .onFailure { e ->
                        _uiState.value = MixingUiState.Error(e.message ?: "Validation failed")
                    }
            }
        }
    }

    fun discardInvalidIngredient() {
        val order = cachedOrder ?: run {
            _uiState.value = MixingUiState.Error("Session lost — please re-scan job card")
            return
        }
        startListeningForScans(currentOrderNo)
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun requestSupervisorOverride(tagId: String, reason: String) {
        scanJob?.cancel()
        _uiState.value = MixingUiState.WaitingForSupervisor(tagId, reason)
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                val pendingState = _uiState.value
                if (pendingState is MixingUiState.WaitingForSupervisor) {
                    submitSupervisorTag(currentOrderNo, pendingState.tagId, event.tagId)
                }
            }
        }
    }

    fun submitSupervisorTag(orderNo: String, tagId: String, supervisorTagId: String) {
        scanJob?.cancel()
        viewModelScope.launch {
            useCase.approveIngredientException(orderNo, tagId, supervisorTagId)
                .onSuccess { ingredient ->
                    _scannedIngredients.update { it + ingredient }
                    startListeningForScans(orderNo)
                    cachedOrder?.let { _uiState.value = MixingUiState.OrderLoaded(it) }
                }
                .onFailure { e ->
                    _supervisorError.trySend(e.message ?: "Approval failed")
                    requestSupervisorOverride(tagId, (_uiState.value as? MixingUiState.WaitingForSupervisor)?.reason ?: "")
                }
        }
    }

    fun checkAndAllocateHopper(orderNo: String, hopperCode: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.checkHopper(orderNo, hopperCode)
                .onSuccess {
                    _hopperCode.value = hopperCode
                    _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE)
                }
                .onFailure { e ->
                    _uiState.value = MixingUiState.HopperUnavailable(hopperCode, e.message ?: "Unavailable")
                }
        }
    }

    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _hopperCode.value, _scannedIngredients.value)
                .onSuccess { _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE) }
                .onFailure { e ->
                    if (e.message?.startsWith("Queued") == true) {
                        _isQueuedOffline.value = true
                        _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE)
                    } else {
                        _uiState.value = MixingUiState.Error(e.message ?: "Failed to complete pre-mix")
                    }
                }
        }
    }

    fun clearError() {
        if (_uiState.value is MixingUiState.Error) _uiState.value = MixingUiState.Idle
    }
}
```

- [ ] **Step 4: Run ViewModel tests — expect all pass**

```
gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"
```

Expected: all 8 tests PASS

- [ ] **Step 5: Run full test suite to check for regressions**

```
gradlew.bat testDebugUnitTest
```

Expected: all tests PASS

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt
git add app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(viewmodel): update MixingViewModel — exception flow, hopper allocation, WaitingForSupervisor state"
```

---

## Task 5: Navigation, Routes, and PreMixCompleteScreen Rename

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt` (callback rename only)
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixerCodeScreen.kt`

**Interfaces:**
- Consumes: `HopperScanScreen` composable from Task 6 (wire in AppNavGraph)
- Note: `HopperScanScreen` does not exist yet — add the route to `NavRoutes` and `AppNavGraph` now but the composable import will be added in Task 6.

- [ ] **Step 1: Update `NavRoutes.kt`**

Replace the entire file:

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val HOPPER_SCAN = "mixing/hopper_scan/{orderNo}"
    const val PREMIX_COMPLETE = "mixing/premix_complete/{orderNo}"
    const val MACHINE_SELECT = "rajoo/machine_select"
    const val PALLET_ALLOC = "rajoo/pallet_alloc/{machineCode}"
    const val RFID_RECOVERY = "rfid/recovery"
    const val DASHBOARD = "dashboard"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"
    fun hopperScan(orderNo: String) = "mixing/hopper_scan/$orderNo"
    fun premixComplete(orderNo: String) = "mixing/premix_complete/$orderNo"
    fun palletAlloc(machineCode: String) = "rajoo/pallet_alloc/$machineCode"
}
```

- [ ] **Step 2: Update `IngredientScanScreen.kt` — rename callback and button label**

Change the composable signature parameter from `onProceedToMixerCode` to `onProceedToHopperScan`:

```kotlin
@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToHopperScan: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
)
```

Change the Button `onClick` and label at the bottom of the screen (currently line 164–169):

```kotlin
Button(
    onClick = onProceedToHopperScan,
    enabled = scannedIngredients.isNotEmpty(),
    modifier = Modifier.fillMaxWidth().height(56.dp)
) {
    Text("Proceed to Hopper Scan")
}
```

- [ ] **Step 3: Update `PreMixCompleteScreen.kt` — rename `mixerCode` to `hopperCode`**

Line 34 — change:
```kotlin
val mixerCode by viewModel.mixerCode.collectAsState()
```
to:
```kotlin
val hopperCode by viewModel.hopperCode.collectAsState()
```

Line 86 — change the chip label:
```kotlin
label = { Text("Hopper: $hopperCode", color = TextPrimary) },
```

- [ ] **Step 4: Update `AppNavGraph.kt`**

Replace the entire file:

```kotlin
package com.mitas.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mitas.ppnam.station2aa.ui.dashboard.DashboardScreen
import com.mitas.ppnam.station2aa.ui.home.HomeScreen
import com.mitas.ppnam.station2aa.ui.mixing.HopperScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.mitas.ppnam.station2aa.ui.mixing.PreMixCompleteScreen
import com.mitas.ppnam.station2aa.ui.rajoo.MachineSelectScreen
import com.mitas.ppnam.station2aa.ui.rajoo.PalletAllocScreen
import com.mitas.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.mitas.ppnam.station2aa.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NavRoutes.HOME) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateMixing = { navController.navigate(NavRoutes.JOB_LOOKUP) },
                onNavigateRajoo = { navController.navigate(NavRoutes.MACHINE_SELECT) },
                onNavigateRfidRecovery = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onNavigateDashboard = { navController.navigate(NavRoutes.DASHBOARD) },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.JOB_LOOKUP) {
            JobLookupScreen(
                onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.INGREDIENT_SCAN) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            IngredientScanScreen(
                orderNo = orderNo,
                onProceedToHopperScan = { navController.navigate(NavRoutes.hopperScan(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.HOPPER_SCAN) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            HopperScanScreen(
                orderNo = orderNo,
                onProceed = { navController.navigate(NavRoutes.premixComplete(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.PREMIX_COMPLETE) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            PreMixCompleteScreen(
                orderNo = orderNo,
                onCompleted = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.MACHINE_SELECT) {
            MachineSelectScreen(
                onMachineSelected = { machineCode -> navController.navigate(NavRoutes.palletAlloc(machineCode)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.PALLET_ALLOC) { backStack ->
            val machineCode = backStack.arguments?.getString("machineCode") ?: return@composable
            PalletAllocScreen(
                machineCode = machineCode,
                onDone = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.RFID_RECOVERY) {
            RfidRecoveryScreen(
                onDone = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.DASHBOARD) {
            DashboardScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 5: Delete `MixerCodeScreen.kt`**

```
git rm app/src/main/java/com/ppnam/station2aa/ui/mixing/MixerCodeScreen.kt
```

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt
git add app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt
git commit -m "feat(nav): replace mixer_code route with hopper_scan; delete MixerCodeScreen"
```

---

## Task 6: HopperScanScreen

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt`

**Interfaces:**
- Consumes: `MixingViewModel.hopperStatusUpdates: SharedFlow<HopperStatus>`, `MixingViewModel.checkAndAllocateHopper()`, `MixingViewModel.hopperCode`, new `MixingUiState.HopperUnavailable`, `MixingUiState.Loading` from Task 4
- Produces: `HopperScanScreen(orderNo, onProceed, onBack)` composable — imported by `AppNavGraph` in Task 5

- [ ] **Step 1: Create `HopperScanScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.HopperAvailability
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*
import kotlinx.coroutines.flow.filterIsInstance

@Composable
fun HopperScanScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val hopperStatuses = remember { mutableStateListOf<HopperStatus>() }

    LaunchedEffect(Unit) {
        viewModel.hopperStatusUpdates.collect { update ->
            val idx = hopperStatuses.indexOfFirst { it.hopperCode == update.hopperCode }
            if (idx >= 0) hopperStatuses[idx] = update else hopperStatuses.add(update)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.PREMIX_COMPLETE) onProceed()
        }
    }

    AppScaffold(
        title = "Scan Hopper",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hopperStatuses.isNotEmpty()) {
                Text("Hopper Status", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hopperStatuses) { hopper ->
                        val chipColor = when (hopper.status) {
                            HopperAvailability.AVAILABLE -> SuccessGreen
                            HopperAvailability.IN_USE -> DangerRed
                            HopperAvailability.OFFLINE -> TextMuted
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(hopper.hopperCode, color = chipColor) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = chipColor.copy(alpha = 0.10f)
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = chipColor.copy(alpha = 0.35f),
                                disabledBorderColor = GraphiteBorder
                            )
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.weight(1f))

            when (val state = uiState) {
                is MixingUiState.Loading -> {
                    CircularProgressIndicator(color = AmberPrimary)
                }
                is MixingUiState.HopperUnavailable -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Hopper ${state.hopperCode} unavailable",
                                style = MaterialTheme.typography.bodyLarge,
                                color = DangerRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(state.reason, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Scan another hopper barcode", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        }
                    }
                }
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Scan hopper barcode to allocate", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
```

Note: The barcode scan listener for hopper allocation is driven by the DataWedge receiver through `ScanEventBus`. Add a `LaunchedEffect` that listens for `ScanEvent.Barcode` and calls `viewModel.checkAndAllocateHopper`. Add this inside the composable body, after the existing `LaunchedEffect` blocks:

```kotlin
LaunchedEffect(Unit) {
    viewModel.startListeningForHopperBarcode(orderNo)
}
```

And add this function to `MixingViewModel` (add after `checkAndAllocateHopper`):

```kotlin
fun startListeningForHopperBarcode(orderNo: String) {
    scanJob?.cancel()
    scanJob = viewModelScope.launch {
        scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
            checkAndAllocateHopper(orderNo, event.value)
        }
    }
}
```

- [ ] **Step 2: Verify the project compiles**

```
gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run full test suite**

```
gradlew.bat testDebugUnitTest
```

Expected: all tests PASS

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt
git commit -m "feat(ui): add HopperScanScreen with live hopper status chips and barcode scan"
```

---

## Task 7: IngredientScanScreen — Exception Overlays

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: `MixingUiState.IngredientInvalid`, `MixingUiState.WaitingForSupervisor` from Task 4; `viewModel.discardInvalidIngredient()`, `viewModel.requestSupervisorOverride()` from Task 4; `viewModel.supervisorError: Flow<String>` from Task 4

- [ ] **Step 1: Update `IngredientScanScreen.kt` — add exception state handling**

After the existing state `when` block (currently ending at line 159 with `else -> Spacer`), the overlays are rendered by extending the `when` block. Replace the full composable body content inside the `AppScaffold` padding lambda with the version below. The key changes are: new `IngredientInvalid` and `WaitingForSupervisor` branches in the `when` block, a `supervisorError` snackbar, and the callback/button label rename from Step 2 of Task 5.

Add these imports at the top of `IngredientScanScreen.kt`:

```kotlin
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import com.mitas.ppnam.station2aa.ui.components.ScanPromptCard
```

Replace the `when (val state = uiState)` block (and `else` branch) entirely, inserting two new branches between `OrderLoaded` and `else`:

```kotlin
is MixingUiState.IngredientInvalid -> {
    Spacer(Modifier.weight(1f))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Invalid Ingredient", style = MaterialTheme.typography.titleMedium, color = WarningOrange)
            }
            Spacer(Modifier.height(8.dp))
            Text("Tag: ${state.tagId}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(state.reason, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { viewModel.discardInvalidIngredient() },
            modifier = Modifier.weight(1f).height(56.dp),
            border = BorderStroke(1.dp, GraphiteBorder)
        ) {
            Text("Discard", color = TextPrimary)
        }
        Button(
            onClick = { viewModel.requestSupervisorOverride(state.tagId, state.reason) },
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
        ) {
            Text("Override")
        }
    }
    Spacer(Modifier.weight(1f))
}

is MixingUiState.WaitingForSupervisor -> {
    Spacer(Modifier.weight(1f))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberPrimary.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.WifiTethering, null, tint = AmberPrimary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Scan supervisor tag to approve",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tag: ${state.tagId}  •  ${state.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { viewModel.discardInvalidIngredient() },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        border = BorderStroke(1.dp, GraphiteBorder)
    ) {
        Text("Cancel Override", color = TextPrimary)
    }
    Spacer(Modifier.weight(1f))
}
```

Add a `SnackbarHostState` for supervisor errors. At the top of the composable body add:

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(Unit) {
    viewModel.supervisorError.collect { message ->
        snackbarHostState.showSnackbar(message)
    }
}
```

Pass `snackbarHost = { SnackbarHost(snackbarHostState) }` to `AppScaffold` (add as a named parameter — verify `AppScaffold` supports it; if not, wrap in a `Box` with the `SnackbarHost` overlaid at the bottom).

- [ ] **Step 2: Verify the project compiles**

```
gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run full test suite**

```
gradlew.bat testDebugUnitTest
```

Expected: all tests PASS

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(ui): add IngredientInvalid and WaitingForSupervisor overlays to IngredientScanScreen"
```

---

## Manual Test Checklist

After all tasks complete, verify on device or emulator:

- [ ] Scan a valid ingredient RFID tag → BOM progress updates, no warning
- [ ] Scan an invalid ingredient RFID tag → amber warning card with tag ID and reason; Discard and Override buttons visible
- [ ] Tap Discard → returns to scan mode, invalid tag not added
- [ ] Tap Override → "Scan supervisor tag" prompt appears; Cancel button visible
- [ ] Scan a valid supervisor RFID tag → ingredient added with exception marker, scan mode resumes
- [ ] Scan an invalid supervisor tag → snackbar error, still waiting for supervisor tag
- [ ] Tap Cancel Override → returns to scan mode, tag not added
- [ ] Tap "Proceed to Hopper Scan" → navigates to HopperScanScreen
- [ ] Hopper status broadcast received → coloured chips appear in HopperScanScreen
- [ ] Scan an available hopper barcode → navigates to PreMixCompleteScreen; chip shows "Hopper: H-01"
- [ ] Scan an unavailable hopper barcode → red error card with reason; scan prompt resets
- [ ] Complete pre-mix → WPF receives `hopperCode` and `exceptions[]` array; confirmation screen shown
- [ ] Complete pre-mix offline → queued confirmation screen shown
