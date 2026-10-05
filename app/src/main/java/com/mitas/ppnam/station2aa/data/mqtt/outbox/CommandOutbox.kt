package com.mitas.ppnam.station2aa.data.mqtt.outbox

import android.util.Log
import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.mqtt.RequestFingerprint
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Unanswered commands, kept until Station 2's outcome is known. `save` must finish before publishing. */
interface CommandOutbox {
    val commands: StateFlow<List<PendingCommand>>
    fun save(command: PendingCommand)
    fun remove(messageId: String)
    fun markManagerReconcile(messageId: String)
}

/** For tests and as the transport's constructor default; production binds [FileCommandOutbox]. */
class InMemoryCommandOutbox : CommandOutbox {
    private val _commands = MutableStateFlow<List<PendingCommand>>(emptyList())
    override val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()
    override fun save(command: PendingCommand) =
        _commands.update { list -> list.filterNot { it.messageId == command.messageId } + command }
    override fun remove(messageId: String) = _commands.update { list -> list.filterNot { it.messageId == messageId } }
    override fun markManagerReconcile(messageId: String) = _commands.update { list ->
        list.map { if (it.messageId == messageId) it.copy(status = PendingStatus.ManagerReconcile) else it }
    }
}

/**
 * One JSON file per command in [dir], named by messageId (a UUID, so always a safe file name).
 * Writes go to a temp file, are fsynced, then atomically moved over the target: a crash leaves the
 * old file or the new one, never half of one. A file that cannot be read, or whose payload no
 * longer matches its fingerprint, is surfaced as [PendingStatus.ManagerReconcile] — never dropped,
 * because it may stand for a bag that was physically captured.
 */
class FileCommandOutbox(
    private val dir: File,
    private val gson: Gson = WireJson.gson,
) : CommandOutbox {

    private val lock = Any()
    private val _commands = MutableStateFlow(load())
    override val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()

    override fun save(command: PendingCommand) {
        synchronized(lock) {
            write(command)
            _commands.update { list -> list.filterNot { it.messageId == command.messageId } + command }
        }
    }

    override fun remove(messageId: String) {
        synchronized(lock) {
            fileFor(messageId).delete()
            _commands.update { list -> list.filterNot { it.messageId == messageId } }
        }
    }

    override fun markManagerReconcile(messageId: String) {
        synchronized(lock) {
            val current = _commands.value.firstOrNull { it.messageId == messageId } ?: return
            // Already flagged (possibly a stub for a damaged file): writing would overwrite the only copy.
            if (current.status == PendingStatus.ManagerReconcile) return
            save(current.copy(status = PendingStatus.ManagerReconcile))
        }
    }

    private fun fileFor(messageId: String) = File(dir, "$messageId$SUFFIX")

    private fun write(command: PendingCommand) {
        dir.mkdirs()
        val target = fileFor(command.messageId)
        val temp = File(dir, "${command.messageId}$SUFFIX$TEMP_SUFFIX")
        FileOutputStream(temp).use { out ->
            out.write(gson.toJson(command).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(): List<PendingCommand> {
        val files = dir.listFiles() ?: return emptyList()
        // A temp file is a save that never completed, so its command was never published.
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        return files.filter { it.name.endsWith(SUFFIX) }.map { file ->
            val id = file.name.removeSuffix(SUFFIX)
            val stub = PendingCommand(messageId = id, status = PendingStatus.ManagerReconcile)
            try {
                val parsed = gson.fromJson(file.readText(Charsets.UTF_8), PendingCommand::class.java)
                // Gson can write JSON nulls into non-null Kotlin fields, so check at runtime.
                val required = listOf<Any?>(
                    parsed, parsed?.messageId, parsed?.requestType, parsed?.responseType, parsed?.action,
                    parsed?.payload, parsed?.fingerprint, parsed?.operatorId, parsed?.sessionId,
                    parsed?.createdAtUtc, parsed?.status,
                )
                when {
                    required.any { it == null } || parsed.messageId != id -> stub
                    RequestFingerprint.of(parsed.payloadBytes) != parsed.fingerprint ->
                        parsed.copy(status = PendingStatus.ManagerReconcile)
                    else -> parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable outbox entry $id", e)
                stub
            }
        }.sortedBy { it.createdAtUtc }
    }

    private companion object {
        const val TAG = "CommandOutbox"
        const val SUFFIX = ".json"
        const val TEMP_SUFFIX = ".tmp"
    }
}
