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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
 * Writes go to a temp file, are fsynced, then atomically moved over the target and the directory
 * is fsynced: a crash leaves the old file or the new one, never half of one. A file that cannot be
 * read, or whose payload no longer matches its fingerprint, is surfaced as
 * [PendingStatus.ManagerReconcile] — never dropped, because it may stand for a bag that was
 * physically captured. That includes an orphan temp file: if the rename was lost in a power cut
 * the command may already have been published.
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
            tempFor(messageId).delete()   // an orphan kept by load() must not resurrect a settled command
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
    private fun tempFor(messageId: String) = File(dir, "$messageId$SUFFIX$TEMP_SUFFIX")

    private fun write(command: PendingCommand) {
        dir.mkdirs()
        val target = fileFor(command.messageId)
        val temp = tempFor(command.messageId)
        FileOutputStream(temp).use { out ->
            out.write(gson.toJson(command).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDir()
    }

    /** Makes the rename durable on ext4/f2fs. Best-effort: some JVMs (Windows) cannot open a directory. */
    private fun syncDir() {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun load(): List<PendingCommand> {
        val files = dir.listFiles() ?: return emptyList()
        val committed = files.filter { it.name.endsWith(SUFFIX) }.map { it.name.removeSuffix(SUFFIX) }.toSet()
        val orphans = mutableListOf<PendingCommand>()
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { temp ->
            val id = temp.name.removeSuffix(TEMP_SUFFIX).removeSuffix(SUFFIX)
            // Beside its .json, the .json is the committed copy and the temp is an unfinished rewrite.
            if (id in committed) { temp.delete(); return@forEach }
            // No .json: the move may have returned (and the command been published) before a power
            // cut lost the rename. An intact copy is promoted; anything else is kept for a manager.
            val (entry, intact) = read(temp, id)
            if (intact) {
                try {
                    Files.move(temp.toPath(), fileFor(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    syncDir()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not promote orphan outbox entry $id", e)
                }
            } else {
                Log.w(TAG, "Damaged orphan outbox entry $id kept for a manager")
            }
            orphans += entry
        }
        return (files.filter { it.name.endsWith(SUFFIX) }.map { read(it, it.name.removeSuffix(SUFFIX)).first } + orphans)
            .sortedBy { it.createdAtUtc }
    }

    /** The entry [file] stands for, and whether it is intact (parsed, named correctly, fingerprint matches). */
    private fun read(file: File, id: String): Pair<PendingCommand, Boolean> {
        val stub = PendingCommand(messageId = id, status = PendingStatus.ManagerReconcile)
        return try {
            val parsed = gson.fromJson(file.readText(Charsets.UTF_8), PendingCommand::class.java)
            // Gson can write JSON nulls into non-null Kotlin fields, so check at runtime.
            val required = listOf<Any?>(
                parsed, parsed?.messageId, parsed?.requestType, parsed?.responseType, parsed?.action,
                parsed?.payload, parsed?.fingerprint, parsed?.operatorId, parsed?.sessionId,
                parsed?.deviceId, parsed?.createdAtUtc, parsed?.status,
            )
            when {
                required.any { it == null } || parsed.messageId != id -> stub to false
                RequestFingerprint.of(parsed.payloadBytes) != parsed.fingerprint ->
                    parsed.copy(status = PendingStatus.ManagerReconcile) to false
                else -> parsed to true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable outbox entry $id", e)
            stub to false
        }
    }

    private companion object {
        const val TAG = "CommandOutbox"
        const val SUFFIX = ".json"
        const val TEMP_SUFFIX = ".tmp"
    }
}
