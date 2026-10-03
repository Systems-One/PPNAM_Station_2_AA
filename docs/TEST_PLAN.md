# PPNAM Station 2 — Android App Test Plan

**Living document.** This is the input to a test run; the output is a dated report under
`docs/test-runs/`. Test IDs are stable forever — a run's results are directly comparable to
any earlier run's.

**How to invoke:** say *"run the test plan"*. Every gate runs, every time. There are no
tiers and no partial modes.

Derived from the source, not from habit: every case below traces to a branch in
`app/src/main/java/com/ppnam/station2aa/`. When the code gains a branch, this plan gains a
test.

---

## §0 How to run

### 0.1 Preconditions

| Requirement | Check |
|---|---|
| C72 attached, USB debugging on | `adb devices` shows one device |
| Broker reachable | `mqtt.sysone.co.za:443` (WSS) or `10.1.50.1:1883` on the plant LAN |
| Live Station 2 online | retained `PPNAM/station_2/status` = `online` |
| Operator account | Operator role — used for the role-gating comparison |
| Admin account | Admin role — needed for cancel, waiver, force-close |
| Supervisor PIN | `079545` (see §9) |
| `pallets.json` | **Must be regenerated** — see 0.3 |

### 0.2 Harness

Scripts live in `tools/test-harness/`:

| Script | Role |
|---|---|
| `sniffer.py` | Passive MQTT capture. Subscribes `PPNAM/#`, writes `wire.jsonl` + `wire.log`, pairs responses to requests, times round trips. **Never publishes.** |
| `sweep2.py` | uiautomator element location + tap. `dump()`, `nodes()`, `find(text)`, `tap(x,y)`. Everything else builds on this. |
| `collect.py` | Drives one job card's collection line by line. Needs `pallets.json`. |
| `board.py` | Mixing-board helpers: start / finish / force-close, with scroll-to-find. |
| `analyze.py` | Summarises `wire.jsonl` — latency per request type, orphaned requests, rejections. |

Start every run with the sniffer up before the app is touched:

```bash
cd tools/test-harness
python sniffer.py ../../docs/test-runs/<DATE>/capture &
adb logcat -c && adb logcat > ../../docs/test-runs/<DATE>/logcat.txt &
```

### 0.3 Regenerating `pallets.json`

```bash
python tools/test-harness/make_pallets.py          # defaults to the path below
python tools/test-harness/make_pallets.py <other-barcodes.html>
```

Lifts the pallet inventory out of the barcode generator
(`C:\Dev\Barcode_Generator\barcodes.html`) — a JS object literal that `json.loads` cannot
read directly — and writes `tools/test-harness/pallets.json`. **Re-run it whenever the
generator is refreshed**; the stale copy will silently pick pallets that have since moved.

It also audits the data on the way through and prints any anomaly (remaining exceeding
original, zero bags, unparseable quantities). Those feed **D-INT** — see §4.4b.

Current snapshot: **114 pallets, 66 materials, 87 collectable now** (unblocked, in Holding
or Mixing), 0 blocked. By location: Holding 79, Station1 25, Mixing 8, Extrusion 2.

`collect.py::pallet_for` prefers an unblocked pallet in `Holding`, then `Mixing`, fullest
first — anything in `Extrusion`, `Consumed` or `Station1` needs the recovery flow first,
which makes those 27 rows the natural source of test pallets for **D29–D32**.

### 0.3b One-time setup

| Fixture | Needed by | Status |
|---|---|---|
| `tools/test-harness/pallets.json` | `collect.py`, F-sweep | **Done** — regenerate per 0.3 |
| `tools/test-harness/mosquitto-denysub.conf` | **A3** | **Missing.** A mosquitto config that authenticates the client but denies `subscribe` on `PPNAM/handheld_1/res/+`, leaving publish allowed — the only way to reach the connected-but-unsubscribed branch. Until it exists, A3 is **BLOCKED**, not skipped |

### 0.4 Driving the UI

Use `ui.py`, **not** raw `sweep2.find()`. Four traps cost real time on 2026-07-23 and it
handles all of them: the top-bar title repeats control labels (tapping it silently does
nothing); an open IME clips a Compose button to ~23 px and drops its text out of the
accessibility tree; uiautomator XML-escapes labels, so "Test & Apply" arrives as
"Test &amp; Apply"; and Compose buttons are unlabelled `View`s wrapping a `Text`.

```python
import ui
ui.tap("Look Up")                    # closes the IME, scrolls, resolves to the real button
ui.type_into("Production Order No.", "510019355")
ui.wait_for(lambda ls: "Scan Ingredients" in ls, timeout=30)
ui.pill()                            # "Connected" / "Reconnecting" / ...
ui.screenshot("shots/C7-1.png")
```

Injecting scans — **the two actions are not interchangeable**, and several tests depend on
the difference:

```bash
# Barcode  -> ScanEvent.Barcode  (cannot log in; is accepted as a pallet stand-in)
adb shell am broadcast -a com.scanner.broadcast --es data <CODE>

# RFID tag -> ScanEvent.RfidTag  (logs in at the Login screen; drives pallet lookup)
adb shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <TAG>
```

