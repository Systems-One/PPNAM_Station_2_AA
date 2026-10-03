# Strip Station 2 to Job Lookup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce the PPNAM Station 2 Android app to login → job lookup → read-only BOM, and put its MQTT layer fully on the fleet base standard.

**Architecture:** Delete four feature verticals outright, split the surviving job-lookup code out of `MixingViewModel`/`MixingUseCase`, then convert workflow messages from the schema-4.1 envelope to the base standard's lightweight envelope while auth keeps 4.1 SCRAM unchanged. Correlation moves from `inResponseToMessageId` to `(responseType, correlatorValue?)`. The Python simulator in `tools/backend-sim/` converts in lockstep and is the only thing the new wire format can be tested against.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT5 client, Gson, JUnit4 + mockito-kotlin + kotlinx-coroutines-test. Python 3 for the simulator.

**Spec:** `docs/superpowers/specs/2026-09-10-strip-to-job-lookup-design.md`

## Global Constraints

- **Base standard is authority:** `C:\Dev\Clients\PPNAM\Andriod\MQTT_BASE_README.md`. Where it conflicts with `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-2\RFID_MQTT_CONTRACT.md`, the base standard wins.
- **Station segment is `PPNAM/station_2`**, hardcoded. Never publish or subscribe outside that subtree.
- **Auth envelope is schema `"4.1"`** and does not change in this plan.
- **Workflow envelope carries `ts`, `deviceId`, `operatorSessionId` and workflow fields only** — never `messageId`, never `schemaVersion`.
- **Auth error codes are `lowercase_snake_case`; workflow error codes are `UPPERCASE_SNAKE_CASE`.** Never compare across families.
- **Timestamps** are UTC RFC 3339 with exactly six fractional digits and a literal `Z`. Use `MqttSchema.formatTimestamp()`; never `Instant.toString()`.
- **Optional fields are omitted, never sent as `null` or `""`.**
- **Workflow timeout is 10 s.**
- **Presence is raw text `online`/`offline`, retained, QoS 2, on the base node.** Do not change any presence, connection, or self-heal code.
- **Never log a secret.** Everything written to a diagnostic goes through `Redact` once Task 17 lands.
- **Do not edit anything under `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-2`** — read-only reference.
- **Branch:** all work on `feat/strip-to-job-lookup`, cut from current `HEAD` (`f25323d`), not from `master`.
- **Build:** `./gradlew assembleDebug` · **Unit tests:** `./gradlew testDebugUnitTest`

## Deviation from spec §8

The spec sequenced commit 1 as "strip UI" and commit 2 as "strip domain and data". This plan deletes each feature **vertically** instead — UI, ViewModel, use case, model, DTO and tests together — because a horizontal split leaves orphaned use cases compiling against nothing for a whole commit, and a reviewer cannot meaningfully accept one half. Same six phases, same end state, same total work.

---

## Phase 0: Branch

### Task 0: Cut the working branch

**Files:** none

- [ ] **Step 1: Create and switch to the branch**

```bash
git checkout -b feat/strip-to-job-lookup
git log --oneline -1
```

Expected: `f25323d docs: design for stripping Station 2 to job lookup...`

---

## Phase 1: Delete the stripped features

### Task 1: Delete the mixing-board feature

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingBoardViewModel.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/MixingBoard.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreenKtTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingBoardScreenKtTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/board/MixingBoardViewModelTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingOverviewWireCaptureTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/NavRoutes.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NavRoutes` no longer declares `MIXING_BOARD`, `MIXING_AREAS`, `MIXING_AREA_BOARD`, `mixingAreas()`, `mixingAreaBoard()`. `MixingArea` no longer exists.

- [ ] **Step 1: Delete the files**

```bash
git rm -r app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/board \
          app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/board
git rm app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingBoardUseCase.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/MixingBoard.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingMessages.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingBoardUseCaseTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingMessagesTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/MixingOverviewWireCaptureTest.kt
```

- [ ] **Step 2: Remove the mixing-board routes**

In `NavRoutes.kt`, delete the constants `MIXING_BOARD`, `MIXING_AREAS`, `MIXING_AREA_BOARD` and the functions `mixingAreas()` and `mixingAreaBoard()`. The file keeps `HOME`, `LOGIN`, `SETTINGS`, `MIXING`, `JOB_LOOKUP`, `INGREDIENT_SCAN`, `RFID_RECOVERY` and `ingredientScan()`.

- [ ] **Step 3: Remove the mixing-board nav graph**

In `AppNavGraph.kt`, delete the entire `navigation(startDestination = NavRoutes.MIXING_AREAS, route = NavRoutes.MIXING_BOARD) { ... }` block and these imports:

```kotlin
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
```

Then fix the three surviving call sites that navigated to it — replace each with a no-op lambda for now; Task 5 removes the parameters entirely:

- `HomeScreen(onOpenMixingBoard = { navController.navigate(NavRoutes.mixingAreas()) }, ...)` → `onOpenMixingBoard = {}`
- `JobLookupScreen(onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) }, ...)` → `onOpenMixing = {}`
- `IngredientScanScreen(onStartMixing = { collectionId -> navController.navigate(NavRoutes.mixingAreas(collectionId)) }, ...)` → `onStartMixing = {}`

- [ ] **Step 4: Verify no dangling references**

```bash
grep -rn "MixingBoard\|MixingArea\|mixingAreas\|mixingAreaBoard" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 5: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "strip: remove the mixing board feature"
```

---

### Task 2: Delete the ingredient-scan feature

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/IngredientScanScreenKtTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/MixingViewModel.kt` — delete everything from `private data class PendingIngredientScan` (line 340) to end of class
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt` — delete `scanIngredient`, `waiveShortBags`, `cancelJob`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`, `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` — delete the cases covering the removed methods
- Modify: `AppNavGraph.kt`, `NavRoutes.kt`

**Interfaces:**
- Consumes: Task 1's `NavRoutes`.
- Produces: `MixingViewModel` retains only `uiState`, `connectionState`, `connectionStatus`, `session`, `logoutEvent`, `logout()`, `activeJobs`, `activeJobsError`, `activeJobsHasMore`, `activeJobsTotal`, `navigationEvent`, `lookupJob()`, `loadActiveJobs()`, `loadMoreActiveJobs()`, and the `init` server-push handler. `MixingUiState` retains only `Idle`, `Loading`, `OrderLoaded`, `Error`. `MixingUseCase` retains only `lookupJob`, `fetchActiveJobCards` and the two private mappers.

- [ ] **Step 1: Delete the files**

```bash
git rm app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/IngredientScanOutcome.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/IngredientScanScreenKtTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt
```

- [ ] **Step 2: Trim `MixingUiState`**

In `MixingViewModel.kt`, the `sealed class MixingUiState` keeps only:

```kotlin
sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}
```

Delete `Cancelling`, `EnteringBagDetails`, `EnteringQuantityDetails`, `IngredientExceptionApproval`, `PalletRecoveryPrompt`, `ShortBagWaiverNeedsApproval`, `ShortBagWaiverEntry`, the `ScanFeedback` enum and the `CancelOutcome` sealed class. `OrderLoaded` loses `selectedLineNumber`, `pendingLineNumber`, `pendingLabel` and `isBusy` — nothing arms a line any more.

- [ ] **Step 3: Trim the ViewModel body**

Delete everything from `private data class PendingIngredientScan` to the end of the class. Then delete these now-unused members from the surviving upper section: `gson` stays (the push handler uses it); delete `_overCollectionToleranceBags`/`overCollectionToleranceBags`, `_supervisorError`/`supervisorError`, `_cancelOutcome`/`cancelOutcome`, `_scanFeedback`/`scanFeedback`, `scanJob`, `currentOrderNo`, `cachedOrder`, `armedLineNumber`, `orderLoadedState()`, and the `scanEventBus` constructor parameter.

`lookupJob` simplifies — it no longer resumes, arms lines, or caches an order:

```kotlin
    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order ->
                    _uiState.value = MixingUiState.OrderLoaded(order)
                    _navigationEvent.send(MixingNavDestination.JOB_LOADED)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }
```

`MixingNavDestination` keeps only `JOB_LOADED`.

- [ ] **Step 4: Trim `MixingUseCase`**

Delete `scanIngredient`, `waiveShortBags` and `cancelJob`, the `palletUseCase` and `managerAuthorization` constructor parameters, and every now-unused import (`ManagerAuthorization`, `EmptyPayload`, `IngredientScanPayload`, `IngredientScanResultResponse`, `IngredientCollectionCancelPayload`, `IngredientCollectionCancelResultResponse`, `ManagerAction`, `ShortBagWaiverPayload`, `IngredientScanOutcome`, `NextAction`).

`lookupJob` loses its resume branch:

```kotlin
    suspend fun lookupJob(jobCardNumber: String): Result<ProductionOrder> {
        val outcome = mqttRepository.request(
            requestType = "job_card_load_requested",
            responseType = "bom_loaded",
            payload = JobCardLoadPayload(jobCardNumber = jobCardNumber),
            correlationKey = jobCardNumber,
            responseClass = BomLoadedResponse::class.java,
        )
        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val order = outcome.body.toProductionOrder()
                bomCacheDao.put(
                    BomCacheEntity(jobCardNumber, gson.toJson(order), Instant.now().toEpochMilli())
                )
                Result.success(order)
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(humaniseLookupRejection(outcome.reason)))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }
```

(The `bomCacheDao` write disappears in Task 4.)

- [ ] **Step 5: Remove the ingredient-scan route**

In `NavRoutes.kt` delete `INGREDIENT_SCAN` and `ingredientScan()`. In `AppNavGraph.kt` delete the `composable(NavRoutes.INGREDIENT_SCAN) { ... }` block and the `IngredientScanScreen` import. Change `JobLookupScreen`'s `onJobFound` to a no-op lambda — Task 8 points it at Job Detail.

- [ ] **Step 6: Trim the two affected test files**

In `MixingUseCaseTest.kt` and `MixingViewModelTest.kt`, delete every test exercising `scanIngredient`, `waiveShortBags`, `cancelJob`, bag entry, quantity entry, manager approval, short-bag waiver, pallet recovery or scan feedback. Keep every test covering `lookupJob`, `fetchActiveJobCards`, `loadActiveJobs`, `loadMoreActiveJobs`, the invalidation push, and `logout`.

- [ ] **Step 7: Verify no dangling references**

```bash
grep -rn "IngredientScan\|ScanFeedback\|CancelOutcome\|ShortBagWaiver\|PendingIngredientScan\|overCollectionTolerance" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 8: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "strip: remove the ingredient collection feature"
```

---

