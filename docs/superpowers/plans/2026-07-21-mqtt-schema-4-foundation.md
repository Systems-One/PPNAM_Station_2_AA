# MQTT Schema 4.0 Foundation (SP4a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the simulator and the Android app to contract v4.0 — simulator reworked for the five-area Mixing model, app-wide schema bump with dead-token cleanup, the three SP3 carry-over fixes, and a rewritten selftest — leaving the app contract-valid for capture and deliberately without mixing UI (that is SP4b).

**Architecture:** Simulator first (envelope schema boundary → v4 world state → capture handlers → new `handlers/mixing.py` → rewritten selftest), then the app (schema bump + retired-token sweep → enriched scan outcome → quantity-only scan path → waiver dialog state → upgrade signal). One branch, `mqtt-schema-4-foundation`. Nothing ships until SP4b lands mixing.

**Tech Stack:** Python 3.14 + paho-mqtt (simulator, `tools/backend-sim/`); Kotlin + Jetpack Compose + Hilt + Gson + HiveMQ client (app); JUnit4 + mockito-kotlin + kotlinx-coroutines-test (app tests).

**Spec:** `docs/superpowers/specs/2026-07-20-mqtt-schema-4-foundation-design.md`. Contract: `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v4.0 (READ-ONLY — never edit anything under `C:\Dev\PPNAM-Station-2` except that one file, and this plan never edits it).

## Global Constraints

- `schemaVersion` is `"4.0"` **everywhere** in the app, including capture flows (Decision 2). The simulator accepts `"3.0"` **only** for the §12 capture actions.
- Simulator replay is **contract-strict on all paths**: body-hash comparison → `message_id_reused`, including 4.0 topics (Decision 3 — deliberately stricter than the real backend, whose 4.0 path skips the hash check). The app must never rely on the server to catch messageId reuse; a corrected operation always mints a new `messageId`.
- `errorCode` may be **omitted** from real-backend JSON when null; parsers treat missing and null identically. The simulator keeps emitting explicit `"errorCode": null` (contract example shape) — the app must handle both.
- Retired v3 production requests answer on `PPNAM/{deviceId}/res/workflow_upgrade_required` with `accepted: false`, `errorCode: "client_upgrade_required"`, `nextAction: "upgrade_reader_for_mixing"`.
- Both SAP flags (`sapIssueQueued`, `sapProductionOrderChanged`) remain `false` in every machine result (§13 item 14).
- `nextAction` is display/navigation guidance, never authorization.
- A collection reaching `ReadyForMixing` (`nextAction: "start_mixing"`) shows an honest "Mixing arrives in the next update" placeholder — never a control that silently does nothing.
- Gradle on Windows: `.\gradlew.bat :app:testDebugUnitTest` / `.\gradlew.bat :app:assembleDebug`. Simulator selftest: `python selftest.py --direct` from `tools/backend-sim/`.
- After each code task, run `graphify update .` (AST-only, no API cost).
- Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

**Simulator (`tools/backend-sim/`):**
- `envelope.py` — modify: `SCHEMA_VERSION = "4.0"`, §12 compat set, per-request-type schema check.
- `state.py` — rewrite: `equipment` (area/role/status) replaces `machines`; `mix_batches` (`MIX_`), `cycles` (`CYC_`), `runs` (`RUN_`) replace `premixes`/`allocations`; `hopper_board()` deleted.
- `seed/seed.json` — rewrite `machines` → `equipment` (five-area topology, contract example codes); bump scan-stock pallet quantities so the selftest can complete four collections.
- `handlers/ingredients.py`, `handlers/jobcards.py` — modify: `ReadyForRouting`→`ReadyForMixing`, `choose_destination`→`start_mixing`, `hoppers[]` removed, `collectionStatus` added to scan results.
- `handlers/cycles.py`, `handlers/allocations.py`, `handlers/completion.py` — **delete** (v3 production mutations; `station2_work_complete_requested` has no v4 counterpart and is treated as retired).
- `handlers/mixing.py` — create: overview, start (mixer/Rajoo/drum/production dispatch), finish, force-close, `areaStatus` embedding.
- `handlers/__init__.py` — rewrite registry; export `RETIRED_REQUEST_TYPES`.
- `sim.py` — modify: retired-topic guard, credential redaction in wire logs, v4 banner.
- `selftest.py` — rewrite for v4.
- `README.md` — modify: v4 wording.

**App (`app/src/main/java/com/ppnam/station2aa/`):**
- `data/mqtt/MqttSchema.kt` — `VERSION = "4.0"`.
- `data/mqtt/MqttVocabulary.kt` — v4 error codes and next actions.
- `domain/model/HopperBoard.kt` — **delete**.
- `data/mqtt/dto/IngredientMessages.kt`, `data/mqtt/dto/JobCardMessages.kt` — drop `hoppers`, add `collectionStatus` to scan result, rename cancel-result `preMix*` fields.
- `domain/model/IngredientScanOutcome.kt` — enriched `Accepted`; `NeedsManagerApproval` gains `quantity`.
- `domain/usecase/MixingUseCase.kt` — quantity-vs-bag exclusive scan shapes; enriched outcome mapping.
- `domain/model/ProductionOrder.kt` — status doc update.
- `ui/mixing/MixingViewModel.kt` — `EnteringQuantityDetails` + `ShortBagWaiverEntry` states, scan dispatch by line type, upgrade flag exposure.
- `ui/mixing/IngredientScanScreen.kt` — quantity dialog, state-driven waiver dialog, placeholder copy, upgrade dialog.
- `navigation/AppNavGraph.kt` — rename `onProceedToHopperScan` → `onProceedToMixing`.
- `domain/repository/MqttRepository.kt`, `data/mqtt/MqttRepositoryImpl.kt` — `upgradeRequired: StateFlow<Boolean>`.
- Tests: `RequestEnvelopeTest`, `MqttTopicsTest`, `MqttRequestCorrelationTest`, `ResponseEnvelopeTest`, `MixingUseCaseTest`, `MixingViewModelTest`.

---

### Task 1: Branch + simulator envelope — schema 4.0 with the §12 compatibility boundary

**Files:**
- Modify: `tools/backend-sim/envelope.py:17` (constant) and `:110-113` (step 3 schema check)

**Interfaces:**
- Produces: `envelope.SCHEMA_VERSION == "4.0"`; `envelope.V3_COMPAT_ACTIONS` (frozenset of the ten §12 capture request types); `validate(world, log, topic_device, request_type, payload_bytes, is_login, ctx)` — signature unchanged, but the schema check now depends on `request_type`.
- Replay stays exactly as-is: body-hash on **all** paths already satisfies Decision 3 — do not touch step 2.

- [ ] **Step 1: Create the branch**

```bash
git checkout master
git checkout -b mqtt-schema-4-foundation
```

- [ ] **Step 2: Bump the schema constant and add the compat set**

In `tools/backend-sim/envelope.py` replace:

```python
SCHEMA_VERSION = "3.0"
```

with:

```python
SCHEMA_VERSION = "4.0"

# Contract §12: during cutover, schema 3.0 is temporarily accepted ONLY for the
# capture actions below. Every other request requires exactly 4.0.
V3_COMPAT_ACTIONS = frozenset({
    "login_requested", "reader_logout_requested",
    "pallet_lookup_requested", "holding_recovery_requested",
    "active_job_cards_requested", "open_sap_job_cards_requested",
    "job_card_load_requested", "collection_resume_requested",
    "ingredient_collection_cancel_requested", "ingredient_scan_requested",
})
```

- [ ] **Step 3: Make the step-3 schema check request-type-aware**

In `validate()` replace:

```python
    if req["schemaVersion"] != SCHEMA_VERSION:
        log.fail(f"step 3 schema: got '{req['schemaVersion']}', require '{SCHEMA_VERSION}'")
        raise Rejection("unsupported_schema", f"schemaVersion must be exactly '{SCHEMA_VERSION}'.")
```

with:

```python
    allowed = {SCHEMA_VERSION}
    if request_type in V3_COMPAT_ACTIONS:
        allowed.add("3.0")
    if req["schemaVersion"] not in allowed:
        log.fail(f"step 3 schema: got '{req['schemaVersion']}' on {request_type}, "
                 f"allowed {sorted(allowed)}")
        raise Rejection("unsupported_schema",
                        f"schemaVersion must be '{SCHEMA_VERSION}' "
                        f"(3.0 is accepted only for capture actions during cutover).")
```

Also update the module docstring's first line to say `contract v4.0` and the `MqttSchema`-style comment above the constant if present.

- [ ] **Step 4: Verify the boundary with an in-process probe**

From `tools/backend-sim/` run:

```bash
python -c "
from selftest import DirectHandheld
hh = DirectHandheld()
r,_ = hh.request('login_requested', {'username':'operator1','password':'pass'}, session='', schema='4.0')
assert r['accepted'] and r['schemaVersion'] == '4.0', r
hh.session = r['operatorSessionId']
r,_ = hh.request('pallet_lookup_requested', {'palletRfidTag':'NO_SUCH_TAG'}, schema='3.0')
assert r['accepted'], r
r,_ = hh.request('pallet_lookup_requested', {'palletRfidTag':'NO_SUCH_TAG'}, schema='2.0')
assert not r['accepted'] and r['errorCode'] == 'unsupported_schema', r
r,_ = hh.request('hopper_overview_requested', schema='3.0')
assert not r['accepted'] and r['errorCode'] == 'unsupported_schema', r
print('ENVELOPE BOUNDARY OK')
hh.close()
"
```

Expected: `ENVELOPE BOUNDARY OK` (the still-registered v3 `hopper_overview_requested` is not a compat action, so 3.0 is refused; it disappears entirely in Task 3).

- [ ] **Step 5: Commit**

```bash
git add tools/backend-sim/envelope.py
git commit -m "feat(sim): schema 4.0 envelope with §12 capture-only 3.0 compat window"
```

---

### Task 2: Simulator world state v4 — equipment topology, MixBatch/Cycle/Run

**Files:**
- Rewrite: `tools/backend-sim/state.py`
- Rewrite: `tools/backend-sim/seed/seed.json` (equipment section + pallet quantity bumps; operators/config/materials unchanged except noted)

**Interfaces:**
- Produces (used by Tasks 3–5): `World.equipment` dict keyed by machineCode with keys `machineCode, displayName, mixingArea, equipmentRole ("Mixer"|"Transfer"|"ProductionMachine"), productLayer, validDestinationMachineCodes, routeDescription, isEnabled, status ("Available"|"InUse"|"Disabled"), inactiveReason, currentCycleId, currentProductionOrderDocumentNumber, currentMixBatchIds`; `World.mix_batches`, `World.cycles`, `World.runs` dicts; `World.MIXING_AREAS` tuple; `World.next_id(kind)` for `"COL"|"MIX"|"CYC"|"RUN"`; `World.equipment_in_area(area)`.
- **Removed** (callers fixed in Task 3): `hopper_board()`, `machines`, `premixes`, `allocations`, `local_jobs`, counters `PMX/ROUTE/ALLOC`.
- Note: the sim is transiently broken between this task and Task 3 (capture handlers still reference `hopper_board`); that is expected on this branch — big-bang principle.

- [ ] **Step 1: Rewrite `state.py`**

Keep `utc_now/iso/parse_iso`, sessions, operators, approver, materials, pallet helpers, replay store, and snapshotting exactly as they are. Replace the machine/premix parts so the file becomes:

```python
"""In-memory world state for the Station 2 backend simulator (contract v4.0).

Everything is plain dicts so the whole world can be JSON-snapshotted after
each mutation. State lives only for the process lifetime — a restart is a
fresh, repeatable test world.
"""

import glob
import hashlib
import json
import os
from datetime import datetime, timedelta, timezone


def utc_now():
    return datetime.now(timezone.utc)


def iso(dt):
    if dt is None:
        return None
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(s):
    """Accept 'Z' or offset ISO 8601."""
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return datetime.fromisoformat(s)


class World:
    # The five fixed v4.0 mixing areas (contract §6). Server-authoritative.
    MIXING_AREAS = ("DolciBulkMixing", "MainMixingRoom", "JandiBulkMixing",
                    "MackieBulkMixing", "RajooMachineMixing")

    def __init__(self, seed_dir, log):
        self.log = log
        with open(os.path.join(seed_dir, "seed.json"), encoding="utf-8") as f:
            seed = json.load(f)
        self.config = seed["config"]
        self.operators = seed["operators"]
        self.materials = seed.get("materials", {})

        self.equipment = {}
        for e in seed["equipment"]:
            self.equipment[e["machineCode"]] = {
                "machineCode": e["machineCode"],
                "displayName": e["displayName"],
                "mixingArea": e["mixingArea"],
                "equipmentRole": e["equipmentRole"],
                "productLayer": e.get("productLayer"),
                "validDestinationMachineCodes": list(e.get("validDestinationMachineCodes", [])),
                "routeDescription": e.get("routeDescription", ""),
                "isEnabled": e.get("isEnabled", True),
                "status": e.get("status", "Available"),
                "inactiveReason": e.get("inactiveReason"),
                "currentCycleId": None,
                "currentProductionOrderDocumentNumber": None,
                "currentMixBatchIds": [],
            }
        # Main-room rule ("any of exactly 25 production extruders"): a mixer that
        # names no explicit destinations may feed every production machine in its
        # own area. Fixed routes (DOLCI pairs, JANDI, Mackie, Rajoo) stay in seed.
        for eq in self.equipment.values():
            if eq["equipmentRole"] == "Mixer" and not eq["validDestinationMachineCodes"]:
                eq["validDestinationMachineCodes"] = sorted(
                    code for code, other in self.equipment.items()
                    if other["mixingArea"] == eq["mixingArea"]
                    and other["equipmentRole"] == "ProductionMachine")

        self.pallets = {p["palletRfidTag"]: p for p in seed["pallets"]}

        # SAP production orders from sample dumps
        self.sap_orders = {}
        for path in sorted(glob.glob(os.path.join(seed_dir, "prod-*.json"))):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            for order in data.get("value", []):
                if "DocumentNumber" in order:
                    self.sap_orders[str(order["DocumentNumber"])] = order
        log.step(f"seed loaded: {len(self.operators)} operators, "
                 f"{len(self.equipment)} equipment across {len(self.MIXING_AREAS)} areas, "
                 f"{len(self.pallets)} pallets, {len(self.sap_orders)} SAP orders "
                 f"({', '.join(self.sap_orders)})")

        # dynamic state
        self.sessions = {}          # deviceId -> session dict
        self.presence = {}          # deviceId -> "online"/"offline"
        self.collections = {}       # COL_ -> collection dict
        self.mix_batches = {}       # MIX_ -> mix batch dict
        self.cycles = {}            # CYC_/RUN_ -> cycle dict (production cycles use their RUN_ id)
        self.runs = {}              # RUN_ -> production run dict
        self.replay = {}            # (device, reqType, messageId) -> {"hash", "topic", "response"}
        self.known_devices = set()  # auto-registered handheld ids
        self._counters = {"COL": 0, "MIX": 0, "CYC": 0, "RUN": 0, "RSP": 0}

    # ---- ids ------------------------------------------------------------
    def next_id(self, kind):
        self._counters[kind] += 1
        return f"{kind}_{self._counters[kind]:06d}"

    def next_response_id(self):
        self._counters["RSP"] += 1
        return f"S2-{self._counters['RSP']:06d}"

    # ---- mixing areas ----------------------------------------------------
    def equipment_in_area(self, area):
        return [e for e in self.equipment.values() if e["mixingArea"] == area]
