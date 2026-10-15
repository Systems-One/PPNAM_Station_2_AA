# Station 2 Backend Simulator

Mimics the Station 2 WPF backend over MQTT so the Android app can be tested
end-to-end without the real backend. It speaks **rev2.1** (schema `rev2.1`): SCRAM
login plus the General `read`, `identify` (proposed), `lookup`, `recover`, `prepare`,
`capture`, `adjust_ingredient` (kind `Add` only) and `confirm` actions. Every message, validation step,
business decision, and state change is logged, so when the app misbehaves the logs
are a second source of truth for diagnosis.

## Setup

```powershell
cd tools\backend-sim
pip install -r requirements.txt
python sim.py                      # uses wss://mqtt.sysone.co.za:443/mqtt (shared broker)
python sim.py --host 10.1.50.1     # or the factory broker
```

Point the app at the same broker in its Settings screen. The simulator plays
`station_2`: retained `online` presence with an `offline` LWT on the base topic
`PPNAM/station_2`, subscribes to `PPNAM/station_2/+/req/+` and `PPNAM/station_2/+`
(device presence), and answers on `PPNAM/station_2/{deviceId}/res/*`.

**Collision warning:** if the real Station 2 backend is online on the same
broker, both backends will answer every request. The simulator checks the
retained presence on `PPNAM/station_2` at startup and warns loudly; pass
`--yield-to-real` to make it exit instead. Note that the simulator also writes
that retained presence topic (`online` on start, `offline` on exit).

## Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--host` / `--port` | `mqtt.sysone.co.za` / `443` (websockets + TLS) | Broker |
| `--window` | 300 | Timestamp acceptance window (seconds) — raise it if the device clock drifts |
| `--yield-to-real` | off | Exit if the real Station 2 is online |
| `--no-color` | off | Plain console output |

## Logs (per run: `logs/<UTC-timestamp>/`)

- **`wire.jsonl`** — every MQTT message in/out with full payload. Passwords are
  redacted per contract but flagged (`<redacted:present>`) so you can still see
  whether the app sent credentials. `messageId`/`inResponseToMessageId` are
  lifted to the top level for easy grepping.
- **`sim.log`** — the narrative: why each request was accepted or rejected and
  every state transition (sessions, challenges, held jobs).
- **`state-snapshots/`** — full world state after every accepted mutation;
  diff consecutive snapshots to see exactly what the backend believed.
- **Console** — colour-coded mirror of `sim.log`.

## Seed world

State is in-memory only; restarting gives a fresh, repeatable world.

- **Operators:** `operator1`/`pass` — ordinary; `manager1`/`secret` — manager.
  Login is SCRAM-SHA-256 only (`scram_start_requested` / `scram_proof_requested`,
  purpose `login`); any `password` field is rejected.
- **Jobs:** SAP orders `510019068` and `510018531`, loaded from real SAP sample
  dumps. Job `510019068` (8 mixes) is held at start with preparation
  `PREP_demo0001` (2 mixes, `Collecting`, real effective rows, so a resume can
  collect against it; `--no-demo-collections` starts clean). `510018531` is a
  closed order, so a lookup of it is rejected (`rev2_rejected`).
- **Ingredient-exception catalog** (`ingredientExceptions` in `seed/seed.json`):
  revision `1`, one code `1500000331` ("Additive (example exception)"). It is on
  job `510019068`'s BOM, so a lookup or a JC read lists it under
  `requiredIngredientChoices` and `prepare` needs an explicit choice for it.
- **Pallets for job `510019068`'s BOM** (scan the RFID tag or the pallet id):

  | Material | Pallet | RFID tag | Notes |
  | --- | --- | --- | --- |
  | `1500000326` | PAL-009 | `300833B2DDD9014000000009` | 25 kg bags |
  | `1600000301` | PAL-001 | `300833B2DDD9014000000001` | 25 kg bags |
  | `1600000217` | PAL-002 | `300833B2DDD9014000000002` | no bag weight: the 25 kg default |
  | `1600000070` | PAL-012 | `300833B2DDD901400000000C` | 25 kg bags |
  | `1600000070` | PAL-003 | `300833B2DDD9014000000003` | **blocked**: capture rejects |
  | `1600000233` | PAL-010 | `300833B2DDD901400000000A` | 25 kg bags |
  | `1600000309` | PAL-008 | `300833B2DDD9014000000008` | 25 kg bags |
  | `1500000331` | PAL-011 / PAL-007 | `...000B` / `...0007` | the catalog code; rejects when excluded |
  | `1600000233` | PAL-004 | `300833B2DDD9014000000004` | at Station 1: capture rejects |
  | `1500000400` | PAL-013 | `300833B2DDD901400000000D` | blue pigment, 20 kg bags, **not on any BOM**: use it to test `identify` + Add |

  Each pallet's `bagSize` (seed `bagSize`, null when unconfigured) is what `identify`
  reports and what a capture with an omitted quantity records. PAL-007 and PAL-011
  (additive) have 10 kg bags, PAL-013 20 kg, the rest 25 kg; PAL-002 has none.
