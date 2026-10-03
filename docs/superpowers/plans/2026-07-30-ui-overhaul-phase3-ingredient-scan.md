# UI Overhaul Phase 3: Ingredient Scan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the Ingredient Scan screen — the app's single biggest friction point — around a
"scan this next" guided card, move admin-only actions behind an overflow menu, and restyle the BOM
checklist with the shared `StatusCard` component. Per
`docs/superpowers/specs/2026-07-30-android-ui-ux-overhaul-design.md` §5.4.

**Architecture:** `AppScaffold` gains one new optional slot (`actions`) so screens can add their own
top-bar icon/menu without every screen needing bespoke chrome — Ingredient Scan is its first
consumer, moving the existing "Cancel Job" trigger there. The screen itself gets a small
LaunchedEffect that auto-arms the first unsatisfied line (calling the existing, unchanged
`viewModel.selectLine()`), a new hero card summarizing whatever line is currently armed, and its
BOM checklist rows swap their hand-rolled `Card` chrome for `StatusCard` (from Phase 2), with the
tone computed by a new pure `BomLine.checklistTone()` function.

**Tech Stack:** Kotlin, Jetpack Compose, Material3. Tests: JUnit4 + plain assertions for the one new
pure function; everything else is presentation-only and verified by compilation, matching Phases 1–2.

## Global Constraints

- No MQTT contract, domain model, use case, or `MixingViewModel` changes. Every dialog
  (`showCancelDialog`, `EnteringBagDetails`, `EnteringQuantityDetails`,
  `IngredientExceptionApproval`, `ShortBagWaiverEntry`, `ShortBagWaiverNeedsApproval`,
  `PalletRecoveryPrompt`) keeps its exact current trigger condition, fields, and submit logic —
  only where its trigger *button* lives on screen changes (Task 2), never its behavior.
- `viewModel.selectLine(lineNumber: Int)` already supports arming any line, not just "the next
  one" — confirmed in `MixingViewModel.kt:461`. Nothing about that function changes; this phase
  only changes when it gets called automatically (once, for the first unsatisfied line) versus
  only on an explicit tap.
- `AppScaffold`'s new `actions` slot must be optional (`= null`) and backward compatible — every
  other screen's current call site must compile and render identically without passing it.
- Follow existing code conventions exactly: named Compose modifier parameters, 4-space indentation,
  existing import ordering.

## Scope note (found during investigation, not verbatim in the spec)

1. **Prior "DialogFormColumn used inconsistently" flag is stale.** A project memory note raised
   this as a known issue; investigation found `IngredientScanScreen.kt`'s dialogs already use
   `DialogFormColumn` consistently everywhere a dialog has input fields. No standardization work is
   needed in this phase.

2. **"Admin actions... move behind an overflow menu" (§5.4) applies to exactly one action, not
   three.** The design spec's original framing (from an early interactive prototype, before this
   screen's real code was read) described cancel/waiver/exception-approval as three menu items.
   Investigation of the real screen found:
   - **Cancel Job** is genuinely screen-level and admin-gated (`mayCancelCollection`) — this is the
     one action that moves to the overflow menu.
   - **Exception approval** (`MixingUiState.IngredientExceptionApproval`) has no trigger button at
     all — the ViewModel enters this state reactively after a scan needs supervisor override.
     There's nothing to move; the dialog already just appears when needed.
   - **Short-bag waiver** is tied to one *specific* material (`openShortBagWaiver(bomLine.itemCode)`),
     triggered by a button living inside that line's own checklist row. Moving it to a screen-level
     menu would force the operator to pick a material from a list first — worse, not better. It
     stays exactly where it is, inside the restyled `StatusCard` row (Task 4).

3. **The "Start Mixing" button already exists and is always visible (disabled until ready), not
   hidden-until-ready as an early reading of §5.4 might suggest.** Hiding a button an operator has
   learned to expect removes a discoverability cue for no real benefit — it stays visible always,
   same as today, just made full-width and green when enabled (Task 2).

---

