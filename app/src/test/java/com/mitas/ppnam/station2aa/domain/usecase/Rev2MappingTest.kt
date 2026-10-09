package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class Rev2MappingTest {

    private fun data(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    )

    @Test
    fun `lookup carries the choices and the catalog revision to echo`() {
        val s = data("general_lookup").toPrepSnapshot()
        assertEquals(listOf("OPTIONAL"), s.requiredChoices.map { it.code })
        assertEquals(1, s.exceptionListRevision)
        assertNull(s.preparation)
        assertEquals(60, s.lookup.detail!!.mixProgress!!.availableToPrepareMixes)
    }

    @Test
    fun `prepare gives the new PREP with effective rows and the omission record`() {
        val s = data("general_prepare").toPrepSnapshot()
        val p = s.preparation!!
        assertEquals("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", s.resultTargetId)
        assertEquals(PrepStage.Collecting, p.stage)
        val optional = p.materials.single { it.code == "OPTIONAL" }
        assertTrue(optional.excluded)
        assertEquals(0, BigDecimal("8").compareTo(optional.originalRequired))
        assertEquals("Ingredient omitted", s.commandExceptions.single().kind)
        assertTrue(s.requiredChoices.isEmpty())
    }

    @Test
    fun `final capture is awaiting confirmation with every included row complete`() {
        val p = data("final_capture").toPrepSnapshot().preparation!!
        assertEquals(PrepStage.AwaitingConfirmation, p.stage)
        assertEquals(4, p.collectionRevision)
        assertTrue(p.allIncludedCollected)
    }

    @Test
    fun `confirm is ReadyForMixer and keeps the revision`() {
        val p = data("general_confirm").toPrepSnapshot().preparation!!
        assertEquals(PrepStage.ReadyForMixer, p.stage)
        assertEquals(4, p.collectionRevision)
    }

    @Test
    fun `operator-added and substituted rows survive the mapping`() {
        val p = data("general_recover_not_executed").toPrepSnapshot().preparation!!
        val alt = p.materials.single { it.code == "ALT" }
        assertTrue(alt.isAdded)
        assertFalse(alt.excluded)
        assertEquals(0, BigDecimal("8").compareTo(alt.remaining))
    }

    @Test
    fun `an unknown stage is kept, not crashed on`() {
        assertEquals(PrepStage.Unknown, PrepStage.fromWire("Teleporting"))
    }

    @Test
    fun `the original capture intent is read back from the saved bytes`() {
        val intent = CaptureIntent.fromPayload(ContractFixtures.text("general_capture_request.json"))!!
        assertEquals("TAG-RESIN", intent.code)
        assertEquals(0, BigDecimal("10").compareTo(intent.quantity))
        assertNull(CaptureIntent.fromPayload("{\"action\":\"confirm\",\"targetId\":\"P\"}"))
    }
}
