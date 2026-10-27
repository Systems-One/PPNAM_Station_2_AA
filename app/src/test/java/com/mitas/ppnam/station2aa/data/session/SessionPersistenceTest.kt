package com.mitas.ppnam.station2aa.data.session

import com.mitas.ppnam.station2aa.domain.model.SessionState
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPersistenceTest {

    private val now = Instant.parse("2026-10-27T12:00:00Z").toEpochMilli()
    private val fifteenMin = 15 * 60_000L

    private fun session(state: SessionState = SessionState.Active, expires: Instant? = Instant.parse("2026-10-27T20:00:00Z")) =
        OperatorSession("sess-1", "OP-1", "Avi", "Worker", state, expires)

    @Test
    fun `a session active within the inactivity timeout comes back after a restart`() =
        assertTrue(SessionPersistence.canRestore(session(), now - 60_000, fifteenMin, now))

    @Test
    fun `a session idle past the inactivity timeout does not come back`() =
        assertFalse(SessionPersistence.canRestore(session(), now - fifteenMin, fifteenMin, now))

    @Test
    fun `with auto sign-out off only expiry limits a restore`() {
        assertTrue(SessionPersistence.canRestore(session(), now - 6 * 3_600_000L, 0, now))
        assertFalse(SessionPersistence.canRestore(session(expires = Instant.ofEpochMilli(now)), now - 1, 0, now))
    }

    @Test
    fun `a closed or expired session never comes back`() {
        assertFalse(SessionPersistence.canRestore(session(state = SessionState.Closed), now - 1, fifteenMin, now))
        assertFalse(SessionPersistence.canRestore(session(expires = Instant.ofEpochMilli(now - 1)), now - 1, fifteenMin, now))
    }

    @Test
    fun `no recorded activity or a clock that ran backwards refuses`() {
        assertFalse(SessionPersistence.canRestore(session(), -1, fifteenMin, now))
        assertFalse(SessionPersistence.canRestore(session(), now + 60_000, fifteenMin, now))
    }
}