```

Then keep the existing blocks **verbatim** from the current file: `find_operator`, `operator_by_id`, `create_session`, `get_session`, `presence_change`, `check_approver`, `full_bag_weight`, `available_quantity`, `pallet_usable`, `pallet_recoverable`, `body_hash`, `replay_lookup`, `replay_store`. Replace `to_dict` with:

```python
    # ---- snapshotting -----------------------------------------------------------
    def to_dict(self):
        return {
            "sessions": self.sessions,
            "presence": self.presence,
            "equipment": self.equipment,
            "pallets": self.pallets,
            "collections": self.collections,
            "mixBatches": self.mix_batches,
            "cycles": self.cycles,
            "runs": self.runs,
            "counters": self._counters,
            "knownDevices": sorted(self.known_devices),
        }
```

(`hopper_board()` is deleted outright.)

- [ ] **Step 2: Rewrite the seed's equipment topology**

In `tools/backend-sim/seed/seed.json`, delete the `"machines"` array and insert `"equipment"` (placeholder codes for DOLCI/Mackie/Rajoo-extruder per spec open question 1 — trivially re-coded when the Station 2 developer confirms real codes):

```json
  "equipment": [
    { "machineCode": "DOL-MIX-01", "displayName": "DOLCI Bulk Mixer 1", "mixingArea": "DolciBulkMixing", "equipmentRole": "Mixer", "validDestinationMachineCodes": ["DOL-EXT-01"], "routeDescription": "Pair 1 feeds DOLCI 1." },
    { "machineCode": "DOL-MIX-02", "displayName": "DOLCI Bulk Mixer 2", "mixingArea": "DolciBulkMixing", "equipmentRole": "Mixer", "validDestinationMachineCodes": ["DOL-EXT-02"], "routeDescription": "Pair 2 feeds DOLCI 2." },
    { "machineCode": "DOL-MIX-03", "displayName": "DOLCI Bulk Mixer 3", "mixingArea": "DolciBulkMixing", "equipmentRole": "Mixer", "validDestinationMachineCodes": ["DOL-EXT-11"], "routeDescription": "Pair 3 feeds DOLCI 11." },
    { "machineCode": "DOL-EXT-01", "displayName": "DOLCI 1", "mixingArea": "DolciBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "DOL-EXT-02", "displayName": "DOLCI 2", "mixingArea": "DolciBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "DOL-EXT-11", "displayName": "DOLCI 11", "mixingArea": "DolciBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "MXR-01", "displayName": "Main Mixer 1", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer", "routeDescription": "Any Main Mixing Room extruder." },
    { "machineCode": "MXR-02", "displayName": "Main Mixer 2", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer", "routeDescription": "Any Main Mixing Room extruder." },
    { "machineCode": "MXR-03", "displayName": "Main Mixer 3", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer", "routeDescription": "Any Main Mixing Room extruder." },
    { "machineCode": "MXR-04", "displayName": "Main Mixer 4", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer", "routeDescription": "Any Main Mixing Room extruder." },
    { "machineCode": "MXR-05", "displayName": "Main Mixer 5", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer", "routeDescription": "Any Main Mixing Room extruder." },
    { "machineCode": "EXT-01", "displayName": "Extruder 1", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-02", "displayName": "Extruder 2", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-03", "displayName": "Extruder 3", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-04", "displayName": "Extruder 4", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-05", "displayName": "Extruder 5", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-06", "displayName": "Extruder 6", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-07", "displayName": "Extruder 7", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-08", "displayName": "Extruder 8", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-09", "displayName": "Extruder 9", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-10", "displayName": "Extruder 10", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-11", "displayName": "Extruder 11", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-12", "displayName": "Extruder 12", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-13", "displayName": "Extruder 13", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-14", "displayName": "Extruder 14", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-15", "displayName": "Extruder 15", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-16", "displayName": "Extruder 16", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-17", "displayName": "Extruder 17", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-18", "displayName": "Extruder 18", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-19", "displayName": "Extruder 19", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-20", "displayName": "Extruder 20", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-21", "displayName": "Extruder 21", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-22", "displayName": "Extruder 22", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-23", "displayName": "Extruder 23", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-24", "displayName": "Extruder 24", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine" },
    { "machineCode": "EXT-25", "displayName": "Extruder 25", "mixingArea": "MainMixingRoom", "equipmentRole": "ProductionMachine", "isEnabled": false, "status": "Disabled", "inactiveReason": "Awaiting commissioning" },
    { "machineCode": "JAN-MIX-01", "displayName": "JANDI 2/3 Shared Bulk Mixer", "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer", "validDestinationMachineCodes": ["JAN-02", "JAN-03", "JAN-04"], "routeDescription": "JANDI 2 or JANDI 3 direct; use the drum cycle before JANDI 4." },
    { "machineCode": "JAN-DRUM-01", "displayName": "JANDI Transfer Drum", "mixingArea": "JandiBulkMixing", "equipmentRole": "Transfer", "validDestinationMachineCodes": ["JAN-04"], "routeDescription": "Drum cycle gates JANDI 4." },
    { "machineCode": "JAN-02", "displayName": "JANDI 2", "mixingArea": "JandiBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "JAN-03", "displayName": "JANDI 3", "mixingArea": "JandiBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "JAN-04", "displayName": "JANDI 4", "mixingArea": "JandiBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "MAC-MIX-01", "displayName": "Mackie Grey Bulk Mixer", "mixingArea": "MackieBulkMixing", "equipmentRole": "Mixer", "validDestinationMachineCodes": ["MAC-EXT-01"], "routeDescription": "Fixed Mackie extruder." },
    { "machineCode": "MAC-EXT-01", "displayName": "Mackie Extruder", "mixingArea": "MackieBulkMixing", "equipmentRole": "ProductionMachine" },
    { "machineCode": "RAJ-GM-01", "displayName": "Rajoo Gravimetric Mixer Layer 1", "mixingArea": "RajooMachineMixing", "equipmentRole": "Mixer", "productLayer": 1, "validDestinationMachineCodes": ["RAJ-EXT-01"], "routeDescription": "Feeds Layer 1 of the Rajoo extruder." },
    { "machineCode": "RAJ-GM-02", "displayName": "Rajoo Gravimetric Mixer Layer 2", "mixingArea": "RajooMachineMixing", "equipmentRole": "Mixer", "productLayer": 2, "validDestinationMachineCodes": ["RAJ-EXT-01"], "routeDescription": "Feeds Layer 2 of the Rajoo extruder." },
    { "machineCode": "RAJ-GM-03", "displayName": "Rajoo Gravimetric Mixer Layer 3", "mixingArea": "RajooMachineMixing", "equipmentRole": "Mixer", "productLayer": 3, "validDestinationMachineCodes": ["RAJ-EXT-01"], "routeDescription": "Feeds Layer 3 of the Rajoo extruder." },
    { "machineCode": "RAJ-EXT-01", "displayName": "Rajoo 3-Layer Extruder", "mixingArea": "RajooMachineMixing", "equipmentRole": "ProductionMachine" }
  ],
```

- [ ] **Step 3: Bump scan-stock pallet quantities**

The v4 selftest completes **four** collections of job 510019068 (Main ×2, JANDI, Rajoo). In the same `seed.json`, change `remainingQuantity` on these pallets (semantics pallets — blocked `...0003`, AtStation1 `...0004`, Unknown `...0005`, Consumed `...0006`, small-stock `...0007` — stay unchanged):

| palletRfidTag | old | new |
|---|---|---|
| `300833B2DDD9014000000001` | 625.0 | 9999.0 |
| `300833B2DDD9014000000002` | 1800.0 | 99999.0 |
| `300833B2DDD901400000000C` | 700.0 | 9999.0 |
| `300833B2DDD9014000000008` | 875.0 | 9999.0 |
| `300833B2DDD9014000000009` | 250.0 | 9999.0 |
| `300833B2DDD901400000000A` | 450.0 | 9999.0 |
| `300833B2DDD901400000000B` | 500.0 | 9999.0 |

Also update the two operators' `allowedTabs` from `["collect", "premix", "allocation"]` / `[..., "admin"]` to `["collect", "mixing"]` / `["collect", "mixing", "admin"]` (v3 tab names are dead tokens).

- [ ] **Step 4: Verify the world loads**

```bash
python -c "
import json, types
from simlog import SimLogger
from state import World
log = SimLogger('.', color=False)
w = World('seed', log)
assert len(w.equipment) == 47, len(w.equipment)
assert w.equipment['MXR-01']['validDestinationMachineCodes'] == sorted([f'EXT-{i:02d}' for i in range(1, 26)])
assert w.equipment['EXT-25']['status'] == 'Disabled'
assert w.equipment['RAJ-GM-02']['productLayer'] == 2
assert w.next_id('MIX') == 'MIX_000001' and w.next_id('CYC') == 'CYC_000001' and w.next_id('RUN') == 'RUN_000001'
json.dumps(w.to_dict())
print('WORLD OK')
log.close()
"
```

Expected: `WORLD OK`. (`selftest.py --direct` is expected to fail until Task 5 — capture handlers still reference the deleted hopper board until Task 3.)

- [ ] **Step 5: Commit**

```bash
git add tools/backend-sim/state.py tools/backend-sim/seed/seed.json
git commit -m "feat(sim): v4 world state — five-area equipment topology, MixBatch/Cycle/Run"
```

---
### Task 3: Capture handlers v4, registry rework, retired-topic guard, credential redaction

**Files:**
- Modify: `tools/backend-sim/handlers/ingredients.py:14-28` (`_result`), `:51-57` (`_completion_check`), `:68-74` (status guard)
- Modify: `tools/backend-sim/handlers/jobcards.py` (drop premix/hopper content; v4 statuses)
- Rewrite: `tools/backend-sim/handlers/__init__.py`
- Modify: `tools/backend-sim/sim.py` (retired guard + redaction + banner)
- Delete: `tools/backend-sim/handlers/cycles.py`, `tools/backend-sim/handlers/allocations.py`, `tools/backend-sim/handlers/completion.py`

**Interfaces:**
- Consumes: Task 2's `World` (no `hopper_board`, no `premixes`).
- Produces: `handlers.RETIRED_REQUEST_TYPES` (frozenset) and a `REGISTRY` whose mixing entries point at `handlers/mixing.py` functions created in Task 4 (`mixing.overview`, `mixing.start`, `mixing.finish`, `mixing.force_close` — all answering on `machine_cycle_result` except overview on `mixing_overview_result`). Collection dicts now carry `claimedByMixBatchId` (None until a mixer claims them) and status vocabulary `Collecting | ReadyForMixing | Mixing | Cancelled`. Scan results now carry `collectionStatus` and no `hoppers`.

- [ ] **Step 1: v4-ify `ingredients.py`**

Replace `_result`'s extras dict (drop the board, add status):

```python
    extras = {
        "collectionId": col["collectionId"],
        "collectionStatus": col["status"],
        "requiresManagerApproval": requires_approval,
        "overCollectionToleranceBags": (world.config["overCollectionToleranceBags"]
                                        if tolerance == "unset" else tolerance),
        "collectionSummary": collection_summary(col),
        "ingredients": ingredients_payload(world, col),
    }
```

Replace `_completion_check`:

```python
def _completion_check(world, log, col):
    if col["status"] == "Collecting" and collection_is_complete(col):
        col["status"] = "ReadyForMixing"
        log.transition(f"collection {col['collectionId']}: Collecting -> ReadyForMixing "
                       f"(all adjusted manual requirements satisfied)")
        return "start_mixing"
    return "scan_ingredient"
```

Replace the status guard inside `scan()`:

```python
    if col["status"] != "Collecting":
        log.fail(f"ingredient scan on {col_id}: status is {col['status']}, not Collecting")
        return _reject(world, req, col, "state_conflict",
                       f"Collection {col_id} is {col['status']}; ingredient scanning "
                       f"is only valid while Collecting.",
                       next_action="start_mixing"
                       if col["status"] == "ReadyForMixing" else "active_job_cards")
```

- [ ] **Step 2: v4-ify `jobcards.py`**

Delete `_premix_active` and `_active_hopper_codes`. Replace `active_list`'s job entry construction and drop the board:

```python
def active_list(world, log, req, session):
    jobs = []
    for col in world.collections.values():
        status = col["status"]
        if status == "Cancelled":
            continue
        collected, remaining = collection_progress(col)
        jobs.append({
            "collectionId": col["collectionId"],
            "jobCardNumber": col["jobCardNumber"],
            "productionOrderDocumentNumber": col["productionOrderDocumentNumber"],
            "productName": col["productName"],
            "status": status,
            "collectedQuantity": collected,
            "remainingQuantity": remaining,
            "claimedByMixBatchId": col["claimedByMixBatchId"],
        })
    log.ok(f"active job cards: {len(jobs)} non-terminal collection(s)")
    return build_response(world, req, response_extras={"jobs": jobs})
```

In `bom_loaded_response` remove the `"hoppers": world.hopper_board(),` line. In `load()` replace `"linkedPreMixId": None,` with `"claimedByMixBatchId": None,`. In `resume()` replace the status dispatch:

```python
    if status == "Collecting":
        next_action = "scan_ingredient"
    elif status == "ReadyForMixing":
        next_action = "start_mixing"
    else:
        raise Rejection("state_conflict",
                        f"Collection {col_id} is {status}; nothing to resume.",
                        next_action="active_job_cards")
```

In `cancel()` change the allowed statuses to `("Collecting", "ReadyForMixing")`.

- [ ] **Step 3: Delete the retired handler modules**

```bash
git rm tools/backend-sim/handlers/cycles.py tools/backend-sim/handlers/allocations.py tools/backend-sim/handlers/completion.py
```

(`station2_work_complete_requested` has no v4.0 counterpart — machine completion is local traceability inside the mixing flow — so it is retired along with the allocation lifecycle it ended.)

- [ ] **Step 4: Rewrite the registry**

`tools/backend-sim/handlers/__init__.py` becomes:

```python
"""Request-type registry: maps each req/{type} leaf to its handler and its
res/{type} leaf. RETIRED_REQUEST_TYPES lists every v3 production mutation the
contract retired — sim.py answers those on res/workflow_upgrade_required."""

from handlers import auth, ingredients, jobcards, mixing, pallets


def _scan_reject(world):
    # Envelope/session-layer scan rejections still carry the approval flag the
    # scanner branches on; business rejections come fully formed from the handler.
    return {"requiresManagerApproval": False}


REGISTRY = {
    "login_requested": {
        "handler": auth.login, "response": "operator_context",
        "is_login": True, "mutating": True, "reject_extras": None},
    "reader_logout_requested": {
        "handler": auth.logout, "response": "operator_context",
        "is_login": False, "mutating": True, "reject_extras": None},
    "pallet_lookup_requested": {
        "handler": pallets.lookup, "response": "pallet_lookup_result",
        "is_login": False, "mutating": False, "reject_extras": None},
    "holding_recovery_requested": {
        "handler": pallets.recovery, "response": "holding_recovery_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "active_job_cards_requested": {
        "handler": jobcards.active_list, "response": "active_job_cards_list",
        "is_login": False, "mutating": False, "reject_extras": None},
    "open_sap_job_cards_requested": {
        "handler": jobcards.open_sap_list, "response": "open_sap_job_cards_list",
        "is_login": False, "mutating": False, "reject_extras": None},
    "job_card_load_requested": {
        "handler": jobcards.load, "response": "bom_loaded",
        "is_login": False, "mutating": True, "reject_extras": None},
    "collection_resume_requested": {
        "handler": jobcards.resume, "response": "bom_loaded",
        "is_login": False, "mutating": False, "reject_extras": None},
    "ingredient_collection_cancel_requested": {
        "handler": jobcards.cancel, "response": "ingredient_collection_cancel_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "ingredient_scan_requested": {
        "handler": ingredients.scan, "response": "ingredient_scan_result",
        "is_login": False, "mutating": True, "reject_extras": _scan_reject},
    "mixing_overview_requested": {
        "handler": mixing.overview, "response": "mixing_overview_result",
        "is_login": False, "mutating": False, "reject_extras": None},
    "machine_cycle_start_requested": {
        "handler": mixing.start, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "machine_cycle_finish_requested": {
        "handler": mixing.finish, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "machine_cycle_force_close_requested": {
        "handler": mixing.force_close, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
}

# Contract §12: retired v3 production mutations. The real backend answers these
# on res/workflow_upgrade_required; so do we — a tripwire for any v3 call left
# in the app.
RETIRED_REQUEST_TYPES = frozenset({
    "hopper_overview_requested",
    "allocation_overview_requested",
    "full_pallet_allocation_requested",
    "bag_allocation_requested",
    "allocation_action_requested",
    "station2_work_complete_requested",
})
```

Create a **stub** `tools/backend-sim/handlers/mixing.py` so imports resolve until Task 4 lands the real handlers:

```python
"""mixing_overview_requested -> mixing_overview_result
machine_cycle_{start,finish,force_close}_requested -> machine_cycle_result

Implemented in the next task; the registry imports these names now so the
capture-only simulator already runs as a valid v4 backend."""

from envelope import Rejection


def overview(world, log, req, session):
    raise Rejection("validation_failed", "mixing_overview is not implemented yet.")


def start(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_start is not implemented yet.")


def finish(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_finish is not implemented yet.")


def force_close(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_force_close is not implemented yet.")
```

- [ ] **Step 5: Retired-topic guard + credential redaction in `sim.py`**

Change the imports line:

```python
from handlers import REGISTRY, RETIRED_REQUEST_TYPES
```

Add above `class Simulator`:

```python
def _redacted(payload):
    """Wire-log payloads with credentials masked. The workflow still receives the
    original bytes — only the log copy is redacted (contract: credentials are
    redacted from application and MQTT logs)."""
    try:
        body = json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        return payload
    if not isinstance(body, dict):
        return payload
    masked = False
    for key in ("password", "managerPassword"):
        if body.get(key) is not None:
            body[key] = "***"
            masked = True
    return json.dumps(body, ensure_ascii=False) if masked else payload
```

In `handle_request`, replace the first two statements:

```python
        self.log.wire("in", f"PPNAM/{device_id}/req/{request_type}", _redacted(payload))
        if request_type in RETIRED_REQUEST_TYPES:
            self.log.fail(f"req/{request_type} from {device_id}: RETIRED v3 production request "
                          f"— answering client_upgrade_required (v4 tripwire)")
            try:
                body = json.loads(payload)
                if not isinstance(body, dict):
                    body = {}
            except (ValueError, UnicodeDecodeError):
                body = {}
            body.setdefault("deviceId", device_id)
            response = build_response(
                self.world, body, accepted=False,
                error_code="client_upgrade_required",
                reason="This request was retired by contract v4.0. Upgrade the reader "
                       "build for the unified Mixing workflow.",
                next_action="upgrade_reader_for_mixing")
            self.publish_response(device_id, "workflow_upgrade_required", response)
            return
        entry = REGISTRY.get(request_type)
```

Also update the `run()` banner string `contract v3` → `contract v4` and the argparse description `(contract v3)` → `(contract v4)`.

- [ ] **Step 6: Verify capture flow end-to-end at 4.0**

```bash
python -c "
from selftest import DirectHandheld
hh = DirectHandheld()
r,_ = hh.request('login_requested', {'username':'operator1','password':'pass'}, session='', schema='4.0')
hh.session = r['operatorSessionId']
r,_ = hh.request('job_card_load_requested', {'jobCardNumber':'510019068'}, schema='4.0')
assert r['accepted'] and 'hoppers' not in r and r['collectionStatus'] == 'Collecting', r.keys()
r,_ = hh.request('hopper_overview_requested', schema='4.0')
assert r['_topic'].endswith('/res/workflow_upgrade_required'), r['_topic']
assert r['errorCode'] == 'client_upgrade_required' and r['nextAction'] == 'upgrade_reader_for_mixing', r
r,_ = hh.request('station2_work_complete_requested', {'productionOrderDocumentNumber':'510019068'}, schema='4.0')
assert r['_topic'].endswith('/res/workflow_upgrade_required'), r['_topic']
print('CAPTURE + RETIRED GUARD OK')
hh.close()
"
```

Expected: `CAPTURE + RETIRED GUARD OK`.

- [ ] **Step 7: Commit**

```bash
git add tools/backend-sim/handlers/ tools/backend-sim/sim.py
git commit -m "feat(sim): v4 capture handlers, registry, retired-topic guard, credential redaction"
```

---

### Task 4: `handlers/mixing.py` — overview, start dispatch, finish, force-close

**Files:**
- Rewrite (replacing the Task 3 stub): `tools/backend-sim/handlers/mixing.py`

**Interfaces:**
- Consumes: `World.equipment / mix_batches / cycles / runs / collections`, `envelope.approve` (action id `machine_force_close`), `Rejection`, `build_response`, `handlers.common.EPS, r3`.
- Produces: `overview`, `start`, `finish`, `force_close` (all `(world, log, req, session) -> response dict`) plus `area_overview(world, area=None, po=None)` used by the unified result. Mix batches: `status` walks `Mixing -> ReadyForProduction -> Consumed` (or `Cancelled` via force-close); `assignedToCycleId` marks drum/run assignment; `drumCompleted` gates JANDI 4. Production cycles use their `RUN_` id as `cycleId` and appear in **both** `world.cycles` and `world.runs`.

- [ ] **Step 1: Write the full handler module**

```python
"""mixing_overview_requested -> mixing_overview_result
machine_cycle_start_requested / machine_cycle_finish_requested /
machine_cycle_force_close_requested -> machine_cycle_result

Contract v4.0 §6-§10. Business rejections come back fully formed with a
refreshed areaStatus; envelope/session failures never reach these handlers
and carry no operational area data."""

from envelope import NO_APPROVER, Rejection, approve, build_response
from handlers.common import EPS, r3
from state import iso, utc_now

LEGACY_FIELDS = ("machineCodes", "collectionIds", "preMixId", "preMixIds")


# ------------------------------------------------------------ payloads ----
def _equipment_payload(eq):
    return {
        "mixingArea": eq["mixingArea"],
        "equipmentRole": eq["equipmentRole"],
        "machineCode": eq["machineCode"],
        "displayName": eq["displayName"],
        "isEnabled": eq["isEnabled"],
        "isAvailable": eq["isEnabled"] and eq["status"] == "Available",
        "status": eq["status"],
        "productLayer": eq["productLayer"],
        "currentCycleId": eq["currentCycleId"],
        "currentProductionOrderDocumentNumber": eq["currentProductionOrderDocumentNumber"],
        "currentMixBatchIds": list(eq["currentMixBatchIds"]),
        "validDestinationMachineCodes": list(eq["validDestinationMachineCodes"]),
        "routeDescription": eq["routeDescription"],
    }


def valid_next_machine_codes(world, mix):
    """Server-authoritative destinations for one ReadyForProduction mix."""
    mixer = world.equipment.get(mix["mixerCode"])
    dests = list(mixer["validDestinationMachineCodes"]) if mixer else []
    if mix["mixingArea"] == "JandiBulkMixing":
        if mix["drumCompleted"]:
            return ["JAN-04"]
        return ["JAN-DRUM-01"] + [d for d in dests if d != "JAN-04"]
    return dests


def _mix_payload(world, mix):
    nexts = valid_next_machine_codes(world, mix)
    return {
        "mixBatchId": mix["mixBatchId"],
        "collectionId": mix["collectionId"],
        "mixingArea": mix["mixingArea"],
        "productionOrderDocumentNumber": mix["productionOrderDocumentNumber"],
        "mixerCode": mix["mixerCode"],
        "mixerDisplayName": mix["mixerDisplayName"],
        "productLayer": mix["productLayer"],
        "status": mix["status"],
        "plannedDestinationMachineCode": mix["plannedDestinationMachineCode"],
        "validNextMachineCodes": nexts,
        "nextStepDescription": f"Start one of: {', '.join(nexts)}." if nexts else "",
    }


def _cycle_payload(world, c):
    return {
        "cycleId": c["cycleId"],
        "machineCode": c["machineCode"],
        "mixingArea": c["mixingArea"],
        "equipmentRole": c["equipmentRole"],
        "productionOrderDocumentNumber": c["productionOrderDocumentNumber"],
        "collectionId": c["collectionId"],
        "mixBatchIds": list(c["mixBatchIds"]),
        "productionRunId": c["runId"],
        "startedAtUtc": c["startedAtUtc"],
        "startedByOperatorId": c["startedByOperatorId"],
    }


def _run_payload(world, r):
    return {
        "productionRunId": r["productionRunId"],
        "machineCode": r["machineCode"],
        "productionOrderDocumentNumber": r["productionOrderDocumentNumber"],
        "mixBatchIds": list(r["mixBatchIds"]),
        "startedAtUtc": r["startedAtUtc"],
    }


def area_overview(world, area=None, po=None):
    """The board (§7/§8): equipment, active cycles, ready mixes, active runs.
    area=None means all five areas; po filters mixes/cycles/runs, never equipment."""
    def in_scope(item_area):
        return area is None or item_area == area

    def po_ok(item_po):
        return po is None or str(item_po) == str(po)

    return {
        "accepted": True,
        "mixingArea": area,
        "productionOrderDocumentNumber": po,
        "equipment": [_equipment_payload(e) for e in world.equipment.values()
                      if in_scope(e["mixingArea"])],
        "activeCycles": [_cycle_payload(world, c) for c in world.cycles.values()
                         if c["active"] and in_scope(c["mixingArea"])
                         and po_ok(c["productionOrderDocumentNumber"])],
        "readyMixes": [_mix_payload(world, m) for m in world.mix_batches.values()
                       if m["status"] == "ReadyForProduction"
                       and m["assignedToCycleId"] is None
                       and in_scope(m["mixingArea"])
                       and po_ok(m["productionOrderDocumentNumber"])],
        "activeRuns": [_run_payload(world, r) for r in world.runs.values()
                       if r["active"] and in_scope(world.equipment[r["machineCode"]]["mixingArea"])
                       and po_ok(r["productionOrderDocumentNumber"])],
    }


# ------------------------------------------------------- unified result ----
def _machine_result(world, req, *, accepted=True, error_code=None, reason=None,
                    next_action="", action=None, eq=None, cycle_id=None, po=None,
                    collection_id=None, mix_batch_id=None, run_id=None, affected=None,
                    already_finished=False, force_closed=False, approver=None,
                    correlation=None):
    area = eq["mixingArea"] if eq else None
    extras = {
        "action": action,
        "mixingArea": area,
        "equipmentRole": eq["equipmentRole"] if eq else None,
        "machineCode": eq["machineCode"] if eq else req.get("machineCode"),
        "cycleId": cycle_id,
        "productionOrderDocumentNumber": (str(po) if po is not None
                                          else req.get("productionOrderDocumentNumber")),
        "collectionId": collection_id,
        "mixBatchId": mix_batch_id,
        "productionRunId": run_id,
        "affectedMixBatchIds": list(affected or []),
        "alreadyFinished": already_finished,
        "forceClosed": force_closed,
        "sapIssueQueued": False,
        "sapProductionOrderChanged": False,
        "areaStatus": area_overview(world, area),
    }
    extras.update(approver or NO_APPROVER)
    return build_response(world, req, accepted=accepted, error_code=error_code,
                          reason=reason, next_action=next_action,
                          correlation=correlation, response_extras=extras)


def _mreject(world, log, req, eq, error_code, reason, next_action=""):
    log.fail(f"machine cycle rejected ({error_code}): {reason}")
    return _machine_result(world, req, accepted=False, error_code=error_code,
                           reason=reason, next_action=next_action, eq=eq)


# ------------------------------------------------------------ overview ----
def overview(world, log, req, session):
    area = req.get("mixingArea")
    if area is not None and area not in world.MIXING_AREAS:
        raise Rejection("invalid_mixing_area",
                        f"'{area}' is not one of the five fixed mixing areas.")
    po = req.get("productionOrderDocumentNumber")
    ov = area_overview(world, area, po)
    log.ok(f"mixing overview: area={area or 'ALL'} po={po or 'ALL'} — "
           f"{len(ov['equipment'])} equipment, {len(ov['activeCycles'])} active cycles, "
           f"{len(ov['readyMixes'])} ready mixes, {len(ov['activeRuns'])} active runs")
    extras = dict(ov)
    del extras["accepted"]  # build_response owns the envelope's accepted flag
    return build_response(world, req, next_action="select_collection_mix_or_machine",
                          response_extras=extras)


# --------------------------------------------------------------- start ----
def start(world, log, req, session):
    present = [f for f in LEGACY_FIELDS if f in req]
    if present:
        return _mreject(world, log, req, None, "legacy_request_shape",
                        f"v3 field(s) {', '.join(present)} are rejected in a 4.0 mixing "
                        f"request. Remove them and resend with a NEW messageId.")
    code = req.get("machineCode")
    if not code:
        return _mreject(world, log, req, None, "validation_failed",
                        "machineCode is required.")
    eq = world.equipment.get(code)
    if not eq or not eq["isEnabled"] or eq["status"] == "Disabled":
        return _mreject(world, log, req, None, "unknown_or_disabled_equipment",
                        f"Machine '{code}' is unknown or disabled.")
    po = req.get("productionOrderDocumentNumber")
    if not po:
        return _mreject(world, log, req, eq, "validation_failed",
                        "productionOrderDocumentNumber is required.")
    role = eq["equipmentRole"]
    if role == "Mixer":
        return _start_mixer(world, log, req, session, eq, po)
    if role == "Transfer":
        return _start_drum(world, log, req, session, eq, po)
    return _start_production(world, log, req, session, eq, po)


def _start_mixer(world, log, req, session, eq, po):
    if req.get("mixBatchIds"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "A mixer start sends exactly one collectionId and no mixBatchIds.")
    col_id = req.get("collectionId")
    if not col_id:
        return _mreject(world, log, req, eq, "validation_failed",
                        "collectionId is required on a mixer start.")
    if eq["status"] == "InUse":
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{eq['machineCode']} is busy on cycle {eq['currentCycleId']}.")
    col = world.collections.get(col_id)
    if not col:
        return _mreject(world, log, req, eq, "source_not_found",
                        f"Collection '{col_id}' was not found.")
    if col["jobCardNumber"] != str(po):
        return _mreject(world, log, req, eq, "job_card_mismatch",
                        f"Collection {col_id} belongs to JC {col['jobCardNumber']}, not {po}.")
    if col["status"] != "ReadyForMixing":
        if col["claimedByMixBatchId"]:
            return _mreject(world, log, req, eq, "source_already_assigned",
                            f"Collection {col_id} was already claimed by "
                            f"{col['claimedByMixBatchId']}. Each collection is claimed once, ever.")
        return _mreject(world, log, req, eq, "source_not_ready",
                        f"Collection {col_id} is {col['status']}; it must be ReadyForMixing.")

    layer_inputs = None
    if eq["mixingArea"] == "RajooMachineMixing":
        layer_inputs, err = _validate_layer_inputs(req, col)
        if err:
            return _mreject(world, log, req, eq, "invalid_layer_inputs", err)
    elif "layerInputs" in req:
        return _mreject(world, log, req, eq, "validation_failed",
                        "layerInputs are only valid on a Rajoo gravimetric mixer start.")

    mix_id = world.next_id("MIX")
    cyc_id = world.next_id("CYC")
    dests = eq["validDestinationMachineCodes"]
    world.mix_batches[mix_id] = {
        "mixBatchId": mix_id,
        "collectionId": col_id,
        "mixingArea": eq["mixingArea"],
        "productionOrderDocumentNumber": str(po),
        "mixerCode": eq["machineCode"],
        "mixerDisplayName": eq["displayName"],
        "productLayer": eq["productLayer"],
        "status": "Mixing",
        "plannedDestinationMachineCode": dests[0] if len(dests) == 1 else None,
        "assignedToCycleId": None,
        "drumCompleted": False,
        "layerInputs": layer_inputs,
    }
    world.cycles[cyc_id] = _new_cycle(cyc_id, eq, po, session, req,
                                      collection_id=col_id, mix_batch_ids=[mix_id])
    col["claimedByMixBatchId"] = mix_id
    col["status"] = "Mixing"
    _occupy(eq, cyc_id, po, [mix_id])
    log.transition(f"mixer start: {eq['machineCode']} claimed {col_id} -> {mix_id} / {cyc_id} "
                   f"(operator {session['operatorId']}, device {req['deviceId']})")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=cyc_id, po=po,
                           collection_id=col_id, mix_batch_id=mix_id, affected=[mix_id],
                           next_action="scan_same_machine_to_finish", correlation=mix_id)


def _validate_layer_inputs(req, col):
    inputs = req.get("layerInputs")
    if not isinstance(inputs, list) or not (1 <= len(inputs) <= 5):
        return None, "layerInputs must contain one to five material/dose lines."
    collected = {l["materialCode"]: l["collectedQuantity"]
                 for l in col["lines"] if l["requiresIngredientCollection"]}
    for item in inputs:
        if not isinstance(item, dict):
            return None, "Each layer input must be an object with materialCode and dosingQuantity."
        mat = item.get("materialCode")
        dose = item.get("dosingQuantity")
        if not mat or not isinstance(dose, (int, float)) or dose <= 0:
            return None, "Each layer input needs a materialCode and a positive dosingQuantity."
        if mat not in collected:
            return None, f"Material {mat} was not collected in this collection."
        if dose > collected[mat] + EPS:
            return None, (f"Dose {r3(dose)} of {mat} exceeds its collected "
                          f"quantity {r3(collected[mat])}.")
    return inputs, None


def _start_drum(world, log, req, session, eq, po):
    if req.get("collectionId"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "A drum start takes exactly one completed mixBatchId, never a collection.")
    ids = req.get("mixBatchIds")
    if not isinstance(ids, list) or len(ids) != 1:
        return _mreject(world, log, req, eq, "validation_failed",
                        "A drum start accepts exactly one completed JANDI mixBatchId.")
    if eq["status"] == "InUse":
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{eq['machineCode']} is busy on cycle {eq['currentCycleId']}.")
    mix = world.mix_batches.get(ids[0])
    if not mix:
        return _mreject(world, log, req, eq, "source_not_found",
                        f"Mix '{ids[0]}' was not found.")
    if mix["productionOrderDocumentNumber"] != str(po):
        return _mreject(world, log, req, eq, "job_card_mismatch",
                        f"Mix {ids[0]} belongs to JC {mix['productionOrderDocumentNumber']}.")
    if mix["mixingArea"] != "JandiBulkMixing":
        return _mreject(world, log, req, eq, "invalid_route",
                        f"Mix {ids[0]} is a {mix['mixingArea']} mix; the drum serves JANDI only.")
    if mix["status"] != "ReadyForProduction":
        return _mreject(world, log, req, eq, "source_not_ready",
                        f"Mix {ids[0]} is {mix['status']}; it must be ReadyForProduction.")
    if mix["assignedToCycleId"]:
        return _mreject(world, log, req, eq, "source_already_assigned",
                        f"Mix {ids[0]} is already assigned to {mix['assignedToCycleId']}.")
    if mix["drumCompleted"]:
        return _mreject(world, log, req, eq, "invalid_route",
                        f"The drum cycle for {ids[0]} is already complete; start JANDI 4.")
    cyc_id = world.next_id("CYC")
    world.cycles[cyc_id] = _new_cycle(cyc_id, eq, po, session, req, mix_batch_ids=list(ids))
    mix["assignedToCycleId"] = cyc_id
    _occupy(eq, cyc_id, po, list(ids))
    log.transition(f"drum start: {eq['machineCode']} cycle {cyc_id} on {ids[0]}")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=cyc_id, po=po,
                           mix_batch_id=ids[0], affected=list(ids),
                           next_action="scan_same_machine_to_finish", correlation=cyc_id)


def _start_production(world, log, req, session, eq, po):
    if req.get("collectionId"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "No collection/pallet/bag ID is valid in a downstream start.")
    ids = req.get("mixBatchIds")
    if not isinstance(ids, list) or not ids:
        return _mreject(world, log, req, eq, "validation_failed",
                        "One or more completed same-JC mixBatchIds are required.")
    # Resolve and validate ALL mixes first — a single failure rejects the whole request.
    code = eq["machineCode"]
    for mid in ids:
        mix = world.mix_batches.get(mid)
        if not mix:
            return _mreject(world, log, req, eq, "source_not_found",
                            f"Mix '{mid}' was not found.")
        if mix["productionOrderDocumentNumber"] != str(po):
            return _mreject(world, log, req, eq, "job_card_mismatch",
                            f"Mix {mid} belongs to JC {mix['productionOrderDocumentNumber']}, "
                            f"not {po}. Remove mixes from other JCs.")
        if mix["status"] != "ReadyForProduction":
            return _mreject(world, log, req, eq, "source_not_ready",
                            f"Mix {mid} is {mix['status']}; it must be ReadyForProduction.")
        if mix["assignedToCycleId"]:
            return _mreject(world, log, req, eq, "source_already_assigned",
                            f"Mix {mid} is already assigned to {mix['assignedToCycleId']}.")
        allowed = valid_next_machine_codes(world, mix)
        if code not in allowed:
            if (mix["mixingArea"] == "JandiBulkMixing" and code == "JAN-04"
                    and not mix["drumCompleted"]):
                return _mreject(world, log, req, eq, "drum_cycle_required",
                                f"Start and finish {'JAN-DRUM-01'} before JANDI 4 for {mid}.")
            return _mreject(world, log, req, eq, "invalid_route",
                            f"{code} is not a valid destination for {mid}; "
                            f"valid: {', '.join(allowed)}.")
    if eq["status"] == "InUse":
        run = world.runs.get(eq["currentCycleId"])
        if run and run["active"] and run["productionOrderDocumentNumber"] == str(po):
            # Accumulate additional same-JC mixes into the active run.
            run["mixBatchIds"].extend(ids)
            for mid in ids:
                world.mix_batches[mid]["assignedToCycleId"] = run["productionRunId"]
            world.cycles[run["productionRunId"]]["mixBatchIds"].extend(ids)
            eq["currentMixBatchIds"].extend(ids)
            log.transition(f"production run {run['productionRunId']} on {code}: "
                           f"accumulated {', '.join(ids)}")
            return _machine_result(world, req, action="Started", eq=eq,
                                   cycle_id=run["productionRunId"], po=po,
                                   run_id=run["productionRunId"], affected=list(ids),
                                   next_action="scan_same_machine_to_finish",
                                   correlation=run["productionRunId"])
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{code} is busy on another job card.")
    run_id = world.next_id("RUN")
    world.runs[run_id] = {
        "productionRunId": run_id,
        "machineCode": code,
        "productionOrderDocumentNumber": str(po),
        "mixBatchIds": list(ids),
        "active": True,
        "startedAtUtc": iso(utc_now()),
    }
    # A production cycle's durable id IS the run id (§8: cycleId == productionRunId).
    world.cycles[run_id] = _new_cycle(run_id, eq, po, session, req,
                                      mix_batch_ids=list(ids), run_id=run_id)
    for mid in ids:
        world.mix_batches[mid]["assignedToCycleId"] = run_id
    _occupy(eq, run_id, po, list(ids))
    log.transition(f"production start: {code} run {run_id} consuming {', '.join(ids)}")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=run_id, po=po,
                           run_id=run_id, affected=list(ids),
                           next_action="scan_same_machine_to_finish", correlation=run_id)


def _new_cycle(cyc_id, eq, po, session, req, collection_id=None, mix_batch_ids=None,
               run_id=None):
    return {
        "cycleId": cyc_id,
        "machineCode": eq["machineCode"],
        "mixingArea": eq["mixingArea"],
        "equipmentRole": eq["equipmentRole"],
        "productionOrderDocumentNumber": str(po),
        "collectionId": collection_id,
        "mixBatchIds": list(mix_batch_ids or []),
        "runId": run_id,
        "active": True,
        "startedAtUtc": iso(utc_now()),
        "finishedAtUtc": None,
        "forceClosed": False,
        "startedByOperatorId": session["operatorId"],
        "fromDevice": req["deviceId"],
    }


def _occupy(eq, cyc_id, po, mix_ids):
    eq["status"] = "InUse"
    eq["currentCycleId"] = cyc_id
    eq["currentProductionOrderDocumentNumber"] = str(po)
    eq["currentMixBatchIds"] = list(mix_ids)


# ------------------------------------------------- finish / force-close ----
def _resolve(world, log, req):
    """Returns (eq, cycle, reject_response_or_None) for finish/force-close."""
    code = req.get("machineCode")
    cyc_id = req.get("cycleId")
    if not code or not cyc_id:
        return None, None, _mreject(world, log, req, None, "validation_failed",
                                    "Both machineCode and cycleId are required.")
    eq = world.equipment.get(code)
    if not eq:
        return None, None, _mreject(world, log, req, None, "unknown_or_disabled_equipment",
                                    f"Machine '{code}' is unknown.")
    cycle = world.cycles.get(cyc_id)
    if not cycle or cycle["machineCode"] != code:
        return eq, None, _mreject(world, log, req, eq, "cycle_mismatch",
                                  f"Cycle '{cyc_id}' is not a cycle of {code}. A stale "
                                  f"cycle ID can never finish a newer use of the machine.")
    return eq, cycle, None


def _apply_finish(world, log, eq, cycle, forced):
    cycle["active"] = False
    cycle["finishedAtUtc"] = iso(utc_now())
    cycle["forceClosed"] = forced
    if eq["currentCycleId"] == cycle["cycleId"]:
        eq["status"] = "Disabled" if not eq["isEnabled"] else "Available"
        eq["currentCycleId"] = None
        eq["currentProductionOrderDocumentNumber"] = None
        eq["currentMixBatchIds"] = []
    role = cycle["equipmentRole"]
    if role == "Mixer":
        mix = world.mix_batches[cycle["mixBatchIds"][0]]
        if forced:
            mix["status"] = "Cancelled"
            col = world.collections.get(cycle["collectionId"])
            if col:
                col["claimedByMixBatchId"] = None
                col["status"] = "ReadyForMixing"
                log.transition(f"force-close voided {mix['mixBatchId']}; collection "
                               f"{col['collectionId']} released back to ReadyForMixing")
        else:
            mix["status"] = "ReadyForProduction"
    elif role == "Transfer":
        mix = world.mix_batches[cycle["mixBatchIds"][0]]
        mix["assignedToCycleId"] = None
        if not forced:
            mix["drumCompleted"] = True
    else:  # ProductionMachine
        run = world.runs.get(cycle["runId"])
        if run:
            run["active"] = False
        for mid in cycle["mixBatchIds"]:
            mix = world.mix_batches.get(mid)
            if not mix:
                continue
            if forced:
                mix["assignedToCycleId"] = None  # released, not consumed
            else:
                mix["status"] = "Consumed"
    log.transition(f"cycle {cycle['cycleId']} on {eq['machineCode']} -> "
                   f"{'FORCE-CLOSED' if forced else 'Finished'}")


def _finish_next_action(cycle):
    return "" if cycle["equipmentRole"] == "ProductionMachine" \
        else "select_collection_mix_or_machine"


def finish(world, log, req, session):
    eq, cycle, rej = _resolve(world, log, req)
    if rej:
        return rej
    if not cycle["active"]:
        log.warn(f"re-finish of completed cycle {cycle['cycleId']}: idempotent no-op")
        return _machine_result(world, req, action="Finished", eq=eq,
                               cycle_id=cycle["cycleId"], po=cycle["productionOrderDocumentNumber"],
                               collection_id=cycle["collectionId"],
                               mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                               run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                               already_finished=True, force_closed=cycle["forceClosed"],
                               next_action=_finish_next_action(cycle),
                               correlation=cycle["cycleId"])
    _apply_finish(world, log, eq, cycle, forced=False)
    return _machine_result(world, req, action="Finished", eq=eq, cycle_id=cycle["cycleId"],
                           po=cycle["productionOrderDocumentNumber"],
                           collection_id=cycle["collectionId"],
                           mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                           run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                           next_action=_finish_next_action(cycle),
                           correlation=cycle["cycleId"])


def force_close(world, log, req, session):
    eq, cycle, rej = _resolve(world, log, req)
    if rej:
        return rej
    try:
        approver = approve(world, log, req, "machine_force_close")
    except Rejection as r:
        return _machine_result(world, req, accepted=False, error_code=r.error_code,
                               reason=r.reason, next_action=r.next_action, eq=eq,
                               cycle_id=cycle["cycleId"],
                               po=cycle["productionOrderDocumentNumber"],
                               correlation=cycle["cycleId"])
    if not cycle["active"]:
        return _machine_result(world, req, action="Finished", eq=eq,
                               cycle_id=cycle["cycleId"],
                               po=cycle["productionOrderDocumentNumber"],
                               already_finished=True, force_closed=cycle["forceClosed"],
                               approver=approver, next_action=_finish_next_action(cycle),
                               correlation=cycle["cycleId"])
    _apply_finish(world, log, eq, cycle, forced=True)
    return _machine_result(world, req, action="ForceClosed", eq=eq,
                           cycle_id=cycle["cycleId"],
                           po=cycle["productionOrderDocumentNumber"],
                           collection_id=cycle["collectionId"],
                           mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                           run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                           force_closed=True, approver=approver,
                           next_action=_finish_next_action(cycle),
                           correlation=cycle["cycleId"])
```

- [ ] **Step 2: Smoke-test the mixer→production happy path in-process**

```bash
python -c "
from selftest import DirectHandheld
hh = DirectHandheld()
r,_ = hh.request('login_requested', {'username':'operator1','password':'pass'}, session='', schema='4.0')
hh.session = r['operatorSessionId']
r,_ = hh.request('mixing_overview_requested', schema='4.0')
assert r['accepted'] and len(r['equipment']) == 47 and r['readyMixes'] == [], r.get('errorCode')
r,_ = hh.request('mixing_overview_requested', {'mixingArea':'Atlantis'}, schema='4.0')
assert not r['accepted'] and r['errorCode'] == 'invalid_mixing_area', r
r,_ = hh.request('machine_cycle_start_requested',
                 {'machineCode':'MXR-01','productionOrderDocumentNumber':'510019068',
                  'collectionId':'COL_999999'}, schema='4.0')
assert not r['accepted'] and r['errorCode'] == 'source_not_found' and 'areaStatus' in r, r
r,_ = hh.request('machine_cycle_start_requested',
                 {'machineCodes':['MXR-01'],'productionOrderDocumentNumber':'510019068'}, schema='4.0')
assert r['errorCode'] == 'legacy_request_shape', r
print('MIXING HANDLERS SMOKE OK')
hh.close()
"
```

Expected: `MIXING HANDLERS SMOKE OK`. (Full flow coverage lands in Task 5's selftest, which can complete collections.)

- [ ] **Step 3: Commit**

```bash
git add tools/backend-sim/handlers/mixing.py
git commit -m "feat(sim): v4 mixing handlers — overview, family-dispatched start, finish, force-close"
```

---
### Task 5: Selftest rewritten for v4 + README refresh

**Files:**
- Rewrite: `tools/backend-sim/selftest.py` (keep `Handheld`, `_FakeMsg`, `_FakePublishResult`, `_FakeClient`, `DirectHandheld` classes as-is except the one default noted below; replace `main()` wholesale)
- Modify: `tools/backend-sim/README.md`

**Interfaces:**
- Consumes: everything Tasks 1–4 produced.
- Produces: the SP4a acceptance gate — `python selftest.py --direct` exits 0 with every v4 check passing.

- [ ] **Step 1: Flip the request default schema**

In `Handheld.request`, change the parameter default `schema="3.0"` to `schema="4.0"`.

- [ ] **Step 2: Replace `main()` with the v4 flow**

```python
def collect_all(hh, col):
    """Drive one collection of job 510019068 from Collecting to ReadyForMixing:
    bag scans, bulk direct weight, over-tolerance approval, short-bag waiver.
    Returns the final ingredient_scan_result."""
    def scan(fields, **kw):
        f = {"collectionId": col, "correlationKey": col}
        f.update(fields)
        return hh.request("ingredient_scan_requested", f, **kw)

    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "full", "bagCount": 22})
    check(r["accepted"], f"[{col}] 22 full bags HD WHITE", json.dumps(r)[:200])
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "1/2", "bagCount": 2})
    check(r["accepted"] and r["overCollectionToleranceBags"] == 1.0,
          f"[{col}] within-tolerance top-up; tolerance echoed")
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000002",
                 "requestedMaterialCode": "1600000217", "quantity": 1671.147})
    check(r["accepted"], f"[{col}] bulk line by direct weight")
    r, p = scan({"palletRfidTag": "300833B2DDD901400000000C",
                 "requestedMaterialCode": "1600000070", "quantity": 600.0})
    check(not r["accepted"] and r["requiresManagerApproval"],
          f"[{col}] over-tolerance rejected pending approval")
    retry = {k: v for k, v in p.items()
             if k not in ("messageId", "timestampUtc", "schemaVersion",
                          "deviceId", "operatorSessionId")}
    retry.update({"managerUsername": "manager1", "managerPassword": "secret",
                  "auditReason": "Verified spillage allowance."})
    r, _ = hh.request("ingredient_scan_requested", retry)
    check(r["accepted"] and r["approverUserId"] == "OP-012",
          f"[{col}] approved retry (new messageId) accepted")
    for tag, mat, qty in (("300833B2DDD9014000000009", "1500000326", 69.631),
                          ("300833B2DDD901400000000A", "1600000233", 278.524),
                          ("300833B2DDD9014000000008", "1600000309", 557.049)):
        r, _ = scan({"palletRfidTag": tag, "requestedMaterialCode": mat, "quantity": qty})
        check(r["accepted"], f"[{col}] {mat} collected")
    r, _ = scan({"requestedMaterialCode": "1500000331", "shortBagCount": 1,
                 "managerUsername": "manager1", "managerPassword": "secret",
                 "auditReason": "One damaged bag unavailable."})
    check(r["accepted"] and r["collectionStatus"] == "ReadyForMixing"
          and r["nextAction"] == "start_mixing",
          f"[{col}] final waiver -> ReadyForMixing + start_mixing", json.dumps(r)[:300])
    return r


def load_collection(hh, job):
    r, _ = hh.request("job_card_load_requested",
                      {"jobCardNumber": job, "correlationKey": job})
    check(r["accepted"] and r["collectionId"].startswith("COL_")
          and "hoppers" not in r and r["collectionStatus"] == "Collecting",
          "bom_loaded: new v4 collection, no hoppers board", json.dumps(r)[:200])
    return r["collectionId"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="mqtt.sysone.co.za")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--direct", action="store_true",
                    help="run the simulator in-process (no broker required)")
    args = ap.parse_args()
    hh = DirectHandheld() if args.direct else Handheld(args.host, args.port)
    job = "510019068"

    print("== negative probes before login ==")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000001"}, session="nope")
    check(not r["accepted"] and r["errorCode"] == "session_required"
          and r["nextAction"] == "login",
          "request without session -> session_required + nextAction login")

    print("== login ==")
    r, _ = hh.request("login_requested", {"username": "operator1", "password": "wrong"},
                      session="")
    check(not r["accepted"] and r["errorCode"] == "permission_denied", "wrong password rejected")
    r, _ = hh.request("login_requested", {"username": "operator1", "password": "pass"},
                      session="")
    check(r["accepted"] and r["sessionState"] == "Active" and r["operatorSessionId"]
          and r["schemaVersion"] == "4.0",
          "login accepted, Active session, 4.0 envelope")
    hh.session = r["operatorSessionId"]

    print("== envelope & §12 compatibility boundary ==")
    r, _ = hh.request("mixing_overview_requested", schema="2.0")
    check(not r["accepted"] and r["errorCode"] == "unsupported_schema",
          "schema 2.0 -> unsupported_schema")
    r, _ = hh.request("pallet_lookup_requested", {"palletRfidTag": "NO_SUCH_TAG"}, schema="3.0")
    check(r["accepted"] and r["found"] is False,
          "schema 3.0 accepted for a capture action (§12)")
    r, _ = hh.request("mixing_overview_requested", schema="3.0")
    check(not r["accepted"] and r["errorCode"] == "unsupported_schema",
          "schema 3.0 rejected for a mixing action")
    r, _ = hh.request("mixing_overview_requested", timestamp=now_iso(-3600))
    check(not r["accepted"] and r["errorCode"] == "message_expired",
          "1h-old timestamp -> message_expired")
    r, _ = hh.request("mixing_overview_requested", device="handheld_other")
    check(not r["accepted"] and r["errorCode"] == "device_mismatch",
          "payload/topic device mismatch -> device_mismatch")

    print("== strict replay on a 4.0 topic (deliberately beyond the real backend) ==")
    r1, p1 = hh.request("mixing_overview_requested")
    check(r1["accepted"], "mixing overview accepted")
    hh.send_raw("mixing_overview_requested", p1)
    r2 = hh.await_response(p1["messageId"])
    check(r2["messageId"] == r1["messageId"],
          "identical replay returns the STORED response (same server messageId)")
    p_mut = dict(p1)
    p_mut["correlationKey"] = "MUTATED-BODY"
    hh.send_raw("mixing_overview_requested", p_mut)
    r3_ = hh.await_response(p1["messageId"])
    check(not r3_["accepted"] and r3_["errorCode"] == "message_id_reused",
          "same messageId + different body -> message_id_reused (4.0 path)")

    print("== retired-topic guard ==")
    r, _ = hh.request("hopper_overview_requested")
    check(r["_topic"].endswith("/res/workflow_upgrade_required")
          and not r["accepted"] and r["errorCode"] == "client_upgrade_required"
          and r["nextAction"] == "upgrade_reader_for_mixing",
          "retired v3 topic -> client_upgrade_required on workflow_upgrade_required")

    print("== pallet lookup & holding recovery ==")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004"})
    check(r["accepted"] and r["palletState"] == "AtStation1" and r["recoverable"]
          and not r["usable"] and r["nextAction"] == "recover_holding",
          "AtStation1 pallet: recoverable, nextAction recover_holding")
    r, _ = hh.request("holding_recovery_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004",
                       "auditReason": "Missed door read; pallet is physically here."})
    check(r["accepted"] and r["palletState"] == "Holding" and r["usable"],
          "recovery -> Holding, usable")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000003"})
    check(r["accepted"] and r["blocked"] and not r["usable"] and not r["recoverable"],
          "blocked Holding pallet: not usable, not recoverable")

    print("== capture: collection 1 (Main) ==")
    r, _ = hh.request("open_sap_job_cards_requested", {"refreshFromSap": True})
    check(r["accepted"] and any(j["jobCardNumber"] == job for j in r["jobs"]),
          f"open SAP job cards contains {job}")
    col1 = load_collection(hh, job)
    r, _ = hh.request("ingredient_scan_requested",
                      {"collectionId": col1,
                       "palletRfidTag": "300833B2DDD9014000000001",
                       "requestedMaterialCode": "1600000301",
                       "bagSizeOption": "full", "bagCount": 1, "quantity": 10.0})
    check(not r["accepted"], "bag fields and quantity together are rejected")
    collect_all(hh, col1)
    r, _ = hh.request("collection_resume_requested",
                      {"jobCardNumber": job, "collectionId": col1})
    check(r["accepted"] and r["resumed"] and r["nextAction"] == "start_mixing",
          "resume of a ReadyForMixing collection -> start_mixing")

    print("== mixing overview ==")
    r, _ = hh.request("mixing_overview_requested")
    check(r["accepted"] and len(r["equipment"]) == 47 and r["activeCycles"] == []
          and r["nextAction"] == "select_collection_mix_or_machine",
          "overview (all areas): 47 equipment, no active cycles")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "JandiBulkMixing"})
    check(r["accepted"] and all(e["mixingArea"] == "JandiBulkMixing" for e in r["equipment"])
          and len(r["equipment"]) == 5,
          "overview filtered to JANDI: 5 equipment")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "Atlantis"})
    check(not r["accepted"] and r["errorCode"] == "invalid_mixing_area",
          "unknown area -> invalid_mixing_area")

    print("== mixer start (Main) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "machineCodes": ["MXR-01"], "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "legacy_request_shape",
          "v3 array field present -> legacy_request_shape")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "NOPE-99", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "unknown_or_disabled_equipment",
          "unknown machine -> unknown_or_disabled_equipment")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-25", "productionOrderDocumentNumber": job,
                       "mixBatchIds": ["MIX_000001"]})
    check(not r["accepted"] and r["errorCode"] == "unknown_or_disabled_equipment",
          "disabled machine -> unknown_or_disabled_equipment")
    r, _ = hh.request("machine_cycle_start_requested", {"productionOrderDocumentNumber": job})
    check(not r["accepted"] and r["errorCode"] == "validation_failed",
          "missing machineCode -> validation_failed")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": col1, "correlationKey": col1})
    check(r["accepted"] and r["action"] == "Started"
          and r["mixBatchId"].startswith("MIX_") and r["cycleId"].startswith("CYC_")
          and r["productionRunId"] is None
          and r["sapIssueQueued"] is False and r["sapProductionOrderChanged"] is False
          and r["nextAction"] == "scan_same_machine_to_finish"
          and r["areaStatus"]["accepted"] is True,
          "mixer start: MIX_/CYC_ minted, SAP flags false, areaStatus embedded",
          json.dumps(r)[:300])
    mix1, cyc1 = r["mixBatchId"], r["cycleId"]
    busy = next(e for e in r["areaStatus"]["equipment"] if e["machineCode"] == "MXR-01")
    check(busy["status"] == "InUse" and not busy["isAvailable"],
          "areaStatus shows the started mixer InUse")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "source_already_assigned",
          "second claim of a claimed collection rejected")
    col2 = load_collection(hh, job)
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(not r["accepted"] and r["errorCode"] == "source_not_ready",
          "Collecting collection -> source_not_ready")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(not r["accepted"] and r["errorCode"] == "equipment_in_use",
          "busy mixer -> equipment_in_use")

    print("== finish (Main) ==")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-02", "cycleId": cyc1})
    check(not r["accepted"] and r["errorCode"] == "cycle_mismatch",
          "finish on the wrong machine -> cycle_mismatch")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1, "correlationKey": cyc1})
    check(r["accepted"] and r["action"] == "Finished" and not r["alreadyFinished"],
          "mixer finish accepted")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1})
    check(r["accepted"] and r["alreadyFinished"],
          "re-finish is an accepted idempotent no-op (alreadyFinished true)")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "MainMixingRoom"})
    ready = [m for m in r["readyMixes"] if m["mixBatchId"] == mix1]
    check(len(ready) == 1 and ready[0]["status"] == "ReadyForProduction"
          and "EXT-03" in ready[0]["validNextMachineCodes"],
          "finished mix is ReadyForProduction with Main extruders as valid next")

    print("== production run + accumulation (Main) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix1], "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "validation_failed",
          "collection id in a downstream start rejected")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": ["MIX_999999"]})
    check(not r["accepted"] and r["errorCode"] == "source_not_found",
          "unknown mix -> source_not_found")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix1]})
    check(r["accepted"] and r["cycleId"].startswith("RUN_")
          and r["cycleId"] == r["productionRunId"],
          "production start: cycleId == productionRunId (RUN_ shape)")
    run1 = r["productionRunId"]
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-04", "productionOrderDocumentNumber": "510018531",
                       "mixBatchIds": [mix1]})
    check(not r["accepted"] and r["errorCode"] == "job_card_mismatch",
          "mix from another JC -> job_card_mismatch")
    collect_all(hh, col2)
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(r["accepted"], "second mixer start on second collection")
    mix2, cyc2 = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-02", "cycleId": cyc2})
    check(r["accepted"], "second mixer finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix2]})
    check(r["accepted"] and r["productionRunId"] == run1
          and r["affectedMixBatchIds"] == [mix2],
          "same-JC start on the busy machine accumulates into the active run")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "EXT-03", "cycleId": run1})
    check(r["accepted"] and sorted(r["affectedMixBatchIds"]) == sorted([mix1, mix2]),
          "run finish consumes both accumulated mixes")

    print("== JANDI drum gate ==")
    col3 = load_collection(hh, job)
    collect_all(hh, col3)
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-MIX-01", "productionOrderDocumentNumber": job,
                       "collectionId": col3})
    check(r["accepted"], "JANDI mixer start")
    jmix, jcyc = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-MIX-01", "cycleId": jcyc})
    check(r["accepted"], "JANDI mixer finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-01", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(not r["accepted"] and r["errorCode"] == "invalid_route",
          "JANDI mix on a Main extruder -> invalid_route")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-04", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(not r["accepted"] and r["errorCode"] == "drum_cycle_required",
          "JANDI 4 before the drum -> drum_cycle_required")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-DRUM-01", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(r["accepted"], "drum start on the completed JANDI mix")
    dcyc = r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-DRUM-01", "cycleId": dcyc})
    check(r["accepted"], "drum finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-04", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(r["accepted"], "JANDI 4 unblocked after its exact drum cycle finished")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-04", "cycleId": r["cycleId"]})
    check(r["accepted"], "JANDI 4 run finish")

    print("== Rajoo layer inputs + force-close ==")
    col4 = load_collection(hh, job)
    collect_all(hh, col4)
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "RAJ-GM-01", "productionOrderDocumentNumber": job,
                       "collectionId": col4,
                       "layerInputs": [{"materialCode": "1600000301",
                                        "dosingQuantity": 999999.0}]})
    check(not r["accepted"] and r["errorCode"] == "invalid_layer_inputs",
          "dose above collected quantity -> invalid_layer_inputs")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "RAJ-GM-01", "productionOrderDocumentNumber": job,
                       "collectionId": col4,
                       "layerInputs": [{"materialCode": "1600000301", "dosingQuantity": 12.5},
                                       {"materialCode": "1600000217", "dosingQuantity": 3.0}]})
    check(r["accepted"], "Rajoo mixer start with valid doses")
    rcyc = r["cycleId"]
    r, _ = hh.request("machine_cycle_force_close_requested",
                      {"machineCode": "RAJ-GM-01", "cycleId": rcyc,
                       "auditReason": "Mixer fault."})
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "force-close without credentials -> permission_denied")
    r, _ = hh.request("machine_cycle_force_close_requested",
                      {"machineCode": "RAJ-GM-01", "cycleId": rcyc,
                       "managerUsername": "manager1", "managerPassword": "secret",
                       "auditReason": "Mixer fault; releasing the cycle for maintenance."})
    check(r["accepted"] and r["forceClosed"] and r["approverUserId"] == "OP-012"
          and r["approverRole"] == "Manager",
          "force-close approved: forceClosed true, approver identity echoed")

    print("== logout ==")
    r, _ = hh.request("reader_logout_requested")
    check(r["accepted"] and r["sessionState"] == "Closed", "logout closes session")
    r, _ = hh.request("mixing_overview_requested")
    check(not r["accepted"] and r["errorCode"] == "session_required",
          "request on closed session -> session_required")

    hh.close()
    print(f"\nALL {CHECKS['passed']} CHECKS PASSED — simulator is v4.0 contract-conformant")
    return 0