Screenshot at every step: `adb exec-out screencap -p > shots/<TESTID>-<n>.png`.

### 0.5 Backends

Most of Gate 4 runs against the **live** Station 2. Three blocks need the **simulator**
(`tools/backend-sim/`) because the real backend will not produce the fault on demand:
the fault-injection block (§4.1b), the recovery flow (§4.4c), and the malformed-payload
cases in §4.2.

The simulator and the live backend must never be up together — both answer every request.
Hand over with:

```bash
python tools/backend-sim/sim.py --yield-to-real     # refuses to start if Station 2 is live
```

To run the sim block, coordinate a window where Station 2 is stopped, or point the app at a
separate broker. **Record in the report which backend answered each block.**

### 0.6 When something fails

- **Gate 1 fails** → hard stop. Nothing to test. Report and end.
- **Gates 2 or 3 fail** → record and continue to Gate 4, unless the failure makes device
  testing meaningless (e.g. the transport layer does not compile).
- **A Gate 4 test fails** → record verdict and evidence, then continue. Never abandon the
  run over one failure.
- **A test cannot be run** (backend down, account missing, hardware absent) → `BLOCKED`,
  with the reason. Never silently skipped.

---

## §1 Gate 1 — Build & static

| ID | Test | Command | Pass |
|---|---|---|---|
| G1.1 | Debug build | `./gradlew assembleDebug` | exit 0 |
| G1.2 | Lint | `./gradlew lint` | no new errors vs the last run |
| G1.3 | No new compiler warnings | build output | count not increased |

Failure here is a hard stop.

---

## §2 Gate 2 — Unit tests

| ID | Test | Command | Pass |
|---|---|---|---|
| G2.1 | Full unit suite | `./gradlew test` | all green, 29 test classes |
| G2.2 | No silently-skipped tests | test report | ignored count = 0 |

`G2.1` covers, among others: topic-segment validation (**A21**), the scan-shape XOR guard
that stops both-or-neither capture shapes reaching the wire (**D18**), retry/correlation
semantics, clock-skew arithmetic, session expiry, the pure highlight rule, `canShow`
fail-open, bag/bulk line semantics, and timestamp formatting.

These cases live here rather than in Gate 4 because they are pure functions with no UI
surface — a device pass could not observe them any more precisely than a unit test can.

---

## §3 Gate 3 — Contract conformance (backend-sim)

| ID | Test | How | Pass |
|---|---|---|---|
| G3.1 | Simulator self-test | `python tools/backend-sim/selftest.py --direct` | 109/109 checks |
| G3.2 | Retired-topic guard | selftest | v3 topics refused |
| G3.3 | Envelope validation | sim `sim.log` | all 8 validation steps logged per request |
| G3.4 | Schema version | wire capture | every request carries schema `4.0` |
| G3.5 | Timestamp window | sim | requests outside the window rejected `message_expired` |

---

## §4 Gate 4 — On-device

Ordered so the cheap, low-risk blocks run first and the settings block (which mutates device
config) runs last.

### 4.1 Connection & transport

Live backend unless marked **[sim]**.

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| A1 | Cold start connects | Launch app. Watch the status pill. | Reconnecting → **Connected** (green) within 15 s |
| A2 | Failed initial connect retries | Settings → PIN → Host = `10.255.255.1` → Test & Apply | Failure message; pill red; logcat shows a retry every 5 s |
| A4 | Mid-session drop is visible | `adb shell cmd connectivity airplane-mode enable` | Pill → **Reconnecting** (orange) and **stays there** while HiveMQ retries; station presence cleared. It does **not** go to Offline — `DISCONNECTED` is only reached via an explicit `disconnect()` or a failed connect |
| A5 | Auto-reconnect re-subscribes | disable airplane mode | Pill → Connected; sniffer shows a *subscribe* but **no** new CONNECT |
| A6 | LWT on ungraceful death | `adb shell am force-stop com.mitas.ppnam.station2aa` | retained `PPNAM/handheld_1/status` = `offline` |
| A7 | Graceful disconnect publishes offline | Settings → Test & Apply with valid settings | sniffer sees an explicit `offline` on the old deviceId, then `online` |
| A8 | Subscribe announces presence | any fresh connect | retained `PPNAM/handheld_1/status` = `online` |
| A10 | Status precedence | force each of the five states in turn | Offline > Reconnecting > StationOffline > ClockSkewed > Connected |
| A13 | Offline request is honest | airplane mode → Job Lookup → Look Up | **"Not connected to Station 2"** — nothing on the wire |
| A22 | Bad settings keep the good connection | Test & Apply with a bad host | Failure shown, **old connection still live**, pill green |

**A12 — clock skew (device clock is modified; restore step is mandatory)**

