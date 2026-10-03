# UI Overhaul Phase 4: Mixing Area Picker + Mixing Board — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the Mixing Area Picker (5 area cards, currently visually identical) and the Mixing
Board (the app's most complex screen) with the shared `StatusCard` color language, per
`docs/superpowers/specs/2026-07-30-android-ui-ux-overhaul-design.md` §5.5–§5.6 — without changing
any selection, scan, or dialog business logic, all of which is already implemented and tested per
`docs/superpowers/specs/2026-07-28-jc-driven-mixing-design.md`.

**Architecture:** `StatusCard` (Phase 2) gains one new optional `highlighted: Boolean = false`
parameter — resolving a gap Phase 2's own final review flagged in advance: `tone` alone can't
express both "what state is this in" (status) and "is this the thing to tap right now"
(highlighted/scan-target) at once, and Mixing Board's equipment grid is exactly the first screen
that needs both simultaneously. Every hand-rolled `Card` across both screens — area cards,
collection/mix/drum/cycle/run cards, and equipment tiles — swaps to `StatusCard` with a tone
derived from data that already exists (`readyMixes`, `activeCycles`, `machine.status`). No
`MixingBoardViewModel` selection/scan/dialog logic changes.

**Tech Stack:** Kotlin, Jetpack Compose. Tests: JUnit4 + plain assertions for the new pure tone
functions, matching Phases 1–3.

## Global Constraints

- No MQTT contract, domain model, use case, or `MixingBoardViewModel` changes. `BoardSelection`,
  `BoardSheet`, `computeHighlightedMachines`, `machineChosen`, `selectRoute`, `selectMainSource`,
  `confirmStart`, and every dialog's fields/validation stay byte-identical.
- `StartConfirmDialog`'s route/main-source radio pickers and Rajoo dose fields are **not** touched
  in this phase — see Scope note below, they're already chromatically consistent.
- Follow existing code conventions exactly: named Compose modifier parameters, 4-space indentation,
  existing import ordering.

## Scope note (found during investigation, not verbatim in the spec)

1. **`StartConfirmDialog`'s radio pickers need no restyle.** `RadioButtonDefaults.colors(selectedColor
   = AmberPrimary)` already uses the exact same theme constant `StatusTone.Running.color()` resolves
   to (`Color.kt:16`) — they're not a different, unstyled color scheme, they're already the
   established accent. The design spec's original vision of "restyling" this dialog turned out, on
   inspection, to already be done by coincidence of the existing palette. No task here.

2. **The design spec's §5.6 implied five visually-distinct area-specific board layouts. The real
   code has one generic, server-data-driven board (`BoardContent`) that already handles every area
   through shared sections** (Collections ready to mix / Ready mixes / JANDI drum / Machines grid /
   Active cycles / Active runs) rather than per-area composables — the "5 different interaction
   patterns" complexity documented in the design spec already lives in the domain/ViewModel layer
   (`Equipment.fixedDestinationMachineCode`, `validDestinationMachineCodes`, `JandiDrum`, `RunInput`),
   not in bespoke per-area UI. Rewriting this into five bespoke layouts would be a large, risky
   rewrite of the most complex, heavily-tested screen in the app, for a benefit the generic
   server-driven sections already deliver. This plan restyles the existing generic structure with
   the shared card language instead of rebuilding it around area identity.

3. **Jandi route picker (design spec §6.1, previously unresolved): confirmed real, shipped, and
   required** — `StartConfirm.routeOptions`/`selectedRoute` render as actual radio buttons
   (`MixingBoardScreen.kt:472-487`) and a start is hard-blocked without a selection
   (`MixingBoardViewModel.kt:488-497`, test-locked at `MixingBoardViewModelTest.kt:671-672`). This
   contradicts what the client described during design brainstorming (auto-resolved, no picker).
   Per human decision: **accepted as shipped, no code change in this phase.** (Ties into point 1 —
   also confirms there is nothing to restyle here beyond what's already correct.)

---

### Task 1: `StatusCard` — add a `highlighted` parameter

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/StatusCard.kt`

