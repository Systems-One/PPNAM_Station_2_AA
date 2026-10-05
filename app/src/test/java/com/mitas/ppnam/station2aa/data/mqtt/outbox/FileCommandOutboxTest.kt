package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.RequestFingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCommandOutboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun command(id: String = "11111111-1111-1111-1111-111111111111"): PendingCommand {
        // Non-ASCII and escapes on purpose: the payload must survive a disk round trip byte-for-byte.
        val payload = """{"action":"capture","code":"TAG-é\"x","schemaVersion":"rev2.1","messageId":"$id"}"""
        return PendingCommand(
            messageId = id,
            requestType = "rev2_general_requested",
            responseType = "rev2_general_result",
            action = "capture",
            targetId = "PREP_1",
            payload = payload,
            fingerprint = RequestFingerprint.of(payload.toByteArray(Charsets.UTF_8)),
            operatorId = "OP-1",
            sessionId = "S-1",
            createdAtUtc = "2026-10-05T08:00:00.000000Z",
        )
    }

    @Test
    fun `a new outbox on the same directory reloads the identical command`() {
        val dir = tmp.newFolder("outbox")
        val original = command()
        FileCommandOutbox(dir).save(original)

        val reloaded = FileCommandOutbox(dir).commands.value.single()
        assertEquals(original, reloaded)
        assertArrayEquals(original.payloadBytes, reloaded.payloadBytes)
        assertEquals(PendingStatus.Unresolved, reloaded.status)
    }

    @Test
    fun `remove deletes the file`() {
        val dir = tmp.newFolder("outbox")
        val outbox = FileCommandOutbox(dir)
        outbox.save(command())
        outbox.remove(command().messageId)
        assertTrue(outbox.commands.value.isEmpty())
        assertTrue(FileCommandOutbox(dir).commands.value.isEmpty())
    }

    @Test
    fun `markManagerReconcile persists`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).apply { save(command()); markManagerReconcile(command().messageId) }
        assertEquals(PendingStatus.ManagerReconcile, FileCommandOutbox(dir).commands.value.single().status)
    }

    @Test
    fun `saving the same messageId twice keeps one entry`() {
        val outbox = FileCommandOutbox(tmp.newFolder("outbox"))
        outbox.save(command())
        outbox.save(command())
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `an unreadable file becomes a ManagerReconcile entry`() {
        val dir = tmp.newFolder("outbox")
        File(dir, "22222222-2222-2222-2222-222222222222.json").writeText("{not json")
        val entry = FileCommandOutbox(dir).commands.value.single()
        assertEquals("22222222-2222-2222-2222-222222222222", entry.messageId)
        assertEquals(PendingStatus.ManagerReconcile, entry.status)
    }

    @Test
    fun `a payload that no longer matches its fingerprint becomes ManagerReconcile`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).save(command().copy(fingerprint = "0".repeat(64)))
        assertEquals(PendingStatus.ManagerReconcile, FileCommandOutbox(dir).commands.value.single().status)
    }

    @Test
    fun `a damaged orphan tmp file is kept and surfaced for a manager`() {
        val dir = tmp.newFolder("outbox")
        val id = "33333333-3333-3333-3333-333333333333"
        val stray = File(dir, "$id.json.tmp").apply { writeText("{}") }
        val entry = FileCommandOutbox(dir).commands.value.single()
        assertEquals(id, entry.messageId)
        assertEquals(PendingStatus.ManagerReconcile, entry.status)
        assertTrue(stray.exists())
    }

    @Test
    fun `a valid orphan tmp file is promoted and reloads as Unresolved`() {
        // The move returned and the command was published, but the rename did not survive a power cut.
        val dir = tmp.newFolder("outbox")
        val original = command()
        FileCommandOutbox(dir).save(original)
        val json = File(dir, "${original.messageId}.json")
        val orphan = File(dir, "${original.messageId}.json.tmp")
        json.renameTo(orphan)

        val reloaded = FileCommandOutbox(dir).commands.value.single()
        assertEquals(original, reloaded)
        assertEquals(PendingStatus.Unresolved, reloaded.status)
        assertTrue(json.exists())
        assertTrue(!orphan.exists())
    }

    @Test
    fun `a garbage orphan tmp file becomes ManagerReconcile and its bytes are untouched`() {
        val dir = tmp.newFolder("outbox")
        val id = "55555555-5555-5555-5555-555555555555"
        val orphan = File(dir, "$id.json.tmp").apply { writeText("{garbage") }
        val before = orphan.readBytes()
        val outbox = FileCommandOutbox(dir)
        val entry = outbox.commands.value.single()
        assertEquals(id, entry.messageId)
        assertEquals(PendingStatus.ManagerReconcile, entry.status)
        outbox.markManagerReconcile(id)
        assertArrayEquals(before, orphan.readBytes())
        assertArrayEquals(before, File(dir, "$id.json.tmp").readBytes())
    }

    @Test
    fun `a tmp file beside a valid json is removed and the json loads`() {
        val dir = tmp.newFolder("outbox")
        val original = command()
        FileCommandOutbox(dir).save(original)
        val stray = File(dir, "${original.messageId}.json.tmp").apply { writeText("{half a write") }
        val reloaded = FileCommandOutbox(dir).commands.value.single()
        assertEquals(original, reloaded)
        assertTrue(!stray.exists())
    }

    @Test
    fun `the in-memory outbox behaves the same for save and remove`() {
        val outbox = InMemoryCommandOutbox()
        outbox.save(command())
        outbox.markManagerReconcile(command().messageId)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
        outbox.remove(command().messageId)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `marking an unreadable entry leaves its file untouched`() {
        val dir = tmp.newFolder("outbox")
        val id = "22222222-2222-2222-2222-222222222222"
        val file = File(dir, "$id.json").apply { writeText("{not json") }
        val before = file.readBytes()
        FileCommandOutbox(dir).markManagerReconcile(id)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `marking a fingerprint-mismatch entry leaves its file untouched`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).save(command().copy(fingerprint = "0".repeat(64)))
        val file = File(dir, "${command().messageId}.json")
        val before = file.readBytes()
        FileCommandOutbox(dir).markManagerReconcile(command().messageId)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `a file with a null payload does not break loading the others`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).save(command())
        val badId = "44444444-4444-4444-4444-444444444444"
        File(dir, "$badId.json").writeText("""{"messageId":"$badId","payload":null}""")
        val byId = FileCommandOutbox(dir).commands.value.associateBy { it.messageId }
        assertEquals(2, byId.size)
        assertEquals(PendingStatus.ManagerReconcile, byId.getValue(badId).status)
        assertEquals(PendingStatus.Unresolved, byId.getValue(command().messageId).status)
    }

    @Test
    fun `a file with an unknown status is ManagerReconcile`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).save(command())
        val file = File(dir, "${command().messageId}.json")
        file.writeText(file.readText().replace("\"Unresolved\"", "\"Bogus\""))
        assertEquals(PendingStatus.ManagerReconcile, FileCommandOutbox(dir).commands.value.single().status)
    }
}
