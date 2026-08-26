# Station 2 Backend Simulator

Mimics the Station 2 WPF backend over MQTT (RFID contract **v4.1**, schema
`4.1`) so the Android app can be tested end-to-end without the real backend.
Every message, validation step, business decision, and state change is
logged, so when the app misbehaves the logs are a second source of truth for
diagnosis.

## Setup

```powershell
cd tools\backend-sim
pip install -r requirements.txt
python sim.py                      # uses mqtt.sysone.co.za:1883
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
| `--host` / `--port` | `mqtt.sysone.co.za` / `1883` | Broker |
| `--window` | 300 | Timestamp acceptance window (seconds) — raise it if the device clock drifts |
| `--tolerance` | 1.0 | Over-collection tolerance in full bags |
| `--yield-to-real` | off | Exit if the real Station 2 is online |
| `--no-color` | off | Plain console output |

## Logs (per run: `logs/<UTC-timestamp>/`)

- **`wire.jsonl`** — every MQTT message in/out with full payload. Passwords are
  redacted per contract but flagged (`<redacted:present>`) so you can still see
  whether the app sent credentials. `messageId`/`inResponseToMessageId` are
  lifted to the top level for easy grepping.
- **`sim.log`** — the narrative: each request's 8 validation steps, bag-math
  arithmetic, why something was accepted/rejected, and every state transition
  (sessions, collections, hoppers, pre-mixes, cycles, allocations). Clock skew
  is logged on every request even when accepted.
- **`state-snapshots/`** — full world state after every accepted mutation;
  diff consecutive snapshots to see exactly what the backend believed.
- **Console** — colour-coded mirror of `sim.log`.

## Seed world

State is in-memory only; restarting gives a fresh, repeatable world.

- **Operators:** `operator1`/`pass` (badge `BADGE001`) — ordinary;
  `manager1`/`secret` (badge `BADGE012`) — holds all six privileged action ids
  (use for approvals, waivers, cancels, force-close, return/transfer).
- **Job cards:** SAP orders `510019068` (Layer-mash-style BOM, 7 manual lines,
  one bulk line `1600000217`) and `510018531`, loaded from real SAP sample dumps.
- **Equipment:** 47 pieces across five mixing areas — `MainMixingRoom`
  (mixers `MXR-01`..`05`, extruders `EXT-01`..`25`, with `EXT-25` seeded
  `Disabled`), `JandiBulkMixing` (shared mixer `JAN-MIX-01`, transfer drum
  `JAN-DRUM-01` gating `JAN-04`, extruders `JAN-02`..`04`),
  `DolciBulkMixing`, `MackieBulkMixing`, and `RajooMachineMixing`
  (gravimetric mixers `RAJ-GM-01`..`03` per product layer feeding
  `RAJ-EXT-01`). Scan-stock pallets are sized for ~16 full collections;
  the selftest runs five.
- **Pallets** (tags abbreviated; see `seed/seed.json` for full 24-char tags):

| Tag suffix | Pallet | Product | State | Exercises |
| --- | --- | --- | --- | --- |
| …0001 | PAL-001 | 1600000301 HD WHITE, 625 kg | Holding | happy path, tolerance |
| …0002 | PAL-002 | 1600000217 LD MIX, 1800 kg | Holding, bulk | direct-weight scans |
| …0003 | PAL-003 | 1600000070 FILLER, 600 kg | Holding + **blocked** | blocked rejection |
| …000C | PAL-012 | 1600000070 FILLER, 700 kg | Holding | over-tolerance approval |
| …0004 | PAL-004 | 1600000233 WRAP, 300 kg | **AtStation1** | lookup → recovery flow |
| …0005 | PAL-005 | 1500000326 MB WHITE, 75 kg | **Unknown** | recovery flow |
| …0006 | PAL-006 | 1600000309 STRETCHHOOD, 0 kg | **Consumed** | unrecoverable |
| …0007 | PAL-007 | 1500000331 DESICCANT, 15 kg | Holding, low stock | short scans / waiver |
| …0008–000B | PAL-008…011 | various | Holding | normal collection stock |

- Device ids are **auto-registered** on first sight (the app's `ANDROID_ID`
  can't be known ahead of time); the real backend would reject unconfigured
  devices, and the simulator logs a warning each time it auto-registers one.

## Self-test

Run it in-process (no broker needed) or against a running simulator:

```powershell
python selftest.py --direct       # in-process, no broker required
python selftest.py [--host …]     # over MQTT against a running sim
```

Drives contract v4.0's minimum acceptance flow end-to-end across all five
mixing areas — login → §12 schema-compatibility boundary (3.0 accepted only
for capture actions, rejected for `mixing_overview_requested`) → retired v3
topics answering `client_upgrade_required` on `res/workflow_upgrade_required`
→ pallet lookup/holding recovery → capture collections via
`job_card_load_requested`/`ingredient_scan_requested` (bag scans, bulk
direct weight, over-tolerance approval retry, short-bag waiver) to
`ReadyForMixing` → `mixing_overview_requested` (area filtering,
`invalid_mixing_area`) → family-dispatched `machine_cycle_start_requested`/
`machine_cycle_finish_requested` for mixer start/finish, production-run
accumulation across multiple mixes on the same machine, the JANDI transfer-
drum gate (`drum_cycle_required`), Rajoo `layerInputs` dosing validation
(`invalid_layer_inputs`), and manager-gated `machine_cycle_force_close_requested`
— plus negative probes for every §10 error code (`legacy_request_shape`,
`unknown_or_disabled_equipment`, `equipment_in_use`, `cycle_mismatch`,
`source_not_found`, `source_not_ready`, `source_already_assigned`,
`job_card_mismatch`, `invalid_route`, `permission_denied`,
`validation_failed`, and more), strict replay on a 4.0 topic
(`message_id_reused`), stale timestamps, and device mismatch. Exits non-zero
on any deviation.

## What it deliberately does not do

- No TLS / broker auth, no persistence across restarts, no SAP posting,
  no fixed-door-reader TCP, no v2 compatibility.
- No fault injection console (by design decision 2026-07-17).