```bash
adb shell settings put global auto_time 0
adb shell date $(date -u -d '+2 minutes' +%m%d%H%M%Y.%S)   # push 2 min ahead
# expect: pill shows "Clock out of sync" (amber) within ~2 s of the next response
adb shell settings put global auto_time 1                   # RESTORE — always
```
Confirm the pill returns to Connected before continuing. If the run aborts during A12, the
report must state the device was left with `auto_time 0`.

**A3 — the strand bug (needs the ACL fixture from 0.3b)**

Point the app at the deny-subscribe broker. Expected: the transport connects, the subscribe
fails, and the app keeps retrying **the subscribe** on its own timer. It must **not** sit
permanently on Offline with a live socket, and it must **not** re-issue CONNECT — that was
the original bug, where `connect()` no-opped against the already-live transport and the app
never recovered.

**4.1b Fault injection [sim]** — the sim must be the only backend answering.

| ID | What it proves | Sim behaviour | Expected |
|---|---|---|---|
| A9 | Garbage presence = offline | publish `PPNAM/station_2/status` = `maybe` | pill shows **Station 2 offline**; logcat warns |
| A11 | No badge flicker | one delayed reply producing a false skew reading, corrected on the next | badge does **not** flip — the 1.5 s debounce absorbs it |
| A14 | Replay identity | withhold all replies | sniffer: exactly **3** frames, identical bytes, same `messageId` |
| A15 | Timeout budget | withhold all replies | ~20 s per attempt; caller gives up at ~60 s with "Station 2 did not respond" |
| A16 | Correlation discipline | send (a) a reply with no `inResponseToMessageId`, (b) a reply for an unknown id | both dropped, logged; the real request still resolves |
| A17 | Malformed response | reply with broken JSON | **"Station 2 sent an unreadable response"**; app usable |
| A18 | Session loss logs out | `session_required` carrying the **current** `operatorSessionId` | returned to Login from wherever the operator was |
| A19 | Stale rejection does **not** log out | `session_required` carrying an **old** `operatorSessionId` | session survives — operator stays put. *(Regression: fixed 2026-07-23.)* |
| A20 | Upgrade gate blocks everything | `client_upgrade_required` | **"App update required"** dialog over every screen; not dismissible; survives navigation |

### 4.2 Auth, session & roles

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| B1 | Login works | type Operator credentials → **Log In** | lands on **Job Lookup**; Back does not return to Login |
| B2 | Wrong password | correct user, wrong password | red error under the fields; fields still editable |
| B6 | Only RFID logs in | at Login, inject a **barcode** broadcast, then an **RFID** broadcast | barcode: nothing happens. RFID: login attempt fires |
| B7 | No double login | inject the same RFID tag twice, 100 ms apart | exactly **one** `login_requested` on the wire |
| B8 | Retry after failure | fail once, then log in correctly | succeeds |
| B9 | Listener stops after login | inject an RFID badge on Job Lookup | no second `login_requested` |
| B10 | Logout via top bar | tap the operator name (amber logout icon) → **Log out** | confirm dialog, then Login |
| B11 | Logout via Settings | Settings → **Log Out** → confirm | Login |
| B12 | Logout survives a dead network | airplane mode → log out | session cleared locally; lands on Login |
| B14 | No double navigation | logcat during any logout | exactly one navigation to Login |
| B15 | Role gating | log in as Operator, then Admin; compare | **Operator:** no "Cancel" on the scan screen, no "Short bags", no "Force close…". **Admin:** all three present |
| B17 | No session persistence | force-stop, relaunch | Login screen |

**[sim]** B3 blank `operatorSessionId` → *"Station 2 accepted the login but issued no
session"*. B4 `sessionState: Closed` → *"Station 2 closed this session immediately"*.
B5 unparseable `sessionExpiresAtUtc` → login **succeeds** anyway. B13 `session_required` on
any screen → forced to Login. B16 empty `allowedActions` → **all** controls render (fails
open).

### 4.3 Job lookup

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| C1 | List loads on entry | open Job Lookup | Active Jobs populated |
| C1b | List refreshes on back-navigation | note a job's %, open it, collect a line, go Back | % updated; a fresh `active_job_cards_requested` on the wire |
| C1c | **List refreshes after backgrounding** | sit on Job Lookup, lock the screen (or switch apps) 30 s, return | a fresh `active_job_cards_requested` on the wire and updated progress. Backgrounding does **not** leave the composable, so this needs the `ON_RESUME` observer — not `LaunchedEffect(Unit)`. *(Fixed 2026-07-23; see R-17.)* |
| C2 | Rows are distinguishable | read a card | shows `collectionId · N of M lines · NN%`; approvals-waiting line when > 0 |
| C3 | Duplicate job cards | load the same JC three times, return to Job Lookup | three distinct rows, no crash (keyed on `collectionId`) |
| C4 | List failure is visible | airplane mode → open Job Lookup | error text; the order field still works |
| C5 | Numeric keypad | tap **Production Order No.** | keypad, not QWERTY |
| C6 | Look Up gating | empty field / during a load | button disabled |
| C7 | Valid order | type a known order → **Look Up** | Scan Ingredients screen |
| C8 | Unknown order | type `999999999` | **"That job card number wasn't found. Check the number on the card and try again."** |
| C10 | Tap resumes, never reloads | tap an active job; watch the wire | `collection_resume_requested` — **not** `job_card_load_requested` |
| C11 | Two-stage Back | focus the field (keyboard up) → Back → Back | 1st: keyboard closes. 2nd: **"Close the app?"** |
| C12 | Mixing entry | tap **Mixing** | area picker, no "ready to mix" banner |
| C13 | Arming resets | arm a line, Back, load a different job | no line armed on the new job |

