# MQTT Schema 3.0 Auth & Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver v3 session semantics — session state through the model, transport-level `session_required` handling, removal of the contract-violating role gate, and a connection banner that tells the truth.

**Architecture:** The session state machine is server-side; the client's only obligation is to react to `session_required`, which the transport intercepts centrally so every request is covered. Permission enforcement on `allowedActions`/`role` is deleted outright — v3 authorises privileged actions solely by manager credentials carried in the request.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson, HiveMQ MQTT5, JUnit4 + mockito-kotlin + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-16-mqtt-schema-3-auth-session-design.md`
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Builds on:** sub-project 1, merged to master as `0c3dd9e`

## Global Constraints

- **Never enforce with `allowedActions` or `role`.** Both are display hints. The contract: *"Do not use this list to enforce anything"*; *"`role` is informational only."*
- **Manager credentials are required on EVERY privileged action**, even when the sender is a Manager. There is no direct-cancel path in v3.
- The v3 action id for cancelling a collection is `ingredient_collection_cancel`. `cancel_premix_direct` does not exist.
- **Every field in a Gson response DTO MUST keep a default value.** Kotlin only emits the no-arg constructor Gson needs when ALL params have defaults; add one without and Gson silently Unsafe-allocates, making EVERY field null at runtime with no compile error.
- Closed contract vocabularies are enums whose constant names match wire values exactly (`SessionState`, `HopperState`, `PalletState`). Open vocabularies (`errorCode`, `nextAction`) are value classes.
- **mockito-kotlin's `any()` EXCLUDES nulls.** Use `anyOrNull()` where a call site passes null.
- Do not disturb: the `CancellationException` rethrow in `request()`, or `bytes` being built once outside the retry loop.
- Run tests: `./gradlew testDebugUnitTest`; build: `./gradlew assembleDebug`. On Windows use `./gradlew.bat`; you may need `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- **Never `git add -A`.** Stage explicit paths only.
- `C:\Dev\PPNAM-Station-2` is read-only reference. Never edit it, never push anything anywhere.

## Sequencing

Tasks 1-2 extend the model and transport. Task 3 wires navigation. Task 4 is the security fix. Tasks 5-6 are UI. Task 4 depends on nothing but is placed after the plumbing so its tests can assert real behaviour.

---

### Task 1: SessionState through the DTO and model

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/SessionState.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/model/SessionStateTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt` (extend)

