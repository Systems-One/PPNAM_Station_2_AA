package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.ui.joblookup.countsLine
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary

/**
 * Every stage after collection (ready for a mixer, mixing, produced, completed, cancelled): the
 * previous read-only view. A later release replaces this one composable with the mixer stage.
 */
@Composable
internal fun NonCollectingStage(
    state: PreparationUiState,
    sessionId: String?,
    onRetry: (PendingCommand) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prep = state.snapshot?.preparation
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.prepId, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                if (prep != null) {
                    val detail = state.snapshot?.lookup?.detail
                    Text(
                        listOf(prep.jobId, detail?.product.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(onClick = {}, label = { Text(prep.stageRaw) })
                        Text(
                            "${prep.mixCount} ${if (prep.mixCount == 1) "mix" else "mixes"}",
                            color = TextMuted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    detail?.mixProgress?.let {
                        Text(it.countsLine(), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
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
        if (state.blocking.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HoldBanners(state, sessionId, onRetry) } }
        state.message?.let { item { InfoLine(it) } }
        if (prep == null) {
            if (state.loading) item { CircularProgressIndicator() }
        } else {
            item { HorizontalDivider() }
            items(prep.materials, key = { "material-${it.code}" }) { MaterialRow(it) }
            when (state.stage) {
                PrepStage.Completed, PrepStage.Cancelled -> item { Text("${prep.stageRaw} — history only", color = TextMuted) }
                PrepStage.Unknown, null -> Unit
                else -> item {
                    Text(
                        "Ingredients confirmed — next: scan a permitted mixer (coming in a later release)",
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialRow(material: PrepMaterial) {
    val muted = material.excluded
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GraphiteSurface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(material.name, color = if (muted) TextMuted else TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(material.code, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Text(
                material.progressLine(),
                color = if (muted) TextMuted else TextPrimary, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
