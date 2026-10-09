# Phase 2: General preparation and material collection from a job

**Date:** 2026-10-08
**Status:** Implemented (branch feat/contract-2026-10-01-phase2-collection). On-device check (plan Task 13 Step 2) not yet run.
**Parent spec:** `2026-10-05-contract-2026-10-01-adoption-design.md`, §3 row 2 and §6 (entry gate)
**Authority:** `PPNAM-Station-2/RFID_MQTT_CONTRACT.md` (wire `rev2.1`, revision 2026-10-01). Where the contract is silent, the server source decides. This spec cites `PPNAM.Station2.Core` by file and line, as read on 2026-10-08.
**Acceptance:** `RFID_SCANNER_APP_TEST_CASES.md`: GEN-02 to GEN-13 and REC-01. GEN-01 and GEN-14 are server-side. REC-05's "one newly confirmed intent" is covered on the client by the replacement prompt.

The sibling repo is read-only, as `CLAUDE.md` requires. Paths in this spec are relative to `C:\Dev\Clients\PPNAM\Station_2\`. Android paths drop the prefix `PPNAM_Station_2_AA/app/src/main/java/com/mitas/ppnam/station2aa/`.

---

## 1. Scope

**In scope:**
- An operator opens a job card (JC) and answers the Include/Exclude choices.
- `prepare` allocates N mixes, where N is at most the available-to-prepare count.
- The operator opens a saved preparation (PREP) by `read`. This covers PREPs created on the desktop and PREPs resumed after a reconnect.
- One `capture` per physical bag or fraction, with exact decimal quantities.
- `confirm`.
- Every unanswered mutation goes through Phase 1's durable outbox.
- When recovery returns `not_executed`, the operator must confirm a **replacement** before it is sent.
- The simulator gains all of the above, plus receipts and `recover`.

**Out of scope:**
- `machine_start` and `machine_finish` (Phase 3). After a confirm, the PREP screen shows `ReadyForMixer` as read-only.
- `adjust_ingredient` and its exception UI (Phase 4). Phase 2 still *displays* effective rows, including rows that Phase 4 decisions produced, and the server-recorded exceptions for the selected PREP.
- Rajoo (Phase 6).
- Polishing hints for every reason (Phase 5). Phase 2 does need one hint stream that several screens can share, because the PREP screen must refresh on a hint (§8).

**Entry gate.** This plan assumes the §6 entry gate items from the parent spec are merged. Branch `fix/phase2-entry-gate` is in progress; its working tree already shows `CommandOutcome.NotSaved`, `CommandOutcome.NoLongerPending`, `Rev2Snapshot.exceptionListRevision: Int?` and `fingerprintMismatchFn`. Phase 2 code relies on those names. Task 0 of the plan verifies they are present.

---

## 2. Facts established from the contract and the server

| # | Fact | Source |
|---|---|---|
| F1 | `prepare` takes `targetId` (JC), integer `mixCount` > 0, `ingredientChoices`, integer `exceptionListRevision`, and an optional `reason`. The server rejects it when `mixCount > requiredMixes − allocated` (non-cancelled PREPs) | Contract §4; `Rev2MixingService.cs:232-263` |
| F2 | Choice validation, in this order: `includeTackifier:false` rejects; a supplied revision ≠ the catalog revision rejects; null/blank code or `include:null` rejects; duplicate codes (trimmed, case-insensitive) reject; a code not in the matches rejects; a matching code with no answer rejects; matches > 0 with no revision rejects; all materials excluded rejects | `IngredientExceptionRules.cs:21-49`; contract §7 |
| F3 | Choices come from `data.requiredIngredientChoices`. They are empty once a PREP is selected, and empty when `job` is null. The revision to echo is the **top-level** `data.exceptionListRevision` (the catalog revision), not `job.exceptionListRevision` | `Rev2ScannerProcessor.cs:150-185`; contract §7, §8 |
| F4 | With no matching codes, send `ingredientChoices:[]` and the current revision | Contract §7 |
| F5 | `capture` takes `targetId` (PREP), `code` and an optional decimal `quantity`. If `quantity` is omitted or 0, the server captures one configured bag (`pallet.BagSize`). The capture must satisfy `0 < q ≤ bagSize`, `q ≤ pallet stock` and `q ≤ material.Remaining`, or it rejects. **A whole bag that exceeds the remaining target is rejected**, so the last bag must be sent as a fraction | `Rev2MixingService.cs:265-293` (line 281) |
| F6 | The **server**, not the scanner, works out which material a capture is for: it reads the pallet's product code. The scanner cannot know the material or the bag size before the reply | `Rev2MixingService.cs:274-279` |
| F7 | `code` is matched against Station 1's `rfid_tag` (only when the Station 2 `last_known_rfid` equals it), `label_barcode` or `pallet_barcode`, after trimming. Two matches count as no match. The replica must be fresh (≤ 5 min) | `PalletMovementSqlStore.Rev2.cs:91-108` |
| F8 | Capture is allowed only while `stage == Collecting`. When every row's `remaining == 0` the stage becomes `AwaitingConfirmation` | `Rev2MixingService.cs:270-271, 291` |
| F9 | Confirm is allowed only when `stage == AwaitingConfirmation` and no row has `remaining > 0`. Success gives `ReadyForMixer` and sets `confirmedAtUtc`. **Confirm does not increment `collectionRevision`** | `Rev2MixingService.cs:295-303`; `general_confirm_response.json` |
| F10 | A rejected `capture`, `confirm` or `adjust_ingredient` against a loaded job records a "Rejected collection action" exception. The reply is `rev2_rejected` and carries the full snapshot, with `commandExceptions` holding that record | `Rev2MixingService.cs:176-183`; contract §6 |
| F11 | A JSON field's type matters. `quantity`, `mixCount`, `collectionRevision` and `exceptionListRevision` must be JSON numbers; a numeric string gives `invalid_envelope`. `mixCount` binds to `int`, so `4.0` most likely fails deserialization (JsonException), which also gives `invalid_envelope` | `Rev2ScannerProcessor.cs:211-223, 128-129, 187` |
| F12 | The server keys duplicate detection on (device, request suffix, messageId). The exact replay of a mutation returns the saved result with a fresh snapshot. A different body under the same identity gives `message_id_conflict`. A sealed identity returns its sealed result (`receipt_sealed`) | `Rev2MixingService.cs:166-174`; contract §9 |
| F13 | `recover` → `committed`, `rejected` or `not_executed`; the `not_executed` case seals the identity. The nested `recovery.result` has `errorCode` | `Rev2MixingService.Recovery.cs:11-47`; `general_recover_*_response.json` |
| F14 | A `read` with `targetId` set to a PREP id selects both that PREP and its job. `collectionExceptions` is scoped to the PREP | `Rev2ScannerProcessor.cs:141-149` |
| F15 | The capture example sends `"quantity":10`, a whole number written as a bare JSON number | `general_capture_request.json` |
| F16 | Every reply's `data.job.mixProgress` and `data.jobs[]` carry the §8.1 totals. `collectedMixes` counts whole PREPs only | `Rev2JobProgress.cs:16-31` |

---

## 3. Message flows (real payloads, from `DOCS/MQTT/Message_Examples`)

Requests go on `PPNAM/station_2/{deviceId}/req/rev2_general_requested` and replies come back on `.../res/rev2_general_result`. Every request carries the envelope `schemaVersion`, `deviceId`, `messageId`, `timestampUtc` and `sessionId`. `RequestEnvelope.build` adds the envelope; callers never write it.

### 3.1 Open a job and fetch the choices (read)

```json
{"schemaVersion":"rev2.1","deviceId":"scanner_1","messageId":"example-006","timestampUtc":"2026-10-01T08:00:00.000000Z","sessionId":"…","action":"read","targetId":"1"}
```

Lookup and read return the same shape. `general_lookup_response.json` `data` (abridged):

```json
"requiredIngredientChoices":[{"code":"OPTIONAL","description":"Example optional material","note":""}],
"exceptionListRevision":1,
"job":{"id":"1","requiredMixes":60,"materials":[{"code":"RESIN","unit":"kg","perMix":10,"required":600,…},{"code":"OPTIONAL","perMix":2,"required":120,…}],
       "mixProgress":{"requiredMixes":60,"allocatedMixes":0,"activeMixes":0,"availableToPrepareMixes":60,…,"activePreparations":[]}},
