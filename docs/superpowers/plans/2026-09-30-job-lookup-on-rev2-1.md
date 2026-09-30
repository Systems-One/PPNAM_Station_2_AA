# Job Lookup on rev2.1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce the PPNAM Station 2 Android app to SCRAM login → job list → look up a job card → read-only job/BOM detail, speaking the Station 2 **rev2.1** MQTT contract.

**Architecture:** Strip every feature except login and settings, then move the Python simulator to rev2.1 so there is something to test against. Rebuild the transport around rev2.1's envelope (`schemaVersion:"rev2.1"`, `sessionId`, response body under `data`). Move auth onto the new envelope, then build job lookup fresh on a single `rev2_general_requested` message with `action: read | lookup`. Base-standard guards (duplicate names, credential guard, redaction, logging, 10 s timeout) land last.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT5 client, Gson, JUnit4 + mockito-kotlin + kotlinx-coroutines-test. Python 3 + paho-mqtt for the simulator.

**Spec:** `docs/superpowers/specs/2026-09-30-job-lookup-on-rev2-1-design.md`

## Global Constraints

- **rev2.1 governs the wire.** Contract: `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2\RFID_MQTT_CONTRACT.md`. Tie-breaker where prose is silent: `PPNAM.Station2.Core/Services/Rev2ScannerProcessor.cs` and `PPNAM.Station2.Core/Models/Rev2MixingModels.cs` in that repo.
- **The fleet base standard** (`C:\Dev\Clients\PPNAM\Docs\MQTT_BASE_README.md`) governs only what rev2.1 leaves open: device identity, presence/LWT, reconnect/self-heal, logging/redaction, 10 s timeout.
- **Everything under `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2` is read-only** except `RFID_MQTT_CONTRACT.md`. Nothing in this plan edits that repo.
- **Request envelope:** `schemaVersion:"rev2.1"`, `deviceId` (= topic device), `messageId` (UUID, 1–100 chars), `timestampUtc` via `MqttSchema.formatTimestamp()` (exactly six fractional digits and `Z`; never `Instant.toString()`), `sessionId` only when a session exists and never on `scram_*` requests. No `operatorSessionId`, no `correlationKey`.
- **Response envelope:** `inResponseToMessageId`, `success`, `error` (`""` on success), `operatorMessage`, `receivedAtUtc`, `sentAtUtc`, `durationMs` (double), `nextAction` (prose — never branched on), body under `data`. Direct replies have **no** `messageId`; pushes do.
- **Only four request suffixes exist:** `scram_start_requested`, `scram_proof_requested`, `rev2_general_requested`, `rev2_rajoo_requested`. This app sends only the first three.
- **Optional fields are omitted, never sent as `null` or `""`.**
- **Presence is raw text `online`/`offline`, retained, QoS 2, on the base node.** Do not change any connection, presence or self-heal code.
- **Workflow timeout is 10 s**, 3 attempts republishing byte-identical bytes.
- **Login never returns `allowedTabs`/`allowedActions`. There is no logout topic. A job card barcode is the Production Order number (digits).** (Customer-confirmed 2026-09-30.)
- **Branch:** `feat/strip-to-job-lookup`. Task 1's deletions are already staged there.
- **Build:** `./gradlew assembleDebug` · **Unit tests:** `./gradlew testDebugUnitTest` · **Simulator self-test:** `python tools/backend-sim/selftest.py --direct`
- **After modifying code**, run `graphify update .` (project rule; AST-only, no API cost) before each commit.

## Review Focus

1. **A scanned or typed job card with surrounding whitespace or a trailing newline** (DataWedge appends one) must look up the trimmed number, not be rejected as non-digit. → Task 10, `lookup trims whitespace and a trailing newline`.
2. **`read` for a job card Station 2 doesn't hold (unknown or not General)** returns `job: null`. Detail must say so, not spin forever. → Task 10, `read of an unknown target is a failure naming the job card`.
3. **An `active_job_cards_invalidated` push arriving before login or after logout** must not send a `read`, because it would go out with no session. → Task 11, `a push with no session sends nothing`.
4. **A `rev2_rejected` lookup (e.g. a closed or non-FAC order)** must show the operator message, stay on Job Lookup, and still apply the returned job list. → Task 11, `a rejected lookup shows the message, stays put and applies the snapshot's job list`.
5. **Quantities with long decimal tails (`557.0490000001`) or a blank unit** must render readably on a handheld, with no scientific notation. → Task 12, `formatQuantity` tests.

## Deviations from the spec

- **Spec §4 kept the `MixingViewModel`/`MixingUseCase` split.** This plan deletes the 4.1 job lookup outright (Task 2) and builds rev2.1 job lookup fresh (Tasks 10–12). Every line of the old code is shaped around 4.1 collections (`bom_loaded`, `collectionId`, paging), so porting it would mean rewriting all of it twice.
- **Badge login and the logout request go in Task 3**, not with the auth rework. This leaves the transport rework (Task 8) with exactly one caller, `ScramExchange`.
- **Settings needs no trim.** It already shows only Diagnostics (including the read-only derived device id), broker configuration and Session. This was verified, so there is no Settings task.
- **Three transport tests are consolidated.** `MqttRequestCorrelationTest`, `MqttResponseDeduplicationTest` and `MqttSessionExpiryTest` become one `Rev2TransportTest`. `Schema41EnvelopeTest` keeps only its timestamp tests and is renamed `MqttSchemaTest`.

---

## Phase 1: Strip

### Task 1: Finish deleting the mixing board

The six main files and six test files are already `git rm`'d and staged. What remains: routes, nav graph, call sites, and `WireNullToleranceTest`, which still references the deleted DTOs, so the test source set does not compile yet.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/WireNullToleranceTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NavRoutes` without `MIXING_BOARD`, `MIXING_AREAS`, `MIXING_AREA_BOARD`, `mixingAreas()`, `mixingAreaBoard()`.

- [ ] **Step 1: Confirm the staged state**

```bash
git status --short | grep "^D " | wc -l
```

Expected: `12`.

- [ ] **Step 2: Remove the mixing-board routes**

In `NavRoutes.kt` delete the constants `MIXING_BOARD`, `MIXING_AREAS`, `MIXING_AREA_BOARD` and the functions `mixingAreas()` and `mixingAreaBoard()`.

- [ ] **Step 3: Remove the mixing-board nav graph**

In `AppNavGraph.kt` delete the whole `navigation(startDestination = NavRoutes.MIXING_AREAS, route = NavRoutes.MIXING_BOARD) { ... }` block, and these imports:

```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModel
```

Replace the three call sites that navigated there with no-op lambdas (Tasks 2 and 5 remove the parameters):

- `HomeScreen(onOpenMixingBoard = { navController.navigate(NavRoutes.mixingAreas()) }, ...)` → `onOpenMixingBoard = {}`
- `JobLookupScreen(onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) }, ...)` → `onOpenMixing = {}`
- `IngredientScanScreen(onStartMixing = { collectionId -> navController.navigate(NavRoutes.mixingAreas(collectionId)) }, ...)` → `onStartMixing = {}`

- [ ] **Step 4: Remove the mixing-board types from `WireNullToleranceTest`**

In `responseRoots`, delete `MixingOverviewResponse::class.java` and `MachineCycleResultResponse::class.java`. Delete every import of a type that no longer exists: `ActiveCycleDto`, `ActiveRunDto`, `EquipmentDto`, `JandiDrumDto`, `MachineCycleResultResponse`, `MixingOverviewResponse`, `ReadyCollectionDto`, `ReadyMixDto`, `RunInputDto`, `AreaOverview`, `MachineCycleOutcome`, `MixingBoardUseCase`. Delete every test, and every field or helper, that uses any of them. That covers `an overview whose every field is null maps all the way to domain without throwing`, `a machine cycle result whose every field is null still reports the cycle`, and any array/embedded-object test whose fixture is a mixing DTO. Keep the three generic tests: `every wire type keeps the no-arg constructor…`, `an explicit null deserializes exactly like an omitted key…` and `no production code builds its own Gson`. The two reflective tests keep covering every surviving type.

- [ ] **Step 5: Verify no dangling references**

```bash
grep -rn "MixingBoard\|MixingArea\|mixingAreas\|mixingAreaBoard\|MixingOverview\|MachineCycle" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
graphify update .
git add -A
git commit -m "strip: remove the mixing board feature"
```

---

### Task 2: Delete ingredient collection and the 4.1 job lookup

The 4.1 job lookup and ingredient scan share `MixingViewModel`, `MixingUseCase` and `JobCardMessages.kt`, and both are replaced wholesale in Tasks 10–12, so they go together.

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing/` (whole package: `IngredientScanScreen.kt`, `JobLookupScreen.kt`, `MixingViewModel.kt`)
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`, `ProductionOrder.kt`, `ActiveJobsPage.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`, `JobCardMessages.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing/` (whole package)
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/BomLineTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt`, `BomLoadedResponseTest.kt`
- Modify: `NavRoutes.kt`, `AppNavGraph.kt`, `WireNullToleranceTest.kt`

**Interfaces:**
- Consumes: Task 1's `NavRoutes`.
- Produces: no `ui.mixing` package, no `MixingUseCase`, no `ProductionOrder`/`BomLine`/`ActiveJobsPage`. `NavRoutes` declares `HOME`, `LOGIN`, `SETTINGS`, `RFID_RECOVERY`. Home's Job Cards tile is a no-op until Task 12.

- [ ] **Step 1: Delete the files**

```bash
git rm -r app/src/main/java/com/mitas/ppnam/station2aa/ui/mixing \
          app/src/test/java/com/mitas/ppnam/station2aa/ui/mixing
git rm app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/IngredientScanOutcome.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/ProductionOrder.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/domain/model/ActiveJobsPage.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
       app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/domain/model/BomLineTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/IngredientScanResultTest.kt \
       app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/BomLoadedResponseTest.kt
```

- [ ] **Step 2: Reduce `NavRoutes`**

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val RFID_RECOVERY = "rfid/recovery"
}
```

(`RFID_RECOVERY` goes in Task 3.)

- [ ] **Step 3: Remove the mixing graph from `AppNavGraph.kt`**

Delete the whole `navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.MIXING) { ... }` block and the imports of `IngredientScanScreen`, `JobLookupScreen` and `MixingViewModel`. In the `HOME` composable change `onOpenJobCards = { navController.navigate(NavRoutes.MIXING) }` to `onOpenJobCards = {}`. Remove the `remember` import if nothing else uses it.

- [ ] **Step 4: Remove the deleted DTOs from `WireNullToleranceTest`**

Delete these `responseRoots` entries and their imports: `BomLoadedResponse`, `ActiveJobCardsListResponse`, `ActiveJobCardsInvalidatedResponse`, `IngredientCollectionCancelResultResponse`, `IngredientScanResultResponse`. Delete any remaining test whose fixture uses one of them.

- [ ] **Step 5: Verify no dangling references**

```bash
grep -rn "ui\.mixing\|MixingUseCase\|MixingViewModel\|ProductionOrder\b\|BomLine\b\|ActiveJobsPage\|IngredientScan\|BomLoaded\|ActiveJobCard\|JobCardLoad" app/src --include=*.kt
```

Expected: no output. If `PalletUseCase` or `RfidViewModel` still reference a deleted type, Task 3 deletes them next. In that case, run Task 3 Step 1 now and commit both tasks together with the Task 3 message.

- [ ] **Step 6: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
graphify update .
git add -A
git commit -m "strip: remove ingredient collection and the schema 4.1 job lookup"
```

---

### Task 3: Delete pallets, manager authorization, badge login and the logout request

rev2.1 has no pallet, badge-login, manager-action or logout messages.

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/ui/rfid/` (whole package)
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCase.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/PalletInfo.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt`
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ManagerAuthorization.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/ui/rfid/` (whole package)
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt`
- Delete: `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/PalletStateTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCase.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModel.kt`, `LoginScreen.kt`
- Modify: `NavRoutes.kt`, `AppNavGraph.kt`, `HomeScreen.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt`
- Modify: `WireNullToleranceTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `class AuthUseCase @Inject constructor(private val sessionHolder: OperatorSessionHolder, private val scramExchange: ScramExchange)` with `suspend fun login(username: String, password: String): Result<OperatorSession>` and `suspend fun logout(): Result<Unit>` (local only).
  - `ScramExchange.authenticate(username: String, password: String): Result<ScramProofResponse>`.
  - `ScramStartPayload(username, clientNonce, purpose = "login")`, `ScramProofPayload(challengeId, clientFinalWithoutProof, clientProof, purpose = "login")`.
  - `LoginMethod`, `BadgeLoginPayload`, `OperatorContextResponse`, `ManagerAction`, `ScramPurpose` no longer exist.
  - `data/rfid/` (`DataWedgeReceiver`, `ScanEventBus`, `ScanEvent`) is untouched.

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

- [ ] **Step 2: Remove the RFID route and the dead Home tiles**

In `NavRoutes.kt` delete `RFID_RECOVERY`. In `AppNavGraph.kt` delete the `composable(NavRoutes.RFID_RECOVERY) { ... }` block and the `RfidRecoveryScreen` import, and in the `HOME` composable delete the `onOpenMixingBoard` and `onFixATag` arguments.

In `HomeScreen.kt`, change the signature to:

```kotlin
fun HomeScreen(
    onOpenJobCards: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onExitApp: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
)
```

Delete the "Mixing Board" and "Fix a Tag" `HomeTile` calls, and the now-unused `Icons.Filled.Science` and `Icons.Filled.WifiTethering` imports. Change the remaining tile's subtitle from `"Start or resume a job"` to `"Look up a job card"`.

- [ ] **Step 3: Trim `AuthMessages.kt` to SCRAM login**

Replace the file's contents with:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 authentication: SCRAM-SHA-256 login only.
 *
 * There is no plaintext login, no badge login and no manager authorization — Station 2 answers any
 * `purpose` other than `login` with `purpose_not_enabled`, and rejects any field whose name
 * contains `password` with `password_field_forbidden`. Message-specific fields only; the transport
 * injects the envelope.
 */

/** `scram_start_requested`. */
data class ScramStartPayload(
    val username: String,
    val clientNonce: String,
    val purpose: String = "login",
)

/**
 * `scram_challenge`. Valid for 60 seconds, one use, bound to this device.
 *
 * [serverNonce] is the *combined* nonce and must begin with the client nonce we sent;
 * [serverFirstMessage] is the exact string that goes into the AuthMessage — it is reproduced
 * verbatim rather than rebuilt from the parts, since any difference in spelling breaks the proof.
 */
data class ScramChallengeResponse(
    val challengeId: String = "",
    val serverNonce: String = "",
    val salt: String = "",
    val iterations: Int = 0,
    val serverFirstMessage: String = "",
    val expiresAtUtc: String? = null,
)

/** `scram_proof_requested`. */
data class ScramProofPayload(
    val challengeId: String,
    val clientFinalWithoutProof: String,
    val clientProof: String,
    val purpose: String = "login",
)

/**
 * Response to `scram_proof_requested`. [serverSignature] must be validated before anything else
 * in it is trusted. Task 9 moves the session fields under `session` to match rev2.1.
 */
