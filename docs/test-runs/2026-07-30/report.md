# PPNAM Station 2 — Android Test Run Report

**Date:** 2026-07-30
**Commit under test:** `29ad1c3` — chore: refresh the knowledge graph
**Branch:** `feat/jc-driven-mixing`
**Change under test:** UI Overhaul Phase 4 (StatusCard restyle of the mixing area picker and
mixing board — collection, mix, drum, cycle, run and equipment cards).

**Scope:** Gates 1–3 only, by explicit user choice. **Gate 4 (on-device) was not run** —
a C72 (`HC720DE260100322`) was attached and reachable via `adb`, so it was not a hardware
blocker; the user opted to skip the multi-hour on-device sweep (airplane-mode/clock/force-stop
manipulation, live+sim backend switching) for this pass.

**Backend used per block:**

| Gate / block | Backend |
|---|---|
| G1 Build & static | n/a (local Gradle) |
| G2 Unit tests | n/a (JVM) |
| G3 Contract conformance | **backend-sim** (`tools/backend-sim`, `--direct`, v4.1) |
| G4 On-device | **not run this pass** (see Scope) |

---

## Headline

- **FAIL(app): 0** across every gate run this pass.
- **Gates 1–3: all green** — build, lint (0 errors, 70 warnings), **410 unit tests** across
  **42 test classes**, **149/149 sim conformance checks**.
