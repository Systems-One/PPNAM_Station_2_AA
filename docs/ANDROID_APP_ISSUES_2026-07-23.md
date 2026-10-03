# PPNAM Station 2 Android App — Issues Found in Live Testing

**Date:** 2026-07-23
**App:** `com.mitas.ppnam.station2aa` v1.0 (1), branch `master`
**Device:** Chainway C72 (`handheld_1`), Android 13, 1080×1920 @ 480 dpi
**Backend:** the live Station 2 backend over `wss://mqtt.sysone.co.za:443/mqtt`
**Operators:** `Jono` (Operator), `Avi` (Admin)
**Method:** UI driven via `uiautomator` + `adb input`; scans injected as genuine Chainway
broadcasts (`com.scanner.broadcast` → `DataWedgeReceiver`); full logcat and MQTT capture.
Evidence in `docs/test-evidence-2026-07-23/`.

**Scope note:** multiple collections per job card is **intended, client-requested behaviour**.
Nothing here treats it as a defect; A9 addresses only how those collections are *displayed*.

---

## Severity summary

| ID | Severity | Issue | Where |
|---|---|---|---|
| A1 | **High** | No window insets anywhere — root cause of the recurring toolbar cropping | `MainActivity.kt:24`, `AppScaffold.kt:89` |
| A2 | **High** | Bag dialog opens with no line armed, then silently discards the entry | `MixingViewModel.kt:275-284` |
| A3 | **High** | `allowedActions` / `allowedTabs` received then ignored | `OperatorSessionHolder.kt:20-22` |
| A4 | **High** | ¼-bag granularity cannot express BOM quantities | `IngredientScanScreen.kt:226-266` |
| A5 | **High** | Every backend call blocks the whole screen | `MixingViewModel.kt:292` |
| A6 | **Medium** | Dialog action buttons sit under the IME | force-close, login |
| A7 | **Medium** | Layout defects on ingredient cards | `IngredientScanScreen.kt` |
| A8 | **Medium** | Login screen: Back exits app; IME hides controls | `LoginScreen.kt` |
| A9 | **Medium** | Active Jobs cannot distinguish collections of the same job card | `JobLookupScreen.kt` |
| A10 | **Medium** | Logout hidden behind the operator-name label | `AppScaffold.kt:118` |
| A11 | **Medium** | The two cancel dialogs contradict each other | `IngredientScanScreen.kt` |
| A12 | **Low** | Raw ISO timestamps, missing operator name | `MixingBoardScreen.kt:337` |
| A13 | **Low** | Assorted smaller items | various |
| A14 | **Plan** | Large parts of the Station 2 process have no UI at all | — |

---

## A1. No window insets anywhere — root cause of the recurring toolbar cropping — **High**

`MainActivity.kt:24` calls `enableEdgeToEdge()`, so the app deliberately draws underneath the
system status bar. But **no window insets are ever applied**:

```
grep -r "statusBarsPadding|systemBarsPadding|imePadding|safeDrawing" app/src/main/java
→ 0 matches
```

`AppScaffold`'s `Scaffold` (line 89) passes no `contentWindowInsets`, and the manifest
declares no `windowSoftInputMode`.

### Observed on the C72
- The top bar sits under the status bar; the title overlaps the clock.
- When the IME opens, the window pans and the app bar is pushed **completely off-screen** —
  Back, title, Settings and the connection pill all unreachable
  (`shots/36-topbar-scrolled.png` vs `shots/33-settings.png`).
- Because logout lives in that bar, **an operator cannot log out while a keyboard is open**.

### Why this matters
This is the underlying cause of the cropping issues that have been patched repeatedly at the
layout level (row heights, text sizes, truncation). Those patches treat symptoms.

### Fix
Apply `Modifier.statusBarsPadding()` (or `WindowInsets.safeDrawing`) to the scaffold/top bar
and `imePadding()` to scrollable content. Set `contentWindowInsets` on the `Scaffold` rather
than adjusting dimensions.

---

