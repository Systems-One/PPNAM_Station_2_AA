package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCommandCoordinatorTest {

    private fun cmd(id: String, operator: String, session: String) =
        PendingCommand(messageId = id, operatorId = operator, sessionId = session, action = "capture")

    @Test
    fun `only the signed-in operator's commands from earlier sessions are recovered`() = runTest {
        val outbox = InMemoryCommandOutbox().apply {
            save(cmd("a", "OP-1", "old"))
            save(cmd("b", "OP-2", "old"))
            save(cmd("c", "OP-1", "current"))   // still in its own session: the owning screen retries it
        }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer {
            RecoveryResult.Resolved(it.getArgument(0), RecoveryOutcome.Committed, "done", Rev2Snapshot())
        }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions)

        coordinator.recoverForCurrentOperator()

        verify(recovery).recover(cmd("a", "OP-1", "old"))
        verify(recovery, never()).recover(cmd("b", "OP-2", "old"))
        verify(recovery, never()).recover(cmd("c", "OP-1", "current"))
        assertEquals(listOf("a"), coordinator.notices.value.map { it.command.messageId })
    }

    @Test
    fun `with nobody signed in nothing is sent`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val recovery = mock<CommandRecoveryUseCase>()
        PendingCommandCoordinator(outbox, recovery, OperatorSessionHolder()).recoverForCurrentOperator()
        verify(recovery, never()).recover(any())
    }

    @Test
    fun `another operator signing in does not inherit earlier notices`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("s1", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer { RecoveryResult.StillUnresolved(it.getArgument(0), "later") }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions)
        coordinator.recoverForCurrentOperator()
        assertEquals(1, coordinator.notices.value.size)

        sessions.set(OperatorSession("s2", "OP-2", "Other", "Worker"))
        coordinator.recoverForCurrentOperator()

        assertTrue(coordinator.notices.value.isEmpty())
    }

    @Test
    fun `dismiss removes a notice`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer { RecoveryResult.StillUnresolved(it.getArgument(0), "later") }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions)
        coordinator.recoverForCurrentOperator()
        coordinator.dismiss(coordinator.notices.value.single())
        assertTrue(coordinator.notices.value.isEmpty())
    }
}