### Task 3: Delete pallet lookup, holding recovery and manager authorization

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/rfid/RfidViewModel.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCase.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/PalletInfo.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ManagerAuthorization.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/rfid/RfidViewModelTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/PalletStateTest.kt`
- Modify: `AppNavGraph.kt`, `NavRoutes.kt`

**Interfaces:**
- Consumes: Task 2's `NavRoutes`.
- Produces: `data/rfid/` (`DataWedgeReceiver`, `ScanEventBus`, `ScanEvent`) is untouched — it is hardware barcode input, not this feature.

- [ ] **Step 1: Delete the files**

```bash
git rm -r app/src/main/java/com/mitas/ppnam/station2aa/ui/rfid \
          app/src/test/java/com/mitas/ppnam/station2aa/ui/rfid
git rm app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCase.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/PalletInfo.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ManagerAuthorization.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/model/PalletStateTest.kt
```

- [ ] **Step 2: Remove the RFID recovery route**

In `NavRoutes.kt` delete `RFID_RECOVERY`. In `AppNavGraph.kt` delete the `composable(NavRoutes.RFID_RECOVERY) { ... }` block and the `RfidRecoveryScreen` import. Replace the two `onRfidLookup = { viewModel.pauseScanning(); navController.navigate(NavRoutes.RFID_RECOVERY) }` call sites and `HomeScreen`'s `onFixATag` with no-op lambdas; Task 5 removes the parameters.

- [ ] **Step 3: Verify no dangling references**

```bash
grep -rn "PalletUseCase\|PalletInfo\|RfidRecovery\|RfidViewModel\|ManagerAuthorization\|RFID_RECOVERY\|pauseScanning" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 4: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "strip: remove pallet lookup, holding recovery and manager authorization"
```

---

### Task 4: Delete the upgrade gate and the Room BOM cache

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/UpgradeGate.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/local/AppDatabase.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/local/BomCacheDao.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/local/BomCacheEntity.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/local/BomCacheDaoTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/di/AppModule.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: Task 2's trimmed `MixingUseCase`.
- Produces: `MqttRepository` no longer declares `upgradeRequired`. `MixingUseCase` constructor is `(mqttRepository: MqttRepository)` only. `AppModule` provides only `provideMqttRepository`.

- [ ] **Step 1: Delete the files**

```bash
git rm app/src/main/java/com/mitas/ppnam/station2aa/ui/components/UpgradeGate.kt
git rm -r app/src/main/java/com/mitas/ppnam/station2aa/data/local
git rm app/src/test/java/com/mitas/ppnam/station2aa/data/local/BomCacheDaoTest.kt
```

- [ ] **Step 2: Reduce `AppModule` to the one binding**

```kotlin
package com.mitas.ppnam.station2aa.di

import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMqttRepository(impl: MqttRepositoryImpl): MqttRepository = impl
}
```

- [ ] **Step 3: Drop the cache write from `MixingUseCase`**

Remove the `bomCacheDao` constructor parameter, the `BomCacheDao`/`BomCacheEntity`/`Instant` imports, and the `bomCacheDao.put(...)` call. The `Accepted` branch of `lookupJob` becomes:

```kotlin
            is MqttOutcome.Accepted -> Result.success(outcome.body.toProductionOrder())
```

The class declaration becomes:

```kotlin
@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {
```

The `gson` property is now unused here — delete it and the `WireJson` import.

- [ ] **Step 4: Remove the upgrade latch from the transport**

In `MqttRepository.kt` delete the `upgradeRequired` property and its KDoc. In `MqttRepositoryImpl.kt` delete `_upgradeRequired`, the `upgradeRequired` override, the `if (code == ErrorCode.CLIENT_UPGRADE_REQUIRED) { ... }` block in `parseOutcome`, and the `if (envelope?.errorCode == ErrorCode.CLIENT_UPGRADE_REQUIRED.raw) { ... }` block in `handleIncomingResponse`. In `AppNavGraph.kt` delete the `UpgradeRequiredGate()` call and its import.

- [ ] **Step 5: Remove Room from the build**

In `app/build.gradle.kts` delete the Room dependency lines and the Room KSP/kapt processor line. Search first so nothing is missed:

```bash
grep -n "room" app/build.gradle.kts
```

Delete every line that match reports, including `androidTestImplementation(libs.room.testing)`.

- [ ] **Step 6: Verify no dangling references**

```bash
grep -rni "bomcache\|appdatabase\|upgraderequired\|room" app/src app/build.gradle.kts --include=*.kt --include=*.kts
```

Expected: no output.

- [ ] **Step 7: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "strip: remove the upgrade gate and the Room BOM cache"
```

---

### Task 5: Reduce Home to one tile and flatten navigation

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/home/HomeScreen.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/ui/home/HomeViewModelTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `NavRoutes` declares exactly `HOME`, `LOGIN`, `SETTINGS`, `JOB_LOOKUP` (no nested graphs, no parameterised routes). `HomeScreen(onOpenJobCards, onSettings, onLogout, onExitApp, viewModel)`. `JobLookupScreen(onJobFound, onSettings, onLogout, onBack, viewModel)`.

- [ ] **Step 1: Reduce `NavRoutes` to four flat routes**

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val JOB_LOOKUP = "job_lookup"
}
```

- [ ] **Step 2: Drop the dead parameters from `HomeScreen`**

Change the signature to `HomeScreen(onOpenJobCards, onSettings, onLogout, onExitApp, viewModel)` — delete `onOpenMixingBoard` and `onFixATag` — and delete the two corresponding tiles from the tile column, leaving only the job-cards tile. Delete the now-unused `Icons.Filled.Science` and `Icons.Filled.WifiTethering` imports.

- [ ] **Step 3: Drop the dead parameters from `JobLookupScreen`**

Delete the `onRfidLookup` and `onOpenMixing` parameters and every control that invoked them.

- [ ] **Step 4: Flatten the nav graph**

In `AppNavGraph.kt`, replace the `navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.MIXING) { ... }` wrapper with a plain top-level composable. The whole `NavHost` body becomes four `composable` blocks — `LOGIN`, `HOME`, `SETTINGS`, `JOB_LOOKUP`:

```kotlin
        composable(NavRoutes.JOB_LOOKUP) {
            JobLookupScreen(
                onJobFound = {},
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Navigation on logout is SessionWatcher's job alone.
                onLogout = {},
                onBack = { navController.popBackStack() },
            )
        }
```

The `remember { navController.getBackStackEntry(...) }` / `hiltViewModel(parentEntry)` dance goes with the nested graph — a flat route gets its ViewModel from a plain `hiltViewModel()` default. Delete the `remember` and `navigation` imports if nothing else uses them.

- [ ] **Step 5: Fix `HomeViewModelTest`**

Update any assertion that referenced the removed tiles.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "strip: reduce Home to one tile and flatten the navigation graph"
```

---

## Phase 2: Reshape what survives