## A2. Bag dialog opens with no line armed, then silently discards the entry — **High**

`MixingViewModel.kt:275-284`:

```kotlin
val line = armedLineNumber?.let { ln -> order.lines.firstOrNull { it.lineNumber == ln } }
if (line == null) {
    _supervisorError.trySend("Select a material line before scanning a pallet.")
    _uiState.value = orderLoadedState(order)
    return          // <- nothing is published
}
```

The bag-entry dialog is shown for **any** scanned pallet regardless of arming. The operator
scans, types a bag count, taps **Confirm Scan**, and the dialog closes having sent nothing.
A snackbar does fire — but only after all that work, and it auto-dismisses.

Reproduced twice; the MQTT capture shows no `ingredient_scan_requested` for those attempts.

### Fix
The app already knows the scanned pallet's material code — **auto-arm the matching line**.
Failing that, refuse the scan up front and never open the dialog.

---

## A3. `allowedActions` / `allowedTabs` are received then ignored — **High**

Measured on the wire:

| Role | Actions | Admin-only extras |
|---|---|---|
| Operator (Jono) | 17 | — |
| Admin (Avi) | 24 | `allocation_return`, `allocation_transfer`, `cancel_premix_direct`, `ingredient_approve_override`, `ingredient_approve_short_bag`, `ingredient_collection_cancel`, `machine_force_close` |

`allowedTabs` is identical for both (`collect`, `premix`, `allocation`).

The app parses this (`AuthMessages.kt:35-36`), stores it (`OperatorSessionHolder.kt:20-22`)
and maps it (`AuthUseCase.kt:63-64`) — and **no UI code reads either field**. Verified: zero
references under `ui/`.

### Impact
Both roles render an identical UI. None of the admin's 7 extra privileges is surfaced, and no
operator-facing control is hidden. An unpermitted action is discovered only after a
2.5–7.6 s round trip and a generic rejection.

### Fix
Gate controls on `allowedActions` — the data is already on the device. Hide or disable
force-close, approvals and cancel for operators who lack them.

---

## A4. ¼-bag granularity cannot express BOM quantities — **High**

BOM lines require fractional bags — 89.03, 10.99, 37.09, 2.97, 0.74. The dialog offers only
`0, ¼, ½, ¾`. Meanwhile:

- under-collection has **no** tolerance (89.00 / 89.03 → `isRequirementSatisfied: false`)
- over-collection has a **1-bag** tolerance and auto-passes

So the only workable operator rule is *always round up* — and nothing in the UI says so. The
dialog also never shows how many bags are still required, so the operator must remember it
from the card behind the dialog.

### Fix
- Show remaining bags **inside** the dialog and pre-fill the rounded-up count.
- State the tolerance.
- Longer term, allow decimal or weight entry for the final partial bag.

> Note this interacts with backend **B1** — the bag figures currently displayed are not
> trustworthy, so fix B1 first or the pre-filled count will be wrong too.

---

## A5. Every backend call blocks the whole screen — **High**

`MixingViewModel.kt:292` sets `_uiState.value = MixingUiState.Loading`, replacing the entire
screen for the duration of the request. Combined with backend latency of 2.5–7.6 s per scan,
an operator collecting a 6-line BOM spends ~30 s staring at spinners, unable to see what they
have already collected.

### Fix
Use an inline per-line pending state that leaves the rest of the list readable and the screen
scrollable. Only block the screen for operations that genuinely invalidate it.

---

## A6. Dialog action buttons sit under the IME — **Medium**

In the force-close dialog the Cancel / Force close buttons are at y=1167 with the keyboard
open and y=1410 with it closed — the keyboard covers them and taps aimed at the button land
on keys instead. During testing a stray `i` was appended to the audit-reason field this way.
The dialog neither scrolls nor lifts its buttons above the IME.

Same root cause as A1. Also affects the login form (A8).

### Fix
`imePadding()` on dialog content, or move actions into a bottom bar that tracks the IME.

---

## A7. Layout defects on ingredient cards — **Medium**

