package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.internal.LazilyParsedNumber
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** A message carried two properties whose names differ only by case, at [path]. */
class DuplicatePropertyException(val path: String) :
    IllegalArgumentException("Duplicate property name at '$path'")

/**
 * Parses one wire message, rejecting duplicate property names case-insensitively at any depth
 * (base standard §4; Station 2 applies the same rule to what it receives).
 *
 * Gson's own parser silently keeps the LAST value for a repeated key, so
 * `{"success":true,"Success":false}` would parse as a failure. That is a message to reject, not one
 * to guess at — and the guess here is whether an operation succeeded.
 *
 * Duplicates are scoped per object: the same name at different depths is ordinary nesting.
 */
object StrictJson {

    fun parse(raw: String): JsonElement =
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            read(reader, "")
        }

    private fun read(reader: JsonReader, path: String): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val seen = HashSet<String>()
            val obj = JsonObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (!seen.add(name.lowercase())) throw DuplicatePropertyException(childPath)
                obj.add(name, read(reader, childPath))
            }
            reader.endObject()
            obj
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val arr = JsonArray()
            var index = 0
            while (reader.hasNext()) arr.add(read(reader, "$path[${index++}]"))
            reader.endArray()
            arr
        }
        // Numbers stay as text until used: going via Double would quietly round a long id.
        JsonToken.NUMBER -> JsonPrimitive(LazilyParsedNumber(reader.nextString()))
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Unexpected token ${reader.peek()} at '$path'")
    }
}