data class ScramProofResponse(
    val serverSignature: String = "",
    val operatorSessionId: String = "",
    val operatorId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList(),
    val sessionState: String? = null,
    val sessionExpiresAtUtc: String? = null,
)
```

- [ ] **Step 4: Reduce `ScramExchange.authenticate` to login**

Change the signature and the two payload constructions:

```kotlin
    suspend fun authenticate(
        username: String,
        password: String,
    ): Result<ScramProofResponse> {
```

```kotlin
            payload = ScramStartPayload(
                username = username,
                clientNonce = clientNonce,
            ),
```

```kotlin
            payload = ScramProofPayload(
                challengeId = challenge.challengeId,
                clientFinalWithoutProof = clientFinalWithoutProof,
                clientProof = proof.clientProofBase64,
            ),
```

Delete the `@param actionTarget` / `@param managerAction` KDoc lines. In the class KDoc, change "Login and manager authorization differ only in `purpose` and scope, so they share this." to "rev2.1 enables SCRAM for operator login only."

- [ ] **Step 5: Reduce `AuthUseCase` to SCRAM login and a local logout**

Replace the file's contents with:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import java.time.Instant
import javax.inject.Inject

class AuthUseCase @Inject constructor(
    private val sessionHolder: OperatorSessionHolder,
    private val scramExchange: ScramExchange,
) {

    /** A SCRAM-SHA-256 exchange — the password never goes on the wire. */
    suspend fun login(username: String, password: String): Result<OperatorSession> {
        val proof = scramExchange.authenticate(username, password)
            .getOrElse { return Result.failure(it) }

        val state = SessionState.fromWire(proof.sessionState)
        return when {
            proof.operatorSessionId.isBlank() ->
                Result.failure(Exception("Station 2 accepted the login but issued no session"))
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            state == SessionState.Closed ->
                Result.failure(Exception("Station 2 closed this session immediately"))
            else -> {
                val session = OperatorSession(
                    operatorSessionId = proof.operatorSessionId,
                    operatorId = proof.operatorId.orEmpty(),
                    operatorName = proof.displayName.orEmpty(),
                    role = proof.role.orEmpty(),
                    sessionState = state,
                    // A bad timestamp must not fail an otherwise valid login — expiry is
                    // display-only, and Station 2 enforces it regardless.
                    sessionExpiresAtUtc = proof.sessionExpiresAtUtc?.let {
                        try { Instant.parse(it) } catch (e: Exception) { null }
                    },
                    allowedActions = proof.allowedActions,
                    allowedTabs = proof.allowedTabs,
                )
                sessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /**
     * rev2.1 has no logout message, so this only forgets the session on this device. Station 2's
     * copy expires on its own at `expiresAtUtc`.
     */
    suspend fun logout(): Result<Unit> {
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond"
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
```

- [ ] **Step 6: Remove badge login from the login screen**

In `LoginViewModel.kt`:
- Delete the `scanEventBus` constructor parameter, `badgeScanJob`, `startListeningForBadgeScans()`, the `startListeningForBadgeScans()` call in `init`, both `badgeScanJob?.cancel()` calls, and `onCleared()`.
- Delete the `ScanEvent`, `ScanEventBus`, `LoginMethod` and `Job` imports.
- Replace `submitCredentials` and `attemptLogin` with:

```kotlin
    fun submitCredentials(username: String, password: String) {
        // Blocks re-entry for the whole LoggingIn -> LoggedIn span: a second tap arriving after
        // success but before Compose has navigated away must not start a second, concurrent login.
        if (_uiState.value != LoginUiState.Idle && _uiState.value !is LoginUiState.Error) return
        viewModelScope.launch {
            _uiState.value = LoginUiState.LoggingIn
            authUseCase.login(username, password)
                .onSuccess {
                    _uiState.value = LoginUiState.LoggedIn
                    _navigationEvent.send("home")
                }
                .onFailure { e ->
                    _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
                }
        }
    }
```

In `LoginScreen.kt`, delete the "or scan your badge" divider row (the composable around line 171–180) and its comment.

- [ ] **Step 7: Update `LoginViewModelTest`**

- Construct the ViewModel as `LoginViewModel(mockAuthUseCase, mockMqttRepository)`.
- Delete `mockScanEventBus`, `scanEvents` and their setup lines, and the `ScanEvent`/`ScanEventBus`/`LoginMethod` imports.
- Replace `mockAuthUseCase.login(LoginMethod.Credentials("operator1", "1234"))` with `mockAuthUseCase.login("operator1", "1234")`, and every `mockAuthUseCase.login(any())` with `mockAuthUseCase.login(any(), any())`.
- Delete the test `badge scan while showing an error still attempts login`.

- [ ] **Step 8: Update `AuthUseCaseTest`**

- Setup becomes `useCase = AuthUseCase(sessionHolder, scramExchange)`. Delete the `mqtt` field, `acceptedBadge`, `stubBadge`, and the `BadgeLoginPayload`, `OperatorContextResponse`, `ScramPurpose`, `MqttRepository`, `NextAction` and `ErrorCode` imports.
- `stubScram` becomes `whenever(scramExchange.authenticate(any(), any())).thenReturn(result)`.
- Every `useCase.login(LoginMethod.Credentials(u, p))` becomes `useCase.login(u, p)`.
- Delete these tests:
  - `a credentials login never publishes the password on login_requested`
  - `badge login still uses the single login topic with a badge payload`
  - `a badge login stores the session too`
  - `a rejected badge login fails with the operator-readable reason`
  - `a timeout during badge login fails with a connection message`
  - `being disconnected during badge login fails with a connection message`
  - `logout sends the envelope-only request and clears the session`
  - `logout clears the local session even when Station 2 never answers`
- Rename `a credentials login runs a SCRAM exchange with the login purpose` to `a login runs a SCRAM exchange with the operator's credentials` and make its verify `verify(scramExchange).authenticate("operator1", "pass")`, using whatever credentials the test passes.
- Add:

```kotlin
    @Test
    fun `logout clears the local session and sends nothing`() = runTest {
        stubScram(Result.success(provedLogin))
        useCase.login("operator1", "pass")

        useCase.logout()

        assertNull(sessionHolder.session.value)
    }
```

(`AuthUseCase` has no `MqttRepository` any more, so "sends nothing" is enforced by the compiler.)

- [ ] **Step 9: Remove the deleted DTOs from `WireNullToleranceTest`**

Delete the `PalletLookupResultResponse` and `OperatorContextResponse` roots and imports, the `ManagerAuthorization` import, and any test using them.

- [ ] **Step 10: Verify no dangling references**

```bash
grep -rn "PalletUseCase\|PalletInfo\|PalletLookup\|RfidRecovery\|RfidViewModel\|ManagerAuthorization\|ManagerAction\|ScramPurpose\|LoginMethod\|BadgeLogin\|OperatorContextResponse\|reader_logout\|login_requested\|RFID_RECOVERY\|pauseScanning" app/src --include=*.kt
```

Expected: no output.

- [ ] **Step 11: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
graphify update .
git add -A
git commit -m "strip: remove pallets, manager authorization, badge login and the logout request"
```

---

### Task 4: Delete the Room BOM cache

`UpgradeGate` stays: rev2.1 still returns `client_upgrade_required`.

**Files:**
- Delete: `app/src/main/java/com/mitas/ppnam/station2aa/data/local/` (`AppDatabase.kt`, `BomCacheDao.kt`, `BomCacheEntity.kt`)
- Delete: `app/src/androidTest/java/com/mitas/ppnam/station2aa/data/local/BomCacheDaoTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/di/AppModule.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: Task 2 (the cache's only writer is gone).
- Produces: `AppModule` provides only `provideMqttRepository`. Room is out of the build.

- [ ] **Step 1: Delete the files**

```bash
git rm -r app/src/main/java/com/mitas/ppnam/station2aa/data/local
git rm app/src/androidTest/java/com/mitas/ppnam/station2aa/data/local/BomCacheDaoTest.kt
```

- [ ] **Step 2: Reduce `AppModule`**

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

- [ ] **Step 3: Remove Room from the build**

In `app/build.gradle.kts` delete these four lines:

```kotlin
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)
```

In `gradle/libs.versions.toml` delete `room = "2.6.1"` and the four `room-*` library entries. Keep the `ksp` plugin — Hilt uses it.

- [ ] **Step 4: Verify**

```bash
grep -rni "bomcache\|appdatabase\|androidx.room\|libs.room" app/src app/build.gradle.kts gradle/libs.versions.toml
```

Expected: no output.

- [ ] **Step 5: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
graphify update .
git add -A
git commit -m "strip: remove the Room BOM cache"
```

---

### Task 5: Fix the stale reference path in `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Point the external-directory rule at the real location**

In `CLAUDE.md`, replace every occurrence of `C:\Dev\PPNAM-Station-2` with `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2`. There are three: the heading and two bullet points. Change nothing else.

- [ ] **Step 2: Verify**

```bash
grep -n "PPNAM-Station-2" CLAUDE.md
```

Expected: three lines, each containing `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: point CLAUDE.md at the real PPNAM-Station-2 location"
```

---

## Phase 2: The simulator speaks rev2.1

### Task 6: Convert the simulator to rev2.1

The simulator mirrors `Rev2ScannerProcessor.cs`: exactly four request suffixes, rev2.1 validation, rev2.1 replies with the body under `data`, SCRAM login only, and General `read`/`lookup`.

**Files:**
- Rewrite: `tools/backend-sim/envelope.py`
- Rewrite: `tools/backend-sim/handlers/scram.py`
- Create: `tools/backend-sim/handlers/rev2_general.py`
- Rewrite: `tools/backend-sim/handlers/__init__.py`
- Delete: `tools/backend-sim/handlers/auth.py`, `common.py`, `jobcards.py`, `mixing.py`, `ingredients.py`, `pallets.py`
- Modify: `tools/backend-sim/sim.py`, `tools/backend-sim/state.py`, `tools/backend-sim/seed/seed.json`, `tools/backend-sim/README.md`
- Delete from `tools/test-harness/`: `drive_mix_A.py`, `drive_mix_B.py`, `drive_mix_C.py`, `drive_mix_D.py`, `drive_mixing_open.py`, `drive_area_scope_check.py`, `drive_board_main.py`, `board.py`, `collect.py`, `collect_sim.py`, `d_assign_go.py`, `d_assign_sheet.py`, `make_pallets.py`, `pallets.json`, `pallets_sim.json`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `envelope.validate(topic_device, suffix, payload_bytes, ctx) -> dict`, raising `envelope.Rejection(error, message, data=None)`
  - `envelope.reply(device_id, message_id, received_at, success, message, error="", data=None) -> dict`
  - `envelope.response_suffix(request_suffix) -> str`
  - `Simulator.handle_request(device_id, request_type, payload)`
  - `Simulator.publish_invalidations()`
  - control frame `{"cmd":"invalidate"}` on `PPNAM/_sim/control`
  - `World.rev2_jobs`, `World.rev2_preparations`, `World.general_readers`, `World.auth_replies`
  - `rev2_general.seed_demo_jobs(world, log)`

- [ ] **Step 1: Delete the retired handlers and harness drivers**

```bash
git rm tools/backend-sim/handlers/auth.py tools/backend-sim/handlers/common.py \
       tools/backend-sim/handlers/jobcards.py tools/backend-sim/handlers/mixing.py \
       tools/backend-sim/handlers/ingredients.py tools/backend-sim/handlers/pallets.py
cd tools/test-harness
git rm drive_mix_A.py drive_mix_B.py drive_mix_C.py drive_mix_D.py \
       drive_mixing_open.py drive_area_scope_check.py drive_board_main.py \
       board.py collect.py collect_sim.py d_assign_go.py d_assign_sheet.py \
       make_pallets.py pallets.json pallets_sim.json
cd ../..
```

- [ ] **Step 2: Rewrite `envelope.py`**

```python
"""rev2.1 envelope handling, mirroring PPNAM.Station2.Core/Services/Rev2ScannerProcessor.cs.

Request validation, in the server's order:
  1. The suffix is one of the four subscribed request suffixes, else client_upgrade_required.
  2. The payload is at most 65,536 characters, else invalid_envelope.
  3. It is a JSON object with unique field names (case-insensitive), else invalid_envelope.
     (The server checks the top level only; this is stricter, at any depth.)
  4. No top-level field name contains "password" (case-insensitive), else password_field_forbidden.
  5. schemaVersion == "rev2.1", deviceId == topic device, messageId is 1-100 chars, timestampUtc is
     exactly yyyy-MM-ddTHH:mm:ss.ffffffZ, and the device is not "WPF", else invalid_envelope.

Direct replies carry no messageId of their own — correlation is inResponseToMessageId alone.
"""

import json
import re

from state import iso, utc_now

SCHEMA_VERSION = "rev2.1"
REQUEST_SUFFIXES = (
    "scram_start_requested",
    "scram_proof_requested",
    "rev2_general_requested",
    "rev2_rajoo_requested",
)
_RESPONSE_SUFFIX = {
    "scram_start_requested": "scram_challenge",
    "scram_proof_requested": "scram_proof_result",
    "rev2_rajoo_requested": "rev2_rajoo_result",
}
MAX_PAYLOAD_CHARS = 65536
TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")

SUCCESS_NEXT_ACTION = "Follow the saved job or preparation state."
FAILURE_NEXT_ACTION = ("Correct the request with a new message ID; uncertain transport retries "
                       "keep the identical body.")


class Rejection(Exception):
    """Raised to short-circuit into a failed reply. `data` is the snapshot to include, if any."""

    def __init__(self, error, message, data=None):
        super().__init__(message)
        self.error = error
        self.message = message
        self.data = data


def response_suffix(request_suffix):
    """Every unknown suffix answers on rev2_general_result, exactly as the server does."""
    return _RESPONSE_SUFFIX.get(request_suffix, "rev2_general_result")


def reply(device_id, message_id, received_at, success, message, error="", data=None):
    sent_at = utc_now()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "deviceId": device_id,
        "inResponseToMessageId": message_id,
        "receivedAtUtc": iso(received_at),
        "sentAtUtc": iso(sent_at),
        "durationMs": (sent_at - received_at).total_seconds() * 1000.0,
        "success": success,
        "error": error,
        "operatorMessage": message,
        "nextAction": SUCCESS_NEXT_ACTION if success else FAILURE_NEXT_ACTION,
        "data": data,
    }


def _unique_fields(pairs):
    seen = set()
    for name, _ in pairs:
        if name.lower() in seen:
            raise Rejection("invalid_envelope", "Request must be a JSON object with unique fields.")
        seen.add(name.lower())
    return dict(pairs)


def validate(topic_device, suffix, payload_bytes, ctx):
    """Return the request dict, or raise Rejection.

    ctx["messageId"] receives the request's messageId as soon as it is known, so a rejection can
    still be correlated — and stays "" when the failure came before the id could be read, which is
    exactly what the server does.
    """
    if suffix not in REQUEST_SUFFIXES:
        raise Rejection("client_upgrade_required", "Use the Rev 2 scanner contract.")
    text = payload_bytes.decode("utf-8", errors="replace")
    if len(text) > MAX_PAYLOAD_CHARS:
        raise Rejection("invalid_envelope", "Request too large.")
    try:
        req = json.loads(text, object_pairs_hook=_unique_fields)
    except Rejection:
        raise
    except ValueError:
        raise Rejection("invalid_envelope", "Invalid JSON or field type.")
    if not isinstance(req, dict):
        raise Rejection("invalid_envelope", "Request must be a JSON object with unique fields.")

    message_id = req.get("messageId")
    ctx["messageId"] = message_id if isinstance(message_id, str) else ""

    if any("password" in name.lower() for name in req):
        raise Rejection("password_field_forbidden",
                        "Use SCRAM authentication; password fields are prohibited.")

    timestamp = req.get("timestampUtc")
    if (req.get("schemaVersion") != SCHEMA_VERSION
            or req.get("deviceId") != topic_device
            or not isinstance(message_id, str) or not 1 <= len(message_id) <= 100
            or not isinstance(timestamp, str) or not TIMESTAMP_RE.match(timestamp)
            or topic_device.lower() == "wpf"):
        raise Rejection("invalid_envelope",
                        "Check schemaVersion, deviceId, messageId and the six-digit UTC timestamp.")
    return req
```

- [ ] **Step 3: Rewrite `handlers/scram.py`**

The crypto is unchanged from the current file. The functions now return the reply's `data` dict and raise `Rejection` with rev2.1 codes.

```python
"""rev2.1 SCRAM-SHA-256 login (RFC 7677).

scram_start_requested -> scram_challenge    data: the challenge
scram_proof_requested -> scram_proof_result data: serverSignature + session

Purpose, replay and message-id-conflict checks live in sim.py, ahead of these handlers, because
the server makes them before touching the SCRAM service.
"""

import base64
import hashlib
import hmac
import os
import unicodedata
from datetime import timedelta

from envelope import Rejection
from state import iso, utc_now

SCRAM_ITERATIONS = 4096
CHALLENGE_TTL_SECONDS = 60


def _salted_password(password, salt, iterations):
    """PBKDF2-HMAC-SHA-256 over the NFKC-normalized password's UTF-8 bytes."""
    normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
    return hashlib.pbkdf2_hmac("sha256", normalized, salt, iterations)


def _hmac(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()


def _escape_username(username):
    """RFC 5802 5.1: '=' before ',' — the other order re-escapes the '=' it just introduced."""
    return username.replace("=", "=3D").replace(",", "=2C")


def scram_start(world, log, req):
    """Issue a challenge — even for an unknown username, so this is not a user-enumeration oracle.
    An unknown user fails at the proof step exactly as a wrong password does."""
    username = req.get("username") or ""
    client_nonce = req.get("clientNonce") or ""
    if not username or not client_nonce:
        raise Rejection("authentication_failed", "username and clientNonce are required.")

    operator = world.find_operator(username=username)
    salt = hashlib.sha256(("ppnam-salt::" + username).encode("utf-8")).digest()[:16]
    server_nonce = client_nonce + base64.b64encode(os.urandom(18)).decode("ascii")
    server_first = "r=%s,s=%s,i=%d" % (
        server_nonce, base64.b64encode(salt).decode("ascii"), SCRAM_ITERATIONS)

    challenge_id = world.next_challenge_id()
    world.scram_challenges[challenge_id] = {
        "challengeId": challenge_id,
        "username": username,
        "deviceId": req["deviceId"],
        "clientNonce": client_nonce,
        "serverNonce": server_nonce,
        "salt": salt,
        "iterations": SCRAM_ITERATIONS,
        "serverFirstMessage": server_first,
        "operatorId": operator["operatorId"] if operator else None,
        "createdAt": utc_now(),
        "used": False,
    }
    log.step("scram: issued challenge %s for '%s'" % (challenge_id, username))
    return {
        "challengeId": challenge_id,
        "serverNonce": server_nonce,
        "salt": base64.b64encode(salt).decode("ascii"),
        "iterations": SCRAM_ITERATIONS,
        "serverFirstMessage": server_first,
        "expiresAtUtc": iso(utc_now() + timedelta(seconds=CHALLENGE_TTL_SECONDS)),
    }


def scram_proof(world, log, req):
    """Verify the client proof and issue a device-bound session."""
    challenge_id = req.get("challengeId")
    client_final = req.get("clientFinalWithoutProof") or ""
    client_proof_b64 = req.get("clientProof") or ""

    challenge = world.scram_challenges.get(challenge_id)
    if not challenge:
        log.fail("scram: unknown challengeId '%s'" % challenge_id)
        raise Rejection("authentication_failed", "Authentication challenge is unknown or expired.")
    if challenge["used"]:
        log.fail("scram: challenge %s already used" % challenge_id)
        raise Rejection("authentication_failed", "This authentication challenge was already used.")
    age = (utc_now() - challenge["createdAt"]).total_seconds()
    if age > CHALLENGE_TTL_SECONDS:
        log.fail("scram: challenge %s expired (%.0fs old)" % (challenge_id, age))
        raise Rejection("authentication_failed", "Authentication challenge expired.")
    if challenge["deviceId"] != req["deviceId"]:
        log.fail("scram: challenge was issued to a different device")
        raise Rejection("authentication_failed", "This challenge belongs to another device.")
    challenge["used"] = True

    operator = world.operator_by_id(challenge["operatorId"]) if challenge["operatorId"] else None
    if not operator:
        log.fail("scram: no such operator '%s'" % challenge["username"])
        raise Rejection("authentication_failed", "Invalid credentials.")

    salted = _salted_password(operator["password"], challenge["salt"], challenge["iterations"])
    client_key = _hmac(salted, b"Client Key")
    stored_key = hashlib.sha256(client_key).digest()
    auth_message = ("n=%s,r=%s,%s,%s" % (
        _escape_username(challenge["username"]),
        challenge["clientNonce"],
        challenge["serverFirstMessage"],
        client_final,
    )).encode("utf-8")
    client_signature = _hmac(stored_key, auth_message)
    expected_proof = bytes(a ^ b for a, b in zip(client_key, client_signature))
    try:
        supplied = base64.b64decode(client_proof_b64)
    except Exception:
        supplied = b""
    if not hmac.compare_digest(expected_proof, supplied):
        log.fail("scram: proof mismatch for '%s'" % challenge["username"])
        raise Rejection("authentication_failed", "Invalid credentials.")

    server_key = _hmac(salted, b"Server Key")
    server_signature = base64.b64encode(_hmac(server_key, auth_message)).decode("ascii")

    session = world.create_session(req["deviceId"], operator)
    log.ok("scram login: %s (%s) session %s"
           % (operator["operatorId"], operator["displayName"], session["sessionId"]))
    # The server's ScramProofResult, serialized camelCase. No allowedTabs/allowedActions exist.
    return {
        "serverSignature": server_signature,
        "session": {
            "sessionId": session["sessionId"],
            "operatorId": operator["operatorId"],
            "displayName": operator["displayName"],
            "role": operator["role"],
            "sourceDevice": req["deviceId"],
            "loggedInAtUtc": session["createdAtUtc"],
            "loggedOutAtUtc": None,
            "expiresAtUtc": session["expiresAtUtc"],
            "sessionState": "Active",
            "isActive": True,
        },
        "approver": None,
        "authorizationToken": None,
        "authorizationExpiresAtUtc": None,
    }
```

- [ ] **Step 4: Create `handlers/rev2_general.py`**

```python
"""rev2_general_requested -> rev2_general_result, for the job-lookup slice: `read` and `lookup`.

Mirrors Rev2ScannerProcessor.ProcessAsync: every reply's data is the full snapshot, with the
command's own result under `result`. The other five General actions exist in the contract but are
outside this slice, so they answer action_not_allowed with a message saying so.

Simulator approximation: the real server builds one-mix quantities from the SAP ProductTree. The
simulator divides each im_Manual line's planned quantity by a mix count derived from
config.rev2OutputPerMix. The shape is exact; the arithmetic is illustrative.
"""

import re

from envelope import Rejection
from state import iso, utc_now

ACTIONS = ("lookup", "read", "prepare", "capture", "confirm", "machine_start", "machine_finish")
SLICE_ACTIONS = ("lookup", "read")
OPEN_STATUSES = ("boposPlanned", "boposReleased")
DIGITS = re.compile(r"^[0-9]+$")
UNITS = {"269": "kg", "268": "each"}
LOOKUP_REJECTION = ("Only Standard Planned or Released jobs in header warehouse FAC with positive "
                    "outstanding output can be loaded.")


def check_action(req):
    """Runs BEFORE session authorization, as on the server."""
    action = req.get("action") or ""
    if action not in ACTIONS:
        raise Rejection("action_not_allowed", "Action is not part of this process mode.")
    if action not in SLICE_ACTIONS:
        raise Rejection("action_not_allowed",
                        "The simulator implements only read and lookup; '%s' is outside the "
                        "job-lookup slice." % action)
    return action


def handle(world, log, req, action):
    """Return (result, snapshot). result["success"] decides success/rev2_rejected."""
    world.general_readers.add(req["deviceId"])
    if action == "lookup":
        result = lookup(world, log, str(req.get("jobCard") or ""))
    else:
        result = {"success": True, "message": "Current progress.",
                  "targetId": req.get("targetId") or None, "cycleId": None}
    snap = snapshot(world, result.get("targetId") or req.get("targetId") or None)
    snap["result"] = result
    return result, snap


def _fail(message):
    return {"success": False, "message": message, "targetId": None, "cycleId": None}


def _round_half_away(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)


def lookup(world, log, job_card):
    if job_card in world.rev2_jobs:
        log.step("lookup %s: already held, earlier progress preserved" % job_card)
        return {"success": True, "message": "JC loaded.", "targetId": job_card, "cycleId": None}
    if not DIGITS.match(job_card):
        log.fail("lookup '%s': not a digit-only Production Order number" % job_card)
        return _fail(LOOKUP_REJECTION)
    order = world.sap_orders.get(job_card)
    if (not order
            or order.get("ProductionOrderType") != "bopotStandard"
            or order.get("ProductionOrderStatus") not in OPEN_STATUSES
            or order.get("Warehouse") != "FAC"
            or (order.get("PlannedQuantity") or 0) <= (order.get("CompletedQuantity") or 0)):
        log.fail("lookup %s: not an open Standard FAC order with outstanding output" % job_card)
        return _fail(LOOKUP_REJECTION)

    outstanding = order["PlannedQuantity"] - (order.get("CompletedQuantity") or 0)
    output_per_mix = world.config["rev2OutputPerMix"]
    mixes = _round_half_away(outstanding / output_per_mix)
    if mixes <= 0:
        return _fail("The current BOM material required for outstanding output does not round to "
                     "a positive supported whole-mix count.")
    materials = []
    for line in order.get("ProductionOrderLines", []):
        if line.get("ProductionOrderIssueType") != "im_Manual":
            continue
        planned = line.get("PlannedQuantity") or 0.0
        materials.append({
            "code": line["ItemNo"],
            "name": line.get("ItemName") or "",
            "unit": UNITS.get(str(line.get("UoMCode")), str(line.get("UoMCode"))),
            "perMix": round(planned / mixes, 3),
            "required": round(planned, 3),
            "collected": 0.0,
            "excluded": False,
        })
    world.rev2_jobs[job_card] = {
        "id": job_card,
        "product": order.get("ProductDescription") or "",
        "mode": "General",
        "unit": "each",
        "overallQuantity": order["PlannedQuantity"],
        "scopeQuantity": outstanding,
        "sapCompletedAtLookup": order.get("CompletedQuantity") or 0.0,
        "outputPerMix": output_per_mix,
        "requiredMixes": mixes,
        "materials": materials,
        "capturedAtUtc": iso(utc_now()),
        "activeSessionId": None,
        "rajooMachineId": None,
        "closed": False,
        "ingredientChoices": None,
        "exceptionListRevision": None,
    }
    log.transition("lookup %s: job saved locally, %d mixes, %d materials"
                   % (job_card, mixes, len(materials)))
    return {"success": True, "message": "JC loaded.", "targetId": job_card, "cycleId": None}


def snapshot(world, target_id):
    preparation = world.rev2_preparations.get(target_id) if target_id else None
    job_id = preparation["jobId"] if preparation else target_id
    job = world.rev2_jobs.get(job_id) if job_id else None
    preparations = list(world.rev2_preparations.values())
    return {
        "jobs": [{"id": j["id"], "product": j["product"], "closed": j["closed"],
                  "requiredMixes": j["requiredMixes"]} for j in world.rev2_jobs.values()],
        "job": None if job is None else dict(
            job, allocatedMixes=sum(p["mixCount"] for p in preparations if p["jobId"] == job["id"])),
        "preparation": preparation if job is not None else None,
        "preparations": preparations,
        "machines": [],
        "exceptionListRevision": 1,
        "ingredientExceptions": [],
        "requiredIngredientChoices": [],
    }


def seed_demo_jobs(world, log):
    """One held job with one preparation, so a fresh login sees a non-empty list."""
    if lookup(world, log, "510019068")["success"]:
        world.rev2_preparations["PREP_demo0001"] = {
            "id": "PREP_demo0001", "jobId": "510019068", "mixCount": 2, "mixed": 0,
            "produced": 0, "stage": "Collecting", "ingredientChoices": [],
            "exceptionListRevision": 1, "mixerId": None, "productionId": None, "cycleId": None,
            "startedAtUtc": iso(utc_now()), "confirmedAtUtc": None, "startedBy": "SEEDED",
            "materials": [],
        }
        log.transition("seed: job 510019068 held with preparation PREP_demo0001")
```

- [ ] **Step 5: Rewrite `handlers/__init__.py`**

```python
"""rev2.1 handlers: SCRAM login and the General job-lookup actions. Dispatch lives in sim.py."""
```

- [ ] **Step 6: Add rev2.1 state to `state.py` and the seed**

In `World.__init__`, after `self.auth_tokens = {}`, add:

```python
        # rev2.1
        self.rev2_jobs = {}           # JC -> job dict (the General `job` snapshot shape)
        self.rev2_preparations = {}   # PREP_ id -> preparation dict
        self.general_readers = set()  # devices that made an authenticated General request
        self.auth_replies = {}        # (device, suffix, messageId) -> {"body", "data", "expires"}
```

Add this method to `World`:

```python
    def expire_auth_replies(self, now):
        for key in [k for k, v in self.auth_replies.items() if v["expires"] < now]:
            del self.auth_replies[key]
```

In `seed/seed.json`, add `"rev2OutputPerMix": 50.0` to the `config` object.

- [ ] **Step 7: Rewrite the request path in `sim.py`**

Replace the imports

```python
import envelope
from envelope import Rejection, Replay, build_response
from handlers import REGISTRY, RETIRED_REQUEST_TYPES
from simlog import SimLogger
from state import World
```

with

```python
import envelope
from envelope import Rejection
from handlers import rev2_general, scram
from simlog import SimLogger
from state import World, utc_now
```

In `Simulator.__init__`, replace the `demo_collections` block with:

```python
        if getattr(args, "demo_collections", False):
            rev2_general.seed_demo_jobs(self.world, self.log)
```

Replace `publish_response` with:

```python
    def publish_response(self, device_id, response_type, response):
        topic = f"{STATION_BASE}/{device_id}/res/{response_type}"
        self.client.publish(topic, json.dumps(response, ensure_ascii=False), qos=1, retain=False)
        self.log.wire("out", topic, response)
        outcome = "SUCCESS" if response.get("success") else f"FAILED ({response.get('error')})"
        self.log.tx(f"res/{response_type} -> {device_id}: {outcome} "
                    f"inResponseTo={response.get('inResponseToMessageId')!r}")
        return topic

    def publish_invalidations(self):
        """The server's PreparationsChanged push, to every device that has read General."""
        for device_id in sorted(self.world.general_readers):
            self.publish_response(device_id, "active_job_cards_invalidated", {
                "schemaVersion": envelope.SCHEMA_VERSION,
                "deviceId": device_id,
                "messageId": f"rev2-preparations-{os.urandom(16).hex()}",
                "timestampUtc": iso(utc_now()),
                "mode": "General",
                "reason": "preparation_created",
                "nextAction": "read",
            })
```

and add `from state import iso` to the `state` import line (`from state import World, iso, utc_now`).

In `_apply_control`, delete the `save_mix_plan` branch and add:

```python
        if kind == "invalidate":
            self.publish_invalidations()
            self.log.ok("sim-control: INVALIDATE — active_job_cards_invalidated pushed")
            return
```

Delete the methods `_fault_reject` and `_mangle_login`. Replace the whole `handle_request` method with:

```python
    def handle_request(self, device_id, request_type, payload):
        self.log.wire("in", f"{STATION_BASE}/{device_id}/req/{request_type}", _redacted(payload))
        if self._faults and self._take_fault(request_type, ("withhold",)):
            self.log.warn(f"sim-control: FAULT withhold — dropping req/{request_type} "
                          f"(no processing, no response)")
            return
        received_at = utc_now()
        ctx = {"messageId": ""}
        try:
            req = envelope.validate(device_id, request_type, payload, ctx)
            response = self._dispatch(device_id, request_type, payload, req, received_at)
        except Rejection as rej:
            self.log.fail(f"req/{request_type} from {device_id}: {rej.error} — {rej.message}")
            response = envelope.reply(device_id, ctx["messageId"], received_at, False,
                                      rej.message, rej.error, rej.data)
        except Exception:  # noqa: BLE001 — mirrors the server's outcome_unconfirmed catch-all
            import traceback
            self.log.fail("unhandled error:\n" + traceback.format_exc())
            response = envelope.reply(
                device_id, ctx["messageId"], received_at, False,
                "The result could not be confirmed. Retry the identical request and message ID.",
                "outcome_unconfirmed")
        self.publish_response(device_id, envelope.response_suffix(request_type), response)

    def _dispatch(self, device_id, request_type, payload, req, received_at):
        message_id = req["messageId"]
        if request_type.startswith("scram_"):
            key = (device_id, request_type, message_id)
            self.world.expire_auth_replies(received_at)
            prior = self.world.auth_replies.get(key)
            if prior:
                if prior["body"] == payload:
                    return envelope.reply(device_id, message_id, received_at, True,
                                          "Authentication response replayed.", data=prior["data"])
                raise Rejection("message_id_conflict", "Message ID body mismatch.")
            if req.get("purpose") != "login":
                raise Rejection("purpose_not_enabled", "Only operator login is enabled.")
            if request_type == "scram_start_requested":
                data, text = scram.scram_start(self.world, self.log, req), "Challenge issued."
            else:
                data, text = scram.scram_proof(self.world, self.log, req), "Signed in."
            self.world.auth_replies[key] = {"body": payload, "data": data,
                                            "expires": received_at + timedelta(seconds=60)}
            return envelope.reply(device_id, message_id, received_at, True, text, data=data)

        if request_type == "rev2_rajoo_requested":
            raise Rejection("action_not_allowed", "The simulator does not implement the Rajoo family.")
        action = rev2_general.check_action(req)
        session, err = self.world.get_session(device_id, req.get("sessionId") or "")
        if not session:
            self.log.fail(f"session: {err}")
            raise Rejection("operator_session_invalid", "Sign in on this device before continuing.")
        result, snap = rev2_general.handle(self.world, self.log, req, action)
        return envelope.reply(device_id, message_id, received_at, result["success"],
                              result["message"], "" if result["success"] else "rev2_rejected", snap)
```

Update the module docstring's first line to `"""Station 2 backend simulator — answers the Android handheld's rev2.1 MQTT traffic`. In `run()`, change the start-up line's `f"(schema {envelope.SCHEMA_VERSION}, contract v4) — "` to `f"(schema {envelope.SCHEMA_VERSION}) — "` and delete the `tolerance {…}` part of that message. In `main()` change the description to `"Station 2 MQTT backend simulator (rev2.1)"`.

- [ ] **Step 8: Verify nothing references the old modules**

```bash
grep -rn "REGISTRY\|RETIRED_REQUEST\|build_response\|Replay\|jobcards\|ingredients\|pallets\|mixing\b\|handlers.auth\|handlers.common" tools/backend-sim --include=*.py | grep -v selftest.py
```

Expected: no output. (`selftest.py` is rewritten in Task 7.)

- [ ] **Step 9: Smoke-test in process**

```bash
cd tools/backend-sim
python -c "
import argparse, json, queue, sim
a = argparse.Namespace(host='x', port=0, transport='tcp', ws_path='/mqtt', tls=False, username='', password='', window=None, tolerance=None, yield_to_real=False, no_color=True, demo_collections=True)
s = sim.Simulator(a)
out = []
class C:
    def publish(self, t, p=None, qos=0, retain=False, properties=None):
        out.append((t, json.loads(p)))
        class R:
            def wait_for_publish(self, timeout=None): pass
        return R()
s.client = C()
s.handle_request('scanner_smoke', 'job_card_load_requested', b'{}')
s.handle_request('scanner_smoke', 'rev2_general_requested', b'{\"schemaVersion\":\"4.1\"}')
for t, r in out: print(t, r['success'], r['error'])
"
cd ../..
```

Expected:

```
PPNAM/station_2/scanner_smoke/res/rev2_general_result False client_upgrade_required
PPNAM/station_2/scanner_smoke/res/rev2_general_result False invalid_envelope
```

- [ ] **Step 10: Update the README**

In `tools/backend-sim/README.md`:
- Change the intro to say the simulator speaks **rev2.1**: SCRAM login plus General `read`/`lookup`.
- In the seed world section, list `operator1`/`pass` and `manager1`/`secret` without badges. State that job `510019068` is held at start with preparation `PREP_demo0001`, and that `510018531` is a closed order and so a lookup rejection.
- Document the `{"cmd":"invalidate"}` control frame on `PPNAM/_sim/control`.
- Delete the `--tolerance` row and every mention of collections, mixing, pallets and badges.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "sim: speak rev2.1 — SCRAM login and General read/lookup"
```

---

### Task 7: Rewrite `selftest.py` as the rev2.1 conformance suite

**Files:**
- Rewrite: `tools/backend-sim/selftest.py`

**Interfaces:**
- Consumes: Task 6's `Simulator`, `envelope`, control frames.
- Produces: `python tools/backend-sim/selftest.py --direct` exits 0 when every rev2.1 check passes.

- [ ] **Step 1: Write the suite**

```python
"""rev2.1 conformance self-test: a fake handheld driving the simulator through login and the
General read/lookup slice, plus envelope negative probes.

    python selftest.py --direct                 # in-process, no broker needed
    python selftest.py [--host …] [--port …]    # over MQTT against a running sim

Exits 0 when every check passes, 1 on the first deviation.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import queue
import re
import sys
import time
import unicodedata
import uuid
from datetime import datetime, timezone

DEVICE = "scanner_selftest"
CHECKS = {"passed": 0}
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
REPLY_KEYS = {"schemaVersion", "deviceId", "inResponseToMessageId", "receivedAtUtc", "sentAtUtc",
              "durationMs", "success", "error", "operatorMessage", "nextAction", "data"}


def now_iso():
    now = datetime.now(timezone.utc)
    return "%s.%06dZ" % (now.strftime("%Y-%m-%dT%H:%M:%S"), now.microsecond)


def check(cond, label, detail=""):
    if cond:
        CHECKS["passed"] += 1
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}  {detail}")
        sys.exit(1)


class Handheld:
    """Over MQTT against a running simulator."""

    def __init__(self, host, port):
        import paho.mqtt.client as mqtt
        self.rx = queue.Queue()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=DEVICE,
                                  protocol=mqtt.MQTTv5)
        self.client.on_message = lambda c, u, m: self.rx.put(m)
        self.client.connect(host, port, keepalive=30)
        self.client.loop_start()
        self.client.subscribe([(f"PPNAM/station_2/{DEVICE}/res/+", 1)])
        time.sleep(0.5)
        self.session = ""

    def send_raw(self, suffix, text):
        self.client.publish(f"PPNAM/station_2/{DEVICE}/req/{suffix}", text, qos=1)

    def invalidate(self):
        self.client.publish("PPNAM/_sim/control", json.dumps({"cmd": "invalidate"}), qos=1)

    def close(self):
        self.client.loop_stop()
        self.client.disconnect()

    # ---- shared ----
    def envelope(self, fields, msg_id=None, schema="rev2.1", timestamp=None, session=True):
        body = dict(fields)
        body.update({"schemaVersion": schema, "deviceId": DEVICE,
                     "messageId": msg_id or uuid.uuid4().hex, "timestampUtc": timestamp or now_iso()})
        if session and self.session:
            body["sessionId"] = self.session
        return body

    def request(self, suffix, fields=None, **kw):
        body = self.envelope(fields or {}, **kw)
        self.send_raw(suffix, json.dumps(body))
        return self.await_reply(body["messageId"]), body

    def await_reply(self, msg_id, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            r = json.loads(m.payload)
            if r.get("inResponseToMessageId") == msg_id:
                r["_topic"] = m.topic
                return r
        return None

    def await_push(self, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            if m.topic.endswith("/res/active_job_cards_invalidated"):
                return json.loads(m.payload)
        return None

    def scram_login(self, username, password, purpose="login"):
        client_nonce = base64.b64encode(os.urandom(18)).decode("ascii")
        r, _ = self.request("scram_start_requested",
                            {"username": username, "clientNonce": client_nonce, "purpose": purpose},
                            session=False)
        if not r or not r["success"]:
            return r
        ch = r["data"]
        salt = base64.b64decode(ch["salt"])
        normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
        salted = hashlib.pbkdf2_hmac("sha256", normalized, salt, ch["iterations"])
        client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
        stored_key = hashlib.sha256(client_key).digest()
        escaped = username.replace("=", "=3D").replace(",", "=2C")
        client_final = f"c=biws,r={ch['serverNonce']}"
        auth_message = f"n={escaped},r={client_nonce},{ch['serverFirstMessage']},{client_final}".encode()
        signature = hmac.new(stored_key, auth_message, hashlib.sha256).digest()
        proof = bytes(a ^ b for a, b in zip(client_key, signature))
        pr, _ = self.request("scram_proof_requested", {
            "challengeId": ch["challengeId"], "clientFinalWithoutProof": client_final,
            "clientProof": base64.b64encode(proof).decode("ascii"), "purpose": purpose,
        }, session=False)
        if pr and pr["success"]:
            server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
            expected = base64.b64encode(
                hmac.new(server_key, auth_message, hashlib.sha256).digest()).decode("ascii")
            pr["_serverSignatureValid"] = hmac.compare_digest(expected, pr["data"]["serverSignature"])
        return pr


class _FakeMsg:
    def __init__(self, topic, payload):
        self.topic = topic
        self.payload = payload.encode() if isinstance(payload, str) else payload


class _FakeClient:
    def __init__(self, rx):
        self.rx = rx

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        if "/res/" in topic:
            self.rx.put(_FakeMsg(topic, payload))

        class _R:
            def wait_for_publish(self, timeout=None):
                pass
        return _R()

    def subscribe(self, *a, **k):
        pass


class DirectHandheld(Handheld):
    """In-process: send_raw calls handle_request directly, as the worker thread would."""

    def __init__(self):
        import sim as sim_module
        args = argparse.Namespace(host="direct", port=0, transport="tcp", ws_path="/mqtt",
                                  tls=False, username="", password="", window=None,
                                  tolerance=None, yield_to_real=False, no_color=True,
                                  demo_collections=True)
        self.sim = sim_module.Simulator(args)
        self.rx = queue.Queue()
        self.sim.client = _FakeClient(self.rx)
        self.session = ""
        print(f"direct mode: simulator in-process, logs at {self.sim.log.run_dir}")

    def send_raw(self, suffix, text):
        self.sim.handle_request(DEVICE, suffix, text.encode())

    def invalidate(self):
        self.sim.publish_invalidations()

    def close(self):
        self.sim.log.close()


def check_reply_shape(r, label):
    check(r is not None, f"{label}: a reply arrived")
    check(REPLY_KEYS <= set(r), f"{label}: reply carries every rev2.1 envelope field",
          sorted(REPLY_KEYS - set(r)))
    check("messageId" not in r, f"{label}: a direct reply carries no messageId of its own")
    check(r["schemaVersion"] == "rev2.1", f"{label}: schemaVersion is rev2.1")
    check(TS_RE.match(r["sentAtUtc"] or "") is not None, f"{label}: sentAtUtc has six fractional digits")
    check(isinstance(r["durationMs"], (int, float)), f"{label}: durationMs is numeric")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--direct", action="store_true")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    args = parser.parse_args()
    hh = DirectHandheld() if args.direct else Handheld(args.host, args.port)

    print("envelope")
    mid = uuid.uuid4().hex
    hh.send_raw("job_card_load_requested", json.dumps(hh.envelope({}, msg_id=mid)))
    # The server rejects an unknown suffix before reading the body, so the reply is uncorrelated.
    r = hh.await_reply("")
    check(r and r["error"] == "client_upgrade_required", "a retired 4.1 suffix -> client_upgrade_required")
    check(r["_topic"].endswith("/res/rev2_general_result"), "…answered on rev2_general_result")

    r, _ = hh.request("rev2_general_requested", {"action": "read"}, schema="4.1")
    check(r and r["error"] == "invalid_envelope", "schemaVersion 4.1 -> invalid_envelope")

    r, _ = hh.request("rev2_general_requested", {"action": "read"},
                      timestamp=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    check(r and r["error"] == "invalid_envelope", "a timestamp without six fractional digits -> invalid_envelope")

    r, _ = hh.request("rev2_general_requested", {"action": "read"}, msg_id="x" * 101)
    check(r and r["error"] == "invalid_envelope", "a 101-character messageId -> invalid_envelope")

    r, _ = hh.request("scram_start_requested", {"username": "operator1", "clientNonce": "n",
                                                "purpose": "login", "Password": "x"}, session=False)
    check(r and r["error"] == "password_field_forbidden", "any field containing 'password' -> password_field_forbidden")

    body = hh.envelope({"action": "read"})
    hh.send_raw("rev2_general_requested", json.dumps(body)[:-1] + ',"ACTION":"read"}')
    r = hh.await_reply("")
    check(r and r["error"] == "invalid_envelope", "a case-insensitive duplicate field -> invalid_envelope")
    check(r["inResponseToMessageId"] == "", "…uncorrelatable, as on the server")

    print("auth")
    r = hh.scram_login("operator1", "pass", purpose="manager_action")
    check(r and r["error"] == "purpose_not_enabled", "a manager_action purpose -> purpose_not_enabled")
    r = hh.scram_login("operator1", "wrong")
    check(r and r["error"] == "authentication_failed", "a wrong password -> authentication_failed")
    r = hh.scram_login("operator1", "pass")
    check_reply_shape(r, "scram proof")
    check(r["success"], "a correct password signs in")
    check(r.get("_serverSignatureValid"), "the server signature verifies")
    session = r["data"]["session"]
    check(session["sessionId"] and session["isActive"], "data.session carries an active sessionId")
    check("allowedTabs" not in json.dumps(r), "login returns no allowedTabs")
    hh.session = session["sessionId"]

    start = {"username": "operator1", "clientNonce": "fixed-nonce", "purpose": "login"}
    first, body = hh.request("scram_start_requested", start, session=False)
    hh.send_raw("scram_start_requested", json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["data"]["challengeId"] == first["data"]["challengeId"],
          "an identical auth request replays the stored reply")
    changed = dict(body, clientNonce="other-nonce")
    hh.send_raw("scram_start_requested", json.dumps(changed))
    r = hh.await_reply(body["messageId"])
    check(r and r["error"] == "message_id_conflict", "the same auth messageId with a new body -> message_id_conflict")

    print("general")
    r, _ = hh.request("rev2_general_requested", {"action": "read"}, session=False)
    check(r and r["error"] == "operator_session_invalid", "read without a session -> operator_session_invalid")

    r, _ = hh.request("rev2_general_requested", {"action": "prepare", "targetId": "510019068"})
    check(r and r["error"] == "action_not_allowed", "prepare is outside the slice -> action_not_allowed")
    r, _ = hh.request("rev2_general_requested", {"action": "teleport"})
    check(r and r["error"] == "action_not_allowed", "an unknown action -> action_not_allowed")

    r, _ = hh.request("rev2_general_requested", {"action": "read"})
    check_reply_shape(r, "read")
    check(r["success"] and r["error"] == "", "read succeeds with an empty error")
    ids = [j["id"] for j in r["data"]["jobs"]]
    check("510019068" in ids, "the seeded job is in data.jobs", ids)
    check(r["data"]["job"] is None, "read with no target has no job detail")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "510019068"})
    check(r["success"] and r["data"]["job"]["id"] == "510019068", "lookup of an open FAC job succeeds")
    job = r["data"]["job"]
    check(job["requiredMixes"] > 0 and job["materials"], "the job has mixes and materials")
    check(all({"code", "name", "unit", "perMix", "required", "collected", "excluded"} <= set(m)
              for m in job["materials"]), "every material carries the rev2.1 material fields")
    check(r["data"]["result"]["targetId"] == "510019068", "data.result names the target")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "510018531"})
    check(not r["success"] and r["error"] == "rev2_rejected", "lookup of a closed order -> rev2_rejected")
    check(isinstance(r["data"]["jobs"], list), "…and still returns the snapshot")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "JC-24001"})
    check(r["error"] == "rev2_rejected", "a non-digit job card -> rev2_rejected")

    r, _ = hh.request("rev2_general_requested", {"action": "read", "targetId": "510019068"})
    check(r["data"]["job"]["id"] == "510019068", "read with a target returns that job")
    check(r["data"]["job"]["allocatedMixes"] == 2, "…with allocatedMixes from its preparations")
    check(any(p["jobId"] == "510019068" for p in r["data"]["preparations"]), "…and its preparations")

    print("push")
    hh.invalidate()
    push = hh.await_push()
    check(push is not None, "active_job_cards_invalidated reaches a device that has read General")
    check(push["mode"] == "General" and push["reason"] == "preparation_created"
          and push["nextAction"] == "read", "the push carries mode, reason and nextAction")
    check(push["messageId"] and "inResponseToMessageId" not in push,
          "the push has its own messageId and no correlation id")

    hh.close()
    print(f"\nall {CHECKS['passed']} checks passed")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run it**

```bash
python tools/backend-sim/selftest.py --direct
```

Expected: last line `all N checks passed`, exit code 0. If a check fails, fix the **simulator** (Task 6 code), not the check. Each check restates a line of the spec's §2 wire-facts table.

- [ ] **Step 3: Commit**

```bash
git add tools/backend-sim/selftest.py
git commit -m "sim: rewrite the self-test as the rev2.1 conformance suite"
```

---

## Phase 3: The transport speaks rev2.1

### Task 8: rev2.1 envelopes, outcomes, vocabulary and correlation

After this task the app no longer speaks 4.1. Login is broken until Task 9, which is accepted.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttSchema.kt`
- Rewrite: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`
- Rewrite: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttOutcome.kt`
- Rewrite: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelope.kt`
- Rewrite: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/ResponseEnvelope.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/Rev2TransportTest.kt`
- Rewrite: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt`, `MqttVocabularyTest.kt`, `dto/ResponseEnvelopeTest.kt`
- Rename + trim: `Schema41EnvelopeTest.kt` → `MqttSchemaTest.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt`
- Delete: `MqttRequestCorrelationTest.kt`, `MqttResponseDeduplicationTest.kt`, `MqttSessionExpiryTest.kt`

**Interfaces:**
- Consumes: Task 3's `ScramExchange`.
- Produces:
  - `MqttRepository.request(requestType: String, responseType: String, payload: Any, responseClass: Class<T>): MqttOutcome<T>`
  - `MqttOutcome.Accepted<T>(body: T)`, `MqttOutcome.Rejected<T>(body: T?, error: ErrorCode?, operatorMessage: String?)`, `MqttOutcome.NoResponse(kind: FailureKind)`
  - `ErrorCode` constants: `INVALID_ENVELOPE`, `PASSWORD_FIELD_FORBIDDEN`, `OPERATOR_SESSION_INVALID`, `ACTION_NOT_ALLOWED`, `MESSAGE_ID_CONFLICT`, `REV2_REJECTED`, `CLIENT_UPGRADE_REQUIRED`, `OUTCOME_UNCONFIRMED`, `AUTHENTICATION_FAILED`, `PURPOSE_NOT_ENABLED`. `NextAction` no longer exists.
  - `ResponseEnvelope` with `errorCode: ErrorCode?` and `displayMessage: String?`
  - `RequestEnvelope.build(gson, payload, messageId, deviceId, sessionId: String?, timestampUtc): String`
  - `MqttSchema.VERSION = "rev2.1"`
  - `data class TestBody(val value: String = "")` in test package `data.mqtt` (moved from the deleted correlation test)

- [ ] **Step 1: Write `Rev2TransportTest` (failing)**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

data class TestBody(val value: String = "")

/**
 * The rev2.1 request/response contract as the transport sees it: envelope, body under `data`,
 * correlation on `inResponseToMessageId`, duplicate handling, and the request-side session guard.
 */
class Rev2TransportTest {

    private val device = "scanner_5c64df8d86a8"
    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun sent(index: Int): JsonObject =
        JsonParser.parseString(String(published[index].second)).asJsonObject

    private fun idOf(index: Int): String = sent(index)["messageId"].asString

    private fun session(id: String) =
        OperatorSession(operatorSessionId = id, operatorId = "OP-1", operatorName = "Op", role = "Worker")

    private fun reply(
        inResponseTo: String,
        success: Boolean = true,
        error: String = "",
        message: String = "",
        data: String? = """{"value":"ok"}""",
    ) {
        val dataPart = if (data == null) "" else ""","data":$data"""
        val json = """{"schemaVersion":"rev2.1","deviceId":"$device",""" +
            """"inResponseToMessageId":"$inResponseTo","receivedAtUtc":"2026-09-30T10:00:00.000000Z",""" +
            """"sentAtUtc":"2026-09-30T10:00:00.012000Z","durationMs":12.5,"success":$success,""" +
            """"error":"$error","operatorMessage":"$message",""" +
            """"nextAction":"Follow the saved job or preparation state."$dataPart}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
    }

    private fun push(messageId: String?) {
        val idPart = if (messageId == null) "" else """"messageId":"$messageId","""
        val json = """{"schemaVersion":"rev2.1","deviceId":"$device",$idPart""" +
            """"timestampUtc":"2026-09-30T10:00:00.000000Z","mode":"General",""" +
            """"reason":"preparation_created","nextAction":"read"}"""
        repo.handleIncomingResponse(
            "PPNAM/station_2/$device/res/active_job_cards_invalidated", json.toByteArray()
        )
    }

    private suspend fun read() = repo.request(
        "rev2_general_requested", "rev2_general_result", mapOf("action" to "read"), TestBody::class.java
    )

    // ---- envelope -------------------------------------------------------------------------------

    @Test
    fun `publishes to the device's own req topic`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        assertEquals("PPNAM/station_2/$device/req/rev2_general_requested", published[0].first)
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a workflow request carries the rev2_1 envelope and the active session`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        val body = sent(0)
        assertEquals("rev2.1", body["schemaVersion"].asString)
        assertEquals(device, body["deviceId"].asString)
        assertEquals("sess-A", body["sessionId"].asString)
        assertEquals("read", body["action"].asString)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z""")
            .matches(body["timestampUtc"].asString))
        assertTrue(body["messageId"].asString.length in 1..100)
        assertFalse(body.has("operatorSessionId"))
        assertFalse(body.has("correlationKey"))
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a request with no session omits sessionId`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        assertFalse(sent(0).has("sessionId"))
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a SCRAM request never carries a session even when one is active`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async {
            repo.request("scram_start_requested", "scram_challenge", mapOf("username" to "op"), TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        assertFalse(sent(0).has("sessionId"))
        reply(idOf(0)); call.await()
    }

    // ---- correlation and parsing ------------------------------------------------------------

    @Test
    fun `two concurrent requests answered out of order each get their own body`() = runTest {
        val first = async { read() }
        val second = async { read() }
        while (published.size < 2) yield()
        assertNotEquals(idOf(0), idOf(1))

        reply(idOf(1), data = """{"value":"second"}""")
        reply(idOf(0), data = """{"value":"first"}""")

        assertEquals("first", (first.await() as MqttOutcome.Accepted).body.value)
        assertEquals("second", (second.await() as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a success with no data is a malformed response`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), data = null)
        assertEquals(MqttOutcome.NoResponse(FailureKind.MalformedResponse), call.await())
    }

    @Test
    fun `a rejection without data carries a null body, the code and the operator message`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "invalid_envelope", message = "Check schemaVersion.", data = null)
        val outcome = call.await() as MqttOutcome.Rejected
        assertNull(outcome.body)
        assertEquals(ErrorCode.INVALID_ENVELOPE, outcome.error)
        assertEquals("Check schemaVersion.", outcome.operatorMessage)
    }

    @Test
    fun `a rejection with data keeps the snapshot body`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "rev2_rejected", data = """{"value":"snapshot"}""")
        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals("snapshot", outcome.body?.value)
        assertEquals(ErrorCode.REV2_REJECTED, outcome.error)
    }

    @Test
    fun `a rejection with a blank error and message reports both as absent`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "", message = "", data = null)
        val outcome = call.await() as MqttOutcome.Rejected
        assertNull(outcome.error)
        assertNull(outcome.operatorMessage)
    }

    @Test
    fun `a second reply to the same request is dropped`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), data = """{"value":"first"}""")
        reply(idOf(0), data = """{"value":"again"}""")
        assertEquals("first", (call.await() as MqttOutcome.Accepted).body.value)
    }

    // ---- session guard ------------------------------------------------------------------------

    @Test
    fun `operator_session_invalid clears the session the request was sent with`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `operator_session_invalid for a request sent under an older session keeps the new one`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        sessionHolder.set(session("sess-B"))
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertEquals("sess-B", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `operator_session_invalid for a request sent before login keeps the new session`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        sessionHolder.set(session("sess-A"))
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertEquals("sess-A", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a late operator_session_invalid matching no request keeps the session`() {
        sessionHolder.set(session("sess-A"))
        reply("no-such-request", success = false, error = "operator_session_invalid", data = null)
        assertEquals("sess-A", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `client_upgrade_required latches upgradeRequired`() {
        reply("no-such-request", success = false, error = "client_upgrade_required", data = null)
        assertTrue(repo.upgradeRequired.value)
    }

    // ---- pushes -----------------------------------------------------------------------------

    @Test
    fun `a server push reaches the push handler`() {
        val seen = mutableListOf<ResponseEnvelope>()
        repo.setServerPushHandler { _, envelope, _ -> seen += envelope }
        push("rev2-preparations-1")
        assertEquals(1, seen.size)
        assertEquals("General", seen[0].mode)
        assertEquals("preparation_created", seen[0].reason)
    }

    @Test
    fun `a redelivered server push is handled once`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        push("rev2-preparations-1")
        push("rev2-preparations-1")
        assertEquals(1, count)
    }

    @Test
    fun `a push without a messageId is still handled`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        push(null)
        assertEquals(1, count)
    }

    @Test
    fun `a push with no handler registered is dropped without error`() {
        push("rev2-preparations-1")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "*Rev2TransportTest*"
```

Expected: compilation FAIL — the 4-argument `request`, `ErrorCode.REV2_REJECTED`, `Rejected.error`/`operatorMessage` and `ResponseEnvelope.mode` are unresolved.

- [ ] **Step 3: `MqttSchema.VERSION`**

In `MqttSchema.kt` set `const val VERSION = "rev2.1"`. Replace the class KDoc's first paragraph with: "The one place the wire schema version is defined. Station 2 answers any request whose `schemaVersion` is not exactly `rev2.1` with `invalid_envelope`." Delete the paragraph about the 4.1 plaintext-credentials cutover. Leave the timestamp formatter and its KDoc as they are.

- [ ] **Step 4: Rewrite `MqttVocabulary.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * rev2.1 `error`. A value class rather than an enum: an unknown code must pass through intact
 * rather than fail the parse — the SCRAM service can return its own codes.
 *
 * There is deliberately no `NextAction` type. rev2.1's `nextAction` is an English sentence
 * ("Follow the saved job or preparation state."), not a code, so nothing may branch on it.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        /** A field name containing `password` was sent. A build defect, never a wrong password. */
        val PASSWORD_FIELD_FORBIDDEN = ErrorCode("password_field_forbidden")
        val OPERATOR_SESSION_INVALID = ErrorCode("operator_session_invalid")
        val ACTION_NOT_ALLOWED = ErrorCode("action_not_allowed")
        val MESSAGE_ID_CONFLICT = ErrorCode("message_id_conflict")
        /** A definite business rejection. `data` still carries the refreshed snapshot. */
        val REV2_REJECTED = ErrorCode("rev2_rejected")
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        /** Station 2 faulted mid-request. Retry the identical request with the same messageId. */
        val OUTCOME_UNCONFIRMED = ErrorCode("outcome_unconfirmed")
        val AUTHENTICATION_FAILED = ErrorCode("authentication_failed")
        val PURPOSE_NOT_ENABLED = ErrorCode("purpose_not_enabled")
    }
}
```

- [ ] **Step 5: Rewrite `MqttOutcome.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The result of one rev2.1 request/response exchange.
 *
 * `Rejected.body` is the reply's `data` when Station 2 sent one: a `rev2_rejected` General reply
 * still carries the full refreshed snapshot, and discarding it would force a redundant read.
 * Envelope-level rejections (`invalid_envelope`, `operator_session_invalid`, …) carry no `data`,
 * hence nullable.
 *
 * `NoResponse` means Station 2 never answered — distinct from a decision it actually made.
 */
sealed interface MqttOutcome<out T> {

    data class Accepted<T>(val body: T) : MqttOutcome<T>

    data class Rejected<T>(
        val body: T?,
        val error: ErrorCode?,
        /** rev2.1 `operatorMessage`; null when blank. Show it, never parse it. */
        val operatorMessage: String?,
    ) : MqttOutcome<T>

    data class NoResponse(val kind: FailureKind) : MqttOutcome<Nothing>
}

enum class FailureKind {
    /** Published, but no matching response arrived within the timeout and retry budget. */
    Timeout,

    /** Not connected to the broker, or the publish itself failed. */
    NotConnected,

    /** A response arrived but could not be parsed. */
    MalformedResponse,
}
```

- [ ] **Step 6: Rewrite `RequestEnvelope.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson

/** Payload for a request with no message-specific fields. */
object EmptyPayload

/**
 * Builds a rev2.1 request as one flat JSON object: the caller's message-specific payload with the
 * envelope merged in.
 *
 * Callers never construct envelopes. Only the transport knows the device id, the session and the
 * clock, so only the transport writes those fields — which is also why envelope fields are written
 * last and always win over anything of the same name in the payload.
 *
 * `sessionId` is written only when non-blank: Station 2 has no notion of an empty session, and the
 * contract's rule is that an unused optional field is omitted, never sent as `""`.
 */
object RequestEnvelope {

    fun build(
        gson: Gson,
        payload: Any,
        messageId: String,
        deviceId: String,
        sessionId: String?,
        timestampUtc: String,
    ): String {
        val obj = gson.toJsonTree(payload).asJsonObject
        obj.addProperty("schemaVersion", MqttSchema.VERSION)
        obj.addProperty("deviceId", deviceId)
        obj.addProperty("messageId", messageId)
        obj.addProperty("timestampUtc", timestampUtc)
        sessionId?.takeIf { it.isNotBlank() }?.let { obj.addProperty("sessionId", it) }
        return gson.toJson(obj)
    }
}
```

- [ ] **Step 7: Rewrite `ResponseEnvelope.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode

/**
 * The envelope of every rev2.1 message Station 2 sends, parsed from the same JSON object as the
 * body (which lives under `data` and is parsed separately by the transport).
 *
 * Direct replies carry `inResponseToMessageId` and NO `messageId` of their own. Server pushes
 * (`active_job_cards_invalidated`) are the reverse: their own `messageId`, no correlation id, and
 * `mode`/`reason` instead of a result.
 *
 * Every constructor parameter here must keep a default value. Kotlin only emits the no-arg
 * constructor Gson needs when every parameter has a default; drop one and Gson falls back to
 * `UnsafeAllocator`, so every field deserializes to null regardless of its declared type.
 */
data class ResponseEnvelope(
    val schemaVersion: String = "",
    val deviceId: String = "",
    val inResponseToMessageId: String = "",
    val receivedAtUtc: String? = null,
    val sentAtUtc: String? = null,
    val durationMs: Double? = null,
    val success: Boolean = false,
    /** Stable lowercase code; `""` on success. */
    val error: String = "",
    val operatorMessage: String = "",
    /** Prose guidance, not a code. Never branch on it. */
    val nextAction: String = "",
    // Server pushes only.
    val messageId: String = "",
    val timestampUtc: String = "",
    val mode: String = "",
    val reason: String = "",
) {
    val errorCode: ErrorCode? get() = error.takeIf { it.isNotBlank() }?.let(::ErrorCode)

    val displayMessage: String? get() = operatorMessage.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 8: Drop `correlationKey` from `MqttRepository`**

```kotlin
    suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        responseClass: Class<T>,
    ): MqttOutcome<T>
```

Update the `setServerPushHandler` KDoc to: "Registers the single handler for rev2.1 server pushes — messages with no `inResponseToMessageId`, currently `active_job_cards_invalidated`. The transport does not interpret them: the push is a hint to issue a fresh authenticated `read`, and only the layer that owns the displayed list can do that." Update the `clockSkewMillis` KDoc's `message_expired` mention to "a badly drifted clock makes timestamps hard to reconcile with Station 2's logs".

- [ ] **Step 9: Rework `MqttRepositoryImpl`**

Make these changes:

(a) Replace the `pending` declaration and its comment with:

```kotlin
    // Correlation registry: messageId -> the caller awaiting that exact response, plus the session
    // the request was SENT with. rev2.1 replies do not echo the session, so this is the only way to
    // tell whether an operator_session_invalid reply is about the session that is active now.
    private class PendingRequest(val waiter: CompletableDeferred<String>, val sessionId: String)

    private val pending = ConcurrentHashMap<String, PendingRequest>()
```

(b) Replace the comment above `seenResponseIds` with:

```kotlin
    // Duplicate suppression for server PUSHES only. rev2.1 direct replies carry no messageId of
    // their own — a duplicate direct reply is handled by the pending map instead (the first one
    // removes the waiter; later ones match nothing and are dropped). A push has no waiter, so a
    // QoS-1 redelivery would otherwise trigger a second read.
    //
    // Access is guarded by the map itself: HiveMQ delivers callbacks on its own event-loop threads.
```

(c) In `request()`, drop the `correlationKey` parameter and replace the envelope build and registration:

```kotlin
        val messageId = UUID.randomUUID().toString()
        // SCRAM runs before a session exists and must never carry a stale one.
        val sessionId = if (requestType.startsWith("scram_")) "" else sessionHolder.currentSessionIdOrEmpty()
        val json = RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = messageId,
            deviceId = deviceId,
            sessionId = sessionId,
            timestampUtc = MqttSchema.formatTimestamp(nowFn()),
        )
        val topic = MqttTopics.request(deviceId, requestType)

        val bytes = json.toByteArray()  // frozen: every attempt republishes these exact bytes
        val waiter = CompletableDeferred<String>()
        pending[messageId] = PendingRequest(waiter, sessionId)