- Environment note: this machine had no `JAVA_HOME`/`ANDROID_HOME`/`adb` on `PATH`. Resolved by
  pointing at Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`) and
  the local SDK (`C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools`) for the session —
  no project files changed to make this work.
- **Gate 4 is the next step**, not skipped for cause — the device is present and connected.

---

## Gate 1 — Build & static — **PASS**

| ID | Test | Result | Evidence |
|---|---|---|---|
| G1.1 | `./gradlew assembleDebug` | **PASS** | BUILD SUCCESSFUL in 17s, exit 0 |
| G1.2 | `./gradlew lint` | **PASS** | BUILD SUCCESSFUL; **0 lint errors**, 70 warnings (no baseline diff performed — no prior lint-error count recorded to compare against) |
| G1.3 | No new compiler warnings | **PASS** | Kotlin compile clean; only 3 pre-existing deprecation warnings (`Icons.Filled.Assignment`, `LocalLifecycleOwner` ×2), consistent with prior runs (R-… none new) |

## Gate 2 — Unit tests — **PASS**

| ID | Test | Result | Evidence |
|---|---|---|---|
| G2.1 | `./gradlew test` — all green | **PASS** | **410 tests, 0 failures, 0 errors** across **42 test classes** (`testDebugUnitTest`; plan baseline 29 — suite has grown) |
| G2.2 | No silently-skipped tests | **PASS** | **skipped = 0** in the aggregated `testDebugUnitTest` XML results |

## Gate 3 — Contract conformance (backend-sim) — **PASS**

`python tools/backend-sim/selftest.py --direct`

| ID | Test | Result | Evidence |
|---|---|---|---|
| G3.1 | Simulator self-test | **PASS** | **149/149 checks passed** — "ALL 149 CHECKS PASSED — simulator is v4.1 contract-conformant" |
| G3.2 | Retired-topic guard | **PASS** | `hopper_overview_requested` (v3) → `client_upgrade_required` on `workflow_upgrade_required`; check "retired v3 topic → client_upgrade_required" passed |
| G3.3 | Envelope validation | **PASS** | Every request logs steps 1–5 (JSON, envelope, replay, schema/device/timestamp, session) before a response |
| G3.4 | Schema version | **PASS** | Non-`4.0`/`4.1` schema values explicitly rejected in-log (`got '2.0'…`, `got '3.0'…`), confirming the guard is live |
| G3.5 | Timestamp window | **PASS** | 1h-old timestamp → `message_expired`; check "1h-old timestamp → message_expired" passed |

---

## Gate 4 — On-device — **NOT RUN**

Not executed this pass — user explicitly scoped this run to Gates 1–3. Device
`HC720DE260100322` was connected and `adb`-reachable throughout, so this is a scheduling
choice, not a `BLOCKED` verdict. Re-run per §0 of `docs/TEST_PLAN.md` when the full on-device
sweep is wanted.

---

## Regression register (§5) — not re-verified

No on-device pass this run, so R-01 through R-22 were not re-checked. All Gate 1–3 evidence
that touches regression-relevant pure logic (G2.1: topic-segment validation, scan-shape XOR
guard, retry/correlation, clock-skew arithmetic, session expiry, highlight rule, `canShow`
fail-open, bag/bulk semantics, timestamp formatting) is green.

---

## Addendum — readyCollections area-scoping bugfix, on-device verified

Later the same day, a targeted follow-up (not a full Gate 4 run): the user reported the
mixing board's back-navigation and "used collections" behaviour as a sticking point.
Investigation found a real bug — `MixingBoardViewModel` never passed its current area to
`MixingBoardUseCase.fetchReadyCollections()`, and backend-sim's `area_overview()` never
scoped `readyCollections[]` by area either (unlike `equipment`/`activeCycles`/`readyMixes`/
`activeRuns`, which all do). Net effect: every mixing-board area showed every job card's
in-flight collection, including ones already reserved to a mixer in a different area —
which reads exactly like "collections don't behave right when I go back and switch areas."

**Fixed:**
- `MixingBoardViewModel.kt` — all three `fetchReadyCollections()` call sites now pass the
  board's current area (`openArea()`, and both branches of `applyOutcome()`).
- `tools/backend-sim/handlers/mixing.py` — `readyCollections[]` and a planned collection's
  `validMixerCodes` are now scoped to the requested `mixingArea`, matching every other list
  in the overview.
- `tools/backend-sim/selftest.py` — new regression check: plants a Main-only-planned
  collection and asserts it's absent from a JANDI-scoped overview. This is the check that
  would have caught the original bug.
- `tools/backend-sim/sim.py` + `tools/test-harness/simctl.py` — added a test-only
  `save_mix_plan` control-plane command (`simctl.py save_plan <collectionId> <mixerCodes>`),
  needed to seed a `MixingPlanned` collection from outside `--direct` selftest — normally
  only Station 2 (WPF) can save a plan, so there was no way to exercise this on-device before.
- `tools/test-harness/drive_area_scope_check.py` — small reusable driver: logs in and lands
  on the post-login screen, ready for area-hopping checks like this one.

**Re-verified:** unit suite 410/410 green (with mocks updated to assert the area argument),
backend-sim selftest **150/150** (up from 149).

**On-device (live, against backend-sim over `mqtt.sysone.co.za`, Station 2 confirmed offline
first):** rebuilt and reinstalled the debug APK, logged in as `operator1`, and captured wire
traffic while opening Main Mixing Room then JANDI. `docs/test-runs/2026-07-30/capture-area-scope/wire.jsonl`
shows the fix live:

| Request `mixingArea` | `readyCollections` |
|---|---|
| *(none — area picker's overview)* | COL_000002 (Main), COL_000004 (Rajoo), COL_000005 (Main) — all 3 |
| `MainMixingRoom` | COL_000002, COL_000005 — Rajoo's COL_000004 correctly excluded |
| `JandiBulkMixing` | **empty** — none of the 3 Main/Rajoo-planned collections leak in |

Screenshots: `docs/test-runs/2026-07-30/shots/area-scope-0{1..4}-*.png`. The JANDI board
screenshot (04) shows no "Collections ready to mix" section at all, matching the wire
evidence. Sim and sniffer were stopped cleanly afterward; retained `station_2/status`
confirmed back to `offline`.

**Verdict: FAIL(app) — 0. FIXED and verified.** Not part of the formal Gate 1–4 register
(§5 regression register) yet; worth adding a row there (e.g. "R-23 | readyCollections leaked
across mixing areas | scoped mixing_overview_requested, confirm empty on a foreign area") the
next time §5 is updated.

---

## Verdict

**Gates 1–3: PASS, 0 FAIL(app), 0 FAIL(backend), 0 BLOCKED.** Release gate criterion 1
("Gates 1–3 fully green") is met. Criteria 2–4 (on-device coverage) remain open pending a
Gate 4 run. The readyCollections area-scoping bug found and fixed later this run (see
addendum) is now closed, live-verified on-device.