**Interfaces:**
- Produces: `StatusCard(tone, onClick, enabled, highlighted: Boolean = false, modifier, content)` —
  consumed by Task 4 (`MachineCard`). Backward compatible: every existing caller (Job Cards,
  Ingredient Scan, and this phase's Tasks 2–3) omits it and gets identical behavior to today.

No test: the added logic is two small conditionals with no meaningful branch to lock in beyond what
compiling + Task 4's usage already verifies, consistent with `StatusCard`'s own precedent (no
dedicated composable test exists for it today).

- [ ] **Step 1: Add the parameter and separate the highlight axis from the tone axis**

In `app/src/main/java/com/ppnam/station2aa/ui/components/StatusCard.kt`, replace:

```kotlin
@Composable
fun StatusCard(
    tone: StatusTone = StatusTone.Idle,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(accent: Color) -> Unit,
) {
    val accentColor = tone.color()
    val borderColor = if (tone == StatusTone.Idle) GraphiteBorder else accentColor
    val shape = RoundedCornerShape(16.dp)
    var cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(enabled = enabled, onClick = onClick)
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = shape,
        border = BorderStroke(if (tone == StatusTone.Idle) 1.dp else 2.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content(accentColor)
        }
    }
}
```

with:

```kotlin
@Composable
fun StatusCard(
    tone: StatusTone = StatusTone.Idle,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /**
     * Whether this is the current scan/tap target, independent of [tone]. A card can be e.g.
     * `Warning`-toned AND highlighted at once — status (what state something is in) and highlight
     * (is this the thing to act on right now) are two different questions, so they get two
     * parameters rather than overloading [tone] to answer both.
     */
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(accent: Color) -> Unit,
) {
    val accentColor = tone.color()
    val borderColor = when {
        highlighted -> AmberPrimary
        tone == StatusTone.Idle -> GraphiteBorder
        else -> accentColor
    }
    val borderWidth = if (highlighted || tone != StatusTone.Idle) 2.dp else 1.dp
    val shape = RoundedCornerShape(16.dp)
    var cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(enabled = enabled, onClick = onClick)
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = shape,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content(accentColor)
        }
    }
}
```

When `highlighted = false` (the default), `borderColor`/`borderWidth` reduce to exactly the same
expressions as before — every existing caller is unaffected.

- [ ] **Step 2: Verify existing callers still compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Job Cards' and Ingredient Scan's `StatusCard(...)` calls don't pass
`highlighted`, so they compile and behave identically.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/components/StatusCard.kt
git commit -m "feat(ui): add highlighted parameter to StatusCard, separate from tone"
```

---

### Task 2: Restyle the Mixing Area Picker

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreenKtTest.kt`

**Interfaces:**
- Consumes: `StatusCard`/`StatusTone` (Phase 2/Task 1).
- Produces: `internal fun areaTone(mixes: Int, cycles: Int): StatusTone`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class MixingAreaPickerScreenKtTest {

    @Test
    fun `a ready mix takes priority and maps to Ready`() {
        assertEquals(StatusTone.Ready, areaTone(mixes = 1, cycles = 3))
    }

    @Test
    fun `active cycles without a ready mix map to Running`() {
        assertEquals(StatusTone.Running, areaTone(mixes = 0, cycles = 2))
    }

    @Test
    fun `nothing active maps to Idle`() {
        assertEquals(StatusTone.Idle, areaTone(mixes = 0, cycles = 0))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreenKtTest"`
Expected: FAIL — `areaTone()` does not exist yet (compile error).

- [ ] **Step 3: Update imports and add `areaTone()`**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt`, replace:

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
```

with:

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

/**
 * A ready mix waiting is the strongest signal (act now); active cycles alone mean the area is
 * working but has nothing new for the operator yet; neither means nothing to report.
 */
internal fun areaTone(mixes: Int, cycles: Int): StatusTone = when {
    mixes > 0 -> StatusTone.Ready
    cycles > 0 -> StatusTone.Running
    else -> StatusTone.Idle
}
```

`clickable` and `GraphiteBorder` are dropped — `clickable` is no longer called directly (`StatusCard`
owns it internally) and `GraphiteBorder` was only used by the per-area `Card`'s border, replaced in
Step 4. `BorderStroke`, `GraphiteSurface`, `SuccessGreen` stay: the "pending collection" banner
`Card` above the area list is unchanged in this task and still uses them — check after Step 4
whether `SuccessGreen` becomes unused (it's no longer referenced by the per-area card once tone
drives that color) and remove it then if so.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreenKtTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Restyle the per-area cards**

Replace:

```kotlin
                items(MixingArea.entries) { area ->
                    val equipment = state.overview.equipment.filter { it.area == area }
                    val available = equipment.count { it.isEnabled && it.status == "Available" }
                    val cycles = state.overview.activeCycles.count { it.area == area }
                    val mixes = state.overview.readyMixes.count { it.area == area }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onAreaChosen(area) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(area.display, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$available machine(s) available · $cycles active cycle(s) · $mixes ready mix(es)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mixes > 0) SuccessGreen else TextMuted,
                            )
                        }
                    }
                }
