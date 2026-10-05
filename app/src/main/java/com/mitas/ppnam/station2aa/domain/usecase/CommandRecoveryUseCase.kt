package com.mitas.ppnam.station2aa.domain.usecase

import androidx.annotation.VisibleForTesting
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RecoverRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryOutcome {
    /** The original succeeded. Never perform it again. */
    Committed,

    /** The original was definitely rejected. Review and correct with a new intent. */
    Rejected,

    /** The original never ran, and its id is now sealed. Re-read, and ask before doing it again. */
    NotExecuted;

    companion object {
        fun fromWire(value: String): RecoveryOutcome? = when (value) {
            "committed" -> Committed
            "rejected" -> Rejected
            "not_executed" -> NotExecuted
            else -> null
        }
    }
}

sealed interface RecoveryResult {
    val command: PendingCommand

    data class Resolved(
        override val command: PendingCommand,
        val outcome: RecoveryOutcome,
        /** The original operation's saved result message. */
        val message: String,
        val snapshot: Rev2Snapshot,
    ) : RecoveryResult

    data class StillUnresolved(override val command: PendingCommand, val message: String) : RecoveryResult
    data class NeedsManager(override val command: PendingCommand, val message: String) : RecoveryResult
    data class OtherOperator(override val command: PendingCommand) : RecoveryResult
}

/**
 * Contract §9: after a re-login (or a message-id conflict), ask Station 2 what became of an
 * unanswered command. The recovery itself performs no physical operation and is safe to repeat,
 * so every attempt uses a new message id via [MqttRepository.request].
 */
@Singleton
class CommandRecoveryUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val outbox: CommandOutbox,
    private val sessionHolder: OperatorSessionHolder,
) {

    /** Outbox writes fsync; keep them off the caller's (Main) thread. Test seam. */
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    suspend fun recover(command: PendingCommand): RecoveryResult {
        val session = sessionHolder.session.value
            ?: return RecoveryResult.StillUnresolved(command, "Sign in to resolve this command")
        if (session.operatorId != command.operatorId) return RecoveryResult.OtherOperator(command)
        if (command.status == PendingStatus.ManagerReconcile) return RecoveryResult.NeedsManager(command, MANAGER_MESSAGE)
        // Station 2 answers recovery per device: asked from another scanner, it would say
        // not_executed about something that may have happened there.
        if (!command.isFromDevice(mqttRepository.deviceId)) {
            withContext(ioDispatcher) { outbox.markManagerReconcile(command.messageId) }
            return RecoveryResult.NeedsManager(command, OTHER_DEVICE_MESSAGE)
        }

        val outcome = mqttRepository.request(
            requestType = command.requestType,
            responseType = command.responseType,
            payload = Rev2RecoverRequest(
                targetId = command.targetId,
                originalMessageId = command.messageId,
                originalRequestFingerprint = command.fingerprint,
            ),
            responseClass = Rev2Snapshot::class.java,
        )
        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val recovery = outcome.body.recovery
                val kind = recovery?.outcome?.let(RecoveryOutcome::fromWire)
                if (recovery == null || kind == null || recovery.originalMessageId != command.messageId) {
                    RecoveryResult.StillUnresolved(command, "Station 2's recovery reply was incomplete — try again")
                } else {
                    withContext(ioDispatcher) { outbox.remove(command.messageId) }
                    RecoveryResult.Resolved(command, kind, recovery.result?.message.orEmpty(), outcome.body)
                }
            }
            is MqttOutcome.Rejected -> when (outcome.error) {
                ErrorCode.RECEIPT_OWNER_MISMATCH, ErrorCode.RECEIPT_RECOVERY_UNAVAILABLE -> {
                    withContext(ioDispatcher) { outbox.markManagerReconcile(command.messageId) }
                    RecoveryResult.NeedsManager(command, outcome.operatorMessage ?: MANAGER_MESSAGE)
                }
                else -> RecoveryResult.StillUnresolved(
                    command, outcome.operatorMessage ?: "Station 2 could not recover this command yet",
                )
            }
            is MqttOutcome.NoResponse -> RecoveryResult.StillUnresolved(command, outcome.kind.message())
        }
    }

    private companion object {
        const val MANAGER_MESSAGE = "A manager must reconcile this at the station before you scan again"
        const val OTHER_DEVICE_MESSAGE = "Sent from another scanner — a manager must reconcile this"
    }
}