```

Also update the module docstring's first paragraph to mention contract v4.0.

- [ ] **Step 3: Run it**

```bash
python selftest.py --direct
```

Expected: `ALL <n> CHECKS PASSED — simulator is v4.0 contract-conformant`, exit 0. Debug any failure via the run's `sim.log` (validation narrative) and `wire.jsonl` before touching handler code — the three-layer logging exists exactly for this.

- [ ] **Step 4: Update the README**

In `tools/backend-sim/README.md`: replace mentions of contract v3 / hopper / pre-mix / allocation flows with the v4.0 vocabulary (five mixing areas, `mixing_overview_requested`, family-dispatched `machine_cycle_start_requested`, retired-topic guard on `res/workflow_upgrade_required`, §12 3.0-capture-only compat, strict replay on all paths). Keep the run instructions (`python sim.py`, `python selftest.py --direct`) and logging description as they are.

- [ ] **Step 5: Commit**

```bash
git add tools/backend-sim/selftest.py tools/backend-sim/README.md
git commit -m "feat(sim): v4 selftest — five-area mixing flows, drum gate, strict replay, retired guard"
```

---
### Task 6: App schema bump to 4.0, v4 vocabulary, dead-token sweep

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttSchema.kt`
- Rewrite: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/HopperBoard.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`, `.../dto/JobCardMessages.kt`, `.../domain/model/ProductionOrder.kt`, `.../ui/mixing/MixingViewModel.kt:482`, `.../ui/mixing/IngredientScanScreen.kt` (comments/copy), `.../navigation/AppNavGraph.kt:65-67`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt`, `MqttTopicsTest.kt`, `MqttRequestCorrelationTest.kt`, `dto/ResponseEnvelopeTest.kt`

