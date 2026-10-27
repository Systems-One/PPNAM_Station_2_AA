package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures1021
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Adjustment
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooAdjustIngredientRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooCaptureRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooCloseRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooFinishRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooStartRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class RajooUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: RajooUseCase

    private fun fixture(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures1021.text("${name}_response.json")).asJsonObject["data"], Rev2Snapshot::class.java,
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = RajooUseCase(mqtt)
    }

    private suspend fun stubSend(snapshot: Rev2Snapshot) {
        whenever(mqtt.sendCommand(any(), any(), any(), eq(Rev2Snapshot::class.java)))
            .thenReturn(CommandOutcome.Settled(MqttOutcome.Accepted(snapshot)))
    }

    private suspend fun sent(): Pair<String, Any> {
        val type = argumentCaptor<String>()
        val payload = argumentCaptor<Any>()
        verify(mqtt).sendCommand(type.capture(), any(), payload.capture(), eq(Rev2Snapshot::class.java))
        return type.firstValue to payload.firstValue
    }

    @Test
    fun `a lookup maps the full-job rows, machine and first-session choices`() = runTest {
        whenever(mqtt.request(eq("rev2_rajoo_requested"), eq("rev2_rajoo_result"), any(), eq(Rev2Snapshot::class.java)))
            .thenReturn(MqttOutcome.Accepted(fixture("rajoo_lookup")))

        val snapshot = (useCase.lookup(" 2 ", "RAJ-EXT-01") as ReadResult.Loaded).snapshot

        val job = snapshot.rajoo!!
        assertEquals("2", job.id)
        assertEquals("RAJ-EXT-01", job.machineId)
        assertNull(job.activeSessionId)
        assertFalse(job.choicesFrozen)
        assertEquals(listOf("RESIN", "OPTIONAL"), job.materials.map { it.code })
        // Full Production Order quantities, not per-mix.
        assertEquals(0, BigDecimal("600").compareTo(job.materials[0].required))
        assertEquals(0, BigDecimal("120").compareTo(job.materials[1].required))
        // The collect screen sees the job as a "preparation" that is not collecting without a session.
        assertEquals("2", snapshot.preparation!!.id)
        assertEquals(PrepStage.Unknown, snapshot.preparation!!.stage)
        assertEquals(listOf("OPTIONAL"), snapshot.requiredChoices.map { it.code })
        assertEquals("RAJ-EXT-01", snapshot.machines.single().code)
    }

    @Test
    fun `an active session makes the job collectable and carries the RAJ id as its cycle`() = runTest {
        stubSend(fixture("rajoo_start"))

        val done = useCase.start("2", "RAJ-EXT-01", 1, listOf(Rev2ChoiceAnswer("OPTIONAL", true))) as CollectionResult.Done

        val (type, payload) = sent()
        assertEquals("rev2_rajoo_requested", type)
        assertEquals(Rev2RajooStartRequest("2", "RAJ-EXT-01", 1, listOf(Rev2ChoiceAnswer("OPTIONAL", true))), payload)
        val job = done.snapshot.rajoo!!
        assertEquals("RAJ_f4b7fcfd49d748fc9f9743fce950a2fa", job.activeSessionId)
        assertTrue(job.choicesFrozen)
        assertEquals(PrepStage.Collecting, done.snapshot.preparation!!.stage)
        assertEquals(job.activeSessionId, done.snapshot.preparation!!.cycleId)
    }

    @Test
    fun `capture is outboxed on the Rajoo family with the session id`() = runTest {
        stubSend(fixture("rajoo_capture"))
        useCase.capture("2", " TAG-RESIN ", "RAJ_1", BigDecimal("10"))
        assertEquals("rev2_rajoo_requested" to Rev2RajooCaptureRequest("2", "TAG-RESIN", "RAJ_1", BigDecimal("10")), sent())
    }

    @Test
    fun `finish ends the session and close marks the job closed`() = runTest {
        stubSend(fixture("rajoo_finish"))
        val finished = (useCase.finish("2", "RAJ-EXT-01", "RAJ_1") as CollectionResult.Done).snapshot.rajoo!!
        assertEquals(Rev2RajooFinishRequest("2", "RAJ-EXT-01", "RAJ_1"), sent().second)
        assertNull(finished.activeSessionId)
        assertTrue(finished.allCollected)
        assertTrue(finished.canClose)

        mqtt = mock(); useCase = RajooUseCase(mqtt)
        stubSend(fixture("rajoo_close"))
        val closed = (useCase.close("2", "RAJ-EXT-01") as CollectionResult.Done).snapshot.rajoo!!
        assertEquals(Rev2RajooCloseRequest("2", "RAJ-EXT-01"), sent().second)
        assertTrue(closed.closed)
        assertFalse(closed.canClose)
    }

    @Test
    fun `identify returns the source and pallet_not_found is Unknown`() = runTest {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java)))
            .thenReturn(MqttOutcome.Accepted(fixture("rajoo_identify")))
        val identified = useCase.identify("2", "TAG-RESIN") as IdentifyResult.Identified
        assertEquals("RESIN", identified.source.materialCode)
        assertNull(identified.snapshot.rajoo)   // compact: no job in the reply

        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java)))
            .thenReturn(MqttOutcome.Rejected(null, ErrorCode("pallet_not_found"), "No pallet."))
        assertTrue(useCase.identify("2", "NOPE") is IdentifyResult.Unknown)
    }

    private val raj = "RAJ_f4b7fcfd49d748fc9f9743fce950a2fa"

    @Test
    fun `each decision is sent in the active session with the job revision and trimmed reason`() = runTest {
        stubSend(fixture("rajoo_less"))
        useCase.changeQuantity("2", raj, 3, " RESIN ", BigDecimal("580.000"), "  less resin ")
        val (type, payload) = sent()
        assertEquals("rev2_rajoo_requested", type)
        assertEquals(
            Rev2RajooAdjustIngredientRequest("2", raj, 3, "less resin",
                Rev2Adjustment(Rev2Adjustment.KIND_QUANTITY, materialCode = "RESIN", targetQuantity = BigDecimal("580"))),
            payload,
        )
    }

    @Test
    fun `substitute names the replaced row and the source, omit sends no target`() = runTest {
        stubSend(fixture("rajoo_substitute"))
        useCase.substitute("2", raj, 2, "OPTIONAL", "TAG-ALT", BigDecimal("100"), "alt")
        assertEquals(
            Rev2Adjustment(Rev2Adjustment.KIND_SUBSTITUTE, "OPTIONAL", "TAG-ALT", BigDecimal("100")),
            (sent().second as Rev2RajooAdjustIngredientRequest).adjustment,
        )

        mqtt = mock(); useCase = RajooUseCase(mqtt)
        stubSend(fixture("rajoo_omit"))
        useCase.omit("2", raj, 5, "ADDITIVE", "not needed")
        val omit = (sent().second as Rev2RajooAdjustIngredientRequest).adjustment
        assertEquals(Rev2Adjustment(Rev2Adjustment.KIND_OMIT, materialCode = "ADDITIVE"), omit)
        assertNull(omit.targetQuantity)
    }

    @Test
    fun `a decision without a reason, session or positive total is refused before sending`() = runTest {
        assertFails { useCase.changeQuantity("2", raj, 1, "RESIN", BigDecimal("5"), "  ") }
        assertFails { useCase.omit("2", " ", 1, "RESIN", "r") }
        assertFails { useCase.changeQuantity("2", raj, 1, "RESIN", BigDecimal.ZERO, "r") }
        assertFails { useCase.changeQuantity("2", raj, 1, "RESIN", BigDecimal("1000000000"), "r") }
        assertFails { useCase.substitute("2", raj, 1, "", "TAG", BigDecimal("1"), "r") }
        verify(mqtt, never()).sendCommand(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("expected IllegalArgumentException")
    }

    @Test
    fun `a General snapshot has no Rajoo job`() {
        val general = WireJson.gson.fromJson(
            JsonParser.parseString(ContractFixtures1021.text("general_substitute_response.json")).asJsonObject["data"],
            Rev2Snapshot::class.java,
        ).toPrepSnapshot()
        assertNull(general.rajoo)
        assertTrue(general.preparation!!.id.startsWith("PREP_"))
    }
}