```

Keep the retry loop and the `finally { pending.remove(messageId) }` unchanged.

(d) Replace `parseOutcome` with:

```kotlin
    private fun <T : Any> parseOutcome(
        raw: String,
        responseClass: Class<T>,
        expectedResponseType: String,
    ): MqttOutcome<T> = try {
        val envelope = gson.fromJson(raw, ResponseEnvelope::class.java)
            ?: throw IllegalStateException("Empty $expectedResponseType response")
        // rev2.1 puts the body under `data`. Absent or null on envelope-level rejections.
        val data = JsonParser.parseString(raw).asJsonObject.get("data")?.takeIf { it.isJsonObject }
        val body: T? = data?.let { gson.fromJson(it, responseClass) }
        if (envelope.success) {
            if (body == null) {
                Log.e(TAG, "$expectedResponseType reported success with no data")
                MqttOutcome.NoResponse(FailureKind.MalformedResponse)
            } else {
                MqttOutcome.Accepted(body)
            }
        } else {
            MqttOutcome.Rejected(body = body, error = envelope.errorCode, operatorMessage = envelope.displayMessage)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not parse $expectedResponseType response", e)
        MqttOutcome.NoResponse(FailureKind.MalformedResponse)
    }
```

Add `import com.google.gson.JsonParser`.

(e) In `recordClockSkew`'s warning text, replace "Requests may be rejected as message_expired." with "Timestamps will be hard to reconcile with Station 2's logs."

(f) Replace `handleIncomingResponse` with:

```kotlin
    @VisibleForTesting
    internal fun handleIncomingResponse(topic: String, bytes: ByteArray) {
        val raw = String(bytes)
        val envelope = try {
            gson.fromJson(raw, ResponseEnvelope::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable response on $topic", e)
            return
        } ?: return

        // Measured from every message with a parseable timestamp, matched or not: a late or
        // duplicate reply is still evidence about our own clock.
        recordClockSkew(envelope.sentAtUtc?.takeIf { it.isNotBlank() } ?: envelope.timestampUtc)
        if (envelope.errorCode == ErrorCode.CLIENT_UPGRADE_REQUIRED) {
            _upgradeRequired.value = true
        }

        val id = envelope.inResponseToMessageId
        if (id.isBlank()) {
            // A server push. rev2.1 pushes carry their own messageId; a redelivery must not
            // trigger a second read. One with no messageId can't be deduplicated, so it is
            // handled rather than silently dropped.
            if (envelope.messageId.isNotBlank() && !claimResponseId(envelope.messageId)) {
                Log.i(TAG, "Suppressing duplicate push on $topic (messageId=${envelope.messageId})")
                return
            }
            val handler = serverPushHandler
            if (handler != null) handler(topic, envelope, raw)
            else Log.w(TAG, "Dropping push on $topic — no handler registered")
            return
        }

        val entry = pending.remove(id)
        if (entry == null) {
            // A duplicate, or a reply to a request that already timed out. Neither may have a
            // side effect — including the session clear below, which cannot be attributed to the
            // current session without the request it answers.
            Log.w(TAG, "Dropping unmatched response on $topic for messageId=$id")
            return
        }
        // Only a rejection of a request sent WITH the currently active session may end it. A reply
        // to a request from before a re-login, or from before any login, is about a session that
        // is already gone — acting on it would log the operator out of a perfectly good one.
        if (envelope.errorCode == ErrorCode.OPERATOR_SESSION_INVALID &&
            entry.sessionId.isNotBlank() &&
            entry.sessionId == sessionHolder.currentSessionIdOrEmpty()
        ) {
            Log.w(TAG, "Station 2 reports the session is invalid ($topic) — clearing local session")
            sessionHolder.clear()
        }
        entry.waiter.complete(raw)
    }
```

(g) Update the stale comments that mention "contract v4.1" or "4.1" in the fields you touched to say "rev2.1".

- [ ] **Step 10: Adapt `ScramExchange` to the new outcome**

Delete `correlationKey = null,` from both `request(...)` calls. Replace `authFailureMessage` with:

```kotlin
/**
 * `password_field_forbidden` means this build sent a field named like a password. That is a build
 * defect, not a bad password, and showing it as "login failed" would send an operator round a loop
 * retyping a password that was never the problem.
 */
private fun <T> MqttOutcome.Rejected<T>.authFailureMessage(): String = when (error) {
    ErrorCode.PASSWORD_FIELD_FORBIDDEN ->
        "This app build sent credentials in a form Station 2 no longer accepts. Update the app."
    ErrorCode.PURPOSE_NOT_ENABLED ->
        "Station 2 does not accept this kind of sign-in. Update the app."
    else -> operatorMessage ?: "Authentication failed"
}
```

- [ ] **Step 11: Delete the superseded tests and rename the schema test**

```bash
T=app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt
git rm $T/MqttRequestCorrelationTest.kt $T/MqttResponseDeduplicationTest.kt $T/MqttSessionExpiryTest.kt
git mv $T/Schema41EnvelopeTest.kt $T/MqttSchemaTest.kt
```

In `MqttSchemaTest.kt`, rename the class to `MqttSchemaTest`. Keep the five timestamp tests (`a whole second still carries exactly six fractional digits` through `a formatted timestamp still round-trips as an Instant`) and delete the rest, from `errorMessage wins over the rollout reason mirror` to the end, along with any import only they used. Add:

```kotlin
    @Test
    fun `the wire schema version is rev2_1`() {
        assertEquals("rev2.1", MqttSchema.VERSION)
    }
```

- [ ] **Step 12: Update `MqttRequestRetryTest`**

```bash
T=app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt
sed -i 's/EmptyPayload, null, TestBody::class.java/EmptyPayload, TestBody::class.java/g' $T
sed -i 's/"accepted":true,"value":"ok"/"success":true,"data":{"value":"ok"}/g' $T
sed -i 's/"accepted":true,"value":"late"/"success":true,"data":{"value":"late"}/g' $T
grep -n "accepted\|, null, " $T
```

Expected: the final grep prints nothing.

- [ ] **Step 13: Rewrite `RequestEnvelopeTest`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestEnvelopeTest {

    private data class Payload(val action: String, val jobCard: String? = null, val deviceId: String? = null)

    private fun build(payload: Any = Payload("read"), sessionId: String? = "sess-1") =
        JsonParser.parseString(
            RequestEnvelope.build(
                gson = WireJson.gson,
                payload = payload,
                messageId = "msg-1",
                deviceId = "scanner_abc",
                sessionId = sessionId,
                timestampUtc = "2026-09-30T10:00:00.000000Z",
            )
        ).asJsonObject

    @Test
    fun `writes every rev2_1 envelope field`() {
        val obj = build()
        assertEquals("rev2.1", obj["schemaVersion"].asString)
        assertEquals("scanner_abc", obj["deviceId"].asString)
        assertEquals("msg-1", obj["messageId"].asString)
        assertEquals("2026-09-30T10:00:00.000000Z", obj["timestampUtc"].asString)
        assertEquals("sess-1", obj["sessionId"].asString)
    }

    @Test
    fun `keeps the payload's own fields`() {
        assertEquals("lookup", build(Payload("lookup", jobCard = "510019296"))["action"].asString)
        assertEquals("510019296", build(Payload("lookup", jobCard = "510019296"))["jobCard"].asString)
    }

    @Test
    fun `omits a null payload field rather than sending null`() {
        assertFalse(build(Payload("read"))
            .has("jobCard"))
    }

    @Test
    fun `omits sessionId when there is no session`() {
        assertFalse(build(sessionId = null).has("sessionId"))
    }

    @Test
    fun `omits sessionId when it is blank`() {
        assertFalse(build(sessionId = "  ").has("sessionId"))
    }

    @Test
    fun `envelope fields win over a payload field of the same name`() {
        assertEquals("scanner_abc", build(Payload("read", deviceId = "forged"))["deviceId"].asString)
    }

    @Test
    fun `never writes the retired 4_1 envelope fields`() {
        val obj = build()
        assertFalse(obj.has("operatorSessionId"))
        assertFalse(obj.has("correlationKey"))
    }
}
```

- [ ] **Step 14: Rewrite `MqttVocabularyTest`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `every error code carries its exact rev2_1 wire value`() {
        mapOf(
            ErrorCode.INVALID_ENVELOPE to "invalid_envelope",
            ErrorCode.PASSWORD_FIELD_FORBIDDEN to "password_field_forbidden",
            ErrorCode.OPERATOR_SESSION_INVALID to "operator_session_invalid",
            ErrorCode.ACTION_NOT_ALLOWED to "action_not_allowed",
            ErrorCode.MESSAGE_ID_CONFLICT to "message_id_conflict",
            ErrorCode.REV2_REJECTED to "rev2_rejected",
            ErrorCode.CLIENT_UPGRADE_REQUIRED to "client_upgrade_required",
            ErrorCode.OUTCOME_UNCONFIRMED to "outcome_unconfirmed",
            ErrorCode.AUTHENTICATION_FAILED to "authentication_failed",
            ErrorCode.PURPOSE_NOT_ENABLED to "purpose_not_enabled",
        ).forEach { (code, wire) -> assertEquals(wire, code.raw) }
    }

    @Test
    fun `an unknown code passes through intact`() {
        assertEquals("some_future_code", ErrorCode("some_future_code").raw)
    }

    @Test
    fun `codes compare by value`() {
        assertEquals(ErrorCode.REV2_REJECTED, ErrorCode("rev2_rejected"))
        assertNotEquals(ErrorCode.REV2_REJECTED, ErrorCode("REV2_REJECTED"))
    }
}
```

