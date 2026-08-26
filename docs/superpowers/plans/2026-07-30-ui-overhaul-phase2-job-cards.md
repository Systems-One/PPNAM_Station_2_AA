# UI Overhaul Phase 2: Job Cards — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the active-jobs list on `JobLookupScreen` (the screen behind Home's "Job Cards"
tile) as color-coded status cards, and rename its displayed title from "Job Lookup" to "Job Cards" —
per `docs/superpowers/specs/2026-07-30-android-ui-ux-overhaul-design.md` §4.2 and §5.3.

**Architecture:** A new shared `StatusCard` component (`ui/components/StatusCard.kt`) — a
tone-tinted card shell with caller-supplied content — becomes the first real consumer of the color
system described in the design spec's §3.4 (deferred from Phase 1, which had no consumer for it
yet). `JobLookupScreen.kt`'s active-jobs `LazyColumn` rows switch from a plain bordered `Card` with
a hardcoded-blue status line to `StatusCard` with a tone computed from each job's real status.

**Tech Stack:** Kotlin, Jetpack Compose, Material3. Tests: JUnit4 + plain assertions (no
mocking needed — both new pieces of logic are pure functions), matching the project's existing
`app/src/test` conventions.

## Global Constraints

- No MQTT contract, domain model, use case, or ViewModel changes. `MixingViewModel`'s
  `activeJobs`/`lookupJob`/`loadActiveJobs` logic is untouched — this phase only changes how
  `JobLookupScreen.kt` renders the data it already receives.
- Only `JobLookupScreen.kt` and the new `StatusCard.kt` change in this phase. Home, Settings, RFID
  Recovery, Ingredient Scan, Mixing Board, Mixing Area Picker, and Login are not touched.
- The manual order-number entry field and buttons at the bottom of `JobLookupScreen` are unchanged
  — the design spec explicitly says this section "stays below the list," not that it's restyled in
  this phase.
- Follow existing code conventions exactly: named Compose modifier parameters, 4-space indentation,
  existing import ordering (standard-library-style: `androidx.*` grouped before `com.ppnam.*`,
  alphabetical within each group — matches the current file).

## Scope note (found during investigation, not verbatim in the spec)

The design spec's §5.3 lists card statuses as "Collecting = blue, Ready for Mixing = green,
Awaiting Approval = amber." Investigation of the real `ActiveJobCardSummary` DTO
(`app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt:142`) found **there is no
"Awaiting Approval" job status** — the wire only sends `"Collecting"`, `"ReadyForMixing"`,
`"Mixing"`, or `"Cancelled"` as `status`. "Awaiting approval" is a separate, orthogonal signal: a
`pendingApprovalCount: Int?` field that can be non-zero alongside *any* status (a collection can be
`Collecting` **and** have lines pending approval at the same time).

This plan resolves that the spec's intent — surface the amber "needs attention" signal — is better
served by pending-approval **taking priority over** the base status when computing a card's tone,
rather than inventing a status value that doesn't exist on the wire. See Task 2's `cardTone()`.

---

### Task 1: Shared `StatusCard` component

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/components/StatusCard.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/components/StatusCardTest.kt`

**Interfaces:**
- Consumes: existing theme colors from `com.mitas.ppnam.station2aa.ui.theme` — `AmberPrimary`,
  `DangerRed`, `GraphiteBorder`, `GraphiteSurface`, `SuccessGreen`, `TextMuted`, `WarningOrange`
  (all already exist, confirmed unchanged since Phase 1).
- Produces: `enum class StatusTone { Ready, Running, Warning, Danger, Idle }` with a
  `StatusTone.color(): Color` member function, and
  `@Composable fun StatusCard(tone: StatusTone = StatusTone.Idle, onClick: (() -> Unit)? = null, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable ColumnScope.(accent: Color) -> Unit)`
  — both consumed by Task 2. The `content` lambda receives the tone's accent color so callers can
  tint their own text consistently with the card's border, without hardcoding a color themselves.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCardTest {

    @Test
    fun `each tone maps to its designed color`() {
        assertEquals(SuccessGreen, StatusTone.Ready.color())
        assertEquals(AmberPrimary, StatusTone.Running.color())
        assertEquals(WarningOrange, StatusTone.Warning.color())
        assertEquals(DangerRed, StatusTone.Danger.color())
        assertEquals(TextMuted, StatusTone.Idle.color())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.components.StatusCardTest"`
