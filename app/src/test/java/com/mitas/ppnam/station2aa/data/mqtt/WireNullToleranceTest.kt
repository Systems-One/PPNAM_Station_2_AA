package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.annotations.SerializedName
import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveCycleDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsInvalidatedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveRunDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiDrumDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyCollectionDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyMixDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.mqtt.dto.RunInputDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramChallengeResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
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
        MixingOverviewResponse::class.java,
        MachineCycleResultResponse::class.java,
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
    fun `a null embedded object falls back to its default instead of nulling the field`() {
        // `areaStatus` is the embedded overview every machine result carries. Null here used to mean
        // an NPE at the first mapping call, on a cycle the server had already run.
        val parsed = WireJson.gson.fromJson(
            """{"action": "Started", "machineCode": "MXR-01", "areaStatus": null}""",
            MachineCycleResultResponse::class.java,
        )

        assertEquals("Started", parsed.action)
        assertEquals(MixingOverviewResponse(), parsed.areaStatus)
        assertTrue(parsed.areaStatus.equipment.isEmpty())
    }

    @Test
    fun `an overview whose every field is null maps all the way to domain without throwing`() = runTest {
        // The end-to-end guarantee. Parsing is only half the problem: the crash happened in the
        // DTO -> domain mapping, one layer past Gson, so this drives the real use case over a
        // response in which every single field of every array entry arrived as null.
        val mockMqtt = mock<MqttRepository>()
        val useCase = MixingBoardUseCase(mockMqtt, mock<ManagerAuthorization>())
        val allNullOverview = """
            {
              "mixingArea": null,
              "equipment": [${allNullsJson(EquipmentDto::class.java)}],
              "readyCollections": [${allNullsJson(ReadyCollectionDto::class.java)}],
              "activeCycles": [${allNullsJson(ActiveCycleDto::class.java)}],
              "readyMixes": [${allNullsJson(ReadyMixDto::class.java)}],
              "activeRuns": [{
                "productionRunId": null, "machineCode": null, "status": null,
                "startedAtUtc": null, "inputs": [${allNullsJson(RunInputDto::class.java)}]
              }],
              "jandiDrum": ${allNullsJson(JandiDrumDto::class.java)},
              "nextAction": null
            }
        """.trimIndent()
        val body = WireJson.gson.fromJson(allNullOverview, MixingOverviewResponse::class.java)
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(body, NextAction.NONE))

        val overview = useCase.fetchOverview().getOrThrow()

        // Every branch of the mapping ran — nothing was skipped into a vacuous pass.
        assertEquals(1, overview.equipment.size)
        assertEquals(1, overview.readyCollections.size)
        assertEquals(1, overview.activeCycles.size)
        assertEquals(1, overview.readyMixes.size)
        assertEquals(1, overview.activeRuns.single().inputs.size)
        assertNotNull(overview.jandiDrum)
    }

    @Test
    fun `a machine cycle result whose every field is null still reports the cycle`() = runTest {
        // The other half of the mixing board. A cycle the server has already run must survive an
        // unmappable response: reporting it as failed would send the operator to re-scan a machine
        // that is already going.
        val mockMqtt = mock<MqttRepository>()
        val useCase = MixingBoardUseCase(mockMqtt, mock<ManagerAuthorization>())
        val body = WireJson.gson.fromJson(
            allNullsJson(MachineCycleResultResponse::class.java),
            MachineCycleResultResponse::class.java,
        )
        whenever(mockMqtt.request(
            any(), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(body, NextAction.NONE))

        val outcome = useCase.finish(machineCode = "MXR-01", cycleId = "CYC_000001")

        assertTrue("expected an Accepted outcome, got $outcome", outcome is MachineCycleOutcome.Accepted)
        assertEquals(AreaOverview.EMPTY, (outcome as MachineCycleOutcome.Accepted).areaStatus)
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
