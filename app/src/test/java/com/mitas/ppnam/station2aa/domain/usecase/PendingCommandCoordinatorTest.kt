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

    /** Records in memory; [failWith] makes the next record throw, like a full disk. */
    private class FakeClearedLog : com.mitas.ppnam.station2aa.data.mqtt.outbox.ClearedCommandLog {
        val records = mutableListOf<com.mitas.ppnam.station2aa.data.mqtt.outbox.ClearedCommandRecord>()
        var failWith: Exception? = null
        override fun record(entry: com.mitas.ppnam.station2aa.data.mqtt.outbox.ClearedCommandRecord) {
            failWith?.let { throw it }
            records += entry
        }
        override fun entries() = records.toList()
    }

    private fun identity() = mock<com.mitas.ppnam.station2aa.data.identity.DeviceIdentity>().also {
        whenever(it.deviceId()).thenReturn("scanner_1")
    }

    private fun reconcile(id: String, operator: String = "OP-1") =
        cmd(id, operator, "old").copy(status = com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus.ManagerReconcile)

    @Test
    fun `any signed-in operator can clear a command awaiting reconciliation, and it is recorded first`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(reconcile("a", operator = "OP-1")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("S-9", "OP-2", "Sam", "")) }
        val log = FakeClearedLog()
        val coordinator = PendingCommandCoordinator(outbox, mock(), sessions, log, identity())
        coordinator.nowFn = { java.time.Instant.parse("2026-10-08T07:00:00Z") }

        val result = coordinator.clearReconciled("a")

        assertEquals(ClearResult.Cleared, result)
        assertTrue(outbox.commands.value.isEmpty())
        val entry = log.records.single()
        assertEquals(reconcile("a", operator = "OP-1"), entry.command)
        assertEquals("OP-2", entry.clearedByOperatorId)
        assertEquals("Sam", entry.clearedByOperatorName)
        assertEquals("S-9", entry.clearedBySessionId)
        assertEquals("scanner_1", entry.clearedOnDeviceId)
        assertEquals("2026-10-08T07:00:00.000000Z", entry.clearedAtUtc)
    }

    @Test
    fun `a clear that cannot be recorded removes nothing`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(reconcile("a")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("S-9", "OP-2", "Sam", "")) }
        val log = FakeClearedLog().apply { failWith = java.io.IOException("disk full") }

        val result = PendingCommandCoordinator(outbox, mock(), sessions, log, identity()).clearReconciled("a")

        assertTrue(result is ClearResult.NotRecorded)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `nobody signed in, an unknown id, or a command still recoverable here is not cleared`() = runTest {
        val outbox = InMemoryCommandOutbox().apply {
            save(reconcile("a"))
            save(cmd("b", "OP-1", "old"))   // Unresolved: retry or recover tells the truth; never discard it
        }
        val log = FakeClearedLog()
        val signedOut = PendingCommandCoordinator(outbox, mock(), OperatorSessionHolder(), log, identity())
        assertEquals(ClearResult.NotSignedIn, signedOut.clearReconciled("a"))

        val sessions = OperatorSessionHolder().apply { set(OperatorSession("S-9", "OP-2", "Sam", "")) }
        val coordinator = PendingCommandCoordinator(outbox, mock(), sessions, log, identity())
        assertEquals(ClearResult.NotFound, coordinator.clearReconciled("zzz"))
        assertEquals(ClearResult.NotAwaitingReconciliation, coordinator.clearReconciled("b"))

        assertEquals(2, outbox.commands.value.size)
        assertTrue(log.records.isEmpty())
    }

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
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions, FakeClearedLog(), identity())

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
        PendingCommandCoordinator(outbox, recovery, OperatorSessionHolder(), FakeClearedLog(), identity()).recoverForCurrentOperator()
        verify(recovery, never()).recover(any())
    }

    @Test
    fun `another operator signing in does not inherit earlier notices`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("s1", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer { RecoveryResult.StillUnresolved(it.getArgument(0), "later") }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions, FakeClearedLog(), identity())
        coordinator.recoverForCurrentOperator()
        assertEquals(1, coordinator.notices.value.size)

        sessions.set(OperatorSession("s2", "OP-2", "Other", "Worker"))
        coordinator.recoverForCurrentOperator()

        assertTrue(coordinator.notices.value.isEmpty())
    }

    @Test
    fun `a recovery that throws becomes a notice and the next command is still recovered`() = runTest {
        val outbox = InMemoryCommandOutbox().apply {
            save(cmd("a", "OP-1", "old"))
            save(cmd("b", "OP-1", "old"))
        }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(cmd("a", "OP-1", "old"))).thenAnswer { throw java.io.IOException("disk full") }
        whenever(recovery.recover(cmd("b", "OP-1", "old"))).thenAnswer {
            RecoveryResult.Resolved(it.getArgument(0), RecoveryOutcome.Committed, "done", Rev2Snapshot())
        }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions, FakeClearedLog(), identity())

        coordinator.recoverForCurrentOperator()

        val byId = coordinator.notices.value.associateBy { it.command.messageId }
        assertEquals(setOf("a", "b"), byId.keys)
        val failed = byId.getValue("a") as RecoveryResult.StillUnresolved
        assertEquals("Could not check this command: disk full", failed.message)
        assertTrue(byId.getValue("b") is RecoveryResult.Resolved)
    }

    @Test
    fun `dismiss removes a notice`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer { RecoveryResult.StillUnresolved(it.getArgument(0), "later") }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions, FakeClearedLog(), identity())
        coordinator.recoverForCurrentOperator()
        coordinator.dismiss(coordinator.notices.value.single())
        assertTrue(coordinator.notices.value.isEmpty())
    }
}
