package com.mitas.ppnam.station2aa.data.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OperatorSessionHolderTest {

    @Test
    fun `initial session is null`() = runTest {
        val holder = OperatorSessionHolder()
        assertNull(holder.session.first())
    }

    @Test
    fun `set stores the session`() = runTest {
        val holder = OperatorSessionHolder()
        val session = OperatorSession(
            operatorSessionId = "sess-1",
            operatorId = "OP-1",
            operatorName = "Jane Smith",
            role = "Operator"
        )
        holder.set(session)
        assertEquals(session, holder.session.first())
    }

    @Test
    fun `clear removes the session`() = runTest {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear()
        assertNull(holder.session.first())
    }

    @Test
    fun `currentSessionIdOrEmpty returns empty string when no session`() {
        val holder = OperatorSessionHolder()
        assertEquals("", holder.currentSessionIdOrEmpty())
    }

    @Test
    fun `currentSessionIdOrEmpty returns session id when set`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        assertEquals("sess-1", holder.currentSessionIdOrEmpty())
    }
}