- [ ] **Step 15: Rewrite `ResponseEnvelopeTest`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEnvelopeTest {

    private fun parse(json: String) = WireJson.gson.fromJson(json, ResponseEnvelope::class.java)

    @Test
    fun `parses every rev2_1 reply field`() {
        val e = parse(
            """{"schemaVersion":"rev2.1","deviceId":"scanner_1","inResponseToMessageId":"m-1",
               "receivedAtUtc":"2026-09-30T10:00:00.000000Z","sentAtUtc":"2026-09-30T10:00:00.012500Z",
               "durationMs":12.5,"success":false,"error":"rev2_rejected",
               "operatorMessage":"Load a General Mixing JC first.","nextAction":"Correct the request.",
               "data":{"jobs":[]}}"""
        )
        assertEquals("m-1", e.inResponseToMessageId)
        assertEquals("2026-09-30T10:00:00.012500Z", e.sentAtUtc)
        assertEquals(12.5, e.durationMs!!, 0.0)
        assertFalse(e.success)
        assertEquals(ErrorCode.REV2_REJECTED, e.errorCode)
        assertEquals("Load a General Mixing JC first.", e.displayMessage)
    }

    @Test
    fun `an empty error on success reads as no error code`() {
        val e = parse("""{"success":true,"error":"","operatorMessage":"Current progress."}""")
        assertTrue(e.success)
        assertNull(e.errorCode)
    }

    @Test
    fun `a blank operator message reads as absent`() {
        assertNull(parse("""{"success":false,"operatorMessage":"  "}""").displayMessage)
    }

    @Test
    fun `parses a push's own fields`() {
        val e = parse(
            """{"schemaVersion":"rev2.1","deviceId":"scanner_1","messageId":"rev2-preparations-1",
               "timestampUtc":"2026-09-30T10:00:00.000000Z","mode":"General",
               "reason":"preparation_created","nextAction":"read"}"""
        )
        assertEquals("rev2-preparations-1", e.messageId)
        assertEquals("", e.inResponseToMessageId)
        assertEquals("General", e.mode)
        assertEquals("preparation_created", e.reason)
    }

    @Test
    fun `explicit nulls fall back to defaults`() {
        val e = parse("""{"error":null,"operatorMessage":null,"inResponseToMessageId":null}""")
        assertEquals("", e.error)
        assertEquals("", e.operatorMessage)
        assertEquals("", e.inResponseToMessageId)
    }
}
```

- [ ] **Step 16: Run the tests to verify they pass**

```bash
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. `MqttClockSkewTest`, `MqttStationPresenceTest`, `MqttRepositoryImplTest` and `MqttClientFactoryTest` pass with **no edits**. If one of them fails, a change leaked into connection or presence code; undo that change rather than editing the test.

