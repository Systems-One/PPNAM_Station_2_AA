package com.mitas.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/**
 * Unanswered commands and what recovery found. Shown only when there is something to say: an
 * unresolved capture is a physical-stock question the operator must not lose sight of.
 */
@Composable
fun UnresolvedCommandsCard(
    pending: List<PendingCommand>,
    notices: List<RecoveryResult>,
    currentOperatorId: String?,
    onCheckAgain: () -> Unit,
    onDismiss: (RecoveryResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty() && notices.isEmpty()) return
    val noticed = notices.map { it.command.messageId }.toSet()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending.isNotEmpty()) {
                Text(
                    "${pending.size} unanswered ${if (pending.size == 1) "command" else "commands"}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            notices.forEach { notice ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(notice.noticeText(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (notice !is RecoveryResult.StillUnresolved) {
                        TextButton(onClick = { onDismiss(notice) }) { Text("OK") }
                    }
                }
            }
            pending.filter { it.messageId !in noticed }.forEach { command ->
                Text(command.statusLine(currentOperatorId), style = MaterialTheme.typography.bodySmall)
            }
            if (shouldOfferCheckAgain(pending, currentOperatorId)) {
                OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
            }
        }
    }
}