**Interfaces:**
- Produces: `MqttSchema.VERSION == "4.0"`; `NextAction.START_MIXING`, `NextAction.UPGRADE_READER_FOR_MIXING`, `NextAction.SELECT_COLLECTION_MIX_OR_MACHINE`; `ErrorCode.CLIENT_UPGRADE_REQUIRED` plus the full §10 mixing code set. Consumed by Tasks 7 and 10.
- Removes: `HopperBoardEntry`/`HopperState`, `hoppers` fields, v3-only vocabulary (`CHOOSE_DESTINATION`, `ASSIGN_OR_FINISH_HOPPER`, `ALLOCATE_PREMIX`, `REVIEW_ALLOCATION`, `COMPLETE_STATION2_WORK`, `MACHINE_UNAVAILABLE`, `NOT_FOUND`→kept? **kept** — capture handlers still send `not_found`).

- [ ] **Step 1: Update the failing envelope tests first (TDD)**

- `RequestEnvelopeTest.kt:32` and `:75`: `assertEquals("3.0", ...)` → `assertEquals("4.0", ...)`.
- `MqttTopicsTest.kt:84`: `assertEquals("3.0", MqttSchema.VERSION)` → `assertEquals("4.0", MqttSchema.VERSION)`.
- `MqttRequestCorrelationTest.kt:62`: `"schemaVersion":"3.0"` → `"schemaVersion":"4.0"` in the `respond()` template.
- `ResponseEnvelopeTest.kt:19` and `:34`: `"3.0"` → `"4.0"`.

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: FAIL (`MqttSchema.VERSION` still "3.0").

