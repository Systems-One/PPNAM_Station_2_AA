# PPNAM Station 2 — Android App / Backend Test Report

**Date:** 2026-07-23
**Device:** Chainway C72 (`handheld_1`), Android 13, 1080×1920 @ 480 dpi
**App:** `com.mitas.ppnam.station2aa` v1.0 (1), branch `master`
**Backend:** **live Station 2 backend** (`PPNAM/station_2/status` retained `online`)
**Broker:** `wss://mqtt.sysone.co.za:443/mqtt`, mosquitto 2.0.22, auth `admin/admin`
**Operators tested:** `Jono` (Operator), `Avi` (Admin)

---

## How this test was run

- **MQTT.** A passive Python sniffer subscribed to `PPNAM/#`, writing every frame to
  `wire.jsonl`, pairing each response to its request and timing the round trip. It never
  published — the app was the only actor.
- **Scans.** Injected as genuine Chainway broadcasts
  (`am broadcast -a com.scanner.broadcast --es data <code>`), the exact path the hardware
  scanner uses (`DataWedgeReceiver`). Pallet codes came from
  `C:\Dev\Barcode_Generator\barcodes.html` (114 pallets).
- **UI.** Driven via `uiautomator` element lookup + `adb input`, screenshot at every step.
- **Logs.** Full `adb logcat` capture for the session.

The simulator in `tools/backend-sim` was deliberately **not** started — the real backend was
online on the same broker and both would have answered every request.

## Coverage

| Area | Result |
|---|---|
| All 20 supplied job cards loaded | **20/20 accepted**, BOM sizes 1–8 lines |
| Login: wrong password, Operator, Admin, logout | pass |
| Session persistence across app restart | returns to Login (by design) |
| Full collect flow with real scans | pass — 6 collections driven to completion |
| Manager-approval path | triggered and inspected |
| Role comparison Operator vs Admin | 17 vs 24 actions — **UI identical** |
| **Concurrent cycles, multi-machine, multi-area** | **pass** — 4 cycles across 2 areas |
| **Mixer → mix batch → downstream machine routing** | pass, gating rules enforced |
| **Finish cycle / force close** | pass, but see B10 |
| **RFID pallet lookup + holding recovery** | pass / rejected with poor message |
| Navigation, back, resume, job switching | pass |
| Settings + supervisor PIN lockout | pass |
| Offline → reconnect → retry | pass |
| Invalid production order | handled gracefully |

**All 13 request types the app implements were exercised.** 48 findings recorded
(F-001 … F-048 in `FINDINGS.md`).

> **Scope warning.** The app implements only part of the Station 2 process. Allocation,
> extrusion run management, the pre-mix lifecycle and job-card submission have **no UI at
> all** — see A11. "Fully tested" here means *everything the app can currently do*.

---

# Part 1 — What the backend needs to improve

> **Note.** Multiple collections per job card (one per `job_card_load_requested`) is
> **intended, client-requested behaviour** and is not treated as a defect anywhere in this
> report. The only related item retained is a presentation one — see A15.

## B1. `bagSize` / `expectedBags` contradict the backend's own arithmetic — **CRITICAL**

Job card 510019355, line 0, material `1600000044` "LF 2220 (BUBBLE WRAP)". The backend sends:

```
bagSize = "20.000 kg"    expectedBags = 10.9946    requiredQuantity = 219.892
```

The app faithfully shows *"Bag size: 20.000 kg"* and *"0.00 / 10.99 full bags"*. Scanning
**9** bags returned:

```
scannedBags = 9.0    capturedKilograms = 225.0    collected = true   satisfied = true
```

225.0 ÷ 9 = **25 kg per bag** — the pallet's real bag weight (25 000 kg ÷ 1 000 bags), not the
20 kg advertised. At 20 kg, 9 bags is 180 kg — 39.9 kg short — and could not have satisfied a
219.892 kg requirement.

Confirmed by the converse: **10** bags was *rejected* with `requiresManagerApproval: true`
and "Additional quantity requires manager approval" — 10 × 25 = 250 kg, i.e. 1.5 bags over,
exceeding `overCollectionToleranceBags: 1`. "Additional" only makes sense at 25 kg/bag.

**Impact.** The operator is told to fetch 10.99 bags; 9 completes the line; they physically
move 225 kg while the screen implies 180 kg. Every quantity displayed for this line is wrong,
and the tolerance is judged against a number the operator cannot see.

