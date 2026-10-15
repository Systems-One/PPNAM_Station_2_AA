package com.mitas.ppnam.station2aa.domain.usecase

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.MqttSchema
import com.mitas.ppnam.station2aa.data.mqtt.outbox.ClearedCommandLog
import com.mitas.ppnam.station2aa.data.mqtt.outbox.ClearedCommandRecord
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract §9 step 1–3: once the same operator is signed in again, recover each command they left
 * unresolved in an EARLIER session. A command from the current session is the owning screen's to
 * retry identically. Another operator's command is never sent; it is shown so they can come back.
 */
@Singleton
class PendingCommandCoordinator @Inject constructor(
    private val outbox: CommandOutbox,
    private val recovery: CommandRecoveryUseCase,
    private val sessionHolder: OperatorSessionHolder,
    private val clearedLog: ClearedCommandLog,
    private val deviceIdentity: DeviceIdentity,
) {
    /** Test seam for the clock. */
    @VisibleForTesting
    internal var nowFn: () -> Instant = { Instant.now() }
    val pending: StateFlow<List<PendingCommand>> get() = outbox.commands

    private val _notices = MutableStateFlow<List<RecoveryResult>>(emptyList())
    val notices: StateFlow<List<RecoveryResult>> = _notices.asStateFlow()

    private val mutex = Mutex()

    suspend fun recoverForCurrentOperator() = mutex.withLock {
        val session = sessionHolder.session.value
        // Notices are the sending operator's: never carry them across a sign-in change.
        _notices.update { list -> list.filter { it.command.operatorId == session?.operatorId } }
        if (session == null) return@withLock
        val due = outbox.commands.value.filter {
            it.operatorId == session.operatorId &&
                it.status == PendingStatus.Unresolved &&
                it.sessionId != session.operatorSessionId
        }
        for (command in due) {
            // One failure (a disk error, say) must not stop the rest being checked.
            val result = try {
                recovery.recover(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecoveryResult.StillUnresolved(command, "Could not check this command: ${e.message}")
            }
            _notices.update { list -> list.filterNot { it.command.messageId == command.messageId } + result }
        }
    }

    fun dismiss(notice: RecoveryResult) = _notices.update { it - notice }

    /**
     * Removes a command the scanner can no longer settle itself ([PendingStatus.ManagerReconcile])
     * once it has been reconciled at the station. There are no roles: anyone signed in may clear.
     * Every clear is recorded — who, when, on which scanner, and the exact command — BEFORE the
     * command leaves the outbox; if it can't be recorded, nothing is removed.
     */
    suspend fun clearReconciled(messageId: String): ClearResult = mutex.withLock {
        val session = sessionHolder.session.value ?: return@withLock ClearResult.NotSignedIn
        val command = outbox.commands.value.firstOrNull { it.messageId == messageId }
            ?: return@withLock ClearResult.NotFound
        // An Unresolved command is still settled truthfully by retry or recovery: never discard it.
        if (command.status != PendingStatus.ManagerReconcile) return@withLock ClearResult.NotAwaitingReconciliation
        try {
            clearedLog.record(
                ClearedCommandRecord(
                    clearedByOperatorId = session.operatorId,
                    clearedByOperatorName = session.operatorName,
                    clearedBySessionId = session.operatorSessionId,
                    clearedOnDeviceId = deviceIdentity.deviceId(),
                    clearedAtUtc = MqttSchema.formatTimestamp(nowFn()),
                    command = command,
                    // A damaged entry is only a stub in memory: keep the bytes on disk as evidence.
                    rawOutboxEntry = outbox.rawEntry(messageId),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not record clearing $messageId; kept in the outbox", e)
            return@withLock ClearResult.NotRecorded(e)
        }
        outbox.remove(messageId)
        _notices.update { list -> list.filterNot { it.command.messageId == messageId } }
        Log.w(TAG, "Operator ${session.operatorId} cleared ${command.action} ${command.targetId} ($messageId), sent by ${command.operatorId}")
        ClearResult.Cleared
    }

    private companion object {
        const val TAG = "PendingCommands"
    }
}

sealed interface ClearResult {
    data object Cleared : ClearResult
    data object NotSignedIn : ClearResult
    data object NotFound : ClearResult
    data object NotAwaitingReconciliation : ClearResult
    /** The clear could not be recorded, so the command was kept. */
    data class NotRecorded(val cause: Exception) : ClearResult
}
