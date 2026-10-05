package com.mitas.ppnam.station2aa.data.settings

import android.content.Context
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorEntryDto
import com.mitas.ppnam.station2aa.domain.model.OperatorEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the last accepted operator directory lives on the device, so the login dropdown is
 * populated before (or without) an answer from Station 2. An interface so unit tests can use an
 * in-memory store.
 */
interface OperatorDirectoryStore {
    fun load(): List<OperatorEntry>
    fun save(entries: List<OperatorEntry>)
}

/** SharedPreferences `operator_directory` / `operators`, a JSON array: the shape Station 1 AA keeps. */
@Singleton
class PrefsOperatorDirectoryStore @Inject constructor(
    @ApplicationContext context: Context,
) : OperatorDirectoryStore {
    private val prefs = context.getSharedPreferences("operator_directory", Context.MODE_PRIVATE)

    override fun load(): List<OperatorEntry> = OperatorDirectoryJson.decode(prefs.getString(KEY_OPERATORS, null))

    override fun save(entries: List<OperatorEntry>) {
        prefs.edit().putString(KEY_OPERATORS, OperatorDirectoryJson.encode(entries)).apply()
    }

    private companion object {
        const val KEY_OPERATORS = "operators"
    }
}

/** The cache format: a JSON array of `{username, displayName}`. Decoding anything else yields an empty list. */
object OperatorDirectoryJson {

    fun encode(entries: List<OperatorEntry>): String =
        WireJson.gson.toJson(entries.map { OperatorEntryDto(it.username, it.displayName) })

    fun decode(text: String?): List<OperatorEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val dtos = WireJson.gson.fromJson(text, Array<OperatorEntryDto>::class.java) ?: return emptyList()
            OperatorEntry.fromWire(dtos.filterNotNull())
        } catch (e: Exception) {
            emptyList()
        }
    }
}