### Task 6: Rename `MixingUseCase` to `JobLookupUseCase`

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCase.kt` (renamed from `MixingUseCase.kt`)
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCaseTest.kt` (renamed from `MixingUseCaseTest.kt`)
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/MixingViewModel.kt`

**Interfaces:**
- Consumes: Task 4's trimmed `MixingUseCase`.
- Produces: `class JobLookupUseCase @Inject constructor(private val mqttRepository: MqttRepository)` with `suspend fun lookupJob(jobCardNumber: String): Result<ProductionOrder>` and `suspend fun fetchActiveJobCards(pageSize: Int? = null, continuationToken: String? = null, statuses: List<String>? = null, search: String? = null): Result<ActiveJobsPage>`.

- [ ] **Step 1: Rename both files with history preserved**

```bash
git mv app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCase.kt
git mv app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCaseTest.kt
```

- [ ] **Step 2: Rename the class and its references**

In `JobLookupUseCase.kt` rename `class MixingUseCase` → `class JobLookupUseCase`. In `JobLookupUseCaseTest.kt` rename the class and every `MixingUseCase(` construction. In `MixingViewModel.kt` change the constructor parameter to `private val useCase: JobLookupUseCase` and update the import.

- [ ] **Step 3: Verify**

```bash
grep -rn "MixingUseCase" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 4: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename MixingUseCase to JobLookupUseCase"
```

---

### Task 7: Move the ViewModel and screen into `ui/joblookup`

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModel.kt` (renamed from `ui/mixing/MixingViewModel.kt`)
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreen.kt` (moved from `ui/mixing/`)
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModelTest.kt` (renamed from `ui/mixing/MixingViewModelTest.kt`)
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreenKtTest.kt` (moved from `ui/mixing/`)
- Delete: the whole `ui/mixing/` package, main and test
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: Task 6's `JobLookupUseCase`.
- Produces: `com.mitas.ppnam.station2aa.ui.joblookup.JobLookupViewModel` exposing `uiState: StateFlow<JobLookupUiState>`, `connectionStatus`, `session`, `logoutEvent`, `navigationEvent`, `activeJobs`, `activeJobsError`, `activeJobsHasMore`, `activeJobsTotal`, `lookupJob(orderNo: String)`, `loadActiveJobs()`, `loadMoreActiveJobs()`, `logout()`. `sealed class JobLookupUiState { Idle, Loading, OrderLoaded(order), Error(message) }`. `object JobLookupNavDestination { const val JOB_LOADED = "job_loaded" }`.

- [ ] **Step 1: Move the four files**

```bash
mkdir -p app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup \
         app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup
git mv app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModel.kt
git mv app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/JobLookupScreen.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreen.kt
git mv app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModelTest.kt
git mv app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/JobLookupScreenKtTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreenKtTest.kt
```

- [ ] **Step 2: Rename package and symbols**

In all four files change `package com.mitas.ppnam.station2aa.ui.mixing` → `...ui.joblookup`. Rename `MixingViewModel` → `JobLookupViewModel`, `MixingUiState` → `JobLookupUiState`, `MixingNavDestination` → `JobLookupNavDestination`, in both main and test sources. Update the `AppNavGraph.kt` import.

- [ ] **Step 3: Verify the old package is gone**

```bash
ls app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing 2>&1
grep -rn "MixingViewModel\|MixingUiState\|MixingNavDestination\|ui\.mixing" app/src --include=*.kt
```

Expected: "No such file or directory" for both directories, and no grep output.

- [ ] **Step 4: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move job lookup into its own ui.joblookup package"
```

---

### Task 8: Add the read-only Job Detail screen

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobDetailScreen.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobDetailScreenKtTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: Task 7's `JobLookupViewModel` and `JobLookupUiState`.
- Produces: `@Composable fun JobDetailScreen(orderNo: String, onBack: () -> Unit, viewModel: JobLookupViewModel)`. `NavRoutes.JOB_DETAIL = "job_detail/{orderNo}"` and `NavRoutes.jobDetail(orderNo: String): String`. `internal fun BomLine.detailSubtitle(): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobDetailScreenKtTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.domain.model.BomLine
import org.junit.Assert.assertEquals
import org.junit.Test

class JobDetailScreenKtTest {

    private fun line(
        required: Double = 25.0,
        collected: Double = 0.0,
        uom: String = "kg",
    ) = BomLine(
        lineNumber = 1,
        itemCode = "MAT-1",
        itemName = "Carbon black",
        requiredQty = required,
        collectedQty = collected,
        weightReceived = 0.0,
        remainingQty = required - collected,
        availableQty = 0.0,
        uom = uom,
    )

    @Test
    fun `subtitle shows collected of required with the unit`() {
        assertEquals("0 / 25 kg", line(required = 25.0, collected = 0.0).detailSubtitle())
    }

    @Test
    fun `subtitle drops a trailing zero decimal`() {
        assertEquals("3.5 / 25 kg", line(required = 25.0, collected = 3.5).detailSubtitle())
    }

    @Test
    fun `subtitle omits a blank unit rather than leaving a trailing space`() {
        assertEquals("0 / 25", line(required = 25.0, collected = 0.0, uom = "").detailSubtitle())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*JobDetailScreenKtTest*"
```

Expected: FAIL — `detailSubtitle` unresolved.

- [ ] **Step 3: Write `JobDetailScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

/**
 * "collected / required unit" for one BOM line.
 *
 * Quantities arrive as Double but are whole numbers far more often than not, so a bare
 * toString() would put "0.0 / 25.0 kg" on a shop-floor handheld. A blank unit is omitted
 * rather than rendered as a trailing space — Station 2 sends an empty `unit` on lines whose
 * UoM it could not resolve.
 *
 * `internal` so the test can assert on it directly without a Compose harness.
 */
internal fun BomLine.detailSubtitle(): String {
    val text = "${collectedQty.trimNumber()} / ${requiredQty.trimNumber()}"
    return if (uom.isBlank()) text else "$text $uom"
}

private fun Double.trimNumber(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

@Composable
fun JobDetailScreen(
    orderNo: String,
    onBack: () -> Unit,
    viewModel: JobLookupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    AppScaffold(
        title = orderNo,
        connectionStatus = connectionStatus,
        onBack = onBack,
    ) { padding ->
        when (val state = uiState) {
            is JobLookupUiState.OrderLoaded -> {
                val order = state.order
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabelValueRow("Job card", order.docNo)
                            LabelValueRow("Collection", order.collectionId)
                            LabelValueRow("Status", order.collectionStatus)
                            order.productBeingMade?.let { LabelValueRow("Product", it) }
                            if (order.summary.isNotBlank()) {
                                Text(order.summary, color = TextMuted)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    items(order.lines, key = { it.lineNumber }) { line ->
                        ListItem(
                            headlineContent = { Text(line.itemName, color = TextPrimary) },
                            supportingContent = { Text(line.detailSubtitle(), color = TextMuted) },
                            trailingContent = { Text(line.itemCode, color = TextMuted) },
                        )
                    }
                }
            }
            is JobLookupUiState.Error ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text(state.message, color = TextMuted)
                }
            else ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    CircularProgressIndicator()
                }
        }
    }
}
```

If `AppScaffold`'s parameter names differ, match the existing call in `JobLookupScreen.kt` rather than this sketch — that file is the reference for the shared scaffold's API.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*JobDetailScreenKtTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Wire up the route**

In `NavRoutes.kt`:

```kotlin
    const val JOB_DETAIL = "job_detail/{orderNo}"

    fun jobDetail(orderNo: String) = "job_detail/$orderNo"
```

In `AppNavGraph.kt`, change Job Lookup's `onJobFound` to navigate, and add the detail destination:

```kotlin
                onJobFound = { orderNo -> navController.navigate(NavRoutes.jobDetail(orderNo)) },
```

```kotlin
        composable(NavRoutes.JOB_DETAIL) { backStackEntry ->
            val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
            JobDetailScreen(
                orderNo = orderNo,
                onBack = { navController.popBackStack() },
            )
        }
```

Both screens must share one `JobLookupViewModel` instance so the loaded order survives the hop. Scope it to the activity by passing the activity-scoped owner:

```kotlin
        composable(NavRoutes.JOB_LOOKUP) {
            val activity = LocalContext.current.findActivity()!!
            JobLookupScreen(viewModel = hiltViewModel(activity as ViewModelStoreOwner), ...)
        }
```

Apply the same to the `JOB_DETAIL` composable. Add `import androidx.lifecycle.ViewModelStoreOwner`.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(joblookup): add the read-only job detail screen"
```

---

### Task 9: Trim the Settings screen

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: Task 4's `MqttRepository` (no `upgradeRequired`).
- Produces: Settings renders exactly two sections — **Broker** (host, port, TLS, certificate validation, username, password, request timeout) and **Diagnostics** (derived device id read-only, broker connection state, station presence, clock skew).

- [ ] **Step 1: Delete controls belonging to removed features**

Walk `SettingsScreen.kt` top to bottom. Keep only the two sections above. Delete any control referencing mixing, ingredient collection, pallets, RFID recovery or the upgrade gate, and delete the matching `SettingsViewModel` members.

The derived device id **must** stay visible and read-only — base standard §2.3 requires it be readable off the device for enrolment. Do not make it editable.

- [ ] **Step 2: Verify**

```bash
grep -rni "mixing\|ingredient\|pallet\|rfid\|upgrade" app/src/main/java/com/mitas/ppnam/station2aa/ui/settings
```

Expected: no output.

- [ ] **Step 3: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "strip: trim Settings to broker config and diagnostics"
```

### CHECKPOINT A — on-device verification

Before Phase 3, install and drive the app against the **current** simulator. The wire format has not changed yet, so this must work end to end.

```bash
python tools/backend-sim/sim.py
./gradlew installDebug
```

Verify: login succeeds; the active job list loads; tapping a card opens Job Detail with its BOM; Settings shows a `scanner_`-prefixed device id, Connected, and Station 2 Online. Stop and report if any of these fail — Phase 4 is much harder to debug on top of a broken Phase 2.

---

## Phase 3: Strip the simulator

### Task 10: Reduce the simulator to auth plus two workflow messages

**Files:**
- Delete: `tools/backend-sim/handlers/mixing.py`, `ingredients.py`, `pallets.py`
- Modify: `tools/backend-sim/handlers/__init__.py`
- Modify: `tools/backend-sim/handlers/jobcards.py`
- Modify: `tools/backend-sim/state.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `REGISTRY` contains exactly `scram_start_requested`, `scram_proof_requested`, `login_requested`, `reader_logout_requested`, `active_job_cards_requested`, `job_card_load_requested`.

- [ ] **Step 1: Delete the three handler modules**

```bash
git rm tools/backend-sim/handlers/mixing.py \
       tools/backend-sim/handlers/ingredients.py \
       tools/backend-sim/handlers/pallets.py
```

- [ ] **Step 2: Trim the registry**

In `handlers/__init__.py` remove the imports and `REGISTRY` entries for the deleted modules. Move every request type they handled into `RETIRED_REQUEST_TYPES` so the simulator answers a stale client with a clear rejection instead of silence.

- [ ] **Step 3: Trim `jobcards.py`**

Keep the handlers for `active_job_cards_requested` and `job_card_load_requested` plus `seed_demo_collections`. Delete the handlers for `collection_resume_requested` and `ingredient_collection_cancel_requested`, and any helper only they used.

- [ ] **Step 4: Trim `state.py`**

Delete equipment, cycle, run, mix, drum and pallet state. Keep operators, sessions, collections and their BOM lines.

- [ ] **Step 5: Verify the simulator still starts and serves**

```bash
python tools/backend-sim/sim.py &
sleep 3
python tools/backend-sim/selftest.py
kill %1
```

Expected: the login and job-card portions pass. Cases for deleted messages will fail — that is expected here; Task 16 rewrites `selftest.py`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "sim: reduce the simulator to auth and the two job-lookup messages"
```

---

### Task 11: Trim the test harness drivers

**Files:**
- Delete: `tools/test-harness/drive_mix_A.py`, `drive_mix_B.py`, `drive_mix_C.py`, `drive_mix_D.py`, `drive_mixing_open.py`, `drive_area_scope_check.py`, `drive_board_main.py`, `board.py`, `collect.py`, `collect_sim.py`, `d_assign_go.py`, `d_assign_sheet.py`, `make_pallets.py`, `pallets.json`, `pallets_sim.json`

**Interfaces:**
- Consumes: nothing.
- Produces: `tools/test-harness/` retains `drive_login.py`, `drive_relogin.py`, `d_login.py`, `d_nav.py`, `ui.py`, `sniffer.py`, `simctl.py`, `analyze.py`, `sweep2.py` and the mosquitto configs.

- [ ] **Step 1: Delete the drivers for removed screens**

```bash
cd tools/test-harness
git rm drive_mix_A.py drive_mix_B.py drive_mix_C.py drive_mix_D.py \
       drive_mixing_open.py drive_area_scope_check.py drive_board_main.py \
       board.py collect.py collect_sim.py d_assign_go.py d_assign_sheet.py \
       make_pallets.py pallets.json pallets_sim.json
cd ../..
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "harness: drop drivers for the removed screens"
```

---

## Phase 4: The envelope split

### Task 12: Split the error-code and next-action vocabulary

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttOutcome.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCase.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt`

**Interfaces:**
- Consumes: Task 6's `JobLookupUseCase`.
- Produces: `@JvmInline value class AuthErrorCode(val raw: String)` and `@JvmInline value class WorkflowErrorCode(val raw: String)`, both in `data/mqtt/MqttVocabulary.kt`. `NextAction` keeps only `NONE`, `LOGIN`, `SCAN_JOB_CARD`, `ACTIVE_JOB_CARDS`, `REFRESH_ACTIVE_JOBS`. `MqttOutcome.Rejected` carries `errorCode: WorkflowErrorCode?`; a new `MqttOutcome.AuthRejected` is **not** introduced — auth keeps using `Rejected` with a separate parse path added in Task 13.

- [ ] **Step 1: Write the failing test**

Replace the body of `MqttVocabularyTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `auth codes are lowercase snake case`() {
        assertEquals("session_required", AuthErrorCode.SESSION_REQUIRED.raw)
        assertEquals("message_id_reused", AuthErrorCode.MESSAGE_ID_REUSED.raw)
        assertEquals("scram_proof_invalid", AuthErrorCode.SCRAM_PROOF_INVALID.raw)
    }

    @Test
    fun `workflow codes are uppercase snake case`() {
        assertEquals("INVALID_PAYLOAD", WorkflowErrorCode.INVALID_PAYLOAD.raw)
        assertEquals("AUTHENTICATION_REQUIRED", WorkflowErrorCode.AUTHENTICATION_REQUIRED.raw)
        assertEquals("OPERATOR_SESSION_INVALID", WorkflowErrorCode.OPERATOR_SESSION_INVALID.raw)
        assertEquals("ACTION_NOT_ALLOWED", WorkflowErrorCode.ACTION_NOT_ALLOWED.raw)
        assertEquals("PAGE_CURSOR_STALE", WorkflowErrorCode.PAGE_CURSOR_STALE.raw)
    }

    @Test
    fun `an unknown code passes through intact rather than failing`() {
        assertEquals("SOMETHING_NEW", WorkflowErrorCode("SOMETHING_NEW").raw)
        assertEquals("something_new", AuthErrorCode("something_new").raw)
    }

    @Test
    fun `the same spelling in the two families is not the same value`() {
        // The two families are separate types precisely so this comparison cannot compile
        // elsewhere; here we only assert the raw strings differ in case.
        assertNotEquals(AuthErrorCode.SESSION_REQUIRED.raw, WorkflowErrorCode.OPERATOR_SESSION_INVALID.raw)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*MqttVocabularyTest*"
```

Expected: FAIL — `AuthErrorCode` unresolved.

- [ ] **Step 3: Rewrite `MqttVocabulary.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Authentication error codes — schema 4.1, `lowercase_snake_case`, compared exactly
 * (base standard §4).
 *
 * A value class rather than an enum: the station may add codes, and an unrecognised one must
 * pass through intact rather than fail the parse.
 */
@JvmInline
value class AuthErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = AuthErrorCode("invalid_json")
        val INVALID_ENVELOPE = AuthErrorCode("invalid_envelope")
        val UNSUPPORTED_SCHEMA = AuthErrorCode("unsupported_schema")
        val DEVICE_MISMATCH = AuthErrorCode("device_mismatch")
        val DEVICE_NOT_CONFIGURED = AuthErrorCode("device_not_configured")
        val MESSAGE_EXPIRED = AuthErrorCode("message_expired")
        val TIMESTAMP_STALE = AuthErrorCode("timestamp_stale")
        val MESSAGE_ID_REUSED = AuthErrorCode("message_id_reused")
        val SESSION_REQUIRED = AuthErrorCode("session_required")
        val PERMISSION_DENIED = AuthErrorCode("permission_denied")
        val VALIDATION_FAILED = AuthErrorCode("validation_failed")
        val SERVICE_UNAVAILABLE = AuthErrorCode("service_unavailable")
        val SCRAM_PROOF_INVALID = AuthErrorCode("scram_proof_invalid")

        /**
         * The app sent a `password` or `managerPassword` property on a 4.1 message — a build
         * defect, never an operator error. Never surface it as "wrong password".
         * Task 15's pre-dispatch guard should make this unreachable.
         */
        val PLAINTEXT_CREDENTIALS_FORBIDDEN = AuthErrorCode("plaintext_credentials_forbidden")
    }
}