- [ ] **Step 17: Verify no 4.1 vocabulary survives in main**

```bash
grep -rn "NextAction\|correlationKey\|errorCode\s*=\|\.accepted\|operatorSessionId\s*=\|PLAINTEXT_CREDENTIALS\|SESSION_REQUIRED\|\"4\.1\"" app/src/main --include=*.kt
```

Expected: matches only in `AuthMessages.kt`, `AuthUseCase.kt` and `OperatorSessionHolder.kt`, all on `operatorSessionId`, which Task 9 handles. Nothing else.

- [ ] **Step 18: Build and commit**

```bash
./gradlew assembleDebug
graphify update .
git add -A
git commit -m "feat(mqtt): speak the rev2.1 envelope — body under data, request-side session guard"
```

---

### Task 9: Auth on rev2.1

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCase.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/session/OperatorSessionHolder.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/WireNullToleranceTest.kt`

**Interfaces:**
- Consumes: Task 8's transport.
- Produces:
  - `ScramProofResponse(serverSignature: String = "", session: Rev2Session? = null)`
  - `Rev2Session(sessionId, operatorId, displayName, role, expiresAtUtc: String?, sessionState: String?, isActive: Boolean = false)`
  - `OperatorSession(operatorSessionId, operatorId, operatorName, role, sessionState, sessionExpiresAtUtc)`, with no `allowedActions`/`allowedTabs`
  - `StationAction` and `canShow` no longer exist

- [ ] **Step 1: Write the failing tests**

In `AuthUseCaseTest.kt`, replace the `provedLogin` fixture with:

```kotlin
    /** What a successful rev2.1 SCRAM proof returns under `data`. */
    private val provedLogin = ScramProofResponse(
        serverSignature = "verified-by-ScramExchange",
        session = Rev2Session(
            sessionId = "session-id",
            operatorId = "OP-001",
            displayName = "Operator One",
            role = "Worker",
            expiresAtUtc = "2026-09-30T18:00:00.000000Z",
            sessionState = "Active",
            isActive = true,
        ),
    )
```

Add `import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Session`. Update the existing tests that build variants of the fixture so they use `provedLogin.copy(session = provedLogin.session!!.copy(...))`:
- `a proved login with no session id is still a failure`: `sessionId = ""`
- `a login answered with a Closed session is a failure`: `sessionState = "Closed"`
- `an unparseable expiry does not fail the login`: `expiresAtUtc = "not-a-date"`
- `a successful login carries session state and expiry`: assert `Instant.parse("2026-09-30T18:00:00.000000Z")`

Then add:

```kotlin
    @Test
    fun `a proof with no session object fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = null)))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a session Station 2 reports inactive fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(isActive = false))))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `the stored session takes identity from data_session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login("operator1", "pass").getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Worker", session.role)
    }
```

Delete any assertion on `allowedActions` or `allowedTabs` in this file.

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*AuthUseCaseTest*"
```

Expected: compilation FAIL — `Rev2Session` unresolved.

- [ ] **Step 3: Nest the session in `ScramProofResponse`**

In `AuthMessages.kt`, replace `ScramProofResponse` with:

```kotlin
/**
 * `scram_proof_result` `data`. [serverSignature] must be validated before [session] is trusted.
 *
 * rev2.1 sends no `allowedTabs`/`allowedActions` and never will (customer-confirmed 2026-09-30):
 * Station 2 authorizes every request by its `sessionId`, and nothing on the device gates on role.
 */
data class ScramProofResponse(
    val serverSignature: String = "",
    val session: Rev2Session? = null,
)

/** Station 2's `StationOperatorSession`, camelCase. Enum values arrive as names (`Worker`, `Active`). */
data class Rev2Session(
    val sessionId: String = "",
    val operatorId: String = "",
    val displayName: String = "",
    /** Display and audit only — never branch on it. */
    val role: String = "",
    val expiresAtUtc: String? = null,
    val sessionState: String? = null,
    val isActive: Boolean = false,
)
```

- [ ] **Step 4: Wait on the right response suffix**

In `ScramExchange.kt`, in the proof `request(...)` call, change `responseType = "operator_context"` to `responseType = "scram_proof_result"`.

- [ ] **Step 5: Build the session from `data.session`**

In `AuthUseCase.login`, replace everything after `.getOrElse { return Result.failure(it) }` with:

```kotlin
        val wire = proof.session
            ?: return Result.failure(Exception("Station 2 accepted the login but issued no session"))
        val state = SessionState.fromWire(wire.sessionState)
        return when {
            wire.sessionId.isBlank() ->
                Result.failure(Exception("Station 2 accepted the login but issued no session"))
            // Accepting an already-closed or inactive session would strand the operator in a UI
            // that rejects every action.
            state == SessionState.Closed || !wire.isActive ->
                Result.failure(Exception("Station 2 closed this session immediately"))
            else -> {
                val session = OperatorSession(
                    operatorSessionId = wire.sessionId,
                    operatorId = wire.operatorId,
                    operatorName = wire.displayName,
                    role = wire.role,
                    sessionState = state,
                    // A bad timestamp must not fail an otherwise valid login — expiry is
                    // display-only, and Station 2 enforces it regardless.
                    sessionExpiresAtUtc = wire.expiresAtUtc?.let {
                        try { Instant.parse(it) } catch (e: Exception) { null }
                    },
                )
                sessionHolder.set(session)
                Result.success(session)
            }
        }
```

- [ ] **Step 6: Remove the display-hint lists from `OperatorSession`**

In `OperatorSessionHolder.kt`, delete `object StationAction`, the `allowedActions` and `allowedTabs` properties, `fun canShow(...)` with its KDoc, and the top-level `fun OperatorSession?.canShow(...)`. The data class becomes:

```kotlin
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only. Nothing gates on role — never branch on this. */
    val role: String,
    val sessionState: SessionState = SessionState.Active,
    val sessionExpiresAtUtc: Instant? = null,
)
```

- [ ] **Step 7: Register the new DTO with the null-tolerance test**

In `WireNullToleranceTest.responseRoots`, keep `ScramChallengeResponse` and `ScramProofResponse`. `Rev2Session` is reached through `ScramProofResponse.session`, so it needs no root of its own.

- [ ] **Step 8: Verify and run all tests**