**[sim]** C9 — an unrecognised rejection reason passes through **verbatim**, not flattened
into the humanised message.

**Job card sweep.** Load all 20 supplied cards (load only, no collection). Record BOM line
count, bulk vs bagged mix, and any rejection. Then drive **three fixed cards** end-to-end in
§4.4: one single-line, one multi-line bagged, one containing a bulk line. Name them in the
report so every run uses the same three.

### 4.4 Ingredient collection

#### 4.4a Arming and dialog routing

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| D2 | No blind scans | on a multi-line job with nothing armed, inject a pallet scan | snackbar **"Tap the material line you're collecting, then scan the pallet."** — **nothing on the wire**, no dialog |
| D3 | Unambiguous auto-arm | job with exactly one unsatisfied line, scan a pallet | dialog opens directly, line armed |
| D4 | Satisfied single-line job | fully collect a single-line job, scan again | scan still reaches the server (server decides tolerance, not the app) |
| D5 | Tap to arm | tap a line card | **ARMED** badge, amber border |
| D6 | No arming mid-flight | tap another line while a scan is pending | tap ignored |
| D7 | Correct dialog per type | scan against a bagged line, then a bulk line | **"Bag size & count"** vs **"Weight received"** |
| D8 | Round-up pre-fill | open the bag dialog on a line needing 10.99 bags | **Full bags** pre-filled `11`; fraction `0` |
| D9 | Edits survive | type `9`, rotate nothing, let the screen recompose | still `9` — re-priming is keyed on pallet + line |
| D10 | Dialog carries context | read the bag dialog | line number, material name, **"Still required: X bags of Y"**, the round-up rule, pallet tag |
| D11 | Short entry warned | set the count below what's required | red **"…is short of the … required — Station 2 will reject this."** |
| D12 | Zero refused | clear the count | **Confirm Scan** disabled |
| D13 | Fractions offered | read the chips | exactly `0`, `1/4`, `1/2`, `3/4` |
| D14 | Weight positive-only | enter `0` then a valid weight | Confirm disabled, then enabled; field clears after confirm |
| D15/D16 | Shape guards | force a bulk line through the bag path, and vice versa | snackbar naming the material; nothing sent |
| D17 | Non-positive weight | enter `0` or `-5` in the weight dialog | snackbar **"Quantity must be a positive number."**; the dialog stays open, nothing sent |
| D1 | Listener lifecycle | enter the scan screen, leave by Back, then inject a pallet scan | the departed screen does **not** process it — the listener is disposed, not merely paused |

#### 4.4b In-flight, guards and outcomes

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| D19 | Non-blocking progress | confirm a scan | thin amber bar; **that line only** spins; list stays scrollable and readable |
| D20 | **Scan-guard matrix** | inject one pallet scan in each of the 10 states | **Allowed:** `OrderLoaded` (idle), `Error`. **Swallowed:** `Idle`, `Loading`, `Cancelling`, `OrderLoaded` busy, bag dialog, weight dialog, approval dialog, recovery prompt, waiver entry, rejected-waiver dialog. One screenshot per state |
| D21 | Lines replace cleanly | after an accepted scan | list refreshes; the backflush line never appears as collectible |
| D22 | Arming persists sensibly | scan a partial amount, then complete the line | stays armed while unsatisfied; clears once satisfied |
| D23 | Auto-navigate on ready | collect the final line | lands on the mixing area picker with the collection pre-selected |
| D33 | Rejection is clean | force a rejection | snackbar with the server's reason; back to the list, nothing lost |
| D38 | Error is not a trap | force an error state | **Dismiss** returns to the list with arming intact; a rescan also works |
| D42 | Start Mixing gating | before and after completion | **"Mixing after collection"** (disabled) → **"Start Mixing"** (enabled) only when `collectionStatus == ReadyForMixing` |

**D-INT — bag-math integrity (per collected line).** For every line driven end-to-end,
compare three sources:

1. BOM `bagSize` (from `bom_loaded`)
2. `capturedKilograms ÷ scannedBags` (from `ingredient_scan_result`)
3. `remainingQuantity ÷ remainingBags` for the same pallet (from `pallet_lookup_result`)

All three must agree. **Currently FAILS** on material `1600000044`: BOM says 20.000 kg/bag,
the other two say 25.0. Verdict **FAIL(backend)** until it is fixed.

**Root cause, from the pallet master (snapshot 2026-07-23 07:07, all 114 pallets):** this is
**one bad pallet row, not a systemic UoM conversion fault** — which is what B1 left open.