- [ ] **Step 2: Bump the constant**

`MqttSchema.kt` becomes:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The one place the wire schema version is defined. Contract v4.0 rejects any request whose
 * schemaVersion is not exactly "4.0" with errorCode `unsupported_schema` (schema 3.0 survives
 * server-side only for capture actions during cutover — this app never sends it).
 */
object MqttSchema {
    const val VERSION = "4.0"
}
```

Run the same tests. Expected: PASS.

- [ ] **Step 3: Rewrite the vocabulary for v4**

`MqttVocabulary.kt` becomes:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Contract v4.0 `errorCode`. A value class rather than an enum: codes are shared across message
 * families and message-specific codes may also arrive. An unknown code must pass through intact
 * rather than fail the parse.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = ErrorCode("invalid_json")
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        val UNSUPPORTED_SCHEMA = ErrorCode("unsupported_schema")
        val DEVICE_MISMATCH = ErrorCode("device_mismatch")
        val DEVICE_NOT_CONFIGURED = ErrorCode("device_not_configured")
        val MESSAGE_EXPIRED = ErrorCode("message_expired")
        val SESSION_REQUIRED = ErrorCode("session_required")
        val PERMISSION_DENIED = ErrorCode("permission_denied")
        val NOT_FOUND = ErrorCode("not_found")
        val STATE_CONFLICT = ErrorCode("state_conflict")
        val VALIDATION_FAILED = ErrorCode("validation_failed")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        val SERVICE_UNAVAILABLE = ErrorCode("service_unavailable")

        // v4.0 §10 — stable Mixing codes. SP4a consumes CLIENT_UPGRADE_REQUIRED;
        // SP4b's mixing UI branches on the rest.
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        val INVALID_MIXING_AREA = ErrorCode("invalid_mixing_area")
        val LEGACY_REQUEST_SHAPE = ErrorCode("legacy_request_shape")
        val UNKNOWN_OR_DISABLED_EQUIPMENT = ErrorCode("unknown_or_disabled_equipment")
        val EQUIPMENT_IN_USE = ErrorCode("equipment_in_use")
        val CYCLE_MISMATCH = ErrorCode("cycle_mismatch")
        val SOURCE_NOT_FOUND = ErrorCode("source_not_found")
        val SOURCE_NOT_READY = ErrorCode("source_not_ready")
        val SOURCE_ALREADY_ASSIGNED = ErrorCode("source_already_assigned")
        val JOB_CARD_MISMATCH = ErrorCode("job_card_mismatch")
        val INVALID_ROUTE = ErrorCode("invalid_route")
        val DRUM_CYCLE_REQUIRED = ErrorCode("drum_cycle_required")
        val INVALID_LAYER_INPUTS = ErrorCode("invalid_layer_inputs")
    }
}

/**
 * Contract v4.0 `nextAction`. Guidance for the scanner UI, never authorization. An empty value
 * means "no forced navigation".
 */
@JvmInline
value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val SCAN_JOB_CARD = NextAction("scan_job_card")
        val ACTIVE_JOB_CARDS = NextAction("active_job_cards")
        val SCAN_INGREDIENT = NextAction("scan_ingredient")
        val RECOVER_HOLDING = NextAction("recover_holding")
        val RETRY_WITH_MANAGER_APPROVAL = NextAction("retry_with_manager_approval")
        val START_MIXING = NextAction("start_mixing")
        val SELECT_COLLECTION_MIX_OR_MACHINE = NextAction("select_collection_mix_or_machine")
        val SCAN_SAME_MACHINE_TO_FINISH = NextAction("scan_same_machine_to_finish")
        val UPGRADE_READER_FOR_MIXING = NextAction("upgrade_reader_for_mixing")
    }
}
```

