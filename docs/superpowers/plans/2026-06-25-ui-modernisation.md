# UI Modernisation — Station 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the boilerplate Material3 purple theme and completely unstyled screens with a cohesive dark-graphite + amber identity across all 10 screens of PPNAM Station 2.

**Architecture:** A new `AppScaffold` composable wraps every screen, providing a branded TopBar with amber connection-status dot, screen title, and optional back arrow. The colour system, typography, and all screen composables are rewritten; four ViewModels gain `connectionState` and `pendingCount` StateFlows to drive the status indicator. No UseCases, data layer, Room, MQTT, or navigation routes are changed.

**Tech Stack:** Jetpack Compose BOM 2024.06.00, Material3, Material Icons Extended (added in Task 1), Hilt DI, Kotlin Coroutines, minSdk 26

## Global Constraints

- Dark theme only — `dynamicColor = false`, fixed dark scheme always on, no light mode toggle
- All icons from `material-icons-extended` — no custom vector drawable assets
- Button height: 56dp minimum (glove-friendly tap targets)
- Card corner radius: 16dp throughout (ElevatedCard default)
- Screen edge padding: 16dp throughout
- Between-card gap: 12dp
- Connection status visible on every screen via `AppScaffold` TopBar
- No changes to business logic, UseCases, Room entities, MQTT layer, or nav routes
- Package: `com.mitas.ppnam.station2aa`
- All commits: `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

---

### Task 1: Theme Layer + Material Icons Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/theme/Color.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/theme/Theme.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/theme/Type.kt`

**Interfaces:**
- Consumes: nothing (foundation layer)
- Produces: colour constants `GraphiteBackground`, `GraphiteSurface`, `GraphiteSurfaceVariant`, `GraphiteBorder`, `TextPrimary`, `TextMuted`, `AmberPrimary`, `AmberDark`, `SuccessGreen`, `DangerRed`, `InfoBlue`, `IndigoAccent`; `PPNAMStation2AATheme(@Composable () -> Unit)` (no required params); `Typography` object

- [ ] **Step 1: Add `material-icons-extended` to `gradle/libs.versions.toml`**

In the `[libraries]` section (the BOM manages the version — no `version.ref` needed):

```toml
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
```

- [ ] **Step 2: Add the dependency to `app/build.gradle.kts`**

After `implementation(libs.androidx.material3)`:

```kotlin
implementation(libs.androidx.material.icons.extended)
```

- [ ] **Step 3: Verify Gradle sync**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL (library resolves from BOM)

- [ ] **Step 4: Rewrite `Color.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.ui.graphics.Color

val GraphiteBackground = Color(0xFF0D0D0D)
val GraphiteSurface = Color(0xFF1A1A1A)
val GraphiteSurfaceVariant = Color(0xFF242424)
val GraphiteBorder = Color(0xFF2E2E2E)

val TextPrimary = Color(0xFFF5F5F5)
val TextMuted = Color(0xFF8A8A8A)

val AmberPrimary = Color(0xFFF59E0B)
val AmberDark = Color(0xFFB45309)

val SuccessGreen = Color(0xFF10B981)
val DangerRed = Color(0xFFEF4444)
val InfoBlue = Color(0xFF3B82F6)
val IndigoAccent = Color(0xFF6366F1)
```

- [ ] **Step 5: Rewrite `Theme.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = AmberDark,
    secondary = SuccessGreen,
    onSecondary = TextPrimary,
    background = GraphiteBackground,
    onBackground = TextPrimary,
    surface = GraphiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteSurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = DangerRed,
    onError = TextPrimary,
    outline = GraphiteBorder
)

@Composable
fun PPNAMStation2AATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
```

- [ ] **Step 6: Rewrite `Type.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displaySmall  = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall  = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleLarge     = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge      = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)
```

- [ ] **Step 7: Run tests and build**

```
.\gradlew testDebugUnitTest assembleDebug
```
Expected: BUILD SUCCESSFUL, 17 tests pass. The app now shows a graphite background behind the existing unstyled screens — no crash.

- [ ] **Step 8: Commit**

```
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/ppnam/station2aa/ui/theme/Color.kt app/src/main/java/com/ppnam/station2aa/ui/theme/Theme.kt app/src/main/java/com/ppnam/station2aa/ui/theme/Type.kt
git commit -m "feat(theme): dark graphite + amber colour system, disable dynamic colour

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: Shared UI Components — AppScaffold & LabelValueRow

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/ui/components/LabelValueRow.kt`