Expected: FAIL — `StatusTone` does not exist yet (compile error).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/**
 * The five states a status-driven card can be in, shared across every screen that shows job
 * cards, equipment, or areas as color-coded cards (design: 2026-07-30-android-ui-ux-overhaul §3.4).
 */
enum class StatusTone {
    Ready, Running, Warning, Danger, Idle;

    fun color(): Color = when (this) {
        Ready -> SuccessGreen
        Running -> AmberPrimary
        Warning -> WarningOrange
        Danger -> DangerRed
        Idle -> TextMuted
    }
}

/**
 * The shared color-coded card shell: a tone-tinted border on the graphite surface. Chrome
 * (border/background/shape/click) is shared; layout inside is not, since different callers (a
 * job card, a machine card, an area card) need a different number of lines — `content` gets the
 * tone's accent color so callers can tint their own status text consistently with the border.
 */
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
    var cardModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        cardModifier = cardModifier.clickable(enabled = enabled, onClick = onClick)
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (tone == StatusTone.Idle) 1.dp else 2.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content(accentColor)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.components.StatusCardTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Verify the composable compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `StatusCard` has no consumer yet (Task 2), so there's nothing to
visually check until then — this step only confirms the file compiles.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/components/StatusCard.kt app/src/test/java/com/ppnam/station2aa/ui/components/StatusCardTest.kt
git commit -m "feat(ui): add shared StatusCard component"
```

---

### Task 2: Restyle Job Cards' active-jobs list

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/JobLookupScreenKtTest.kt`

**Interfaces:**
- Consumes: `StatusCard`/`StatusTone` from Task 1 (exact signature above).
  `ActiveJobCardSummary` (`com.mitas.ppnam.station2aa.data.mqtt.dto.JobCardMessages.kt:142`) — real
  fields used: `jobCardNumber: String`, `collectionId: String`, `status: String`,
  `statusLabel: String` (computed property), `productName: String`,
  `progressPercent: Double?`, `completedIngredientCount: Int?`, `requiredIngredientCount: Int?`,
  `pendingApprovalCount: Int?`. `viewModel.lookupJob(jobCardNumber, collectionId)` (unchanged,
  already exists on `MixingViewModel`).
