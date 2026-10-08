package com.mitas.ppnam.station2aa.data.mqtt.outbox

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileClearedCommandLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: String) = ClearedCommandRecord(
        clearedByOperatorId = "OP-2",
        clearedByOperatorName = "Sam",
        clearedBySessionId = "S-9",
        clearedOnDeviceId = "scanner_1",
        clearedAtUtc = "2026-10-08T07:00:00.000000Z",
        command = PendingCommand(
            messageId = id, action = "capture", targetId = "PREP_1", payload = """{"code":"TAG-1"}""",
            fingerprint = "AB", operatorId = "OP-1", status = PendingStatus.ManagerReconcile,
        ),
    )

    @Test
    fun `every clear is kept, in order, with the exact command, across a restart`() {
        val file = tmp.root.resolve("audit/cleared-commands.jsonl")
        FileClearedCommandLog(file).apply {
            record(record("a"))
            record(record("b"))
        }
        val reopened = FileClearedCommandLog(file)
        assertEquals(listOf(record("a"), record("b")), reopened.entries())
        reopened.record(record("c"))
        assertEquals(listOf("a", "b", "c"), FileClearedCommandLog(file).entries().map { it.command.messageId })
    }
}