"preparation":null
```

Contract §11 says to re-read before a new preparation. The Prepare screen therefore always issues its own `read targetId=<JC>` when it opens, even if the job detail is already on screen.

### 3.2 Prepare (mutation; outbox)

```json
{"schemaVersion":"rev2.1","deviceId":"scanner_1","messageId":"example-004","timestampUtc":"…","sessionId":"…","action":"prepare","targetId":"1","mixCount":4,"exceptionListRevision":1,"ingredientChoices":[{"code":"OPTIONAL","include":false}],"reason":"Prepare four mixes without the optional ingredient"}
```

Reply (`general_prepare_response.json`):
- `result.targetId` is the new PREP id: `PREP_440f…`.
- `preparation.stage` is `Collecting`, with `collectionRevision` 0.
- The materials hold the effective rows. RESIN has `required:40`. OPTIONAL has `required:0`, `excluded:true` and `originalRequired:8`.
- `job.mixProgress` reads allocated 4, active 4, available 56.
- `commandExceptions` holds one "Ingredient omitted" record carrying the server's operator ID, time and reason.

### 3.3 Resume an exact PREP (read)

```json
{"…":"…","action":"read","targetId":"PREP_440fbd8f9cd3411e9be943a33f1a3ceb"}
```

The reply has the same shape, with `preparation` set and `requiredIngredientChoices:[]`. This is the **only** way to open a saved PREP. Never send `prepare` to open one (contract §4).

### 3.4 Capture one bag or fraction (mutation; outbox)

```json
{"…":"…","action":"capture","targetId":"PREP_440fbd8f9cd3411e9be943a33f1a3ceb","code":"TAG-RESIN","quantity":10}
```

Reply (`general_capture_response.json`): RESIN shows `collected:10, remaining:30`, and `collectionRevision` moves to 1. After the final bag (`final_capture_response.json`) the stage is `AwaitingConfirmation`, `collectionRevision` is 4 and `mixProgress.collectedMixes` is 4.

### 3.5 Confirm (mutation; outbox)

```json
{"…":"…","action":"confirm","targetId":"PREP_440fbd8f9cd3411e9be943a33f1a3ceb"}
```

The reply has `stage:"ReadyForMixer"`, `confirmedAtUtc` set and `mixProgress.confirmedMixes:4`.

### 3.6 Recovery (Phase 1 use case; Phase 2 consumes it)

`general_recover_not_executed_response.json`:
- top level: `success:true`;
- `data.recovery`: `{"originalMessageId":"example-delayed-bag","outcome":"not_executed","result":{"success":false,…,"errorCode":"receipt_sealed"}}`.

If the delayed original arrives later, the reply is `success:false, error:"receipt_sealed"` (`general_delayed_sealed_response.json`).

---

## 4. The PREP lifecycle on the scanner

```
           Job detail (read JC)
              │ "Prepare mixes" (available > 0, JC open, no unresolved prepare for this JC)
              ▼
   ┌── Prepare screen: read JC ──► choices + N + optional reason ──► confirm dialog
   │          │ sendCommand(prepare)
   │          ├─ Settled success ──► PREP screen (snapshot already in hand)
   │          ├─ Settled rev2_rejected ──► apply snapshot, show message, re-validate form (new intent = new ID)
   │          └─ Unresolved ──► "not confirmed" banner; Retry = identical bytes; Home card shows it if left
   │
   │   Job detail active/history PREP row, or Lookup input "PREP_…" ──► PREP screen: read PREP
   ▼
 PREP screen, stage-driven:
   Collecting ──scan──► staged code ──"Record bag"/"Record part bag"──► sendCommand(capture)
       ▲   │                                  ├─ success ──► apply snapshot (stage may become AwaitingConfirmation)
       │   │                                  ├─ rev2_rejected ──► apply snapshot + show exception record(s)
       │   │                                  └─ Unresolved ──► BLOCK scans/confirm on this PREP; Retry identical
       │   └─ (Phase 4 decisions may move AwaitingConfirmation back to Collecting)
   AwaitingConfirmation ──"Confirm collection"──► sendCommand(confirm) ──► ReadyForMixer
   ReadyForMixer … Completed / Cancelled ──► read-only (machine cycles: Phase 3)