**Interfaces:**
- Consumes: theme colours from Task 1; `MqttConnectionState` enum from `com.mitas.ppnam.station2aa.domain.repository`
- Produces:
  - `AppScaffold(title: String, connectionState: MqttConnectionState, pendingCount: Int, onBack: (() -> Unit)? = null, content: @Composable (PaddingValues) -> Unit)`
  - `LabelValueRow(label: String, value: String)`

- [ ] **Step 1: Create `AppScaffold.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val (dotColor, statusLabel) = when (connectionState) {
        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
        MqttConnectionState.RECONNECTING -> AmberPrimary to "Reconnecting"
        MqttConnectionState.DISCONNECTED ->
            DangerRed to if (pendingCount > 0) "Offline — $pendingCount queued" else "Offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(color = dotColor)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GraphiteSurface
                )
            )
        },
        containerColor = GraphiteBackground,
        content = content
    )
}
```

- [ ] **Step 2: Create `LabelValueRow.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun LabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(0.6f)
        )
    }
}
```

- [ ] **Step 3: Verify build**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. `ConnectionStatusBar.kt` is still present — both components coexist at this point.

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt app/src/main/java/com/ppnam/station2aa/ui/components/LabelValueRow.kt
git commit -m "feat(ui): add AppScaffold and LabelValueRow shared components

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3: ViewModel Connection State Flows

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/rajoo/RajooViewModel.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/dashboard/DashboardViewModel.kt`

**Interfaces:**
- Consumes: `MqttRepository.connectionState: StateFlow<MqttConnectionState>` (already in Hilt graph); `OfflineQueueRepository.pendingCount(): Flow<Int>` (already in Hilt graph)
- Produces: each ViewModel now additionally exposes:
  - `val connectionState: StateFlow<MqttConnectionState>`
  - `val pendingCount: StateFlow<Int>`

**Background:** `HomeViewModel` already uses this exact pattern — inject both repos, `connectionState = mqttRepository.connectionState`, `pendingCount = offlineQueueRepository.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)`. Copy that pattern into the four ViewModels below. No existing tests reference ViewModels, so none will break.

**Note on `RajooViewModel`:** `startListeningForScans` is updated to reset state to Idle before re-subscribing, so "Allocate Another" works correctly after a success or error.

- [ ] **Step 1: Rewrite `MixingViewModel.kt`**

Full file (all existing logic preserved, two new constructor params + two new StateFlows added):

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
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
    data class Error(val message: String) : MixingUiState()
}

object MixingNavDestination {
    const val MIXER_CODE = "mixer_code"
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

    private val _mixerCode = MutableStateFlow("")
    val mixerCode: StateFlow<String> = _mixerCode.asStateFlow()

    private val _isQueuedOffline = MutableStateFlow(false)
    val isQueuedOffline: StateFlow<Boolean> = _isQueuedOffline.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private var scanJob: Job? = null

    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order -> _uiState.value = MixingUiState.OrderLoaded(order) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(orderNo: String) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            launch {
                scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                    useCase.validateIngredient(orderNo, event.tagId)
                        .onSuccess { bomLine ->
                            val ingredient = ScannedIngredient(tagId = event.tagId, itemCode = bomLine.itemCode, qty = 1.0)
                            _scannedIngredients.update { it + ingredient }
                        }
                        .onFailure {
                            _uiState.value = MixingUiState.Error("Unknown ingredient: ${event.tagId}")
                        }
                }
            }
            launch {
                scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                    if (_mixerCode.value.isEmpty()) setMixerCode(event.value)
                }
            }
        }
    }

    fun startListeningForBarcode() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                if (_mixerCode.value.isEmpty()) setMixerCode(event.value)
            }
        }
    }

    fun setMixerCode(code: String) { _mixerCode.value = code }

    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _mixerCode.value, _scannedIngredients.value)
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

- [ ] **Step 2: Rewrite `RajooViewModel.kt`**

Full file — note `startListeningForScans` now resets state to Idle before subscribing:

```kotlin
package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.AllocationRecord
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RajooUiState {
    object Idle : RajooUiState()
    object Loading : RajooUiState()
    data class MachinesLoaded(val machines: List<String>) : RajooUiState()
    data class AllocationSuccess(val record: AllocationRecord) : RajooUiState()
    data class Error(val message: String) : RajooUiState()
}