- Device ids are **auto-registered** on first sight (the app's `ANDROID_ID`
  can't be known ahead of time); the simulator logs a warning each time.

## Requests

Exactly five request suffixes are accepted: `scram_start_requested`,
`scram_proof_requested`, `operator_list_requested` (pre-login, bare envelope;
answers `operator_list` with the seeded SCRAM users under `data.operators`),
`rev2_general_requested` (`read`, `identify`, `lookup`, `recover`, `prepare`, `capture`,
`adjust_ingredient`, `confirm`; other actions answer `action_not_allowed`) and `rev2_rajoo_requested` (always `action_not_allowed`).
Anything else answers `client_upgrade_required` on `res/rev2_general_result`.
Replies carry the body under `data`.

### Receipts and recovery (contract §9)

Every mutation is saved as a receipt keyed `(deviceId, request suffix, messageId)`
with the uppercase SHA-256 of its exact bytes and the signed-in operator, whether it
succeeded or was rejected:

- the identical bytes again replay the saved `result` with a fresh snapshot; nothing
  executes twice (no second stock deduction);
- the same id with any other body answers `message_id_conflict`;
- `recover` (`originalMessageId`, `originalRequestFingerprint`, a new `messageId`)
  answers `success:true`, `nextAction:"review_recovered_outcome"` and
  `data.recovery = {originalMessageId, outcome, result}`, where `outcome` is
  `committed`, `rejected` or `not_executed`. An unknown id is **sealed**: a later
  delivery of it answers `receipt_sealed`. Another operator gets
  `receipt_owner_mismatch`; a different fingerprint `message_id_conflict`; a missing
  original id, one equal to `messageId`, or a fingerprint that is not 64 hex
  characters `invalid_envelope`.

Quantities are exact decimals and go on the wire as plain JSON numbers.

### Collection (contract §4, §6, §7)

- `prepare` — `targetId` JC, integer `mixCount` (1 to the job's available mixes),
  `exceptionListRevision` and one `ingredientChoices` entry per
  `requiredIngredientChoices` code (`{"code","include"}`), optional `reason`.
  Missing, duplicate or unknown choices, a stale revision, `includeTackifier:false`
  or an all-excluded recipe reject. Effective rows are `perMix x mixCount`; an
  excluded row keeps `originalRequired` at `required:0` and records an
  `Ingredient omitted` exception. The new `PREP_...` starts `Collecting` at
  `collectionRevision 0`.
- `capture` — `targetId` PREP, `code` pallet tag or id, optional `quantity`
  (omitted or 0 = one full bag). The pallet must be Holding/Mixing and unblocked,
  its material an included row with something remaining, the unit equal; the
  quantity at most a bag, the pallet stock and the remaining target. Each capture
  deducts the pallet, adds to `collected` and bumps `collectionRevision`; when
  nothing remains the stage becomes `AwaitingConfirmation`.
- `confirm` — `targetId` PREP in `AwaitingConfirmation` -> `ReadyForMixer`.
- `identify` (**proposed**, not in the contract yet) — `targetId` PREP, `code` a pallet
  tag or id. Read-only like `read`: no receipt, no exception, no stock change, safe to
  resend. `data` is the normal snapshot for `targetId` plus
  `source = {code, materialCode, name, unit, bagSize, available, usable, reason}`
  (decimals are JSON numbers; `bagSize` is `null` for an unconfigured pallet; `reason`
  is `""` when `usable`). A blocked pallet, one not in Holding/Mixing or one without
  stock answers `success:true` with `usable:false` and a reason. An unknown code
  answers `success:false`, `errorCode:"unknown_source"`, `source:null`; an unknown
  PREP `rev2_rejected`, `source:null`.
- `adjust_ingredient` — kind `Add` only (any other kind is a `rev2_rejected` with a
  message saying so). `targetId` PREP in Collecting/AwaitingConfirmation, integer
  `collectionRevision` (stale -> `collection_revision_conflict` plus a recorded
  `Rejected collection action` exception and the current snapshot), `reason` 1-1000
  trimmed characters, `adjustment = {kind:"Add", sourceCode, targetQuantity}`. The
  source must be a known usable kg pallet whose material is not already a row
  (excluded rows count), `targetQuantity` positive. It appends an effective row
  (`perMix 0`, `originalRequired 0`, `required = targetQuantity`, `collected 0`),
  bumps `collectionRevision`, returns the stage to `Collecting`, records an
  `Ingredient added` exception and pushes the `adjust_ingredient` hint. It is a
  mutation, so it uses the same durable receipts, replay and `drop_reply` handling as
  `capture`. No stock is deducted: capture the new row afterwards; `confirm` needs it
  collected too.
- A rejected `capture`/`adjust_ingredient`/`confirm` against a loaded collection records a
  `Rejected collection action` exception. `data.collectionExceptions` holds the
  selected scope's records and `data.commandExceptions` the ones this command (or
  the recovered original) created.
- Each successful or exception-recording mutation pushes
  `active_job_cards_invalidated` with reason `preparation_created`, `capture`,
  `adjust_ingredient`, `confirm`, `machine_start`, `machine_finish` or
  `collection_exception_recorded`. A replay pushes nothing.

### Machines (contract §4, §8)

`data.machines` is the General machine list in the contract DTO
(`id,name,area,code,enabled,kind,isProduction,pairId,destinations`), seeded in
`state.build_machines`. Codes to scan:

- DOLCI FixedPair: `DOL-MIX-01`->`DOL-01`, `DOL-MIX-02`->`DOL-02`, `DOL-MIX-11`->`DOL-11`;
  Mackie `MAC-MIX-01`->`MAC-EXT-01`; Bubblewrap `BUB-MIX-01`->`BUB-EXT-01`.
- Main mixers `MAIN-MIX-01`..`05` (kind Main) -> any of `MAIN-EXT-01`..`25`.
- Mixer 6 `MAIN-MIX-06` (kind Mixer6) -> only `JAN-02`, `JAN-03`, `JAN-04`.
- `DOL-MIX-03`/`DOL-03` are **disabled**; `DOL-MIX-04`/`DOL-04` have a **blank code**
  (unconfigured, so nothing can be scanned for them).

The server decides the route from the machine scanned: any enabled mixer is permitted for
any job.

- `machine_start` — `targetId` PREP, `code` machine. `ReadyForMixer`: an enabled mixer,
  assigned as `mixerId` the first time. `ReadyForNextMix`: must be the saved mixer.
  `ReadyForProduction`: a production machine in the saved mixer's `destinations`
  (FixedPair never gets here); `productionId` is saved on the first production cycle and
  later cycles must reuse it. A new `CYC_<hex>` goes in `result.cycleId` and
  `preparation.cycleId`, and the stage becomes `Mixing` or `Producing`. Rejected
  (`rev2_rejected`, readable message): unknown, disabled or blank code, wrong kind for
  the stage, wrong stage (including an already running cycle), a machine that another
  non-Completed/Cancelled PREP holds as mixer or production machine.
- `machine_finish` — `targetId` PREP, exact machine `code` (case-sensitive), exact active
  `cycleId`; anything else is rejected. Mixing: `mixed += 1` (FixedPair also
  `produced += 1`), then `ReadyForNextMix` while `mixed < mixCount`, else `Completed`
  (FixedPair) or `ReadyForProduction`. Producing: `produced += 1`, then
  `ReadyForProduction` or `Completed`. `preparation.cycleId` returns to null
  (`result.cycleId` names the finished cycle); `mixProgress` is derived from the same counts.
- Both are durable mutations (receipts, replay, `drop_reply`, `recover`) and push the
  `machine_start` / `machine_finish` hints. Machine rejections record **no** exception
  (contract §6 lists only capture, confirmation and decisions).

## Control frames (`PPNAM/_sim/control`)

- `{"cmd":"invalidate"}` — publishes `active_job_cards_invalidated` to every device
  that has made a General request (the server's preparations-changed push).
- `{"cmd":"reset"}` — clears faults and restores retained `online` presence.
- `{"cmd":"presence","value":"offline"}` — overrides the retained presence.
- `{"cmd":"withhold","match":"*","count":1}` — drop the next request(s), no reply.
- `{"cmd":"drop_reply","match":"rev2_general_requested","count":1}` — process the next
  matching request normally (its receipt is saved, stock moves) but never publish the
  reply: a lost reply, for testing identical retry and `recover`.

## Self-test

In-process, no broker needed (runs every check):

```powershell
python selftest.py --direct
```

Over MQTT, the simulator and the self-test must use the **same** broker. The
self-test defaults to `localhost:1883`, while `sim.py` defaults to the shared
remote broker, so start the simulator locally:

```powershell
python sim.py --host localhost --port 1883 --transport tcp --no-tls --username ""
python selftest.py
```

The self-test exits with code 2 and says so when no simulator is online on its
broker (it used to fail its first check instead).

## What it deliberately does not do

- No TLS / broker auth, no persistence across restarts, no SAP posting,
  no fixed-door-reader TCP.
- No machine actions, and `adjust_ingredient` handles only `Add`; one-mix quantities
  are an approximation of the server's ProductTree arithmetic.