- [ ] **Step 4: Remove the hopper board and other retired tokens**

1. `git rm app/src/main/java/com/ppnam/station2aa/domain/model/HopperBoard.kt`
2. `IngredientMessages.kt`: delete `import com.mitas.ppnam.station2aa.domain.model.HopperBoardEntry` and the `hoppers` field (with its doc line) from `IngredientScanResultResponse`.
3. `JobCardMessages.kt`: delete the same import and the `hoppers` field from `BomLoadedResponse`; change the `collectionStatus` doc to `/** Collecting | ReadyForMixing | Mixing | Cancelled */`; rename `IngredientCollectionCancelResultResponse.preMixId` → `collectionId` and `preMixStatus` → `collectionStatus` (grep `preMixId`/`preMixStatus` under `app/src` — expected: no other usages; fix any found).
4. `ProductionOrder.kt:10`: doc becomes `/** Collecting | ReadyForMixing | Mixing | Cancelled. */`
5. `MixingViewModel.kt:482-485`: reword the comment to v4 — replace the three lines starting `// Waits for premix_cancel_result ...` with:

```kotlin
    // Waits for ingredient_collection_cancel_result before touching any local state — a
    // rejected cancel (e.g. the collection was already claimed by a mixer, or the manager
    // approval was denied) must leave the job exactly as it was.
```

6. `IngredientScanScreen.kt:94`: replace `"...ingredients scanned, hopper assigned, SAP issue, etc)..."` text with `"This closes the job card if it hasn't had any activity yet (ingredients scanned, mixing started, etc). You'll be notified if it can't be cancelled."`
7. `IngredientScanScreen.kt:786`: text becomes `"Collection complete. Mixing arrives in the next update."`
8. `IngredientScanScreen.kt:805-813`: replace the TODO comment + button with:

```kotlin
                    // SP4b wires this into the five-area Mixing flow (mixing_overview_requested →
                    // machine_cycle_start_requested). Permanently disabled until that flow exists;
                    // do not re-enable based on allIngredientsSatisfied.
                    Button(
                        onClick = onProceedToMixing,
                        enabled = false,
                        modifier = Modifier.weight(2f).height(56.dp)
                    ) {
                        Text("Mixing available in the next update")
                    }
```

9. Rename the screen parameter `onProceedToHopperScan: () -> Unit` → `onProceedToMixing: () -> Unit` (`IngredientScanScreen.kt:29`) and update `AppNavGraph.kt:65-67`:

```kotlin
                    // SP4b wires this into the five-area Mixing flow. The button that invokes
                    // this is permanently disabled in IngredientScanScreen until then.
                    onProceedToMixing = { },
```

- [ ] **Step 5: Dead-token grep (the SP3-close gate, v4 edition)**

```bash
grep -rniE "hopper|preMix|premix|allocation_|choose_destination|assign_or_finish|allocate_premix|review_allocation|complete_station2|machineCodes|collectionIds|ReadyForRouting" app/src/main/java app/src/test/java
```