@HiltViewModel
class RajooViewModel @Inject constructor(
    private val useCase: RajooUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RajooUiState>(RajooUiState.Idle)
    val uiState: StateFlow<RajooUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var scanJob: Job? = null

    fun loadMachines() {
        viewModelScope.launch {
            _uiState.value = RajooUiState.Loading
            useCase.getMachines()
                .onSuccess { machines -> _uiState.value = RajooUiState.MachinesLoaded(machines) }
                .onFailure { e -> _uiState.value = RajooUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(machineCode: String) {
        scanJob?.cancel()
        _uiState.value = RajooUiState.Idle
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                allocatePallet(machineCode, event.tagId)
            }
        }
    }

    fun allocatePallet(machineCode: String, tagId: String) {
        viewModelScope.launch {
            _uiState.value = RajooUiState.Loading
            useCase.allocatePallet(machineCode, tagId)
                .onSuccess { record -> _uiState.value = RajooUiState.AllocationSuccess(record) }
                .onFailure { e -> _uiState.value = RajooUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun navigateHome() {
        viewModelScope.launch { _navigationEvent.send("home") }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
```

- [ ] **Step 3: Rewrite `RfidViewModel.kt`**

Full file:

```kotlin
package com.mitas.ppnam.station2aa.ui.rfid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.Pallet
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.RfidUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RfidUiState {
    object Idle : RfidUiState()
    object Loading : RfidUiState()
    data class PalletFound(val pallet: Pallet) : RfidUiState()
    data class Error(val message: String) : RfidUiState()
}

@HiltViewModel
class RfidViewModel @Inject constructor(
    private val useCase: RfidUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidUiState>(RfidUiState.Idle)
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var scanJob: Job? = null

    fun startListening() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                lookupPallet(event.tagId)
            }
        }
    }

    private fun lookupPallet(tagId: String) {
        viewModelScope.launch {
            _uiState.value = RfidUiState.Loading
            useCase.lookupPallet(tagId)
                .onSuccess { pallet -> _uiState.value = RfidUiState.PalletFound(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun resetToIdle() {
        _uiState.value = RfidUiState.Idle
        startListening()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
```

- [ ] **Step 4: Rewrite `DashboardViewModel.kt`**

Full file:

```kotlin
package com.mitas.ppnam.station2aa.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.DashboardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val palletLocation: String = "",
    val palletTagInput: String = "",
    val preMixList: String = "",
    val exceptions: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dashboardUseCase: DashboardUseCase,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setPalletTagInput(tag: String) = _state.update { it.copy(palletTagInput = tag) }

    fun lookupPallet() {
        val tag = _state.value.palletTagInput
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            dashboardUseCase.fetchPalletLocation(tag)
                .onSuccess { json -> _state.update { it.copy(isLoading = false, palletLocation = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun loadPreMixList() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            dashboardUseCase.fetchPreMixList()
                .onSuccess { json -> _state.update { it.copy(isLoading = false, preMixList = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun loadExceptions() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            dashboardUseCase.fetchExceptions()
                .onSuccess { json -> _state.update { it.copy(isLoading = false, exceptions = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }
}
```

- [ ] **Step 5: Run tests and build**

```
.\gradlew testDebugUnitTest assembleDebug
```
Expected: BUILD SUCCESSFUL, all 17 tests pass. Hilt generates updated constructors automatically.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/main/java/com/ppnam/station2aa/ui/rajoo/RajooViewModel.kt app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt app/src/main/java/com/ppnam/station2aa/ui/dashboard/DashboardViewModel.kt
git commit -m "feat(viewmodel): expose connectionState and pendingCount on all workflow ViewModels

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4: HomeScreen Redesign

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/components/ConnectionStatusBar.kt`

**Interfaces:**
- Consumes: `AppScaffold` from Task 2; `HomeViewModel.connectionState`, `HomeViewModel.pendingCount` (existed before this plan); theme colours from Task 1; Material Icons: `Science`, `Factory`, `WifiTethering`, `BarChart`
- Produces: `HomeScreen(onNavigateMixing, onNavigateRajoo, onNavigateRfidRecovery, onNavigateDashboard)` — same external signature as before

- [ ] **Step 1: Rewrite `HomeScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun HomeScreen(
    onNavigateMixing: () -> Unit,
    onNavigateRajoo: () -> Unit,
    onNavigateRfidRecovery: () -> Unit,
    onNavigateDashboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    AppScaffold(
        title = "PPNAM Station 2",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "Mixing",
                    subtitle = "Pre-Mix Flow",
                    icon = Icons.Filled.Science,
                    tileColor = AmberPrimary,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateMixing
                )
                HomeTile(
                    title = "Rajoo",
                    subtitle = "Allocation",
                    icon = Icons.Filled.Factory,
                    tileColor = SuccessGreen,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRajoo
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "RFID Recovery",
                    subtitle = null,
                    icon = Icons.Filled.WifiTethering,
                    tileColor = InfoBlue,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRfidRecovery
                )
                HomeTile(
                    title = "Dashboard",
                    subtitle = null,
                    icon = Icons.Filled.BarChart,
                    tileColor = IndigoAccent,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateDashboard
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    tileColor: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(height),
        colors = CardDefaults.elevatedCardColors(containerColor = tileColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        if (subtitle != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
```

- [ ] **Step 2: Delete `ConnectionStatusBar.kt`**

```powershell
Remove-Item "app\src\main\java\com\ppnam\station2aa\ui\components\ConnectionStatusBar.kt"
```

- [ ] **Step 3: Verify build**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. `ConnectionStatusBar` is no longer imported anywhere once HomeScreen is updated.

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt
git rm app/src/main/java/com/ppnam/station2aa/ui/components/ConnectionStatusBar.kt
git commit -m "feat(home): 2-large/2-small tile grid, AppScaffold TopBar, remove ConnectionStatusBar

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5: Mixing Screens

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixerCodeScreen.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `AppScaffold`, `LabelValueRow`; `MixingViewModel` (all flows from Task 3); `BomLine.itemCode`, `.itemName`, `.requiredQty`; `ScannedIngredient.itemCode`, `.qty`; `MixingNavDestination.PREMIX_COMPLETE`; `MixingUiState` sealed class
- Produces updated signatures (all have `onBack: () -> Unit = {}`):
  - `JobLookupScreen(onJobFound: (String) -> Unit, onBack: () -> Unit = {})`
  - `IngredientScanScreen(orderNo: String, onProceedToMixerCode: () -> Unit, onBack: () -> Unit = {})`
  - `MixerCodeScreen(orderNo: String, onProceed: () -> Unit, onBack: () -> Unit = {})`
  - `PreMixCompleteScreen(orderNo: String, onCompleted: () -> Unit, onBack: () -> Unit = {})`

**Note on `PremixConfirmedScreen`:** The confirmation state is shown inline within `PreMixCompleteScreen` using a local `var showConfirmation` flag. When the navigation channel emits `PREMIX_COMPLETE`, `showConfirmation = true`; the composable switches to a private `PremixConfirmedContent` composable. `onCompleted()` is called only by the "Done" button. No new nav route is needed.

- [ ] **Step 1: Rewrite `JobLookupScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed

@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var orderInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is MixingUiState.OrderLoaded) {
            onJobFound((uiState as MixingUiState.OrderLoaded).order.docNo)
        }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            OutlinedTextField(
                value = orderInput,
                onValueChange = { orderInput = it },
                label = { Text("Production Order No.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookupJob(orderInput) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    focusedLabelColor = AmberPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.lookupJob(orderInput) },
                enabled = orderInput.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Look Up")
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(text = err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite `IngredientScanScreen.kt`**

Each BOM line is an `ElevatedCard` with a `LinearProgressIndicator`. When satisfied, `containerColor` becomes `SuccessGreen.copy(alpha = 0.12f)` and a `CheckCircle` icon appears. The progress lambda form `progress = { fraction }` avoids unnecessary recomposition.

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToMixerCode: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }

    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                is MixingUiState.Loading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AmberPrimary)
                    }
                }
                is MixingUiState.Error -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                        Text(state.message, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is MixingUiState.OrderLoaded -> {
                    val order = state.order
                    val satisfiedCount = order.lines.count { bomLine ->
                        scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text(
                                "$satisfiedCount of ${order.lines.size} satisfied",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(order.lines) { bomLine ->
                            val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                            val required = bomLine.requiredQty.toInt().coerceAtLeast(1)
                            val satisfied = scannedCount >= required
                            val fraction = (scannedCount.toFloat() / required.toFloat()).coerceIn(0f, 1f)
                            val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (satisfied) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Satisfied",
                                                tint = SuccessGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = "$scannedCount / $required",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (satisfied) SuccessGreen else AmberPrimary,
                                        trackColor = GraphiteBorder
                                    )
                                }
                            }
                        }
                    }
                }
                else -> Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onProceedToMixerCode,
                enabled = scannedIngredients.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Proceed to Mixer Code")
            }
        }
    }
}
```

- [ ] **Step 3: Rewrite `MixerCodeScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurfaceVariant
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun MixerCodeScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val mixerCode by viewModel.mixerCode.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListeningForBarcode() }

    AppScaffold(
        title = "Mixer Code",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurfaceVariant)
            ) {
                Text(
                    text = "Scan barcode or enter the mixer code manually",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = mixerCode,
                onValueChange = { viewModel.setMixerCode(it) },
                label = { Text("Mixer Code") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    focusedLabelColor = AmberPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onProceed,
                enabled = mixerCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Review & Complete")
            }
        }
    }
}
```

- [ ] **Step 4: Rewrite `PreMixCompleteScreen.kt`**

`PremixConfirmedContent` is a private composable rendered inline when `showConfirmation = true`. The `return` after calling it prevents the review content from rendering simultaneously.

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun PreMixCompleteScreen(
    orderNo: String,
    onCompleted: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val mixerCode by viewModel.mixerCode.collectAsState()
    val isQueuedOffline by viewModel.isQueuedOffline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.PREMIX_COMPLETE) showConfirmation = true
        }
    }

    if (showConfirmation) {
        PremixConfirmedContent(
            isQueuedOffline = isQueuedOffline,
            connectionState = connectionState,
            pendingCount = pendingCount,
            onDone = onCompleted
        )
        return
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Review Pre-Mix",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = {}, label = { Text("Order $orderNo") })
                SuggestionChip(onClick = {}, label = { Text("Mixer: $mixerCode") })
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(scannedIngredients) { ingredient ->
                        LabelValueRow(label = ingredient.itemCode, value = "Qty: ${ingredient.qty.toInt()}")
                        HorizontalDivider(color = GraphiteBorder)
                    }
                }
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.completePremix(orderNo) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Confirm & Complete")
            }
        }
    }
}

@Composable
private fun PremixConfirmedContent(
    isQueuedOffline: Boolean,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onDone: () -> Unit
) {
    AppScaffold(
        title = "Pre-Mix Complete",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isQueuedOffline) {
                Icon(Icons.Filled.Schedule, null, tint = AmberPrimary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text("Pre-mix queued", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Will send when online", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            } else {
                Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text("Pre-mix confirmed by WPF", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Order sent successfully", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Spacer(Modifier.height(40.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Done")
            }
        }
    }
}
```

- [ ] **Step 5: Update `AppNavGraph.kt` — mixing routes**

Replace only the four mixing composable blocks (lines for `JOB_LOOKUP`, `INGREDIENT_SCAN`, `MIXER_CODE`, `PREMIX_COMPLETE`). Leave all other blocks unchanged:

```kotlin
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
        onProceedToMixerCode = { navController.navigate(NavRoutes.mixerCode(orderNo)) },
        onBack = { navController.popBackStack() }
    )
}
composable(NavRoutes.MIXER_CODE) { backStack ->
    val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
    MixerCodeScreen(
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
```

- [ ] **Step 6: Verify build**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/MixerCodeScreen.kt app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(mixing): BOM progress bars, confirmation state, AppScaffold on all mixing screens

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 6: Rajoo Screens

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/rajoo/MachineSelectScreen.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/rajoo/PalletAllocScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `AppScaffold`, `LabelValueRow`; `RajooViewModel` (all flows from Task 3); `AllocationRecord.preMixId`, `.machineCode`, `.allocatedAt`; `RajooUiState` sealed class; `LazyVerticalGrid`, `GridCells` from `androidx.compose.foundation.lazy.grid`; `rememberInfiniteTransition` from `androidx.compose.animation.core`
- Produces:
  - `MachineSelectScreen(onMachineSelected: (String) -> Unit, onBack: () -> Unit = {})`
  - `PalletAllocScreen(machineCode: String, onDone: () -> Unit, onBack: () -> Unit = {})`

**Note:** When `RajooUiState.Error.message` starts with `"Offline"`, it is a queued allocation — render amber card. Otherwise render red error card.

- [ ] **Step 1: Rewrite `MachineSelectScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun MachineSelectScreen(
    onMachineSelected: (machineCode: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadMachines() }

    AppScaffold(
        title = "Select Machine",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                is RajooUiState.Loading -> {
                    CircularProgressIndicator(color = AmberPrimary, modifier = Modifier.align(Alignment.Center))
                }
                is RajooUiState.MachinesLoaded -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.machines) { machine ->
                            ElevatedCard(
                                onClick = { onMachineSelected(machine) },
                                modifier = Modifier.height(120.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Factory,
                                        contentDescription = null,
                                        tint = AmberPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = machine,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
                is RajooUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = DangerRed.copy(alpha = 0.12f)
                            )
                        ) {
                            Text(
                                text = state.message,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadMachines() }) { Text("Retry") }
                    }
                }
                else -> Unit
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite `PalletAllocScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.navigation.NavRoutes
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PalletAllocScreen(
    machineCode: String,
    onDone: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(machineCode) { viewModel.startListeningForScans(machineCode) }
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == NavRoutes.HOME) onDone()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

    AppScaffold(
        title = "Allocate — $machineCode",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = uiState) {
                    is RajooUiState.Idle -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = AmberPrimary,
                                    modifier = Modifier.size(48.dp).alpha(pulseAlpha)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("Scan RFID pallet tag", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            }
                        }
                    }
                    is RajooUiState.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AmberPrimary)
                            Spacer(Modifier.height(12.dp))
                            Text("Allocating…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    is RajooUiState.AllocationSuccess -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = SuccessGreen.copy(alpha = 0.12f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Allocated", style = MaterialTheme.typography.headlineSmall, color = SuccessGreen)
                                }
                                Spacer(Modifier.height(12.dp))
                                LabelValueRow("Pre-Mix ID", state.record.preMixId)
                                LabelValueRow("Machine", state.record.machineCode)
                                LabelValueRow("Time", formatter.format(state.record.allocatedAt))
                            }
                        }
                    }
                    is RajooUiState.Error -> {
                        val isQueued = state.message.startsWith("Offline")
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (isQueued) AmberPrimary.copy(alpha = 0.12f) else DangerRed.copy(alpha = 0.12f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                if (isQueued) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Schedule, null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Queued", style = MaterialTheme.typography.headlineSmall, color = AmberPrimary)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Allocation queued — will send when online", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                } else {
                                    Text(state.message, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }

            Spacer(Modifier.height(16.dp))
            when (uiState) {
                is RajooUiState.AllocationSuccess, is RajooUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.startListeningForScans(machineCode) },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Allocate Another") }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                else -> Unit
            }
        }
    }
}
```

- [ ] **Step 3: Update `AppNavGraph.kt` — Rajoo routes only**

Replace the `MACHINE_SELECT` and `PALLET_ALLOC` blocks:

```kotlin
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
```

- [ ] **Step 4: Verify build**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/rajoo/MachineSelectScreen.kt app/src/main/java/com/ppnam/station2aa/ui/rajoo/PalletAllocScreen.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(rajoo): machine 2-col grid, allocation state cards, pulse scan icon, AppScaffold

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 7: RFID Recovery Screen

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `AppScaffold`, `LabelValueRow`; `RfidViewModel.connectionState`, `.pendingCount`, `.uiState`; `Pallet.tagId`, `.batchNo`, `.itemCode`, `.location`; `RfidUiState` sealed class; `rememberInfiniteTransition`
- Produces: `RfidRecoveryScreen(onDone: () -> Unit, onBack: () -> Unit = {})`

- [ ] **Step 1: Rewrite `RfidRecoveryScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.rfid

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun RfidRecoveryScreen(
    onDone: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: RfidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListening() }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    AppScaffold(
        title = "RFID Recovery",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = uiState) {
                    is RfidUiState.Idle -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = AmberPrimary,
                                    modifier = Modifier.size(48.dp).alpha(pulseAlpha)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Scan an RFID tag to look up a pallet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                    is RfidUiState.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AmberPrimary)
                            Spacer(Modifier.height(12.dp))
                            Text("Looking up pallet…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    is RfidUiState.PalletFound -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = SuccessGreen.copy(alpha = 0.12f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Pallet Found", style = MaterialTheme.typography.headlineSmall, color = SuccessGreen)
                                }
                                Spacer(Modifier.height(12.dp))
                                LabelValueRow("Tag ID", state.pallet.tagId)
                                LabelValueRow("Batch No", state.pallet.batchNo)
                                LabelValueRow("Item Code", state.pallet.itemCode)
                                LabelValueRow("Location", state.pallet.location)
                            }
                        }
                    }
                    is RfidUiState.Error -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = DangerRed.copy(alpha = 0.12f))
                        ) {
                            Text(
                                text = state.message,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            when (uiState) {
                is RfidUiState.PalletFound, is RfidUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text(if (uiState is RfidUiState.Error) "Try Again" else "Scan Another")
                        }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                else -> Unit
            }
        }
    }
}
```

- [ ] **Step 2: Update `AppNavGraph.kt` — RFID route only**

Replace the `RFID_RECOVERY` block:

```kotlin
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
```

- [ ] **Step 3: Verify build**

```
.\gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(rfid): pulse scan icon, pallet found card with LabelValueRow, AppScaffold

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 8: Dashboard Screen

**Files:**
- Rewrite: `app/src/main/java/com/ppnam/station2aa/ui/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `AppScaffold`, `LabelValueRow`; `DashboardViewModel.connectionState`, `.pendingCount`, `.state`; `DashboardUiState` fields; `TabRowDefaults.SecondaryIndicator`, `tabIndicatorOffset` from `androidx.compose.material3`
- Produces: `DashboardScreen(onBack: () -> Unit = {})`

- [ ] **Step 1: Rewrite `DashboardScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pallet", "Pre-Mix", "Allocation", "Exceptions")

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> viewModel.loadPreMixList()
            3 -> viewModel.loadExceptions()
        }
    }

    AppScaffold(
        title = "Dashboard",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = GraphiteSurface,
                contentColor = AmberPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AmberPrimary,
                        height = 3.dp
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedTab == index) AmberPrimary else TextMuted
                            )
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTab) {
                    0 -> PalletTab(
                        tagInput = state.palletTagInput,
                        result = state.palletLocation,
                        isLoading = state.isLoading,
                        onTagChange = viewModel::setPalletTagInput,
                        onLookup = viewModel::lookupPallet
                    )
                    1 -> JsonTab(json = state.preMixList, isLoading = state.isLoading, emptyMessage = "No Pre-Mix data")
                    2 -> PlaceholderTab("No allocation history available")
                    3 -> JsonTab(json = state.exceptions, isLoading = state.isLoading, emptyMessage = "No exceptions", isError = true)
                }
                state.error?.let { err ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        containerColor = DangerRed
                    ) { Text(err, color = TextPrimary) }
                }
            }
        }
    }
}

@Composable
private fun PalletTab(
    tagInput: String,
    result: String,
    isLoading: Boolean,
    onTagChange: (String) -> Unit,
    onLookup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = tagInput,
            onValueChange = onTagChange,
            label = { Text("Tag ID") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmberPrimary,
                focusedLabelColor = AmberPrimary,
                cursorColor = AmberPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onLookup,
            enabled = tagInput.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Look Up")
        }
        if (result.isNotBlank()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LabelValueRow("Result", result)
                }
            }
        }
    }
}

@Composable
private fun JsonTab(json: String, isLoading: Boolean, emptyMessage: String, isError: Boolean = false) {
    when {
        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AmberPrimary)
        }
        json.isBlank() -> PlaceholderTab(emptyMessage)
        else -> ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isError) DangerRed.copy(alpha = 0.1f) else GraphiteSurface
            )
        ) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) DangerRed else TextPrimary,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun PlaceholderTab(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
}
```

- [ ] **Step 2: Update `AppNavGraph.kt` — Dashboard route only**

Replace the `DASHBOARD` block:

```kotlin
composable(NavRoutes.DASHBOARD) {
    DashboardScreen(onBack = { navController.popBackStack() })
}
```

- [ ] **Step 3: Run full test suite and build**

```
.\gradlew testDebugUnitTest assembleDebug
```
Expected: BUILD SUCCESSFUL, all 17 tests pass.

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/ppnam/station2aa/ui/dashboard/DashboardScreen.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "feat(dashboard): amber tab indicator, styled tabs, AppScaffold

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
