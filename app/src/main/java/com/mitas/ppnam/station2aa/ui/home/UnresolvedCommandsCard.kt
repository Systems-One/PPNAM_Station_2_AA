package com.mitas.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.NoticeTarget
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.openTarget

/**
 * Unanswered commands and what recovery found. Shown only when there is something to say: an
 * unresolved capture is a physical-stock question the operator must not lose sight of.
 */
@Composable
fun UnresolvedCommandsCard(
    pending: List<PendingCommand>,
    notices: List<RecoveryResult>,
    currentOperatorId: String?,
    currentSessionId: String?,
    currentDeviceId: String,
    onCheckAgain: () -> Unit,
    onDismiss: (RecoveryResult) -> Unit,
    onClear: (messageId: String) -> Unit,
    clearError: String?,
    modifier: Modifier = Modifier,
    /** A NotExecuted notice's "Open PREP_…" / "Open job …" (spec §6). */
    onOpen: (NoticeTarget) -> Unit = {},
) {
    if (pending.isEmpty() && notices.isEmpty()) return
    val noticed = notices.map { it.command.messageId }.toSet()
    var confirming by remember { mutableStateOf<PendingCommand?>(null) }
    confirming?.let { command ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Clear ${command.label()}?") },
            text = {
                Text(
                    "Only clear it once it has been reconciled at the station. " +
                        "The scanner records that you cleared it, and when."
                )
            },
            confirmButton = {
                TextButton(onClick = { onClear(command.messageId); confirming = null }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
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
                    Text(notice.noticeText(currentDeviceId), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    val target = notice.openTarget()
                    val openLabel = notice.openActionLabel()
                    if (target != null && openLabel != null) {
                        TextButton(onClick = { onOpen(target) }) { Text(openLabel) }
                    }
                    if (notice !is RecoveryResult.StillUnresolved) {
                        TextButton(onClick = { onDismiss(notice) }) { Text("OK") }
                    }
                }
            }
            pending.filter { it.messageId !in noticed }.forEach { command ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        command.statusLine(currentOperatorId, currentSessionId, currentDeviceId),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (command.canBeCleared(signedIn = currentOperatorId != null)) {
                        TextButton(onClick = { confirming = command }) { Text("Clear") }
                    }
                }
            }
            clearError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (shouldOfferCheckAgain(pending, currentOperatorId, currentSessionId)) {
                OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
            }
        }
    }
}
