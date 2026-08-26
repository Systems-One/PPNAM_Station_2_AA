# PPNAM Station 2 — Live Test Findings Log

Environment: Chainway C72 (`handheld_1`), Android 13, app `com.mitas.ppnam.station2aa`.
Broker: `wss://mqtt.sysone.co.za:443/mqtt`, mosquitto 2.0.22, auth `admin/admin`.
Backend: **real Station 2** (`PPNAM/station_2/status` = retained `online`). Simulator NOT used.
Captures: `capture/wire.jsonl` (MQTT), `logcat.txt`, `shots/`.

---

## F-001 — SECURITY (High): operator passwords traverse MQTT in cleartext
`login_requested` payload contains `"password":"9999"` as a plain string.
Transport is TLS, so it is not on-the-wire readable, BUT:
- The app ships hardcoded broker credentials `admin/admin` (`AppSettings.kt:8-9`).
- Those credentials grant `PPNAM/#` subscribe — I used them to read the password.
So anyone who extracts the APK strings can harvest every operator password from the
broker. Evidence: `capture/wire.jsonl`, first `req/login_requested` record.

## F-002 — CONTRACT (Medium): response `timestampUtc` is earlier than the request
Request `timestampUtc` = `2026-07-23T10:12:03.450922Z`.
Response `timestampUtc` = `2026-07-23T10:12:03.3372679+00:00` — 113 ms BEFORE the
request was created, and ~5.5 s before the response actually reached the broker
(sniffer receipt 10:12:08.805). The backend is not stamping responses at send time.
Any client-side staleness or replay-window check on responses would misbehave.

## F-003 — CONTRACT (Low): inconsistent timestamp serialization
Request uses `...Z` with 6 fractional digits; response uses `...+00:00` with 7.
Two different serializers on the two sides of the same contract.

## F-004 — CONTRACT (Medium): error text is in `reason`, `errorMessage` is absent
Rejection carries `errorCode: "validation_failed"` and puts the human string in a
non-standard `reason` field. `errorMessage` is omitted entirely rather than null.
Clients must special-case `reason` per response type.

## F-005 — ENV (Low): device clock 2.67 s behind broker/host
Device UTC 10:12:38.872 vs host 10:12:41.545. Within the contract's acceptance
window, but there is no NTP discipline on the handheld; drift will grow.

## F-006 — UX (Medium): IME hides the password field and Log In button
On first focus the keyboard covers both. The form does scroll the focused field
into view, and the IME Done key does submit (good), but the Log In button itself is
never reachable while the keyboard is up. An operator who taps rather than presses
Done must first dismiss the keyboard.

## F-007 — UX (Low): "Or scan your badge" sits above the username field
The word "Or" precedes any alternative, so the first thing the operator reads is the
second option. Should read as a divider *between* the badge and manual entry.

## F-008 — PERF (Medium): 2.69 s for a credential rejection
`login_requested` -> `res/operator_context` round trip = 2686 ms for a simple
invalid-password answer. Slow enough to feel broken on a factory floor.

## F-009 — UX (Low): white splash screen flashes before dark UI
App is dark-themed throughout; the launch splash is white, causing a bright flash
on every cold start in a dim plant.