- 113 of 114 pallets are internally consistent: 83 at 25 kg/bag, 30 at 30 kg/bag.
- Exactly **one** pallet sits at 20 kg/bag — `DUMMY-ST2-1600000044` — and it is also the
  only row in the whole file whose `remaining_quantity` (4340.216) **exceeds** its
  `original_quantity` (2000.000). It is corrupt on two counts.
- The same material has a second, healthy pallet — `DUMMY-ST2-ACTIVE-20260717-1600000044`,
  25 000 kg / 1 000 bags = **25 kg/bag** — which is the one the scan actually credited.

So the BOM published `bagSize` derived from the corrupt pallet while the scan consumed the
healthy one. Fixing that single row should close B1.

Only one other material has pallets that disagree: `1600000043`, where a single seeded
pallet reads 25 kg/bag against **30** real Station-1 pallets at 30 kg/bag — again an
outlier seed row, not a conversion fault.

`make_pallets.py` re-runs this audit on every regeneration and prints any anomaly it finds,
so a new bad row surfaces before it reaches an operator.

#### 4.4c Approval, waiver and recovery

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| D24 | Approval prompt | over-collect past tolerance | **"Manager or admin approval required"** with the server's reason |
| D25 | Approval resubmits | enter Admin credentials + audit reason → **Approve** | the same scan goes again **with** credentials; line updates |
| D26 | Blank refused | leave a field empty | **Approve** disabled; if forced, an inline red validation line — nothing sent |
| D27 | No double approval | double-tap **Approve** | exactly one credentialed request on the wire |
| D28 | Cancel kills in-flight | tap **Approve**, then immediately **Cancel** | the late response does not overwrite the screen |
| D34 | Waiver is Admin-only | as Operator, then Admin | **"Short bags"** absent, then present |
| D35 | Waiver validation | open **Waive short bags**, leave fields blank | **Submit** disabled |
| D36 | Rejected waiver re-approves | force a rejection | re-approval dialog; credentials re-collected; a **fresh** submission (not a scan resubmit) |
| D37 | No double waiver | double-tap **Submit** | one request |
| D39 | Cancel is one dialog | as Admin, tap **Cancel** | a **single** dialog that says approval is always required and collects credentials in the same step. **Confirm Cancel** disabled until both are filled |
| D40 | Rejected cancel restores | cancel a collection already routed to a mixer | dialog closes, snackbar gives the reason, the order is **unchanged**, scanning resumes |
| D41 | Confirmed cancel exits | cancel an eligible collection | returns to Job Lookup |

**[sim] Recovery flow.** Put a pallet in a state that is neither Holding nor Mixing, then
scan it:

| ID | What it proves | Expected |
|---|---|---|
| D29 | The prompt appears | **"Pallet not in Holding"** — *"Recover it into Holding?"* |
| D30 | Recover then retry credits **once** | **Recover** → the pallet is recovered *and* the original scan is retried automatically. The line gains the quantity exactly once — check the wire, not the screen |
| D31 | No double recovery | double-tap **Recover** → exactly one `holding_recovery_requested` + one retried scan |
| D31b | Dismiss kills in-flight | tap **Recover**, then **No** immediately → the late response does not overwrite the screen |
| D32 | Failed recovery is not a dead end | make recovery fail → snackbar with the reason, back to the line list, scanning still works |

**[live] D-MSG — recovery rejection wording.** On the live backend, scan a genuinely
ineligible pallet once and record the rejection text verbatim. Today's read was misleading
(open finding B11). Verdict **FAIL(backend)** while the message does not tell the operator
what to do next.

### 4.5 Mixing board

