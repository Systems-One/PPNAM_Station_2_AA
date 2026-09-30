package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.annotations.SerializedName
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsInvalidatedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramChallengeResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * The app must survive an explicit JSON `null` anywhere in a Station 2 response.
 *
 * Station 2 reports "nothing here" as `"jobCardNumber": null`, not by omitting the key. A Kotlin
 * default only fills an ABSENT key, so Gson writes such a null straight into a non-null field by
 * reflection — past the type system, with nothing to see until a non-null consumer receives it and
 * `Intrinsics.checkNotNullParameter` throws. On 2026-07-29 an idle `jandiDrum` killed the app on
 * every mixing-board refresh through a field the board does not even display.
 *
 * These tests are deliberately reflective rather than a list of hand-written fixtures: the bug is a
 * property of EVERY field of EVERY wire DTO, including ones added after this was written, and a
 * fixture-per-field suite would only ever cover the fields somebody remembered.
 */
class WireNullToleranceTest {

    /**
     * Every type Gson is asked to deserialize a Station 2 message into.
     *
     * Request payloads are deliberately absent — they are only ever serialized, which is why they
     * are allowed to keep required constructor parameters (e.g. `LayerInputDto`). Everything a
     * response can reach is discovered from these roots by [reachableWireTypes], so a new nested DTO
     * is covered the moment a response field points at it.
     */
    private val responseRoots: List<Class<*>> = listOf(
        ResponseEnvelope::class.java,
        BomLoadedResponse::class.java,
        ActiveJobCardsListResponse::class.java,
        ActiveJobCardsInvalidatedResponse::class.java,
        IngredientCollectionCancelResultResponse::class.java,
        IngredientScanResultResponse::class.java,
        PalletLookupResultResponse::class.java,
        ScramChallengeResponse::class.java,
        ScramProofResponse::class.java,
        OperatorContextResponse::class.java,
    )

    // ---- the guarantees -----------------------------------------------------------------------

    @Test
    fun `every wire type keeps the no-arg constructor Gson needs to apply defaults`() {
        // Kotlin emits the no-arg constructor only when EVERY parameter has a default. Drop one and
        // Gson falls back to UnsafeAllocator, which skips constructors entirely: every field lands
        // null regardless of its declared default and non-null type, with no compile error and
        // nothing for the null-pruning guard below to protect.
        val missing = reachableWireTypes().filter { type ->
            runCatching { type.getDeclaredConstructor() }.isFailure
        }.map { it.simpleName }

        assertEquals(
            "wire types with no no-arg constructor — give every constructor parameter a default",
            emptyList<String>(), missing,
        )
    }

    @Test
    fun `an explicit null deserializes exactly like an omitted key, for every wire type`() {
        // The invariant in one line: null and absent must be indistinguishable. Anything else means
        // some field is holding a null its declared type promises it cannot.
        val offenders = reachableWireTypes().mapNotNull { type ->
            val fromNulls = WireJson.gson.fromJson(allNullsJson(type), type)
            val fromNothing = type.getDeclaredConstructor().newInstance()
            if (fromNulls == fromNothing) null else "${type.simpleName} -> $fromNulls"
        }

        assertEquals(
            "wire types where an explicit null overwrote a declared default",
            emptyList<String>(), offenders,
        )
    }

    @Test
    fun `a null inside an array is dropped instead of becoming a null element`() {
        // `List<String>` promises non-null elements just as firmly as a non-null field does, and the
        // NPE it produces surfaces even further from the wire — at the point of use.
        val parsed = WireJson.gson.fromJson(
            """{"allowedActions": ["mixing_view", null, "rfid_view"], "allowedTabs": [null]}""",
            OperatorContextResponse::class.java,
        )

        assertEquals(listOf("mixing_view", "rfid_view"), parsed.allowedActions)
        assertEquals(emptyList<String>(), parsed.allowedTabs)
    }

    @Test
    fun `a null object inside an array is dropped rather than parsed as a null entry`() {
        val parsed = WireJson.gson.fromJson(
            """{"jobCardNumber": "510019339", "ingredients": [null]}""",
            BomLoadedResponse::class.java,
        )

        assertEquals("510019339", parsed.jobCardNumber)
        assertEquals(emptyList<Any>(), parsed.ingredients)
    }

    @Test
    fun `no production code builds its own Gson`() {
        // The guard only guards what flows through it. A bare `Gson()` anywhere in main is a second
        // parser with none of the null pruning, which is how this bug would come back.
        val mainSources = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
        assertNotNull("could not find src/main/java from ${File("").absolutePath}", mainSources)

        val offenders = mainSources!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "WireJson.kt" }
            .filter { it.readText().contains("Gson()") }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(
            "files building their own Gson — parse through WireJson.gson instead",
            emptyList<String>(), offenders,
        )
    }

    // ---- reflective plumbing ------------------------------------------------------------------

    /** Wire DTOs live in one package; that is what makes "is this ours to guard" answerable. */
    private val wirePackagePrefix = ResponseEnvelope::class.java.name.substringBeforeLast('.') + "."

    /** Every wire type reachable from [responseRoots], following fields and their type arguments. */
    private fun reachableWireTypes(): Set<Class<*>> {
        val found = LinkedHashSet<Class<*>>()
        fun visit(type: Class<*>) {
            if (!type.name.startsWith(wirePackagePrefix) || !found.add(type)) return
            instanceFields(type).forEach { field -> classesIn(field.genericType).forEach(::visit) }
        }
        responseRoots.forEach(::visit)
        return found
    }

    /** `{"a": null, "b": null, ...}` — every field this type reads off the wire, explicitly null. */
    private fun allNullsJson(type: Class<*>): String =
        instanceFields(type).joinToString(prefix = "{", postfix = "}") { "\"${wireName(it)}\": null" }

    /**
     * Declared, non-static, non-synthetic fields — the ones Gson binds. Skipping static drops the
     * Compose compiler's `$stable` and any companion, neither of which is wire state.
     */
    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

    /** The key Station 2 actually sends, which is not always the Kotlin property name. */
    private fun wireName(field: Field): String =
        field.getAnnotation(SerializedName::class.java)?.value ?: field.name

    /** Flattens `List<BomLineResponse>` and friends to the raw classes inside them. */
    private fun classesIn(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> listOf(type)
        is ParameterizedType ->
            classesIn(type.rawType) + type.actualTypeArguments.flatMap { classesIn(it) }
        is WildcardType -> type.upperBounds.flatMap { classesIn(it) }
        else -> emptyList()
    }
}