### Task 1: `AppScaffold` — add an `actions` slot

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`

**Interfaces:**
- Produces: `AppScaffold(..., actions: (@Composable RowScope.() -> Unit)? = null, ...)` — a new
  optional trailing parameter, consumed by Task 2.

No test: this is a backward-compatible, purely additive parameter with a null-check render, no
branching logic worth a unit test (consistent with Phase 1's precedent for pure layout additions).

- [ ] **Step 1: Add the parameter**

In `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`, change the function
signature from:

```kotlin
fun AppScaffold(
    title: String,
    status: ConnectionStatus,
    onBack: (() -> Unit)? = null,
    onRfidLookup: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    operatorName: String? = null,
    operatorRole: String? = null,
    onLogout: (() -> Unit)? = null,
    loading: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
```

to:

```kotlin
fun AppScaffold(
    title: String,
    status: ConnectionStatus,
    onBack: (() -> Unit)? = null,
    onRfidLookup: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    /** A screen-specific top-bar action (e.g. an overflow menu) rendered next to Settings/RFID. */
    actions: (@Composable RowScope.() -> Unit)? = null,
    operatorName: String? = null,
    operatorRole: String? = null,
    onLogout: (() -> Unit)? = null,
    loading: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
```

- [ ] **Step 2: Render it in the two-row (operator-present) layout**

Find this block (inside the `if (operatorName != null)` branch's `Row`):

```kotlin
                        if (onSettings != null) {
                            IconButton(onClick = onSettings) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = TextMuted
                                )
                            }
                        }
                        statusPill()
                    }
                    Text(
                        text = title,
```

and change it to:

```kotlin
                        if (onSettings != null) {
                            IconButton(onClick = onSettings) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = TextMuted
                                )
                            }
                        }
                        actions?.invoke(this)
                        statusPill()
                    }
                    Text(
                        text = title,
```

- [ ] **Step 3: Render it in the single-row (no-operator) `TopAppBar` layout**

Find this block:

```kotlin
                            if (onSettings != null) {
                                IconButton(onClick = onSettings) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = TextMuted
                                    )
                                }
                            }
                            statusPill()
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
```

and change it to:

```kotlin
                            if (onSettings != null) {
                                IconButton(onClick = onSettings) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = TextMuted
                                    )
                                }
                            }
                            actions?.invoke(this)
                            statusPill()
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
```

- [ ] **Step 4: Verify it compiles and every existing call site is unaffected**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `actions` defaults to `null` everywhere it isn't explicitly passed, so
every existing `AppScaffold(...)` call site (Login, Home, Job Cards, Settings, RFID Recovery,
Mixing Board, Mixing Area Picker) renders identically — nothing else in this step should change.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt
git commit -m "feat(ui): add optional actions slot to AppScaffold"
```

---

### Task 2: Overflow menu for Cancel Job, sticky Start Mixing button

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: `AppScaffold`'s `actions` slot from Task 1 (exact signature above).

- [ ] **Step 1: Add the overflow-menu state and the `MoreVert` import**

In the imports block, add:

```kotlin
import androidx.compose.material.icons.filled.MoreVert
```

Near the other `rememberSaveable`/`remember` declarations at the top of the composable (right
after the existing `var showBackConfirmDialog by rememberSaveable { mutableStateOf(false) }`
line), add:

```kotlin
    var showOverflowMenu by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Wire the overflow menu into `AppScaffold` and remove the old inline Cancel button**

Replace:

```kotlin
    AppScaffold(
        title = "Scan Ingredients",
        status = connectionStatus,
        onBack = { showBackConfirmDialog = true },
        onRfidLookup = onRfidLookup,
        loading = uiState is MixingUiState.Loading
    ) { padding ->
```

with:

```kotlin
    AppScaffold(
        title = "Scan Ingredients",
        status = connectionStatus,
        onBack = { showBackConfirmDialog = true },
        onRfidLookup = onRfidLookup,
        actions = if (mayCancelCollection) {
            {
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More actions",
                            tint = TextMuted
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cancel Job", color = DangerRed) },
                            onClick = {
                                showOverflowMenu = false
                                showCancelDialog = true
                            }
                        )
                    }
                }
            }
        } else null,
        loading = uiState is MixingUiState.Loading
    ) { padding ->
```

Then find the bottom button row:

```kotlin
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancelling a collection is an Admin privilege — an Operator's
                    // allowedActions does not include ingredient_collection_cancel, so the
                    // button is simply absent rather than a dead end they discover after a
                    // credential prompt and a round trip.
                    if (mayCancelCollection) {
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                    Button(
                        onClick = {
                            (uiState as? MixingUiState.OrderLoaded)
                                ?.order?.collectionId?.takeIf { it.isNotBlank() }
                                ?.let(onStartMixing)
                        },
                        enabled = readyForMixing,
                        modifier = Modifier.weight(2f).height(56.dp)
                    ) {
                        Text(if (readyForMixing) "Start Mixing" else "Mixing after collection")
                    }
                }
```

and replace it with:

```kotlin
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        (uiState as? MixingUiState.OrderLoaded)
                            ?.order?.collectionId?.takeIf { it.isNotBlank() }
                            ?.let(onStartMixing)
                    },
                    enabled = readyForMixing,
                    colors = if (readyForMixing) {
                        ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = GraphiteBackground)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (readyForMixing) "Start Mixing →" else "Mixing after collection")
                }