## F-010 — APP BUG (High): scan dialog opens with no line armed, then silently discards the entry
`MixingViewModel.kt:275-284` — `confirmIngredientScan` returns early when
`armedLineNumber == null`. But the bag-entry dialog is still shown for the scanned
pallet. The operator scans, types a bag count, taps **Confirm Scan**, and the dialog
closes having published nothing. A snackbar ("Select a material line before scanning
a pallet.") does fire, but only *after* all that work, and it auto-dismisses.
Reproduced twice; wire shows no `ingredient_scan_requested`.
**Fix:** either refuse the scan up front (don't open the dialog), or auto-arm the line
by matching the pallet's material code — the app already knows it.

## F-011 — UX (High): ¼-bag granularity cannot satisfy BOM quantities
BOM lines require fractional bags (line 1 = 89.03 bags, line 0 = 2.97 bags). The
dialog offers only 0, ¼, ½, ¾ fractions, so an exact match is usually impossible.
Under-collection has NO tolerance (`isRequirementSatisfied:false` at 89.00/89.03);
over-collection has a 1-bag tolerance and auto-passes. The only workable operator
rule is "always round up" — but nothing in the UI says so.
Consequence: systematic physical over-issue. Line 1 recorded 2225.657 kg against
2250 kg physically scanned — 24.34 kg unaccounted per line, silently.

## F-012 — UX (Medium): ingredient line cards clip behind the bottom button bar
The scrolling line list has insufficient bottom padding; the "Short bags" action on
the last visible card renders half-cut behind the Cancel / "Mixing after collection"
bar. Visible in `shots/16-line1-done.png`, `shots/18-line1-result.png`.

## F-013 — UX (Medium): product name collides with the quantity value
On the ingredient card the material name wraps under the right-aligned kg value and
the two overlap (`shots/10-jc-337-result.png`: "MASTERBATCH BLACK ME 9200 ME" runs
into "74.19 kg"). Needs a constrained-width name column.

## F-014 — UX (Low): Production Order No. field uses the alphanumeric IME
Order numbers are always digits; the field opens a full QWERTY keyboard. The bag
count field correctly uses a numeric keypad — the lookup field should match.

## F-015 — UX (Medium): Back on the login screen exits the app
A single Back press from Login drops the operator to the Android launcher without
even dismissing the IME first. On a shared factory handheld this is easy to hit by
accident. Expect either a confirm or for Back to be swallowed on the root screen.

## F-016 — UX (Low): snackbar uses a light surface in a dark-themed app
The error snackbar renders as a light grey panel with dark text, inconsistent with
every other surface and harsh in a dim plant.

## F-017 — PERF (High): backend round trips are slow and highly variable
Measured, live: login 2686/2861 ms, job_card_load 4945 ms,
ingredient_scan 3502 / 2553 / **7585** ms, active_job_cards 1027 ms.
Every scan blocks the UI behind a full-screen Loading state, so an operator
collecting a 6-line BOM waits ~30 s in aggregate on a good run.

## F-018 — APP (High): `allowedActions` / `allowedTabs` are received then ignored
The backend computes a precise per-operator permission set (Jono/Operator returns 17
`allowedActions` and 3 `allowedTabs`). The app parses them (`AuthMessages.kt:35-36`),
stores them (`OperatorSessionHolder.kt:20-22`), maps them (`AuthUseCase.kt:63-64`) —
and then **no UI code reads either field**. Verified: zero references under `ui/`.
Consequence: every operator sees every control regardless of role. Authorisation is
enforced only server-side, so an unpermitted action is discovered only after a full
round trip (2.5-7.6 s) and surfaces as a generic rejection. The data to grey out or
hide those controls is already on the device.

## F-019 — BACKEND (Low): `consumedApprovalId` returned when nothing needed approval
The first accepted scan returned `requiresManagerApproval:false`,
`hasApprovedException:false`, `approvalState:""` — yet
`consumedApprovalId:"fb326e2e12e94aaf8fa9df4a21d23176"`. An approval record was
consumed for an operation that required none. Worth auditing: either the field should
be empty here, or approvals are being silently spent.

## F-020 — OBSERVATION: backend latency scales with BOM size
job_card_load: 2 lines=1979 ms, 3=3070 ms, 4=2817 ms, 5=7460 ms, 6=4945 ms.
Suggests per-line work (likely an SAP/stock call per ingredient) rather than a single
batched query. Reliability itself was perfect — 0 unanswered requests across the run.

## F-021 — APP BUG (High): top app bar collapses under the status bar when the IME opens
On Job Lookup with the soft keyboard open, the two-row app bar collapses to one row and
the title draws **on top of** the system status bar clock; the entire second row
(operator name, RFID Pallet Lookup, Settings, connection pill) disappears.
Dismiss the IME and the bar is restored (operator row returns at y=96, title at y=222).
Reproducible; see `shots/31-topbar-clean.png` (broken) vs `shots/32-after-ime-dismiss.png`.
Root cause is the app bar losing its status-bar window inset when the IME resizes the
window — this is the same class as the previously-fixed C72 cropping issue, but the
IME-open case was not covered. Operator loses access to Settings and connection status
whenever the keyboard is up.

## F-022 — GOOD: invalid production order handled gracefully
Entering a non-numeric/unknown order returned a clean on-screen message and no crash:
"SAP production order lookup failed and no stored local BOM snapshot is available."
Wording is internal jargon for a shop-floor operator ("BOM snapshot"), but the failure
path itself is correct.

## F-023 — COVERAGE: all 20 job cards loaded successfully
Every one of the 20 supplied job cards returned `bom_loaded` / `accepted:true` /
`status:Collecting`. BOM sizes 1-8 lines. No rejections, no timeouts, no missing
responses across the whole sweep.

## F-024 — ROOT CAUSE of the recurring toolbar-cropping bug (High)
`MainActivity.kt:24` calls `enableEdgeToEdge()`, so the app deliberately draws underneath
the system status bar. But **no window insets are ever applied anywhere in the app**:
grep for `statusBarsPadding` / `systemBarsPadding` / `imePadding` / `safeDrawing` across
`app/src/main/java` returns **zero** matches, and `AppScaffold`'s `Scaffold` (line 89)
passes no `contentWindowInsets`. The manifest also declares no `windowSoftInputMode`.

Consequences observed on the C72:
- The top bar sits under the status bar; the title overlaps the clock.
- When the IME opens, the window pans and the app bar is pushed **completely off-screen**
  — Back arrow, title, Settings and the connection pill all become unreachable
  (`shots/36-topbar-scrolled.png`).
- Because logout is the operator-name button in that bar, an operator cannot log out
  while a keyboard is open.

This is the underlying cause of the cropping issues that have been patched repeatedly at
the layout level. **Fix at the source:** apply `Modifier.statusBarsPadding()` (or
`WindowInsets.safeDrawing`) to the scaffold/top bar and `imePadding()` to scrollable
content, rather than adjusting row heights and text sizes.

## F-025 — UX (Medium): logout is hidden behind the operator-name label
`AppScaffold.kt:118` wraps "Jono · Operator" in a `TextButton` that opens the logout
dialog. It has no icon, no underline and no button styling — it reads as a status label.
This is the **only** way to switch users, and it is absent from the Settings screen.

## F-026 — UX (Low): wrong supervisor PIN gives no message
`SettingsViewModel` sets `pinError` (a Boolean) which the screen uses only as
`isError = pinError` — a red field border, no text. Attempts 1-4 produce no explanation.
The lockout after 5 attempts *does* show "Too many attempts. Try again in 30s." (works
correctly, and is a good protection).

## F-027 — BACKEND BUG (Critical): `bagSize`/`expectedBags` contradict the arithmetic
Job card 510019355, line 0, material 1600000044 "LF 2220 (BUBBLE WRAP)".
Backend reports to the app:
```
bagSize      = "20.000 kg"
expectedBags = 10.9946        requiredQuantity = 219.892
```
The app faithfully displays "Bag size: 20.000 kg" and "0.00 / 10.99 full bags".
Scanning **9** bags of pallet `DUMMY-ST2-ACTIVE-20260717-1600000044` returned:
```
scannedBags = 9.0    capturedKilograms = 225.0    collected = true   satisfied = true
```
225.0 / 9 = **25 kg per bag** — the pallet's real bag weight (25000 kg / 1000 bags),
not the 20 kg the backend advertised. At the advertised 20 kg, 9 bags would be 180 kg,
39.9 kg short, and could not have satisfied a 219.892 kg requirement.

Confirmed by the converse: 10 bags was **rejected** with
`requiresManagerApproval:true`, "Additional quantity requires manager approval" —
10 x 25 = 250 kg, i.e. 1.5 bags over, exceeding `overCollectionToleranceBags:1`.
"Additional" only makes sense at 25 kg/bag; at 20 kg it would have been short.

**Impact:** the operator is told to fetch 10.99 bags, 9 completes the line, and they
physically move 225 kg while the screen implies 180 kg. Every quantity shown for this
line is wrong, and the over/under tolerance is evaluated against a figure the operator
cannot see. Either publish the pallet's true bag size in `bagSize`/`expectedBags`, or do
the arithmetic in the advertised units — but the two must agree.

## F-028 — CONTRACT BUG (High): `errorCode` carries a GUID instead of an error code
The approval-required rejection returned:
```
errorCode   = "dac675694be14b7685280f006640ed6e"
exceptionId = "dac675694be14b7685280f006640ed6e"
```
`errorCode` elsewhere holds a symbolic value (`validation_failed`). Here it duplicates
the opaque `exceptionId`. Any client branching on `errorCode` breaks. Keep `errorCode`
symbolic (e.g. `manager_approval_required`) and leave the GUID in `exceptionId`.

## F-029 — UX (Medium): approval message says "Additional" for a shortfall-looking case
The operator sees "Additional quantity requires manager approval." while the screen
says they have collected 0.00 of 10.99 bags — reading as a shortfall. The wording only
makes sense against the hidden 25 kg figure (F-027). Confusing in isolation.

## F-030 — UX (Medium): admin must re-enter their own credentials to approve
Logged in as Avi (Admin, holds `ingredient_approve_override` and
`ingredient_approve_short_bag`), the approval dialog still demands
Manager/Admin Username + Password + Audit reason. Dual-control is defensible, but the
already-authenticated admin re-types the password, which then crosses the wire in
cleartext again (see F-001). At minimum, pre-fill the username and explain why
re-authentication is required.

## F-031 — ROLE COMPARISON (evidence for F-018)
Operator (Jono) 17 actions; Admin (Avi) 24. Admin-only:
`allocation_return`, `allocation_transfer`, `cancel_premix_direct`,
`ingredient_approve_override`, `ingredient_approve_short_bag`,
`ingredient_collection_cancel`, `machine_force_close`.
`allowedTabs` identical for both (`collect`, `premix`, `allocation`).
The two roles render an **identical UI** — none of the 7 extra privileges is surfaced,
and no operator-facing control is hidden.

## F-032 — GOOD: completing a collection advances cleanly to area selection
On satisfying the last line the app moved straight to "COL_000028 ready to mix — pick an
area" listing DOLCI / Main Mixing Room / JANDI / Mackie / Rajoo with live machine counts.
Good, purposeful transition.

# ===== PHASE 2: post-collection workflow =====

## F-033 — SCOPE (Critical for planning): large parts of the Station 2 workflow are not implemented
The backend grants the Operator 17 actions and the Admin 24. The app implements only
**13 request types** in total, and several granted capabilities have **no UI whatsoever**.

App implements: `login_requested`, `reader_logout_requested`, `active_job_cards_requested`,
`job_card_load_requested`, `collection_resume_requested`, `ingredient_scan_requested`,
`ingredient_collection_cancel_requested`, `mixing_overview_requested`,
`machine_cycle_start_requested`, `machine_cycle_finish_requested`,
`machine_cycle_force_close_requested`, `holding_recovery_requested`,
`pallet_lookup_requested`.

**Granted but entirely absent from the app** (zero source matches):
- **Allocation** — `allocate_premix`, `allocate_full_pallet`, `allocate_bags`,
  `finish_allocation`, `allocation_return`, `allocation_transfer`.
  Note `allocation` is returned as an **`allowedTab`** for both roles, yet no allocation
  screen exists.
- **Extrusion** — `view_extrusion_overview`, `assign_extrusion_run`, `finish_extrusion_run`.
- **Pre-mix lifecycle** — `complete_premix`, `cancel_premix`, `cancel_premix_direct`,
  `assign_hopper`.
- **Completion** — `complete_station2_work`, `submit_job_card`.

So of the Operator's 17 allowed actions the app can perform roughly 5; of the Admin's 24,
roughly 7. Any statement that "the app is tested end to end" should be read as covering
**collect -> mix cycle** only. The rest of the Station 2 process is not buildable from this
app today.

## F-034 — GOOD: unrecoverable pallet triggers a clear recovery offer
Scanning a pallet that is `InUse` at Extrusion returned
"Pallet is not in Holding or Mixing." and the app offered a dialog:
"Pallet not in Holding - This pallet isn't currently in Holding or Mixing. Recover...?"
with **No / Recover**. Good in-context recovery affordance.

## F-035 — BACKEND (Medium): misleading recovery rejection message
Recovering `DUMMY-ST2-1600000050` (status `InUse`, location `Extrusion`) for a collection
whose line 0 material **is** `1600000050` was rejected with
"Recovery product is not valid for the active ingredient collection."
The product *is* valid; the real obstacle is the pallet's location/state. The message sends
the operator looking for the wrong problem. Say what actually blocked it.

## F-036 — CONTRACT (High): second confirmed case of `errorCode` carrying a GUID
`ingredient_scan_result` rejection returned
`errorCode = "936666e9990c4c0cbd33c6ec9ca4e157"` (an opaque id, not a symbolic code).
Together with `dac675694be14b7685280f006640ed6e` seen earlier, this is a pattern on the
scan/exception paths, not a one-off. Reinforces B3.

## F-037 — CONCURRENCY: multi-collection / multi-machine / multi-area works correctly
Drove five collections through the board simultaneously:

| Cycle | Machine | Area | Collection | Outcome |
|---|---|---|---|---|
| CYC_000010 | MXR-01 | Main Mixing Room | COL_000031 | force-closed |
| CYC_000011 | MXR-02 | Main Mixing Room | COL_000036 | ran concurrently |
| CYC_000012 | MXR-03 | Main Mixing Room | COL_000037 | ran concurrently |
| CYC_000013 | JAN-MIX-01 | JANDI | COL_000038 | finished -> MIX_000009 |
| RUN_000010 | JAN-02 | JANDI | MIX_000009 | started from mix |

Verified: machine states flip Available/InUse correctly and independently; three cycles in
one area plus one in another ran simultaneously without interference; force-closing
CYC_000010 left CYC_000011 and CYC_000012 untouched; the area picker counters updated
accurately (Main Mixing Room "27 machine(s) available - 3 active cycle(s)").
Two-stage routing works: finishing a mixer cycle produced a mix whose
`validNextMachineCodes` correctly listed `JAN-DRUM-01, JAN-02, JAN-03` and **excluded
JAN-04** (which is transfer-drum gated). Downstream machines create `RUN_*` ids rather
than `CYC_*`. **This part of the app is solid.**

## F-038 — BUSINESS LOGIC (High): force-closed cycles still yield a usable mix
Force-close is described in the dialog as "the cycle is released **without completing**".
In practice force-closing CYC_000010 released MXR-01 *and* published **MIX_000006** into
"Ready mixes", where it is rendered **identically** to MIX_000009 which came from a
properly finished cycle. Nothing marks it as abandoned/incomplete.
An operator can therefore feed a deliberately-abandoned mix into an extruder without any
indication. Either force-close should discard/quarantine the mix, or the mix must carry and
display a "from force-closed cycle" flag.

## F-039 — APP BUG (Medium): dialog action buttons sit under the IME
In the force-close dialog the Cancel / Force close buttons are at y=1167 with the keyboard
open and y=1410 with it closed — i.e. the keyboard covers them and taps land on keys
instead (an `i` was appended to the audit-reason field by a tap aimed at the button).
The dialog neither scrolls nor lifts its buttons above the IME. Same root cause as F-024
(no `imePadding()` anywhere). Affects the login form too (F-006).

## F-040 — UX (Low): raw ISO timestamps and a missing operator name
The active-cycle sheet shows
"Started 2026-07-23T11:38:28.2733333+00:00 by " — an unformatted ISO-8601 string a
shop-floor operator cannot parse, followed by "by " with **no name** (it renders
`startedByOperatorId`, which is empty/opaque). Format as local time and resolve the
operator's display name.

## F-041 — F-027 CORROBORATED from a second endpoint
`pallet_lookup_result` for `DUMMY-ST2-ACTIVE-20260717-1600000044` returns
`remainingQuantity: 24775.0`, `remainingBags: 991` -> **25.0 kg per bag**.
The BOM line for that same material (`1600000044`, job card 510019355) advertises
`bagSize: "20.000 kg"`. Two backend endpoints disagree about the bag size of one material,
and the app will happily show an operator both figures. This makes F-027 a data-integrity
problem in the BOM/UoM conversion, not a display bug.

## F-042 — GOOD: RFID pallet lookup is clean and fast
`pallet_lookup_requested` -> result in 1351 ms (the fastest write-free call measured).
Screen shows Tag ID, Pallet ID, Product, Batch, Remaining, Location, State with a clear
"Pallet Ready" header and Scan Another / Done actions. Well built.
Minor: the response carries `remainingBags: 991` but the screen shows only kg — in a
bag-driven workflow the bag count is arguably the more useful number.

## F-043 — WITHDRAWN (by design, client-requested)
**Not a defect.** Multiple collections per job card, one per lookup, is intended behaviour
confirmed by the client. Original observation retained below for the record only.

### (original text, superseded)
`job_card_load_requested` creates a **new** collection every time, even when an
in-progress collection for that job card already exists. Traced for 510019340:

```
10:34:55  COL_000011  resumedExistingCollection=False
11:21:34  COL_000035  resumedExistingCollection=False   (COL_000011 still open)
11:30:28  COL_000039  resumedExistingCollection=False   (COL_000035 was 75% done)
11:48:10  COL_000040  resumedExistingCollection=False   (two 75% collections open)
```

The final `active_job_cards_list` shows **29 open collections for 20 job cards**, including:

| Job card | Simultaneous open collections |
|---|---|
| 510019340 | COL_000011 (0%), COL_000035 (**75%**), COL_000039 (**75%**), COL_000040 (0%) |
| 510019341 | COL_000012, COL_000029, COL_000030 (+COL_000031 consumed) |
| 510019346 | COL_000016, COL_000034 (**66.7%**) (+COL_000038 consumed) |
| 510019347 | COL_000017, COL_000032 (**50%**) (+COL_000036 consumed) |
| 510019339 | COL_000010, COL_000033 (**66.7%**) (+COL_000037 consumed) |

**Impact — this loses real stock.** Ingredients issued against COL_000035 are stranded the
moment the operator re-enters the order number and lands on COL_000039. The same BOM
requirement then gets collected *again*, so material is physically issued two or more times
for one job card. The Active Jobs list shows the same job card repeatedly at different
percentages with nothing to distinguish them.

The backend clearly has the concept — it returns a `resumedExistingCollection` field and
supports `collection_resume_requested` — but the load path never sets it. Resuming only
works if the operator happens to tap the Active Jobs card instead of typing the number.

**Fix (backend):** on `job_card_load_requested`, return the existing open collection for
that job card with `resumedExistingCollection: true` instead of minting a new one; only
create a new collection when none is open.
**Fix (app):** if a job card already has an open collection, resume it or make the operator
choose explicitly — never silently start a second one.

## F-044 — UX (Medium): the two cancel dialogs contradict each other
First dialog: "This closes the job card **if it hasn't had any activity yet** (ingredients
scanned, mixing started, etc). You'll be notified if it can't be cancelled."
Second dialog, immediately after: "Cancelling a job card **always** needs a manager's
approval."
The first implies it may simply succeed; the second says approval is unconditional. The app
knows approval is always required — say so once, up front, and drop the conditional wording.

## F-045 — REFRAMED as a presentation issue (see A15)
Since multiple collections per job card are by design, the defect is not their existence but
that the Active Jobs list cannot tell them apart.
After the duplicate collections accumulated, the Job Lookup screen renders
**"510019340" four times in a row**, each with the same product description and nothing to
distinguish them (`shots/79-logged-out.png`). An operator has no way to tell which entry
holds their 75%-complete work. This is F-043 as the operator experiences it.

## F-046 — PERF: worst latency of the whole test
`active_job_cards_list` took **10 366 ms**. The payload enumerates 29 open collections plus all
hoppers. Because multiple collections per job card are by design, this list will keep growing
in normal use — the endpoint needs pagination or filtering, not fewer collections.

## F-047 — CONTRACT (Low): logout returns empty `sessionState`
`reader_logout_requested` -> `operator_context` with `accepted:true` but
`sessionState: ""`. The failed-login response earlier correctly used
`sessionState: "Closed"`. Logout should report `Closed` too.
Also two `operator_context` responses were published for the single logout request.

## F-048 — GOOD: logout is clean and correct
Confirmation dialog ("Log out? You'll need to log in again to continue."),
`reader_logout_requested` published, session ended server-side, app returned to Login in
5557 ms. Behaves correctly.

---

# COVERAGE SUMMARY — all 13 implemented request types exercised

| Request type | Tested | Result |
|---|---|---|
| `login_requested` | yes | pass (valid + invalid) |
| `reader_logout_requested` | yes | pass |
| `active_job_cards_requested` | yes | pass (slow, F-046) |
| `job_card_load_requested` | yes | pass (new collection per load - by design) |
| `collection_resume_requested` | yes | pass, fastest call (553 ms) |
| `ingredient_scan_requested` | yes | **F-027 bag size**, F-010 silent drop |
| `ingredient_collection_cancel_requested` | partial | reached the manager-approval gate, F-044 |
| `mixing_overview_requested` | yes | pass |
| `machine_cycle_start_requested` | yes | pass x5, incl. mix->machine |
| `machine_cycle_finish_requested` | yes | pass, produced a mix |
| `machine_cycle_force_close_requested` | yes | pass, but **F-038** |
| `holding_recovery_requested` | yes | rejected, F-035 misleading message |
| `pallet_lookup_requested` | yes | pass, F-041 corroborates F-027 |

**Not implemented in the app at all** (F-033): allocation, extrusion overview/runs,
pre-mix lifecycle (`complete_premix`/`cancel_premix`/`assign_hopper`),
`complete_station2_work`, `submit_job_card`.
