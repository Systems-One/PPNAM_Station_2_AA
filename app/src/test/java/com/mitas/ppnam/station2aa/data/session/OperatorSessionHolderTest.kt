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

    @Test
    fun `clearIf clears a matching session and returns true`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        assertTrue(holder.clearIf("sess-1"))
        assertNull(holder.session.value)
    }

    @Test
    fun `clearIf leaves a different session untouched and returns false`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-2", "OP-1", "Jane Smith", "Operator"))
        assertFalse(holder.clearIf("sess-1"))
        assertEquals("sess-2", holder.session.value?.operatorSessionId)
    }

    @Test
    fun `clearIf with no session returns false`() {
        val holder = OperatorSessionHolder()
        assertFalse(holder.clearIf("sess-1"))
        assertNull(holder.session.value)
    }

    @Test
    fun `clear with a reason records it and set forgets it`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear("Signed out after 15 minutes of inactivity.")
        assertNull(holder.session.value)
        assertEquals("Signed out after 15 minutes of inactivity.", holder.signedOutReason.value)
        holder.set(OperatorSession("sess-2", "OP-1", "Jane Smith", "Operator"))
        assertNull(holder.signedOutReason.value)
    }

    @Test
    fun `a manual logout leaves no signed-out reason`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear()
        assertNull(holder.signedOutReason.value)
    }

    @Test
    fun `consumeSignedOutReason hands the reason over exactly once`() {
        val holder = OperatorSessionHolder()
        holder.clear("Signed out after 1 minute of inactivity.")
        assertEquals("Signed out after 1 minute of inactivity.", holder.consumeSignedOutReason())
        assertNull(holder.consumeSignedOutReason())
        assertNull(holder.signedOutReason.value)
    }
}