```

with:

```kotlin
                items(MixingArea.entries) { area ->
                    val equipment = state.overview.equipment.filter { it.area == area }
                    val available = equipment.count { it.isEnabled && it.status == "Available" }
                    val cycles = state.overview.activeCycles.count { it.area == area }
                    val mixes = state.overview.readyMixes.count { it.area == area }
                    StatusCard(
                        tone = areaTone(mixes = mixes, cycles = cycles),
                        onClick = { onAreaChosen(area) },
                    ) { accent ->
                        Text(area.display, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$available machine(s) available · $cycles active cycle(s) · $mixes ready mix(es)",
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                        )
                    }
                }
```

- [ ] **Step 6: Verify the full build and remove any now-unused import**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If the build warns `SuccessGreen` is unused, remove that import — it
was only used by the code just replaced.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreen.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingAreaPickerScreenKtTest.kt
git commit -m "feat(mixing): restyle area picker cards with StatusCard"
```

---

### Task 3: Restyle Mixing Board's list sections (collections, mixes, drum, cycles, runs)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`

**Interfaces:**
- Consumes: `StatusCard`/`StatusTone` (Phase 2/Task 1). Does not touch `MachineCard` (Task 4) or
  `StartConfirmDialog`/`CycleSheetDialog`/`ForceCloseDialog` (out of scope, see Scope note).

Five sections inside the `BoardContent` composable, all following the same "hand-rolled `Card` →
`StatusCard`" pattern. No new pure function needed here — tone is a simple inline expression per
section (selected/not-selected, or a fixed tone), not complex enough to warrant extraction, unlike
Task 2's `areaTone` and Task 4's tone function (which is genuinely reused).

