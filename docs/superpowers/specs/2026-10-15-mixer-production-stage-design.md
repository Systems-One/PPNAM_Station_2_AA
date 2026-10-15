# Mixer & production stage — design

Status: Approved in brainstorming 2026-10-15 (mockups `.superpowers/brainstorm/312-1792052891/content/mixer-stage.html`, finish choice **A: scan, then tap**). Builds on `2026-10-15-simplified-collect-flow-design.md` (same PREP screen, same scan zone, same result banners and lost-reply hold).

## Scope

General mode only, after `confirm`. Stages `ReadyForMixer`, `Mixing`, `ReadyForNextMix`, `ReadyForProduction`, `Producing`, `Completed`. Uses existing contract actions `machine_start` and `machine_finish` (contract §4, §12.11–12.16). No contract change.

## Screen (PREP screen, these stages)

Top: a **stage strip** `Collect · Mix · Produce · Done` (done = green, current = filled brand green, future = muted), then two count tiles **Mixed m/N** and **Produced p/N** (`preparation.mixed`, `.produced`, `.mixCount`). Fixed-pair (`kind == FixedPair`) preparations skip Produce visually: the strip shows `Collect · Mix · Done` and finishing a mix updates both counts (server does that).

Per stage:

| stage | body | scan zone text |
|---|---|---|
| ReadyForMixer | "Mixers" list: every machine with `isProduction == false` and kind ≠ Rajoo; row shows name, and `free` (green) / `in use · PREP_xxxx` (amber, another non-completed preparation in `data.preparations` has that `mixerId`) / `disabled` (muted, `enabled == false` or blank code) | "Scan a mixer — to start mix 1 of N" |
| Mixing | Blue live banner: spinner, "Mixing k of N", "<mixer name> · started HH:mm" (`startedAtUtc` of the cycle if present, else omit), elapsed minutes ticking | "Scan <mixer name> to finish" + muted "only <mixer name> is accepted" |
| ReadyForNextMix | Green banner "Mix k finished — r mixes to go" (when arriving from a finish) | "Scan <mixer name> — to start mix k+1 of N" |
| ReadyForProduction | Green banner "All N mixes mixed" (when arriving), "Free extruders" line listing the assigned mixer's `destinations` names that are enabled and not in use | "Scan an extruder — to produce mix k of N" |
| Producing | Blue live banner "Producing k of N", "<machine name> · started HH:mm" | "Scan <machine name> to finish" |
| Completed | Green banner "Preparation complete", "N mixes · <mixer> → <production>", job line "x of R finished · y available to prepare" from `mixProgress` when present; button **Back to job** | none |

k = `mixed + 1` while mixing, `produced + 1` while producing.

## Scan → confirm → send (one deliberate tap)

A scan in these stages is matched case-insensitively against `machines[].code`.
- Unknown code: red banner "Not a machine — <code>" (no send). A pallet/material code here: "Collection is finished — scan a mixer" etc.
- Start (ReadyForMixer / ReadyForNextMix / ReadyForProduction): a confirm card — scanned code, machine name, area, "Feeds: …" (destinations summary) for mixers, big blue-banner "Mix k of N" (or "Production k of N"), note "This mixer stays assigned for all N mixes" on first start, button **Start mix k on <name>** (or **Start production k on <name>**) and **Cancel**. Sends `machine_start {targetId: PREP, code}`.
  - In ReadyForNextMix / ReadyForProduction-after-first, if the scanned machine is not the saved `mixerId`/`productionId` where the server requires the same one, still allow the send — the server is the authority — but show an amber line "Saved mixer is <name>" first.
- Finish (Mixing / Producing): scanned machine must be the active one (`mixerId` while Mixing, `productionId` while Producing); otherwise red "Only <name> can finish this mix" (no send). Confirm card "Finish mix k on <name>?" with button **Finish mix k** (or **Finish production k**) and **Cancel**. Sends `machine_finish {targetId: PREP, code: <scanned exact code>, cycleId: preparation.cycleId}` — always the current preparation's `cycleId` from the latest snapshot, never one remembered from an earlier reply.
- Disabled machine: confirm card shows "Disabled — can't start" and no Start button.

Results reuse the collect result banners: Done → green ("Mix 1 started on Mixer 2" / "Mix 1 finished" / "Production 2 finished"), Rejected → red with Station 2's reason, Unresolved → the amber full-width hold with Retry (identical bytes), ManagerReconcile → hold without Retry. Replacement offers do not apply to machine commands (never offered). All sends go through `GeneralCollectionUseCase` → `sendCommand` (outboxed, blocking the PREP while unresolved).

The read on resume/hint/reconnect refreshes stage, counts and the elapsed timer source.

## Out of scope

Rajoo, cancelling, reassigning machines, editing machine config.

## Testing

- Unit: machine matching (case-insensitive, unknown, disabled), busy detection from `preparations`, stage → screen model mapping incl. FixedPair strip, k computation, finish requires active machine + uses snapshot `cycleId`, request JSON for both actions matches contract examples, VM flows start/finish Done/Rejected/Unresolved.
- Simulator: `machine_start`/`machine_finish` for Main (mix all then produce), Mixer6→JAN, FixedPair (finish increments mixed+produced); wrong machine / wrong cycle / disabled / occupied rejections; replay; drop_reply. Selftest extended.
- Device: storyboard steps 1–7 against the simulator.