Open **all five** areas (Dolci, Main Mixing Room, Jandi, Mackie, Rajoo). Drive **two**
end-to-end: **Rajoo** (the only area with the dose sheet) and one bulk area.

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| E1 | Resume refetch | on the area picker, background the app 60 s, return | counts refresh (`ON_RESUME` observer) |
| E2 | Five areas with counts | read the picker | each area shows machines available / active cycles / ready mixes |
| E2b | No area returns empty | read all five | every configured area lists equipment. *Mackie returned null collections on 2026-07-23 — check specifically* |
| E3 | Pending banner | arrive via auto-navigation | **"<collectionId> ready to mix — pick an area"** |
| E4 | Error retry | airplane mode → open an area → restore → **Retry** | board loads |
| E5 | Both load calls matter | fail the overview, then fail the ready-collections call | either failure lands on the Error screen — never a half-rendered board |
| E6 | One-shot pre-select | arrive pre-selected, tap **Refresh** | the pre-selection does **not** re-assert itself over a manual choice |
| E7 | Superseded load | open one area then immediately another | only the second area's data renders |
| E8 | Reconnect refresh | drop and restore the network on a board | board reloads by itself |
| E9 | Collection highlights mixers | select a ready collection | only enabled, **Available** Mixers get the amber border |
| E10 | Mix intersection | select two mixes | highlights = the intersection of their `validNextMachineCodes` |
| E11 | Same-JC accumulation | send one mix downstream, then select a second mix from the **same** JC | that busy machine is **still highlighted** — it accepts more into its run |
| E12 | Cross-JC blocked | with a mix selected, tap a mix from another JC | greyed out; tap does nothing |
| E13 | Clear selection | with a selection active, tap **Clear** | selection drops, all highlighting clears |
| E14 | Tap → start | tap a highlighted machine | **"Start <machine>"** confirm sheet |
| E15 | Tap busy → cycle sheet | with nothing selected, tap a machine running a cycle | **"Active cycle on <machine>"** |
| E16 | Scan beats highlighting | inject a scan of a **non-highlighted** machine, and of an **unknown** code | both reach the server; the server's rejection is shown. A **tap** on the same card does nothing |
| E17 | Scan blocked over a sheet | inject a scan with a sheet open | swallowed |
| E18 | Rajoo dose rows | select a collection → choose a Rajoo mixer | one row per collected material, each capped at its collected quantity |
| E19 | Dose validation | submit empty / 6 rows / negative / above collected | **"Enter at least one dose."** / **"…at most five dose lines."** / **"Every dose must be a positive number."** / **"A dose cannot exceed the collected quantity."** |
| E20 | Sixth field disabled | fill five doses | remaining empty fields disable; counter reads `5 / 5 doses entered` |
| E21 | No double start | double-tap **Start** | one `machine_cycle_start_requested` |
| E22 | Accepted refreshes from the response | start a cycle | board updates from the embedded `areaStatus`; selection cleared; ready collections re-fetched |
| E23 | Rejected keeps the selection | start against an in-use machine | reason in a snackbar; **selection survives** so another machine can be tried |
| E25 | Finish | open a cycle sheet → **Finish cycle** | confirmation; a repeat finish reads **"…was already finished"** |
| E26 | Force close | as Admin: **Force close…** → credentials + audit reason | cycle closes; confirmation names the approver. As Operator the button is absent |

**[sim] E24 — no-response re-sync.** Withhold the reply to a cycle start. The board must
**re-fetch** the overview and ready collections rather than trusting its stale cache, and
clear the selection. (Station 2 may have applied the change.)

**E-FC — force-closed mixes must not flow downstream.** Force-close a cycle, then inspect
`readyMixes` in the next overview. The resulting mix batch must **not** appear with valid
next machines. **Currently FAILS** — verdict **FAIL(backend)** (open finding B10). An
incomplete mix reaching extrusion is a real material risk.

### 4.6 RFID pallet lookup

**Sweep:** look up all 114 pallet tags, record `found`, `usable`, `blocked`, `recoverable`,
`remainingQuantity`, `remainingBags`. Cross-check `remainingQuantity ÷ remainingBags`
against the BOM's `bagSize` for the same material (feeds D-INT).

Tags come from `pallets.json` `rfid_tag` and are injected **verbatim, prefix included** —
111 are `EPC:DUMMY-ST2-…`, 3 are `DEMO-MIX-RFID-00n`. Stripping the `EPC:` prefix produces
a not-found, which is a real test (F3) but not this one:

```bash
python - <<'PY'
import json, subprocess
for p in json.load(open("tools/test-harness/pallets.json", encoding="utf-8")):
    subprocess.run(["adb", "shell", "am", "broadcast",
                    "-a", "com.rscja.scanner.action.scanner.RFID",
                    "--es", "data", p["rfid_tag"]])
PY
```

Pace the sweep against the sniffer — one lookup at a time, waiting for each
`pallet_lookup_result`, since the screen ignores a scan arriving mid-request (F2).

**Assertions on five fixed pallets**, covering found+usable, found+unusable, blocked,
recoverable, and not-found:

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| F1 | RFID only | inject a **barcode** on the lookup screen, then an **RFID** tag | barcode: nothing. RFID: lookup fires |
| F2 | No racing scans | inject a second tag during a lookup | ignored; the first result is not clobbered |
| F3 | Not found is a result | look up an unknown tag | **"Pallet Not Found"** + *"Resolve it at Station 1 first."* — an amber/red card, not an error state |
| F4 | Usable pallet | a good pallet | green **"Pallet Ready"**; Tag, Pallet ID, Product, Batch, Remaining, **Remaining bags**, Location, State |
| F5 | Unusable pallet | a pallet in the wrong state | amber **"Pallet Not Usable"** |
| F6 | Blocked flagged | a blocked pallet | **Blocked: Yes** |
| F7 | Recover gated by the server | pallets with and without `recoverable` | **"Recover to Holding"** appears only when the server says so |
| F9 | Exits work | **Scan Another** / **Done** | back to the prompt / back to the previous screen |
| F10 | Listener handover | enter RFID lookup from the scan screen, scan, return | the scan screen does **not** also process that tag |

**[sim] F8** — recovery succeeds but the pallet is still blocked. The screen must show the
refreshed, still-unusable state rather than claiming success.