- [ ] **Step 1: Add imports**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`, add:

```kotlin
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
```

- [ ] **Step 2: Restyle "Collections ready to mix"**

Replace:

```kotlin
                items(board.readyCollections, key = { it.collectionId }) { collection ->
                    val selected = (board.selection as? BoardSelection.Collection)
                        ?.collectionId == collection.collectionId
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy) { viewModel.selectCollection(collection.collectionId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${collection.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text(collection.collectionId,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            if (collection.productName.isNotBlank()) {
                                Text(collection.productName,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                    }
                }
```

with:

```kotlin
                items(board.readyCollections, key = { it.collectionId }) { collection ->
                    val selected = (board.selection as? BoardSelection.Collection)
                        ?.collectionId == collection.collectionId
                    StatusCard(
                        tone = if (selected) StatusTone.Running else StatusTone.Idle,
                        onClick = { viewModel.selectCollection(collection.collectionId) },
                        enabled = !board.busy,
                    ) {
                        Text("JC ${collection.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text(collection.collectionId,
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        if (collection.productName.isNotBlank()) {
                            Text(collection.productName,
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
```

- [ ] **Step 3: Restyle "Ready mixes"**

Replace:

```kotlin
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val selected =
                        (board.selection as? BoardSelection.Mix)?.mixBatchId == mix.mixBatchId
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy) { viewModel.selectMix(mix.mixBatchId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${mix.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            val mixSecondary = secondaryLine(
                                mix.mixBatchId,
                                mix.collectionId,
                                mix.sourceMixerCode.takeIf { it.isNotBlank() }?.let { "from $it" },
                            )
                            if (mixSecondary.isNotBlank()) {
                                Text(mixSecondary,
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            // Destinations render ONLY from validNextMachineCodes (§13.8).
                            Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SuccessGreen)
                        }
                    }
                }
```

with:

```kotlin
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val selected =
                        (board.selection as? BoardSelection.Mix)?.mixBatchId == mix.mixBatchId
                    StatusCard(
                        tone = if (selected) StatusTone.Running else StatusTone.Idle,
                        onClick = { viewModel.selectMix(mix.mixBatchId) },
                        enabled = !board.busy,
                    ) {
                        Text("JC ${mix.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        val mixSecondary = secondaryLine(
                            mix.mixBatchId,
                            mix.collectionId,
                            mix.sourceMixerCode.takeIf { it.isNotBlank() }?.let { "from $it" },
                        )
                        if (mixSecondary.isNotBlank()) {
                            Text(mixSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        // Destinations render ONLY from validNextMachineCodes (§13.8).
                        Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen)
                    }
                }
```

(`SuccessGreen` stays here — it labels the destination line regardless of card tone, a fixed
semantic like the checklist's satisfied checkmark in Phase 3, not a tone-derived color.)

- [ ] **Step 4: Restyle the JANDI drum card**

Replace:

```kotlin
            board.overview.jandiDrum?.let { drum ->
                item { SectionHeader("JANDI drum") }
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            // An idle drum has no batch on it, so there is no JC to lead with —
                            // naming the drum itself beats printing an empty JC line.
                            Text(
                                drum.jobCardNumber?.let { "JC $it" } ?: "JANDI Transfer Drum",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            val drumSecondary = secondaryLine(drum.mixBatchId, drum.collectionId)
                            if (drumSecondary.isNotBlank()) {
                                Text(drumSecondary,
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            Text(drum.status,
                                style = MaterialTheme.typography.bodyMedium, color = WarningOrange)
                            if (drum.scanGuidance.isNotBlank()) {
                                Text(drum.scanGuidance,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                    }
                }
            }
```

with:

```kotlin
            board.overview.jandiDrum?.let { drum ->
                item { SectionHeader("JANDI drum") }
                item {
                    // The drum's exact status vocabulary isn't fully enumerated in this codebase
                    // today (it's rendered verbatim, never matched against known values) — a
                    // single fixed Warning tone matches its current always-amber presentation
                    // without guessing at status strings this task can't verify.
                    StatusCard(tone = StatusTone.Warning) { accent ->
                        // An idle drum has no batch on it, so there is no JC to lead with —
                        // naming the drum itself beats printing an empty JC line.
                        Text(
                            drum.jobCardNumber?.let { "JC $it" } ?: "JANDI Transfer Drum",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        val drumSecondary = secondaryLine(drum.mixBatchId, drum.collectionId)
                        if (drumSecondary.isNotBlank()) {
                            Text(drumSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        Text(drum.status,
                            style = MaterialTheme.typography.bodyMedium, color = accent)
                        if (drum.scanGuidance.isNotBlank()) {
                            Text(drum.scanGuidance,
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }
```

- [ ] **Step 5: Restyle "Active cycles"**

Replace:

```kotlin
                items(board.overview.activeCycles, key = { it.cycleId }) { cycle ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy && board.selection is BoardSelection.None) {
                                viewModel.machineChosen(cycle.machineCode)
                            },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("JC ${cycle.jobCardNumber}",
                                style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                            Text("${cycle.cycleId} on ${cycle.machineCode}",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(
                                "Started " + (formatElapsedSince(cycle.startedAtUtc)
                                    ?: formatStationTimestamp(cycle.startedAtUtc)),
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
```

with:

```kotlin
                items(board.overview.activeCycles, key = { it.cycleId }) { cycle ->
                    StatusCard(
                        onClick = { viewModel.machineChosen(cycle.machineCode) },
                        enabled = !board.busy && board.selection is BoardSelection.None,
                    ) {
                        Text("JC ${cycle.jobCardNumber}",
                            style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text("${cycle.cycleId} on ${cycle.machineCode}",
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text(
                            "Started " + (formatElapsedSince(cycle.startedAtUtc)
                                ?: formatStationTimestamp(cycle.startedAtUtc)),
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
```

(`tone` omitted — defaults to `StatusTone.Idle`, matching the card's current neutral appearance;
there's no per-cycle status signal to derive a richer tone from.)

- [ ] **Step 6: Restyle "Active runs"**

Replace:

```kotlin
                items(board.overview.activeRuns, key = { it.productionRunId }) { run ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(run.machineCode,
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            val runSecondary = secondaryLine(run.productionRunId, run.status)
                            if (runSecondary.isNotBlank()) {
                                Text(runSecondary,
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            // A JANDI 4 or Rajoo run carries several inputs whose job cards may
                            // differ. Listing them is the only way that is visible to an operator.
                            run.inputs.forEach { input ->
                                // "JC …" is the leading identifier (never conditionally hidden,
                                // matching every other card's JC line); only the traits after it
                                // — layer and role — are optional and joined so a blank one
                                // can't leave a dangling " · " behind.
                                val inputTraits = secondaryLine(
                                    input.productLayer?.let { "layer $it" },
                                    input.inputRole,
                                )
                                Text(
                                    "JC ${input.jobCardNumber}" +
                                        (inputTraits.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(input.mixBatchId,
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
```

with:

```kotlin
                items(board.overview.activeRuns, key = { it.productionRunId }) { run ->
                    StatusCard {
                        Text(run.machineCode,
                            style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        val runSecondary = secondaryLine(run.productionRunId, run.status)
                        if (runSecondary.isNotBlank()) {
                            Text(runSecondary,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        // A JANDI 4 or Rajoo run carries several inputs whose job cards may
                        // differ. Listing them is the only way that is visible to an operator.
                        run.inputs.forEach { input ->
                            // "JC …" is the leading identifier (never conditionally hidden,
                            // matching every other card's JC line); only the traits after it
                            // — layer and role — are optional and joined so a blank one
                            // can't leave a dangling " · " behind.
                            val inputTraits = secondaryLine(
                                input.productLayer?.let { "layer $it" },
                                input.inputRole,
                            )
                            Text(
                                "JC ${input.jobCardNumber}" +
                                    (inputTraits.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text(input.mixBatchId,
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
```

(No `onClick` — this card was never clickable, and stays that way.)

- [ ] **Step 7: Verify the build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. `Card`, `CardDefaults`, `BorderStroke`, `GraphiteBorder`, `GraphiteSurface`
are still used by `MachineCard` (untouched until Task 4) and possibly by `CycleSheetDialog`/
`ForceCloseDialog` (not inspected in this plan) — do **not** remove any of these imports in this
task even if they look unused from what this task touched; only remove an import the build actually
flags as unused after this task's specific edits, and only if grep confirms no other function in the
file still references it.

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — no ViewModel logic touched.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt
git commit -m "feat(mixing-board): restyle collection, mix, drum, cycle, and run cards with StatusCard"
```

---

### Task 4: Restyle `MachineCard` using `highlighted`

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreenKtTest.kt`

**Interfaces:**
- Consumes: `StatusCard`'s `highlighted` parameter (Task 1).
- Produces: `internal fun machineStatusTone(status: String): StatusTone`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class MixingBoardScreenKtTest {

    @Test
    fun `Available maps to Ready`() {
        assertEquals(StatusTone.Ready, machineStatusTone("Available"))
    }

    @Test
    fun `InUse maps to Warning`() {
        assertEquals(StatusTone.Warning, machineStatusTone("InUse"))
    }

    @Test
    fun `any other status maps to Danger`() {
        assertEquals(StatusTone.Danger, machineStatusTone("Disabled"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreenKtTest"`
Expected: FAIL — `machineStatusTone()` does not exist yet (compile error).

- [ ] **Step 3: Add `machineStatusTone()` and restyle `MachineCard`**

Replace:

```kotlin
@Composable
private fun MachineCard(
    machine: Equipment,
    highlighted: Boolean,
    hasCycle: Boolean,
    noSelection: Boolean,
    busy: Boolean,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (machine.status) {
        "Available" -> SuccessGreen
        "InUse" -> WarningOrange
        else -> DangerRed
    }
    // Taps work on highlighted machines (start) or, with no selection, on busy
    // machines (cycle sheet). A SCAN reaches any machine via the ViewModel.
    val clickable = !busy && (highlighted || (noSelection && hasCycle))
    Card(
        modifier = modifier.clickable(enabled = clickable) { onChosen(machine.machineCode) },
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp,
            if (highlighted) AmberPrimary else GraphiteBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(machine.displayName, style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary, maxLines = 1)
            Text(machine.machineCode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            // Rendered verbatim from areaStatus.equipment — never inferred locally (§13.7).
            Text(machine.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
    }
}
```

with:

```kotlin
/** Rendered verbatim from areaStatus.equipment — never inferred locally (§13.7). */
internal fun machineStatusTone(status: String): StatusTone = when (status) {
    "Available" -> StatusTone.Ready
    "InUse" -> StatusTone.Warning
    else -> StatusTone.Danger
}

@Composable
private fun MachineCard(
    machine: Equipment,
    highlighted: Boolean,
    hasCycle: Boolean,
    noSelection: Boolean,
    busy: Boolean,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Taps work on highlighted machines (start) or, with no selection, on busy
    // machines (cycle sheet). A SCAN reaches any machine via the ViewModel.
    val clickable = !busy && (highlighted || (noSelection && hasCycle))
    StatusCard(
        tone = machineStatusTone(machine.status),
        highlighted = highlighted,
        onClick = { onChosen(machine.machineCode) },
        enabled = clickable,
        modifier = modifier,
    ) { accent ->
        Text(machine.displayName, style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary, maxLines = 1)
        Text(machine.machineCode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(4.dp))
        Text(machine.status, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreenKtTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Verify the full build and clean up now-unused imports**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. With Tasks 3 and 4 both done, check whether `Card`, `CardDefaults`,
`BorderStroke`, `GraphiteBorder`, `GraphiteSurface`, `SuccessGreen`, `WarningOrange`, `DangerRed` are
still referenced anywhere in the file (`CycleSheetDialog`/`ForceCloseDialog`, not touched by this
plan, may still use some of them) — remove only the ones the build actually flags as unused, and
confirm with a grep of the file, not by assumption.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Manual verification (if a device/emulator is available)**

Same accepted limitation as Phases 1–3 if none is available. If available: open each of the five
areas (Dolci, Main Mixing Room, Jandi, Mackie, Rajoo) and confirm (1) the area picker cards show
green/amber/gray appropriately, (2) equipment tiles show green for Available, amber for InUse, red
for anything else, (3) a highlighted (scannable) tile still shows the amber emphasis border
distinctly from its status color, (4) tapping a collection/mix card still selects it and opens the
right machine tab, (5) the JANDI drum card and Rajoo dose dialog still work exactly as before, (6)
the Jandi route picker still requires a manual selection before Start.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreen.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/board/MixingBoardScreenKtTest.kt
git commit -m "feat(mixing-board): restyle equipment tiles with StatusCard, separate highlight from status"
```

---

## Self-Review Notes

- **Spec coverage:** §5.5 (area picker color-coding) and §5.6 (equipment/board card restyle) are
  covered by Tasks 2–4. The Scope note documents, with evidence, why §5.6's original "five distinct
  area layouts" framing doesn't match the real (already-generic, already-correct) architecture, and
  why the Jandi route picker and Rajoo/route dialog styling need no work — both are deliberate,
  evidenced scope corrections, not gaps.
- **Placeholder scan:** no TBD/TODO; every step has real, complete code. Import-cleanup steps
  explicitly say "verify before removing" rather than asserting an import is unused without having
  read every consumer in the file (`CycleSheetDialog`/`ForceCloseDialog` are out of this plan's
  read-verified scope).
- **Type consistency:** `StatusCard`'s `highlighted: Boolean = false` (Task 1) is consumed by
  `MachineCard` (Task 4) with the same name and type. `areaTone(mixes, cycles): StatusTone` (Task 2)
  and `machineStatusTone(status): StatusTone` (Task 4) both return exactly the enum `StatusCard`'s
  `tone` parameter accepts.
- **Risk flagged for the implementer:** Task 3 and Task 4 both edit `MixingBoardScreen.kt` and both
  touch import-cleanup at their own tail end — Task 3 must not preemptively remove imports Task 4's
  `MachineCard` (not yet migrated when Task 3 runs) still needs. The order (3 before 4) and each
  step's explicit "don't remove without verifying" instruction exists specifically to prevent that.