- **Cards clip behind the bottom button bar.** The scrolling line list lacks bottom padding
  for the Cancel / "Mixing after collection" bar, so the last visible card's "Short bags"
  action renders half-cut (`shots/16-line1-done.png`).
- **Product name collides with the quantity.** The material name wraps underneath the
  right-aligned kg value and the two overlap — "MASTERBATCH BLACK ME 9200 ME" over
  "74.19 kg" (`shots/10-jc-337-result.png`). Constrain the name column; give the value a
  fixed width.
- **Two unlabelled progress bars** per line (kg and bags) look identical and neither is
  captioned.

---

## A8. Login screen — **Medium**

- **Back exits the app.** One Back press from Login drops the operator to the Android
  launcher without even dismissing the IME first. Easy to hit by accident on a shared
  handheld.
- **The IME hides the password field and Log In button.** The form does scroll the focused
  field into view and the IME Done key does submit (both good), but the button itself is
  never reachable while the keyboard is up.
- **"Or scan your badge" sits above the username field**, so the first thing the operator
  reads is the second option. It should be a divider *between* the two methods.

---

## A9. Active Jobs cannot distinguish collections of the same job card — **Medium**

Multiple concurrent collections per job card is intended behaviour. But the Active Jobs list
renders each entry as just the **order number plus the product description**, so four
collections of 510019340 appeared as four visually identical rows
(`shots/79-logged-out.png`) — while actually holding 0%, 75%, 75% and 0% progress.

The operator has no way to pick the one they were working on.

### Fix
The backend already returns everything needed per entry — `collectionId`, `progressPercent`,
`completedIngredientCount` / `requiredIngredientCount`, `status` — none of which is
displayed. Show them, e.g. **"COL_000039 · 3 of 4 lines · 75%"**. Display-only change; the
underlying behaviour stays as designed.

---

## A10. Logout is hidden behind the operator-name label — **Medium**

`AppScaffold.kt:118` wraps "Jono · Operator" in a `TextButton` that opens the logout dialog.
It has no icon, no underline and no button styling — it reads as a status label. This is the
**only** way to switch users, and it is absent from the Settings screen.

### Fix
Give it an affordance (icon or overflow menu) and add logout to Settings.

---

## A11. The two cancel dialogs contradict each other — **Medium**

First dialog:
> "This closes the job card **if it hasn't had any activity yet** (ingredients scanned, mixing
> started, etc). You'll be notified if it can't be cancelled."

Immediately after, second dialog:
> "Cancelling a job card **always** needs a manager's approval."

The first implies it may simply succeed; the second says approval is unconditional. The app
knows it is unconditional — it prompts locally without sending anything.

### Fix
State the approval requirement once, up front, and drop the conditional wording.

---

## A12. Raw ISO timestamps and a missing operator name — **Low**

The active-cycle sheet (`MixingBoardScreen.kt:337`) shows:

> "Started 2026-07-23T11:38:28.2733333+00:00 by "

An unformatted ISO-8601 string no shop-floor operator can read, followed by "by " with **no
name** — it renders `startedByOperatorId`, which is empty/opaque.

### Fix
Format as local time (or relative, "12 min ago") and resolve the operator's display name.

---

## A13. Smaller items — **Low**

- **Production Order No. opens a QWERTY keyboard** for a digits-only field. The bag-count
  field correctly uses a numeric keypad — match it.
- **A wrong supervisor PIN gives no message.** `SettingsViewModel.pinError` is a Boolean used
  only as `isError`, so the operator gets a red border and no explanation. *(The lockout
  itself works correctly: 5 attempts → "Too many attempts. Try again in 30s.")*
- **The snackbar is light-on-dark** — a bright grey panel with dark text in an otherwise dark
  UI, harsh in a dim plant.
- **The splash screen is white**, flashing on every cold start.
- **The invalid-order message is internal jargon:** *"SAP production order lookup failed and
  no stored local BOM snapshot is available."*