**Corroborated from a second endpoint.** `pallet_lookup_result` for that same pallet returns
`remainingQuantity: 24775.0` and `remainingBags: 991` — **25.0 kg per bag**. So two backend
endpoints disagree about the bag size of one material, and the app will show an operator both
figures. This is a data-integrity problem in the BOM/UoM conversion, not a display bug.

**Fix.** Publish the pallet's true bag size in `bagSize`/`expectedBags`, or do the arithmetic
in the advertised units. The two must agree. This needs an audit across all materials — if the
BOM's UoM conversion and the pallet master disagree generally, this is a systemic stock
accuracy problem, not one bad row.

## B2. Latency is the biggest day-to-day problem — High

Measured live, end to end:

| Action | n | min | mean | max |
|---|---|---|---|---|
| `job_card_load_requested` | 23 | 1 382 | 4 013 | **9 771** |
| `ingredient_scan_requested` | 5 | 2 553 | 4 142 | **7 585** |
| `login_requested` | 3 | 2 686 | 2 765 | 2 861 |
| `active_job_cards_requested` | 24 | 315 | 1 520 | 4 384 |
| `mixing_overview_requested` | 2 | 418 | 1 676 | 2 933 |
| `collection_resume_requested` | 1 | 553 | 553 | 553 |

Load time tracks BOM line count (2 lines ≈ 2.0 s, 5 lines ≈ 7.5–9.8 s), which points at
per-line work — most likely one SAP/stock call per ingredient — rather than one batched query.
**Batching those calls is the highest-value backend change available.**

For scale: the app's request timeout is 20 s (`AppSettings.requestTimeoutMs`). A 9.8 s worst
case already spends half the budget.

**Reliability itself was excellent — zero unanswered requests all session.** The issue is
purely speed.

## B3. `errorCode` carries a GUID instead of an error code — High

The approval-required rejection returned:

```
errorCode   = "dac675694be14b7685280f006640ed6e"
exceptionId = "dac675694be14b7685280f006640ed6e"
```

Everywhere else in the capture `errorCode` is correctly symbolic — `validation_failed` for a
bad password, `service_unavailable` for an unknown production order. This one path duplicates
the opaque `exceptionId` instead, so any client branching on `errorCode` breaks on it. Keep
`errorCode` symbolic (e.g. `manager_approval_required`) and leave the GUID in `exceptionId`.

## B4. Passwords cross the wire in cleartext — High (security)

`login_requested` carries `"password": "<plaintext>"`. TLS protects transit, but the app ships
hardcoded broker credentials `admin/admin` (`AppSettings.kt:8-9`) granting `PPNAM/#`. I used
exactly those credentials to read an operator's password off the broker. Anyone who can
`strings` the APK can harvest every operator password. The manager-approval dialog sends a
second password the same way.

Recommended, in order: per-device broker credentials instead of a shared `admin/admin`;
broker ACLs restricting each device to its own topic prefix; then stop sending the password at
all — challenge/response, or client-side hash with a server-issued salt.

## B5. Response `timestampUtc` is wrong — Medium

Every response is stamped *earlier than the request it answers*, and seconds before it is
actually sent:

```
req  login_requested   timestampUtc 2026-07-23T10:12:03.450922Z
res  operator_context  timestampUtc 2026-07-23T10:12:03.3372679+00:00   <- 113 ms earlier
                       reached the broker at 10:12:08.805               <- 5.5 s later
```

Observed on **29 of 29** matched request/response pairs in the capture — it is systematic, not
occasional. Stamp responses at send time; today no client can use them for staleness, ordering,
or replay checks.

## B6. Error contract is inconsistent — Medium

Rejections put the human text in `reason` and **omit `errorMessage` entirely** rather than
sending it null — 4 of 4 rejections captured did this. Clients must special-case `reason` per
response type. Settle on one field and always emit it.

## B7. Over-collection is absorbed without any visible record — Medium

Scanning 90 bags against an 89.03-bag requirement recorded `collectedQuantity: 2225.657` (the
plan) while `capturedKilograms` was 2250.0 and pallet stock dropped the full 2250 kg. The
backend tracks both correctly, but nothing reports the 24.34 kg variance. Across every line,
every shift, that is a real reconciliation gap. Expose the variance per line and per
collection, and consider requiring acknowledgement past a threshold.

## B8. `consumedApprovalId` returned when no approval was required — Low

