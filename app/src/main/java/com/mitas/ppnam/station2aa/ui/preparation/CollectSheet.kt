package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.BagGuide
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.model.splitCaptures
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurfaceVariant
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import com.mitas.ppnam.station2aa.ui.theme.brandButtonColors
import java.math.BigDecimal

internal class CollectActions(
    val onAmount: (String) -> Unit,
    val onReason: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onAddToMix: () -> Unit,
    val onCancel: () -> Unit,
    val onPickMaterial: (String) -> Unit,
    val onRetry: (PendingCommand) -> Unit,
)

/** The Collect screen for one scanned pallet; which form it shows follows [PreparationUiState.step]. */
@Composable
internal fun CollectSheet(state: PreparationUiState, sessionId: String?, actions: CollectActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.blocking.isNotEmpty()) HoldBanners(state, sessionId, actions.onRetry)
        state.message?.let { InfoLine(it) }
        when (val step = state.step) {
            CollectStep.Idle -> Unit
            is CollectStep.Identifying -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Identifying ${step.code}…", color = TextPrimary)
                }
                CancelButton("Cancel", actions.onCancel)
            }
            is CollectStep.Identified -> IdentifiedForm(state, step, actions)
            is CollectStep.Submitting -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(
                        if (step.total > 1) "Recording ${step.name}: ${minOf(step.done + 1, step.total)} of ${step.total}…"
                        else "Recording ${step.name}…",
                        color = TextPrimary,
                    )
                }
                Text("Don't scan again until Station 2 answers.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            is CollectStep.NotInMix -> NotInMixForm(state, step, actions)
            is CollectStep.Excluded -> {
                StatusBanner(
                    "✕", "Excluded from this mix — can't collect", DangerRed, BannerRedBg,
                    details = listOf("${step.materialName} · ${step.source.materialCode}" to false),
                )
                CancelButton("Cancel", actions.onCancel)
            }
            is CollectStep.Unusable -> {
                StatusBanner(
                    "✕", "Can't collect from this pallet", DangerRed, BannerRedBg,
                    details = listOf(step.reason to false, "Pallet ${step.source.code}" to true),
                )
                CancelButton("Cancel", actions.onCancel)
            }
            is CollectStep.Unknown -> {
                StatusBanner("?", "Pallet not recognised", WarningOrange, BannerAmberBg, details = listOf(step.message to false))
                CancelButton("Cancel", actions.onCancel)
            }
            is CollectStep.Fallback -> FallbackForm(state, step, actions)
        }
    }
}