### 4.7 Lifecycle & interruption

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| H1 | **Kill mid-scan loses nothing** | confirm a scan, then `adb shell am force-stop com.mitas.ppnam.station2aa` within the round trip. Relaunch, log in, resume the collection | Reconcile the resumed line against the wire capture: either the server acknowledged the scan and the line reflects it, or it did not and the line is unchanged. **Never a half-state, never double-counted** |
| H2 | Screen off | `adb shell input keyevent 26` mid-collection, wake, unlock | collection intact; scanning still works |
| H3 | Long background | background 30 min, return | area picker refetches; MQTT reconnects; pill green |
| H4 | Doze | `adb shell dumpsys deviceidle force-idle`, wait, `unforce` | connection drops and **recovers**; no permanent Offline |
| H5 | Low storage | fill the device, run a collection | BOM cache write failure does not take the collection down |
| H6 | Interruption over a dialog | fire a notification with the approval dialog open and text typed | dialog survives; typed audit reason intact |
| H7 | Rotation | rotate with the bag dialog open and a count typed | Activity recreated; **dialog still open, count preserved**; manager credential fields correctly **cleared** |
| H8 | Process death | `adb shell am kill com.mitas.ppnam.station2aa` (background death), return | clean restore to Login; no crash |
| H9 | Battery pull | remove power/battery mid-session | retained `PPNAM/handheld_1/status` = `offline` |

### 4.8 Layout on the C72

Judged on **element bounds from uiautomator**, not opinion. Screenshots accompany each as
evidence.

| ID | What it proves | Assertion |
|---|---|---|
| I1 | Top bar clear of the status bar | top bar `bounds.top` ≥ status-bar height; the title node is fully within the bar |
| I2 | Correct bar variant | with an operator: two rows (operator row + title). Without: a single `TopAppBar` |
| I3 | Keyboard shrinks content | with the IME up, no content node extends below `ime.top`; the top bar's `bounds.top` is **unchanged** |
| I4 | Dialog buttons reachable | for each of the 7 credential dialogs, with the IME up: the confirm button's `bounds.bottom` < `ime.top` |
| I5 | Bar never squeezed | with a long `name · role`, the status pill's width is unchanged and the operator text ellipsises |
| I6 | Snackbar themed | snackbar background matches `GraphiteSurfaceVariant`, not the Material default |

### 4.9 Settings — **runs last** (mutates device configuration)

**Before this block:** capture the current settings and record them in the report. **After:**
restore and confirm the pill is green again.

| ID | What it proves | Steps | Expected |
|---|---|---|---|
| S1 | Reachable pre-login | from Login → Settings | opens; **no** "Session" card |
| S2 | PIN length cap | type 7 digits | only 6 accepted |
| S3 | Attempt feedback | one wrong PIN | **"Incorrect PIN. 4 attempts left before lockout."** |
| S4 | Lockout | five wrong PINs | **"Too many attempts. Try again in 30s."** A **correct** PIN during the window is still refused. After 30 s the correct PIN unlocks |
| S5 | Unlock resets | unlock, lock, one wrong PIN | counter restarts at 4 left |
| S6 | All fields editable | unlock | Device ID, Host, Port, WebSocket, TLS, Username, Password, Request Timeout |
| S7 | Bad numbers don't crash | type `abc` into Port and Timeout | falls back to the previous value |
| S8 | Apply succeeds | valid settings → **Test & Apply** | **"Connected — settings saved"**, then re-locks after ~2 s with the PIN cleared |
| S9 | Apply fails | bad host → **Test & Apply** | red failure message; stays unlocked; **old connection still live** (= A22) |
| S10 | Diagnostics | read the card | connection state and `v<name> (<code>)` |

**Restore:** re-enter the PIN, restore every captured value, **Test & Apply**, confirm green.

---

## §5 Regression register

One row per fixed defect, each with a specific re-check. This is what makes the app get
*safer* over time rather than just re-testing happy paths. Add a row whenever a bug is fixed.

| Ref | Defect | Re-check |
|---|---|---|
| R-01 | Spurious logout from a stale `session_required` | A19 |
| R-02 | Bag dialog opened with no line armed, then discarded the entry | D2 |
| R-03 | Toolbar cropped behind the status bar | I1 |
| R-04 | Dialog buttons under the IME | I4 |
| R-05 | Two contradictory cancel dialogs | D39 |
| R-06 | Board blanked by envelope-level rejections | E23 |
| R-07 | Superseded area load overwrote a newer one | E7 |
| R-08 | Area picker stale after resume | E1 |
| R-09 | Double-tap Recover double-credited a pallet | D31 |
| R-10 | Concurrent badge logins overwrote the session | B7 |
| R-11 | Scan listener leaked past the scan screen | F10 |
| R-12 | `Error` was a trap state with no exit | D38 |
| R-13 | Elapsed-time truncation masked future timestamps | G2.1 (`TimeFormatTest`) |
| R-14 | Subscribe failure stranded the app on Offline | A3 |
| R-15 | Raw ISO timestamps in the cycle sheet | E15 |
| R-16 | Unlimited PIN retries | G4 |
| R-17 | Active-jobs list stale after the app was backgrounded | C1c |
| R-18 | **Crash on any unknown pallet** — `PalletState.fromWire` NPE on a null from the wire | F3 |
| R-19 | Ready collections rendered as "0%" when the backend sends null progress | C2 |
| R-20 | Dose validation refused silently — message below the scrollable fields | E19 |
| R-21 | Unknown-job-card rejection reached the operator as raw SAP jargon | C8 |
| R-22 | Latent NPE on `unit.ifBlank` in the BOM mapper | G2.1 |
| R-23 | `readyCollections` leaked across mixing areas — the board never passed its area to `fetchReadyCollections()`, and the sim never scoped the field either | scope a `mixing_overview_requested` to one area and confirm a collection planned only to another area's mixer is absent |