An accepted scan returned `requiresManagerApproval:false`, `hasApprovedException:false`,
`approvalState:""` — yet `consumedApprovalId:"fb326e2e12e94aaf8fa9df4a21d23176"`. Either the
field should be empty, or approval records are being silently spent. Worth an audit.

## B9. Timestamp serialization differs between the two sides — Low

Requests emit `...Z` with 6 fractional digits; responses `...+00:00` with 7. Same contract, two
serializers.

## B10. Force-closed cycles still yield a usable mix — High (business logic)

The force-close dialog states the cycle is "released **without completing**". In practice
force-closing CYC_000010 released MXR-01 *and* published **MIX_000006** into "Ready mixes",
rendered **identically** to MIX_000009 which came from a properly finished cycle. Nothing
marks it as abandoned.

An operator can therefore feed a deliberately-abandoned mix into an extruder with no
indication that the cycle was never completed. Either force-close should discard/quarantine
the mix, or the mix must carry and display a "from force-closed cycle" flag.

## B11. Misleading recovery rejection — Medium

Recovering `DUMMY-ST2-1600000050` (status `InUse`, location `Extrusion`) for a collection
whose line 0 material **is** `1600000050` was rejected with *"Recovery product is not valid
for the active ingredient collection."* The product is valid; the obstacle is the pallet's
location/state. The message sends the operator hunting the wrong problem.

## B12. Logout returns an empty `sessionState` — Low

`reader_logout_requested` → `operator_context` with `accepted:true` but `sessionState: ""`,
where the failed-login response correctly used `"Closed"`. Two `operator_context` responses
were also published for the single logout request.

---

# Part 2 — What the app needs to improve

## A1. No window insets anywhere — root cause of the recurring toolbar cropping — High

`MainActivity.kt:24` calls `enableEdgeToEdge()`, so the app deliberately draws under the system
status bar. But **no insets are ever applied**: grep for `statusBarsPadding` /
`systemBarsPadding` / `imePadding` / `safeDrawing` across `app/src/main/java` returns **zero**
matches, and `AppScaffold`'s `Scaffold` (line 89) passes no `contentWindowInsets`. The manifest
declares no `windowSoftInputMode`.

Observed consequences on the C72:

- The top bar sits under the status bar; the title overlaps the clock.
- When the IME opens, the window pans and the app bar is pushed **completely off-screen** —
  Back, title, Settings and the connection pill all unreachable (`shots/36-topbar-scrolled.png`).
- Because logout lives in that bar, **an operator cannot log out while a keyboard is open**.

This is the underlying cause of the cropping issues that have been patched repeatedly at the
layout level (row heights, text sizes, truncation). **Fix at the source:** apply
`Modifier.statusBarsPadding()` (or `WindowInsets.safeDrawing`) to the scaffold/top bar and
`imePadding()` to scrollable content.

## A2. `allowedActions` / `allowedTabs` are received then ignored — High

The backend computes a precise permission set. Measured:

- **Operator (Jono):** 17 actions
- **Admin (Avi):** 24 actions
- Admin-only: `allocation_return`, `allocation_transfer`, `cancel_premix_direct`,
  `ingredient_approve_override`, `ingredient_approve_short_bag`,
  `ingredient_collection_cancel`, `machine_force_close`
- `allowedTabs` identical for both

The app parses this (`AuthMessages.kt:35-36`), stores it (`OperatorSessionHolder.kt:20-22`),
maps it (`AuthUseCase.kt:63-64`) — and **no UI code reads either field** (verified: zero
references under `ui/`). Both roles render an identical UI. None of the admin's 7 extra
privileges is surfaced; no operator-facing control is hidden. An unpermitted action is
discovered only after a 2.5–7.6 s round trip. The data to grey those controls out is already
on the device.

## A3. Scan dialog opens with no line armed, then silently discards the entry — High

`MixingViewModel.kt:275-284`:

```kotlin
val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
if (line == null) {
    _supervisorError.trySend("Select a material line before scanning a pallet.")
    _uiState.value = orderLoadedState(order)
    return          // <- nothing is published
}
```

The bag dialog is shown for *any* scanned pallet regardless of arming. The operator scans,
types a bag count, taps **Confirm Scan**, and the dialog closes having sent nothing. A snackbar
does fire — but only after all that work, and it auto-dismisses. Reproduced twice; the wire
shows no `ingredient_scan_requested`.

**Fix.** The app already knows the pallet's material code — auto-arm the matching line. Failing
that, reject the scan up front and never open the dialog.