/**
 * Workflow error codes — `UPPERCASE_SNAKE_CASE` (base standard §4).
 *
 * The first four are the common session/permission codes every station app handles. The rest
 * are Station 2's, and the set is provisional until the rewritten contract defines it.
 */
@JvmInline
value class WorkflowErrorCode(val raw: String) {
    companion object {
        val INVALID_PAYLOAD = WorkflowErrorCode("INVALID_PAYLOAD")
        val AUTHENTICATION_REQUIRED = WorkflowErrorCode("AUTHENTICATION_REQUIRED")
        val OPERATOR_SESSION_INVALID = WorkflowErrorCode("OPERATOR_SESSION_INVALID")
        val ACTION_NOT_ALLOWED = WorkflowErrorCode("ACTION_NOT_ALLOWED")

        /** Keyset paging: discard accumulated pages and re-request page one. */
        val PAGE_CURSOR_STALE = WorkflowErrorCode("PAGE_CURSOR_STALE")
        val NOT_FOUND = WorkflowErrorCode("NOT_FOUND")
        val STATE_CONFLICT = WorkflowErrorCode("STATE_CONFLICT")
    }
}

/**
 * Guidance for the scanner UI, never authorization. An empty value means "no forced navigation".
 */
@JvmInline
value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val SCAN_JOB_CARD = NextAction("scan_job_card")
        val ACTIVE_JOB_CARDS = NextAction("active_job_cards")
        val REFRESH_ACTIVE_JOBS = NextAction("refresh_active_jobs")
    }
}
```

- [ ] **Step 4: Update the four consumers**

- `MqttOutcome.Rejected.errorCode` becomes `WorkflowErrorCode?`.
- `JobLookupUseCase.fetchActiveJobCards` compares against `WorkflowErrorCode.PAGE_CURSOR_STALE`.
- `MqttRepositoryImpl` compares the central session-clear guard against `AuthErrorCode.SESSION_REQUIRED.raw`.
- `ScramExchange` compares against `AuthErrorCode.*`.

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*MqttVocabularyTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Build and test everything**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(mqtt): split error codes into auth and workflow families"
```

---

