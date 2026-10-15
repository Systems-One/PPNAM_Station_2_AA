package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.MachineUse
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.Preparation
import com.mitas.ppnam.station2aa.domain.model.StripState
import com.mitas.ppnam.station2aa.domain.model.freeDestinations
import com.mitas.ppnam.station2aa.domain.model.isFixedPair
import com.mitas.ppnam.station2aa.domain.model.machineUse
import com.mitas.ppnam.station2aa.domain.model.mixers
import com.mitas.ppnam.station2aa.domain.model.savedMixer
import com.mitas.ppnam.station2aa.domain.model.savedProduction
import com.mitas.ppnam.station2aa.domain.model.stageStrip
import com.mitas.ppnam.station2aa.ui.theme.BrandPrimary
import com.mitas.ppnam.station2aa.ui.theme.CyanAccent
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurfaceVariant
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import com.mitas.ppnam.station2aa.ui.theme.brandButtonColors
import kotlinx.coroutines.delay

private val BannerBlueBg = Color(0xFF0F2A3A)
private const val TICK_MILLIS = 15_000L
private const val FEEDS_SHOWN = 3
private const val FREE_SHOWN = 8

class MixerActions(
    val onRetry: (PendingCommand) -> Unit,
    val onRefresh: () -> Unit,
    val onUseCode: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onBack: () -> Unit,
)

/** Mixer & production stages of a PREP (ReadyForMixer .. Completed): either the confirm card or the stage home. */
@Composable
internal fun MixerStage(state: PreparationUiState, sessionId: String?, actions: MixerActions, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot
    val prep = snapshot?.preparation
    val machine = state.machine
    if (snapshot != null && prep != null && machine is MachineStep.Confirm) {
        MachineConfirm(state, snapshot, prep, machine, actions, modifier)
    } else {
        StageHome(state, sessionId, snapshot, prep, actions, modifier)
    }
}

