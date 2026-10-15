# Simplified collect flow — design

Status: Approved in brainstorming 2026-10-15 (mockups `.superpowers/brainstorm/312-1792052891/content/collect-flow.html`). Builds on branch `feat/contract-2026-10-01-phase2-collection`.

## Goal

Make General-mode collection on the handscanner fast and obvious:
scan a pallet → app identifies the product → app says how much to take in bags and quarters → Submit → green tick or red reason → ingredient shown collected → one button marks the mix complete.

## What the operator sees (PREP screen, stage Collecting)

1. **Collect home.** Compact header (JC · product, mix count, short PREP id), a progress bar ("2 of 4 ingredients collected"), the ingredient list, and a scan zone at the bottom ("Scan a pallet tag", with a type-the-code fallback). Each ingredient row: name, code, and either `✓ <collected> <unit>` (green edge, complete), `<remaining> <unit>` / `of <required> left` (amber edge, outstanding), or muted "excluded". Operator-added rows show an "added" tag.
2. **Identified (Collect sheet/screen).** After a scan the app sends `identify` (below) and shows: pallet code, product name, bag size, still needed, stock on pallet. A large **Take** guide (e.g. `≈ ½ bag`, `2 bags + ≈ ½`) with bag icons, the line "records exactly N kg", an editable **Amount (kg)** field prefilled with the amount to record, then **Submit N kg** (with `(k bags)` when k > 1) and **Cancel**.
3. **Accepted.** Green banner "Recorded N kg <name>" (+ "<name> is now fully collected" when remaining hits 0). The row flips to green and briefly highlights. Scan zone reads "Scan next pallet".
4. **Rejected.** Red banner "Not recorded", Station 2's reason, and the server date/time + operator ID of the logged exception when present. Nothing is recorded; scan zone reads "Scan another pallet".
5. **Not in this mix.** If the identified material is not an effective row of this PREP: amber banner "Not in this mix — <name> · <code>", bag size, **Adding (kg)** field, **Reason (required)** field, explanation that it is logged for manager review, buttons **Add N kg to mix** and **Cancel — don't add**. If the material is an *excluded* row: red "Excluded from this mix — can't collect", only Cancel.
6. **All collected.** Green banner "All ingredients collected" and one large **Mix complete — ready for mixer** button (`confirm`), with a single confirm dialog.

**Lost reply (Station 2 doesn't answer):** option A — the result area becomes an amber full-width hold: "Station 2 hasn't answered — don't scan again", one large **Retry** (same bytes, same message id). Scanning and Submit are disabled until resolved. Manager-reconcile hold: same area, "A manager must reconcile this at the station", no Retry. Replacement offers (spec §6 of the Phase 2 design) keep their current dialog.

Exceptions list stays at the bottom of the screen, collapsed under "Exceptions (n)".

## Bag guide (display only)

Inputs: `amount` (kg to record), `bagSize` (kg, > 0).
- `whole = floor(amount / bagSize)`, `rest = amount − whole × bagSize`.
- `restQuarters = round(rest / bagSize × 4)` (half-up), but if `rest > 0` and that rounds to 0, use 1 (show `≈ ¼`). If it rounds to 4, show it as one more whole bag with `≈`.
- Text: `1 bag`, `2 bags`, `≈ ½ bag`, `2 bags + ≈ ½`, `≈ 1 bag` (rest rounded up to a full bag), `¼ ½ ¾` glyphs.
- Without a bag size the guide shows only `N kg`.

The guide never changes what is recorded. **Recorded amount = exact kg** in the Amount field.

Prefill: `min(remaining, availableOnPallet)` for an in-mix material. Validation (Submit disabled with a muted reason): blank/zero/negative, more than 3 decimals (`QuantityInput.MAX_SCALE`), above `remaining` ("More than still needed — N kg"), above pallet stock when known ("Only N kg on this pallet").

## Recording (wire)

Submit splits the amount into captures, sent **sequentially**, each its own durable outbox command (new message id each):
`whole` captures of `quantity = bagSize`, then one capture of `quantity = rest` if `rest > 0`. Without a bag size: one capture of the whole amount.
Stop at the first capture that is not `Done`: show its rejection/hold; earlier captures stay recorded and the banner says how many of k were recorded ("Recorded 2 of 3 bags (50 kg) — not recorded: …"). Never auto-continue after a hold.

**Add to mix:** `adjust_ingredient` with `targetId` = PREP, `collectionRevision` = the PREP's current revision, `reason` (trimmed, 1–1000), `adjustment = {kind:"Add", sourceCode:<scanned code>, targetQuantity:<amount>}`. On Done the app re-reads, then shows the normal Identified screen for that pallet with the amount prefilled; the operator taps **Submit** to capture (contract §4: an adjustment never captures; capture is a deliberate new command). `collection_revision_conflict` → show the reason, re-read, return to Collect home (no automatic resend).

**Mix complete:** `confirm` (unchanged command).

All sends go through `GeneralCollectionUseCase` / `MqttRepository.sendCommand` so outbox, retry, recovery and blocking behave exactly as Phase 2.

## New contract action: `identify` (proposed)

Read-only, no receipt, no stock change, no exception record. General family topics.

Request: common envelope + `action:"identify"`, `targetId`: PREP id, `code`: scanned pallet/label code.

Response `data`: the normal read snapshot for `targetId` plus `source`:

| field | meaning |
|---|---|
| `code` | the scanned code, echoed |
| `materialCode`, `name`, `unit` | material on the pallet |
| `bagSize` | configured bag quantity in `unit` (decimal, nullable when unconfigured) |
| `available` | physical stock on the pallet in `unit` (decimal) |
| `usable` | boolean: unblocked local Station 1 pallet in Holding/Mixing with fresh evidence |
| `reason` | readable reason when `usable` is false, else empty |

Unknown code: `result.success:false`, `errorCode:"unknown_source"`, `source:null`. Android treats `identify` like `read`: plain request/response, never outboxed, safe to resend.

**Fallback while Station 2 lacks `identify`** (rejected as unsupported/unknown action, or no reply after the read timeout): show "Station 2 couldn't identify this pallet — pick the ingredient", the outstanding ingredient list to tap, then the Amount field (prefilled with that ingredient's remaining, no bag guide) and Submit → one capture with that quantity. Station 2 still decides the real material and rejects mismatches.

The section is added to `RFID_MQTT_CONTRACT.md` marked **Proposed — not yet implemented by Station 2**. The backend simulator implements `identify` and `adjust_ingredient` (kind `Add` only).

## Out of scope

Mixer/production stages (shown as mockups for approval separately), Substitute/Quantity/Omit decisions, Rajoo, the Prepare screen.

## Testing

- Unit: bag guide (table of cases incl. 10/25 → ≈ ½, 60/25 → 2 + ≈ ½, 24/25 → ≈ 1, 1/25 → ≈ ¼, exact 50/25 → 2 bags), capture split, amount validation, identify mapping incl. unknown_source/unsupported fallback, add-ingredient request JSON, VM state machine (identified → submitting → accepted/rejected/partial/hold; not-in-mix → add → identified; all collected → confirm).
- Simulator selftest: identify known/unknown/blocked; adjust_ingredient Add then capture; revision conflict.
- Device: the storyboard steps against the simulator.
