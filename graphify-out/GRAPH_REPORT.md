# Graph Report - PPNAM_Station_2_AA  (2026-08-26)

## Corpus Check
- 3153 files · ~10,296,197 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2676 nodes · 3653 edges · 328 communities (114 shown, 214 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 375 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `740814ed`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Room DAO Tests
- Offline Queue Repository & RFID Scan Bus
- Operator Session & App Entry
- Pre-Mix Hopper Domain Models
- MQTT Message Envelope & Repository Impl
- Mixing ViewModel State Machine
- Typed MQTT Result & Repository Contract
- Operator Login & Auth Use Case
- Job Resume & Lookup Flow
- Dashboard & RFID Recovery ViewModels
- Job Cancel & Exception Approval Tests
- MQTT Client Factory & Reconnection Tests
- Mixing Screen Flow & Navigation
- Rajoo Allocation ViewModel
- Job Card Lifecycle Planning Docs
- MQTT Topic Builder Tests
- Rajoo Use Case & Tests
- App Settings Defaults & Tests
- Final MQTT Bugfix Round
- Shared UI Scaffold & Screens
- MQTT Topic Construction
- Dashboard Use Case & Tests
- Shared Scan UI Components
- Login & Session Design Docs
- Production Order & BOM Line Model
- Settings ViewModel Tests
- Settings Feature Design Docs
- Mixing Use Case Core Actions
- Settings Screen UI
- Settings PIN State Machine
- Android App Architecture Design Docs
- Pre-Mix Hopper Design Docs
- MqttRepository
- MixingViewModel.kt
- BomLine
- MQTT Schema 3.0 — Auth & Session Design
- BOM Line Response & Lookup Tests
- Navigation Routes
- UI Modernisation Design Docs
- MQTT Reconnection Fix Docs
- Settings Persistence Repository
- App / Hilt Bootstrap
- Sequencing
- MQTT Client Factory Tests
- Gradle Wrapper Script
- Android Instrumented Test Boilerplate
- MQTT Repository Reconnect Contract
- Unit Test Boilerplate
- Repo Rules & Graphify Workflow
- App Gradle Build Config
- UI Color Theme
- UI Typography Theme
- Root Gradle Build Config
- Gradle Settings
- MQTT Contract Foundation & Operator Login Implementation Plan
- Global Constraints
- BOM Ingredient Progress Display — Design Spec
- Global Constraints
- Global Constraints
- HomeTile
- SessionStateTest
- IngredientScanOutcome
- External Repo Read-Only Except RFID_MQTT_CONTRACT.md
- Graphify Query-First Workflow for Codebase Questions
- AppModule (Hilt DI)
- DataWedgeReceiver
- HomeViewModel
- IngredientScanScreen
- JobLookupScreen
- MixerCodeScreen
- MixingUseCase
- MixingViewModel
- MqttRepository interface
- MqttRepositoryImpl
- MqttTopics
- OfflineQueueRepository
- PreMix / ScannedIngredient domain model
- PreMixCompleteScreen
- ProductionOrder / BomLine domain model
- ScanEventBus
- AppScaffold shared composable
- LabelValueRow shared composable
- AppSettings data class
- MqttClientFactory
- SettingsRepository (DataStore-backed)
- SettingsScreen
- SettingsViewModel
- HopperScanScreen
- HopperStatus / HopperAvailability
- IngredientValidationResult (Valid/Invalid)
- AuthUseCase
- OperatorSession data class
- OperatorSessionHolder
- MqttRepository.sendTyped / MqttTypedResult
- ActiveJobCardSummary / ActiveJobCardsListResponse
- MixingUseCase.cancelJob / PreMixCancelResultResponse
- CancelOutcome (Confirmed/Failed)
- handleTransportDisconnected
- isTransportConnected AtomicBoolean flag
- retryBounded generic retry helper
- DataWedge RFID/Barcode Scan Integration via ScanEventBus
- Layered MVVM + Clean Architecture Pattern
- MQTT Request/Response Correlation-ID Pattern
- Two-Layer Offline Queue Retry Strategy (connectivity callback + WorkManager fallback)
- Dark Graphite + Amber Design System
- Test & Apply MQTT Reconnect Flow
- Hopper Allocation Workflow (replaces Mixer Code capture)
- Supervisor-Gated Ingredient Exception Override
- Login Mandatory at Startup (LoginScreen is nav-graph start destination)
- LoginScreen
- LoginViewModel
- In-Memory-Only Operator Session (never persisted, fresh login every cold start)
- Parallel Typed sendTyped Transport (coexists with legacy kebab-case transport during migration)
- Active Job List Tap-to-Load (spec section B1)
- Cancel With Role-Gated Approval (cancel_premix_direct capability, backend re-verifies server-side as defense-in-depth) (spec section B3)
- Per-Line Allocation Status Surfaced from bom_loaded (spec section B2)
- Transport-Connected State Guard + Bounded Subscribe Retry Fix
- Superseded Decision: Backend Already Auto-Resumes Pre-Mix by jobCardNumber+operator+handheld
- RFID MQTT Contract (sibling repo, RFID_MQTT_CONTRACT.md)
- Global Constraints
- scram_proof
- Design
- LoginViewModelTest
- MQTT Schema 3.0 — Hopper Board & Machine Cycles Design
- SettingsRepository
- MQTT Schema 3.0 — Collection & Ingredients Design
- BomLine
- main
- Sequencing
- Request to Station 2: the timestamp acceptance window
- build_response
- common.py
- SimLogger
- IngredientScanResultTest
- Rejection
- MixingViewModel.kt
- AuthUseCase
- LoginViewModel
- HomeViewModel
- Architecture
- areaTone
- LoginViewModel
- Design
- jobcards.py
- MqttClockSkewTest
- LoginViewModelTest
- MqttRepository
- Station 2 Backend Simulator
- LoginViewModel.kt
- MqttVocabularyTest
- MqttResponseDeduplicationTest
- MixingBoardUseCase
- SettingsViewModel.kt
- __init__.py
- SettingsViewModelTest
- ScanEventBus
- MixingMessagesTest
- .onCreate
- MqttRepository
- SettingsRepository
- LoginViewModelTest
- .readyMix
- WireNullToleranceTest
- .request
- ConnectionStatus
- MqttClientFactoryTest
- MqttClientFactory
- ===== PHASE 2: post-collection workflow =====
- sniffer.py
- UI Overhaul Phase 2: Job Cards — Implementation Plan
- UpgradeGateViewModel
- analyze.py
- RfidViewModelTest
- .create
- MqttResponseDeduplicationTest
- make_pallets.py
- NavRoutes
- Replay
- PalletStateTest
- make_pallets.py
- MixingOverviewWireCaptureTest
- PalletStateTest
- MqttClientFactory
- ResponseEnvelopeTest
- formatElapsedSince
- HoverExitScrollCrashTest
- MqttClientFactoryTest
- analyze.py
- gradlew
- analyze.py
- __init__.py
- SessionState.kt
- ScanRepository.kt
- FINDINGS.md
- build.gradle.kts
- String
- String
- Mqtt5AsyncClient
- T
- Any
- ByteArray
- Class
- Int
- Job
- Long
- Mqtt5AsyncClient
- String
- T
- Unit
- String
- Any
- Gson
- String
- StateFlow
- String
- Unit
- SharedFlow
- List
- String
- Unit
- Any
- collect.py
- Boolean
- ByteArray
- IngredientMessages.kt
- LoginViewModel.kt
- RfidViewModel.kt
- String
- .request
- Flow
- MqttClientFactoryTest
- MqttClientFactory
- ScanRepository
- Boolean
- Boolean
- Any
- Boolean
- Class
- Long
- StateFlow
- String
- T
- Unit
- SharedFlow
- List
- String
- Unit
- Any
- List
- String
- Double
- Int
- List
- String
- Unit
- String
- Boolean
- Flow
- Long
- Modifier
- String
- Boolean
- StateFlow
- Flow
- Job
- StateFlow
- String
- Flow
- Job
- List
- Set
- StateFlow
- String
- Unit
- Job
- StateFlow
- String
- NavHostController
- StateFlow
- Boolean
- String
- Boolean
- StateFlow
- String
- Instant
- String
- String
- Any
- String
- Class
- List
- Set
- String
- Double
- Int
- Boolean
- Long
- Boolean
- List
- MutableStateFlow
- String
- Boolean
- String
- String
- Boolean
- String
- Unit
- Int
- String
- Flow
- StateFlow
- Unit
- Int
- String
- Boolean
- List
- Modifier
- Set
- String
- MutableStateFlow
- Double
- Int
- String

## God Nodes (most connected - your core abstractions)
1. `MixingViewModelTest` - 79 edges
2. `MixingViewModel` - 71 edges
3. `MixingUseCaseTest` - 50 edges
4. `MixingBoardViewModelTest` - 49 edges
5. `MqttRepositoryImpl` - 38 edges
6. `PPNAM Station 2 — Live Test Findings Log` - 33 edges
7. `World` - 28 edges
8. `MixingBoardUseCase` - 27 edges
9. `MixingBoardUseCaseTest` - 27 edges
10. `MixingBoardViewModel` - 25 edges

## Surprising Connections (you probably didn't know these)
- `AppNavGraph()` --calls--> `IngredientScanScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
- `AppNavGraph()` --calls--> `rememberReducedMotion()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/theme/Motion.kt
- `AppNavGraph()` --calls--> `RfidRecoveryScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt
- `AppNavGraph()` --calls--> `SessionWatcher()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/session/SessionWatcher.kt
- `AppNavGraph()` --calls--> `SettingsScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt

## Import Cycles
- None detected.

## Communities (328 total, 214 thin omitted)

### Community 0 - "Room DAO Tests"
Cohesion: 0.11
Nodes (6): BomCacheDaoTest, AppDatabase, BomCacheDao, BomCacheEntity, AppModule, RoomDatabase

### Community 1 - "Offline Queue Repository & RFID Scan Bus"
Cohesion: 0.12
Nodes (16): check(), collect_all(), DirectHandheld, _FakeClient, _FakeMsg, _FakePublishResult, Handheld, load_collection() (+8 more)

### Community 2 - "Operator Session & App Entry"
Cohesion: 0.07
Nodes (49): open_area(), Mixing-board helpers: start / finish / force-close cycles., Find a node, scrolling the board if needed., start_cycle(), tap_scroll(), texts(), arm_line(), collect() (+41 more)

### Community 3 - "Pre-Mix Hopper Domain Models"
Cohesion: 0.06
Nodes (34): _arm(), do_line(), do_load(), do_status(), latest_collection(), latest_state(), Drive a job card's ingredient collection to ReadyForMixing against the BACKEND S, Most recent bom_loaded collectionId for this job card, from the wire. (+26 more)

### Community 4 - "MQTT Message Envelope & Repository Impl"
Cohesion: 0.19
Nodes (3): AppSettings, MqttClientFactoryTest, AppSettingsTest

### Community 5 - "Mixing ViewModel State Machine"
Cohesion: 0.08
Nodes (12): Double, Flow, Int, Job, List, StateFlow, String, Unit (+4 more)

### Community 6 - "Typed MQTT Result & Repository Contract"
Cohesion: 0.06
Nodes (18): ManagerAuthorization, authFailureMessage(), ScramExchange, BadgeLoginPayload, ManagerAction, OperatorContextResponse, ScramChallengeResponse, ScramProofPayload (+10 more)

### Community 7 - "Operator Login & Auth Use Case"
Cohesion: 0.05
Nodes (38): A10. Smaller items — Low, A11. Large parts of the Station 2 process have no UI at all — **plan-level**, A12. Dialog action buttons sit under the IME — Medium, A13. The two cancel dialogs contradict each other — Medium, A14. Raw ISO timestamps and a missing operator name — Low, A15. Active Jobs cannot distinguish multiple collections of the same job card — Medium, A1. No window insets anywhere — root cause of the recurring toolbar cropping — High, A2. `allowedActions` / `allowedTabs` are received then ignored — High (+30 more)

### Community 8 - "Job Resume & Lookup Flow"
Cohesion: 0.06
Nodes (32): A10. Logout is hidden behind the operator-name label — **Medium**, A11. The two cancel dialogs contradict each other — **Medium**, A12. Raw ISO timestamps and a missing operator name — **Low**, A13. Smaller items — **Low**, A14. Large parts of the Station 2 process have no UI at all — **Plan-level**, A1. No window insets anywhere — root cause of the recurring toolbar cropping — **High**, A2. Bag dialog opens with no line armed, then silently discards the entry — **High**, A3. `allowedActions` / `allowedTabs` are received then ignored — **High** (+24 more)

### Community 9 - "Dashboard & RFID Recovery ViewModels"
Cohesion: 0.06
Nodes (33): F-001 — SECURITY (High): operator passwords traverse MQTT in cleartext, F-002 — CONTRACT (Medium): response `timestampUtc` is earlier than the request, F-003 — CONTRACT (Low): inconsistent timestamp serialization, F-004 — CONTRACT (Medium): error text is in `reason`, `errorMessage` is absent, F-005 — ENV (Low): device clock 2.67 s behind broker/host, F-006 — UX (Medium): IME hides the password field and Log In button, F-007 — UX (Low): "Or scan your badge" sits above the username field, F-008 — PERF (Medium): 2.69 s for a credential rejection (+25 more)

### Community 10 - "Job Cancel & Exception Approval Tests"
Cohesion: 0.13
Nodes (34): _apply_finish(), area_overview(), assign_destinations(), _collection_selectable(), _cycle_payload(), _destination_payload(), _equipment_payload(), finish() (+26 more)

### Community 12 - "Mixing Screen Flow & Navigation"
Cohesion: 0.06
Nodes (31): 0.1 Preconditions, 0.2 Harness, 0.3 Regenerating `pallets.json`, 0.3b One-time setup, 0.4 Driving the UI, 0.5 Backends, 0.6 When something fails, §0 How to run (+23 more)

### Community 13 - "Rajoo Allocation ViewModel"
Cohesion: 0.06
Nodes (30): B10. Misleading pallet-recovery rejection — **Medium**, B11. `consumedApprovalId` returned when no approval was required — **Low**, B12. Logout returns an empty `sessionState` — **Low**, B13. Timestamp serialization differs between the two sides — **Low**, B1. `bagSize` / `expectedBags` contradict the backend's own arithmetic — **Critical**, B2. Force-closed cycles still yield a usable mix — **High**, B3. Passwords in cleartext + shared broker credentials — **High (security)**, B4. Latency — **High** (+22 more)

### Community 14 - "Job Card Lifecycle Planning Docs"
Cohesion: 0.20
Nodes (13): DialogFormColumn(), computeHighlightedMachines(), MachineTab, machineTabOf(), BoardContent(), CycleSheetDialog(), ForceCloseDialog(), MachineTabs() (+5 more)

### Community 15 - "MQTT Topic Builder Tests"
Cohesion: 0.07
Nodes (29): 1.1 Updated and new action strings, 1.2 New broadcast subscription — `station2/hopper/status`, 1.3 MqttRepository interface + MqttRepositoryImpl changes, 1. MQTT Layer, 2.1 Updated `BomLine`, 2.2 New `IngredientValidationResult`, 2.3 New `HopperStatus`, 2.4 Updated `ScannedIngredient` (+21 more)

### Community 16 - "Rajoo Use Case & Tests"
Cohesion: 0.08
Nodes (25): 1. Why, 2. The new workflow, as the app must model it, 3.1 Deletions, 3.2 The JC / production-order split, 3.3 `MixingOverviewPayload`, 3.4 `MachineCycleStartPayload`, 3.5 New DTOs, 3.6 Reshaped response DTOs (+17 more)

### Community 17 - "App Settings Defaults & Tests"
Cohesion: 0.12
Nodes (7): ApprovalState, IngredientScanPayload, ShortBagWaiverPayload, SourceType, Station3StockStatus, JobCardLoadPayload, MixingUseCase

### Community 18 - "Final MQTT Bugfix Round"
Cohesion: 0.08
Nodes (25): 1. Why, 2. Design Principles, 3.1 Color, 3.2 Typography, 3.3 Spacing & tap targets, 3.4 The card as the base component, 3.5 Dialogs, 3. Visual System (+17 more)

### Community 19 - "Shared UI Scaffold & Screens"
Cohesion: 0.08
Nodes (8): A snapshot revision for the active-collection queue (4.1 paging).          Der, Mint a single-use token scoped to one device, one action and one target., Validate and CONSUME a manager authorization token.          Every bound prope, Model a WPF/Core-saved cross-area mixer plan for one collection (4.1 §7)., Authenticate manager credentials and check the APPROVER's allowedActions., None means bulk material (no bag size)., Sum of remaining quantity across usable Holding pallets of this product., World

### Community 20 - "MQTT Topic Construction"
Cohesion: 0.08
Nodes (24): Definition of Done, Global Constraints, Handoff to sub-project 2, MQTT Schema 3.0 Protocol Foundation Implementation Plan, Open questions for the Station 2 developer, QoS must be verified by inspection, not by unit test, Sequencing Rationale, Task 10: Pallet lookup and holding recovery (+16 more)

### Community 21 - "Dashboard Use Case & Tests"
Cohesion: 0.08
Nodes (24): Access & Entry, Apply behaviour, Apply state display (below the button), Configuration zone, Data Layer, Data Model, `data/mqtt/MqttClientFactory.kt`, `data/settings/SettingsRepository.kt` (+16 more)

### Community 22 - "Shared Scan UI Components"
Cohesion: 0.18
Nodes (12): CollectionResumePayload, Accepted, ActiveCycle, AreaOverview, CollectedMaterial, EquipmentRole, Failed, JandiDrum (+4 more)

### Community 23 - "Login & Session Design Docs"
Cohesion: 0.08
Nodes (24): 1.1 Topics — `MqttTopics` rewritten, 1.2 Device identity — new `AppSettings.deviceId`, 1.3 Envelope — typed per-message classes, no generic wrapper, 1.4 `MqttRepository` — new typed send path, 1.5 Login is never offline-queued, 1. MQTT Layer, 2.1 New `OperatorSession`, 2.2 New `OperatorSessionHolder` (Hilt `@Singleton`, `data/session/`) (+16 more)

### Community 24 - "Production Order & BOM Line Model"
Cohesion: 0.17
Nodes (15): bom_loaded_response(), load(), open_sap_list(), active_job_cards_requested   -> active_job_cards_list open_sap_job_cards_reques, Pre-load a few collections at startup (bypassing job_card_load_requested     an, resume(), seed_demo_collections(), _snapshot_lines() (+7 more)

### Community 25 - "Settings ViewModel Tests"
Cohesion: 0.38
Nodes (3): MixingBoardScreenKtTest, MachineCard(), machineStatusTone()

### Community 26 - "Settings Feature Design Docs"
Cohesion: 0.12
Nodes (16): ActiveCycleDto, ActiveRunDto, CompletionMode, EquipmentDto, EquipmentStatus, JandiDrumDto, JandiRoute, LayerInputDto (+8 more)

### Community 27 - "Mixing Use Case Core Actions"
Cohesion: 0.08
Nodes (23): 10. Dependencies, 11. Open Items, 1. Purpose & Scope, 2.1 Pattern, 2.2 Package Structure, 2. Architecture, 3. Screens & Navigation, 4.1 Pattern (+15 more)

### Community 29 - "Settings PIN State Machine"
Cohesion: 0.09
Nodes (22): AI / Tech Products, Applying Peak-End to Mobile Apps, Crypto / Web3, Design Process for Client/Product Work, E-commerce / Food, Education / Learning, Emotional Design Principles, Emotional Feedback Loops (+14 more)

### Community 30 - "Android App Architecture Design Docs"
Cohesion: 0.09
Nodes (3): MixingBoardViewModelTest, Equipment, ReadyMix

### Community 31 - "Pre-Mix Hopper Design Docs"
Cohesion: 0.09
Nodes (22): 1. The transport owns the envelope, 2. Correlation, 3. Retry, 4. Result type, 5. Error and nextAction vocabulary, 6. Topics, 7. Presence, 8. Clock skew (+14 more)

### Community 32 - "MqttRepository"
Cohesion: 0.09
Nodes (22): ADDENDUM — On-device run + sim mixer-plan fix (2026-07-27, session 2), ADDENDUM — Session 3 (2026-07-27, ~11:30–): remaining sim-backed Gate 4 blocks, Block 1 — Full ingredient collection to ReadyForMixing (§4.4, D-block + D-INT), Block 2 — Rajoo dose sheet (E16–E20) via seeded COL_000004 (plan reserves RAJ-GM-01), Block 3 — Second JC for cross-mix cases (E11 / E12), Block 4 — Force-close + credential dialogs (E26 / E-FC), and a dialog-visibility correction, Block 5 — Sim fault-injection → §4.1b (A9–A20), B3–B5 (all driven on device via `PPNAM/_sim/control`), Environment note (end of session) (+14 more)

### Community 33 - "MixingViewModel.kt"
Cohesion: 0.09
Nodes (21): Contributing, Core Philosophy, Credits, Design Principles, Direct Download, Examples, Features, File Structure (+13 more)

### Community 34 - "BomLine"
Cohesion: 0.09
Nodes (21): Anti-Patterns to Avoid, Category Screens, Color System (60/30/10 Rule), Core Philosophy, Design Process, Implementation Notes, Mobile App UI/UX Design Skill, Order/Status Tracking (+13 more)

### Community 35 - "MQTT Schema 3.0 — Auth & Session Design"
Cohesion: 0.19
Nodes (3): MachineCycleStartPayload, MixingBoardUseCase, ReadyCollection

### Community 36 - "BOM Line Response & Lookup Tests"
Cohesion: 0.05
Nodes (18): HoldingRecoveryPayload, PalletLookupPayload, PalletLookupResultResponse, FailureKind, PalletInfo, PalletState, MqttConnectionState, MqttRepository (+10 more)

### Community 37 - "Navigation Routes"
Cohesion: 0.18
Nodes (22): collection_is_complete(), collection_progress(), collection_summary(), handheld_lines(), ingredients_payload(), line_payload(), r3(), Shared helpers for message-family handlers. (+14 more)

### Community 38 - "UI Modernisation Design Docs"
Cohesion: 0.10
Nodes (20): 10. Gesture design details (the "feel" checklist), 11. Frame-level smoothness, 12. Materials & depth — translucency conveys hierarchy, 13. Multimodal feedback — motion + sound + haptics, 14. Reduced motion & accessibility, 15. Typography — optical sizing, tracking, leading, 16. Design foundations — the eight principles, 17. Process (+12 more)

### Community 39 - "MQTT Reconnection Fix Docs"
Cohesion: 0.18
Nodes (14): AreaPicker, Board, BoardSelection, BoardSheet, Collection, CycleSheet, DoseRow, Error (+6 more)

### Community 40 - "Settings Persistence Repository"
Cohesion: 0.10
Nodes (19): §6 — Contract Doc Sync (already applied), App, App, B1 — Active Job List, B2 — Per-Line Allocation Status, B3 — Cancel With Role-Gated Approval, Backend, Backend (+11 more)

### Community 41 - "App / Hilt Bootstrap"
Cohesion: 0.18
Nodes (7): Application, BroadcastReceiver, DataWedgeReceiver, PpnamApplication, Configuration, HiltWorkerFactory, Intent

### Community 42 - "Sequencing"
Cohesion: 0.09
Nodes (4): ErrorCode, NextAction, MqttVocabularyTest, Schema41EnvelopeTest

### Community 43 - "MQTT Client Factory Tests"
Cohesion: 0.11
Nodes (18): File Map, Global Constraints, PPNAM Station 2 Android App — Implementation Plan, Self-Review Checklist, Task 10: MixingUseCase & Job Lookup Screen, Task 11: Remaining Mixing Screens (IngredientScan → MixerCode → PreMixComplete), Task 12: Rajoo Flow, Task 13: RFID Recovery (+10 more)

### Community 44 - "Gradle Wrapper Script"
Cohesion: 0.11
Nodes (18): 1. Color System, 2. Typography, 3. AppScaffold Component, 4. HomeScreen, 5. Mixing Workflow Screens, 6. Rajoo Workflow Screens, 7. RfidRecoveryScreen, 8. DashboardScreen (+10 more)

### Community 45 - "Android Instrumented Test Boilerplate"
Cohesion: 0.08
Nodes (15): Logging subsystem for the Station 2 backend simulator.  Four channels per run, a, Deep-copy obj with credential values replaced but their presence preserved., payload: dict, str, or bytes. Logged in full (redacted)., redact(), SimLogger, utc_now_iso(), main(), Station 2 backend simulator — answers the Android handheld's MQTT v3 contract t (+7 more)

### Community 47 - "Unit Test Boilerplate"
Cohesion: 0.05
Nodes (15): Boolean, Int, String, MqttRequestCorrelationTest, TestBody, String, MqttResponseDeduplicationTest, Int (+7 more)

### Community 48 - "Repo Rules & Graphify Workflow"
Cohesion: 0.18
Nodes (4): MachineCycleResultResponse, MixingBoardUseCaseTest, ActiveRun, LayerInput

### Community 49 - "App Gradle Build Config"
Cohesion: 0.17
Nodes (18): Cancelling, CancelOutcome, Confirmed, EnteringBagDetails, EnteringQuantityDetails, Error, Failed, Idle (+10 more)

### Community 50 - "UI Color Theme"
Cohesion: 0.11
Nodes (18): F-033 — SCOPE (Critical for planning): large parts of the Station 2 workflow are not implemented, F-034 — GOOD: unrecoverable pallet triggers a clear recovery offer, F-035 — BACKEND (Medium): misleading recovery rejection message, F-036 — CONTRACT (High): second confirmed case of `errorCode` carrying a GUID, F-037 — CONCURRENCY: multi-collection / multi-machine / multi-area works correctly, F-038 — BUSINESS LOGIC (High): force-closed cycles still yield a usable mix, F-039 — APP BUG (Medium): dialog action buttons sit under the IME, F-040 — UX (Low): raw ISO timestamps and a missing operator name (+10 more)

### Community 51 - "UI Typography Theme"
Cohesion: 0.11
Nodes (17): §4.1 Connection & transport, §4.2 Auth, session & roles, §4.3 Job lookup, §4.4 Ingredient collection, §4.5 Mixing board, §4.6 RFID pallet lookup, §4.8 Layout, §4.9 Settings (+9 more)

### Community 52 - "Root Gradle Build Config"
Cohesion: 0.34
Nodes (13): app_foreground(), dump(), ensure_app(), find(), goto_lookup(), lookup(), nodes(), Robust job-card sweep: locates UI elements via uiautomator instead of fixed taps (+5 more)

### Community 53 - "Gradle Settings"
Cohesion: 0.12
Nodes (16): Adding hoppers later is the same screen, unchanged, Design decisions, Destination choice: Hopper now, Extruder/Rajoo disabled, Finish names the cycle, not the machine, Force-close is privileged, and follows SP2's rule, Inherited from SP2's final review — must be handled here or in SP3, Messages, MQTT Schema 3.0 — Hopper Board & Machine Cycles Design (+8 more)

### Community 54 - "MQTT Contract Foundation & Operator Login Implementation Plan"
Cohesion: 0.12
Nodes (15): Codebase Index, Core Design Framework, Design Laws Applied, Design Principles Summary:, File Structure, Implementation Tech Stack, Key Insights:, Key Sections: (+7 more)

### Community 55 - "Global Constraints"
Cohesion: 0.06
Nodes (23): Any, Boolean, ByteArray, Class, Int, Job, Long, Mqtt5AsyncClient (+15 more)

### Community 56 - "BOM Ingredient Progress Display — Design Spec"
Cohesion: 0.23
Nodes (4): formatElapsedSince(), formatStationTimestamp(), TimeFormatTest, ZoneId

### Community 58 - "Global Constraints"
Cohesion: 0.12
Nodes (15): Deleted files, File Map, Global Constraints, Manual Test Checklist, Modified files, MQTT Pre-Mix & Hopper Workflow Implementation Plan, New files, Task 1: Domain Models (+7 more)

### Community 59 - "HomeTile"
Cohesion: 0.12
Nodes (15): Deferred / open items (carry into SP4b planning), File Structure, Global Constraints, MQTT Schema 4.0 Foundation (SP4a) Implementation Plan, Task 10: Upgrade signal — `client_upgrade_required` as a blocking state, Task 11: SP4a acceptance gate, Task 1: Branch + simulator envelope — schema 4.0 with the §12 compatibility boundary, Task 2: Simulator world state v4 — equipment topology, MixBatch/Cycle/Run (+7 more)

### Community 60 - "SessionStateTest"
Cohesion: 0.12
Nodes (15): 1. `lineNumber` is the line identity — not `materialCode`, 2. `null` and `0.0` are different facts on bag fields, Bag units: full-bag equivalents, Context, Inherited defects that land here, MQTT Schema 3.0 — Collection & Ingredients Design, Open questions for the Station 2 developer, Over-collection tolerance is Station 2's number, never ours (+7 more)

### Community 61 - "IngredientScanOutcome"
Cohesion: 0.14
Nodes (14): approve(), Common JSON envelope handling for contract v4.1: the contract's validation order, Run validation steps 1-4. Returns (req_dict, session_or_None).     Raises Rejec, Step 5 for privileged actions. Returns approver fields for the response., Raised by handlers to short-circuit into a rejected response., Raised when a stored response should be re-published as-is., Rejection, Replay (+6 more)

### Community 119 - "Global Constraints"
Cohesion: 0.09
Nodes (8): ActiveJobCardsPayload, Barcode, RfidTag, ScanEvent, ScanEventBus, ActiveJobsPage, LoginViewModelTest, Exception

### Community 121 - "Design"
Cohesion: 0.13
Nodes (14): Final check, Global Constraints, MQTT Contract Foundation & Operator Login Implementation Plan, Task 10: Operator identity + logout (`AppScaffold`, `HomeViewModel`, `HomeScreen`), Task 11: `SettingsScreen` — Device ID field, Task 1: `AppSettings.deviceId` + persistence, Task 2: `MqttTopics` — contract topic functions, Task 3: Contract envelope DTOs (+6 more)

### Community 122 - "LoginViewModelTest"
Cohesion: 0.13
Nodes (14): 1. New internal transport-state tracking, 2. `connect()` becomes idempotent against a live transport, 3. `connect()` gets the same timeout `reconnectWith()` already has, 4. Subscribe-only retry on the automatic-reconnect path, 5. `onDisconnected` sets `RECONNECTING`, not `DISCONNECTED`, 6. `scheduleReconnectRetry()` scope narrows, Approaches Considered, Context (+6 more)

### Community 123 - "MQTT Schema 3.0 — Hopper Board & Machine Cycles Design"
Cohesion: 0.16
Nodes (16): build_response(), Assemble the full response envelope around handler-provided fields.      4.1 a, login(), logout(), _operator_context_fields(), login_requested / reader_logout_requested -> operator_context, _escape_username(), _hmac() (+8 more)

### Community 125 - "MQTT Schema 3.0 — Collection & Ingredients Design"
Cohesion: 0.36
Nodes (8): ApplyState, Failure, Idle, Locked, PinState, Success, Testing, Unlocked

### Community 126 - "BomLine"
Cohesion: 0.24
Nodes (6): Error, Idle, LoggedIn, LoggingIn, LoginUiState, LoginViewModel

### Community 127 - "main"
Cohesion: 0.14
Nodes (13): File Map, Global Constraints, Self-Review Checklist, Settings Screen Implementation Plan, Task 1: AppSettings data class + DataStore dependency, Task 2: SettingsRepository, Task 3: MqttClientFactory, Task 4: Interface + DAO + Topics changes (+5 more)

### Community 128 - "Sequencing"
Cohesion: 0.14
Nodes (13): Definition of Done, Global Constraints, Handoff to sub-project 4, MQTT Schema 3.0 Collection & Ingredients Implementation Plan, Open questions for the Station 2 developer, Sequencing, Task 1: Unify the BOM line shape and add lineNumber, Task 2: Map the full bom_loaded shape (+5 more)

### Community 129 - "Request to Station 2: the timestamp acceptance window"
Cohesion: 0.14
Nodes (13): Deferred / open items (unchanged from the spec), File Structure, Global Constraints, MQTT Schema 4.0 — Five-Area Mixing UI (SP4b) Implementation Plan, Task 1: Branch + simulator cleanup — strip the vestigial nested `accepted` from `area_overview()`, Task 2: Wire DTOs and domain models, Task 3: MixingBoardUseCase, Task 4: MixingBoardViewModel — states, loading, refresh (+5 more)

### Community 130 - "build_response"
Cohesion: 0.14
Nodes (13): File Structure, Final verification, Global Constraints, JC-Driven Mixing Implementation Plan, Known gaps at completion, Task 1: Vocabulary cutover, Task 2: One mix per destination start, Task 3: Delete the plan and reservation surface (+5 more)

### Community 131 - "common.py"
Cohesion: 0.14
Nodes (13): Connection status: surfacing what sub-project 1 exposed, Context, Design decision: intercept `session_required` in the transport, Inherited defect: the MixingViewModel scan race, MQTT Schema 3.0 — Auth & Session Design, Navigation on session loss, Open questions for the Station 2 developer, Scope (+5 more)

### Community 132 - "SimLogger"
Cohesion: 0.14
Nodes (13): 1. Screens and navigation, 2. Source-first interaction, 3. Finish and force-close, 4. Results, errors, refresh, 5. Architecture (new vertical slice), 6. Cleanups folded in (SP4a final-review carry-ins), 7. Testing and acceptance, Decisions (user-adjudicated 2026-07-21 — do not re-litigate) (+5 more)

### Community 133 - "IngredientScanResultTest"
Cohesion: 0.29
Nodes (7): ConnectionStatus, connectionStatusFlow(), resolveConnectionStatus(), SessionWatcher(), SessionWatcherViewModel, HomeViewModel, ViewModel

### Community 134 - "Rejection"
Cohesion: 0.21
Nodes (11): Boolean, Modifier, Unit, StatusCard(), StatusTone, checklistTone(), hapticConstant(), IngredientScanScreen() (+3 more)

### Community 137 - "LoginViewModel"
Cohesion: 0.05
Nodes (3): String, MixingViewModelTest, BomLine

### Community 138 - "HomeViewModel"
Cohesion: 0.24
Nodes (8): AppScaffold(), greetingForHour(), HomeScreen(), HomeTile(), cardTone(), JobLookupScreen(), ImageVector, RowScope

### Community 140 - "areaTone"
Cohesion: 0.36
Nodes (3): MixingAreaPickerScreenKtTest, areaTone(), MixingAreaPickerScreen()

### Community 142 - "Design"
Cohesion: 0.47
Nodes (8): emit(), now_iso(), on_connect(), on_disconnect(), on_message(), Passive MQTT sniffer for PPNAM Station 2 live-backend testing.  Read-only: subsc, redact(), report_orphans()

### Community 143 - "jobcards.py"
Cohesion: 0.15
Nodes (12): Definition of Done, Global Constraints, Handoff to sub-project 3, MQTT Schema 3.0 Auth & Session Implementation Plan, Open questions for the Station 2 developer, Sequencing, Task 1: SessionState through the DTO and model, Task 2: Intercept session_required in the transport (+4 more)

### Community 144 - "MqttClockSkewTest"
Cohesion: 0.15
Nodes (12): 1. Scan interaction, 2. Live progress replaces the static snapshot, 3. Exception → manager approval (one uniform flow), 4. Pallet-recovery detour, 5. New `MixingUiState` states, 6. Removed, Context, Data verified from source (not assumed) (+4 more)

### Community 145 - "LoginViewModelTest"
Cohesion: 0.31
Nodes (9): ConfigSection(), DiagnosticRow(), SectionLabel(), SettingsScreen(), SettingsTextField(), SettingsToggleRow(), Color, KeyboardType (+1 more)

### Community 146 - "MqttRepository"
Cohesion: 0.15
Nodes (12): 1. Architecture and scope, 2. Simulator v4 rework, 3. App changes, 4. Error handling and testing, Backend survey facts this design leans on (verified 2026-07-20), Decisions (user-adjudicated 2026-07-20 — do not re-litigate), Design, MQTT Schema 4.0 — Foundation (SP4a) Design (+4 more)

### Community 148 - "LoginViewModel.kt"
Cohesion: 0.17
Nodes (11): Also outstanding, and now load-bearing, Context you may want: this already cost us a design decision, If Option A: why 120 seconds, Option A — implement it (our recommendation), Option B — remove it from the contract, Our honest assessment: it may not be worth much, Request to Station 2: the timestamp acceptance window, Summary (+3 more)

### Community 149 - "MqttVocabularyTest"
Cohesion: 0.17
Nodes (11): Architecture, Business rules of note, Decisions (user-confirmed), Error handling, Logging (simlog.py) — the second source of truth, MQTT surface, Out of scope, Self-test (selftest.py) (+3 more)

### Community 154 - "SettingsViewModelTest"
Cohesion: 0.18
Nodes (10): Global Constraints, Task 1: Theme Layer + Material Icons Dependency, Task 2: Shared UI Components — AppScaffold & LabelValueRow, Task 3: ViewModel Connection State Flows, Task 4: HomeScreen Redesign, Task 5: Mixing Screens, Task 6: Rajoo Screens, Task 7: RFID Recovery Screen (+2 more)

### Community 155 - "ScanEventBus"
Cohesion: 0.18
Nodes (10): Global Constraints, Job Card Lookup as Landing Screen — Implementation Plan, Task 1: `MixingViewModel` gains `pauseScanning()`, `session`, and `logout()`, Task 2: `AppScaffold` gains an RFID Pallet Lookup top-bar action, Task 3: `JobLookupScreen` becomes the landing screen (session, logout, settings, RFID button, saveable input), Task 4: `IngredientScanScreen` gets the RFID button and saveable local state, Task 5: `HopperScanScreen` gets the RFID button, Task 6: `PreMixCompleteScreen` gets the RFID button and saveable confirmation state (+2 more)

### Community 156 - "MixingMessagesTest"
Cohesion: 0.18
Nodes (10): Android app — data layer, Android app — domain layer, Android app — ViewModel/UI, Contract (`C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` only), Design, Error handling, Out of scope, Problem (+2 more)

### Community 157 - ".onCreate"
Cohesion: 0.29
Nodes (4): Bundle, MainActivity, PPNAMStation2AATheme(), ComponentActivity

### Community 158 - "MqttRepository"
Cohesion: 0.25
Nodes (7): Logs (per run: `logs/<UTC-timestamp>/`), Options, Seed world, Self-test, Setup, Station 2 Backend Simulator, What it deliberately does not do

### Community 161 - "LoginViewModelTest"
Cohesion: 0.20
Nodes (9): Global Constraints, Ingredient Scanning Migration Implementation Plan, Task 1: Ingredient-scan contract DTOs and BomLine bag-progress fields, Task 2: MixingUseCase.scanIngredient, Task 3: MixingUseCase.approveManagerException, Task 4: MixingUseCase.recoverHolding, Task 5: MixingViewModel — pallet-scan-driven ingredient flow, Task 6: IngredientScanScreen — bag-entry sheet and new dialogs (+1 more)

### Community 162 - ".readyMix"
Cohesion: 0.20
Nodes (9): 1. `BomLine` gains a `uom` field, 2. `MixingUseCase.lookupJob` maps `uomCode` through, 3. `IngredientScanScreen` per-line card, BOM Ingredient Progress Display — Design Spec, Context, Design, Formatting, Out of Scope (+1 more)

### Community 163 - "WireNullToleranceTest"
Cohesion: 0.18
Nodes (3): WireNullToleranceTest, Field, Type

### Community 164 - ".request"
Cohesion: 0.20
Nodes (9): App Redesign Phase 1: Job Card Lookup as Landing Screen — Design, Design, Job Lookup top-bar parity (operator name, Logout, Settings), Navigation graph (`app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`, `NavRoutes.kt`), Out of scope, Problem, Returning to the exact prior state, RFID Pallet Lookup as a top-bar action (+1 more)

### Community 165 - "ConnectionStatus"
Cohesion: 0.20
Nodes (9): Addendum — readyCollections area-scoping bugfix, on-device verified, Gate 1 — Build & static — **PASS**, Gate 2 — Unit tests — **PASS**, Gate 3 — Contract conformance (backend-sim) — **PASS**, Gate 4 — On-device — **NOT RUN**, Headline, PPNAM Station 2 — Android Test Run Report, Regression register (§5) — not re-verified (+1 more)

### Community 166 - "MqttClientFactoryTest"
Cohesion: 0.22
Nodes (8): Global Constraints, Job Card Lifecycle — Android Implementation Plan, Task 1: Per-line allocation status (§B2), Task 2: Active job list — DTOs, use case, view model (§B1), Task 3: `JobLookupScreen` — render active jobs, tap-to-load (§B1), Task 4: Cancel DTOs and use case (§B3), Task 5: `MixingViewModel` cancel state machine and role gate (§B3), Task 6: `IngredientScanScreen` — approval dialog and outcome handling (§B3)

### Community 167 - "MqttClientFactory"
Cohesion: 0.22
Nodes (8): Global Constraints, Manual Verification (required before this ships, per the spec's Verification Caveat), MQTT Reconnection Reliability Fix Implementation Plan, Task 1: Transport-connected flag guards `connect()` against a live client, Task 2: Generic bounded-retry helper, Task 3: Extract `handleTransportDisconnected`, set `RECONNECTING` not `DISCONNECTED`, Task 4: Bounded subscribe-retry replaces the buggy re-`connect()` path, Task 5: Timeout guard on `connect()`'s connect attempt

### Community 168 - "===== PHASE 2: post-collection workflow ====="
Cohesion: 0.18
Nodes (7): Modifier, String, ScanPromptCard(), Boolean, rememberReducedMotion(), LabelValueRow(), RfidRecoveryScreen()

### Community 169 - "sniffer.py"
Cohesion: 0.22
Nodes (8): Global Constraints, Scope note (found during investigation, not verbatim in the spec), Self-Review Notes, Task 1: `StatusCard` — add a `highlighted` parameter, Task 2: Restyle the Mixing Area Picker, Task 3: Restyle Mixing Board's list sections (collections, mixes, drum, cycles, runs), Task 4: Restyle `MachineCard` using `highlighted`, UI Overhaul Phase 4: Mixing Area Picker + Mixing Board — Implementation Plan

### Community 171 - "UI Overhaul Phase 2: Job Cards — Implementation Plan"
Cohesion: 0.47
Nodes (8): emit(), now_iso(), on_connect(), on_disconnect(), on_message(), Passive MQTT sniffer for PPNAM Station 2 live-backend testing.  Read-only: subsc, redact(), report_orphans()

### Community 174 - "analyze.py"
Cohesion: 0.22
Nodes (8): Global Constraints, Scope note (found during investigation, not verbatim in the spec), Self-Review Notes, Task 1: `AppScaffold` — add an `actions` slot, Task 2: Overflow menu for Cancel Job, sticky Start Mixing button, Task 3: "Scan this next" guided card + auto-arm, Task 4: Restyle the BOM checklist with `StatusCard`, UI Overhaul Phase 3: Ingredient Scan — Implementation Plan

### Community 175 - "RfidViewModelTest"
Cohesion: 0.24
Nodes (7): Activity, AppNavGraph(), findActivity(), NavHostController, UpgradeGateViewModel, UpgradeRequiredGate(), LoginScreen()

### Community 177 - ".create"
Cohesion: 0.22
Nodes (6): NullPruningTypeAdapterFactory, WireJson, JsonElement, TypeAdapter, TypeAdapterFactory, TypeToken

### Community 179 - "MqttResponseDeduplicationTest"
Cohesion: 0.52
Nodes (6): Accepted, IngredientScanOutcome, NeedsApprovalForWaiver, NeedsManagerApproval, NeedsRecovery, Rejected

### Community 182 - "Replay"
Cohesion: 0.06
Nodes (12): IngredientScanResultResponse, ActiveJobCardsInvalidatedResponse, ActiveJobCardsListResponse, ActiveJobCardSummary, BagSizeOptionResponse, BomLineResponse, BomLoadedResponse, CollectionSummaryResponse (+4 more)

### Community 184 - "PalletStateTest"
Cohesion: 0.25
Nodes (7): Global Constraints, Scope note (found during investigation, not in the original spec), Self-Review Notes, Task 1: HomeViewModel, Task 2: HomeScreen composable, Task 3: Wire Home into the navigation graph, UI Overhaul Phase 1: Home Screen Foundation — Implementation Plan

### Community 188 - "PalletStateTest"
Cohesion: 0.47
Nodes (5): anomalies(), extract(), main(), Regenerate pallets.json from the barcode generator's embedded pallet table.  The, Pull `var PALLETS = [ ... ];` out of the page and parse it as JSON.

### Community 191 - "ResponseEnvelopeTest"
Cohesion: 0.29
Nodes (6): Global Constraints, Scope note (found during investigation, not verbatim in the spec), Self-Review Notes, Task 1: Shared `StatusCard` component, Task 2: Restyle Job Cards' active-jobs list, UI Overhaul Phase 2: Job Cards — Implementation Plan

### Community 193 - "HoverExitScrollCrashTest"
Cohesion: 0.38
Nodes (3): HoverExitScrollCrashTest, MotionEvent, View

### Community 197 - "analyze.py"
Cohesion: 0.50
Nodes (3): External directory: C:\Dev\PPNAM-Station-2, graphify, Repo Rules

### Community 198 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 199 - "analyze.py"
Cohesion: 0.67
Nodes (3): build(), main(), Publish a fault-injection control frame to the backend sim (PPNAM/_sim/control).

## Knowledge Gaps
- **828 isolated node(s):** `EmptyPayload`, `WireJson`, `ScramPurpose`, `ScramChallengeResponse`, `ScramProofResponse` (+823 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **214 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MqttRepository` connect `BOM Line Response & Lookup Tests` to `Room DAO Tests`, `Typed MQTT Result & Repository Contract`, `LoginViewModel`, `LoginViewModel`, `Repo Rules & Graphify Workflow`, `Global Constraints`, `Replay`, `Global Constraints`, `Android App Architecture Design Docs`, `SettingsRepository`?**
  _High betweenness centrality (0.078) - this node is a cross-community bridge._
- **Why does `Rejection` connect `IngredientScanOutcome` to `Navigation Routes`, `Job Cancel & Exception Approval Tests`, `Android Instrumented Test Boilerplate`, `Shared UI Scaffold & Screens`, `Global Constraints`, `Production Order & BOM Line Model`, `MQTT Schema 3.0 — Hopper Board & Machine Cycles Design`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **Why does `MixingViewModelTest` connect `LoginViewModel` to `BOM Line Response & Lookup Tests`, `Mixing ViewModel State Machine`, `Typed MQTT Result & Repository Contract`, `Unit Test Boilerplate`, `App Settings Defaults & Tests`, `Global Constraints`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **What connects `EmptyPayload`, `WireJson`, `ScramPurpose` to the rest of the system?**
  _925 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Room DAO Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.11067193675889328 - nodes in this community are weakly interconnected._
- **Should `Offline Queue Repository & RFID Scan Bus` be split into smaller, more focused modules?**
  _Cohesion score 0.11742424242424243 - nodes in this community are weakly interconnected._
- **Should `Operator Session & App Entry` be split into smaller, more focused modules?**
  _Cohesion score 0.06558441558441558 - nodes in this community are weakly interconnected._