```

Cancelling now reaches the exact same `showCancelDialog = true` state and the exact same dialog
defined earlier in the file — only its trigger moved. `mayCancelCollection`'s gating logic is
unchanged (the overflow icon itself doesn't render at all for an Operator, same as the old button).

- [ ] **Step 3: Verify it builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — this task changes no ViewModel logic, so nothing in `MixingViewModelTest` should
be affected.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(ingredient-scan): move Cancel Job to an overflow menu, make Start Mixing sticky"
```

---

### Task 3: "Scan this next" guided card + auto-arm

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`

**Interfaces:**
- Consumes: the existing, unchanged `viewModel.selectLine(lineNumber: Int)`
  (`MixingViewModel.kt:461`) and `MixingUiState.OrderLoaded.selectedLineNumber: Int?`
  (`MixingViewModel.kt:53`).

- [ ] **Step 1: Add the auto-arm effect and the hero card**

Find:

```kotlin
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isSatisfied }
                        val allSatisfied = satisfiedCount == order.lines.size

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (allSatisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allSatisfied) SuccessGreen.copy(alpha = 0.35f) else GraphiteBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                order.productBeingMade?.let { productName ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(productName, style = MaterialTheme.typography.bodyMedium, color = AmberPrimary)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$satisfiedCount of ${order.lines.size} lines satisfied",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (allSatisfied) SuccessGreen else TextMuted
                                )
                                if (order.summary.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        order.summary,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                }
                                if (!allSatisfied && state.selectedLineNumber == null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Tap a line below to arm it before scanning a pallet.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberPrimary
                                    )
                                }
                            }
                        }