- Produces: `internal fun ActiveJobCardSummary.cardTone(): StatusTone` — `internal`, not `private`,
  specifically so `JobLookupScreenKtTest` (a different file, same Gradle module) can call it
  directly.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class JobLookupScreenKtTest {

    private fun job(
        status: String = "",
        pendingApprovalCount: Int? = null,
    ) = ActiveJobCardSummary(
        jobCardNumber = "JC-1",
        collectionId = "COL-1",
        status = status,
        pendingApprovalCount = pendingApprovalCount,
    )

    @Test
    fun `Collecting maps to Running`() {
        assertEquals(StatusTone.Running, job(status = "Collecting").cardTone())
    }

    @Test
    fun `ReadyForMixing maps to Ready`() {
        assertEquals(StatusTone.Ready, job(status = "ReadyForMixing").cardTone())
    }

    @Test
    fun `Mixing maps to Running`() {
        assertEquals(StatusTone.Running, job(status = "Mixing").cardTone())
    }

    @Test
    fun `unknown status maps to Idle`() {
        assertEquals(StatusTone.Idle, job(status = "Cancelled").cardTone())
    }

    @Test
    fun `pending approval overrides status tone to Warning`() {
        assertEquals(StatusTone.Warning, job(status = "ReadyForMixing", pendingApprovalCount = 2).cardTone())
    }

    @Test
    fun `zero pending approval count does not force Warning`() {
        assertEquals(StatusTone.Ready, job(status = "ReadyForMixing", pendingApprovalCount = 0).cardTone())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreenKtTest"`
Expected: FAIL — `cardTone()` does not exist yet (compile error).

- [ ] **Step 3: Update imports and add `cardTone()`**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`, replace the import block
(everything from `package com.mitas.ppnam.station2aa.ui.mixing` down to the last import) with:

```kotlin
package com.mitas.ppnam.station2aa.ui.mixing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.StatusCard
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import com.mitas.ppnam.station2aa.ui.theme.AmberPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/**
 * Maps a job's wire status (plus its pending-approval flag) to the shared color language.
 * Pending approval takes priority over the base status — an operator scanning for what needs
 * attention should see amber immediately, not read a secondary line. `internal`, not `private`,
 * so `JobLookupScreenKtTest` can verify it directly.
 */
internal fun ActiveJobCardSummary.cardTone(): StatusTone = when {
    (pendingApprovalCount ?: 0) > 0 -> StatusTone.Warning
    status == "ReadyForMixing" -> StatusTone.Ready
    status == "Collecting" || status == "Mixing" -> StatusTone.Running
    else -> StatusTone.Idle
}
```

Note: this removes the `GraphiteBorder` and `GraphiteSurface` imports (they were only used by the
old `Card`'s styling, which `StatusCard` now encapsulates internally) and adds
`ActiveJobCardSummary`, `StatusCard`, `StatusTone`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreenKtTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Rename the screen title**

In the same file, find the `AppScaffold(...)` call and change:

```kotlin
    AppScaffold(
        title = "Job Lookup",
```

to:

```kotlin
    AppScaffold(
        title = "Job Cards",
```

- [ ] **Step 6: Restyle the active-jobs list**

Replace this block (the `items(...)` call inside the active-jobs `LazyColumn`):

```kotlin
                    items(activeJobs, key = { it.collectionId.ifBlank { it.jobCardNumber } }) { job ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber, job.collectionId) },
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, GraphiteBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(job.jobCardNumber, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                // Four collections of the same job card previously rendered as
                                // four visually identical rows — order number plus product name —
                                // while actually holding 0%, 75%, 75% and 0% progress, so the
                                // operator had no way to pick the one they were working on. Every
                                // field below was already in the response and simply unread.
                                // Every progress figure here is optional, because the backend
                                // genuinely omits them. Rendering a missing percentage as "0%"
                                // told the operator a ReadyForMixing collection had no progress
                                // at all. `status` is the one field Station 2 fills in reliably,
                                // so it leads and the numbers embellish it only when they exist.
                                Text(
                                    buildString {
                                        if (job.collectionId.isNotBlank()) append(job.collectionId)
                                        if (job.status.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(job.statusLabel)
                                        }
                                        val required = job.requiredIngredientCount ?: 0
                                        if (required > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${job.completedIngredientCount ?: 0} of $required lines")
                                        }
                                        job.progressPercent?.let { percent ->
                                            if (isNotEmpty()) append(" · ")
                                            append("%.0f%%".format(percent))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AmberPrimary
                                )
                                if (job.productName.isNotBlank()) {
                                    Text(
                                        job.productName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if ((job.pendingApprovalCount ?: 0) > 0) {
                                    Text(
                                        "${job.pendingApprovalCount} line(s) awaiting approval",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarningOrange
                                    )
                                }
                            }
                        }
                    }
```

with:

```kotlin
                    items(activeJobs, key = { it.collectionId.ifBlank { it.jobCardNumber } }) { job ->
                        StatusCard(
                            tone = job.cardTone(),
                            onClick = { viewModel.lookupJob(job.jobCardNumber, job.collectionId) },
                            enabled = !isLoading,
                        ) { accent ->
                            Text(job.jobCardNumber, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            // Four collections of the same job card previously rendered as
                            // four visually identical rows — order number plus product name —
                            // while actually holding 0%, 75%, 75% and 0% progress, so the
                            // operator had no way to pick the one they were working on. Every
                            // field below was already in the response and simply unread.
                            // Every progress figure here is optional, because the backend
                            // genuinely omits them. Rendering a missing percentage as "0%"
                            // told the operator a ReadyForMixing collection had no progress
                            // at all. `status` is the one field Station 2 fills in reliably,
                            // so it leads and the numbers embellish it only when they exist.
                            Text(
                                buildString {
                                    if (job.collectionId.isNotBlank()) append(job.collectionId)
                                    if (job.status.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(job.statusLabel)
                                    }
                                    val required = job.requiredIngredientCount ?: 0
                                    if (required > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${job.completedIngredientCount ?: 0} of $required lines")
                                    }
                                    job.progressPercent?.let { percent ->
                                        if (isNotEmpty()) append(" · ")
                                        append("%.0f%%".format(percent))
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                            if (job.productName.isNotBlank()) {
                                Text(
                                    job.productName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if ((job.pendingApprovalCount ?: 0) > 0) {
                                Text(
                                    "${job.pendingApprovalCount} line(s) awaiting approval",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningOrange
                                )
                            }
                        }
                    }
```

The only functional change in this block is the status line's color: `AmberPrimary` (hardcoded,
regardless of actual status) becomes `accent` (the tone's real color — green/blue/amber/gray
depending on `job.cardTone()`). Everything else — the text content, the click behavior, the
pending-approval line — is unchanged, just re-hosted inside `StatusCard`'s content slot.

- [ ] **Step 7: Verify the full file builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, no unused-import warnings for `GraphiteBorder`/`GraphiteSurface`
(removed in Step 3).

- [ ] **Step 8: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — this file has no dedicated Compose UI test (consistent with Phase 1's precedent:
pure layout/styling changes are verified by compilation, not a Compose test harness this project
doesn't have), but the full suite must still pass since `JobLookupScreenKtTest` is now part of it
and nothing else should have broken.

- [ ] **Step 9: Manual verification (if a device/emulator is available)**

Run the app, log in, land on Home, tap "Job Cards." If any active jobs are present:
1. A `Collecting` job shows a blue-bordered card.
2. A `ReadyForMixing` job shows a green-bordered card.
3. A job with `pendingApprovalCount > 0` shows an amber-bordered card, regardless of its status,
   with the "N line(s) awaiting approval" line still present.
4. Tapping a card still navigates into that job's ingredient scan / order flow, same as before.
5. The screen's title bar now reads "Job Cards" instead of "Job Lookup."

If no device/emulator is available in this environment, skip this step and say so explicitly in
the report — do not treat it as a blocker (same accepted limitation as Phase 1).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/JobLookupScreenKtTest.kt
git commit -m "feat(job-cards): restyle active-jobs list with color-coded status cards"
```

---

## Self-Review Notes

- **Spec coverage:** §4.2 ("one door," already true via existing `lookupJob()` — no change needed)
  and §5.3 (color-coded active-jobs cards, title rename, order-entry section unchanged) are both
  fully covered by Tasks 1–2. The "Awaiting Approval" imprecision is documented and resolved in the
  Scope note, not silently glossed over.
- **Placeholder scan:** no TBD/TODO; every step has real, complete code.
- **Type consistency:** `StatusCard`'s `content: @Composable ColumnScope.(accent: Color) -> Unit`
  signature (Task 1) matches exactly how Task 2's `items { job -> StatusCard(...) { accent -> ... } }`
  call site uses it. `cardTone(): StatusTone` (Task 2) returns exactly the enum type `StatusCard`
  (Task 1) declares as its `tone` parameter.
