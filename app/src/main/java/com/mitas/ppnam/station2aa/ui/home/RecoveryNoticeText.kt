package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/** "capture PREP_1" — what the operator did, in the terms the scanner showed them. */
internal fun PendingCommand.label(): String = listOfNotNull(action.ifBlank { "command" }, targetId).joinToString(" ")

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
