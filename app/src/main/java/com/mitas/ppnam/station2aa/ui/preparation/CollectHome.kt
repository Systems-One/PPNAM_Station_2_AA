package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.ui.joblookup.countsLine
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
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

/** How long a just-completed ingredient row stays highlighted. */
private const val HIGHLIGHT_MILLIS = 2000L

/**
 * Collect home: compact header, progress, ingredient rows, result area at the top of the list, and
 * the scan zone (or the one big Mix complete button) pinned at the bottom.
 */
@Composable
internal fun CollectHome(
    state: PreparationUiState,
    sessionId: String?,
    onRetry: (PendingCommand) -> Unit,
    onRefresh: () -> Unit,
    onUseCode: (String) -> Unit,
    onMixComplete: () -> Unit,
    onDecide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prep = state.snapshot?.preparation
    val materials = prep?.materials.orEmpty()
    val banner = state.banner

    // The ingredient that just finished stays highlighted for a moment.
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(banner) {
        val code = (banner as? ResultBanner.Accepted)?.completedMaterialCode ?: return@LaunchedEffect
        highlighted = code
        delay(HIGHLIGHT_MILLIS)
        highlighted = null
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prep != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${prep.mixCount} ${if (prep.mixCount == 1) "mix" else "mixes"}",
                            color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
                        )
                        Text(shortPrepId(state.prepId), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    state.snapshot?.lookup?.detail?.mixProgress?.let {
                        Text(it.countsLine(), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
            state.readError?.let { error ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = DangerRed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Retry") }
                    }
                }
            }
            // Result area: a hold (lost reply) takes it over; otherwise the last result.
            if (state.blocking.isNotEmpty()) {
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HoldBanners(state, sessionId, onRetry) } }
            } else {
                banner?.let { b ->
                    item {
                        val name = (b as? ResultBanner.Accepted)?.completedMaterialCode
                            ?.let { code -> materials.firstOrNull { it.code == code }?.name }
                        ResultBannerView(b, name)
                    }
                }
                state.message?.let { item { InfoLine(it) } }
                if (banner == null && state.allCollected) {
                    item {
                        StatusBanner("✓", "All ingredients collected", SuccessGreen, BannerGreenBg)
                    }
                }
            }
            if (prep == null) {
                if (state.loading) item { CircularProgressIndicator() }
            } else {
                item { ProgressSummary(materials) }
                if (state.canDecide) {
                    item { Text(DecisionScope.Preparation.hint, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
                }
                items(materials, key = { "material-${it.code}" }) { material ->
                    val tappable = state.canDecide && !material.excluded
                    Box(if (tappable) Modifier.clickable { onDecide(material.code) } else Modifier) {
                        IngredientRow(material, highlighted = material.code == highlighted)
                    }
                }
                val exceptions = state.snapshot?.collectionExceptions.orEmpty()
                if (exceptions.isNotEmpty()) item { ExceptionsSection(exceptions) }
            }
        }

        if (state.canConfirm) {
            Button(
                onClick = onMixComplete,
                colors = brandButtonColors(),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Mix complete — ready for mixer", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            Text(
                "Asks once to confirm", color = TextMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        } else if (state.stage == PrepStage.Collecting) {
            ScanZone(state, onUseCode)
        }
    }
}