```bash
grep -rn "allowedTabs\|allowedActions\|canShow\|StationAction\|operator_context" app/src --include=*.kt
./gradlew testDebugUnitTest
```

Expected: the grep prints nothing; tests BUILD SUCCESSFUL.

- [ ] **Step 9: Log in against the simulator (in-process wire check)**

```bash
python tools/backend-sim/selftest.py --direct
```

Expected: `all N checks passed`. This checks the simulator side of the same exchange; Task 12's on-device checkpoint covers the app side.

- [ ] **Step 10: Build and commit**

```bash
./gradlew assembleDebug
graphify update .
git add -A
git commit -m "feat(auth): read the rev2.1 session from data.session; drop display-hint lists"
```

---

## Phase 4: Job lookup

### Task 10: General DTOs and `JobLookupUseCase`

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/dto/Rev2GeneralMessages.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/Job.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCase.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCaseTest.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/WireNullToleranceTest.kt`

**Interfaces:**
- Consumes: Task 8's `MqttRepository.request`, `MqttOutcome`, `ErrorCode`. Task 3's `FailureKind.message()` (internal, in `AuthUseCase.kt`).
- Produces:
  - DTOs `Rev2GeneralRequest(action, jobCard?, targetId?)`, `Rev2GeneralSnapshot`, `Rev2CommandResult`, `Rev2JobSummary`, `Rev2Job`, `Rev2Material`, `Rev2Preparation`
  - Domain `JobSummary(jobCard, product, requiredMixes, closed)`, `JobMaterial(code, name, unit, perMix, required, collected, excluded)` with `remaining`, `JobPreparation(id, mixCount, mixed, produced, stage)`, `JobDetail(jobCard, product, unit, outputPerMix, requiredMixes, allocatedMixes, capturedAtUtc: Instant?, closed, materials, preparations)`, `JobLookupSnapshot(jobs, detail: JobDetail?)`
  - `sealed interface JobLookupResult { Loaded(snapshot); Failed(message, snapshot: JobLookupSnapshot? = null) }`
  - `class JobLookupUseCase @Inject constructor(mqttRepository)` with `suspend fun read(targetId: String? = null): JobLookupResult` and `suspend fun lookup(rawJobCard: String): JobLookupResult`
  - `JobLookupUseCase.NOT_DIGITS_MESSAGE`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralSnapshot
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Job
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2JobSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Material
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Preparation
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JobLookupUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: JobLookupUseCase

    private val job = Rev2Job(
        id = "510019068", product = "BAG CARRIER MIDI WHT", unit = "each",
        outputPerMix = 50.0, requiredMixes = 8, allocatedMixes = 2,
        capturedAtUtc = "2026-09-30T10:00:00.000000Z",
        materials = listOf(
            Rev2Material("1600000301", "HD WHITE", "kg", perMix = 69.631, required = 557.049, collected = 100.0),
            Rev2Material("1500000306", "TACKIFIER", "kg", perMix = 1.5, required = 0.0, collected = 0.0, excluded = true),
        ),
    )
    private val snapshot = Rev2GeneralSnapshot(
        jobs = listOf(Rev2JobSummary("510019068", "BAG CARRIER MIDI WHT", closed = false, requiredMixes = 8)),
        job = job,
        preparations = listOf(
            Rev2Preparation("PREP_1", "510019068", mixCount = 2, stage = "Collecting"),
            Rev2Preparation("PREP_2", "510099999", mixCount = 1, stage = "Mixing"),
        ),
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = JobLookupUseCase(mqtt)
    }

    private suspend fun stub(outcome: MqttOutcome<Rev2GeneralSnapshot>) {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2GeneralSnapshot::class.java))).thenReturn(outcome)
    }

    private suspend fun sentRequest(): Rev2GeneralRequest {
        val captor = argumentCaptor<Any>()
        verify(mqtt).request(eq("rev2_general_requested"), eq("rev2_general_result"), captor.capture(), eq(Rev2GeneralSnapshot::class.java))
        return captor.firstValue as Rev2GeneralRequest
    }

    @Test
    fun `read sends action read with no target`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        useCase.read()
        assertEquals(Rev2GeneralRequest(action = "read"), sentRequest())
    }

    @Test
    fun `read with a target sends it as targetId`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.read("510019068")
        assertEquals(Rev2GeneralRequest(action = "read", targetId = "510019068"), sentRequest())
    }

    @Test
    fun `read maps the job list`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        val result = useCase.read() as JobLookupResult.Loaded
        assertEquals(1, result.snapshot.jobs.size)
        assertEquals("510019068", result.snapshot.jobs[0].jobCard)
        assertEquals(8, result.snapshot.jobs[0].requiredMixes)
        assertNull(result.snapshot.detail)
    }

    @Test
    fun `an empty job list is loaded, not a failure`() = runTest {
        stub(MqttOutcome.Accepted(Rev2GeneralSnapshot()))
        val result = useCase.read() as JobLookupResult.Loaded
        assertTrue(result.snapshot.jobs.isEmpty())
    }

    @Test
    fun `detail carries the job, its materials and only its own preparations`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        val detail = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!
        assertEquals("510019068", detail.jobCard)
        assertEquals(8, detail.requiredMixes)
        assertEquals(2, detail.allocatedMixes)
        assertEquals(2, detail.materials.size)
        assertEquals(listOf("PREP_1"), detail.preparations.map { it.id })
    }

    @Test
    fun `remaining is required minus collected, never negative`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        val materials = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!.materials
        assertEquals(457.049, materials[0].remaining, 1e-9)
        assertEquals(0.0, materials[1].remaining, 0.0)
        assertTrue(materials[1].excluded)
    }

    @Test
    fun `read of an unknown target is a failure naming the job card`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        val result = useCase.read("510000000") as JobLookupResult.Failed
        assertTrue(result.message, result.message.contains("510000000"))
        assertEquals(1, result.snapshot?.jobs?.size)
    }

    @Test
    fun `lookup sends the job card as action lookup`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.lookup("510019068")
        assertEquals(Rev2GeneralRequest(action = "lookup", jobCard = "510019068"), sentRequest())
    }

    @Test
    fun `lookup trims whitespace and a trailing newline`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.lookup("  510019068\n")
        assertEquals("510019068", sentRequest().jobCard)
    }

    @Test
    fun `lookup rejects a non-digit job card without sending anything`() = runTest {
        val result = useCase.lookup("JC-24001") as JobLookupResult.Failed
        assertEquals(JobLookupUseCase.NOT_DIGITS_MESSAGE, result.message)
        verify(mqtt, never()).request(any(), any(), any(), any<Class<Any>>())
    }

    @Test
    fun `lookup rejects a blank job card without sending anything`() = runTest {
        assertTrue(useCase.lookup("   ") is JobLookupResult.Failed)
        verify(mqtt, never()).request(any(), any(), any(), any<Class<Any>>())
    }

    @Test
    fun `lookup rejects non-ASCII digits`() = runTest {
        assertTrue(useCase.lookup("５１００") is JobLookupResult.Failed)
    }

    @Test
    fun `a successful lookup with no job in the reply is a failure`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        assertTrue(useCase.lookup("510019068") is JobLookupResult.Failed)
    }

    @Test
    fun `a rev2_rejected lookup shows the operator message and keeps the snapshot`() = runTest {
        stub(MqttOutcome.Rejected(snapshot.copy(job = null), ErrorCode.REV2_REJECTED,
            "Only Standard Planned or Released jobs in header warehouse FAC can be loaded."))
        val result = useCase.lookup("510018531") as JobLookupResult.Failed
        assertEquals("Only Standard Planned or Released jobs in header warehouse FAC can be loaded.", result.message)
        assertEquals(1, result.snapshot?.jobs?.size)
    }

    @Test
    fun `outcome_unconfirmed asks the operator to try again`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.OUTCOME_UNCONFIRMED, "The result could not be confirmed."))
        val result = useCase.read() as JobLookupResult.Failed
        assertEquals("Station 2 couldn't confirm that — try again", result.message)
        assertNull(result.snapshot)
    }

    @Test
    fun `operator_session_invalid says to sign in again`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.OPERATOR_SESSION_INVALID, "Sign in on this device before continuing."))
        assertEquals("Your session has ended — sign in again", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `a rejection with no message falls back to a generic line`() = runTest {
        stub(MqttOutcome.Rejected(null, null, null))
        assertEquals("Station 2 rejected the request", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `no response reports the transport failure`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertEquals("Station 2 did not respond", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `an unparseable capture time does not fail the read`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = job.copy(capturedAtUtc = "not-a-date"))))
        val detail = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!
        assertNull(detail.capturedAtUtc)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*JobLookupUseCaseTest*"
```

Expected: compilation FAIL — `JobLookupUseCase` and the `Rev2*` DTOs are unresolved.

- [ ] **Step 3: Write `Rev2GeneralMessages.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 General Mixing: `rev2_general_requested` → `rev2_general_result`.
 *
 * Only `read` and `lookup` exist in this app. Every reply's `data` is the whole General snapshot
 * (see `Rev2ScannerProcessor.ProcessAsync` in the Station 2 repo), so one DTO serves both. Fields
 * the slice does not use — `machines`, `ingredientExceptions`, `requiredIngredientChoices`,
 * `exceptionListRevision`, `preparation`, the Rajoo fields — are not declared; Gson ignores them.
 *
 * Every constructor parameter keeps a default: see [ResponseEnvelope] for why that is load-bearing.
 */

/** Request payload. Null fields are omitted on the wire. */
data class Rev2GeneralRequest(
    val action: String,
    /** `lookup` only: the Production Order number, digits only. */
    val jobCard: String? = null,
    /** `read` only: a JC or preparation id to include as detail. */
    val targetId: String? = null,
)

data class Rev2GeneralSnapshot(
    val result: Rev2CommandResult? = null,
    val jobs: List<Rev2JobSummary> = emptyList(),
    val job: Rev2Job? = null,
    val preparations: List<Rev2Preparation> = emptyList(),
)

data class Rev2CommandResult(
    val success: Boolean = false,
    val message: String = "",
    val targetId: String? = null,
)

data class Rev2JobSummary(
    val id: String = "",
    val product: String = "",
    val closed: Boolean = false,
    val requiredMixes: Int = 0,
)

data class Rev2Job(
    val id: String = "",
    val product: String = "",
    val unit: String = "",
    val overallQuantity: Double = 0.0,
    val scopeQuantity: Double = 0.0,
    val sapCompletedAtLookup: Double = 0.0,
    val outputPerMix: Double = 0.0,
    val requiredMixes: Int = 0,
    val allocatedMixes: Int = 0,
    val capturedAtUtc: String? = null,
    val closed: Boolean = false,
    val materials: List<Rev2Material> = emptyList(),
)

/** `Remaining` is computed server-side and not serialized — derive it on the device. */
data class Rev2Material(
    val code: String = "",
    val name: String = "",
    val unit: String = "",
    val perMix: Double = 0.0,
    val required: Double = 0.0,
    val collected: Double = 0.0,
    /** Excluded by an ingredient-exception choice: shown, never collected, `required` is 0. */
    val excluded: Boolean = false,
)

data class Rev2Preparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val mixed: Int = 0,
    val produced: Int = 0,
    /** Enum name: Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, … Completed. */
    val stage: String = "",
    val startedAtUtc: String? = null,
)
```

- [ ] **Step 4: Write `domain/model/Job.kt`**

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import java.time.Instant

/** One row of the General job list. */
data class JobSummary(
    val jobCard: String,
    val product: String,
    val requiredMixes: Int,
    val closed: Boolean,
)

/**
 * One BOM material. Quantities are for display: the server owns every calculation, and the only
 * thing derived here is [remaining], because the server computes it but does not send it.
 */
data class JobMaterial(
    val code: String,
    val name: String,
    val unit: String,
    val perMix: Double,
    val required: Double,
    val collected: Double,
    val excluded: Boolean,
) {
    val remaining: Double get() = maxOf(0.0, required - collected)
}

data class JobPreparation(
    val id: String,
    val mixCount: Int,
    val mixed: Int,
    val produced: Int,
    val stage: String,
)

data class JobDetail(
    val jobCard: String,
    val product: String,
    val unit: String,
    val outputPerMix: Double,
    val requiredMixes: Int,
    val allocatedMixes: Int,
    val capturedAtUtc: Instant?,
    val closed: Boolean,
    val materials: List<JobMaterial>,
    val preparations: List<JobPreparation>,
)

data class JobLookupSnapshot(
    val jobs: List<JobSummary>,
    val detail: JobDetail?,
)
```

- [ ] **Step 5: Write `JobLookupUseCase.kt`**

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface JobLookupResult {
    data class Loaded(val snapshot: JobLookupSnapshot) : JobLookupResult

    /** [snapshot] is set when Station 2 answered with one anyway (a `rev2_rejected` reply does). */
    data class Failed(val message: String, val snapshot: JobLookupSnapshot? = null) : JobLookupResult
}

/**
 * The two General reads this app makes. Both are reads, so the transport's identical-bytes retry is
 * safe, and an uncertain outcome is resolved simply by reading again.
 */
@Singleton
class JobLookupUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    /** The job list, plus detail for [targetId] (a JC or preparation id) when given. */
    suspend fun read(targetId: String? = null): JobLookupResult {
        val target = targetId?.trim()?.takeIf { it.isNotEmpty() }
        val result = send(Rev2GeneralRequest(action = "read", targetId = target))
        if (target != null && result is JobLookupResult.Loaded && result.snapshot.detail == null) {
            return JobLookupResult.Failed("Station 2 has no General job $target", result.snapshot)
        }
        return result
    }

    /**
     * Loads one job card from SAP into Station 2 and returns it as detail. [rawJobCard] is what the
     * scanner or keyboard produced: a printed JC barcode is the Production Order number, and
     * DataWedge may append a newline.
     */
    suspend fun lookup(rawJobCard: String): JobLookupResult {
        val jobCard = rawJobCard.trim()
        // '0'..'9', not isDigit(): Station 2 accepts ASCII digits only, and isDigit() admits
        // full-width and other Unicode digits it would reject.
        if (jobCard.isEmpty() || !jobCard.all { it in '0'..'9' }) {
            return JobLookupResult.Failed(NOT_DIGITS_MESSAGE)
        }
        val result = send(Rev2GeneralRequest(action = "lookup", jobCard = jobCard))
        if (result is JobLookupResult.Loaded && result.snapshot.detail == null) {
            return JobLookupResult.Failed("Station 2 loaded $jobCard but returned no job", result.snapshot)
        }
        return result
    }

    private suspend fun send(request: Rev2GeneralRequest): JobLookupResult =
        when (val outcome = mqttRepository.request(
            requestType = REQUEST_TYPE,
            responseType = RESPONSE_TYPE,
            payload = request,
            responseClass = Rev2GeneralSnapshot::class.java,
        )) {
            is MqttOutcome.Accepted -> JobLookupResult.Loaded(outcome.body.toDomain())
            is MqttOutcome.Rejected -> JobLookupResult.Failed(outcome.message(), outcome.body?.toDomain())
            is MqttOutcome.NoResponse -> JobLookupResult.Failed(outcome.kind.message())
        }

    companion object {
        const val REQUEST_TYPE = "rev2_general_requested"
        const val RESPONSE_TYPE = "rev2_general_result"
        const val NOT_DIGITS_MESSAGE = "Scan or enter the production order number (digits only)"
    }
}

private fun MqttOutcome.Rejected<*>.message(): String = when (error) {
    ErrorCode.OUTCOME_UNCONFIRMED -> "Station 2 couldn't confirm that — try again"
    ErrorCode.OPERATOR_SESSION_INVALID -> "Your session has ended — sign in again"
    ErrorCode.CLIENT_UPGRADE_REQUIRED -> "This app needs updating before it can talk to Station 2"
    else -> operatorMessage ?: "Station 2 rejected the request"
}

private fun Rev2GeneralSnapshot.toDomain(): JobLookupSnapshot = JobLookupSnapshot(
    jobs = jobs.map { JobSummary(it.id, it.product, it.requiredMixes, it.closed) },
    detail = job?.let { job ->
        JobDetail(
            jobCard = job.id,
            product = job.product,
            unit = job.unit,
            outputPerMix = job.outputPerMix,
            requiredMixes = job.requiredMixes,
            allocatedMixes = job.allocatedMixes,
            capturedAtUtc = job.capturedAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() },
            closed = job.closed,
            materials = job.materials.map {
                JobMaterial(it.code, it.name, it.unit, it.perMix, it.required, it.collected, it.excluded)
            },
            preparations = preparations.filter { it.jobId == job.id }.map {
                JobPreparation(it.id, it.mixCount, it.mixed, it.produced, it.stage)
            },
        )
    },
)
```

- [ ] **Step 6: Run to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*JobLookupUseCaseTest*"
```

Expected: PASS, 19 tests.

- [ ] **Step 7: Register the snapshot with the null-tolerance test**

In `WireNullToleranceTest.responseRoots` add `Rev2GeneralSnapshot::class.java`, with its import. `Rev2CommandResult`, `Rev2JobSummary`, `Rev2Job`, `Rev2Material` and `Rev2Preparation` are reached from it.

- [ ] **Step 8: Run all tests and commit**

```bash
./gradlew testDebugUnitTest
graphify update .
git add -A
git commit -m "feat(joblookup): General read/lookup DTOs and use case"
```

Expected: BUILD SUCCESSFUL.

---

### Task 11: `JobLookupViewModel`

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModel.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModelTest.kt`

