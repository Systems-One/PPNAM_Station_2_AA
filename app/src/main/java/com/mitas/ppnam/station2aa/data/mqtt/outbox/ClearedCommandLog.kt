package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A command someone cleared from the outbox after it was reconciled at the station. The cleared
 * [command] is kept whole (exact payload and fingerprint): it may stand for a bag that was
 * physically captured, so clearing it must never lose the evidence.
 */
data class ClearedCommandRecord(
    val clearedByOperatorId: String = "",
    val clearedByOperatorName: String = "",
    val clearedBySessionId: String = "",
    val clearedOnDeviceId: String = "",
    val clearedAtUtc: String = "",
    val command: PendingCommand = PendingCommand(),
    /**
     * The outbox file exactly as it was on disk, when there was one. For a damaged entry [command]
     * is only a stub, so these bytes are the sole remaining evidence of what was sent.
     */
    val rawOutboxEntry: String? = null,
)

/** Every clear, kept on the scanner. `record` must finish before the command leaves the outbox. */
interface ClearedCommandLog {
    fun record(entry: ClearedCommandRecord)
    fun entries(): List<ClearedCommandRecord>
}

/** Append-only JSON Lines in [file], fsynced per record. Nothing ever rewrites or trims it. */
class FileClearedCommandLog(
    private val file: File,
    private val gson: Gson = WireJson.gson,
) : ClearedCommandLog {

    private val lock = Any()

    override fun record(entry: ClearedCommandRecord) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            // A crash mid-append leaves a line with no newline; start a fresh line so the torn one
            // costs only itself and never swallows this record.
            val torn = file.length() > 0 &&
                RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
            FileOutputStream(file, true).use { out ->
                out.write(((if (torn) "\n" else "") + gson.toJson(entry) + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
    }

    override fun entries(): List<ClearedCommandRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { gson.fromJson(line, ClearedCommandRecord::class.java) }.getOrNull() }
    }
}