### Task 13: Add the workflow envelope and its response type

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/WorkflowEnvelope.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/WorkflowResponseEnvelope.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/WorkflowEnvelopeTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelope.kt` → rename to `AuthEnvelope.kt`

**Interfaces:**
- Consumes: Task 12's vocabulary; `MqttSchema.formatTimestamp`.
- Produces: `object WorkflowEnvelope { fun build(gson: Gson, payload: Any, deviceId: String, operatorSessionId: String, ts: String): String }`. `object AuthEnvelope { fun build(gson: Gson, payload: Any, messageId: String, deviceId: String, operatorSessionId: String, timestampUtc: String, correlationKey: String?): String }` — same body as today's `RequestEnvelope.build`. `data class WorkflowResponseEnvelope(ts, deviceId, operatorSessionId, errorCode, errorMessage, nextAction)` with `val accepted: Boolean get() = errorCode.isNullOrBlank()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/WorkflowEnvelopeTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEnvelopeTest {

    private data class Payload(val jobCardNumber: String, val pageSize: Int? = null)

    private fun build(
        payload: Any = Payload("JC0001"),
        operatorSessionId: String = "sess-1",
    ) = JsonParser.parseString(
        WorkflowEnvelope.build(
            gson = WireJson.gson,
            payload = payload,
            deviceId = "scanner_5c64df8d86a8",
            operatorSessionId = operatorSessionId,
            ts = "2026-09-10T07:02:00.000000Z",
        )
    ).asJsonObject

    @Test
    fun `carries ts deviceId operatorSessionId and the workflow fields`() {
        val obj = build()
        assertEquals("2026-09-10T07:02:00.000000Z", obj["ts"].asString)
        assertEquals("scanner_5c64df8d86a8", obj["deviceId"].asString)
        assertEquals("sess-1", obj["operatorSessionId"].asString)
        assertEquals("JC0001", obj["jobCardNumber"].asString)
    }

    @Test
    fun `never carries messageId or schemaVersion`() {
        val obj = build()
        assertFalse(obj.has("messageId"))
        assertFalse(obj.has("schemaVersion"))
        assertFalse(obj.has("timestampUtc"))
        assertFalse(obj.has("correlationKey"))
    }

    @Test
    fun `omits a blank operatorSessionId rather than sending an empty string`() {
        assertFalse(build(operatorSessionId = "").has("operatorSessionId"))
    }

    @Test
    fun `omits unused optional payload fields`() {
        assertFalse(build(Payload("JC0001", pageSize = null)).has("pageSize"))
        assertTrue(build(Payload("JC0001", pageSize = 25)).has("pageSize"))
    }

    @Test
    fun `envelope fields win over a payload field of the same name`() {
        val obj = build(payload = mapOf("deviceId" to "spoofed", "jobCardNumber" to "JC0001"))
        assertEquals("scanner_5c64df8d86a8", obj["deviceId"].asString)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*WorkflowEnvelopeTest*"
```

Expected: FAIL — `WorkflowEnvelope` unresolved.

- [ ] **Step 3: Create `WorkflowEnvelope.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson

/**
 * Builds a workflow request as one flat JSON object: the caller's workflow fields with the
 * lightweight envelope merged in (base standard §4).
 *
 * Deliberately carries no `messageId` and no `schemaVersion` — those belong to the
 * authentication envelope alone. See [AuthEnvelope].
 *
 * Envelope fields are written last and win over anything of the same name in the payload:
 * only the transport knows the device id, the session and the clock.
 *
 * Gson omits nulls, which is the standard's own rule that an unused optional field is omitted
 * rather than sent as null. A blank [operatorSessionId] is treated the same way — the app has
 * no session before login, and "" is not an omission.
 */
object WorkflowEnvelope {

    fun build(
        gson: Gson,
        payload: Any,
        deviceId: String,
        operatorSessionId: String,
        ts: String,
    ): String {
        val obj = gson.toJsonTree(payload).asJsonObject
        obj.addProperty("ts", ts)
        obj.addProperty("deviceId", deviceId)
        if (operatorSessionId.isNotBlank()) {
            obj.addProperty("operatorSessionId", operatorSessionId)
        }
        return gson.toJson(obj)
    }
}
```

- [ ] **Step 4: Create `WorkflowResponseEnvelope.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * The envelope on every workflow response (base standard §4).
 *
 * Success is modelled as the ABSENCE of an error code rather than a separate `accepted` flag.
 * The standard states only that workflow error codes are UPPERCASE_SNAKE_CASE; it does not
 * define an `accepted` field, and inventing one would be an assumption about a contract that
 * is still being rewritten. Absence-means-success needs nothing the standard doesn't already
 * guarantee.
 *
 * [operatorSessionId] is nullable and its absence is meaningful: the transport only clears a
 * local session on a rejection that names the session it is rejecting. See
 * MqttRepositoryImpl.handleIncomingWorkflowResponse.
 */
data class WorkflowResponseEnvelope(
    val ts: String = "",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val nextAction: String? = null,
) {
    val accepted: Boolean get() = errorCode.isNullOrBlank()
}
```

- [ ] **Step 5: Rename `RequestEnvelope` to `AuthEnvelope`**

```bash
git mv app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelope.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/AuthEnvelope.kt
git mv app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/AuthEnvelopeTest.kt
```

Rename `object RequestEnvelope` → `object AuthEnvelope` and update the test class name and every reference. Keep `object EmptyPayload` where it is. Update its KDoc to say it builds the **authentication** envelope, schema 4.1, and to point at `WorkflowEnvelope` for workflow messages.

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*WorkflowEnvelopeTest*" --tests "*AuthEnvelopeTest*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(mqtt): add the workflow envelope alongside the auth envelope"
```

---

### Task 14: Rework correlation onto `(responseType, correlator)`

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCase.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AppSettings.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttWorkflowCorrelationTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestCorrelationTest.kt`

**Interfaces:**
- Consumes: Tasks 12 and 13.
- Produces: on `MqttRepository`:

```kotlin
data class Correlator(val field: String, val value: String)

suspend fun <T : Any> workflowRequest(
    requestType: String,
    responseType: String,
    payload: Any,
    correlator: Correlator?,
    idempotent: Boolean,
    responseClass: Class<T>,
): MqttOutcome<T>
```

The existing `request(...)` is renamed `authRequest(...)` with an unchanged signature and behaviour. `AppSettings.requestTimeoutMs` default becomes `10_000L`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttWorkflowCorrelationTest.kt`. Model the setup on the deleted `MqttRequestCorrelationTest.kt` — it already builds an `MqttRepositoryImpl` with `publishFn` and `nowFn` stubbed; copy that harness rather than inventing one.

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.domain.repository.Correlator
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttWorkflowCorrelationTest {

    private data class BomBody(val jobCardNumber: String = "", val collectionId: String = "")
    private data class ListBody(val snapshotRevision: String = "")

    private fun bomLoaded(jobCardNumber: String, collectionId: String) =
        """{"ts":"2026-09-10T07:02:00.000000Z","deviceId":"scanner_x",
            "jobCardNumber":"$jobCardNumber","collectionId":"$collectionId"}"""

    @Test
    fun `a response matches the waiter whose correlator it echoes`() = runTest {
        val repo = newConnectedRepository()
        val first = async {
            repo.workflowRequest(
                "job_card_load_requested", "bom_loaded", mapOf("jobCardNumber" to "JC0001"),
                Correlator("jobCardNumber", "JC0001"), idempotent = true, BomBody::class.java,
            )
        }
        val second = async {
            repo.workflowRequest(
                "job_card_load_requested", "bom_loaded", mapOf("jobCardNumber" to "JC0002"),
                Correlator("jobCardNumber", "JC0002"), idempotent = true, BomBody::class.java,
            )
        }
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_x/res/bom_loaded",
            bomLoaded("JC0002", "COL-2").toByteArray(),
        )
        val outcome = second.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("COL-2", (outcome as MqttOutcome.Accepted).body.collectionId)
        assertTrue("the JC0001 waiter must still be pending", first.isActive)
        first.cancel()
    }

    @Test
    fun `a correlator-less response completes the single outstanding waiter`() = runTest {
        val repo = newConnectedRepository()
        val pending = async {
            repo.workflowRequest(
                "active_job_cards_requested", "active_job_cards_list", mapOf("pageSize" to 25),
                correlator = null, idempotent = true, ListBody::class.java,
            )
        }
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_x/res/active_job_cards_list",
            """{"ts":"2026-09-10T07:02:00.000000Z","deviceId":"scanner_x","snapshotRevision":"r1"}""".toByteArray(),
        )
        val outcome = pending.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("r1", (outcome as MqttOutcome.Accepted).body.snapshotRevision)
    }

    @Test
    fun `a newer correlator-less request supersedes the older one`() = runTest {
        val repo = newConnectedRepository()
        val older = async {
            repo.workflowRequest(
                "active_job_cards_requested", "active_job_cards_list", mapOf("pageSize" to 25),
                correlator = null, idempotent = true, ListBody::class.java,
            )
        }
        val newer = async {
            repo.workflowRequest(
                "active_job_cards_requested", "active_job_cards_list", mapOf("pageSize" to 25),
                correlator = null, idempotent = true, ListBody::class.java,
            )
        }
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_x/res/active_job_cards_list",
            """{"ts":"2026-09-10T07:02:00.000000Z","deviceId":"scanner_x","snapshotRevision":"r2"}""".toByteArray(),
        )
        assertTrue(newer.await() is MqttOutcome.Accepted)
        val supersededOutcome = older.await()
        assertTrue(supersededOutcome is MqttOutcome.NoResponse)
        assertEquals(FailureKind.Superseded, (supersededOutcome as MqttOutcome.NoResponse).kind)
    }

    @Test
    fun `an UPPERCASE workflow error code comes back as a rejection`() = runTest {
        val repo = newConnectedRepository()
        val pending = async {
            repo.workflowRequest(
                "job_card_load_requested", "bom_loaded", mapOf("jobCardNumber" to "JC0001"),
                Correlator("jobCardNumber", "JC0001"), idempotent = true, BomBody::class.java,
            )
        }
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_x/res/bom_loaded",
            """{"ts":"2026-09-10T07:02:00.000000Z","deviceId":"scanner_x",
                "jobCardNumber":"JC0001","errorCode":"NOT_FOUND",
                "errorMessage":"No such job card."}""".toByteArray(),
        )
        val outcome = pending.await()
        assertTrue(outcome is MqttOutcome.Rejected)
        assertEquals(WorkflowErrorCode.NOT_FOUND, (outcome as MqttOutcome.Rejected).errorCode)
    }
}
```

`newConnectedRepository()` is the helper copied from the deleted correlation test; it must set `deviceId` to `scanner_x` and force `_connectionState` to `CONNECTED`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*MqttWorkflowCorrelationTest*"
```

Expected: FAIL — `workflowRequest` and `FailureKind.Superseded` unresolved.

- [ ] **Step 3: Add `Superseded` to `FailureKind`**

In `MqttOutcome.kt`:

```kotlin
    /**
     * A newer request of the same response type replaced this one before it was answered.
     *
     * Only reachable for a workflow request with no correlator — the lightweight envelope has no
     * messageId to tell two in-flight requests apart, so the newer one wins. Not an error: a list
     * refresh superseding an earlier refresh is the intended behaviour, and the caller should
     * simply drop the superseded result rather than show anything.
     */
    Superseded,
```

- [ ] **Step 4: Add the pending-workflow registry to `MqttRepositoryImpl`**

Add alongside the existing `pending` map (which stays, for auth):

```kotlin
    // Workflow correlation. The lightweight envelope carries no messageId, so a response is
    // matched by its topic's response type plus, when the request declared one, an echoed field
    // (base standard §4: "responses ... echo the correlating fields ... so the scanner can match
    // them"). A request with no correlator is single-flight per response type: registering a
    // second one supersedes the first, which is what a list refresh means.
    //
    // A list rather than a map: matching needs the entry's own correlator field name, and the
    // in-flight count is one or two. CopyOnWriteArrayList because HiveMQ delivers callbacks on
    // its own event-loop threads.
    private class PendingWorkflow(
        val responseType: String,
        val correlator: Correlator?,
        val waiter: CompletableDeferred<String>,
    )

    private val pendingWorkflow = CopyOnWriteArrayList<PendingWorkflow>()

    private fun registerWorkflow(
        responseType: String,
        correlator: Correlator?,
        waiter: CompletableDeferred<String>,
    ): PendingWorkflow {
        if (correlator == null) {
            // Supersede any other correlator-less waiter on this response type.
            pendingWorkflow
                .filter { it.responseType == responseType && it.correlator == null }
                .forEach {
                    pendingWorkflow.remove(it)
                    it.waiter.complete(SUPERSEDED)
                }
        }
        val entry = PendingWorkflow(responseType, correlator, waiter)
        pendingWorkflow.add(entry)
        return entry
    }

    /** Sentinel body meaning "a newer request replaced this one" — never a real payload. */
    private val SUPERSEDED = "\u0000superseded"
```

- [ ] **Step 5: Route incoming workflow responses**

In `handleIncomingResponse`, before the existing auth handling, add a workflow branch. A workflow response is one whose JSON has a `ts` property and no `inResponseToMessageId`:

```kotlin
        val responseType = MqttTopics.responseTypeOf(topic)
        val tree = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable response on $topic", e)
            return
        }
        if (tree.has("ts") && !tree.has("inResponseToMessageId")) {
            handleIncomingWorkflowResponse(topic, responseType, tree, raw)
            return
        }
        // Everything below this line is the existing auth path. Do not modify it: the
        // seenResponseIds dedup, the session_required guard, the server-push dispatch and the
        // inResponseToMessageId correlation all stay exactly as they are.
```

And the handler:

```kotlin
    private fun handleIncomingWorkflowResponse(
        topic: String,
        responseType: String,
        tree: JsonObject,
        raw: String,
    ) {
        recordClockSkew(tree["ts"]?.asString.orEmpty())

        val envelope = gson.fromJson(raw, WorkflowResponseEnvelope::class.java)
        // Clear a session Station 2 has genuinely invalidated — but only when the rejection names
        // the session we currently believe is active. A late reply for an already-superseded
        // session must not log the operator out of a brand-new valid one.
        //
        // The base standard does not require workflow responses to echo operatorSessionId. Until
        // the rewritten contract settles it, absence means DO NOT CLEAR: a lingering stale session
        // is recoverable, a spurious mid-action logout is what this guard exists to prevent.
        val code = envelope?.errorCode
        if ((code == WorkflowErrorCode.AUTHENTICATION_REQUIRED.raw ||
                code == WorkflowErrorCode.OPERATOR_SESSION_INVALID.raw) &&
            !envelope.operatorSessionId.isNullOrBlank() &&
            envelope.operatorSessionId == sessionHolder.currentSessionIdOrEmpty()
        ) {
            Log.w(TAG, "Station 2 rejected a workflow response on $topic with $code — clearing local session")
            sessionHolder.clear()
        }

        val match = pendingWorkflow.firstOrNull { entry ->
            entry.responseType == responseType && when (val c = entry.correlator) {
                null -> true
                else -> tree[c.field]?.takeIf { it.isJsonPrimitive }?.asString == c.value
            }
        }
        if (match == null) {
            Log.w(TAG, "Dropping unmatched workflow response on $topic")
            return
        }
        pendingWorkflow.remove(match)
        match.waiter.complete(raw)
    }
```

- [ ] **Step 6: Implement `workflowRequest` and rename `request` to `authRequest`**

