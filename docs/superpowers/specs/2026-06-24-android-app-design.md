# PPNAM Station 2 — Android App Design

**Date:** 2026-06-24
**Project:** `com.mitas.ppnam.station2aa`
**Platform:** Android (Kotlin, Jetpack Compose, minSdk 26, targetSdk 35)
**Context repo:** `C:\Dev\PPNAM-Station-2` (C# backend, Plans.md, AGENTS.md, test cases)

---

## 1. Purpose & Scope

The Android app is the **primary operator interface** for the PPNAM Station 2 production floor. Both Mixing-region operators and Rajoo-region operators use it as their end-to-end UI. The companion WPF Windows app serves supervisors and dashboards only.

The app covers four functional areas:

| Area | Operator action |
|---|---|
| Mixing workflow | Look up a production order, scan ingredients against the BOM, capture mixer code, complete pre-mix |
| Rajoo allocation | Scan a Rajoo machine, scan a completed pre-mix or full pallet, confirm allocation |
| RFID recovery | Recover a pallet movement the fixed reader missed by scanning the handheld |
| Dashboard | Search pallet locations, browse pre-mixes, view allocation history, review exceptions |

**Out of scope for this app:** Closing SAP production orders, updating SAP from door/fixed-reader events, supervisor workflows. Door movement is local-only and handled by the WPF machine, not this app.

---

## 2. Architecture

### 2.1 Pattern

Layered MVVM with a Clean Architecture domain layer. Dependency direction is strictly one-way:

```
UI (Compose screens)
  └── ViewModel (one per workflow)
        └── UseCase (domain logic, one per workflow)
              ├── MqttRepository  ──► HiveMQ MQTT client ──► MQTT broker ──► WPF
              ├── ScanRepository  ──► DataWedge BroadcastReceiver
              └── OfflineQueueRepository ──► Room database
```

Dependency injection via **Hilt**. All repository interfaces are defined in the domain layer; implementations live in the data layer.

### 2.2 Package Structure

```
com.mitas.ppnam.station2aa/
├── MainActivity.kt
├── navigation/
│   └── AppNavGraph.kt
├── ui/
│   ├── home/               ← mode selection
│   ├── rfid/               ← RFID recovery
│   ├── mixing/             ← job lookup → ingredient scan → mixer code → complete
│   ├── rajoo/              ← machine select → pallet/premix allocation → confirm
│   └── dashboard/          ← pallet location, premix search, history, exceptions
├── domain/
│   ├── model/              ← pure Kotlin data classes, no Android imports
│   └── usecase/            ← MixingUseCase, RajooUseCase, RfidUseCase, DashboardUseCase
├── data/
│   ├── mqtt/               ← MqttClient (HiveMQ), MqttRepository, MqttTopics
│   ├── rfid/               ← DataWedgeReceiver, ScanEventBus
│   └── local/              ← Room AppDatabase, OfflineQueueDao, OfflineQueueEntity
├── di/
│   └── AppModule.kt
└── ui/theme/               ← Color, Theme, Type (already scaffolded)
```

---

## 3. Screens & Navigation

Single `Activity` hosts a `NavHost`. All navigation is Compose-native (Navigation Component for Compose).

```
HomeScreen  (mode select: Mixing / Rajoo / RFID Recovery / Dashboard)
│
├── Mixing flow
│    ├── JobLookupScreen       scan or type production order number → BOM fetched from WPF
│    ├── IngredientScanScreen  scan pallet RFID tags, validate each against BOM lines in real-time
│    ├── MixerCodeScreen       enter or scan the mixer code (blocked from completion until provided)
│    └── PreMixCompleteScreen  review ingredient summary → confirm → WPF issues to SAP
│
├── Rajoo flow
│    ├── MachineSelectScreen   scan Rajoo machine code
│    └── PalletAllocScreen     scan completed pre-mix or full pallet → confirm allocation
│
├── RfidRecoveryScreen         scan missed pallet tag → recover local movement record via WPF
│
└── DashboardScreen  (tab layout)
     ├── PalletLocationTab
     ├── PreMixSearchTab
     ├── AllocationHistoryTab
     └── ExceptionsTab
```

A persistent **connection status bar** appears on every screen showing MQTT connectivity state (connected / reconnecting / offline with queue count).

---

## 4. MQTT Communication

### 4.1 Pattern

Request/response over MQTT using correlation IDs. Android publishes a request to a shared topic; WPF subscribes, processes, and responds on a device-specific topic. Android matches the response by `correlationId`.

| Direction | Topic | Notes |
|---|---|---|
| Android → WPF | `station2/request` | All devices publish here |
| WPF → Android | `station2/response/{deviceId}` | `deviceId` = Android `Settings.Secure.ANDROID_ID` |

### 4.2 Payload Shape

**Request:**
```json
{
  "correlationId": "<UUID>",
  "deviceId": "<ANDROID_ID>",
  "action": "<action-string>",
  "data": { }
}
```

**Response:**
```json
{
  "correlationId": "<UUID>",
  "success": true,
  "data": { },
  "error": null
}
```

### 4.3 Actions

| Action string | Trigger | Data payload |
|---|---|---|
| `lookup-job` | Operator scans/enters order number | `{ "orderNo": "510019068" }` |
| `validate-ingredient` | Operator scans a pallet tag | `{ "tagId": "...", "orderNo": "..." }` |
| `complete-premix` | Operator confirms completion | `{ "orderNo", "mixerCode", "ingredients": [...] }` |
| `allocate-rajoo` | Operator confirms allocation | `{ "machineCode", "palletOrPreMixId" }` |
| `recover-rfid-read` | Operator scans missed pallet | `{ "tagId", "location" }` |
| `fetch-pallet-location` | Dashboard query | `{ "tagId" }` |
| `fetch-premix-list` | Dashboard query | `{ "filter": { ... } }` |
| `fetch-exceptions` | Dashboard query | `{}` |

### 4.4 Timeout, Error Handling & Offline Behaviour

`MqttRepository.send()` returns `Flow<MqttResponse>`. It emits the response when the matching `correlationId` arrives, or emits `MqttResponse.Timeout` after a configurable TTL (default 10 s). The UseCase translates timeouts into user-facing error states on the ViewModel. The ViewModel never retries automatically — it surfaces the error so the operator can retry explicitly (single tap).

**Live vs queued actions — per action:**

| Action | Needs live WPF response? | Offline behaviour |
|---|---|---|
| `lookup-job` | Yes — fetches BOM | Blocked; operator sees "No connection — reconnecting" |
| `validate-ingredient` | No — validated locally against cached BOM | Works offline; result is optimistic |
| `complete-premix` | Yes — WPF issues to SAP | Message queued; screen shows "Queued — will send when online" |
| `allocate-rajoo` | Yes — WPF records allocation | Message queued; screen shows "Queued — will send when online" |
| `recover-rfid-read` | Yes — WPF records movement | Message queued; screen shows "Queued — will send when online" |
| `fetch-*` (dashboard) | Yes | Blocked; shows last cached result with a stale timestamp |

The BOM received from `lookup-job` is cached in Room for the duration of the job session, enabling offline ingredient validation. It is invalidated when the operator starts a new job or explicitly refreshes.

### 4.5 Client Library

**HiveMQ MQTT Client** (`com.hivemq:hivemq-mqtt-client-shaded`). Chosen for: active maintenance, Kotlin coroutine-friendly API, no deprecated methods unlike Eclipse Paho.

---

## 5. RFID Integration (DataWedge)

Zebra and Honeywell enterprise devices ship with **DataWedge**, which broadcasts scan results as Android intents. No vendor SDK is bundled in the APK.

### 5.1 Components

- **`DataWedgeReceiver`** — `BroadcastReceiver` registered in `AndroidManifest.xml`. Extracts the tag ID string from the intent and emits it onto `ScanEventBus`.
- **`ScanEventBus`** — `SharedFlow<ScanEvent>` (replay 0, extraBufferCapacity 16) held in a Hilt `@Singleton`. Only the currently active ViewModel collects from it; screens that don't need scanning do not collect.
- **DataWedge profile** — A `.db` profile file bundled in `assets/datawedge/` configures intent output action/category automatically. On first launch, the app exports and activates this profile via the DataWedge API intents so operators never need manual device configuration.

### 5.2 `ScanEvent`

```kotlin
sealed class ScanEvent {
    data class RfidTag(val tagId: String, val timestamp: Instant) : ScanEvent()
    data class Barcode(val value: String, val format: String, val timestamp: Instant) : ScanEvent()
}
```

Barcode scanning (for mixer codes and machine codes) is handled by the same bus — DataWedge supports both RFID and barcode output from the same profile.

---

## 6. Offline Queue

When `MqttRepository` cannot publish (broker unreachable), it writes the message to Room instead of dropping it. The operator sees a warning in the status bar and can continue scanning.

### 6.1 Room Schema

```
TABLE offline_queue
  id            TEXT PRIMARY KEY   -- correlationId
  action        TEXT NOT NULL
  payload       TEXT NOT NULL      -- JSON string
  created_at    INTEGER NOT NULL   -- epoch millis
  retry_count   INTEGER DEFAULT 0
  status        TEXT DEFAULT 'pending'  -- pending | sent | failed

TABLE bom_cache
  order_no      TEXT PRIMARY KEY
  bom_json      TEXT NOT NULL      -- serialised ProductionOrder JSON
  fetched_at    INTEGER NOT NULL   -- epoch millis; used to show stale timestamp in UI
```

The `bom_cache` table holds one row per active job card. It is written when `lookup-job` succeeds and read for all offline `validate-ingredient` calls.

### 6.2 Retry Strategy

Two-layer approach:

**Primary — connectivity callback:** `MqttRepository` registers a `ConnectivityManager.NetworkCallback`. When connectivity is restored, it immediately drains the `pending` queue and attempts to publish each row. This gives near-instant retry without polling.

**Fallback — WorkManager:** `OfflineQueueWorker : CoroutineWorker` is scheduled as a `PeriodicWorkRequest` with a 15-minute interval (OS minimum) and a `NetworkType.CONNECTED` constraint. It covers the case where the connectivity callback fires before the MQTT broker is ready (e.g., broker restarts after a network blip). On each run it performs the same drain-and-publish loop.

In both cases: on publish success → mark `sent`; on failure → increment `retry_count`; at `retry_count >= 10` → mark `failed`.

`failed` rows surface in the Exceptions tab so a supervisor can investigate or manually retry.

---

## 7. Domain Models

All models are pure Kotlin data classes with no Android framework imports. They live in `domain/model/`.

```kotlin
data class Pallet(
    val tagId: String,
    val batchNo: String,
    val itemCode: String,
    val location: String
)

data class ProductionOrder(
    val docNo: String,
    val itemCode: String,
    val plannedQty: Double,
    val lines: List<BomLine>
)

data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0
)

data class PreMix(
    val id: String,
    val jobCardNo: String,
    val mixerCode: String,
    val ingredients: List<ScannedIngredient>,
    val status: PreMixStatus,
    val createdAt: Instant
)

data class ScannedIngredient(
    val tagId: String,
    val itemCode: String,
    val qty: Double
)

data class AllocationRecord(
    val preMixId: String,
    val machineCode: String,
    val allocatedAt: Instant
)

enum class PreMixStatus { IN_PROGRESS, COMPLETE, ALLOCATED }
```

---

## 8. Key Business Rules (from PPNAM-Station-2 documentation)

These rules are enforced in the UseCase layer, not in the UI:

1. Pre-mix completion is blocked until a mixer code has been captured.
2. Additional pre-mixes for the same job card are allowed (spillage/wastage).
3. Both completed pre-mixes and full pallets may be allocated to a Rajoo machine.
4. RFID recovery via handheld records a local movement only; it does not trigger any SAP activity.
5. SAP production orders are **never closed** from this app.
6. A SAP rejection from the WPF layer results in an exception entry visible in the Exceptions tab.

---

## 9. Testing Strategy

| Layer | What to test | How |
|---|---|---|
| Domain | UseCase business rules (mixer code gate, duplicate pre-mix, allocation validation) | JUnit 5, mock repository interfaces |
| Data — local | `OfflineQueueDao` CRUD and status transitions | Room in-memory database test |
| Data — MQTT | `MqttRepository` topic/payload construction, timeout emission | Mock HiveMQ client |
| Data — RFID | `DataWedgeReceiver` → `ScanEventBus` emission | Robolectric broadcast injection |
| UI | Screen render and interaction (scan flow, error states, connection banner) | Compose `createComposeRule()` with fake ViewModels |
| Integration | Full scan → complete-premix flow | Espresso + DataWedge intent injection on device/emulator |

Test coverage target: all UseCase paths and all DAO operations. UI tests cover the happy path for each workflow screen and the connection-offline warning state.

---

## 10. Dependencies

```toml
# libs.versions.toml additions
hivemqClient        = "1.3.3"
hilt                = "2.51"
roomVersion         = "2.6.1"
workManager         = "2.9.1"
navigationCompose   = "2.7.7"
coroutines          = "1.8.1"
```

| Library | Purpose |
|---|---|
| `com.hivemq:hivemq-mqtt-client-shaded` | MQTT client |
| `com.google.dagger:hilt-android` | Dependency injection |
| `androidx.room:room-runtime` + `room-ktx` | Offline queue persistence |
| `androidx.work:work-runtime-ktx` | Offline queue retry worker |
| `androidx.navigation:navigation-compose` | Screen navigation |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Async / Flow |

---

## 11. Open Items

| # | Question | Impact |
|---|---|---|
| OI-1 | MQTT broker location — is Mosquitto running on the WPF machine, or a dedicated broker device on the LAN? | Affects `MqttTopics.BROKER_HOST` constant and network firewall rules |
| OI-2 | Exact DataWedge intent action/category strings for the target device model | Needed to complete the DataWedge profile `.db` file |
| OI-3 | Does the WPF app assign a `deviceId` to each handheld, or does the Android app self-identify? | Affects response topic subscription at first launch |
| OI-4 | Authentication — do operators log in, or is the app open-access on the device? | Affects whether a login screen and user context travel in MQTT payloads |
