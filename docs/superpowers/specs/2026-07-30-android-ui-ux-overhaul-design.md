# Station 2 Android — UI/UX Overhaul Design

**Date:** 2026-07-30
**Status:** Approved for planning
**Supersedes:** the visual system defined in `2026-06-25-ui-modernisation-design.md` (which itself already
drifted — the shipped theme is graphite + blue, matching the WPF sibling app, not the amber originally
specified there). Also **explicitly reverses** the "no Home/mode-select screen" decision recorded in
`2026-07-14-app-redesign-navigation-design.md` — see §4.1.
**Restyles, does not alter:** the mixing domain model, MQTT wire contract, use cases, and ViewModel
selection/scan logic defined in `2026-07-28-jc-driven-mixing-design.md`. This is a UI/UX-only spec. Where
it appears to conflict with that spec's already-implemented behaviour, the conflict is called out
explicitly in §6 rather than silently decided.

## 1. Why

The client's own framing: operators are minimally trained, work the floor on a rugged handheld
scanner (gloved hands, industrial conditions), and today's app "feels like a hindrance rather than an
asset to the workflow." Screens evolved independently — Login, Job Lookup, Ingredient Scan, the Mixing
Board, RFID Recovery, and Settings each grew their own visual conventions — and the result is
inconsistent and hard to navigate for someone without deep training on the app.