```kotlin
    override suspend fun <T : Any> workflowRequest(
        requestType: String,
        responseType: String,
        payload: Any,
        correlator: Correlator?,
        idempotent: Boolean,
        responseClass: Class<T>,
    ): MqttOutcome<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return MqttOutcome.NoResponse(FailureKind.NotConnected)
        }

        val json = WorkflowEnvelope.build(
            gson = gson,
            payload = payload,
            deviceId = deviceId,
            operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
            ts = MqttSchema.formatTimestamp(nowFn()),
        )
        val topic = MqttTopics.request(deviceId, requestType)
        val bytes = json.toByteArray()

        // Retry is only safe on a read. The lightweight envelope has no messageId, so the station
        // cannot recognise a replay — a retried mutation would run twice. Callers declare this.
        val attempts = if (idempotent) REQUEST_MAX_ATTEMPTS else 1
        val waiter = CompletableDeferred<String>()
        val entry = registerWorkflow(responseType, correlator, waiter)
        try {
            repeat(attempts) { attempt ->
                val publishOk = try {
                    publishFn(topic, bytes)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "publish attempt ${attempt + 1} failed for $requestType", e)
                    false
                }
                if (publishOk) {
                    val raw = withTimeoutOrNull(requestTimeoutMs) { waiter.await() }
                    if (raw == SUPERSEDED) return MqttOutcome.NoResponse(FailureKind.Superseded)
                    if (raw != null) return parseWorkflowOutcome(raw, responseClass, responseType)
                }
                if (attempt < attempts - 1) {
                    Log.w(TAG, "retrying $requestType (attempt ${attempt + 2})")
                }
            }
            return MqttOutcome.NoResponse(FailureKind.Timeout)
        } finally {
            pendingWorkflow.remove(entry)
        }
    }

    private fun <T : Any> parseWorkflowOutcome(
        raw: String,
        responseClass: Class<T>,
        expectedResponseType: String,
    ): MqttOutcome<T> = try {
        val envelope = gson.fromJson(raw, WorkflowResponseEnvelope::class.java)
        val body = gson.fromJson(raw, responseClass)
            ?: throw IllegalStateException("Response body parsed to null for $expectedResponseType")
        val nextAction = NextAction(envelope.nextAction ?: "")
        if (envelope.accepted) {
            MqttOutcome.Accepted(body, nextAction)
        } else {
            MqttOutcome.Rejected(
                body = body,
                errorCode = envelope.errorCode?.let { WorkflowErrorCode(it) },
                reason = envelope.errorMessage,
                nextAction = nextAction,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not parse $expectedResponseType response", e)
        MqttOutcome.NoResponse(FailureKind.MalformedResponse)
    }
```

Rename the existing `request` to `authRequest` in both the interface and the implementation. Its body, including the `messageId` correlation and the `seenResponseIds` dedup, is unchanged.

- [ ] **Step 7: Point `JobLookupUseCase` at `workflowRequest`**

```kotlin
        val outcome = mqttRepository.workflowRequest(
            requestType = "job_card_load_requested",
            responseType = "bom_loaded",
            payload = JobCardLoadPayload(jobCardNumber = jobCardNumber),
            correlator = Correlator("jobCardNumber", jobCardNumber),
            idempotent = true,
            responseClass = BomLoadedResponse::class.java,
        )
```

```kotlin
            val outcome = mqttRepository.workflowRequest(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = ActiveJobCardsPayload(
                    pageSize = pageSize,
                    continuationToken = continuationToken,
                    statuses = statuses?.takeIf { it.isNotEmpty() },
                    search = search?.takeIf { it.isNotBlank() },
                ),
                correlator = null,
                idempotent = true,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
```

Both are reads, so both are `idempotent = true`. Add a `FailureKind.Superseded` branch to each caller that maps it to "no change" rather than an error the operator sees.

- [ ] **Step 8: Drop the workflow timeout to 10 s**

In `AppSettings.kt` change the default to `val requestTimeoutMs: Long = 10_000L` and update the expectation in `AppSettingsTest.kt`.

- [ ] **Step 9: Delete the superseded test and run the new one**

```bash
git rm app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestCorrelationTest.kt
./gradlew testDebugUnitTest --tests "*MqttWorkflowCorrelationTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 10: Confirm the untouched transport tests still pass**

```bash
./gradlew testDebugUnitTest --tests "*MqttStationPresenceTest*" --tests "*MqttClockSkewTest*" --tests "*MqttRepositoryImplTest*"
```

Expected: PASS, **with no edits to those files**. If any fails, spec §5.4 has been violated — stop and report rather than editing the test.

- [ ] **Step 11: Build and test everything**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(mqtt): correlate workflow responses by response type and echoed field"
```

---

### Task 15: Convert the simulator to the workflow envelope

**Files:**
- Modify: `tools/backend-sim/envelope.py`
- Modify: `tools/backend-sim/handlers/jobcards.py`
- Modify: `tools/backend-sim/handlers/__init__.py`
- Modify: `tools/backend-sim/sim.py`

