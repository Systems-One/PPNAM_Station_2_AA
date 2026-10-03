package com.mitas.ppnam.station2aa.data.settings

import com.mitas.ppnam.station2aa.domain.model.OperatorEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device cache format: a JSON array of `{username, displayName}`, tolerant of garbage. */
class OperatorDirectoryJsonTest {

    @Test
    fun `encode and decode round-trip`() {
        val list = listOf(OperatorEntry("a", "A Person"), OperatorEntry("b", "B"))
        assertEquals(list, OperatorDirectoryJson.decode(OperatorDirectoryJson.encode(list)))
    }

    @Test
    fun `encode writes exactly username and displayName`() {
        assertEquals(
            """[{"username":"a","displayName":"A Person"}]""",
            OperatorDirectoryJson.encode(listOf(OperatorEntry("a", "A Person")))
        )
    }

    @Test
    fun `decode tolerates null, blank and garbage`() {
        assertTrue(OperatorDirectoryJson.decode(null).isEmpty())
        assertTrue(OperatorDirectoryJson.decode("").isEmpty())
        assertTrue(OperatorDirectoryJson.decode("not json").isEmpty())
        assertTrue(OperatorDirectoryJson.decode("{}").isEmpty())
        assertTrue(OperatorDirectoryJson.decode("[1, \"x\"]").isEmpty())
    }

    @Test
    fun `decode applies the same rules as the wire - blank usernames dropped, displayName falls back`() {
        assertEquals(
            listOf(OperatorEntry("op.nodisplay", "op.nodisplay")),
            OperatorDirectoryJson.decode("""[{"username":"","displayName":"Nobody"},{"username":"op.nodisplay"}]""")
        )
    }
}
