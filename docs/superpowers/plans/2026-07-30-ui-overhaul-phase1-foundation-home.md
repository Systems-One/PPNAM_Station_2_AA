# UI Overhaul Phase 1: Home Screen Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reintroduce a Home screen (three static tiles: Job Cards, Mixing Board, Fix a Tag) as the
post-login landing screen, per `docs/superpowers/specs/2026-07-30-android-ui-ux-overhaul-design.md` §4.1
and §5.2.

**Architecture:** A new `ui/home` package with a `HomeViewModel` (session + connection status + logout,
copying `MixingViewModel`'s existing pattern exactly) and a `HomeScreen` composable (`AppScaffold` +
three tile cards). `AppNavGraph.kt` gets a new `NavRoutes.HOME` destination inserted between `LOGIN` and
the existing `MIXING`/`MIXING_BOARD`/`RFID_RECOVERY` destinations, which are otherwise untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt (`@HiltViewModel`), Navigation-Compose, Material3. Tests:
JUnit4 + `org.mockito.kotlin` + `kotlinx.coroutines.test` (`UnconfinedTestDispatcher`), matching
`LoginViewModelTest.kt`'s existing convention exactly — no Hilt test runner, ViewModel constructed
directly with mocked dependencies.

## Global Constraints

- No MQTT contract, domain model, use case, or ViewModel changes outside the new `HomeViewModel` — this
  phase is purely additive UI/navigation.
- Existing `MIXING`, `MIXING_BOARD`, `RFID_RECOVERY`, `SETTINGS` destinations and their screens are not
  modified in this phase (their own restyle is later phases per the design spec).
- Dark theme only; existing `Color.kt` palette already matches the design spec's §3.1 color table
  exactly — no theme file changes needed in this phase.
- Follow existing code conventions exactly: `@HiltViewModel` + constructor injection, `StateFlow` for
  UI state, `Channel<Unit>` + `receiveAsFlow()` for one-shot navigation/logout events, named parameters
  in Compose modifiers (e.g. `padding(horizontal = 16.dp)`), 4-space indentation.

## Scope note (found during investigation, not in the original spec)

The design spec's §3 (visual system) and §3.4 (shared `StatusCard` component) are **not** part of this
phase. Investigation found: (1) every color in the spec's §3.1 table already exists verbatim in
`Color.kt` — no theme changes needed; (2) a shared color-coded `StatusCard` component has no consumer
in this phase (Home's tiles are plain navigation actions, not status-driven — they don't get a `tone`).
Building `StatusCard` now with no caller would violate YAGNI. It moves to Phase 2 (Job Cards), which is
its first real consumer.

---

### Task 1: HomeViewModel

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `MqttRepository` (`com.mitas.ppnam.station2aa.domain.repository.MqttRepository`) —
  `connectionState: StateFlow<MqttConnectionState>`, `stationOnline: StateFlow<Boolean>`,
  `clockSkewMillis: StateFlow<Long?>`. `AuthUseCase` (`com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase`)
  — `suspend fun logout()`. `OperatorSessionHolder`
  (`com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder`) — `session: StateFlow<OperatorSession?>`.
  `connectionStatusFlow(...)` (`com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow`) — existing
  utility, exact signature and usage copied from `MixingViewModel.kt` lines 143–147.