```

### 4.1 Screen state (PreparationViewModel)

| State element | Rule |
|---|---|
| `preparation` | Taken from the latest *applicable* snapshot (§4.3), where `snapshot.preparation.id == prepId` |
| `stage` | A `PrepStage` enum: Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, ReadyForNextMix, ReadyForProduction, Producing, Completed, Cancelled, Unknown(raw) |
| `canScan` | `stage == Collecting`, no in-flight command, and **no Unresolved or ManagerReconcile outbox entry whose `targetId == prepId`** |
| `canConfirm` | `stage == AwaitingConfirmation`, every non-excluded row has `remaining.signum() == 0`, nothing in flight, nothing unresolved |
| `staged` | `StagedCapture(code, mode = WholeBag | Part(material, quantityText))` or null |
| `pendingForThisPrep` | Outbox entries with `targetId == prepId`, split into: same session (Retry), earlier session (recovery runs from Home/coordinator), ManagerReconcile (blocked) |
| `lastOutcome` | A one-shot message: recorded / rejected (with exception lines) / not sent / not saved |
| `replacementOffer` | §6 |

### 4.2 Blocking rule (a design decision)

While **any** Unresolved or ManagerReconcile entry targets this PREP, the screen does not accept a new capture or a confirm. Two reasons:
- An operator who sees "no reply" naturally rescans the same bag. Contract §9 forbids treating a missing reply as permission for a second physical capture.
- §9 requires a manager to reconcile `receipt_recovery_unavailable` "before another scan".

Other PREPs stay usable.

### 4.3 Snapshot ordering

Hint reads, the open-read and command replies can all be in flight at once. Every request the VM issues takes a local, increasing sequence number. A reply is applied only if its sequence number is higher than the last applied one.

`collectionRevision` cannot be used for ordering: confirm changes the stage without changing the revision (F9), so a stale read could roll `ReadyForMixer` back to `AwaitingConfirmation`.

A read never clears an unresolved banner. Contract §9: "Never treat a generic read of quantities as proof that one particular unanswered bag committed."

---

## 5. Quantities: BigDecimal end to end

| Concern | Decision |
|---|---|
| Parsing replies | `Rev2Material.perMix/required/collected/originalRequired/remaining` become `BigDecimal` (default `BigDecimal.ZERO`; `originalRequired` stays nullable). Gson binds JSON numbers to `BigDecimal` from their literal text, so nothing passes through a double. `JobLookupUseCase` keeps `JobMaterial` as `Double` for display (`.toDouble()`), so Phase 1 screens do not change |
| Domain remaining | `PrepMaterial.remaining = (required − collected).max(ZERO)`, the server's formula (`Rev2MixingModels.cs:20`). The `remaining` the server sends is used only in a test that pins equality |
| Comparisons | Always `compareTo`, never `equals` (`40` vs `40.000`). Helpers: `isComplete = remaining.signum() == 0`, `fits(q) = q > 0 && q.compareTo(remaining) <= 0` |
| Operator input | `QuantityInput.parse(text)` accepts digits with **either** `.` or `,` as the decimal separator (South African keyboards use a comma). It rejects signs, exponents, blanks, zero, more than 3 decimal places (see Q3) and values above 999 999 999. It returns a `BigDecimal` |
| Wire output | `WireDecimal.of(q)` = `q.stripTrailingZeros()`, then `setScale(0)` if the scale is negative. The input limit caps the scale at 3 (so ≤ 6), and therefore `BigDecimal.toString()` never uses exponent notation. Gson writes a `BigDecimal` through `toString()`, so `0.25` goes out as `0.25`, `10` as `10` and never as `1E+1`. A test pins that the JSON contains no `E` |
| Whole bag | Omit `quantity`. It is never sent as `0`: both mean one bag, but omitting it is unambiguous and matches §4 "optional" |
| Fraction check | The scanner cannot know a pallet's material before the reply (F6). A **part bag therefore requires the operator to pick the material row first**. The app checks `q ≤ row.remaining`. After the reply, if the row whose `collected` grew is not the picked row, the screen shows a warning naming the material that was actually recorded. The server still enforces bag size, stock and target (F5) |
| Last bag | A whole-bag capture rejects when the bag exceeds the remaining target (F5). The part-bag sheet offers a "Remaining: 2.5 kg" chip that fills in the picked row's exact remaining. The rejection message from the server is shown as-is |

---

## 6. Recovery and the "confirm replacement" prompt

Phase 1 already provides:
- `sendCommand` / `retryCommand`, with the outcomes `Settled`, `Unresolved`, `NotSaved` and `NoLongerPending`;
- `CommandRecoveryUseCase`;
- `PendingCommandCoordinator`, which recovers commands from earlier sessions on Home after login and on reconnect;
- the Home card.

Phase 2 adds the following.

| Situation | Behaviour |
|---|---|
| Same session, `Unresolved(RetryIdentical)` (timeout, `outcome_unconfirmed`, publish failed) | The PREP (or Prepare) screen shows "Station 2 has not confirmed this bag. Don't scan it again." with a **Retry** button that calls `retryCommand(command)`, so the bytes and id are identical (REC-01). The screen retries automatically once on each transition back to CONNECTED. It also retries once when the screen opens and finds a same-session entry for this target ("reopen the job to retry it", the Phase 1 Home wording). There is no timer loop |
| `Unresolved(LoginThenRecover)` | The transport has already cleared the session, and `SessionWatcher` sends the operator to Login. After login, Home's coordinator recovers the command. The PREP screen shows nothing extra |
| `Unresolved(Recover)` (`message_id_conflict`, or an unknown failure) | The screen immediately calls `CommandRecoveryUseCase.recover(command)`. Recovery in the same session is allowed; it uses a new id. The result is handled as in the rows below |
| `Unresolved(ManagerReconcile)` | Blocking banner: "A manager must reconcile this bag at the station before you scan again." The entry gate's manager-clear removes it later |
| Recovery `Committed` | Notice: "Station 2 had already recorded this. It was not repeated." Re-read the PREP |
| Recovery `Rejected` | Notice: the saved rejection message, then a re-read. No replacement is offered: the operator corrects deliberately, which means scanning again |
| Recovery `NotExecuted` for **capture** | **Replacement prompt.** After a fresh `read` of the PREP, a dialog shows the original `code` and quantity (from `PendingCommand.payload`, via `CaptureIntent.fromPayload`) and the current remaining, then asks: "Station 2 never received this bag scan, and it can no longer happen. Record it now?" **[Record bag]** sends a *new* `capture` (new messageId) with the same code and quantity. It is only enabled while `canScan`. **[Don't record]** dismisses the notice |
| Recovery `NotExecuted` for **confirm** | The same dialog, with "Confirm collection now?". Enabled only when `canConfirm` |
| Recovery `NotExecuted` for **prepare** | Nothing is auto-filled, because availability may have changed. The Home notice gets **Open job {JC}**, which opens the Prepare screen with a fresh read |
| `receipt_sealed` as a direct reply | This can only happen to a retry of an id that recovery has already sealed. It is Settled (definite). Re-read, then show the same replacement prompt as `NotExecuted` |
| `NotSaved` | "The scanner couldn't save this scan, so nothing was sent. Try again." The scan stays staged; a new tap makes a new intent |
| `NoLongerPending` (on retry) | "Already settled elsewhere — refreshed." Re-read |

Where the prompt appears:
- The coordinator's `notices` is a shared `StateFlow`. The PREP VM observes the notices whose `command.targetId == prepId` and `outcome == NotExecuted`, and shows the dialog.
- Home's notice row for a `NotExecuted` capture or confirm gets an **Open PREP_…** action that navigates to that PREP screen, where the dialog appears.
- Either choice in the dialog calls `coordinator.dismiss(notice)`.

---

## 7. Errors per code (Phase 2 actions)

| `error` | Class (Phase 1 §4.2) | Prepare screen | PREP screen (capture/confirm) |
|---|---|---|---|
| `""` (success) | Settled | Open the new PREP from `result.targetId` | Apply the snapshot; "Recorded 10 kg Resin — 30 kg to go" (from the row diff) |
| `rev2_rejected` | Settled | Apply the snapshot: refresh available, choices and revision. Keep the operator's answers for codes that still match and drop the rest. Show `operatorMessage` | Apply the snapshot. Show `operatorMessage` plus each `commandExceptions` row: **date and time (SAST)**, operator ID, kind and details (contract §6) |
| `collection_revision_conflict` | Settled | Not expected; treat as `rev2_rejected` and re-read | Not expected; treat as `rev2_rejected` and re-read |
| `action_not_allowed` | Settled | "Station 2 doesn't allow this here." Re-read | The same |
| `invalid_envelope`, `password_field_forbidden` | Settled | "Station 2 rejected the message format ({code}). Report this." Logged as an app defect | The same |
| `client_upgrade_required` | Settled | The existing upgrade latch | The same |
| `receipt_sealed` | Settled | Re-read; "This prepare never ran." | Re-read, then the replacement prompt (§6) |
| `outcome_unconfirmed`, no reply, publish failure | Unresolved RetryIdentical | Banner + Retry | Banner + Retry; blocked |
| `operator_session_invalid` | Unresolved LoginThenRecover | Navigate to Login (existing) | The same |
| `message_id_conflict` | Unresolved Recover | Recover now | Recover now |
| `receipt_owner_mismatch`, `receipt_recovery_unavailable` | Manager | Blocking banner | Blocking banner |
| Not connected (pre-check) | Settled NoResponse NotConnected | "Not connected — nothing was sent" | The same |

`operatorMessage` is shown, never parsed. Branching uses `error` only.

---

## 8. Screens and navigation

New routes go inside the existing `jobs` nested graph (`navigation/NavRoutes.kt`, `AppNavGraph.kt:109-126`):

| Route | Screen | ViewModel |
|---|---|---|
| `jobs/prepare/{jobCard}` | `ui/preparation/PrepareScreen.kt` | `PrepareViewModel` (own instance) |
| `jobs/prep/{prepId}` | `ui/preparation/PreparationScreen.kt` | `PreparationViewModel` (own instance) |

A successful prepare navigates to `jobs/prep/{id}` with `popUpTo(jobs/prepare/{jobCard}) { inclusive = true }`, so Back returns to the job detail. Job detail already re-reads when it opens (`JobDetailScreen.kt:32`).

**Job detail** (`JobDetailScreen.kt`) gains:
- a **Prepare mixes** button. It is enabled when `mixProgress.availableToPrepareMixes > 0`, the JC is not closed, and there is no Unresolved `prepare` with `targetId == jobCard`;
- clickable active-preparation and history rows, which open `jobs/prep/{id}`;
- a pending-prepare banner with Retry when one exists in the same session.

**Job lookup** accepts input that starts with `PREP_`, typed or scanned. It opens that PREP directly (GEN-06) instead of rejecting it as non-digits.

**Prepare screen**, top to bottom:
1. JC header with the §8.1 counts line.
2. If active PREPs exist: "This job already has N active preparation(s). Open one to continue it — Prepare creates a new allocation." with links (GEN-13).
3. Mix count: a stepper plus a numeric field, from 1 to `availableToPrepareMixes`.
4. One row per `requiredIngredientChoices` entry: code, description and note, with **Include** / **Exclude** segmented buttons that start with *neither* selected (contract §7: "explicit"). If the list is empty: "No ingredient choices for this job."
5. Optional reason (≤ 1000 characters; see Q5).
6. The **Prepare N mixes** button. A confirm dialog lists N and the excluded codes.

**PREP screen**, top to bottom:
1. Header: PREP id, JC, product, stage chip, mix count, and the job counts line.
2. Banners: unresolved, manager, or the outcome message.
3. Effective material rows: name, code, `collected of required unit`, and remaining. Excluded rows are greyed out and show "excluded — original X". Operator-added rows (`originalRequired == 0`) are marked "added". A row can be tapped to pick it for a part bag.
4. Scan card, shown only while Collecting: "Scan a pallet tag or label". After a scan it shows the code with **Record bag** and **Record part bag…**. There is also a manual code field.
5. Confirm button, shown only in AwaitingConfirmation.
6. Exceptions list (`collectionExceptions`): date and time (SAST), operator, kind, details, reason, and review status ("Open" / "Reviewed by X at T").

Scans reach the PREP screen only while it is resumed. The flag works like `JobLookupViewModel.setLookupScreenActive`. A user badge (`UserTagPolicy`) is refused with "That is a user badge, not a pallet". While a command is in flight, scans are ignored with a short message.

**Hints.** `MqttRepository` gains `serverHints: SharedFlow<ServerHint>`. `setServerPushHandler` is single-slot and is already owned by `JobLookupViewModel` (`JobLookupViewModel.kt:83`), so a second screen cannot use it. Both VMs collect the new flow. The PREP VM re-reads its PREP on a General hint. A hint never touches pending commands (PUSH-02); a test pins this.

**Timestamps.** Exception rows always show the date and the time, in `Africa/Johannesburg` (contract §2, §6). This uses a new `formatStationDateTime`; the existing `formatStationTimestamp` drops the date for today.

---

## 9. Scanning a bag maps to one capture

- `ScanEvent.RfidTag(tagId)` or `ScanEvent.Barcode(value)` gives `code = value.trim()`. The app does **not** parse or transform the code: the server matches it exactly against the RFID tag, label barcode or pallet barcode (F7).
- The scan only **stages** a capture. The capture is sent when the operator taps **Record bag**. Each tap is one deliberate bag, so it gets a new messageId (contract §9), including consecutive bags from the same pallet. Repeated RFID reads of the same tag just re-stage the same code and send nothing. This guards against double deductions from an RFID reader that reports continuously (see Q1).
- After a successful capture the staged code **stays staged**, with "Record another bag from {code}", because operators usually take several bags from one pallet. Each tap is still a separate intent.
- Empty codes and badge EPCs (`505055…`) are refused locally.

---

## 10. Simulator (`tools/backend-sim`)

Today the simulator answers only `lookup` and `read`; other actions get `action_not_allowed` (`handlers/rev2_general.py:15-34`). It does not implement `recover` at all. Phase 2 adds the following:

1. **Receipts** keyed by `(device, suffix, messageId)`, storing the raw SHA-256, the result, the operator and a `sealed` flag. An identical replay returns the saved result with a fresh snapshot. A different raw hash gives `message_id_conflict`. A sealed identity replies `receipt_sealed` (F12).
2. **`recover`**: `committed` / `rejected` / `not_executed`. `not_executed` seals the identity. An owner mismatch gives `receipt_owner_mismatch`, and a hash mismatch gives `message_id_conflict` (F13).
3. **Catalog**: `ingredientExceptions` seeded with `1500000331` (a small additive in BOM 510019068), plus `exceptionListRevision`. `requiredIngredientChoices` follows F3.
4. **`prepare`**: the allocation check (F1), choice validation in the server's order (F2), effective rows (`originalRequired = perMix × N`; excluded rows get `required 0`), and "Ingredient omitted" exceptions.
5. **`capture`**: the pallet lookup by `palletRfidTag` or `palletId` (standing in for a label barcode); usable pallets (Holding/Mixing, not blocked); bag size = `fullBagWeight` or `config.defaultFullBagWeightKg`; the F5 checks with Python `Decimal`; deduction; revision++; the AwaitingConfirmation transition; rejection exceptions (F10).
6. **`confirm`** (F9). `mix_progress.collectedMixes` is computed the way `Rev2JobProgress.cs:26-28` does it.
7. Seed `PREP_demo0001` with real effective materials. Today it has `materials: []` and cannot be collected against.
8. Hints carry the action's `reason` (`preparation_created`, `capture`, `confirm`, `collection_exception_recorded`).
9. A new fault, **`drop_reply`**: process the request and save the receipt, but do not publish the reply. Together with the existing `withhold` (never processed), this lets the device check exercise REC-01, the `committed` recovery and the `not_executed` replacement prompt.
10. Selftest cases for each item above.

---

## 11. Acceptance mapping

| ID | Client evidence in Phase 2 |
|---|---|
| GEN-02 | The prepare request carries N ≤ the freshly read available count. A concurrent-allocation rejection re-reads and clamps N; the operator must tap again, and that new tap gets a new id |
| GEN-03 | Each code needs an explicit choice. The request carries the read's catalog revision. Excluded rows show their original quantity. The omission exception is shown |
| GEN-04 | The client never sends null, duplicate, unknown or tackifier choices. A stale or all-excluded rejection re-reads and asks for the choices again |
| GEN-05 | Empty catalog: `ingredientChoices:[]` plus the revision. A saved PREP never re-asks for choices |
| GEN-06 | A desktop-created PREP opens by `read` with its id (from the job detail or a typed/scanned `PREP_…`); `prepare` is never sent to open one |
| GEN-07 | Whole bag means `quantity` is omitted. A fraction is an exact BigDecimal, checked against the picked row's remaining. Server rejections are shown |
| GEN-08 | A rejected capture shows the server's exception record: time, operator ID, details |
| GEN-09 | Remaining and the revision update from each reply. Confirm is offered only in AwaitingConfirmation, and the stage becomes ReadyForMixer |
| GEN-10 | No role gating anywhere (the existing rule in `OperatorSessionHolder.kt:17`) |
| GEN-11 | Job and PREP screens show the server's `mixProgress` and the active PREP id, count and stage (Phase 1 display, reused) |
| GEN-12 | The PREP screen never derives `collectedMixes` or active counts; it shows the server's values |
| GEN-13 | The job detail lists every active PREP, and Prepare warns that it makes a new allocation |
| REC-01 | Retry republishes identical bytes and id (Phase 1 transport). The PREP screen blocks rescans and offers Retry or auto-retry |

---

## 12. Open questions for the user

The contract and server leave these undecided. The plan applies the **proposed default** but flags each one so it can be changed before release.

**Answers (2026-10-09):** Q1–Q9 not yet answered by the user; the proposed defaults are kept, each in one named place (`CaptureMode`/staged-then-tap, `QuantityInput.MAX_SCALE`, `PREPARE_REASON_MAX`, `STATION_ZONE`).

1. **One tap per bag, or auto-send on scan?** Proposed: a scan stages and a tap sends (§9). This is safer with an RFID reader that reports continuously. Auto-send would be faster for barcode-only use.
2. **Should RFID reads be accepted as bag sources at all?** An RFID reader can pick up a *neighbouring* pallet's tag, and that capture would deduct from the wrong pallet. Proposed: accept both, and show the staged code before the tap. Alternative: barcode only for captures.
3. **Fraction precision.** The contract says "exact decimal comparison" but no scale. Proposed: at most 3 decimal places (grams, for kg).
4. **Bag size and pallet material are not exposed to the scanner.** The app cannot show "1 bag = 25 kg" or warn that a whole bag exceeds the remaining target before sending. Proposed: require the operator to pick a row for part bags, and offer a "Remaining" chip. Should we ask for a contract addition (for example, a source lookup or an expected `materialCode` on `capture`) through `RFID_MQTT_CONTRACT.md`?
5. **`prepare.reason`.** The server records it but sets no length limit. Is it required when something is excluded? Proposed: optional, trimmed, at most 1000 characters (matching §6), and omitted when blank.
6. **Display time zone.** Proposed: always `Africa/Johannesburg` for exception records, as §2 says. Should the rest of the app also switch from the device zone to it?
7. **The blocking scope of an unresolved capture.** Proposed: block only the same PREP. Should an unresolved capture block every capture on the scanner instead?
8. **Code normalisation.** The server trims the code and matches it under SQL collation, probably case-insensitively. Proposed: send the trimmed value exactly as the reader produced it. Does the site need EPCs upper-cased?
9. **Opening a Cancelled PREP** (by direct id). Proposed: show it read-only as history, with no actions.