@Composable
private fun StageHome(
    state: PreparationUiState,
    sessionId: String?,
    snapshot: PrepSnapshot?,
    prep: Preparation?,
    actions: MixerActions,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot == null || prep == null) {
                if (state.loading) item { CircularProgressIndicator() }
                return@LazyColumn
            }
            state.readError?.let { error ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = DangerRed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = actions.onRefresh, enabled = !state.loading) { Text("Retry") }
                    }
                }
            }
            if (state.blocking.isNotEmpty()) {
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HoldBanners(state, sessionId, actions.onRetry) } }
            } else {
                state.banner?.let { item { ResultBannerView(it, null) } }
                state.message?.let { item { InfoLine(it) } }
            }
            val n = prep.mixCount
            when (prep.stage) {
                PrepStage.Completed -> item { CompletedBanner(snapshot, prep) }
                PrepStage.Mixing -> item {
                    LiveBanner("Mixing ${prep.mixed + 1} of $n", snapshot.savedMixer()?.name.orEmpty(), key = prep.cycleId ?: prep.stageRaw)
                }
                PrepStage.Producing -> item {
                    LiveBanner("Producing ${prep.produced + 1} of $n", snapshot.savedProduction()?.name.orEmpty(), key = prep.cycleId ?: prep.stageRaw)
                }
                PrepStage.ReadyForNextMix -> item {
                    val togo = n - prep.mixed
                    Text(
                        "$togo ${if (togo == 1) "mix" else "mixes"} to go",
                        color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PrepStage.ReadyForProduction -> item {
                    if (state.banner == null) StatusBanner("✓", "All $n ${if (n == 1) "mix" else "mixes"} mixed", SuccessGreen, BannerGreenBg)
                    else Text("✓ All $n ${if (n == 1) "mix" else "mixes"} mixed", color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                }
                else -> Unit
            }
            item { StageStripRow(snapshot, prep) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountTile("Mixed", prep.mixed, n, Modifier.weight(1f))
                    CountTile("Produced", prep.produced, n, Modifier.weight(1f))
                }
            }
            if (prep.stage == PrepStage.ReadyForMixer) {
                item { Text("Mixers", color = TextMuted, style = MaterialTheme.typography.bodySmall) }
                snapshot.mixers().forEach { m -> item(key = "mixer-${m.id}") { MixerRow(m, snapshot.machineUse(m)) } }
            }
            if (prep.stage == PrepStage.ReadyForProduction) {
                item { Text(freeExtrudersLine(snapshot), color = TextMuted, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        if (prep != null && snapshot != null) {
            if (prep.stage == PrepStage.Completed) {
                OutlinedButton(onClick = actions.onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Back to job") }
            } else if (prep.stage.isMachineStage) {
                val (title, subtitle) = scanText(snapshot, prep)
                ScanBox(title, subtitle, state.canScanMachine, state.blocking.isNotEmpty(), actions.onUseCode)
            }
        }
    }
}

/** The scan-zone text of the spec table. */
internal fun scanText(snapshot: PrepSnapshot, prep: Preparation): Pair<String, String?> {
    val n = prep.mixCount
    return when (prep.stage) {
        PrepStage.ReadyForMixer -> "Scan a mixer" to "to start mix ${prep.mixed + 1} of $n"
        PrepStage.Mixing -> {
            val name = snapshot.savedMixer()?.name ?: "the mixer"
            "Scan $name to finish" to "only $name is accepted"
        }
        PrepStage.ReadyForNextMix -> "Scan ${snapshot.savedMixer()?.name ?: "the mixer"}" to "to start mix ${prep.mixed + 1} of $n"
        PrepStage.ReadyForProduction -> "Scan an extruder" to "to produce mix ${prep.produced + 1} of $n"
        PrepStage.Producing -> "Scan ${snapshot.savedProduction()?.name ?: "the machine"} to finish" to null
        else -> "" to null
    }
}

internal fun freeExtrudersLine(snapshot: PrepSnapshot): String {
    val free = snapshot.freeDestinations()
    if (free.isEmpty()) return "No free extruders"
    val shown = free.take(FREE_SHOWN).joinToString(", ") { it.name }
    return if (free.size > FREE_SHOWN) "Free extruders: $shown … (${free.size})" else "Free extruders: $shown"
}

@Composable
private fun StageStripRow(snapshot: PrepSnapshot, prep: Preparation) {
    val strip = prep.stageStrip(snapshot.isFixedPair())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        strip.steps.forEach { step ->
            val (bg, fg, weight) = when (step.state) {
                StripState.Done -> Triple(BannerGreenBg, SuccessGreen, FontWeight.Normal)
                StripState.Current -> Triple(BrandPrimary, Color.White, FontWeight.Bold)
                StripState.Future -> Triple(GraphiteSurfaceVariant, TextMuted, FontWeight.Normal)
            }
            Text(
                step.label, color = fg, fontWeight = weight, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).background(bg, RoundedCornerShape(4.dp)).padding(vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun CountTile(label: String, value: Int, total: Int, modifier: Modifier) {
    Column(
        modifier.background(GraphiteSurface, RoundedCornerShape(8.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value/$total", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Blue live banner. Station 2's `startedAtUtc` is when the PREP started collecting, not when this
 * cycle began, so the timer counts from when this screen first saw the cycle ([key]) and no start
 * time is shown.
 */
@Composable
private fun LiveBanner(title: String, machineName: String, key: String) {
    val seen = remember(key) { System.currentTimeMillis() }
    var now by remember(key) { mutableLongStateOf(seen) }
    LaunchedEffect(key) {
        while (true) {
            delay(TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val minutes = ((now - seen) / 60_000L).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BannerBlueBg),
        border = BorderStroke(1.dp, CyanAccent),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CircularProgressIndicator(color = CyanAccent, modifier = Modifier.width(44.dp).height(44.dp), strokeWidth = 5.dp)
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            if (machineName.isNotBlank()) Text(machineName, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            Text("$minutes min", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompletedBanner(snapshot: PrepSnapshot, prep: Preparation) {
    val mixer = snapshot.savedMixer()?.name
    val production = snapshot.savedProduction()?.name
    val route = listOfNotNull(mixer, production).joinToString(" → ")
    val progress = snapshot.lookup.detail?.mixProgress
    StatusBanner(
        icon = "✓", title = "Preparation complete", tint = SuccessGreen, background = BannerGreenBg,
        details = listOfNotNull(
            listOf("${prep.mixCount} ${if (prep.mixCount == 1) "mix" else "mixes"}", route).filter { it.isNotBlank() }
                .joinToString(" · ") to true,
            progress?.let { "${it.producedMixes} of ${it.requiredMixes} finished · ${it.availableToPrepareMixes} available to prepare" to true },
        ),
    )
}

@Composable
private fun MixerRow(machine: Machine, use: MachineUse) {
    val (edge, label, labelColor, dim) = when (use) {
        MachineUse.Free -> MixerRowStyle(SuccessGreen, "free", SuccessGreen, false)
        is MachineUse.InUse -> MixerRowStyle(WarningOrange, "in use · ${shortOtherPrep(use.prepId)}", WarningOrange, false)
        MachineUse.Disabled -> MixerRowStyle(GraphiteBorder, "disabled", TextMuted, true)
    }
    Row(
        Modifier.fillMaxWidth().then(if (dim || use is MachineUse.InUse) Modifier.alpha(if (dim) 0.45f else 0.8f) else Modifier)
            .background(GraphiteSurface, RoundedCornerShape(8.dp)),
    ) {
        Box(Modifier.width(4.dp).height(36.dp).background(edge, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Row(
            Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(machine.name, color = TextPrimary)
            Text(label, color = labelColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class MixerRowStyle(val edge: Color, val label: String, val labelColor: Color, val dim: Boolean)

/** `PREP_91C2` from `PREP_91c2…`. */
private fun shortOtherPrep(id: String): String = "PREP_" + id.removePrefix("PREP_").take(4).uppercase()

// ---- confirm card ----

@Composable
private fun MachineConfirm(
    state: PreparationUiState,
    snapshot: PrepSnapshot,
    prep: Preparation,
    step: MachineStep.Confirm,
    actions: MixerActions,
    modifier: Modifier,
) {
    val scan = step.scan
    val machine: Machine
    val bannerTitle: String?
    val action: String?
    val note: String?
    val warn: String?
    when (scan) {
        is MachineScan.StartMix -> {
            machine = scan.machine
            bannerTitle = "Mix ${scan.k} of ${scan.n}"
            action = "Start mix ${scan.k} on ${machine.name}"
            note = if (scan.firstStart) "This mixer stays assigned for all ${scan.n} mixes." else null
            warn = scan.savedMixer?.takeIf { it.id != machine.id }?.let { "Saved mixer is ${it.name}" }
        }
        is MachineScan.StartProduction -> {
            machine = scan.machine
            bannerTitle = "Production ${scan.k} of ${scan.n}"
            action = "Start production ${scan.k} on ${machine.name}"
            note = null
            warn = scan.savedProduction?.takeIf { it.id != machine.id }?.let { "Saved extruder is ${it.name}" }
        }
        is MachineScan.Finish -> {
            machine = scan.machine
            val what = if (scan.isProduction) "production" else "mix"
            bannerTitle = "Finish $what ${scan.k} on ${machine.name}?"
            action = "Finish $what ${scan.k}"
            note = null
            warn = null
        }
        is MachineScan.Disabled -> {
            machine = scan.machine
            bannerTitle = null
            action = null
            note = null
            warn = null
        }
        else -> return
    }
    val enabled = state.canScanMachine && step === state.machine
    val detail = state.snapshot?.lookup?.detail
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = GraphiteSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Scanned ${step.code}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Text(machine.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                KeyValue("Area", machine.area)
                if (scan is MachineScan.StartMix) {
                    feedsText(snapshot, machine)?.let { KeyValue("Feeds", it) }
                }
            }
        }
        if (bannerTitle != null) {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BannerBlueBg), border = BorderStroke(1.dp, CyanAccent),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bannerTitle, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    val sub = listOf(detail?.product.orEmpty(), shortPrepId(state.prepId)).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(sub, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text("Disabled — can't start", color = DangerRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        warn?.let { Text(it, color = WarningOrange, style = MaterialTheme.typography.bodyMedium) }
        note?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { InfoLine(it) }
        Box(Modifier.weight(1f))
        if (action != null) {
            Button(
                onClick = actions.onConfirm, enabled = enabled, colors = brandButtonColors(),
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) { Text(action, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
        OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }
}

/** "Extruder 1, Extruder 2, Extruder 3 + 22 more", or null when the mixer feeds nothing known. */
internal fun feedsText(snapshot: PrepSnapshot, mixer: Machine): String? {
    val names = mixer.destinations.map { ref -> snapshot.machines.firstOrNull { it.id.equals(ref, true) || it.code.equals(ref, true) }?.name ?: ref }
        .distinct()
    if (names.isEmpty()) return null
    val shown = names.take(FEEDS_SHOWN).joinToString(", ")
    return if (names.size > FEEDS_SHOWN) "$shown + ${names.size - FEEDS_SHOWN} more" else shown
}