Expected: **no matches**. Fix any stragglers (reword comments, delete dead code) until clean. Known test-fixture hits to rename while sweeping:

- `MixingViewModelTest.kt`: `collectionId = "premix-1"` → `"COL_000001"`; `sessionWithActions("cancel_premix")` → `sessionWithActions("ingredient_collection_cancel")`; test name `` `lookupJob forwards preMixId to the use case` `` → `` `lookupJob forwards collectionId to the use case` `` (and its `"premix-1"` literals → `"COL_000001"`).
- `MixingUseCaseTest.kt`: every `collectionId = "premix-1"` / `"premix-1"` assertion literal → `"COL_000001"`.

- [ ] **Step 6: Full unit test run + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
```

Expected: PASS (compile errors here mean a missed `hoppers`/`preMix` usage — fix them).

```bash
git add -A app/src
git commit -m "feat(app): schema 4.0 bump, v4 vocabulary, retired v3 token sweep"
```

---

### Task 7: Gap 3 — enriched Accepted scan outcome + ReadyForMixing placeholder

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt` (add `collectionStatus`)
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt` (both Accepted mappings)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt` (`handleScanOutcome`)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt:783`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`, `.../ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Produces: `IngredientScanOutcome.Accepted(updatedLines, collectionSummary: String, collectionStatus: String, overCollectionToleranceBags: Double?, nextAction: NextAction)` — Task 8's ViewModel work and SP4b both consume this shape. `hoppers[]` is gone from the contract, so that half of the old gap closes by deletion (already done in Task 6).

- [ ] **Step 1: Write the failing use-case test**

Add to `MixingUseCaseTest.kt`:

```kotlin
    @Test
    fun `scanIngredient Accepted carries summary, status, tolerance and nextAction through the boundary`() = runTest {
        val response = IngredientScanResultResponse(
            collectionId = "COL_000001",
            collectionStatus = "ReadyForMixing",
            overCollectionToleranceBags = 1.0,
            collectionSummary = CollectionSummaryResponse(summary = "All products collected."),
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", requiredQuantity = 50.0, collectedQuantity = 50.0)
            )
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(),
                eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.START_MIXING))

        val outcome = useCase.scanIngredient(
            "COL_000001", "TAG-1", "MAT-001", bagSizeOption = "full", bagCount = 2.0
        ).getOrThrow() as IngredientScanOutcome.Accepted

        assertEquals("All products collected.", outcome.collectionSummary)
        assertEquals("ReadyForMixing", outcome.collectionStatus)
        assertEquals(1.0, outcome.overCollectionToleranceBags!!, 0.0)
        assertEquals(NextAction.START_MIXING, outcome.nextAction)
    }
```

Note: this test already uses the Task 8 parameter order (`requestedMaterialCode` third, bag fields named). Until Task 8 lands, write the call as the current signature `useCase.scanIngredient("COL_000001", "TAG-1", "full", 2.0, "MAT-001")` and update it in Task 8's mechanical sweep.

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.MixingUseCaseTest"`
Expected: FAIL (compile — `Accepted` has no such fields).

- [ ] **Step 2: Enrich the DTO and the outcome**

`IngredientScanResultResponse`: add below `collectionId`:

```kotlin
    /** Collecting | ReadyForMixing | Mixing | Cancelled — refreshed on every scan result. */
    val collectionStatus: String = "",
```

`IngredientScanOutcome.kt` — replace the `Accepted` class and add the import:

```kotlin
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
```

```kotlin
    /**
     * The scan/waiver was applied. Carries the refreshed collection picture through the use-case
     * boundary so the UI never re-derives readiness locally: Station 2's own summary line, the
     * collection status (ReadyForMixing gates the SP4b mixing entry point), the tolerance the
     * server actually applied, and its navigation hint.
     */
    data class Accepted(
        val updatedLines: List<BomLine>,
        val collectionSummary: String,
        val collectionStatus: String,
        val overCollectionToleranceBags: Double?,
        val nextAction: NextAction,
    ) : IngredientScanOutcome()
```

- [ ] **Step 3: Map it in both use-case paths**

In `MixingUseCase.scanIngredient` and `waiveShortBags`, the `MqttOutcome.Accepted` branch becomes (identical in both, keeping each one's existing filter comment):

```kotlin
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    updatedLines = outcome.body.ingredients
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() },
                    collectionSummary = outcome.body.collectionSummary.summary,
                    collectionStatus = outcome.body.collectionStatus,
                    overCollectionToleranceBags = outcome.body.overCollectionToleranceBags,
                    nextAction = outcome.nextAction,
                )
            )
```

Run the Step 1 test. Expected: PASS.

- [ ] **Step 4: Propagate into the order + placeholder**

`MixingViewModel.handleScanOutcome`, `Accepted` branch — replace the first line:

```kotlin
                val updatedOrder = order.copy(
                    lines = outcome.updatedLines,
                    collectionStatus = outcome.collectionStatus,
                    summary = outcome.collectionSummary,
                )
```

`IngredientScanScreen.kt:783` — replace:

```kotlin
                if (allIngredientsSatisfied && uiState is MixingUiState.OrderLoaded) {
```

with:

```kotlin
                val readyForMixing = (uiState as? MixingUiState.OrderLoaded)
                    ?.order?.collectionStatus == "ReadyForMixing"
                if ((readyForMixing || allIngredientsSatisfied) && uiState is MixingUiState.OrderLoaded) {
```

- [ ] **Step 5: ViewModel test**

Add to `MixingViewModelTest.kt`:

```kotlin
    @Test
    fun `an accepted scan refreshes the order's status and summary from the server`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                updatedLines = sampleOrder.lines,
                collectionSummary = "All products collected.",
                collectionStatus = "ReadyForMixing",
                overCollectionToleranceBags = 1.0,
                nextAction = com.mitas.ppnam.station2aa.data.mqtt.NextAction.START_MIXING,
            )))
        viewModel.confirmIngredientScan("TAG-1", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value as MixingUiState.OrderLoaded
        assertEquals("ReadyForMixing", state.order.collectionStatus)
        assertEquals("All products collected.", state.order.summary)
    }
```

(Until Task 8 lands, match the current mock signature: `scanIngredient(any(), any(), any(), any(), any())` with the five current parameters; Task 8's sweep updates it.)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: PASS. Then run the full unit suite — existing `Accepted(...)` constructions in tests need the new named fields added; give them `collectionSummary = ""`, `collectionStatus = "Collecting"`, `overCollectionToleranceBags = null`, `nextAction = NextAction.SCAN_INGREDIENT` unless the test asserts otherwise.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(app): enriched Accepted scan outcome — status, summary, tolerance, nextAction (SP3 gap 3)"
```

---

### Task 8: Gap 1 — quantity-only scan path for bulk lines

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt` (`scanIngredient` signature + approval rebuild)
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/IngredientScanOutcome.kt` (`NeedsManagerApproval` + `quantity`)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt` (state, dispatch, confirm paths)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt` (quantity dialog)
- Test: `MixingUseCaseTest.kt`, `MixingViewModelTest.kt`

**Interfaces:**
- Produces:
  - `MixingUseCase.scanIngredient(collectionId: String, palletRfidTag: String, requestedMaterialCode: String, bagSizeOption: String? = null, bagCount: Double? = null, quantity: Double? = null, managerUsername: String? = null, managerPassword: String? = null, auditReason: String? = null): Result<IngredientScanOutcome>` — bag fields and `quantity` are mutually exclusive on the wire; both-or-neither fails closed before publishing.
  - `IngredientScanOutcome.NeedsManagerApproval(collectionId, palletRfidTag, requestedMaterialCode, bagSizeOption: String?, bagCount: Double?, quantity: Double?, reason)`.
  - `MixingUiState.EnteringQuantityDetails(palletTag: String)`; `MixingViewModel.confirmQuantityScan(palletTag: String, quantity: Double)`.

- [ ] **Step 1: Failing use-case tests**

Add to `MixingUseCaseTest.kt`:

```kotlin
    @Test
    fun `scanIngredient with quantity sends quantity and no bag fields`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(),
                eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(), NextAction.SCAN_INGREDIENT))

        useCase.scanIngredient("COL_1", "TAG-1", "MAT-BULK", quantity = 123.4)

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), any(), eq(IngredientScanResultResponse::class.java))
        }.firstValue as IngredientScanPayload
        assertEquals(123.4, payload.quantity!!, 0.0)
        assertNull(payload.bagSizeOption)
        assertNull(payload.bagCount)
    }

    @Test
    fun `scanIngredient refuses both shapes or neither without touching the wire`() = runTest {
        val both = useCase.scanIngredient("COL_1", "TAG-1", "MAT-1",
            bagSizeOption = "full", bagCount = 1.0, quantity = 5.0)
        val neither = useCase.scanIngredient("COL_1", "TAG-1", "MAT-1")
        assertTrue(both.isFailure)
        assertTrue(neither.isFailure)
        verifyNoInteractions(mockMqtt)
    }
```

Run: expected FAIL (no `quantity` parameter).

- [ ] **Step 2: Rework the use case**

`MixingUseCase.scanIngredient` becomes:

```kotlin
    suspend fun scanIngredient(
        collectionId: String,
        palletRfidTag: String,
        requestedMaterialCode: String,
        bagSizeOption: String? = null,
        bagCount: Double? = null,
        quantity: Double? = null,
        managerUsername: String? = null,
        managerPassword: String? = null,
        auditReason: String? = null,
    ): Result<IngredientScanOutcome> {
        // The two capture shapes are mutually exclusive on the wire (contract §5): bag
        // fields for bagged stock, quantity for direct weight. Fail closed before publishing.
        val bagShape = bagSizeOption != null || bagCount != null
        if (bagShape == (quantity != null)) {
            return Result.failure(IllegalArgumentException(
                "Send either bagSizeOption+bagCount or quantity, never both or neither."))
        }
        if (bagShape && (bagSizeOption == null || bagCount == null)) {
            return Result.failure(IllegalArgumentException(
                "A bag scan needs both bagSizeOption and bagCount."))
        }
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                requestedMaterialCode = requestedMaterialCode,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                quantity = quantity,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )
        // ... (Accepted branch unchanged from Task 7)
```

In the `Rejected`→`NeedsManagerApproval` branch, pass the shape through:

```kotlin
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        // Rebuilt from the REQUEST — the response doesn't echo these back.
                        collectionId = collectionId,
                        palletRfidTag = palletRfidTag,
                        requestedMaterialCode = requestedMaterialCode,
                        bagSizeOption = bagSizeOption,
                        bagCount = bagCount,
                        quantity = quantity,
                        reason = outcome.reason ?: "Manager approval required",
                    )
```

`IngredientScanOutcome.NeedsManagerApproval` gains `val quantity: Double?,` after `bagCount`.

- [ ] **Step 3: Mechanical call-site sweep**

Every existing `scanIngredient(` call in main and test code moves `requestedMaterialCode` to the third position and names the bag arguments, e.g. `useCase.scanIngredient(order.collectionId, palletTag, materialCode, bagSizeOption = bagSizeOption, bagCount = bagCount)`. Compile (`.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`) and fix every error the sweep surfaces, including Task 7's two interim test call shapes and mock matchers (now nine parameters: `any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()`).

- [ ] **Step 4: ViewModel — dispatch by armed line, quantity confirm**

In `MixingViewModel`:

1. Add the state:

```kotlin
    /** Direct-weight entry for a bulk line (SP3 gap 1) — the bag picker never opens for bulk. */
    data class EnteringQuantityDetails(val palletTag: String) : MixingUiState()
```