**Interfaces:**
- Consumes: `MqttOutcome`, `request()` from sub-project 1.
- Produces: `SessionState` enum with `fromWire(raw: String?): SessionState`; `OperatorSession.sessionState: SessionState`; `OperatorSession.sessionExpiresAtUtc: Instant?`; `OperatorContextResponse.sessionState`/`.sessionExpiresAtUtc`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/ppnam/station2aa/domain/model/SessionStateTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {

    @Test
    fun `every contract session state maps from its wire value`() {
        assertEquals(SessionState.Active, SessionState.fromWire("Active"))
        assertEquals(SessionState.Suspended, SessionState.fromWire("Suspended"))
        assertEquals(SessionState.Closed, SessionState.fromWire("Closed"))
    }

    @Test
    fun `an unknown state degrades to Active rather than locking the operator out`() {
        assertEquals(SessionState.Active, SessionState.fromWire("SomeFutureState"))
    }

    @Test
    fun `an absent state degrades to Active`() {
        assertEquals(SessionState.Active, SessionState.fromWire(null))
    }
}
```

Add to `AuthUseCaseTest`:

```kotlin
    @Test
    fun `a successful login carries session state and expiry`() = runTest {
        stub(
            MqttOutcome.Accepted(
                accepted.copy(sessionState = "Active", sessionExpiresAtUtc = "2026-07-17T00:00:01Z"),
                NextAction.NONE,
            )
        )

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertEquals(SessionState.Active, session.sessionState)
        assertEquals(Instant.parse("2026-07-17T00:00:01Z"), session.sessionExpiresAtUtc)
    }

    @Test
    fun `a login answered with a Closed session is a failure`() = runTest {
        // Accepting a session Station 2 has already closed would strand the operator in a UI that
        // rejects every action.
        stub(MqttOutcome.Accepted(accepted.copy(sessionState = "Closed"), NextAction.LOGIN))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `an unparseable expiry does not fail the login`() = runTest {
        stub(MqttOutcome.Accepted(accepted.copy(sessionExpiresAtUtc = "not-a-timestamp"), NextAction.NONE))

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertNull(session.sessionExpiresAtUtc)
    }
```

Add `import com.mitas.ppnam.station2aa.domain.model.SessionState` and `import java.time.Instant` to the test.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.SessionStateTest"`
Expected: FAIL — `Unresolved reference: SessionState`.

- [ ] **Step 3: Create the enum**

Create `app/src/main/java/com/ppnam/station2aa/domain/model/SessionState.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

/**
 * Contract v3.0 session state. Constant names match the wire values exactly.
 *
 * This state machine is almost entirely Station 2's: presence drives Active/Suspended, and a valid
 * request on a Suspended session resumes it implicitly. The client mirrors the value for display
 * and reacts to `session_required`; it never drives the machine itself.
 */
enum class SessionState {
    /** Device is online and the session is in use. */
    Active,

    /** The device went offline. The session is preserved, not destroyed — any valid request resumes it. */
    Suspended,

    /** Terminal: logged out, replaced by a newer login, or hit sessionExpiresAtUtc. */
    Closed;

    companion object {
        /**
         * Degrades an unknown or absent value to [Active] rather than locking an operator out of a
         * working session over an unrecognised string.
         */
        fun fromWire(raw: String?): SessionState =
            entries.firstOrNull { it.name == raw } ?: Active
    }
}
```

- [ ] **Step 4: Extend the DTO**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`, add to `OperatorContextResponse` (keeping every field defaulted, per the Gson invariant documented on `ResponseEnvelope`):

```kotlin
    val sessionState: String? = null,
    val sessionExpiresAtUtc: String? = null,
```

Delete the KDoc line saying these are deliberately unmapped for sub-project 2 — this is sub-project 2.

- [ ] **Step 5: Extend the session model**

In `app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt`:

```kotlin
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only. No rule in the contract gates on role — never branch on this. */
    val role: String,
    val sessionState: SessionState = SessionState.Active,
    val sessionExpiresAtUtc: Instant? = null,
    /** A UI display hint only. The contract forbids enforcing anything with this list. */
    val allowedActions: List<String> = emptyList(),
    /** A UI display hint only. */
    val allowedTabs: List<String> = emptyList()
)
```

Add imports `com.mitas.ppnam.station2aa.domain.model.SessionState` and `java.time.Instant`.

- [ ] **Step 6: Map them in AuthUseCase**

In `AuthUseCase.login()`'s `Accepted` branch, replace the session construction:

```kotlin
            is MqttOutcome.Accepted -> {
                val response = outcome.body
                val state = SessionState.fromWire(response.sessionState)
                when {
                    response.operatorSessionId.isBlank() ->
                        Result.failure(Exception("Station 2 accepted the login but issued no session"))
                    // Accepting an already-closed session would strand the operator in a UI that
                    // rejects every action.
                    state == SessionState.Closed ->
                        Result.failure(Exception("Station 2 closed this session immediately"))
                    else -> {
                        val session = OperatorSession(
                            operatorSessionId = response.operatorSessionId,
                            operatorId = response.operatorId.orEmpty(),
                            operatorName = response.displayName.orEmpty(),
                            role = response.role.orEmpty(),
                            sessionState = state,
                            // A bad timestamp must not fail an otherwise valid login — expiry is
                            // display-only, and Station 2 enforces it regardless.
                            sessionExpiresAtUtc = response.sessionExpiresAtUtc?.let {
                                try { Instant.parse(it) } catch (e: Exception) { null }
                            },
                            allowedActions = response.allowedActions,
                            allowedTabs = response.allowedTabs,
                        )
                        sessionHolder.set(session)
                        Result.success(session)
                    }
                }
            }
```

Add imports `com.mitas.ppnam.station2aa.domain.model.SessionState` and `java.time.Instant`.

- [ ] **Step 7: Run tests, then commit**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/SessionState.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt \
        app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/model/SessionStateTest.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt
git commit -m "feat(auth): carry v3 sessionState and sessionExpiresAtUtc through the session model"
```

---