@Composable
private fun ProgressSummary(materials: List<PrepMaterial>) {
    val included = materials.filterNot { it.excluded }
    val done = included.count { it.isComplete }
    val fraction = if (included.isEmpty()) 0f else
        included.map { m ->
            if (m.required.signum() <= 0) 1f
            else (m.collected.toDouble() / m.required.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }.average().toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = SuccessGreen, trackColor = GraphiteSurfaceVariant,
        )
        Text(
            "$done of ${included.size} ingredient${if (included.size == 1) "" else "s"} collected",
            color = TextMuted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One ingredient: green edge when collected, amber while outstanding, muted when excluded. A row
 * Station 2 marks `isModified` also gets a faint amber outline and the [ModifiedLabel].
 */
@Composable
internal fun IngredientRow(material: PrepMaterial, highlighted: Boolean) {
    val edge = when {
        material.excluded -> GraphiteBorder
        material.isComplete -> SuccessGreen
        else -> WarningOrange
    }
    val background = if (highlighted) BannerGreenBg else GraphiteSurface
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (material.excluded) Modifier.alpha(0.45f) else Modifier)
            .background(background, RoundedCornerShape(8.dp))
            .then(if (material.isModified) Modifier.border(1.dp, ModifiedOutline, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(edge, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Row(
            Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(material.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (material.excluded) "${material.code} · excluded" else material.code,
                        color = TextMuted, style = MaterialTheme.typography.bodySmall,
                    )
                    if (material.isModified) ModifiedLabel(Modifier.padding(start = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    material.excluded -> Text("—", color = TextMuted)
                    material.isComplete -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (material.originalRequired?.signum() == 0) {
                            Text("added  ", color = WarningOrange, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("✓ ${material.collectedText()}", color = SuccessGreen, fontWeight = FontWeight.Bold)
                    }
                    else -> {
                        if (material.originalRequired?.signum() == 0) {
                            Text("added", color = WarningOrange, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "${material.remaining.plainText()} ${material.unit}".trim(),
                            color = TextPrimary, fontWeight = FontWeight.Bold,
                        )
                        Text("of ${material.required.plainText()} left", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val ModifiedOutline = WarningOrange.copy(alpha = 0.45f)

/**
 * Contract §8 material display: a restrained amber "Modified" tag beside the code. It sits next to
 * the ingredient description and never replaces it.
 */
@Composable
internal fun ModifiedLabel(modifier: Modifier = Modifier) {
    Text(
        "Modified",
        modifier = modifier
            .background(BannerAmberBg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = WarningOrange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
    )
}

/** Dashed-style scan target with the prompt for the current moment and a small type-the-code field. */
@Composable
private fun ScanZone(state: PreparationUiState, onUseCode: (String) -> Unit) {
    val prompt = when (state.banner) {
        null -> "Scan a pallet tag"
        is ResultBanner.Accepted -> "Scan next pallet"
        else -> "Scan another pallet"
    }
    ScanBox(prompt, null, state.canScan, state.blocking.isNotEmpty(), onUseCode)
}

/**
 * The shared scan target: a slim bar with [title] (and muted [subtitle]). Typing a code is rare, so
 * the type-the-code field stays behind the keyboard button until asked for.
 */
@Composable
internal fun ScanBox(title: String, subtitle: String?, enabled: Boolean, paused: Boolean, onUseCode: (String) -> Unit) {
    var manualCode by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun close() { manualCode = ""; typing = false }
    fun submit() { if (enabled && manualCode.isNotBlank()) { onUseCode(manualCode); close() } }
    LaunchedEffect(typing) { if (typing) focus.requestFocus() }
    LaunchedEffect(enabled) { if (!enabled) close() }
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, GraphiteBorder, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (typing) {
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it.trim() },
                placeholder = { Text("Type the code", color = TextMuted) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { submit() }, enabled = enabled && manualCode.isNotBlank()) {
                Icon(Icons.Filled.Check, contentDescription = "Use code", tint = BrandTint)
            }
            IconButton(onClick = { close() }) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel typing", tint = TextMuted)
            }
        } else {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = TextMuted)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    if (paused) "Scanning paused" else title,
                    color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null && !paused) Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { typing = true }, enabled = enabled) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Type the code", tint = TextMuted)
            }
        }
    }
}

/** Exceptions stay at the bottom of the list, collapsed under "Exceptions (n)". */
@Composable
private fun ExceptionsSection(exceptions: List<CollectionExceptionRecord>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "Exceptions (${exceptions.size})",
            color = BrandTint, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        )
        if (open) exceptions.forEach { ExceptionCard(it) }
    }
}

@Composable
private fun ExceptionCard(record: CollectionExceptionRecord) {
    Card(colors = CardDefaults.cardColors(containerColor = GraphiteSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            record.lines().forEachIndexed { i, line ->
                Text(
                    line,
                    color = if (i == 0) TextPrimary else TextMuted,
                    style = if (i == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
