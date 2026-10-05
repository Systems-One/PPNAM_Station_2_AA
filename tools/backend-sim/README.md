# Station 2 Backend Simulator

Mimics the Station 2 WPF backend over MQTT so the Android app can be tested
end-to-end without the real backend. It speaks **rev2.1** (schema `rev2.1`): SCRAM
login plus the General `read` and `lookup` actions. Every message, validation step,
business decision, and state change is logged, so when the app misbehaves the logs
are a second source of truth for diagnosis.

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
  dumps. Job `510019068` is held at start with preparation `PREP_demo0001`
  (`--no-demo-collections` starts clean). `510018531` is a closed order, so a
  lookup of it is rejected (`rev2_rejected`).
- Device ids are **auto-registered** on first sight (the app's `ANDROID_ID`
  can't be known ahead of time); the simulator logs a warning each time.

## Requests

Exactly five request suffixes are accepted: `scram_start_requested`,
`scram_proof_requested`, `operator_list_requested` (pre-login, bare envelope;
answers `operator_list` with the seeded SCRAM users under `data.operators`),
`rev2_general_requested` (`read`, `lookup`; other actions answer
`action_not_allowed`) and `rev2_rajoo_requested` (always `action_not_allowed`).
Anything else answers `client_upgrade_required` on `res/rev2_general_result`.
Replies carry the body under `data`.

## Control frames (`PPNAM/_sim/control`)

- `{"cmd":"invalidate"}` — publishes `active_job_cards_invalidated` to every device
  that has made a General request (the server's preparations-changed push).
- `{"cmd":"reset"}` — clears faults and restores retained `online` presence.
- `{"cmd":"presence","value":"offline"}` — overrides the retained presence.
- `{"cmd":"withhold","match":"*","count":1}` — drop the next request(s), no reply.

## Self-test

`selftest.py` is being rewritten for rev2.1.

## What it deliberately does not do

- No TLS / broker auth, no persistence across restarts, no SAP posting,
  no fixed-door-reader TCP.
- Only General `read`/`lookup`; one-mix quantities are an approximation of the
  server's ProductTree arithmetic.