**Interfaces:**
- Consumes: Task 14's wire format.
- Produces: `envelope.py` exposes `build_auth_response(...)` (today's `build_response`, unchanged) and `build_workflow_response(world, req, extras=None, error_code=None, error_message=None, next_action=None)`. `validate_workflow(world, log, topic_device, request_type, payload_bytes)` rejects any workflow request carrying `messageId` or `schemaVersion`.

- [ ] **Step 1: Add the workflow builders to `envelope.py`**

Rename `build_response` to `build_auth_response` and add:

```python
WORKFLOW_ENVELOPE_FIELDS = ("ts", "deviceId", "operatorSessionId")
FORBIDDEN_WORKFLOW_FIELDS = ("messageId", "schemaVersion", "timestampUtc", "correlationKey")


def build_workflow_response(world, req, extras=None, error_code=None,
                            error_message=None, next_action=None):
    """Base standard section 4 workflow response.

    Success is the ABSENCE of errorCode - there is no `accepted` flag. Echoed correlating
    fields come in through `extras`; the Android client matches on them because the
    lightweight envelope has no messageId to correlate on.
    """
    body = {
        "ts": utc_now_micros(),
        "deviceId": req["deviceId"],
    }
    if req.get("operatorSessionId"):
        body["operatorSessionId"] = req["operatorSessionId"]
    if error_code:
        body["errorCode"] = error_code
        if error_message:
            body["errorMessage"] = error_message
    if next_action:
        body["nextAction"] = next_action
    body.update(extras or {})
    return body
```

- [ ] **Step 2: Reject 4.1 fields on a workflow topic**

```python
def validate_workflow(world, log, topic_device, request_type, payload_bytes):
    """Validates a lightweight workflow request.

    A workflow message carrying messageId or schemaVersion is a client still on the old
    envelope. Reject it loudly rather than silently accepting both shapes - a simulator that
    tolerates the old format cannot prove the new one works.
    """
    payload = json.loads(payload_bytes.decode("utf-8"))
    for field in FORBIDDEN_WORKFLOW_FIELDS:
        if field in payload:
            raise Rejection("INVALID_PAYLOAD",
                            f"{field} is an authentication-envelope field; "
                            f"workflow messages must not carry it")
    if payload.get("deviceId") != topic_device:
        raise Rejection("INVALID_PAYLOAD", "deviceId does not match the topic segment")
    if not payload.get("ts"):
        raise Rejection("INVALID_PAYLOAD", "ts is required")
    return payload
```

- [ ] **Step 3: Route by family in `sim.py`**

In `handle_request`, dispatch to `validate_workflow` + `build_workflow_response` when the request type is one of the two workflow types, and to the existing auth path otherwise. Mark each registry entry with its family in `handlers/__init__.py` so the routing reads off the registry rather than a hardcoded list.

- [ ] **Step 4: Echo the correlating field from `jobcards.py`**

`job_card_load_requested` must echo `jobCardNumber` in its `bom_loaded` extras — the Android client correlates on exactly that field, so a missing echo means every job-card load times out. `active_job_cards_requested` echoes nothing.

- [ ] **Step 5: Verify by hand against the app**

```bash
python tools/backend-sim/sim.py &
sleep 3
python tools/test-harness/sniffer.py --topic "PPNAM/station_2/#" --count 10
```

Expected: `req/job_card_load_requested` payloads contain `ts`/`deviceId`/`operatorSessionId`/`jobCardNumber` and **no** `messageId` or `schemaVersion`; `res/bom_loaded` echoes `jobCardNumber`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "sim: serve the base-standard workflow envelope"
```

---

## Phase 5: The compliance fixes

### Task 16: Reject duplicate property names

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/StrictJson.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/StrictJsonTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/WireJson.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object StrictJson { fun parse(raw: String): JsonElement }` throwing `DuplicatePropertyException(path: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonTest {

    @Test
    fun `accepts an ordinary object`() {
        val parsed = StrictJson.parse("""{"ts":"x","deviceId":"scanner_1"}""").asJsonObject
        assertEquals("scanner_1", parsed["deviceId"].asString)
    }

    @Test
    fun `rejects an exact duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"deviceId":"a","deviceId":"b"}""")
        }
    }

    @Test
    fun `rejects a case-insensitive duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"deviceId":"a","DeviceId":"b"}""")
        }
    }

    @Test
    fun `rejects a duplicate nested inside an object`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"outer":{"a":1,"A":2}}""")
        }
    }

    @Test
    fun `rejects a duplicate nested inside an array element`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"lines":[{"n":1},{"n":1,"N":2}]}""")
        }
    }

    @Test
    fun `the same name at different depths is not a duplicate`() {
        val parsed = StrictJson.parse("""{"a":1,"outer":{"a":2}}""").asJsonObject
        assertEquals(1, parsed["a"].asInt)
        assertEquals(2, parsed["outer"].asJsonObject["a"].asInt)
    }

    @Test
    fun `reports the path of the offending property`() {
        val thrown = assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"outer":{"inner":{"a":1,"A":2}}}""")
        }
        assertEquals("outer/inner/A", thrown.path)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*StrictJsonTest*"
```

Expected: FAIL — `StrictJson` unresolved.

- [ ] **Step 3: Write `StrictJson.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.internal.LazilyParsedNumber
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** A message carried two properties whose names differ only by case, at [path]. */
class DuplicatePropertyException(val path: String) :
    IllegalArgumentException("Duplicate property name at '$path'")

/**
 * Parses one wire message, rejecting duplicate property names case-insensitively at any depth
 * (base standard §4: "Duplicate property names (case-insensitive, any depth) are rejected").
 *
 * Gson's own parser silently keeps the LAST value for a repeated key, so `{"accepted":true,
 * "Accepted":false}` would parse as a success. The standard treats that as a message to reject,
 * not a message to guess at, and a guess here is a guess about whether an operation succeeded.
 *
 * Duplicates are scoped per object: the same name at different depths is ordinary nesting, not
 * a duplicate, which is why the seen-set is created per `beginObject` rather than shared.
 */
object StrictJson {

    fun parse(raw: String): JsonElement =
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            read(reader, "")
        }

    private fun read(reader: JsonReader, path: String): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val seen = HashSet<String>()
            val obj = JsonObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (!seen.add(name.lowercase())) throw DuplicatePropertyException(childPath)
                obj.add(name, read(reader, childPath))
            }
            reader.endObject()
            obj
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val arr = JsonArray()
            var index = 0
            while (reader.hasNext()) {
                arr.add(read(reader, "$path[$index]"))
                index++
            }
            reader.endArray()
            arr
        }
        // Read numbers as text and defer parsing: going via Double would quietly round a long
        // id, and these values are echoed back to the station.
        JsonToken.NUMBER -> JsonPrimitive(LazilyParsedNumber(reader.nextString()))
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Unexpected token ${reader.peek()} at '$path'")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*StrictJsonTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Parse every inbound message through it**

In `MqttRepositoryImpl.handleIncomingResponse`, replace the `JsonParser.parseString(raw)` call added in Task 14 with `StrictJson.parse(raw)`, and log-and-drop a `DuplicatePropertyException` the same way an unparseable message is dropped:

```kotlin
        val tree = try {
            StrictJson.parse(raw).asJsonObject
        } catch (e: DuplicatePropertyException) {
            Log.w(TAG, "Dropping response on $topic with a duplicate property: ${e.path}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable response on $topic", e)
            return
        }
```

The auth path's `gson.fromJson(raw, ...)` calls then run on a message already proven duplicate-free.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(mqtt): reject duplicate property names on inbound messages"
```

---

### Task 17: Guard against dispatching plaintext credentials

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/CredentialGuard.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/CredentialGuardTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/WorkflowEnvelope.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/AuthEnvelope.kt`

**Interfaces:**
- Consumes: Task 13's envelopes.
- Produces: `object CredentialGuard { fun assertNone(element: JsonElement) }` throwing `PlaintextCredentialException(path: String)`. Both envelope builders call it before serializing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialGuardTest {

    private fun check(raw: String) = CredentialGuard.assertNone(JsonParser.parseString(raw))

    @Test
    fun `allows a message with no credential property`() {
        check("""{"ts":"x","deviceId":"scanner_1","jobCardNumber":"JC0001"}""")
    }

    @Test
    fun `rejects a top-level password`() {
        assertThrows(PlaintextCredentialException::class.java) {
            check("""{"username":"op1","password":"hunter2"}""")
        }
    }

    @Test
    fun `rejects managerPassword regardless of case`() {
        assertThrows(PlaintextCredentialException::class.java) {
            check("""{"MANAGERPASSWORD":"hunter2"}""")
        }
    }

    @Test
    fun `rejects a credential nested at any depth`() {
        assertThrows(PlaintextCredentialException::class.java) {
            check("""{"outer":{"inner":{"password":"hunter2"}}}""")
        }
    }

    @Test
    fun `rejects a credential inside an array element`() {
        assertThrows(PlaintextCredentialException::class.java) {
            check("""{"approvals":[{"user":"a"},{"managerPassword":"x"}]}""")
        }
    }

    @Test
    fun `allows the SCRAM proof, which is not a plaintext credential`() {
        check("""{"clientProof":"dGVzdA==","serverSignature":"dGVzdA=="}""")
    }

    @Test
    fun `reports the path of the offending property`() {
        val thrown = assertThrows(PlaintextCredentialException::class.java) {
            check("""{"outer":{"password":"x"}}""")
        }
        assertEquals("outer/password", thrown.path)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*CredentialGuardTest*"
```

Expected: FAIL — `CredentialGuard` unresolved.

- [ ] **Step 3: Write `CredentialGuard.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonElement

/** An outgoing message carried a plaintext credential property at [path]. */
class PlaintextCredentialException(val path: String) :
    IllegalArgumentException("Refusing to publish a message carrying a credential at '$path'")

/**
 * Refuses to publish any message carrying a plaintext credential (base standard §4:
 * "`password` / `managerPassword` anywhere in a JSON tree is rejected before dispatch").
 *
 * Passwords never travel over MQTT — only the SCRAM proof does. `clientProof` and
 * `serverSignature` are therefore explicitly fine: they are the mechanism that replaced the
 * password, not a copy of it.
 *
 * The station rejects such a message with `plaintext_credentials_forbidden` anyway. Catching it
 * here is what stops the password reaching the broker at all: once published it is in the
 * broker's logs and any subscriber's buffer, and a server-side rejection is far too late.
 * Throwing rather than scrubbing is deliberate — silently stripping the field would let a build
 * defect ship quietly.
 */
object CredentialGuard {

    private val FORBIDDEN = setOf("password", "managerpassword")

    fun assertNone(element: JsonElement, path: String = "") {
        when {
            element.isJsonObject ->
                for ((name, value) in element.asJsonObject.entrySet()) {
                    val childPath = if (path.isEmpty()) name else "$path/$name"
                    if (name.lowercase() in FORBIDDEN) throw PlaintextCredentialException(childPath)
                    assertNone(value, childPath)
                }
            element.isJsonArray ->
                element.asJsonArray.forEachIndexed { index, item ->
                    assertNone(item, "$path[$index]")
                }
        }
    }
}
```

- [ ] **Step 4: Call it from both envelope builders**

In `WorkflowEnvelope.build`, immediately after `val obj = gson.toJsonTree(payload).asJsonObject`:

```kotlin
        CredentialGuard.assertNone(obj)
```

Add the identical line in the same position in `AuthEnvelope.build`.

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*CredentialGuardTest*"
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(mqtt): refuse to dispatch a message carrying a plaintext credential"
```

---

### Task 18: Redact secrets before any diagnostic write

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/Redact.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RedactTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object Redact { const val PLACEHOLDER = "***"; fun payload(raw: String): String }`. Task 19 is its only caller.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `redacts a password`() {
        val out = Redact.payload("""{"username":"op1","password":"hunter2"}""")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("op1"))
    }

    @Test
    fun `redacts every named secret`() {
        val raw = """{"password":"a","managerPassword":"b","clientProof":"c",
                      "serverSignature":"d","authorizationToken":"e","storedKey":"f",
                      "serverKey":"g","saltedPassword":"h","brokerPassword":"i"}"""
        val out = Redact.payload(raw)
        listOf("\"a\"", "\"b\"", "\"c\"", "\"d\"", "\"e\"", "\"f\"", "\"g\"", "\"h\"", "\"i\"")
            .forEach { assertFalse("leaked $it", out.contains(it)) }
    }

    @Test
    fun `redacts case-insensitively and at any depth`() {
        val out = Redact.payload("""{"outer":{"PASSWORD":"hunter2"}}""")
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun `redacts inside array elements`() {
        val out = Redact.payload("""{"items":[{"clientProof":"secret"}]}""")
        assertFalse(out.contains("secret"))
    }

    @Test
    fun `keeps non-secret fields readable`() {
        val out = Redact.payload("""{"ts":"2026-09-10T07:02:00.000000Z","jobCardNumber":"JC0001"}""")
        assertTrue(out.contains("JC0001"))
        assertTrue(out.contains("2026-09-10T07:02:00.000000Z"))
    }

    @Test
    fun `unparseable input is reported, never echoed`() {
        assertEquals("<unparseable payload>", Redact.payload("not json at all"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*RedactTest*"
```

Expected: FAIL — `Redact` unresolved.

- [ ] **Step 3: Write `Redact.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Scrubs secrets out of a payload before it reaches a log or diagnostic file
 * (base standard §7).
 *
 * Unparseable input returns a fixed marker rather than the original text. A payload that failed
 * to parse is exactly the one most likely to be logged for investigation, and echoing raw bytes
 * on that path would defeat the whole guard — a truncated or malformed message can still contain
 * a complete password.
 */
object Redact {

    const val PLACEHOLDER = "***"

    private val SECRET_KEYS = setOf(
        "password",
        "managerpassword",
        "clientproof",
        "serversignature",
        // SCRAM verifier keys — enough to impersonate the server or replay a login.
        "storedkey",
        "serverkey",
        "saltedpassword",
        "authorizationtoken",
        // Broker transport secrets.
        "brokerpassword",
    )

    fun payload(raw: String): String = try {
        JsonParser.parseString(raw).let { redact(it) }.toString()
    } catch (e: Exception) {
        "<unparseable payload>"
    }

    private fun redact(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { out ->
            for ((name, value) in element.asJsonObject.entrySet()) {
                if (name.lowercase() in SECRET_KEYS) {
                    out.add(name, JsonPrimitive(PLACEHOLDER))
                } else {
                    out.add(name, redact(value))
                }
            }
        }
        element.isJsonArray -> com.google.gson.JsonArray().also { out ->
            element.asJsonArray.forEach { out.add(redact(it)) }
        }
        else -> element
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*RedactTest*"
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(mqtt): add recursive secret redaction for diagnostics"
```

---

### Task 19: Log every message, redacted

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttLog.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttLogTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`

**Interfaces:**
- Consumes: Task 18's `Redact`.
- Produces: `object MqttLog { fun line(direction: Direction, topic: String, qos: Int, retain: Boolean, deviceId: String, messageType: String, result: String, durationMs: Long?, payload: String?): String; fun message(...) }` and `enum class Direction { OUT, IN }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttLogTest {

    private fun line(payload: String? = null, durationMs: Long? = 42L) = MqttLog.line(
        direction = Direction.OUT,
        topic = "PPNAM/station_2/scanner_x/req/job_card_load_requested",
        qos = 1,
        retain = false,
        deviceId = "scanner_x",
        messageType = "job_card_load_requested",
        result = "ok",
        durationMs = durationMs,
        payload = payload,
    )

    @Test
    fun `carries every field the base standard requires`() {
        val out = line()
        listOf(
            "dir=OUT",
            "topic=PPNAM/station_2/scanner_x/req/job_card_load_requested",
            "qos=1",
            "retain=false",
            "deviceId=scanner_x",
            "type=job_card_load_requested",
            "result=ok",
            "durationMs=42",
        ).forEach { assertTrue("missing $it in: $out", out.contains(it)) }
    }

    @Test
    fun `omits duration when there is none to report`() {
        assertFalse(line(durationMs = null).contains("durationMs"))
    }

    @Test
    fun `a logged payload is redacted`() {
        val out = line(payload = """{"username":"op1","password":"hunter2"}""")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("op1"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*MqttLogTest*"
```

Expected: FAIL — `MqttLog` unresolved.

- [ ] **Step 3: Write `MqttLog.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import android.util.Log

enum class Direction { OUT, IN }

/**
 * One log line per MQTT message, carrying the fields the base standard §7 requires: direction,
 * topic, QoS, retain, device id, message type, result/error code and duration.
 *
 * [line] builds the string and [message] emits it, split so the format is unit-testable without
 * an Android logger.
 *
 * A payload is optional and always passes through [Redact] — there is no way to log a raw one
 * through this object, which is the point.
 */
object MqttLog {

    private const val TAG = "MqttWire"

    fun line(
        direction: Direction,
        topic: String,
        qos: Int,
        retain: Boolean,
        deviceId: String,
        messageType: String,
        result: String,
        durationMs: Long? = null,
        payload: String? = null,
    ): String = buildString {
        append("dir=").append(direction)
        append(" topic=").append(topic)
        append(" qos=").append(qos)
        append(" retain=").append(retain)
        append(" deviceId=").append(deviceId)
        append(" type=").append(messageType)
        append(" result=").append(result)
        if (durationMs != null) append(" durationMs=").append(durationMs)
        if (payload != null) append(" payload=").append(Redact.payload(payload))
    }

    fun message(
        direction: Direction,
        topic: String,
        qos: Int,
        retain: Boolean,
        deviceId: String,
        messageType: String,
        result: String,
        durationMs: Long? = null,
        payload: String? = null,
    ) {
        Log.i(TAG, line(direction, topic, qos, retain, deviceId, messageType, result, durationMs, payload))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*MqttLogTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Emit a line for every message**

In `MqttRepositoryImpl`:

- In `workflowRequest` and `authRequest`, record `val startedAt = System.currentTimeMillis()` before the first publish, log `Direction.OUT` with `result = "published"` after each successful publish, and log the outcome with the elapsed duration and `result` set to `"accepted"`, the error code, `"timeout"`, or `"superseded"`.
- In `handleIncomingResponse`, log `Direction.IN` with the response type and the error code, or `"accepted"`.
- Presence publishes log with `qos = 2`, `retain = true` and `messageType = "presence"`.

Use `MqttTopics.responseTypeOf(topic)` for the inbound message type.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(mqtt): log direction, topic, QoS, retain and result for every message"
```

---

### Task 20: Gate workflows on `allowedTabs`, fail-closed

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/session/OperatorSessionHolder.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/session/OperatorSessionHolderTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: Task 5's `HomeScreen`.
- Produces: `object WorkflowTab { const val COLLECT = "collect" }`, `fun OperatorSession.canUseWorkflow(tab: String): Boolean`, `fun OperatorSession?.canUseWorkflow(tab: String): Boolean`. `StationAction`, `OperatorSession.canShow` and the nullable `canShow` extension are deleted.

- [ ] **Step 1: Write the failing test**

Add to `OperatorSessionHolderTest.kt`:

```kotlin
    private fun session(tabs: List<String>) = OperatorSession(
        operatorSessionId = "sess-1",
        operatorId = "OP-001",
        operatorName = "Operator One",
        role = "Operator",
        allowedTabs = tabs,
    )

    @Test
    fun `a listed tab is enabled`() {
        assertTrue(session(listOf("collect", "premix")).canUseWorkflow(WorkflowTab.COLLECT))
    }

    @Test
    fun `an unlisted tab is not enabled`() {
        assertFalse(session(listOf("premix", "allocation")).canUseWorkflow(WorkflowTab.COLLECT))
    }

    @Test
    fun `an empty list enables nothing - fail closed`() {
        assertFalse(session(emptyList()).canUseWorkflow(WorkflowTab.COLLECT))
    }

    @Test
    fun `no session enables nothing`() {
        val none: OperatorSession? = null
        assertFalse(none.canUseWorkflow(WorkflowTab.COLLECT))
    }
```

Delete any existing test asserting the fail-open behaviour of `canShow`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*OperatorSessionHolderTest*"
```

Expected: FAIL — `canUseWorkflow` and `WorkflowTab` unresolved.

- [ ] **Step 3: Replace `StationAction`/`canShow` with `WorkflowTab`/`canUseWorkflow`**

Delete the `StationAction` object, `OperatorSession.canShow` and the nullable `canShow` extension — all three only ever gated controls in the deleted features. Add:

```kotlin
/**
 * The workflow tabs Station 2 names in `allowedTabs`.
 *
 * Observed on the wire from a real `operator_context`:
 * `allowedTabs: ["collect", "premix", "allocation"]`. Job lookup is the collection workflow.
 */
object WorkflowTab {
    const val COLLECT = "collect"
}
```

and, on `OperatorSession`:

```kotlin
    /**
     * Whether this operator may use the [tab] workflow (base standard §5).
     *
     * Fails CLOSED: "The app enables exactly the listed workflows; a missing or empty list means
     * no workflows enabled". Contrast the deleted `allowedActions`/`canShow` hint, which failed
     * open by design — that one only decided whether to draw a button, whereas this decides
     * whether a workflow is available at all.
     *
     * Scanner-side gating is UX, not security: the station authorizes every request server-side
     * regardless of what this returns.
     */
    fun canUseWorkflow(tab: String): Boolean = tab in allowedTabs
```

```kotlin
/** [OperatorSession.canUseWorkflow] for a possibly-absent session — no session enables nothing. */
fun OperatorSession?.canUseWorkflow(tab: String): Boolean = this?.canUseWorkflow(tab) ?: false
```

- [ ] **Step 4: Gate the Home tile**

In `HomeScreen.kt`, render the job-cards tile only when `session.canUseWorkflow(WorkflowTab.COLLECT)`, and show a short "No workflows are enabled for this operator." message when nothing is available, so an operator is never left staring at an empty screen with no explanation.

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*OperatorSessionHolderTest*"
```

Expected: PASS.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(auth): gate workflows on allowedTabs, failing closed"
```

---

## Phase 6: Finish

### Task 21: Rewrite the conformance suite and fix the stale docs

**Files:**
- Modify: `tools/backend-sim/selftest.py`
- Modify: `CLAUDE.md`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttTopics.kt`
- Create: `docs/BACKEND_CONTRACT_QUESTIONS_2026-09-10.md`

**Interfaces:**
- Consumes: everything.
- Produces: a green `selftest.py`; the handover note from spec §7.

- [ ] **Step 1: Rewrite `selftest.py` as the base-standard conformance suite**

Delete every case for a removed message. Keep and extend the cases that assert the standard itself:

1. Presence: retained `online` on `PPNAM/station_2/{deviceId}` at QoS 2 after connect; retained `offline` after a graceful disconnect.
2. Auth: full SCRAM login returns a session and a valid `serverSignature`.
3. Auth envelope: a `scram_start_requested` carries `messageId` and `schemaVersion: "4.1"`.
4. Workflow envelope: `job_card_load_requested` and `active_job_cards_requested` carry `ts`/`deviceId`/`operatorSessionId` and **neither** `messageId` nor `schemaVersion`.
5. Correlation: `bom_loaded` echoes `jobCardNumber`.
6. Error families: a rejected workflow request returns an `UPPERCASE_SNAKE_CASE` code; a rejected auth request returns a `lowercase_snake_case` one.
7. Duplicate properties: a response containing `{"ts":..,"TS":..}` is dropped by the client.
8. Credentials: a workflow request carrying `password` is never published.

- [ ] **Step 2: Run the conformance suite**

```bash
python tools/backend-sim/sim.py &
sleep 3
python tools/backend-sim/selftest.py
kill %1
```

Expected: all cases pass.

- [ ] **Step 3: Fix the stale external path in `CLAUDE.md`**

Replace every `C:\Dev\PPNAM-Station-2` with `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-2`, and update the folder list in the read-only rule to match what is actually there now.

- [ ] **Step 4: Document why the broadcast tree is unsubscribed**

Add to the KDoc block at the top of `MqttTopics.kt`:

```kotlin
 * The station-broadcast tree PPNAM/station_2/res/{type} is a reserved segment (base standard §1)
 * that Station 2 does not currently use. Apps MUST tolerate unknown messages there; this app
 * satisfies that by never subscribing to it, so there is nothing to ignore. Add a subscription
 * only when a consumer for it exists.
```

- [ ] **Step 5: Write the backend handover note**

Create `docs/BACKEND_CONTRACT_QUESTIONS_2026-09-10.md` recording the four questions the rewritten Station 2 contract must answer:

1. Do workflow responses echo `operatorSessionId`? The client currently fails safe and will not clear a session on a rejection that does not name one.
2. What correlates `active_job_cards_list` to its request? The client currently treats it as single-flight, newest-wins.
3. What is the Station 2 `UPPERCASE_SNAKE_CASE` workflow error-code set beyond `INVALID_PAYLOAD`, `AUTHENTICATION_REQUIRED`, `OPERATOR_SESSION_INVALID` and `ACTION_NOT_ALLOWED`?
4. Is success on a workflow response the absence of `errorCode`, or is there an explicit `accepted` flag? The client assumes the former.

- [ ] **Step 6: Full verification**

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: base-standard conformance suite and backend contract questions"
```

### CHECKPOINT B — final on-device verification

```bash
python tools/backend-sim/sim.py &
./gradlew installDebug
python tools/test-harness/drive_login.py
python tools/test-harness/sniffer.py --topic "PPNAM/station_2/#" --count 20 > /tmp/wire-capture.txt
```

Verify and capture as deliverables:

1. Login → job lookup → job detail works end to end.
2. The wire capture shows workflow requests with `ts`/`deviceId`/`operatorSessionId` and no `messageId`/`schemaVersion`.
3. The wire capture shows auth requests still carrying `schemaVersion: "4.1"`.
4. Settings shows the derived `scanner_`-prefixed device id, broker Connected, Station 2 Online.
5. `logcat -s MqttWire` shows one redacted line per message with no secret in it.

---

## Self-review notes

**Spec coverage:** §4.1 → Tasks 1–4; §4.2 → Tasks 2, 5, 6, 7, 9, 12; §4.3 → Task 8; §5.1 → Tasks 12, 13; §5.2 → Task 14; §5.3 → Tasks 16–20; §5.4 → Task 21 step 4 and Task 14 step 10; §6.1 → Tasks 10, 11, 15, 21; §6.2 → the TDD steps throughout; §7 → Task 21 step 5; §8 → phase structure.

**Known deviations from the spec, both deliberate:**
- Vertical rather than horizontal feature deletion (see the note under Global Constraints).
- Spec §5.3 item 5 named `canShow()`; the correct target is a new `canUseWorkflow()` on `allowedTabs`, because `canShow()` gates `allowedActions`, a different field whose fail-open behaviour is correct. The spec was corrected before this plan was written.

**One assumption flagged for the executor:** `WorkflowTab.COLLECT = "collect"` comes from a real captured `operator_context` (`allowedTabs: ["collect","premix","allocation"]`), not from a contract. If the rewritten contract renames it, Task 20 is the single place to change.
