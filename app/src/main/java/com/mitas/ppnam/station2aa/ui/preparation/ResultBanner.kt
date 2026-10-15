package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/** The mockup's banner tints (collect-flow.html `.banner.g/.r/.a`). */
internal val BannerGreenBg = Color(0xFF123524)
internal val BannerRedBg = Color(0xFF3A1717)
internal val BannerAmberBg = Color(0xFF3A2A10)

/** A centred status block: big [icon], bold [title], optional detail lines. */
@Composable
internal fun StatusBanner(
    icon: String,
    title: String,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    details: List<Pair<String, Boolean>> = emptyList(),
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, tint),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(icon, color = tint, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            // Detail lines: (text, muted?)
            details.forEach { (text, muted) ->
                Text(
                    text, color = if (muted) TextMuted else TextPrimary, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Accepted (green), Rejected (red) or Partial (red) after a Submit; [completedName] is the row that just finished. */
@Composable
internal fun ResultBannerView(banner: ResultBanner, completedName: String?) {
    when (banner) {
        is ResultBanner.Accepted -> StatusBanner(
            icon = "✓", title = banner.text, tint = SuccessGreen, background = BannerGreenBg,
            details = listOfNotNull(
                completedName?.let { "$it is now fully collected" to true },
                banner.warning?.let { it to false },
            ),
        )
        is ResultBanner.Rejected -> StatusBanner(
            icon = "✕", title = "Not recorded", tint = DangerRed, background = BannerRedBg,
            details = listOfNotNull(banner.text to false, banner.loggedLine?.let { it to true }),
        )
        is ResultBanner.Partial -> StatusBanner(
            icon = "✕",
            title = "Recorded ${banner.recorded} of ${banner.total} bags (${banner.recordedKg} kg)",
            tint = DangerRed, background = BannerRedBg,
            details = listOfNotNull("Not recorded: ${banner.rejection}" to false, banner.loggedLine?.let { it to true }),
        )
    }
}

@Composable
internal fun InfoLine(message: OutcomeMessage) {
    val text = (message as OutcomeMessage.Info).text
    Text(text, color = WarningOrange, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Lost reply (option A): the result area becomes a full-width amber hold with one big Retry. A
 * manager-reconcile entry gets the same hold without Retry. Scanning and Submit are disabled
 * elsewhere while any hold is shown.
 */
@Composable
internal fun HoldBanner(command: PendingCommand, retryable: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BannerAmberBg),
        border = BorderStroke(1.dp, WarningOrange),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (retryable) {
                Text(
                    "Station 2 hasn't answered — don't scan again",
                    color = WarningOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "The last ${command.action} may or may not have been recorded. Retry sends the same message again.",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onRetry, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange, contentColor = Color(0xFF07101A)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Retry", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            } else {
                Text(
                    "A manager must reconcile this at the station",
                    color = WarningOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "You can continue once it has been resolved.",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun HoldBanners(state: PreparationUiState, sessionId: String?, onRetry: (PendingCommand) -> Unit) {
    state.blocking.forEach { command ->
        val ownSession = command.status == PendingStatus.Unresolved && command.sessionId == sessionId
        HoldBanner(command, ownSession, !state.inFlight) { onRetry(command) }
    }
}

/**
 * Spec §6: Station 2 never ran a capture/confirm and never will. Only an explicit tap sends a
 * replacement (a new command); it is enabled only while a tap of its own kind would be.
 */
@Composable
internal fun ReplacementDialog(
    offer: ReplacementOffer,
    state: PreparationUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val (accept, decline, enabled) = when (offer) {
        is ReplacementOffer.Capture -> Triple("Record bag", "Don't record", state.canScan)
        is ReplacementOffer.Confirm -> Triple("Confirm collection", "Don't confirm", state.canConfirm)
    }
    val stillToCollect = state.snapshot?.preparation?.materials.orEmpty()
        .filter { !it.excluded && !it.isComplete }
        .joinToString(", ") { "${it.name} ${it.remaining.plainText()} ${it.unit}".trim() }
    AlertDialog(
        // An accidental tap outside must neither send nor dismiss: the operator chooses.
        onDismissRequest = {},
        title = { Text("Not received by Station 2", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(offer.prompt(), color = TextPrimary)
                if (offer is ReplacementOffer.Capture && stillToCollect.isNotEmpty()) {
                    Text("Still to collect: $stillToCollect", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (!enabled) {
                    Text(
                        "Not possible right now — resolve the unanswered command or wait for Station 2.",
                        color = WarningOrange, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept, enabled = enabled) { Text(accept) } },
        // The offer stays visible while its own send is in flight; neither button acts until it settles.
        dismissButton = { TextButton(onClick = onDecline, enabled = !state.inFlight) { Text(decline) } },
        containerColor = GraphiteSurface,
    )
}
