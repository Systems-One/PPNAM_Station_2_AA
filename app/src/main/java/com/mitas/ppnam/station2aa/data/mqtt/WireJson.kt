package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope

/**
 * The one Gson every contract v4.1 message is read with. Use it instead of `Gson()` — a bare
 * instance reintroduces the crash described below.
 *
 * ### The bug this exists to make impossible
 *
 * Station 2 reports "nothing here" as an explicit `null`, not by omitting the key: an idle transfer
 * drum arrives as `{"status":"Available","jobCardNumber":null,...}`. A Kotlin default only fills a
 * key that is ABSENT, so Gson writes that null straight into a `String` field by reflection, going
 * around the type system rather than through it. Nothing complains at the parse — the value simply
 * sits in a field whose type says it cannot be there, until some non-null consumer receives it and
 * `Intrinsics.checkNotNullParameter` throws, arbitrarily far from the wire.
 *
 * On 2026-07-29 that was a hard crash on every mixing-board refresh:
 *
 * ```
 * NullPointerException: Parameter specified as non-null is null:
 *   method domain.model.JandiDrum.<init>, parameter jobCardNumber
 * ```
 *
 * — from a field the board does not display, on an idle drum, in an area the operator wasn't using.
 *
 * ### Why the guard is here rather than in the DTOs
 *
 * Declaring the offending fields nullable fixes one field each. Every non-null `String`, `List` and
 * embedded object on every wire DTO has the same latent fault, and a new field arrives with it
 * already present — the fault is in the Gson/Kotlin boundary, so that is where it is closed.
 */
object WireJson {

    val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(NullPruningTypeAdapterFactory)
        .create()
}

/**
 * Deletes JSON nulls before Gson can bind them, so a null-valued key is indistinguishable from an
 * absent one and every Kotlin default holds.
 *
 * Only types in the wire DTO package are wrapped: Gson's own adapters for strings, numbers and
 * collections are left alone, and request payloads pass through untouched because [write] delegates.
 * Serialization is unchanged — Gson already omits nulls, which is the contract's own rule.
 *
 * Deleting rather than coercing is what makes this safe to apply blanket-wide: the guard never has
 * to guess what a field's empty value should be, because the constructor default already says.
 */
private object NullPruningTypeAdapterFactory : TypeAdapterFactory {

    private val wirePackagePrefix = ResponseEnvelope::class.java.name.substringBeforeLast('.') + "."

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!type.rawType.name.startsWith(wirePackagePrefix)) return null
        val delegate = gson.getDelegateAdapter(this, type)
        val elements = gson.getAdapter(JsonElement::class.java)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

            override fun read(reader: JsonReader): T =
                delegate.fromJsonTree(prune(elements.read(reader)))
            // nullSafe, because a wholly null message body is still null: pruning answers what a
            // null FIELD means, not what a null document means.
        }.nullSafe()
    }

    /**
     * [element] without its null object members or null array entries, recursively.
     *
     * Array entries are dropped too: `List<String>` promises non-null elements exactly as firmly as
     * a non-null field does, and the NPE a null element produces surfaces even further from the wire
     * — at the point of use, in whatever screen happened to read it.
     */
    private fun prune(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { kept ->
            for ((name, value) in element.asJsonObject.entrySet()) {
                if (!value.isJsonNull) kept.add(name, prune(value))
            }
        }
        element.isJsonArray -> JsonArray().also { kept ->
            for (item in element.asJsonArray) {
                if (!item.isJsonNull) kept.add(prune(item))
            }
        }
        else -> element
    }
}