---

## §6 Scope gaps — explicitly not covered

Recorded so a gap stays visible instead of reading as "all green". Verdict **N/A**.

**No UI exists for these**, though Station 2 grants them:

- **Allocation** — `allocate_premix`, `allocate_full_pallet`, `allocate_bags`,
  `finish_allocation`, `allocation_return`, `allocation_transfer`. Note `allocation` is
  returned as an `allowedTab` for **both** roles.
- **Extrusion** — `view_extrusion_overview`, `assign_extrusion_run`, `finish_extrusion_run`.
  (Downstream machines can be started from the board, minting `RUN_*` ids, but there is no
  run overview or management.)
- **Pre-mix lifecycle** — `complete_premix`, `cancel_premix`, `cancel_premix_direct`,
  `assign_hopper`.
- **Completion** — `complete_station2_work`, `submit_job_card`.

Of an Operator's 17 allowed actions the app performs ~5; of an Admin's 24, ~7. **The app
covers collect → mix cycle, and nothing after it.**

**Known limitations, deliberately not written as failing tests:**

- Bag entry offers only `0 / ¼ / ½ / ¾`, so BOM quantities like 10.99 and 89.03 bags cannot
  be expressed exactly. Mitigated by the round-up default (D8) — not solved.
- Every backend call still blocks its screen in some flows, at 4–10 s latency.

**Not tested by choice:** multi-hour soak runs, two handhelds at once, gloved/glare
usability.

---

## §7 Verdicts and exit criteria

Every test resolves to exactly one:

| Verdict | Meaning |
|---|---|
| **PASS** | Behaved as specified |
| **FAIL(app)** | The Android app is wrong. **This is the headline number.** |
| **FAIL(backend)** | Station 2 is wrong. Reported to the backend team; does not count against the app |
| **BLOCKED** | Could not be run — say why (backend down, no account, hardware absent) |
| **N/A** | Not built (see §6) |

**The headline is app-only.** A backend defect or 9-second latency must never mask whether
your app is solid — but it is still recorded, still reported, and still visible.

### Release gate

The app is "solid enough to ship" when **all** hold:

1. Gates 1–3 fully green.
2. Zero `FAIL(app)` in §4.1, §4.2, §4.4 and §4.7 — transport, auth, collection, lifecycle.
   These are the paths where a defect costs material or stock accuracy.
3. Zero `FAIL(app)` in the regression register (§5) — nothing previously fixed has broken.
4. No `BLOCKED` test in §4.4 or §4.5. A collection or cycle path that could not be tested
   is not a passing path.
5. Every `FAIL(backend)` is filed with Station 2 and consciously accepted for this release.
6. `D-INT` mismatches are enumerated by material code — you decide whether to ship with
   known-wrong bag arithmetic. It is a decision, not an oversight.

Layout and settings failures do not block a release on their own, but three or more in one
run means the UI needs a pass before it goes to the floor.

---

## §8 Evidence

Every run produces `docs/test-runs/YYYY-MM-DD/`:

| Artefact | Contents |
|---|---|
| `report.md` | Verdict per test ID, evidence links, latency table, backend used per block |
| `capture/wire.jsonl` | Every MQTT frame, passwords redacted, request/response paired with latency |
| `capture/wire.log` | Human-readable transcript |
| `logcat.txt` | Full device log |
| `shots/<TESTID>-<n>.png` | Screenshot per step |
| `bounds/<TESTID>.xml` | uiautomator dumps backing the §4.8 assertions |
| `settings-before.json` | Captured settings, for the §4.9 restore |

The report must state, per block, **which backend answered** — live Station 2 or the
simulator. A result is meaningless without it.

---

## §9 Standing security notes

Facts, not tests. Neither should disappear because the functional tests are green.

1. **The supervisor PIN is a hardcoded constant** (`SettingsViewModel.correctPin =
   "079545"`). Anyone who decompiles the APK has it, and it gates broker host, username and
   password. It cannot be rotated without a new build.
2. **Operator and manager passwords cross the wire in cleartext** inside the MQTT payload
   (open finding B4). TLS protects the transport; the broker and anyone with broker
   credentials still sees them.

Both need a decision from you. This plan records them every run until one is made.