- Produces: `HomeViewModel.session: StateFlow<OperatorSession?>`,
  `HomeViewModel.connectionStatus: StateFlow<ConnectionStatus>`, `HomeViewModel.logoutEvent: Flow<Unit>`,
  `HomeViewModel.logout(): Unit` — all consumed by `HomeScreen` in Task 2.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var sessionFlow: MutableStateFlow<OperatorSession?>
    private lateinit var viewModel: HomeViewModel

    private val sampleSession = OperatorSession(
        operatorSessionId = "sess-1",
        operatorId = "OP-1",
        operatorName = "Jane Smith",
        role = "Operator"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        sessionFlow = MutableStateFlow(sampleSession)

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockSessionHolder.session).thenReturn(sessionFlow)

        viewModel = HomeViewModel(mockMqttRepository, mockAuthUseCase, mockSessionHolder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `session reflects the current operator session`() = runTest {
        assertEquals(sampleSession, viewModel.session.value)

        sessionFlow.value = null
        assertNull(viewModel.session.value)
    }

    @Test
    fun `logout calls authUseCase and fires logoutEvent`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.home.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` class does not exist yet (compile error).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.home.HomeViewModelTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt
git commit -m "feat(home): add HomeViewModel with session, connection status, and logout"
```

---

### Task 2: HomeScreen composable

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `HomeViewModel` from Task 1 (`session`, `connectionStatus`, `logoutEvent`, `logout()`).
  `AppScaffold` (`com.mitas.ppnam.station2aa.ui.components.AppScaffold`) — signature confirmed from current
  source: `AppScaffold(title: String, status: ConnectionStatus, onBack: (() -> Unit)? = null,
  onRfidLookup: (() -> Unit)? = null, onSettings: (() -> Unit)? = null, operatorName: String? = null,
  operatorRole: String? = null, onLogout: (() -> Unit)? = null, loading: Boolean = false, content:
  @Composable (PaddingValues) -> Unit)`.
- Produces: `HomeScreen(onOpenJobCards: () -> Unit, onOpenMixingBoard: () -> Unit, onFixATag: () -> Unit,
  onSettings: () -> Unit, onLogout: () -> Unit, viewModel: HomeViewModel = hiltViewModel())` — these
  five callback parameter names and this signature are what Task 3's `AppNavGraph.kt` wiring calls.

Design decisions (from the spec, made explicit here since the spec describes intent, not exact params):
- Home does **not** pass `onRfidLookup` to `AppScaffold` — the top-bar RFID icon exists on other screens
  specifically so RFID recovery is reachable *without leaving the current job/mixing flow*. On Home,
  "Fix a Tag" is already a primary tile; a second top-bar shortcut to the same destination is redundant.
- Home does **not** pass `onBack` — it is the landing screen for the post-login flow, mirroring how
  `LoginScreen` itself has no back target today.
- No automated Compose UI test for this screen: it has no conditional logic beyond null-safe display of
  `session?.operatorName`/`role` (already covered by `HomeViewModelTest`'s session test) — a pure
  layout composable. Verify by running the app (Step 2).

- [ ] **Step 1: Write the implementation**

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun HomeScreen(
    onOpenJobCards: () -> Unit,
    onOpenMixingBoard: () -> Unit,
    onFixATag: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    AppScaffold(
        title = "Station 2",
        status = connectionStatus,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onSettings = onSettings,
        onLogout = viewModel::logout,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = session?.operatorName?.let { "Good morning, $it" } ?: "Good morning",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            HomeTile(
                title = "Job Cards",
                subtitle = "Start or resume a job",
                icon = Icons.Filled.Assignment,
                onClick = onOpenJobCards,
            )
            HomeTile(
                title = "Mixing Board",
                subtitle = "Check or run machines",
                icon = Icons.Filled.Science,
                onClick = onOpenMixingBoard,
            )
            HomeTile(
                title = "Fix a Tag",
                subtitle = "RFID recovery",
                icon = Icons.Filled.WifiTethering,
                onClick = onFixATag,
            )
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `HomeScreen` is not reachable from the nav graph yet (Task 3), so there is
no manual UI check possible until that task lands — this step only confirms the file compiles against
`HomeViewModel` and `AppScaffold`'s real signatures.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt
git commit -m "feat(home): add HomeScreen with three-tile navigation"
```

---

### Task 3: Wire Home into the navigation graph

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `HomeScreen` from Task 2 (exact signature above). Existing `NavRoutes.MIXING`,
  `NavRoutes.mixingAreas()`, `NavRoutes.RFID_RECOVERY`, `NavRoutes.SETTINGS`, `NavRoutes.LOGIN` —
  unchanged.
- Produces: `NavRoutes.HOME: String` constant, used only within `AppNavGraph.kt` in this phase.

Known, deliberately out-of-scope overlap (do not fix in this task): `JobLookupScreen` currently has its
own two-stage "close the app?" confirmation because it used to sit at the effective bottom of the
post-login back stack. With `HOME` now inserted before it, pressing system back from Job Cards will
correctly pop to Home instead of exiting — that's a behavior *improvement*, not a regression — but
`JobLookupScreen`'s own explicit exit-confirmation UI element (if it has one reachable by a button, not
just the back gesture) still exists and is now slightly redundant with Home's natural "back exits the
app" position. This is Phase 2's concern (it owns `JobLookupScreen`/"Job Cards"), not this task's.

- [ ] **Step 1: Add the `HOME` route constant**

In `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`, add one line inside the `NavRoutes`
object, before the `LOGIN` constant (alphabetical/logical grouping with the other top-level routes):

```kotlin
object NavRoutes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    // ...rest of the file unchanged
```

- [ ] **Step 2: Point Login at Home instead of Mixing, and add the Home destination**

In `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`:

Add this import alongside the existing `com.mitas.ppnam.station2aa.ui.mixing.*` imports:

```kotlin
import com.mitas.ppnam.station2aa.ui.home.HomeScreen
```

Replace:

```kotlin
        composable(NavRoutes.LOGIN) {
            // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
            val activity = LocalContext.current.findActivity()
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.MIXING) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Login is the start destination — there is no back stack to pop, so leaving
                // means finishing the Activity. Only reached via the explicit confirm dialog.
                onExitApp = { activity?.finish() },
            )
        }
        composable(NavRoutes.SETTINGS) {
```

with:

```kotlin
        composable(NavRoutes.LOGIN) {
            // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
            val activity = LocalContext.current.findActivity()
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Login is the start destination — there is no back stack to pop, so leaving
                // means finishing the Activity. Only reached via the explicit confirm dialog.
                onExitApp = { activity?.finish() },
            )
        }
        composable(NavRoutes.HOME) {
            HomeScreen(
                onOpenJobCards = { navController.navigate(NavRoutes.MIXING) },
                onOpenMixingBoard = { navController.navigate(NavRoutes.mixingAreas()) },
                onFixATag = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Navigation on logout is SessionWatcher's job alone — see the comment on
                // MixingAreaPickerScreen's onLogout further down in this graph.
                onLogout = {},
            )
        }
        composable(NavRoutes.SETTINGS) {
```

(The rest of `AppNavGraph.kt` — `SETTINGS`, the `MIXING` nested graph, the `MIXING_BOARD` nested graph,
`RFID_RECOVERY`, and `UpgradeRequiredGate()` — is unchanged.)

- [ ] **Step 3: Verify it builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification**

Run the app on a device/emulator (`./gradlew installDebug` or run from Android Studio) and walk through:

1. Log in → lands on the new Home screen (greeting, three tiles, connection status pill, settings gear).
2. Tap "Job Cards" → lands on the existing Job Lookup screen. Press back → returns to Home (not exit-app
   confirmation, not a crash).
3. From Home, tap "Mixing Board" → lands on the Mixing Area Picker. Press back → returns to Home.
4. From Home, tap "Fix a Tag" → lands on RFID Recovery. Press back → returns to Home.
5. From Home, tap the settings gear → lands on Settings. Press back → returns to Home.
6. From Home, log out (via the operator name/logout control in the top bar) → returns to Login.
7. From Home, with nothing left to pop (fresh login), press system back → app exits (expected — Home is
   now the effective root of the post-login flow, same behavior Job Lookup used to have alone).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(nav): insert Home as the post-login landing screen"
```

---

## Self-Review Notes

- **Spec coverage:** §4.1 (Home reintroduction, three fixed tiles) and §5.2 (Home screen: greeting,
  three tiles, gear, no adaptive content) are fully covered by Tasks 1–3. §3 (visual system) and §3.4
  (`StatusCard`) are explicitly deferred to Phase 2 with the reasoning stated in "Scope note" above —
  not a gap, a documented decision.
- **Placeholder scan:** no TBD/TODO; every step has real, complete code.
- **Type consistency:** `HomeScreen`'s five callback parameter names
  (`onOpenJobCards`/`onOpenMixingBoard`/`onFixATag`/`onSettings`/`onLogout`) match exactly between
  Task 2's definition and Task 3's call site. `HomeViewModel`'s constructor parameter order
  (`MqttRepository`, `AuthUseCase`, `OperatorSessionHolder`) matches between Task 1's class and its own
  test's instantiation.