## A4. ¼-bag granularity cannot express BOM quantities — High

BOM lines require fractional bags — 89.03, 10.99, 37.09, 2.97, 0.74. The dialog offers only
`0, ¼, ½, ¾`. Meanwhile under-collection has **no** tolerance (89.00/89.03 →
`isRequirementSatisfied:false`) while over-collection has a **1-bag** tolerance that
auto-passes. The only workable operator rule is *always round up*, and nothing says so.

**Fix.** Show remaining bags *inside* the dialog, pre-fill the rounded-up count, and state the
tolerance. Longer term allow decimal or weight entry for the final partial bag. (Note this
interacts with B1 — the bag figures shown are not currently trustworthy.)

## A5. Every backend call blocks the whole screen — High

`confirmIngredientScan` sets `_uiState.value = MixingUiState.Loading`, replacing the entire
screen. Combined with B2, an operator collecting a 6-line BOM spends ~30 s staring at spinners,
unable to see what they have already collected. Use an inline per-line pending state instead.

## A6. Layout defects — Medium

- **Line cards clip behind the bottom button bar.** The list lacks bottom padding for the
  Cancel / "Mixing after collection" bar, so the last card's "Short bags" action is cut in half.
- **Product name collides with the quantity.** The material name wraps under the right-aligned
  kg value and the two overlap ("MASTERBATCH BLACK ME 9200 ME" over "74.19 kg").
- **Two unlabelled progress bars** per line (kg and bags) look identical.

## A7. Login screen — Medium

- **Back exits the app.** One Back press from Login drops the operator to the launcher without
  even dismissing the IME. Easy to hit on a shared handheld.
- **The IME hides the password field and Log In button.** The form does scroll the focused
  field into view and IME Done does submit, but the button itself is unreachable while the
  keyboard is up.
- **"Or scan your badge" sits above the username field**, so the first thing read is the second
  option. Make it a divider between the two methods.

## A8. Logout is hidden behind the operator-name label — Medium

`AppScaffold.kt:118` wraps "Jono · Operator" in a `TextButton` opening the logout dialog. No
icon, no underline, no button styling — it reads as a status label. It is the **only** way to
switch users, and it is absent from the Settings screen.

## A9. Manager approval asks an already-authenticated admin to re-authenticate — Medium

Logged in as Avi (who holds `ingredient_approve_override` and `ingredient_approve_short_bag`),
the approval dialog still demands Manager/Admin username + password + audit reason.
Dual-control is defensible, but at minimum pre-fill the username and explain why. As built it
also puts a second cleartext password on the wire (B4).

## A11. Large parts of the Station 2 process have no UI at all — **plan-level**

The app implements **13 request types**. Several capabilities the backend grants have **zero**
source matches in the app:

- **Allocation** — `allocate_premix`, `allocate_full_pallet`, `allocate_bags`,
  `finish_allocation`, `allocation_return`, `allocation_transfer`.
  Note `allocation` is returned as an **`allowedTab`** for both roles, yet no allocation
  screen exists.
- **Extrusion** — `view_extrusion_overview`, `assign_extrusion_run`, `finish_extrusion_run`.
  (Downstream machines *can* be started from the board, which mints `RUN_*` ids — but there is
  no run overview or run management.)
- **Pre-mix lifecycle** — `complete_premix`, `cancel_premix`, `cancel_premix_direct`,
  `assign_hopper`.
- **Completion** — `complete_station2_work`, `submit_job_card`.

Of the Operator's 17 allowed actions the app can perform roughly 5; of the Admin's 24,
roughly 7. This is a roadmap fact rather than a defect, but it should be stated plainly:
the app today covers **collect → mix cycle**, and nothing after it.

## A12. Dialog action buttons sit under the IME — Medium

In the force-close dialog the Cancel / Force close buttons sit at y=1167 with the keyboard
open and y=1410 with it closed — the keyboard covers them, and taps aimed at the button land
on keys instead (an `i` was appended to the audit-reason field this way). The dialog neither
scrolls nor lifts its buttons above the IME. Same root cause as A1; also affects login (A7).

## A13. The two cancel dialogs contradict each other — Medium

First: *"This closes the job card **if it hasn't had any activity yet**… You'll be notified if
it can't be cancelled."* Immediately after: *"Cancelling a job card **always** needs a
manager's approval."* The app knows approval is unconditional — say so once, up front.