- **RFID lookup omits `remainingBags`.** The response carries `remainingBags: 991` but the
  screen shows only kg — in a bag-driven workflow the bag count is arguably more useful.

---

## A14. Large parts of the Station 2 process have no UI at all — **Plan-level**

The app implements **13 request types**. Several capabilities the backend grants have **zero**
source matches in the app:

- **Allocation** — `allocate_premix`, `allocate_full_pallet`, `allocate_bags`,
  `finish_allocation`, `allocation_return`, `allocation_transfer`.
  Note `allocation` is returned as an **`allowedTab`** for both roles, yet no allocation
  screen exists.
- **Extrusion** — `view_extrusion_overview`, `assign_extrusion_run`, `finish_extrusion_run`.
  (Downstream machines *can* be started from the board, minting `RUN_*` ids — but there is no
  run overview or run management.)
- **Pre-mix lifecycle** — `complete_premix`, `cancel_premix`, `cancel_premix_direct`,
  `assign_hopper`.
- **Completion** — `complete_station2_work`, `submit_job_card`.

Of the Operator's 17 allowed actions the app can perform roughly 5; of the Admin's 24, roughly
7. This is a roadmap fact rather than a defect, but it should be stated plainly: **the app
today covers collect → mix cycle, and nothing after it.**

---

## What the app does well

Worth protecting in any refactor:

- **Concurrency is genuinely solid.** Four cycles ran simultaneously across two areas
  (MXR-01/02/03 in Main Mixing Room plus JAN-MIX-01 in JANDI). Machine states flipped
  Available/InUse correctly and independently; force-closing one cycle left the others
  untouched; area-picker counters stayed accurate. No cross-talk, no stale state.
- **Two-stage routing is correct.** Finishing a mixer cycle produced a mix batch, and the
  board offered only valid next machines — correctly excluding drum-gated JAN-04.
- **Offline handling is good.** Dropping Wi-Fi flipped the pill to "Reconnecting" within 12 s;
  an action attempted offline gave "Not connected to Station 2" with a **Retry**; restoring
  Wi-Fi reconnected automatically in ~32 s and Retry restored full function.
- **Back-navigation is safe.** Leaving the collect screen raises "Go back? … Your progress is
  saved." — and progress genuinely is preserved.
- **Scan confirmation before publishing.** The app never commits on the raw barcode alone.
- **Unrecoverable pallets prompt in context** — "Pallet not in Holding — Recover?" rather than
  a dead end.
- **RFID pallet lookup** is the cleanest, fastest screen in the app.
- **PIN lockout works** and is a real protection.
- **The dynamic broadcast receiver works.** The Android 11+ package-visibility workaround in
  `DataWedgeReceiver` is correct; every injected scan arrived.

---

## Recommended order

| # | Item | Why |
|---|---|---|
| 1 | **A1** apply window insets | Fixes the whole toolbar-cropping class at source |
| 2 | **A2** auto-arm the scanned line | Silent data loss during normal use |
| 3 | **A3** honour `allowedActions` | Role safety; data already on the device |
| 4 | **A5** stop blocking the screen | Biggest perceived-speed win available client-side |
| 5 | **A9** show collection id + progress | Operators currently cannot pick the right job |
| 6 | **A6, A8** IME-safe dialogs and login | Same root cause as A1, finish the job |
| 7 | **A4** bag entry guidance | Do after backend B1 lands |
| 8 | **A7, A10, A11, A12, A13** | Polish and copy fixes |
| 9 | **A14** | Roadmap decision, not a fix |

---

## Evidence

| Artefact | Contents |
|---|---|
| `test-evidence-2026-07-23/shots/` | screenshots referenced above |
| `test-evidence-2026-07-23/capture*/wire.jsonl` | MQTT frames, latency-paired |
| `test-evidence-2026-07-23/logcat*.txt.gz` | full device logs |
| `test-evidence-2026-07-23/FINDINGS.md` | raw running log, F-001 … F-048 |
| `test-evidence-2026-07-23/sweep2.py`, `collect.py`, `board.py` | the UI harness, re-runnable |