@Composable
private fun CancelButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(label) }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PalletCard(source: SourceInfo, name: String, stillNeeded: PrepMaterial?) {
    Column(
        Modifier.fillMaxWidth().background(GraphiteSurface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Pallet ${source.code}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        source.bagSize?.let { KeyValue("Bag size", "${it.plainText()} ${source.unit}".trim()) }
        stillNeeded?.let { KeyValue("Still needed", "${it.remaining.plainText()} ${it.unit}".trim()) }
        source.available?.let { available ->
            val bags = source.bagSize?.takeIf { it.signum() > 0 }
                ?.let { available.divide(it, 1, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString() }
            KeyValue("On pallet", "${available.plainText()} ${source.unit}".trim() + (bags?.let { " ($it bags)" } ?: ""))
        }
    }
}

@Composable
private fun IdentifiedForm(state: PreparationUiState, step: CollectStep.Identified, actions: CollectActions) {
    val source = step.source
    val unit = source.unit.ifBlank { step.fit.material.unit }
    PalletCard(source, step.fit.material.name, step.fit.material)
    val valid = step.check as? AmountCheck.Valid
    TakeCard(valid?.amount, unit, source.bagSize, step.guide)
    AmountField("Amount ($unit)", step.amountText, actions.onAmount)
    (step.check as? AmountCheck.Invalid)?.let {
        Text(it.problem.message, color = WarningOrange, style = MaterialTheme.typography.bodySmall)
    } ?: Text("Took a different amount? Edit the $unit.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    val bagCount = valid?.let { splitCaptures(it.amount, source.bagSize).size } ?: 0
    val label = valid?.let {
        "Submit ${it.amount.plainText()} $unit".trim() + if (bagCount > 1 && source.bagSize != null) " ($bagCount bags)" else ""
    } ?: "Submit"
    Button(
        onClick = actions.onSubmit,
        enabled = valid != null && state.canScan,
        colors = brandButtonColors(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    CancelButton("Cancel", actions.onCancel)
}

/** The big "Take ≈ ½ bag" guide with bag icons. Display only: the recorded amount is the exact kg. */
@Composable
private fun TakeCard(amount: BigDecimal?, unit: String, bagSize: BigDecimal?, guide: BagGuide?) {
    Column(
        Modifier.fillMaxWidth().background(GraphiteSurfaceVariant, RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TAKE", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        if (amount == null) {
            Text("—", color = TextMuted, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        } else if (guide == null) {
            Text("${amount.plainText()} $unit".trim(), color = BrandTint, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        } else {
            Text(guide.text, color = BrandTint, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            BagIcons(guide)
            val parts = splitCaptures(amount, bagSize)
            Text(
                if (parts.size > 1) "records ${parts.joinToString(" + ") { it.plainText() }} $unit"
                else "records exactly ${amount.plainText()} $unit",
                color = TextMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BagIcons(guide: BagGuide) {
    // Whole bags are full; a rounded-up rest shows as one more full bag, a real rest as a part-filled one.
    val fills = List(guide.wholeBags.coerceAtMost(MAX_BAG_ICONS)) { 1f } +
        (if (guide.restQuarters > 0) listOf(guide.restQuarters / 4f) else emptyList())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        fills.take(MAX_BAG_ICONS).forEach { fill ->
            Box(Modifier.size(width = 26.dp, height = 34.dp).border(2.dp, BrandTint, RoundedCornerShape(5.dp)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fill).background(BrandTint))
            }
        }
    }
}

private const val MAX_BAG_ICONS = 8

@Composable
private fun AmountField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotInMixForm(state: PreparationUiState, step: CollectStep.NotInMix, actions: CollectActions) {
    val source = step.source
    val unit = source.unit.ifBlank { "kg" }
    StatusBanner(
        "!", "Not in this mix", WarningOrange, BannerAmberBg,
        details = listOf("${source.name} · ${source.materialCode}" to false, "Pallet ${source.code}" to true),
    )
    source.bagSize?.let { KeyValue("Bag size", "${it.plainText()} $unit") }
    AmountField("Adding ($unit)", step.amountText, actions.onAmount)
    (step.check as? AmountCheck.Invalid)?.takeIf { step.amountText.isNotBlank() }?.let {
        Text(it.problem.message, color = WarningOrange, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = step.reason, onValueChange = actions.onReason, label = { Text("Reason (required)") },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Adds ${source.name} to this mix, then you record it. Logged as an exception for manager review.",
        color = TextMuted, style = MaterialTheme.typography.bodySmall,
    )
    val valid = step.check as? AmountCheck.Valid
    Button(
        onClick = actions.onAddToMix,
        enabled = step.canAdd && state.canScan,
        colors = ButtonDefaults.buttonColors(containerColor = WarningOrange, contentColor = Color(0xFF07101A)),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(valid?.let { "Add ${it.amount.plainText()} $unit to mix" } ?: "Add to mix", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    CancelButton("Cancel — don't add", actions.onCancel)
}

/** Station 2 couldn't identify the pallet: the operator taps the ingredient, then types the amount. */
@Composable
private fun FallbackForm(state: PreparationUiState, step: CollectStep.Fallback, actions: CollectActions) {
    val materials = state.snapshot?.preparation?.materials.orEmpty().filter { !it.excluded && !it.isComplete }
    val picked = materials.firstOrNull { it.code == step.pickedMaterial }
    StatusBanner(
        "?", step.fallbackTitle(), WarningOrange, BannerAmberBg,
        details = listOf("Pallet ${step.code}" to true),
    )
    materials.forEach { m ->
        val selected = m.code == step.pickedMaterial
        Row(
            Modifier.fillMaxWidth()
                .background(GraphiteSurface, RoundedCornerShape(8.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) BrandTint else GraphiteBorder, RoundedCornerShape(8.dp))
                .clickable { actions.onPickMaterial(m.code) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(m.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(m.code, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("${m.remaining.plainText()} ${m.unit}".trim(), color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
    if (picked != null) {
        AmountField("Amount (${picked.unit})", step.amountText, actions.onAmount)
        (step.check as? AmountCheck.Invalid)?.let {
            Text(it.problem.message, color = WarningOrange, style = MaterialTheme.typography.bodySmall)
        }
        val valid = step.check as? AmountCheck.Valid
        Button(
            onClick = actions.onSubmit,
            enabled = valid != null && state.canScan,
            colors = brandButtonColors(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(valid?.let { "Submit ${it.amount.plainText()} ${picked.unit}".trim() } ?: "Submit", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    }
    CancelButton("Cancel", actions.onCancel)
}
