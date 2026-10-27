package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent

/**
 * Spec §6: a capture or confirm that Station 2 never ran (recovery `not_executed`, or a direct
 * `receipt_sealed` reply) and can never run later. The operator may ask for it again — only by an
 * explicit tap, which sends a NEW command with a new message id (Review Focus 6). Nothing here
 * sends anything.
 */
sealed interface ReplacementOffer {
    /** The coordinator notice it came from; null when it came from a direct `receipt_sealed` reply. */
    val notice: RecoveryResult?
    val prepId: String

    data class Capture(override val notice: RecoveryResult?, override val prepId: String, val intent: CaptureIntent) :
        ReplacementOffer

    data class Confirm(override val notice: RecoveryResult?, override val prepId: String) : ReplacementOffer

    companion object {
        /** Only NotExecuted capture/confirm become offers. A prepare never does (spec §6). */
        fun from(result: RecoveryResult): ReplacementOffer? {
            if (result !is RecoveryResult.Resolved || result.outcome != RecoveryOutcome.NotExecuted) return null
            return of(result.command, result)
        }

        /** A retry of [command] was answered `receipt_sealed`: the same offer, without a notice. */
        fun fromSealed(command: PendingCommand?): ReplacementOffer? = command?.let { of(it, null) }

        private fun of(command: PendingCommand, notice: RecoveryResult?): ReplacementOffer? {
            val prepId = command.targetId?.takeIf { it.isNotBlank() } ?: return null
            return when (command.action) {
                ACTION_CAPTURE -> CaptureIntent.fromPayload(command.payload)?.let { Capture(notice, prepId, it) }
                ACTION_CONFIRM -> Confirm(notice, prepId)
                else -> null
            }
        }
    }
}

private const val ACTION_CAPTURE = "capture"
private const val ACTION_CONFIRM = "confirm"
private const val ACTION_PREPARE = "prepare"
/** Targets its PREP, so it opens (and blocks) that PREP; never offered as a replacement. */
private const val ACTION_ADJUST_INGREDIENT = "adjust_ingredient"
/** Target the PREP (opens and blocks it); never offered as a replacement. */
private const val ACTION_MACHINE_START = "machine_start"
private const val ACTION_MACHINE_FINISH = "machine_finish"

/** Home: where a NotExecuted notice's "Open" goes. */
sealed interface NoticeTarget {
    data class Prep(val id: String) : NoticeTarget
    data class Job(val jobCard: String) : NoticeTarget
    /** A Rajoo-family command: its job card's Rajoo screen. */
    data class Rajoo(val jobCard: String) : NoticeTarget
}

/**
 * NotExecuted only: a capture/confirm opens its PREP (where the replacement offer appears); a
 * prepare opens its job's Prepare screen with a fresh read — nothing is auto-filled or resent.
 */
fun RecoveryResult.openTarget(): NoticeTarget? {
    if (this !is RecoveryResult.Resolved || outcome != RecoveryOutcome.NotExecuted) return null
    val target = command.targetId?.takeIf { it.isNotBlank() } ?: return null
    if (command.requestType == RajooUseCase.REQUEST) return NoticeTarget.Rajoo(target)
    return when (command.action) {
        ACTION_CAPTURE, ACTION_CONFIRM, ACTION_ADJUST_INGREDIENT, ACTION_MACHINE_START, ACTION_MACHINE_FINISH -> NoticeTarget.Prep(target)
        ACTION_PREPARE -> NoticeTarget.Job(target)
        else -> null
    }
}