## A14. Raw ISO timestamps and a missing operator name — Low

The active-cycle sheet shows *"Started 2026-07-23T11:38:28.2733333+00:00 by "* — an
unformatted ISO-8601 string no shop-floor operator can read, followed by "by " with **no
name** (it renders `startedByOperatorId`, which is empty/opaque). Format as local time and
resolve the display name.

## A15. Active Jobs cannot distinguish multiple collections of the same job card — Medium

Multiple concurrent collections per job card is intended behaviour. But the Active Jobs list
renders each one as just the **order number plus the product description**, so four
collections of 510019340 appear as four visually identical rows
(`shots/79-logged-out.png`) — even though at the time they held 0%, 75%, 75% and 0%
progress respectively.

The operator has no way to pick the one they were working on. The backend already returns
everything needed per entry — `collectionId`, `progressPercent`,
`completedIngredientCount`/`requiredIngredientCount`, `status` — none of which is displayed.

**Fix:** show the collection ID and progress on each Active Jobs row (e.g.
"COL_000039 · 3 of 4 lines · 75%"). This is a display change only; the underlying behaviour
stays as designed.

## A10. Smaller items — Low

- Production Order No. opens a **QWERTY** keyboard for a digits-only field (the bag-count field
  correctly uses a numeric keypad).
- A wrong supervisor PIN gives **no message** — `pinError` is a Boolean used only as
  `isError`, so the operator sees a red border and no explanation. (The lockout itself works:
  5 attempts → "Too many attempts. Try again in 30s.")
- The **snackbar is light-on-dark** — a bright panel in an otherwise dark UI.
- The **splash screen is white**, flashing on every cold start in a dim plant.
- The invalid-order message is internal jargon: *"SAP production order lookup failed and no
  stored local BOM snapshot is available."*

---

# Part 3 — What works well

Worth protecting in any refactor:

- **Concurrency is genuinely solid.** Four cycles ran simultaneously across two areas
  (MXR-01/02/03 in Main Mixing Room plus JAN-MIX-01 in JANDI). Machine states flipped
  Available/InUse correctly and independently; force-closing CYC_000010 left CYC_000011 and
  CYC_000012 untouched; area-picker counters stayed accurate ("27 machine(s) available ·
  3 active cycle(s)"). No cross-talk, no stale state.
- **Two-stage routing is correct.** Finishing a mixer cycle produced MIX_000009 whose
  `validNextMachineCodes` listed `JAN-DRUM-01, JAN-02, JAN-03` and correctly **excluded
  JAN-04**, which is transfer-drum gated. Routing the mix onward started RUN_000010 on JAN-02
  in 1817 ms.
- **RFID pallet lookup** is the fastest and cleanest screen in the app — 1351 ms, full pallet
  detail, clear Scan Another / Done.
- **Unrecoverable pallets prompt in context.** Scanning a pallet outside Holding/Mixing
  offered "Pallet not in Holding — Recover?" rather than a dead end.
- **Reliability.** Zero unanswered requests across the whole session — 20 job-card loads,
  repeated scans, 5 cycle starts, a forced disconnect. The bounded-retry and resubscribe logic
  is sound.
- **Offline handling is genuinely good.** Dropping Wi-Fi flipped the pill to "Reconnecting"
  within 12 s; an action attempted offline gave "Not connected to Station 2" with a **Retry**;
  restoring Wi-Fi reconnected automatically in ~32 s and Retry restored full function.
- **Back-navigation is safe.** Leaving the collect screen raises "Go back? … Your progress is
  saved." — and progress genuinely is preserved.
- **Resume is effortless.** Job Lookup lists Active Jobs; one tap resumes.
  `collection_resume_requested` returned in 553 ms, the fastest call measured.
- **Scan confirmation before publishing.** The app never commits on the raw barcode alone.
- **Completion flows cleanly** into area selection ("COL_000028 ready to mix — pick an area")
  with live machine counts per area.
- **PIN lockout works** and is a real protection.
- **The dynamic broadcast receiver works.** The Android 11+ package-visibility workaround in
  `DataWedgeReceiver` is correct; every injected scan arrived.

---

# Recommended order of work

| # | Item | Why first |
|---|---|---|
| 1 | **B1** bag size / expectedBags mismatch | Operators are moving the wrong quantity |
| 2 | **B4** cleartext passwords + shared `admin/admin` | Credential exposure, trivially exploitable |
| 3 | **B10** force-closed cycles yield usable mixes | Abandoned material can reach production |
| 4 | **A1** apply window insets | Fixes the whole toolbar-cropping class at source |
| 5 | **B2** batch the per-line backend calls | Removes the ~30 s per-BOM wait |
| 6 | **A3** auto-arm the scanned line | Silent data loss during normal use |
| 7 | **A2** honour `allowedActions` | Role safety; data already on device |
| 8 | **A4 / B7** bag granularity + variance reporting | Stock accuracy |
| 9 | **B3, B5, B6, B9, B11, B12** contract & message hygiene | Cheap, prevents client breakage |

Items 1 and 3 are **stock-integrity** defects. If only one thing is fixed this week, make it
B1 — operators are physically moving quantities the screen does not describe.

---

# Appendix A — Job card coverage (all 20)

| Job card | Result | BOM lines | Load ms |
|---|---|---|---|
| 510019337 | OK | 6 | 4 945 |
| 510019338 | OK | 5 | 7 460 |
| 510019339 | OK | 3 | 3 070 |
| 510019340 | OK | 4 | 2 817 |
| 510019341 | OK | 2 | 1 979 |
| 510019342 | OK | 5 | 1 807 |
| 510019343 | OK | 5 | 9 771 |
| 510019344 | OK | 5 | 3 597 |
| 510019346 | OK | 3 | 7 771 |
| 510019347 | OK | 2 | 1 780 |
| 510019348 | OK | 4 | 3 809 |
| 510019349 | OK | 4 | 1 382 |
| 510019350 | OK | 4 | 4 511 |
| 510019351 | OK | 4 | 1 952 |
| 510019352 | OK | 4 | 3 807 |
| 510019353 | OK | 4 | 2 561 |
| 510019354 | OK | 8 | 5 725 |
| 510019355 | OK | 1 | 2 620 |
| 510019356 | OK | 7 | 2 377 |
| 510019359 | OK | 7 | 2 199 |

All returned `bom_loaded` / `accepted:true` / `status:Collecting`. No rejections, no timeouts,
no missing responses.

# Appendix C — Concurrency run

Five collections driven to completion and pushed through the board simultaneously:

| Cycle | Machine | Area | Source | Outcome |
|---|---|---|---|---|
| CYC_000010 | MXR-01 | Main Mixing Room | COL_000031 | force-closed → MIX_000006 (B10) |
| CYC_000011 | MXR-02 | Main Mixing Room | COL_000036 | ran concurrently |
| CYC_000012 | MXR-03 | Main Mixing Room | COL_000037 | ran concurrently |
| CYC_000013 | JAN-MIX-01 | JANDI | COL_000038 | finished → MIX_000009 |
| RUN_000010 | JAN-02 | JANDI | MIX_000009 | started from mix batch |

Three cycles in one area plus one in another ran at the same time with no interference.

# Appendix D — Request-type coverage

| Request type | Result |
|---|---|
| `login_requested` | pass (valid + invalid) |
| `reader_logout_requested` | pass |
| `active_job_cards_requested` | pass — slowest call, 10 366 ms |
| `job_card_load_requested` | pass (a new collection per load — by design) |
| `collection_resume_requested` | pass — fastest call, 553 ms |
| `ingredient_scan_requested` | **B1 bag size**, A3 silent drop |
| `ingredient_collection_cancel_requested` | partial — reached manager-approval gate (A13) |
| `mixing_overview_requested` | pass |
| `machine_cycle_start_requested` | pass ×5, incl. mix → machine |
| `machine_cycle_finish_requested` | pass — produced a mix |
| `machine_cycle_force_close_requested` | pass, but **B10** |
| `holding_recovery_requested` | rejected — B11 misleading message |
| `pallet_lookup_requested` | pass — corroborates B1 |

Not implemented in the app at all (A11): allocation, extrusion overview/runs, pre-mix
lifecycle, `complete_station2_work`, `submit_job_card`.

# Appendix B — Evidence

| Artefact | Contents |
|---|---|
| `capture/wire.jsonl` | every MQTT frame, passwords redacted, latency-paired |
| `capture/wire.log` | human-readable running transcript |
| `logcat.txt` | full device log for the session |
| `shots/*.png` | screenshot at every step |
| `FINDINGS.md` | raw running findings log, F-001 … F-032 |
| `sniffer.py`, `sweep2.py`, `analyze.py` | the harness, re-runnable |
