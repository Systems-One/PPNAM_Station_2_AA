package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {

    @Test
    fun `every contract session state maps from its wire value`() {
        assertEquals(SessionState.Active, SessionState.fromWire("Active"))
        assertEquals(SessionState.Suspended, SessionState.fromWire("Suspended"))
        assertEquals(SessionState.Closed, SessionState.fromWire("Closed"))
    }

    @Test
    fun `an unknown state degrades to Active rather than locking the operator out`() {
        assertEquals(SessionState.Active, SessionState.fromWire("SomeFutureState"))
    }

    @Test
    fun `an absent state degrades to Active`() {
        assertEquals(SessionState.Active, SessionState.fromWire(null))
    }
}
