package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    val pending: StateFlow<List<PendingCommand>> get() = outbox.commands

    private val _notices = MutableStateFlow<List<RecoveryResult>>(emptyList())
    val notices: StateFlow<List<RecoveryResult>> = _notices.asStateFlow()

    private val mutex = Mutex()

    suspend fun recoverForCurrentOperator() = mutex.withLock {
        val session = sessionHolder.session.value ?: return@withLock
        val due = outbox.commands.value.filter {
            it.operatorId == session.operatorId &&
                it.status == PendingStatus.Unresolved &&
                it.sessionId != session.operatorSessionId
        }
        for (command in due) {
            val result = recovery.recover(command)
            _notices.update { list -> list.filterNot { it.command.messageId == command.messageId } + result }
        }
    }

    fun dismiss(notice: RecoveryResult) = _notices.update { it - notice }
}