**Interfaces:**
- Consumes: Task 10's `JobLookupUseCase`, `JobLookupResult`, domain models. Task 3's `AuthUseCase.logout()`. `ScanEventBus`, `OperatorSessionHolder`, `MqttRepository.setServerPushHandler`, `connectionStatusFlow`.
- Produces:
  - `@HiltViewModel class JobLookupViewModel @Inject constructor(useCase: JobLookupUseCase, mqttRepository: MqttRepository, scanEventBus: ScanEventBus, sessionHolder: OperatorSessionHolder, authUseCase: AuthUseCase)`
  - `uiState: StateFlow<JobLookupUiState>`, `connectionStatus`, `session`, `navigateToDetail: Flow<String>`
  - `refreshList()`, `lookup(input: String)`, `openDetail(jobCard: String)`, `setLookupScreenActive(active: Boolean)`, `logout()`
  - `data class JobLookupUiState(jobs, listLoading, listError, lookupInFlight, lookupError, detail, detailLoading, detailError)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JobLookupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: JobLookupUseCase
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus
    private lateinit var scans: MutableSharedFlow<ScanEvent>
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var vm: JobLookupViewModel
    private var pushHandler: ((String, ResponseEnvelope, String) -> Unit)? = null

    private val jobs = listOf(JobSummary("510019068", "BAG CARRIER", 8, false))
    private val detail = JobDetail(
        jobCard = "510019068", product = "BAG CARRIER", unit = "each", outputPerMix = 50.0,
        requiredMixes = 8, allocatedMixes = 2, capturedAtUtc = null, closed = false,
        materials = emptyList(), preparations = emptyList(),
    )
    private val listOnly = JobLookupSnapshot(jobs, null)
    private val withDetail = JobLookupSnapshot(jobs, detail)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        mqtt = mock()
        scanBus = mock()
        scans = MutableSharedFlow(extraBufferCapacity = 8)
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(scans)
        whenever(mqtt.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        vm = JobLookupViewModel(useCase, mqtt, scanBus, sessionHolder, mock<AuthUseCase>())
        val captor = argumentCaptor<(String, ResponseEnvelope, String) -> Unit>()
        verify(mqtt).setServerPushHandler(captor.capture())
        pushHandler = captor.firstValue
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun push(mode: String = "General") = pushHandler!!(
        "PPNAM/station_2/scanner_x/res/active_job_cards_invalidated",
        ResponseEnvelope(messageId = "p-1", mode = mode, reason = "preparation_created", nextAction = "read"),
        "{}",
    )

    @Test
    fun `refreshList loads the job list`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        vm.refreshList()
        assertEquals(jobs, vm.uiState.value.jobs)
        assertFalse(vm.uiState.value.listLoading)
        assertNull(vm.uiState.value.listError)
    }

    @Test
    fun `a failed refresh keeps the previous list and shows the error`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        vm.refreshList()
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Failed("Station 2 did not respond"))
        vm.refreshList()
        assertEquals(jobs, vm.uiState.value.jobs)
        assertEquals("Station 2 did not respond", vm.uiState.value.listError)
    }

    @Test
    fun `an empty job list is shown as empty, not as an error`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(JobLookupSnapshot(emptyList(), null)))
        vm.refreshList()
        assertEquals(emptyList<JobSummary>(), vm.uiState.value.jobs)
        assertNull(vm.uiState.value.listError)
    }

    @Test
    fun `a successful lookup stores the detail and navigates to it`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.lookup("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        assertEquals("510019068", vm.navigateToDetail.first())
    }

    @Test
    fun `a rejected lookup shows the message, stays put and applies the snapshot's job list`() = runTest {
        whenever(useCase.lookup("510018531")).thenReturn(
            JobLookupResult.Failed("Only Standard Planned or Released jobs can be loaded.", listOnly)
        )
        vm.lookup("510018531")
        assertEquals("Only Standard Planned or Released jobs can be loaded.", vm.uiState.value.lookupError)
        assertEquals(jobs, vm.uiState.value.jobs)
        assertNull(vm.uiState.value.detail)
        assertFalse(vm.uiState.value.lookupInFlight)
    }

    @Test
    fun `a second lookup while one is in flight is ignored`() = runTest {
        whenever(useCase.lookup(any())).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.setLookupInFlightForTest(true)
        vm.lookup("510019068")
        verify(useCase, never()).lookup(any())
    }

    @Test
    fun `a scan runs a lookup while the lookup screen is active`() = runTest {
        whenever(useCase.lookup("510019068\n")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.setLookupScreenActive(true)
        scans.emit(ScanEvent.Barcode("510019068\n", "CODE128", Instant.EPOCH))
        verify(useCase).lookup("510019068\n")
    }

    @Test
    fun `a scan is ignored while the lookup screen is not active`() = runTest {
        vm.setLookupScreenActive(false)
        scans.emit(ScanEvent.Barcode("510019068", "CODE128", Instant.EPOCH))
        verify(useCase, never()).lookup(any())
    }

    @Test
    fun `openDetail reads the target and stores the detail`() = runTest {
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.openDetail("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        assertFalse(vm.uiState.value.detailLoading)
    }

    @Test
    fun `openDetail for the job a lookup just loaded does not read again`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.lookup("510019068")
        vm.openDetail("510019068")
        // anyOrNull, not any(): any() never matches read(null), so it would pass vacuously.
        verify(useCase, never()).read(anyOrNull())
    }

    @Test
    fun `a failed openDetail shows the error`() = runTest {
        whenever(useCase.read("510000000")).thenReturn(JobLookupResult.Failed("Station 2 has no General job 510000000"))
        vm.openDetail("510000000")
        assertEquals("Station 2 has no General job 510000000", vm.uiState.value.detailError)
    }

    @Test
    fun `a General push re-reads the list`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        push()
        verify(useCase).read(null)
    }

    @Test
    fun `a push while viewing a detail re-reads that detail`() = runTest {
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.openDetail("510019068")
        push()
        verify(useCase, times(2)).read("510019068")
    }

    @Test
    fun `a push with no session sends nothing`() = runTest {
        sessionHolder.clear()
        push()
        verify(useCase, never()).read(anyOrNull())
    }

    @Test
    fun `a push for another mode is ignored`() = runTest {
        push(mode = "Rajoo")
        verify(useCase, never()).read(anyOrNull())
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*JobLookupViewModelTest*"
```

Expected: compilation FAIL — `JobLookupViewModel` unresolved.

- [ ] **Step 3: Write `JobLookupViewModel.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.MqttTopics
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JobLookupUiState(
    val jobs: List<JobSummary> = emptyList(),
    val listLoading: Boolean = false,
    val listError: String? = null,
    val lookupInFlight: Boolean = false,
    val lookupError: String? = null,
    val detail: JobDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

/**
 * Shared by Job Lookup and Job Detail (scoped to their nav graph), so a job loaded by lookup is
 * already on screen when Detail opens.
 */
@HiltViewModel
class JobLookupViewModel @Inject constructor(
    private val useCase: JobLookupUseCase,
    mqttRepository: MqttRepository,
    scanEventBus: ScanEventBus,
    private val sessionHolder: OperatorSessionHolder,
    private val authUseCase: AuthUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobLookupUiState())
    val uiState: StateFlow<JobLookupUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _navigateToDetail = Channel<String>(Channel.BUFFERED)
    val navigateToDetail: Flow<String> = _navigateToDetail.receiveAsFlow()

    /** The job card Detail is showing, or null on the list. Drives what a push re-reads. */
    private var viewedJobCard: String? = null

    @Volatile
    private var lookupScreenActive = false

    init {
        // rev2.1: after a preparation is created Station 2 pushes a hint telling General readers to
        // read again. It is a hint, not data, and never a reason to cancel a request in flight — so
        // it launches its own read alongside whatever is running.
        mqttRepository.setServerPushHandler { topic, envelope, _ ->
            if (MqttTopics.responseTypeOf(topic) != "active_job_cards_invalidated") return@setServerPushHandler
            if (envelope.mode.isNotBlank() && !envelope.mode.equals("General", ignoreCase = true)) return@setServerPushHandler
            // Without a session the read would be rejected — and there is nothing on screen to refresh.
            if (sessionHolder.session.value == null) return@setServerPushHandler
            val target = viewedJobCard
            if (target != null) loadDetail(target) else refreshList()
        }
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                if (!lookupScreenActive) return@collect
                lookup(
                    when (event) {
                        is ScanEvent.Barcode -> event.value
                        is ScanEvent.RfidTag -> event.tagId
                    }
                )
            }
        }
    }

    /** Called by Job Lookup as it becomes visible or hidden, so scans only act on that screen. */
    fun setLookupScreenActive(active: Boolean) {
        lookupScreenActive = active
        if (active) viewedJobCard = null
    }

    fun refreshList() {
        viewModelScope.launch {
            _uiState.update { it.copy(listLoading = true) }
            when (val result = useCase.read()) {
                is JobLookupResult.Loaded ->
                    _uiState.update { it.copy(jobs = result.snapshot.jobs, listLoading = false, listError = null) }
                is JobLookupResult.Failed -> _uiState.update { state ->
                    state.copy(
                        jobs = result.snapshot?.jobs ?: state.jobs,
                        listLoading = false,
                        listError = result.message,
                    )
                }
            }
        }
    }

    fun lookup(input: String) {
        if (_uiState.value.lookupInFlight) return
        viewModelScope.launch {
            _uiState.update { it.copy(lookupInFlight = true, lookupError = null) }
            when (val result = useCase.lookup(input)) {
                is JobLookupResult.Loaded -> {
                    val detail = result.snapshot.detail!!  // the use case guarantees it on Loaded
                    _uiState.update {
                        it.copy(
                            jobs = result.snapshot.jobs, detail = detail, detailError = null,
                            lookupInFlight = false,
                        )
                    }
                    _navigateToDetail.send(detail.jobCard)
                }
                is JobLookupResult.Failed -> _uiState.update { state ->
                    state.copy(
                        jobs = result.snapshot?.jobs ?: state.jobs,
                        lookupInFlight = false,
                        lookupError = result.message,
                    )
                }
            }
        }
    }

    /** Detail is opening for [jobCard]. Reuses a detail a lookup just loaded; otherwise reads it. */
    fun openDetail(jobCard: String) {
        viewedJobCard = jobCard
        if (_uiState.value.detail?.jobCard == jobCard) return
        _uiState.update { it.copy(detail = null) }
        loadDetail(jobCard)
    }

    private fun loadDetail(jobCard: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailLoading = true, detailError = null) }
            when (val result = useCase.read(jobCard)) {
                is JobLookupResult.Loaded -> _uiState.update {
                    it.copy(jobs = result.snapshot.jobs, detail = result.snapshot.detail, detailLoading = false)
                }
                is JobLookupResult.Failed -> _uiState.update {
                    it.copy(detailLoading = false, detailError = result.message)
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    @VisibleForTesting
    internal fun setLookupInFlightForTest(inFlight: Boolean) {
        _uiState.update { it.copy(lookupInFlight = inFlight) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*JobLookupViewModelTest*"
```

Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
./gradlew testDebugUnitTest
graphify update .
git add -A
git commit -m "feat(joblookup): view model with scan, lookup, detail and push refresh"
```

---

### Task 12: Job Lookup and Job Detail screens

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobFormat.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreen.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobDetailScreen.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobFormatTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/NavRoutes.kt`, `AppNavGraph.kt`

**Interfaces:**
- Consumes: Task 11's `JobLookupViewModel`/`JobLookupUiState`, Task 10's domain models. `AppScaffold(title, status, onBack, onSettings, operatorName, operatorRole, onLogout, loading, content)`, `LabelValueRow(label, value)`, `StatusCard`, theme colours.
- Produces:
  - `NavRoutes.JOBS = "jobs"`, `JOB_LOOKUP = "jobs/lookup"`, `JOB_DETAIL = "jobs/detail/{jobCard}"`, `jobDetail(jobCard: String)`
  - `internal fun formatQuantity(value: Double): String`
  - `internal fun JobMaterial.quantityLine(): String`
  - `internal fun JobPreparation.summaryLine(): String`

- [ ] **Step 1: Write the failing formatting test**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import org.junit.Assert.assertEquals
import org.junit.Test

class JobFormatTest {

    private fun material(unit: String = "kg", excluded: Boolean = false) = JobMaterial(
        code = "1600000301", name = "HD WHITE", unit = unit,
        perMix = 69.631, required = 557.049, collected = 100.0, excluded = excluded,
    )

    @Test
    fun `a whole number has no decimals`() = assertEquals("25", formatQuantity(25.0))

    @Test
    fun `trailing zeros are dropped`() = assertEquals("3.5", formatQuantity(3.50))

    @Test
    fun `at most three decimals are shown`() = assertEquals("557.049", formatQuantity(557.0490000001))

    @Test
    fun `half-way rounds away from zero`() = assertEquals("0.001", formatQuantity(0.0005))

    @Test
    fun `a large quantity never uses scientific notation`() = assertEquals("12500000", formatQuantity(1.25e7))

    @Test
    fun `zero is zero`() = assertEquals("0", formatQuantity(0.0))

    @Test
    fun `negative zero is zero`() = assertEquals("0", formatQuantity(-0.0))

    @Test
    fun `a material line shows per-mix, required, collected and remaining with the unit`() =
        assertEquals("69.631 kg per mix · 100 of 557.049 kg · 457.049 kg to go", material().quantityLine())

    @Test
    fun `a blank unit leaves no dangling spaces`() =
        assertEquals("69.631 per mix · 100 of 557.049 · 457.049 to go", material(unit = "").quantityLine())

    @Test
    fun `an excluded material says so instead of showing a requirement`() =
        assertEquals("69.631 kg per mix · excluded from this job", material(excluded = true).quantityLine())

    @Test
    fun `a preparation line shows mixes and stage`() =
        assertEquals("2 mixes · 1 mixed · 0 produced · Collecting",
            JobPreparation("PREP_1", mixCount = 2, mixed = 1, produced = 0, stage = "Collecting").summaryLine())

    @Test
    fun `a single-mix preparation is singular`() =
        assertEquals("1 mix · 0 mixed · 0 produced · Mixing",
            JobPreparation("PREP_2", mixCount = 1, mixed = 0, produced = 0, stage = "Mixing").summaryLine())
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*JobFormatTest*"
```

Expected: compilation FAIL — `formatQuantity` unresolved.

- [ ] **Step 3: Write `JobFormat.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A quantity for a handheld: at most three decimals, trailing zeros dropped, never scientific
 * notation. SAP quantities arrive as doubles with float noise (`557.0490000001`), and a bare
 * toString() would put that — or `1.25E7` — on a shop-floor screen.
 */
internal fun formatQuantity(value: Double): String {
    val text = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}

private fun withUnit(value: Double, unit: String): String =
    if (unit.isBlank()) formatQuantity(value) else "${formatQuantity(value)} $unit"

internal fun JobMaterial.quantityLine(): String {
    val perMixText = "${withUnit(perMix, unit)} per mix"
    if (excluded) return "$perMixText · excluded from this job"
    val ofText = if (unit.isBlank()) "${formatQuantity(collected)} of ${formatQuantity(required)}"
    else "${formatQuantity(collected)} of ${formatQuantity(required)} $unit"
    return "$perMixText · $ofText · ${withUnit(remaining, unit)} to go"
}

internal fun JobPreparation.summaryLine(): String =
    "$mixCount ${if (mixCount == 1) "mix" else "mixes"} · $mixed mixed · $produced produced · $stage"
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*JobFormatTest*"
```

Expected: PASS, 12 tests.

- [ ] **Step 5: Write `JobLookupScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobLookupScreen(
    onJobFound: (jobCard: String) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    viewModel: JobLookupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // With the keyboard up, Back closes it rather than leaving and losing the typed number.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) { keyboard?.hide(); focusManager.clearFocus() } else onBack()
    }

    // Re-read on every resume (first entry, back from Detail, and return from background), and
    // accept scans only while this screen is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { viewModel.setLookupScreenActive(true); viewModel.refreshList() }
                Lifecycle.Event.ON_PAUSE -> viewModel.setLookupScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setLookupScreenActive(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToDetail.collect { jobCard -> onJobFound(jobCard) }
    }

    AppScaffold(
        title = "Job Cards",
        status = connectionStatus,
        onBack = onBack,
        onSettings = onSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.lookupInFlight || state.listLoading,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Production order number") },
                supportingText = { Text("Scan the job card or type the number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookup(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary, cursorColor = AmberPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.lookup(input) },
                enabled = input.isNotBlank() && !state.lookupInFlight,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.lookupInFlight) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Look up")
                }
            }
            state.lookupError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Text("Jobs on Station 2", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            when {
                state.jobs.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.jobs, key = { it.jobCard }) { job ->
                        StatusCard(
                            tone = if (job.closed) StatusTone.Idle else StatusTone.Running,
                            onClick = { onJobFound(job.jobCard) },
                            enabled = !state.lookupInFlight,
                        ) { accent ->
                            Text(job.jobCard, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text(
                                buildString {
                                    append("${job.requiredMixes} ${if (job.requiredMixes == 1) "mix" else "mixes"}")
                                    if (job.closed) append(" · closed")
                                },
                                style = MaterialTheme.typography.labelMedium, color = accent,
                            )
                            if (job.product.isNotBlank()) {
                                Text(
                                    job.product, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                state.listError != null ->
                    Text(state.listError!!, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                !state.listLoading ->
                    Text("No jobs yet. Look one up to load it.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

If `StatusCard`/`StatusTone` parameter names differ from this call, match the signature in `ui/components/StatusCard.kt`. The deleted 4.1 `JobLookupScreen` used exactly `StatusCard(tone =, onClick =, enabled =) { accent -> }` with `StatusTone.Running`/`Idle`, so this should compile as written.

- [ ] **Step 6: Write `JobDetailScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.util.formatLocalDateTime

/** Read-only: no scanning, no actions, no mutations. */
@Composable
fun JobDetailScreen(
    jobCard: String,
    onBack: () -> Unit,
    viewModel: JobLookupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(jobCard) { viewModel.openDetail(jobCard) }

    AppScaffold(
        title = jobCard,
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.detailLoading,
    ) { padding ->
        val detail = state.detail?.takeIf { it.jobCard == jobCard }
        when {
            detail != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (detail.product.isNotBlank()) {
                            Text(detail.product, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        }
                        LabelValueRow("Required mixes", detail.requiredMixes.toString())
                        LabelValueRow("Prepared mixes", detail.allocatedMixes.toString())
                        LabelValueRow(
                            "Output per mix",
                            if (detail.unit.isBlank()) formatQuantity(detail.outputPerMix)
                            else "${formatQuantity(detail.outputPerMix)} ${detail.unit}",
                        )
                        detail.capturedAtUtc?.let { LabelValueRow("Loaded", formatLocalDateTime(it)) }
                        if (detail.closed) LabelValueRow("Status", "Closed")
                        state.detailError?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Materials", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    }
                }
                // Keyed by position as well as code: a BOM listing one code twice must not crash LazyColumn.
                itemsIndexed(detail.materials, key = { index, m -> "$index-${m.code}" }) { _, material ->
                    ListItem(
                        modifier = Modifier.alpha(if (material.excluded) 0.5f else 1f),
                        headlineContent = { Text(material.name.ifBlank { material.code }, color = TextPrimary) },
                        supportingContent = { Text(material.quantityLine(), color = TextMuted) },
                        trailingContent = { Text(if (material.excluded) "Excluded" else material.code, color = TextMuted) },
                    )
                }
                if (detail.preparations.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Preparations", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    }
                    items(detail.preparations, key = { it.id }) { prep ->
                        ListItem(
                            headlineContent = { Text(prep.id, color = TextPrimary) },
                            supportingContent = { Text(prep.summaryLine(), color = TextMuted) },
                        )
                    }
                }
            }
            state.detailError != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(state.detailError!!, color = TextMuted)
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
```

Before writing the import, check the local-time helper's real name:

```bash
grep -n "^fun \|^internal fun " app/src/main/java/com/mitas/ppnam/station2aa/ui/util/*.kt
```

Use the function there that formats an `Instant` for display (tested by `TimeFormatTest`) in place of `formatLocalDateTime`. If none takes an `Instant`, drop the "Loaded" row rather than adding a formatter.

- [ ] **Step 7: Add the routes and the nested graph**

`NavRoutes.kt`:

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"

    /** Parent graph for Job Lookup + Detail, which share one JobLookupViewModel. */
    const val JOBS = "jobs"
    const val JOB_LOOKUP = "jobs/lookup"
    const val JOB_DETAIL = "jobs/detail/{jobCard}"

    fun jobDetail(jobCard: String) = "jobs/detail/$jobCard"
}
```

In `AppNavGraph.kt`, in the `HOME` composable, change `onOpenJobCards = {}` to `onOpenJobCards = { navController.navigate(NavRoutes.JOBS) }`. After the `SETTINGS` composable add:

```kotlin
        navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.JOBS) {
            composable(NavRoutes.JOB_LOOKUP) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(NavRoutes.JOBS) }
                JobLookupScreen(
                    onJobFound = { jobCard -> navController.navigate(NavRoutes.jobDetail(jobCard)) },
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel<JobLookupViewModel>(parentEntry),
                )
            }
            composable(NavRoutes.JOB_DETAIL) { backStackEntry ->
                val jobCard = backStackEntry.arguments?.getString("jobCard") ?: return@composable
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(NavRoutes.JOBS) }
                JobDetailScreen(
                    jobCard = jobCard,
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel<JobLookupViewModel>(parentEntry),
                )
            }
        }
```

Add the imports `androidx.compose.runtime.remember`, `androidx.navigation.compose.navigation`, and `com.mitas.ppnam.station2aa.ui.joblookup.JobDetailScreen`, `JobLookupScreen`, `JobLookupViewModel`, where they are missing. Logout navigation stays with `SessionWatcher`, which reacts to the session going null.

- [ ] **Step 8: Build and test**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
graphify update .
git add -A
git commit -m "feat(joblookup): job lookup and read-only job detail screens"
```

### CHECKPOINT A — on-device, against the simulator

```bash
python tools/backend-sim/sim.py            # plus --host/--transport flags for your broker
./gradlew installDebug
python tools/test-harness/sniffer.py       # in a third terminal; captures the wire
```

On the device, verify:
1. Login as `operator1` / `pass` succeeds and lands on Home.
2. Job Cards lists `510019068`.
3. Typing `510019068` → Look up opens Detail showing required mixes, prepared mixes 2, the materials, and preparation `PREP_demo0001`.
4. Scanning or typing `510018531` shows Station 2's rejection message and stays on Job Lookup.
5. Typing `JC-1` shows "Scan or enter the production order number (digits only)", and the sniffer shows **no** request for it.
6. While on Job Lookup, publish `{"cmd":"invalidate"}` to `PPNAM/_sim/control`. The sniffer shows a new `rev2_general_requested` `read` with a new `messageId`.
7. Settings shows a `scanner_`-prefixed device id, Connected, and Station 2 online.

In the sniffer capture, confirm that every request carries `schemaVersion:"rev2.1"` and `sessionId` (except the two `scram_*` requests), and none carries `operatorSessionId` or `correlationKey`. Save the capture as `docs/test-evidence-2026-09-30/wire-checkpoint-a.jsonl`. Stop and report if anything fails: Phase 5 is harder to debug on top of a broken slice.

---

## Phase 5: Base-standard guards

### Task 13: Reject duplicate property names on inbound messages

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/StrictJson.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/StrictJsonTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/Rev2TransportTest.kt`

**Interfaces:**
- Consumes: Task 8's `handleIncomingResponse`/`parseOutcome`.
- Produces: `object StrictJson { fun parse(raw: String): JsonElement }`, throwing `DuplicatePropertyException(path: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonTest {

    @Test
    fun `accepts an ordinary object`() {
        assertEquals("scanner_1", StrictJson.parse("""{"success":true,"deviceId":"scanner_1"}""").asJsonObject["deviceId"].asString)
    }

    @Test
    fun `rejects an exact duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"success":true,"success":false}""") }
    }

    @Test
    fun `rejects a case-insensitive duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"success":true,"Success":false}""") }
    }

    @Test
    fun `rejects a duplicate nested inside data`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"data":{"job":{"id":"1","ID":"2"}}}""") }
    }

    @Test
    fun `rejects a duplicate inside an array element`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"data":{"jobs":[{"id":"1"},{"id":"1","Id":"2"}]}}""")
        }
    }

    @Test
    fun `the same name at different depths is not a duplicate`() {
        val parsed = StrictJson.parse("""{"id":1,"data":{"id":2}}""").asJsonObject
        assertEquals(2, parsed["data"].asJsonObject["id"].asInt)
    }

    @Test
    fun `a long numeric id is not rounded`() {
        assertEquals("510019068000000001", StrictJson.parse("""{"n":510019068000000001}""").asJsonObject["n"].asString)
    }

    @Test
    fun `reports the path of the offending property`() {
        val thrown = assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"data":{"job":{"a":1,"A":2}}}""")
        }
        assertEquals("data/job/A", thrown.path)
    }
}
```

Add to `Rev2TransportTest`:

```kotlin
    @Test
    fun `a reply with a duplicate property is dropped, so the request times out rather than guessing`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        val json = """{"inResponseToMessageId":"${idOf(0)}","success":true,"Success":false,"data":{"value":"x"}}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), call.await())
    }
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*StrictJsonTest*" --tests "*Rev2TransportTest*"
```

Expected: compilation FAIL — `StrictJson` unresolved.

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
 * (base standard §4; Station 2 applies the same rule to what it receives).
 *
 * Gson's own parser silently keeps the LAST value for a repeated key, so
 * `{"success":true,"Success":false}` would parse as a failure. That is a message to reject, not one
 * to guess at — and the guess here is whether an operation succeeded.
 *
 * Duplicates are scoped per object: the same name at different depths is ordinary nesting.
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
            while (reader.hasNext()) arr.add(read(reader, "$path[${index++}]"))
            reader.endArray()
            arr
        }
        // Numbers stay as text until used: going via Double would quietly round a long id.
        JsonToken.NUMBER -> JsonPrimitive(LazilyParsedNumber(reader.nextString()))
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Unexpected token ${reader.peek()} at '$path'")
    }
}
```

- [ ] **Step 4: Parse every inbound message through it**

In `MqttRepositoryImpl.handleIncomingResponse`, replace the envelope parse with a strict parse, then bind from the tree:

```kotlin
        val tree = try {
            StrictJson.parse(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: DuplicatePropertyException) {
            Log.w(TAG, "Dropping message on $topic with a duplicate property: ${e.path}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable message on $topic", e)
            return
        } ?: return
        val envelope = try {
            gson.fromJson(tree, ResponseEnvelope::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping message on $topic with an unreadable envelope", e)
            return
        } ?: return
```

In `parseOutcome`, replace `JsonParser.parseString(raw)` with `StrictJson.parse(raw)` and remove the `JsonParser` import. A message only reaches `parseOutcome` after passing the strict parse above.

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, including 8 `StrictJsonTest` tests and the new transport test.

- [ ] **Step 6: Commit**

```bash
graphify update .
git add -A
git commit -m "feat(mqtt): reject duplicate property names on inbound messages"
```

---

### Task 14: Outbound guards — credential fields and payload size

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/OutboundGuard.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/OutboundGuardTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelope.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt`

**Interfaces:**
- Consumes: Task 8's `RequestEnvelope.build`.
- Produces:
  - `object OutboundGuard { const val MAX_PAYLOAD_CHARS = 65_536; fun assertNoCredentialFields(element: JsonElement); fun assertWithinSize(json: String) }`
  - `PlaintextCredentialException(path)`, `OversizedPayloadException(length)`
  - `RequestEnvelope.build` throws either exception before returning

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboundGuardTest {

    private fun check(raw: String) = OutboundGuard.assertNoCredentialFields(JsonParser.parseString(raw))

    @Test
    fun `allows a message with no credential field`() =
        check("""{"action":"lookup","jobCard":"510019068","sessionId":"s"}""")

    @Test
    fun `rejects a top-level password`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"password":"x"}""") }
    }

    @Test
    fun `rejects any field name containing password, whatever the case`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"managerPASSWORD":"x"}""") }
        assertThrows(PlaintextCredentialException::class.java) { check("""{"passwordHash":"x"}""") }
    }

    @Test
    fun `rejects a credential nested at any depth`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"a":{"b":{"password":"x"}}}""") }
    }

    @Test
    fun `rejects a credential inside an array element`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"items":[{"u":"a"},{"password":"x"}]}""") }
    }

    @Test
    fun `allows the SCRAM proof fields`() =
        check("""{"challengeId":"c","clientFinalWithoutProof":"c=biws,r=n","clientProof":"dGVzdA=="}""")

    @Test
    fun `a value containing the word password is fine — only names are checked`() =
        check("""{"note":"password reset requested"}""")

    @Test
    fun `reports the path of the offending field`() {
        val thrown = assertThrows(PlaintextCredentialException::class.java) { check("""{"outer":{"password":"x"}}""") }
        assertEquals("outer/password", thrown.path)
    }

    @Test
    fun `a payload at the size limit passes`() =
        OutboundGuard.assertWithinSize("x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS))

    @Test
    fun `a payload one character over the limit is refused`() {
        assertThrows(OversizedPayloadException::class.java) {
            OutboundGuard.assertWithinSize("x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS + 1))
        }
    }
}
```

Add to `RequestEnvelopeTest`:

```kotlin
    @Test
    fun `build refuses a payload carrying a password field`() {
        data class Leaky(val action: String, val password: String)
        org.junit.Assert.assertThrows(PlaintextCredentialException::class.java) { build(Leaky("read", "hunter2")) }
    }

    @Test
    fun `build refuses an oversized request`() {
        data class Huge(val action: String, val filler: String)
        org.junit.Assert.assertThrows(OversizedPayloadException::class.java) {
            build(Huge("read", "x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS)))
        }
    }
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*OutboundGuardTest*" --tests "*RequestEnvelopeTest*"
```

Expected: compilation FAIL — `OutboundGuard` unresolved.

- [ ] **Step 3: Write `OutboundGuard.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonElement

/** An outgoing message carried a field whose name contains "password", at [path]. */
class PlaintextCredentialException(val path: String) :
    IllegalArgumentException("Refusing to publish a message with a credential field at '$path'")

/** An outgoing message exceeded Station 2's request size limit. */
class OversizedPayloadException(val length: Int) :
    IllegalArgumentException("Refusing to publish a $length-character request (limit ${OutboundGuard.MAX_PAYLOAD_CHARS})")

/**
 * Checks every request before it reaches the broker.
 *
 * Station 2 rejects a top-level field whose name contains `password` with
 * `password_field_forbidden`, and a request over 65,536 characters with `invalid_envelope`. Catching
 * both here matters more than the server's rejection: once a password is published it is in the
 * broker's logs and every subscriber's buffer. This guard is stricter than the server — any depth —
 * and throws rather than scrubbing, so a build defect cannot ship quietly. Only NAMES are checked;
 * the SCRAM proof is the mechanism that replaced the password, not a copy of it.
 */
object OutboundGuard {

    const val MAX_PAYLOAD_CHARS = 65_536

    fun assertNoCredentialFields(element: JsonElement, path: String = "") {
        when {
            element.isJsonObject -> for ((name, value) in element.asJsonObject.entrySet()) {
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (name.contains("password", ignoreCase = true)) throw PlaintextCredentialException(childPath)
                assertNoCredentialFields(value, childPath)
            }
            element.isJsonArray -> element.asJsonArray.forEachIndexed { index, item ->
                assertNoCredentialFields(item, "$path[$index]")
            }
        }
    }

    fun assertWithinSize(json: String) {
        if (json.length > MAX_PAYLOAD_CHARS) throw OversizedPayloadException(json.length)
    }
}
```

- [ ] **Step 4: Call it from `RequestEnvelope.build`**

Immediately after `val obj = gson.toJsonTree(payload).asJsonObject`, add:

```kotlin
        OutboundGuard.assertNoCredentialFields(obj)
```

Replace `return gson.toJson(obj)` with:

```kotlin
        return gson.toJson(obj).also(OutboundGuard::assertWithinSize)
```

Add to the class KDoc: "Throws [PlaintextCredentialException] or [OversizedPayloadException] rather than publishing — see [OutboundGuard]."

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
graphify update .
git add -A
git commit -m "feat(mqtt): refuse to publish credential fields or oversized requests"
```

---

### Task 15: Redaction and a structured log line for every message

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/Redact.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttLog.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RedactTest.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttLogTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `object Redact { const val PLACEHOLDER = "***"; fun payload(raw: String): String }`
  - `enum class Direction { OUT, IN }`
  - `object MqttLog { fun line(direction, topic, qos, retain, deviceId, messageType, action: String?, result, durationMs: Long?, payload: String?): String; fun message(...) }`

- [ ] **Step 1: Write the failing tests**

`RedactTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `redacts every rev2_1 secret`() {
        val raw = """{"sessionId":"S1","clientProof":"S2","serverSignature":"S3",
            "clientFinalWithoutProof":"S4","salt":"S5","serverFirstMessage":"S6","serverNonce":"S7",
            "clientNonce":"S8","challengeId":"S9","password":"S10","managerPassword":"S11",
            "brokerPassword":"S12"}"""
        val out = Redact.payload(raw)
        (1..12).forEach { assertFalse("leaked S$it", out.contains("\"S$it\"")) }
    }

    @Test
    fun `redacts the session nested under data`() {
        val out = Redact.payload("""{"success":true,"data":{"session":{"sessionId":"secret-id","displayName":"Op"}}}""")
        assertFalse(out.contains("secret-id"))
        assertTrue(out.contains("Op"))
    }

    @Test
    fun `redacts case-insensitively and inside arrays`() {
        val out = Redact.payload("""{"items":[{"SESSIONID":"secret"}]}""")
        assertFalse(out.contains("secret"))
    }

    @Test
    fun `keeps non-secret fields readable`() {
        val out = Redact.payload("""{"action":"lookup","jobCard":"510019068","messageId":"m-1"}""")
        assertTrue(out.contains("510019068"))
        assertTrue(out.contains("m-1"))
    }

    @Test
    fun `unparseable input is reported, never echoed`() {
        assertEquals("<unparseable payload>", Redact.payload("password=hunter2"))
    }
}
```

`MqttLogTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttLogTest {

    private fun line(payload: String? = null, durationMs: Long? = 42L, action: String? = "lookup") = MqttLog.line(
        direction = Direction.OUT,
        topic = "PPNAM/station_2/scanner_x/req/rev2_general_requested",
        qos = 1, retain = false, deviceId = "scanner_x",
        messageType = "rev2_general_requested", action = action,
        result = "published", durationMs = durationMs, payload = payload,
    )

    @Test
    fun `carries every field the base standard requires, plus the rev2_1 action`() {
        val out = line()
        listOf("dir=OUT", "topic=PPNAM/station_2/scanner_x/req/rev2_general_requested", "qos=1",
            "retain=false", "deviceId=scanner_x", "type=rev2_general_requested", "action=lookup",
            "result=published", "durationMs=42").forEach { assertTrue("missing $it in: $out", out.contains(it)) }
    }

    @Test
    fun `omits duration and action when there are none`() {
        val out = line(durationMs = null, action = null)
        assertFalse(out.contains("durationMs"))
        assertFalse(out.contains("action="))
    }

    @Test
    fun `a logged payload is redacted`() {
        val out = line(payload = """{"sessionId":"secret-session","action":"read"}""")
        assertFalse(out.contains("secret-session"))
        assertTrue(out.contains("read"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*RedactTest*" --tests "*MqttLogTest*"
```

Expected: compilation FAIL.

- [ ] **Step 3: Write `Redact.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Scrubs secrets from a payload before it reaches a log (base standard §7; rev2.1 also requires
 * session ids be redacted from MQTT logging).
 *
 * Unparseable input returns a fixed marker rather than the original text: a payload that failed to
 * parse is exactly the one most likely to be logged for investigation, and it can still contain a
 * complete secret.
 */
object Redact {

    const val PLACEHOLDER = "***"

    private val SECRET_KEYS = setOf(
        "sessionid",
        // SCRAM material — enough, together, to replay or brute-force a login.
        "clientproof", "serversignature", "clientfinalwithoutproof", "salt",
        "serverfirstmessage", "servernonce", "clientnonce", "challengeid",
    )

    fun payload(raw: String): String = try {
        redact(JsonParser.parseString(raw)).toString()
    } catch (e: Exception) {
        "<unparseable payload>"
    }

    private fun isSecret(name: String): Boolean {
        val key = name.lowercase()
        return key in SECRET_KEYS || key.contains("password")
    }

    private fun redact(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { out ->
            for ((name, value) in element.asJsonObject.entrySet()) {
                out.add(name, if (isSecret(name)) JsonPrimitive(PLACEHOLDER) else redact(value))
            }
        }
        element.isJsonArray -> JsonArray().also { out -> element.asJsonArray.forEach { out.add(redact(it)) } }
        else -> element
    }
}
```

- [ ] **Step 4: Write `MqttLog.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import android.util.Log

enum class Direction { OUT, IN }

/**
 * One log line per MQTT message: direction, topic, QoS, retain, device id, message type, rev2.1
 * `action`, result/error code and duration (base standard §7).
 *
 * [line] builds the string and [message] emits it, split so the format is unit-testable without an
 * Android logger. A payload always passes through [Redact] — there is no way to log a raw one.
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
        action: String? = null,
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
        if (!action.isNullOrBlank()) append(" action=").append(action)
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
        action: String? = null,
        result: String,
        durationMs: Long? = null,
        payload: String? = null,
    ) {
        Log.i(TAG, line(direction, topic, qos, retain, deviceId, messageType, action, result, durationMs, payload))
    }
}
```

- [ ] **Step 5: Run to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "*RedactTest*" --tests "*MqttLogTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Emit a line for every message**

In `MqttRepositoryImpl`:
- In `request()`:
  - Record `val startedAt = System.currentTimeMillis()` before the loop, and `val action = (gson.toJsonTree(payload) as? com.google.gson.JsonObject)?.get("action")?.takeIf { it.isJsonPrimitive }?.asString`.
  - After each successful `publishFn`, call `MqttLog.message(Direction.OUT, topic, 1, false, deviceId, requestType, action, "published", payload = json)`.
  - On return from `parseOutcome`, log with `durationMs = System.currentTimeMillis() - startedAt` and result `"success"`, the error code's `raw` (or `"rejected"` when null), or `"malformed"`.
  - On timeout, log with result `"timeout"`.
- In `handleIncomingResponse`, once the envelope is parsed, call `MqttLog.message(Direction.IN, topic, 1, false, deviceId, MqttTopics.responseTypeOf(topic), null, result = if (envelope.success) "success" else envelope.error.ifBlank { if (envelope.inResponseToMessageId.isBlank()) "push" else "rejected" })`. Pass no payload: inbound bodies can be large, and the envelope fields already carry what diagnosis needs.
- Presence publishes: after each `STATUS_ONLINE` publish in `subscribeAndAnnounce` and `handleOwnPresence`, and in `publishOfflineBestEffort`, call `MqttLog.message(Direction.OUT, MqttTopics.devicePresence(deviceId), 2, true, deviceId, "presence", null, "online")` (or `"offline"`). These are log calls only; the publish code itself does not change.

Remove the three existing ad-hoc `Log.w(TAG, "retrying …")` / `"publish attempt … failed"` lines only if a `MqttLog` line now covers the same event. Otherwise keep them.

- [ ] **Step 7: Verify no raw payload is logged anywhere**

```bash
grep -rn "Log\.[diwe](.*\(raw\|json\|payload\|bytes\)" app/src/main/java --include=*.kt | grep -v "MqttLog.kt"
```

Expected: no line logs a payload variable. If one does, route it through `Redact.payload(...)` or remove it.

- [ ] **Step 8: Build, test and commit**

```bash
./gradlew assembleDebug testDebugUnitTest
graphify update .
git add -A
git commit -m "feat(mqtt): redact secrets and log every message with the base-standard fields"
```

---

### Task 16: 10 s workflow timeout, and final verification

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AppSettings.kt`
- Modify: `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/AppSettingsTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: `AppSettings().requestTimeoutMs == 10_000L`.

- [ ] **Step 1: Write the failing test**

Add to `AppSettingsTest`:

```kotlin
    @Test
    fun `the default workflow timeout is the base standard's 10 seconds`() {
        assertEquals(10_000L, AppSettings().requestTimeoutMs)
    }
```

If an existing test asserts `20_000L`, change it to `10_000L`.

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew testDebugUnitTest --tests "*AppSettingsTest*"
```

Expected: FAIL — expected 10000, was 20000.

- [ ] **Step 3: Change the default**

In `AppSettings.kt`: `val requestTimeoutMs: Long = 10_000L`. A value already saved on a device is kept; this changes only the default.

- [ ] **Step 4: Full verification**

```bash
./gradlew clean assembleDebug testDebugUnitTest
python tools/backend-sim/selftest.py --direct
grep -rn "\"4\.1\"\|operatorSessionId\"\|correlationKey\|login_requested\|reader_logout\|job_card_load\|active_job_cards_requested\|allowedTabs" app/src/main --include=*.kt
```

Expected: BUILD SUCCESSFUL, then `all N checks passed`, then **no grep output**. (The `OperatorSession.operatorSessionId` Kotlin property is not matched, because the pattern requires a closing quote.)

- [ ] **Step 5: Commit**

```bash
graphify update .
git add -A
git commit -m "feat(mqtt): default workflow timeout to the base standard's 10 s"
```

### CHECKPOINT B — on-device, final

Repeat all seven checks from Checkpoint A against the simulator, with the sniffer running, and additionally confirm:
- `adb logcat -s MqttWire` shows one `dir=OUT` line per request and one `dir=IN` line per reply, carrying `action=` for General requests, with no `sessionId` or SCRAM value in clear text.
- With the simulator stopped, a lookup fails with "Station 2 did not respond" after about 30 s (3 × 10 s), and the app stays usable.

Save the capture as `docs/test-evidence-2026-09-30/wire-checkpoint-b.jsonl` and commit it:

```bash
git add docs/test-evidence-2026-09-30
git commit -m "test: rev2.1 wire captures from on-device checkpoints"
```

When the real Station 2 WPF server is reachable, repeat checks 1–5 against it before calling the slice done.
