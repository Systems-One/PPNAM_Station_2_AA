package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/** "capture PREP_1" — what the operator did, in the terms the scanner showed them. */
internal fun PendingCommand.label(): String = listOfNotNull(action.ifBlank { "command" }, targetId).joinToString(" ")

/** One line per unanswered command, telling whose move it is (spec 4.3). */
internal fun PendingCommand.statusLine(currentOperatorId: String?): String = when {
    status == PendingStatus.ManagerReconcile -> "${label()}: needs a manager"
    currentOperatorId == null || operatorId != currentOperatorId ->
        "${label()}: sent by operator $operatorId. They must sign in on this scanner to resolve it."
    else -> "${label()}: waiting for Station 2"
}

/** "Check again" only helps when the signed-in operator has an Unresolved command of their own. */
internal fun shouldOfferCheckAgain(pending: List<PendingCommand>, currentOperatorId: String?): Boolean =
    currentOperatorId != null &&
        pending.any { it.status == PendingStatus.Unresolved && it.operatorId == currentOperatorId }

/** Notices belong to the operator who sent the command; nobody else ever sees them. */
internal fun List<RecoveryResult>.visibleTo(operatorId: String?): List<RecoveryResult> =
    if (operatorId == null) emptyList() else filter { it.command.operatorId == operatorId }

internal fun RecoveryResult.noticeText(): String {
    val what = command.label()
    return when (this) {
        is RecoveryResult.Resolved -> when (outcome) {
            RecoveryOutcome.Committed -> "$what: Station 2 had already done this. It was not repeated."
            RecoveryOutcome.Rejected -> "$what: Station 2 rejected it — $message. Re-read before trying again."
            RecoveryOutcome.NotExecuted ->
                "$what: this never happened. Re-read the job, then do it again only if it is still needed."
        }
        is RecoveryResult.NeedsManager -> "$what: a manager must reconcile this at the station. $message"
        is RecoveryResult.StillUnresolved -> "$what: still unresolved — $message. It will be checked again."
        is RecoveryResult.OtherOperator ->
            "$what: sent by operator ${command.operatorId}. They must sign in on this scanner to resolve it."
    }
}