### Task 2: Intercept session_required in the transport

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttSessionExpiryTest.kt`

**Interfaces:**
- Consumes: `parseOutcome`, `sessionHolder` (already a constructor dep of `MqttRepositoryImpl`).
- Produces: no new public API. `request()` clears the session holder when a response carries `errorCode: session_required`.

The transport already parses `errorCode` on every response and already holds `OperatorSessionHolder` — it stamps `operatorSessionId` onto every envelope. Clearing the session here covers **every request in the app, present and future**, in one place. The alternative is repeating the check in every use case, and sub-projects 3-5 add roughly a dozen more.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttSessionExpiryTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class MqttSessionExpiryTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        com.google.gson.JsonParser
            .parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    private suspend fun requestAndRespond(errorCode: String?) {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        val codeJson = errorCode?.let { "\"$it\"" } ?: "null"
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":false,"errorCode":$codeJson,"reason":"x"}""".toByteArray()
        )
        call.await()
    }

    @Test
    fun `a session_required rejection clears the local session`() = runTest {
        assertNotNull(sessionHolder.session.value)

        requestAndRespond("session_required")

        assertNull("session_required must clear the session", sessionHolder.session.value)
    }

    @Test
    fun `an unrelated rejection leaves the session intact`() = runTest {
        // Only session_required means the session is gone. A validation failure must not log the
        // operator out.
        requestAndRespond("validation_failed")

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejection with no error code leaves the session intact`() = runTest {
        requestAndRespond(null)

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `an accepted response leaves the session intact`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"${messageIdOf(0)}","accepted":true}""".toByteArray()
        )
        call.await()

        assertNotNull(sessionHolder.session.value)
    }
}
```

`TestBody` and `EmptyPayload` already exist in this package (from `MqttRequestCorrelationTest` and `RequestEnvelope.kt`).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttSessionExpiryTest"`
Expected: FAIL — `a session_required rejection clears the local session` fails; the session is still present.

- [ ] **Step 3: Implement**

In `MqttRepositoryImpl.parseOutcome`, in the branch that builds `MqttOutcome.Rejected`, clear the session when the code says the session is gone:

```kotlin
        } else {
            val code = envelope.errorCode?.let { ErrorCode(it) }
            // The transport stamps operatorSessionId onto every envelope, so it owns the fact that a
            // session is no longer valid. Handling this here covers every request in the app in one
            // place, instead of repeating the check in every use case.
            if (code == ErrorCode.SESSION_REQUIRED) {
                Log.w(TAG, "Station 2 rejected $expectedResponseType with session_required — clearing local session")
                sessionHolder.clear()
            }
            MqttOutcome.Rejected(
                body = body,
                errorCode = code,
                reason = envelope.reason,
                nextAction = nextAction,
            )
        }
```

- [ ] **Step 4: Run, then commit**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS.

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttSessionExpiryTest.kt
git commit -m "feat(mqtt): clear the local session when Station 2 answers session_required"
```

---

### Task 3: Return to login when the session is lost

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/ui/session/SessionWatcher.kt`

**Interfaces:**
- Consumes: `OperatorSessionHolder.session` (Task 1), transport clearing on `session_required` (Task 2).
- Produces: `@Composable fun SessionWatcher(navController: NavHostController)`.

Task 2 clears the session; something must act on it. Making this a global navigation event rather than a per-screen concern is deliberate: a `Closed` session means Station 2 rejects everything, so continuing to show a working-looking screen would be a lie.

- [ ] **Step 1: Write the watcher**

Create `app/src/main/java/com/ppnam/station2aa/ui/session/SessionWatcher.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SessionWatcherViewModel @Inject constructor(
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {
    val session: StateFlow<OperatorSession?> = sessionHolder.session
}

/**
 * Sends the operator back to login whenever the session disappears.
 *
 * The transport clears the session holder when Station 2 answers `session_required` — a Closed
 * session means every subsequent request would be rejected, so any screen still on display is
 * lying. This makes that a single global rule rather than something each screen must remember.
 */
@Composable
fun SessionWatcher(
    navController: NavHostController,
    viewModel: SessionWatcherViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()

    LaunchedEffect(session) {
        if (session != null) return@LaunchedEffect
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        if (current == NavRoutes.LOGIN) return@LaunchedEffect
        navController.navigate(NavRoutes.LOGIN) {
            // Nothing behind us is usable without a session.
            popUpTo(0)
        }
    }
}
```

If `lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`) is not a dependency, use `collectAsState()` from `androidx.compose.runtime` instead and report the substitution.

- [ ] **Step 2: Wire it into the nav graph**

In `AppNavGraph.kt`, call it once, immediately inside `AppNavGraph` and before `NavHost`:

```kotlin
@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    SessionWatcher(navController)
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
```

Add `import com.mitas.ppnam.station2aa.ui.session.SessionWatcher`.

The existing `onLogout` handler in `JobLookupScreen` already navigates to login explicitly; leave it — `AuthUseCase.logout()` clears the session too, so the watcher would fire anyway. Belt and braces on a terminal action is fine.

- [ ] **Step 3: Build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: both BUILD SUCCESSFUL.

There is no Compose test infrastructure in this repo, so `assembleDebug` is the correctness signal for the composable. Report this honestly rather than claiming test coverage.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/session/SessionWatcher.kt \
        app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(session): return to login when the session is lost"
```

---

### Task 4: Delete the role gate and the direct-cancel path

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: `MixingUseCase.cancelJob(collectionId, jobCardNumber, reason, managerUsername, managerPassword)`.
- Produces: `operatorCanCancelDirectly()` is **deleted**. `MixingViewModel.cancelJob` requires non-blank manager credentials.

**This is the security fix and the reason this sub-project exists.** Today:

```kotlin
fun operatorCanCancelDirectly(): Boolean =
    sessionHolder.session.value?.allowedActions?.contains("cancel_premix_direct") == true
```

Wrong three ways: it enforces on `allowedActions` (a display hint the contract forbids enforcing with); `cancel_premix_direct` is not a v3 action id (the real one is `ingredient_collection_cancel`); and action ids are evaluated against **the approver's** account, never the sender's session. `IngredientScanScreen:89` branches on it to offer a cancel that sends **no manager credentials at all**, which v3 rejects — the contract requires them *"even when the sender is themselves a Manager."*

- [ ] **Step 1: Write the failing tests**

Add to `MixingViewModelTest`:

```kotlin
    @Test
    fun `cancelJob refuses to send without manager credentials`() = runTest {
        // v3 has no direct-cancel path: manager credentials are required on every privileged
        // action, checked against the approver's account, even when the sender is a Manager.
        viewModel.cancelJob(managerUsername = "", managerPassword = "")
        advanceUntilIdle()

        verify(mockUseCase, never()).cancelJob(any(), any(), any(), any(), any())
    }

    @Test
    fun `cancelJob forwards the supplied manager credentials`() = runTest {
        whenever(mockUseCase.cancelJob(any(), any(), any(), eq("manager1"), eq("secret")))
            .thenReturn(Result.success(mock()))

        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(any(), any(), any(), eq("manager1"), eq("secret"))
    }
```

Adapt to the file's existing setup (`viewModel`, `mockUseCase`, its `lookupJob` stubbing pattern). If `lookupJob` must succeed first for `cancelJob` to have a `currentOrderNo`, stub it as the other tests do.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: FAIL — `cancelJob refuses to send without manager credentials` fails, because today's `cancelJob` sends regardless.

- [ ] **Step 3: Delete the gate**

In `MixingViewModel.kt`, delete `operatorCanCancelDirectly()` entirely (around line 248):

```kotlin
    fun operatorCanCancelDirectly(): Boolean =
        sessionHolder.session.value?.allowedActions?.contains("cancel_premix_direct") == true
```

In `cancelJob`, add a guard immediately after the existing re-entrancy guard:

```kotlin
    fun cancelJob(managerUsername: String = "", managerPassword: String = "") {
        if (_uiState.value is MixingUiState.Cancelling) return
        // v3 authorises a privileged action solely by the manager credentials carried in the
        // request, checked against the approver's account — never by the sender's session. There is
        // no direct-cancel path, even for a Manager on their own handheld.
        if (managerUsername.isBlank() || managerPassword.isBlank()) return
```

If `sessionHolder` becomes an unused constructor dependency of `MixingViewModel`, remove it and report. If it is still used elsewhere (e.g. exposing `session` to a screen), keep it.

- [ ] **Step 4: Remove the branch in the screen**

In `IngredientScanScreen.kt`, replace the cancel dialog's `confirmButton` (around lines 85-98) so it always routes to the approval dialog:

```kotlin
            confirmButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        // Every cancel needs manager credentials in v3 — there is no direct path.
                        showCancelDialog = false
                        showApprovalDialog = true
                    }
                ) { Text("Cancel Job", color = DangerRed) }
            },
```

Fix the now-false copy in the approval dialog (around line 137). Replace:

```kotlin
                        "Your role can't cancel a job card directly. Ask a manager or admin to enter their credentials to approve this cancellation.",
```

with:

```kotlin
                        "Cancelling a job card always needs a manager's approval. Ask a manager to enter their credentials — this is recorded against their name in the audit trail.",
```

The old copy implied the operator's *role* was the obstacle. It isn't: the contract requires approval from everyone, and names the approver in the audit trail precisely so the authorisation is explicit rather than implied by who happened to be holding the handheld.

- [ ] **Step 5: Verify no enforcement remains**

Run: `grep -rn "operatorCanCancelDirectly\|cancel_premix_direct\|allowedActions" app/src/main --include=*.kt`
Expected: `allowedActions` appears ONLY in `OperatorSessionHolder.kt` (the field, documented as a display hint) and `AuthUseCase.kt` (populating it). No call site branches on it. `operatorCanCancelDirectly` and `cancel_premix_direct` return nothing.

- [ ] **Step 6: Run, build, commit**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: both BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "fix(mixing): remove the contract-violating role gate on cancel

allowedActions is a display hint the contract forbids enforcing with,
cancel_premix_direct is not a v3 action id, and action ids are checked
against the approver's account rather than the sender's session. The
gate offered a direct-cancel path sending no manager credentials, which
v3 rejects even for a Manager."
```

---

### Task 5: A connection banner that tells the truth

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/ui/components/ConnectionStatus.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/components/ConnectionStatusTest.kt`
- Modify: every ViewModel and screen listed in Step 4.

**Interfaces:**
- Consumes: `MqttRepository.connectionState`, `.stationOnline`, `.clockSkewMillis` (all from sub-project 1).
- Produces: `ConnectionStatus` enum; `fun resolveConnectionStatus(connectionState, stationOnline, clockSkewMillis, skewThresholdMs): ConnectionStatus`.

Sub-project 1 exposed `stationOnline` and `clockSkewMillis` and left them without a consumer, because this sub-project owns the UI. Today's banner says **"Connected" when only the broker is reachable** — which can be true while Station 2 is down and every request times out, with no clue why.

The resolution logic goes in a **pure function**, separate from the composable, so it can be unit-tested — this repo has no Compose test infrastructure.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/ui/components/ConnectionStatusTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {

    private fun resolve(
        state: MqttConnectionState = MqttConnectionState.CONNECTED,
        stationOnline: Boolean = true,
        skew: Long? = 0L,
    ) = resolveConnectionStatus(state, stationOnline, skew, SKEW_THRESHOLD_MS)

    @Test
    fun `all well reads as connected`() {
        assertEquals(ConnectionStatus.Connected, resolve())
    }

    @Test
    fun `disconnected outranks everything`() {
        assertEquals(
            ConnectionStatus.Offline,
            resolve(state = MqttConnectionState.DISCONNECTED, stationOnline = false, skew = 90_000L)
        )
    }

    @Test
    fun `reconnecting is reported distinctly from offline`() {
        assertEquals(ConnectionStatus.Reconnecting, resolve(state = MqttConnectionState.RECONNECTING))
    }

    @Test
    fun `broker up but station down is not connected`() {
        // The bug this fixes: "Connected" used to mean only that the broker was reachable, which
        // can be true while Station 2 is down and every request silently times out.
        assertEquals(ConnectionStatus.StationOffline, resolve(stationOnline = false))
    }

    @Test
    fun `station being down outranks a skewed clock`() {
        assertEquals(ConnectionStatus.StationOffline, resolve(stationOnline = false, skew = 90_000L))
    }

    @Test
    fun `a badly skewed clock is surfaced`() {
        assertEquals(ConnectionStatus.ClockSkewed, resolve(skew = 90_000L))
    }

    @Test
    fun `skew is surfaced in both directions`() {
        assertEquals(ConnectionStatus.ClockSkewed, resolve(skew = -90_000L))
    }

    @Test
    fun `skew within tolerance is not surfaced`() {
        assertEquals(ConnectionStatus.Connected, resolve(skew = SKEW_THRESHOLD_MS - 1))
    }

    @Test
    fun `an unmeasured clock is not reported as skewed`() {
        // null means no response has arrived yet to measure against — absence of evidence.
        assertEquals(ConnectionStatus.Connected, resolve(skew = null))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.components.ConnectionStatusTest"`
Expected: FAIL — `Unresolved reference: resolveConnectionStatus`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/ppnam/station2aa/ui/components/ConnectionStatus.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlin.math.abs

/** Beyond this, the device clock is a plausible cause of blanket message_expired rejections. */
const val SKEW_THRESHOLD_MS = 30_000L

enum class ConnectionStatus { Offline, Reconnecting, StationOffline, ClockSkewed, Connected }

/**
 * Resolves what to tell the operator about connectivity, in precedence order.
 *
 * Broker connectivity alone is not "connected": the broker can be up while Station 2 is down, in
 * which case every request times out with no clue why. Station 2's retained presence topic is what
 * makes that visible.
 *
 * Clock skew ranks below station presence because a skew reading is only meaningful once we have
 * had a response to measure it from. It warns rather than blocks: a skewed clock fails every
 * request with message_expired, but the operator cannot fix the clock from here, and blocking would
 * strand them. A specific warning turns "everything is mysteriously broken" into something
 * actionable.
 */
fun resolveConnectionStatus(
    connectionState: MqttConnectionState,
    stationOnline: Boolean,
    clockSkewMillis: Long?,
    skewThresholdMs: Long = SKEW_THRESHOLD_MS,
): ConnectionStatus = when {
    connectionState == MqttConnectionState.DISCONNECTED -> ConnectionStatus.Offline
    connectionState == MqttConnectionState.RECONNECTING -> ConnectionStatus.Reconnecting
    !stationOnline -> ConnectionStatus.StationOffline
    clockSkewMillis != null && abs(clockSkewMillis) > skewThresholdMs -> ConnectionStatus.ClockSkewed
    else -> ConnectionStatus.Connected
}
```

- [ ] **Step 4: Consume it in AppScaffold**

In `AppScaffold.kt`, replace the `connectionState` parameter with a `status: ConnectionStatus`, and replace the `when` block (around lines 37-42):

```kotlin
    val (dotColor, statusLabel) = when (status) {
        ConnectionStatus.Connected      -> SuccessGreen to "Connected"
        ConnectionStatus.Reconnecting   -> WarningOrange to "Reconnecting"
        ConnectionStatus.StationOffline -> WarningOrange to "Station 2 offline"
        ConnectionStatus.ClockSkewed    -> WarningOrange to "Clock out of sync"
        ConnectionStatus.Offline        -> DangerRed to "Offline"
    }
```

Remove the now-unused `MqttConnectionState` import if the compiler flags it.

Each ViewModel that feeds a scaffold must expose a combined status. Add to each of `HomeViewModel`, `LoginViewModel`, `MixingViewModel`, `RfidViewModel`, `SettingsViewModel` (adapting names to each file):

```kotlin
    val connectionStatus: StateFlow<ConnectionStatus> = combine(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ) { state, stationOnline, skew ->
        resolveConnectionStatus(state, stationOnline, skew)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)
```

Add imports: `kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.stateIn`, `kotlinx.coroutines.flow.SharingStarted`, `com.mitas.ppnam.station2aa.ui.components.ConnectionStatus`, `com.mitas.ppnam.station2aa.ui.components.resolveConnectionStatus`.

Then in each screen, replace `val connectionState by viewModel.connectionState.collectAsState()` with `val connectionStatus by viewModel.connectionStatus.collectAsState()` and pass `status = connectionStatus` to `AppScaffold`. The screens are: `HomeScreen`, `LoginScreen`, `JobLookupScreen`, `IngredientScanScreen`, `RfidRecoveryScreen`, `SettingsScreen`.

If a ViewModel still needs raw `connectionState` for other logic, keep that property too. Follow the compiler; report every file you touched.

Any test stubbing `mqttRepository.connectionState` on a mock now needs `stationOnline` and `clockSkewMillis` stubbed too, or `combine` will NPE on a null flow. Fix each and report.

- [ ] **Step 5: Run, build, commit**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: both BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/components/ConnectionStatus.kt \
        app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt \
        app/src/test/java/com/ppnam/station2aa/ui/components/ConnectionStatusTest.kt
# plus every ViewModel/screen/test you touched — stage them explicitly
git commit -m "feat(ui): surface Station 2 presence and clock skew in the connection banner

'Connected' previously meant only that the broker was reachable, which
can be true while Station 2 is down and every request times out."
```

---

### Task 6: Guard MixingViewModel against stray scans

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: `ScanEventBus.events`, `MixingUiState`.
- Produces: no new public API. The scan collector ignores events while a request is in flight or a dialog owns the screen.

This bug has now been reported **twice and deferred twice** — once in a prior project's ledger, once in sub-project 1's final review. Sub-project 1 fixed exactly this class on `RfidViewModel` by guarding the scan collector on `uiState`; `RfidViewModel.startListening()` is the reference implementation. Port the pattern.

The hazard: `startListeningForPalletScans()` collects continuously and acts on every RFID read. A stray read while a bag-entry dialog, manager-approval dialog, or recovery prompt is open can clobber in-flight state or dismiss the dialog.

- [ ] **Step 1: Read the reference implementation**

Read `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt`'s `startListening()` — it guards with `when (_uiState.value) { is Loading, is Recovering -> return@collect; else -> ... }` and carries a comment explaining the race.

- [ ] **Step 2: Identify the states that must ignore scans**

Read `MixingUiState`'s members. A scan must be ignored whenever the screen is mid-request or a dialog owns the interaction — e.g. `Loading`, `Cancelling`, `IngredientExceptionApproval`, `WaitingForSupervisor`, and any bag-entry/recovery state. It must still be accepted in the normal scanning state (e.g. `OrderLoaded`).

**Enumerate the actual members and decide per state. Do not guess from these examples.** List your decision for each state in your report — this is the substance of the task.

- [ ] **Step 3: Write the failing tests**

Add to `MixingViewModelTest`, adapting to its existing scan-bus setup. Mirror `RfidViewModelTest`'s two guard tests:

1. A scan arriving while the ViewModel is in a dialog/in-flight state must NOT trigger a pallet scan — `verify(mockUseCase, never()).scanIngredient(...)` — and the existing state must survive intact.
2. A scan arriving in the normal scanning state MUST still be processed — proving the guard did not over-correct into dropping legitimate scans. An over-correction is worse than the original bug: the operator would think the reader is broken.

If `MixingViewModelTest`'s scan bus is a bare non-emitting mock, replace it with a real `MutableSharedFlow<ScanEvent>` you can emit into, as `RfidViewModelTest` does.

**Test 1 must fail against the unguarded code before you implement.** If it does not, say so rather than forcing it.

Beware: `yield()` does not advance `runTest`'s virtual clock — a prior task hit a deterministic livelock that way. Use `advanceUntilIdle()`/`runCurrent()`.

- [ ] **Step 4: Implement the guard**

Apply the same shape as `RfidViewModel`, with a comment explaining why:

```kotlin
                // A scan landing mid-request or over an open dialog would clobber in-flight state
                // or dismiss the dialog under the operator's hands. Ignore reads until the screen
                // settles; scanning the next pallet from the normal state is still fine.
```

- [ ] **Step 5: Run, build, commit**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: both BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "fix(mixing): ignore stray scans while a request or dialog owns the screen

Same bug class fixed on RfidViewModel in sub-project 1; reported twice
and deferred twice before now."
```

---

## Definition of Done

- [ ] `./gradlew testDebugUnitTest` passes; `./gradlew assembleDebug` succeeds.
- [ ] `grep -rn "operatorCanCancelDirectly\|cancel_premix_direct" app/src --include=*.kt` returns nothing.
- [ ] `allowedActions` and `role` appear only where they are *displayed* or *populated* — no branch anywhere depends on either.
- [ ] No code path sends a cancel without manager credentials.
- [ ] The banner reports Station 2 being offline distinctly from the broker being offline.
- [ ] A `session_required` rejection from any request returns the operator to login.

## Handoff to sub-project 3

- `MixingUseCase.approveManagerException` is still a `@Deprecated` stub that always fails, and `MixingViewModel.submitManagerApproval` still dead-ends on it. **Sub-project 3 must replace it** with v3's inline retry: resubmit the rejected scan carrying `managerUsername`/`managerPassword`/`auditReason` and a **fresh `messageId`** (reusing the old one is rejected as `message_id_reused` and does *not* perform the approval).
- The approving account must hold `ingredient_approve_override` (or `ingredient_approve_short_bag` for a waiver) — checked by Station 2 against the approver, never by us.
- Task 6's per-state scan-guard decisions will need revisiting as sub-project 3 adds states.

## Open questions for the Station 2 developer

Unchanged from sub-project 1, and question 2 now matters more — this sub-project's banner depends on it:

1. **What is the configured timestamp acceptance window?** Still bounds the retry budget (3 × 10s = 30s, unclamped, `requestTimeoutMs` operator-editable).
2. **Is `station_2` the literal, fixed device id in the presence topic?**
3. Are message-specific `errorCode` values expected beyond the 14 shared codes?