```

and replace it with:

```kotlin
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isSatisfied }
                        val allSatisfied = satisfiedCount == order.lines.size

                        // Auto-arm the first unsatisfied line so operators can scan immediately
                        // without an extra tap for the common case. Tapping a different line in
                        // the checklist below (Task 4) still re-arms it — operators grab whatever
                        // pallet is physically nearby first, not strictly in BOM order, so jumping
                        // the queue must stay first-class, not a fallback.
                        LaunchedEffect(state.selectedLineNumber, order.lines) {
                            if (state.selectedLineNumber == null && !state.isBusy) {
                                order.lines.firstOrNull { !it.isSatisfied }
                                    ?.let { viewModel.selectLine(it.lineNumber) }
                            }
                        }

                        val nextLine = order.lines.firstOrNull { it.lineNumber == state.selectedLineNumber }
                        if (!allSatisfied && nextLine != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.10f)),
                                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "SCAN THIS NEXT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SuccessGreen
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        nextLine.itemName.ifBlank { nextLine.itemCode },
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = TextPrimary
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "%.2f %s needed".format(nextLine.remainingQty, nextLine.uom),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (allSatisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allSatisfied) SuccessGreen.copy(alpha = 0.35f) else GraphiteBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                order.productBeingMade?.let { productName ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(productName, style = MaterialTheme.typography.bodyMedium, color = AmberPrimary)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$satisfiedCount of ${order.lines.size} lines satisfied",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (allSatisfied) SuccessGreen else TextMuted
                                )
                                if (order.summary.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        order.summary,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
```

The only content removed is the now-redundant "Tap a line below to arm it before scanning a
pallet." hint — superseded by the new hero card, since a line is now always pre-armed. Everything
else in the original order-summary card is unchanged, just appearing after the new hero card
instead of alone.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. No ViewModel logic changed — `selectLine` is called exactly as it always could be
called, just from a new call site.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt
git commit -m "feat(ingredient-scan): add scan-this-next guided card, auto-arm first unsatisfied line"
```

---

### Task 4: Restyle the BOM checklist with `StatusCard`

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreenKtTest.kt`

**Interfaces:**
- Consumes: `StatusCard`/`StatusTone` (Phase 2, `com.mitas.ppnam.station2aa.ui.components`) and `BomLine`
  (`com.mitas.ppnam.station2aa.domain.model.BomLine:27` — `isSatisfied: Boolean` is a *computed*
  property, not a stored field: `get() = isFullyAllocated && (!isBagged || (remainingBags ?: 0.0) <= 0.0)`).
- Produces: `internal fun BomLine.checklistTone(armed: Boolean, pending: Boolean): StatusTone`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class IngredientScanScreenKtTest {

    // remainingQty > 0.0 and no bagSize (bulk line) => isFullyAllocated false => isSatisfied false.
    // remainingQty == 0.0 => isFullyAllocated true, isBagged false => isSatisfied true.
    private fun bomLine(remainingQty: Double) = BomLine(
        lineNumber = 1,
        itemCode = "MAT-1",
        itemName = "Resin",
        requiredQty = 10.0,
        remainingQty = remainingQty,
    )

    @Test
    fun `pending line maps to Running regardless of armed or satisfied`() {
        assertEquals(StatusTone.Running, bomLine(remainingQty = 5.0).checklistTone(armed = false, pending = true))
    }

    @Test
    fun `satisfied unarmed line maps to Ready`() {
        assertEquals(StatusTone.Ready, bomLine(remainingQty = 0.0).checklistTone(armed = false, pending = false))
    }

    @Test
    fun `satisfied line stays Ready even if armed`() {
        assertEquals(StatusTone.Ready, bomLine(remainingQty = 0.0).checklistTone(armed = true, pending = false))
    }

    @Test
    fun `armed unsatisfied line maps to Running`() {
        assertEquals(StatusTone.Running, bomLine(remainingQty = 5.0).checklistTone(armed = true, pending = false))
    }

    @Test
    fun `idle unsatisfied unarmed line maps to Idle`() {
        assertEquals(StatusTone.Idle, bomLine(remainingQty = 5.0).checklistTone(armed = false, pending = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreenKtTest"`
Expected: FAIL — `checklistTone()` does not exist yet (compile error).

- [ ] **Step 3: Add imports and the `checklistTone()` function**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt`, add to the imports:

```kotlin
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
```

Add this function near the top of the file, after the imports and before `IngredientScanScreen`:

```kotlin
/**
 * Maps a BOM line's satisfied/armed/pending state to the shared color language. Pending (a
 * request in flight for this specific line) takes priority — an operator watching the list should
 * see "this one's working" over any other signal. `internal`, not `private`, so
 * `IngredientScanScreenKtTest` can verify it directly.
 */
internal fun BomLine.checklistTone(armed: Boolean, pending: Boolean): StatusTone = when {
    pending -> StatusTone.Running
    isSatisfied -> StatusTone.Ready
    armed -> StatusTone.Running
    else -> StatusTone.Idle
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreenKtTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Swap the checklist row's `Card` for `StatusCard`**

Find this complete block (the per-line row inside the `LazyColumn`'s `items(...)` — this is the
entire row, opening through closing brace, reproduced in full so the edit is a single unambiguous
replacement rather than a partial one requiring separate brace-count adjustment):

```kotlin
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // Arming during an in-flight request would leave the
                                        // operator unsure which line the response applies to.
                                        .clickable(enabled = !state.isBusy) { viewModel.selectLine(bomLine.lineNumber) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            satisfied -> SuccessGreen.copy(alpha = 0.10f)
                                            armed -> AmberPrimary.copy(alpha = 0.10f)
                                            else -> GraphiteSurface
                                        }
                                    ),
                                    border = BorderStroke(
                                        if (armed || pending) 2.dp else 1.dp,
                                        when {
                                            pending -> AmberPrimary
                                            satisfied -> SuccessGreen.copy(alpha = 0.30f)
                                            armed -> AmberPrimary
                                            else -> GraphiteBorder
                                        }
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "Line ${bomLine.lineNumber}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextMuted
                                                    )
                                                    if (armed) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = "ARMED",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = AmberPrimary
                                                        )
                                                    }
                                                }
                                                // maxLines + ellipsis: an unconstrained name
                                                // ("MASTERBATCH BLACK ME 9200 ME") wrapped under
                                                // the right-aligned kg value and the two overlapped.
                                                Text(
                                                    text = displayName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = TextPrimary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            if (pending) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = AmberPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            } else if (satisfied) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Satisfied",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            // The value column gets its own floor so the name can
                                            // never squeeze it to nothing, and stays right-aligned.
                                            Text(
                                                text = if (bomLine.isSatisfied) {
                                                    "Fully Allocated"
                                                } else {
                                                    "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted,
                                                textAlign = TextAlign.End,
                                                maxLines = 2,
                                                modifier = Modifier.widthIn(min = 72.dp)
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = buildString {
                                                append("Available: %.2f %s".format(bomLine.availableQty, bomLine.uom))
                                                if (bomLine.isBagged) {
                                                    append(" · Bag size: ${bomLine.bagSize}")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        if (!bomLine.isSatisfied) {
                                            Spacer(Modifier.height(8.dp))
                                            // Captioned: a bagged line renders two visually
                                            // identical bars (weight, then bags) and neither said
                                            // which was which.
                                            Text(
                                                text = "Weight  %.2f / %.2f %s".format(
                                                    bomLine.collectedQty, bomLine.requiredQty, bomLine.uom
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { fraction },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (satisfied) SuccessGreen else AmberPrimary,
                                                trackColor = GraphiteBorder
                                            )
                                        }
                                        // Every element below is gated on isBagged: a bulk line has no
                                        // bag arithmetic (its bag fields are null, not zero) and must
                                        // never render bag figures or be treated as bag-incomplete.
                                        if (bomLine.isBagged) {
                                            val expectedBags = bomLine.expectedBags ?: 0.0
                                            val scannedBags = bomLine.scannedBags ?: 0.0
                                            val bagFraction = if (expectedBags > 0.0) {
                                                (scannedBags / expectedBags).toFloat().coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = "Bags  %.2f / %.2f full bags".format(scannedBags, expectedBags),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { bagFraction },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (satisfied) SuccessGreen else AmberPrimary,
                                                trackColor = GraphiteBorder
                                            )
                                            // Waiving short bags is an Admin privilege the
                                            // Operator's allowedActions does not carry. Offering it
                                            // regardless meant discovering that only after a
                                            // multi-second round trip and a generic rejection.
                                            if (!satisfied && mayWaiveShortBags) {
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    TextButton(
                                                        enabled = !state.isBusy,
                                                        onClick = { viewModel.openShortBagWaiver(bomLine.itemCode) }
                                                    ) { Text("Short bags", color = AmberPrimary) }
                                                }
                                            }
                                        }
                                    }
                                }
```

with:

```kotlin
                                StatusCard(
                                    tone = bomLine.checklistTone(armed = armed, pending = pending),
                                    // Arming during an in-flight request would leave the
                                    // operator unsure which line the response applies to.
                                    onClick = { viewModel.selectLine(bomLine.lineNumber) },
                                    enabled = !state.isBusy,
                                ) { accent ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "Line ${bomLine.lineNumber}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                if (armed) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        text = "ARMED",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = accent
                                                    )
                                                }
                                            }
                                            // maxLines + ellipsis: an unconstrained name
                                            // ("MASTERBATCH BLACK ME 9200 ME") wrapped under
                                            // the right-aligned kg value and the two overlapped.
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (pending) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = AmberPrimary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        } else if (satisfied) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Satisfied",
                                                tint = SuccessGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        // The value column gets its own floor so the name can
                                        // never squeeze it to nothing, and stays right-aligned.
                                        Text(
                                            text = if (bomLine.isSatisfied) {
                                                "Fully Allocated"
                                            } else {
                                                "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (satisfied) SuccessGreen else TextMuted,
                                            textAlign = TextAlign.End,
                                            maxLines = 2,
                                            modifier = Modifier.widthIn(min = 72.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = buildString {
                                            append("Available: %.2f %s".format(bomLine.availableQty, bomLine.uom))
                                            if (bomLine.isBagged) {
                                                append(" · Bag size: ${bomLine.bagSize}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    if (!bomLine.isSatisfied) {
                                        Spacer(Modifier.height(8.dp))
                                        // Captioned: a bagged line renders two visually
                                        // identical bars (weight, then bags) and neither said
                                        // which was which.
                                        Text(
                                            text = "Weight  %.2f / %.2f %s".format(
                                                bomLine.collectedQty, bomLine.requiredQty, bomLine.uom
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (satisfied) SuccessGreen else AmberPrimary,
                                            trackColor = GraphiteBorder
                                        )
                                    }
                                    // Every element below is gated on isBagged: a bulk line has no
                                    // bag arithmetic (its bag fields are null, not zero) and must
                                    // never render bag figures or be treated as bag-incomplete.
                                    if (bomLine.isBagged) {
                                        val expectedBags = bomLine.expectedBags ?: 0.0
                                        val scannedBags = bomLine.scannedBags ?: 0.0
                                        val bagFraction = if (expectedBags > 0.0) {
                                            (scannedBags / expectedBags).toFloat().coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "Bags  %.2f / %.2f full bags".format(scannedBags, expectedBags),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { bagFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (satisfied) SuccessGreen else AmberPrimary,
                                            trackColor = GraphiteBorder
                                        )
                                        // Waiving short bags is an Admin privilege the
                                        // Operator's allowedActions does not carry. Offering it
                                        // regardless meant discovering that only after a
                                        // multi-second round trip and a generic rejection.
                                        if (!satisfied && mayWaiveShortBags) {
                                            Spacer(Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    enabled = !state.isBusy,
                                                    onClick = { viewModel.openShortBagWaiver(bomLine.itemCode) }
                                                ) { Text("Short bags", color = AmberPrimary) }
                                            }
                                        }
                                    }
                                }
```

The only functional changes anywhere in this row: the outer `Card` (with its hand-computed
`containerColor`/`border` `when` blocks) becomes `StatusCard` driven by `checklistTone()`; the
`Column(modifier = Modifier.padding(12.dp))` wrapper is gone (`StatusCard` already provides its own
padded `Column`, one indent level shallower — every line inside is de-indented by 4 spaces
accordingly, shown above); and the "ARMED" label's color changes from hardcoded `AmberPrimary` to
the tone-derived `accent`. Every other piece of content — progress bars, the satisfied checkmark
icon, the "Available:"/bag text, the "Short bags" button, all their existing colors and logic — is
byte-for-byte identical, just at one less indent level.

- [ ] **Step 6: Verify it builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If brace-matching in Step 5 is off, this is where it surfaces — resolve
by reading the full method and matching each opening brace to its close, not by guessing.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, including the 5 new `IngredientScanScreenKtTest` cases.

- [ ] **Step 8: Manual verification (if a device/emulator is available)**

Same accepted limitation as Phases 1–2 if none is available — report and move on, don't block.
If available: open Ingredient Scan for an order with multiple lines and confirm (1) the "SCAN THIS
NEXT" card shows the first unsatisfied line without any tap, (2) tapping a different, later line
re-arms it and the hero card... note the hero card does NOT update to follow a manual tap unless
`state.selectedLineNumber` changes, which it does via `selectLine()` — confirm the hero card's
`nextLine` does follow the newly armed line, (3) a satisfied line's checklist row is green, an
armed one is accent-bordered, (4) the "⋮" menu appears only for admin sessions and Cancel Job still
works, (5) "Start Mixing →" is full-width and turns green once every line is satisfied.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreen.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/IngredientScanScreenKtTest.kt
git commit -m "feat(ingredient-scan): restyle BOM checklist rows with StatusCard"
```

---

## Self-Review Notes

- **Spec coverage:** §5.4's four bullets are covered: guided "scan this next" card (Task 3), every
  line stays tappable including out-of-order (unchanged — `selectLine` never restricted this),
  admin actions demoted from primary visual weight (Task 2, scoped correctly per the Scope note),
  sticky Start Mixing (Task 2).
- **Placeholder scan:** no TBD/TODO; every step has real, complete code, including the
  brace-matching note in Task 4 Step 5 where a mechanical edit needs care rather than a vague
  "update accordingly."
- **Type consistency:** `checklistTone(armed: Boolean, pending: Boolean): StatusTone` (Task 4)
  returns exactly what `StatusCard`'s `tone` parameter (Phase 2) accepts. `AppScaffold`'s `actions`
  parameter type (Task 1) matches exactly how Task 2's `IngredientScanScreen` call site assigns it
  (a `{ ... }` lambda block, optionally `null`).
- **Risk called out explicitly:** Task 4 Step 5's brace-count instruction is the one place in this
  plan where a purely mechanical "replace this text with that text" isn't quite sufficient on its
  own, because the edit spans a very large row body without repeating all ~140 unchanged lines
  verbatim — Step 6's build verification is the safety net, and the implementer is told exactly what
  will surface if the edit is wrong (a brace mismatch) rather than left to discover it cold.
