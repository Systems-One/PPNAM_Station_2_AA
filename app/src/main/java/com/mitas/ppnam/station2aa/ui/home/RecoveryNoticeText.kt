package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/** "capture PREP_1" — what the operator did, in the terms the scanner showed them. */
internal fun PendingCommand.label(): String = listOfNotNull(action.ifBlank { "command" }, targetId).joinToString(" ")

/** Spec 4.3: another operator's command — only they, or a manager, can settle it. */
private fun PendingCommand.otherOperatorText(): String =
    "${label()}: sent by operator $operatorId. They must sign in on this scanner to resolve it, or a manager must reconcile it."

/** One line per unanswered command, telling whose move it is (spec 4.3). */
internal fun PendingCommand.statusLine(currentOperatorId: String?, currentSessionId: String?): String = when {
    status == PendingStatus.ManagerReconcile -> "${label()}: needs a manager"
    currentOperatorId == null || operatorId != currentOperatorId -> otherOperatorText()
    // Still in its own session: the job screen retries it identically; recovery would be premature.
    sessionId == currentSessionId -> "${label()}: waiting for Station 2 — reopen the job to retry it"
    else -> "${label()}: waiting for Station 2"
}

/**
 * "Check again" runs recovery, which only covers the signed-in operator's Unresolved commands
 * from an EARLIER session; offering it for anything else would be a button that does nothing.
 */
internal fun shouldOfferCheckAgain(
    pending: List<PendingCommand>,
    currentOperatorId: String?,
    currentSessionId: String?,
): Boolean =
    currentOperatorId != null &&
        pending.any {
            it.status == PendingStatus.Unresolved && it.operatorId == currentOperatorId && it.sessionId != currentSessionId
        }

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
        is RecoveryResult.StillUnresolved ->
            "$what: still unresolved — $message. It is checked again when the scanner reconnects or you tap Check again."
        is RecoveryResult.OtherOperator -> command.otherOperatorText()
    }
}