(inside `MixingUiState`, after `EnteringBagDetails`; also extend the scan-guard comment's blocked list with `EnteringQuantityDetails`.)

2. Reshape the pending scan:

```kotlin
    private data class PendingIngredientScan(
        val palletRfidTag: String,
        val bagSizeOption: String?,
        val bagCount: Double?,
        val quantity: Double?,
        val requestedMaterialCode: String,
    )
```

3. Scan dispatch in `startListeningForPalletScans` — the `OrderLoaded/Error` branch becomes:

```kotlin
                    is MixingUiState.OrderLoaded, is MixingUiState.Error -> {
                        val palletTag = when (event) {
                            is ScanEvent.RfidTag -> event.tagId
                            is ScanEvent.Barcode -> event.value
                        }
                        // A bulk line arms direct-weight entry; arming a bulk line for a
                        // bag scan is impossible by construction (gap 1).
                        val armedLine = armedLineNumber?.let { ln ->
                            cachedOrder?.lines?.firstOrNull { it.lineNumber == ln }
                        }
                        _uiState.value = if (armedLine != null && !armedLine.isBagged) {
                            MixingUiState.EnteringQuantityDetails(palletTag)
                        } else {
                            MixingUiState.EnteringBagDetails(palletTag)
                        }
                    }
```

4. `confirmIngredientScan` — resolve the line (not just its code), refuse a bulk line, store the bag-shaped pending scan:

```kotlin
    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
        if (line == null) {
            _supervisorError.trySend("Select a material line before scanning a pallet.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (!line.isBagged) {
            _supervisorError.trySend("${line.itemCode} is a bulk material — enter its weight instead.")
            _uiState.value = orderLoadedState(order)
            return
        }
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount, null, line.itemCode)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.collectionId, palletTag, line.itemCode,
                bagSizeOption = bagSizeOption, bagCount = bagCount)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun cancelQuantityEntry() {
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }

    fun confirmQuantityScan(palletTag: String, quantity: Double) {
        val order = cachedOrder ?: return
        val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
        if (line == null) {
            _supervisorError.trySend("Select a material line before scanning a pallet.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (line.isBagged) {
            _supervisorError.trySend("${line.itemCode} is a bagged material — scan bags instead.")
            _uiState.value = orderLoadedState(order)
            return
        }
        if (quantity <= 0.0) {
            _supervisorError.trySend("Quantity must be a positive number.")
            return
        }
        pendingScan = PendingIngredientScan(palletTag, null, null, quantity, line.itemCode)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.collectionId, palletTag, line.itemCode, quantity = quantity)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }
```

5. `submitManagerApproval` resubmit call becomes:

```kotlin
            useCase.scanIngredient(
                approval.collectionId,
                approval.palletRfidTag,
                approval.requestedMaterialCode,
                bagSizeOption = approval.bagSizeOption,
                bagCount = approval.bagCount,
                quantity = approval.quantity,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            )
```

6. `retryPendingScan` call becomes:

```kotlin
            useCase.scanIngredient(
                order.collectionId, scan.palletRfidTag, scan.requestedMaterialCode,
                bagSizeOption = scan.bagSizeOption, bagCount = scan.bagCount,
                quantity = scan.quantity,
            )
```

- [ ] **Step 5: ViewModel tests**

Add to `MixingViewModelTest.kt` (the bulk sample order):

```kotlin
    private val bulkOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "COL_1",
        lines = listOf(BomLine(lineNumber = 0, itemCode = "MAT-BULK", itemName = "LD Mix",
            requiredQty = 100.0, bagSize = null))
    )

    @Test
    fun `a scan with a bulk line armed opens quantity entry, not the bag picker`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.selectLine(0)
        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:1", java.time.Instant.now()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MixingUiState.EnteringQuantityDetails)
    }

    @Test
    fun `confirmQuantityScan sends the quantity shape for the armed bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Rejected("nope")))
        viewModel.confirmQuantityScan("EPC:1", 42.5)
        advanceUntilIdle()
        verify(mockUseCase).scanIngredient(eq("COL_1"), eq("EPC:1"), eq("MAT-BULK"),
            anyOrNull(), anyOrNull(), eq(42.5), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `confirmIngredientScan refuses a bag entry against an armed bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        viewModel.confirmIngredientScan("EPC:1", "full", 2.0)
        advanceUntilIdle()
        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: PASS.

- [ ] **Step 6: The quantity dialog**

In `IngredientScanScreen.kt`, next to the other dialog state vars add:

```kotlin
    var quantityText by rememberSaveable { mutableStateOf("") }
```

and directly after the `EnteringBagDetails` dialog block add:

```kotlin
    if (uiState is MixingUiState.EnteringQuantityDetails) {
        val palletTag = (uiState as MixingUiState.EnteringQuantityDetails).palletTag
        AlertDialog(
            onDismissRequest = { viewModel.cancelQuantityEntry() },
            title = { Text("Weight received", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Text("Bulk material — enter the exact weight received.", color = TextMuted,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (kg)") },
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
                    enabled = quantityText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        val qty = quantityText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmQuantityScan(palletTag, qty)
                        quantityText = ""
                    }
                ) { Text("Confirm Weight", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelQuantityEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }
```

- [ ] **Step 7: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
git add -A app/src
git commit -m "feat(app): quantity-only scan path for bulk lines (SP3 gap 1)"
```

---
### Task 9: Gap 2 — first-attempt waiver dialog moves into MixingUiState

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt:46-57, 356-452, 766-772`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Produces: `MixingUiState.ShortBagWaiverEntry(requestedMaterialCode: String)`; `MixingViewModel.openShortBagWaiver(requestedMaterialCode: String)` and `dismissShortBagWaiverEntry()`. The ViewModel scan guard now sees the open first-attempt waiver dialog and swallows stray RFID reads while it is open — previously it lived in local Compose state (`showWaiverDialog`) the guard could not see.
- The **rejected**-waiver dialog (`ShortBagWaiverNeedsApproval`) is already ViewModel state and stays untouched.

- [ ] **Step 1: Failing ViewModel tests**

Add to `MixingViewModelTest.kt`:

```kotlin
    @Test
    fun `openShortBagWaiver enters ShortBagWaiverEntry for a bagged line`() = runTest {
        val baggedOrder = sampleOrder.copy(lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin",
                requiredQty = 10.0, bagSize = "25.000 kg")))
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(baggedOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.openShortBagWaiver("MAT-001")
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.ShortBagWaiverEntry)
        assertEquals("MAT-001", (state as MixingUiState.ShortBagWaiverEntry).requestedMaterialCode)
        viewModel.dismissShortBagWaiverEntry()
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `a stray scan while the first-attempt waiver dialog is open is ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        val baggedOrder = sampleOrder.copy(lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin",
                requiredQty = 10.0, bagSize = "25.000 kg")))
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(baggedOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")
        vm.openShortBagWaiver("MAT-001")

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:STRAY", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue("dialog must survive a stray scan",
            vm.uiState.value is MixingUiState.ShortBagWaiverEntry)
    }
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.MixingViewModelTest"`
Expected: FAIL (no such state/functions).

- [ ] **Step 2: Add the state and the open/dismiss pair**

In `MixingUiState`, after `ShortBagWaiverNeedsApproval`:

```kotlin
    /**
     * The FIRST-ATTEMPT short-bag waiver entry dialog (SP3 gap 2). ViewModel state, not local
     * Compose state, so the scan guard sees it and swallows stray RFID reads while it is open.
     * Distinct from [ShortBagWaiverNeedsApproval], which is a REJECTED waiver being re-approved.
     */
    data class ShortBagWaiverEntry(val requestedMaterialCode: String) : MixingUiState()
```

In `MixingViewModel`, next to `submitShortBagWaiver`:

```kotlin
    /**
     * Opens the first-attempt waiver dialog for [requestedMaterialCode]. Only from OrderLoaded
     * (the dialog owns the screen; opening it over another dialog or an in-flight request would
     * fight the scan guard's whole point), and only for a real bagged line — a bulk line has no
     * bag arithmetic to waive.
     */
    fun openShortBagWaiver(requestedMaterialCode: String) {
        val order = cachedOrder ?: return
        if (_uiState.value !is MixingUiState.OrderLoaded) return
        if (order.lines.none { it.itemCode == requestedMaterialCode && it.isBagged }) return
        _uiState.value = MixingUiState.ShortBagWaiverEntry(requestedMaterialCode)
    }

    fun dismissShortBagWaiverEntry() {
        if (_uiState.value !is MixingUiState.ShortBagWaiverEntry) return
        val order = cachedOrder ?: return
        _uiState.value = orderLoadedState(order)
    }
```

Extend the scan-guard comment's blocked list with `ShortBagWaiverEntry` (the `else -> return@collect` branch already blocks it — the comment is the contract).

`submitShortBagWaiver` needs one addition so a submission from the entry dialog closes it into Loading exactly as before (no change to its body — it already sets `Loading`); nothing else.

Run the Step 1 tests. Expected: PASS.

- [ ] **Step 3: Rewire the screen**

In `IngredientScanScreen.kt`:

1. Delete the two local state vars (`:46-51`): `showWaiverDialog` and `waiverLineMaterialCode` (and the comment block explaining their `rememberSaveable` choice — the state now survives in the ViewModel). Keep `waiverShortBagCountText`, `waiverUsername`, `waiverPassword`, `waiverAuditReason`.
2. The "Short bags" button (`:766-772`) becomes:

```kotlin
                                                    TextButton(
                                                        onClick = { viewModel.openShortBagWaiver(bomLine.itemCode) }
                                                    ) { Text("Short bags", color = AmberPrimary) }
```

3. The first-attempt dialog condition (`:359`) becomes state-driven; replace `if (showWaiverDialog) {` with:

```kotlin
    if (uiState is MixingUiState.ShortBagWaiverEntry) {
        val waiverMaterialCode = (uiState as MixingUiState.ShortBagWaiverEntry).requestedMaterialCode
```

Inside the dialog: every `waiverLineMaterialCode` read becomes `waiverMaterialCode`; every `showWaiverDialog = false` becomes `viewModel.dismissShortBagWaiverEntry()` — **except** the confirm button's, which just drops the line (submitShortBagWaiver's `Loading` state closes the dialog); the confirm `enabled` check drops `waiverLineMaterialCode.isNotBlank() &&` (the state can't exist without a material code). Close the new `if` block with the extra brace the `val` line introduced.

- [ ] **Step 4: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
git add -A app/src
git commit -m "feat(app): waiver entry dialog into MixingUiState so the scan guard sees it (SP3 gap 2)"
```

---

### Task 10: Upgrade signal — `client_upgrade_required` as a blocking state

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`, `IngredientScanScreen.kt`
- Test: `MqttRequestCorrelationTest.kt`, `dto/ResponseEnvelopeTest.kt`, `MixingViewModelTest.kt`

**Interfaces:**
- Produces: `MqttRepository.upgradeRequired: StateFlow<Boolean>` — latched true the moment any response (matched or unsolicited, including `res/workflow_upgrade_required`) carries `errorCode: "client_upgrade_required"`; never reset except by process restart, because the condition ends only with a new build. `MixingViewModel.upgradeRequired` mirrors it for the UI.

- [ ] **Step 1: Failing transport test**

Add to `MqttRequestCorrelationTest.kt`:

```kotlin
    @Test
    fun `client_upgrade_required latches the upgradeRequired flag`() = runTest {
        assertTrue(!repo.upgradeRequired.value)
        val call = async {
            repo.request("machine_cycle_start_requested", "machine_cycle_result",
                EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/workflow_upgrade_required",
            """{"inResponseToMessageId":"$id","accepted":false,
                "errorCode":"client_upgrade_required",
                "nextAction":"upgrade_reader_for_mixing"}""".toByteArray()
        )
        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals(ErrorCode.CLIENT_UPGRADE_REQUIRED, outcome.errorCode)
        assertTrue(repo.upgradeRequired.value)
    }

    @Test
    fun `an UNSOLICITED upgrade rejection still latches the flag`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/workflow_upgrade_required",
            """{"inResponseToMessageId":"nobody-is-waiting","accepted":false,
                "errorCode":"client_upgrade_required"}""".toByteArray()
        )
        assertTrue(repo.upgradeRequired.value)
    }
```

And to `ResponseEnvelopeTest.kt` (backend survey: `errorCode` is omitted, not null, on the real 4.0 path):

```kotlin
    @Test
    fun `a response with errorCode omitted entirely parses as no error`() {
        val json = """{"messageId":"S2-1","inResponseToMessageId":"m-1","schemaVersion":"4.0","accepted":true}"""
        val env = Gson().fromJson(json, ResponseEnvelope::class.java)
        assertNull(env.errorCode)
        assertTrue(env.accepted)
    }
```

Run: expected FAIL (`upgradeRequired` unresolved; the envelope test may already pass — if so it stays as the regression guard).

- [ ] **Step 2: Interface + implementation**

`MqttRepository.kt` — after `clockSkewMillis`:

```kotlin
    /**
     * Latched true when Station 2 answers anything with `client_upgrade_required` — the reader
     * build is too old for the workflow it attempted. There is no un-latch short of installing
     * the required build; surfacing it as state (not a one-shot error) is the point.
     */
    val upgradeRequired: StateFlow<Boolean>
```

`MqttRepositoryImpl.kt` — after `_clockSkewMillis`:

```kotlin
    private val _upgradeRequired = MutableStateFlow(false)
    override val upgradeRequired: StateFlow<Boolean> = _upgradeRequired.asStateFlow()
```

In `parseOutcome`'s rejected branch, after the `SESSION_REQUIRED` block:

```kotlin
            if (code == ErrorCode.CLIENT_UPGRADE_REQUIRED) {
                Log.w(TAG, "Station 2 requires a newer reader build ($expectedResponseType) — latching upgradeRequired")
                _upgradeRequired.value = true
            }
```

In `handleIncomingResponse`, right after the `recordClockSkew(envelope?.timestampUtc ?: "")` call (covers unsolicited rejections that match no pending request):

```kotlin
        if (envelope?.errorCode == ErrorCode.CLIENT_UPGRADE_REQUIRED.raw) {
            _upgradeRequired.value = true
        }
```

Run the Step 1 tests. Expected: PASS.

- [ ] **Step 3: ViewModel + blocking UI**

`MixingViewModel` — after `connectionStatus`:

```kotlin
    val upgradeRequired: StateFlow<Boolean> = mqttRepository.upgradeRequired
```

`MixingViewModelTest.setup()` — the mock now needs the flow (add with the other repository stubs):

```kotlin
        whenever(mockMqttRepository.upgradeRequired).thenReturn(MutableStateFlow(false))
```

`IngredientScanScreen.kt` — with the other `collectAsState()` reads at the top of the composable:

```kotlin
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
```

and as the FIRST dialog block (before `showCancelDialog`), a deliberately undismissable dialog:

```kotlin
    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "Station 2 requires the 4.0 reader build for this workflow. " +
                        "Install the update, then log in again.",
                    color = TextMuted
                )
            },
            confirmButton = {},
            containerColor = GraphiteSurface
        )
    }
```

- [ ] **Step 4: Full suite + commit**

```bash
.\gradlew.bat :app:testDebugUnitTest
git add -A app/src
git commit -m "feat(app): client_upgrade_required latches a blocking update-required state"
```

---

### Task 11: SP4a acceptance gate

**Files:** none created — verification only (fix-forward anything it surfaces).

- [ ] **Step 1: Simulator conformance**

```bash
cd tools/backend-sim
python selftest.py --direct
```

Expected: `ALL <n> CHECKS PASSED — simulator is v4.0 contract-conformant`, exit 0.

- [ ] **Step 2: App unit tests + debug build**

```bash
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: both succeed.

- [ ] **Step 3: Dead-token grep, final sweep (main + tests)**

```bash
grep -rniE "hopper|preMix|premix|allocation_|choose_destination|assign_or_finish|allocate_premix|review_allocation|complete_station2|machineCodes|collectionIds|ReadyForRouting" app/src tools/backend-sim --include="*.kt" --include="*.py" --include="*.json"
```

Expected: no matches (the simulator's `LEGACY_FIELDS` tuple and `legacy_request_shape` strings in `mixing.py`/`selftest.py` are the deliberate exception — they exist to REJECT those tokens; if the grep flags them, narrow the pattern rather than deleting the tripwire).

- [ ] **Step 4: Manual capture run against the live simulator**

1. `cd tools/backend-sim && python sim.py` (defaults to the `mqtt.sysone.co.za:1883` broker; the app's settings must point at the same broker).
2. On the handheld/emulator: log in (`operator1`/`pass`), load job card `510019068`, collect every manual line — including the bulk line via the new quantity dialog and one short-bag waiver.
3. Confirm: final scan shows the collection complete and the screen shows "Collection complete. Mixing arrives in the next update." with the disabled "Mixing available in the next update" button.
4. In the run's `wire.jsonl`: every app request carries `"schemaVersion": "4.0"`; no request ever hit `res/workflow_upgrade_required`; the waiver request's `managerPassword` is logged as `"***"`.

- [ ] **Step 5: Update the knowledge graph and close out**

```bash
graphify update .
git add graphify-out
git commit -m "chore: refresh knowledge graph after SP4a"
```

Then use **superpowers:finishing-a-development-branch** to decide merge/PR for `mqtt-schema-4-foundation`. Do NOT ship a release: nothing ships until SP4b lands mixing.

---

## Deferred / open items (carry into SP4b planning)

1. Real machine codes for DOLCI/Mackie/Rajoo-extruder — seed topology is deliberately trivial to re-code (`seed.json` + the JANDI special-case in `valid_next_machine_codes`).
2. Whether the backend's 4.0 replay path gains the §11 body-hash check — the app self-polices messageId hygiene either way; the simulator is already strict.
3. `station_2` presence-topic id: backend survey found `StationDeviceId = "station_2"` hard-coded, which answers SP3's open question unless renamed.
4. SP4b consumes: `NextAction.START_MIXING` navigation, the §10 `ErrorCode` set, `IngredientScanOutcome.Accepted.collectionStatus/nextAction`, and the simulator's full mixing surface.