This is a full ground-up overhaul: visual system **and** information architecture, not a re-skin. The
app is free to diverge from the WPF sibling app's palette — the theme today matches it deliberately
(`Color.kt` comments reference WPF's `BlueColor`/`BlueDarkColor`), but that parity is not a requirement
operators benefit from, and this spec drops it.

### Scope

**In scope:** everything under `app/src/main/java/com/ppnam/station2aa/ui/` — theme, `AppScaffold`,
shared components, and all eight screens (Login, Home, Job Cards, Ingredient Scan, Mixing Area Picker,
Mixing Board, RFID Recovery, Settings). Navigation graph changes needed to add the Home screen.

**Out of scope:** the MQTT contract, domain models (`MixingBoard.kt`), use cases, and ViewModel business
logic. `2026-07-28-jc-driven-mixing-design.md` already defines the mixing state machine end-to-end
(`Equipment`, `ReadyMix`, `ReadyCollection`, `JandiDrum`, `RunInput`, `scanAllowed`,
`validDestinationMachineCodes`, `validMixerCodes`) and it's already implemented. This spec's job is to
make that existing, server-authoritative state legible to an untrained operator — not to redefine it.

## 2. Design Principles

The core shift: **from "figure it out" to "here's what to do next."** Concretely:

1. **Guided over exploratory.** Every screen leads with the single clearest next action where one
   exists (a "scan this next" card, a sticky primary button), rather than presenting an undifferentiated
   list and expecting the operator to infer what matters.
2. **State encoded in color and shape, not just text.** A card's border/fill color should communicate
   its status before its label does.
3. **Predictable structure.** The same three home-screen tiles, in the same order, every time. No
   layout that reorganizes itself based on state (rejected in favor of "adaptive status card" during
   design review — see the discarded option in the interactive prototype's history).
4. **One door per job, correctly reusing what already works.** E.g. resuming an in-progress ingredient
   collection is not a new feature — it's the existing `active_job_cards_list` / `lookupJob()` plumbing,
   just surfaced better.

## 3. Visual System

### 3.1 Color

Kept from the existing `Color.kt` base palette (background/surface/border/text) but color now carries
semantic weight everywhere, not just as an accent:

| Token | Hex | Role |
|---|---|---|
| `GraphiteBackground` | `#07101A` | Screen background |
| `GraphiteSurface` | `#102233` | Cards, panels |
| `GraphiteSurfaceVariant` | `#14293D` | Secondary surfaces |
| `GraphiteBorder` | `#25384C` | Card stroke, dividers |
| `TextPrimary` | `#EDF4FB` | Primary text |
| `TextMuted` | `#9BAEC0` | Secondary text, captions |
| Primary accent (interactive) | `#2E77F5` | Buttons, links, "running/info" status — the existing blue, kept as the one non-semantic accent |
| Success/ready | `#2BC36D` | Ready, satisfied, completed |
| Warning | `#F0A13A` | Needs attention, awaiting an operator action (e.g. Ready for Allocation) |
| Danger | `#E25C5C` | Blocked, alert, rejected |

Implementation note: the existing constant `AmberPrimary` holds a blue value with a comment explaining
it matches the WPF app's blue accent. Since this spec drops WPF parity, rename it to something that
doesn't contradict its own value (e.g. `AccentPrimary`) during implementation — cosmetic, not urgent.

### 3.2 Typography

System sans stack (already in use), capped to 4 sizes / 2 weights. New: a **tabular monospace variant**
for job card numbers, quantities, and timestamps — these are scanned at a glance under time pressure and
benefit from fixed-width digits the way the current free-form text doesn't provide.

### 3.3 Spacing & tap targets

8-point grid throughout (already partially followed). Minimum 44×44pt tap targets — the existing 56dp
buttons already clear this; extend the same minimum to every tappable card, chip, and icon button, which
today are inconsistent.

### 3.4 The card as the base component

Nearly every screen becomes a stack or grid of color-coded cards: BOM lines, active job cards, mixing
areas, equipment/machine cards, RFID results. One visual language, reused everywhere, replacing today's
mix of dense text rows, ad hoc `Card` usage, and the one flat `LazyColumn` list in the Area Picker where
all five areas currently render identically regardless of status.

### 3.5 Dialogs

Standardize on `DialogFormColumn` for every modal form (cancel job, short-bag waiver, exception approval,
manager/PIN entry). Usage is currently inconsistent across `IngredientScanScreen`'s six-plus dialogs —
bring them in line as part of this pass.

## 4. Information Architecture

### 4.1 Reintroducing a Home screen (supersedes 2026-07-14's decision)

The 2026-07-14 spec removed Home because "there will no longer be separate operating 'modes' selected
from a Home screen" — a deliberate client decision at the time, not incidental cleanup (an earlier
description of this as unrelated migration debris was wrong). That decision predates this overhaul's
starting complaint. Today `JobLookupScreen` is the de facto landing screen *and* carries the entry points
for RFID recovery, Settings, and jumping directly into an active mix — three unrelated jobs stacked on
one screen, which is a direct contributor to "difficult to navigate."

This spec reverses that decision. A Home screen returns, but not as a "mode select" in the old sense —
it is three fixed, equal-weight, always-in-the-same-place tiles, not a menu of exclusive operating modes:

- **Job Cards** — start a new job or resume any in-progress collection
- **Mixing Board** — browse/operate machines directly, independent of any one job card
- **Fix a Tag** — RFID recovery

Plus a settings gear in the header. Tiles are static and predictable by design (validated against an
"adaptive status card" alternative during design review, which was rejected — see §7).

### 4.2 Job Cards (renamed from Job Lookup)

One door for starting a new job *or* resuming any in-progress collection, reusing the existing
`active_job_cards_list` fetch and `lookupJob(orderNo, collectionId)` call — both already correctly route
to the right next step regardless of how far along a collection is. The only change is presentation: the
active-jobs list currently renders as dense text rows and moves to the color-coded card language from
§3.4.

## 5. Screen-by-screen

### 5.1 Login

Password stays the primary path (confirmed — floor operators don't necessarily carry a scannable badge
as their primary credential). The existing "or scan your badge" option today is a bare text divider with
no visible tap target; give it a real affordance (icon + button), still secondary to the password form.

### 5.2 Home

See §4.1. Header shows operator name + line/shift. No adaptive content — three tiles, always.

### 5.3 Job Cards

See §4.2. Active jobs render as color-coded cards (status: Collecting = blue, Ready for Mixing = green,
Awaiting Approval = amber). Manual order-number entry stays below the list for starting fresh.

### 5.4 Ingredient Scan

The single biggest friction point in the current app: operators must tap a BOM line to "arm" it before
scanning, with no visible explanation why. Redesign:

- A **"scan this next"** card leads the screen — large type, the single next required ingredient, one
  scan button already primed for it.
- The full BOM stays visible below as a checklist, and **every line stays tappable, not just the next
  one** — confirmed with the client that operators collect whatever pallet is physically nearby first,
  not strictly in BOM order, so jumping the queue must stay first-class, not a fallback.
- Admin actions (cancel job, short-bag waiver, exception approval) move behind an overflow (⋮) menu.
  They're still available, but they were previously competing for visual weight with the everyday task,
  which they are not part of.
- Once every line is satisfied, a sticky "Start Mixing →" button appears (green), leading to the Mixing
  Area Picker.

### 5.5 Mixing Area Picker

Today's five area cards are visually identical, differentiated only by a small muted text line. Give
each of the five areas (Dolci, Main Mixing Room, Jandi, Mackie, Rajoo — these are five distinct
production zones, not five identical mixer sets) a color-coded status swatch reflecting its current
state, plus a one-line description of what kind of area it is (e.g. "3 fixed direct-feed mixers" for
Dolci, "5 mixers · allocate to any area but Rajoo" for Main).

### 5.6 Mixing Board — area-specific behavior

This is the core of the overhaul and the part most likely to be gotten wrong by treating it as one
generic "pick a machine, scan it" screen, which the first draft of this design did before the client
corrected it. **The interaction pattern differs by area**, and the board must reflect that rather than
flattening it into a single generic machine grid:

| Area | Pattern | UI |
|---|---|---|
| Dolci | Direct-feed, fixed 1:1 (Mixer 1→Machine 1, Mixer 2→Machine 2, Mixer 11→Machine 11) | One card per fixed pair. First scan starts mixing **and** production as one combined stage; the second scan of the *same* mixer ends and completes it. No separate destination card — the machine is implied by `fixedDestinationMachineCode`. |
| Mackie | Direct-feed, single fixed pair | Same pattern as Dolci, one card. |
| Main Mixing Room | Staged — mixing, then allocation, then production | Two sections: (1) five mixer cards, each toggling ready↔mixing on scan; (2) a separate **"Ready to Allocate"** list (mirrors the domain's `ReadyMix`/`readyMixes`) once a mixer's second scan ends mixing. Each ready item shows its valid destination machines to scan — which may be **any** area's production machine (25 extruders, Dolci, Jandi, Mackie), **never Rajoo**. First destination scan allocates + starts production; the second scan of that same destination completes it. |
| Jandi | Mixed: two direct-feed machines fed by one shared bulk mixer, plus a distinct two-input composite (Jandi 4) | The Jandi Drum (a `Transfer`-role equipment, not a `Mixer`) gets its own persistent status card, separate from mixer cards, since it's a single shared resource for the area. The bulk mixer resolves to Jandi 2 or Jandi 3 automatically from the JC's own allocation — **no in-app route picker**; the physical scan of whichever code is correct (`JAN-02` / `JAN-03` / `JAN-DRUM-01`) is itself the selection (confirmed with the client — see §6.1 for the conflict this raises against the current implementation). If the dedicated bulk mixer is unavailable, a Main Mixing Room mixer can be allocated to Jandi 2/3 instead, following the standard Main Mixing Room staged pattern — no special-case UI. Jandi 4 gets its own composite card showing two linked inputs (the decanted drum + a selected Main Mixing Room mixer), both tied to the same JC — this must not be presented as a single-mixer direct feed. |
| Rajoo | Direct-feed, 3 fixed layers into one extruder | Three layer-labeled cards (gravimetric mixers), each following the direct-feed scan-twice pattern, feeding the shared 3-layer extruder. Rajoo is the **only** area excluded from the Main Mixing Room fallback — a mix from one of the five main mixers must never be offered as a valid destination here, and the board should say so explicitly rather than leave it as an absence. |

**Status vocabulary**, matching what the domain layer already reports — this is copy/labeling guidance,
not a new state machine:

- Direct-feed: *Ingredients Collected → Mixing and Production In Progress → Completed*
- Main Mixing Room: *Ingredients Collected → Mixing → Ready for Allocation → Production In Progress →
  Completed*

The underlying selection/highlight mechanics (`BoardSelection`, `computeHighlightedMachines`,
`scanAllowed`-gated scan affordances) defined in §6.1 of the JC-driven mixing spec are **not** being
replaced — this spec restyles their presentation into the card language above; it does not change what
triggers a highlight or what a scan is allowed to do.

### 5.7 RFID Recovery

Keeps its already-good pattern (`ScanPromptCard` + `LabelValueRow`, color-coded accent bar on the
result). One addition: a manual pallet-ID entry fallback for when a tag is too damaged to scan — today
there is no recovery path other than physically re-scanning.

### 5.8 Settings

Structure is unchanged — Diagnostics tier (broker connection, Station 2 online status, app version) stays
operator-facing and simple. The PIN-gated technician config tier (device ID, MQTT host/port/TLS,
timeouts) gets a visually distinct "technician mode" treatment — warning-toned border, lock icon, an
explicit collapsed-by-default state — so it reads as a different kind of control, not an everyday
settings toggle an operator might tap into by accident.

## 6. Open items for the implementation plan

These are places where this spec's UI-level conclusions appear to conflict with, or need verification
against, the already-implemented mixing logic from `2026-07-28-jc-driven-mixing-design.md`. They are
flagged, not resolved here, because resolving them requires reading the current `MixingBoardScreen.kt`/
`MixingBoardViewModel.kt` implementation and possibly real wire data — implementation-plan work, not
design work.

1. **Jandi route picker.** §5.6 above states there is no in-app route picker for Jandi — the physical
   scan of `JAN-02` / `JAN-03` / `JAN-DRUM-01` is itself the route selection, per the client's direct
   confirmation. But the JC-driven mixing spec's §6.1 describes `BoardSheet.StartConfirm` gaining
   `routeOptions` / `selectedRoute` specifically for JANDI, and a `SELECT_JANDI_ROUTE` next-action — which
   reads as a manual on-screen picker. The implementation plan must check the shipped code and confirm
   which is true before touching this area: it's possible `routeOptions` is populated from
   `validMixerCodes` and rendering it as tappable *scan targets* (not an abstract dropdown) already
   satisfies both descriptions — in which case this is a non-conflict, just a presentation question.
2. **`AmberPrimary` constant rename** (§3.1) — cosmetic, low-risk, but touches every screen file that
   imports it.
3. **Rajoo layer representation.** Confirm the three gravimetric mixers are already modeled as three
   separate `Equipment` rows with `productLayer` set (1/2/3) rather than one equipment entry — the
   three-card-per-layer UI in §5.6 assumes this.

## 7. Interactive prototype

A working click-through prototype covering all eight screens (including the area-specific Mixing Board
behavior in §5.6) was built and approved during design review:
`https://claude.ai/code/artifact/eb50be15-9d11-41b8-ac95-a68487701a7c`

It uses mock data and a representative subset of destination machines (not the full 25 extruders) for
demo purposes — not a source of truth for exact field names or the full destination list, which come
from the domain model and JC-driven mixing spec instead. It's a reference for interaction patterns and
visual language, not a spec of wire behavior.

Discarded alternative, for the record: an "adaptive status card" version of the Home screen (content
changes based on whether a mix is currently active) was mocked up alongside the static three-tile
version and rejected in favor of the static version's predictability.

## 8. Testing / rollout

This is a presentation-layer change; the JC-driven mixing spec's existing unit test coverage
(`MixingBoardUseCaseTest`, `MixingBoardViewModelTest`) is unaffected and should keep passing untouched
unless §6 investigation finds an actual behavior change is needed. New coverage to add during
implementation: navigation tests for the new Home destination, and Compose UI tests for the restyled
card components (equipment card, area card, active-job card) given they become the primary building
block reused across most screens.

Manual verification should include: full flow walkthrough per area kind (Dolci/Mackie direct-feed,
Main Mixing Room staged + cross-area destination, Jandi drum + Jandi 4 composite, Rajoo 3-layer) against
the real backend or simulator, confirming the visual restyle didn't change what scans are accepted or
when.
