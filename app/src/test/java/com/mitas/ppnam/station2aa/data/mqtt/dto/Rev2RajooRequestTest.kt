package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures1021
import com.mitas.ppnam.station2aa.data.mqtt.RequestEnvelope
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Each Rajoo request equals Station 2's own generated example (2026-10-21), field for field. */
class Rev2RajooRequestTest {

    private fun built(payload: Any, fixture: String): Pair<JsonObject, JsonObject> {
        val expected = JsonParser.parseString(ContractFixtures1021.text("${fixture}_request.json")).asJsonObject
        val json = RequestEnvelope.build(
            gson = WireJson.gson, payload = payload,
            messageId = expected["messageId"].asString, deviceId = expected["deviceId"].asString,
            sessionId = expected["sessionId"].asString, timestampUtc = expected["timestampUtc"].asString,
        )
        return JsonParser.parseString(json).asJsonObject to expected
    }

    private val session = "RAJ_f4b7fcfd49d748fc9f9743fce950a2fa"

    @Test
    fun `lookup names the job card and the Rajoo machine`() {
        val (actual, expected) = built(Rev2RajooLookupRequest("2", "RAJ-EXT-01"), "rajoo_lookup")
        assertEquals(expected, actual)
    }

    @Test
    fun `the first rajoo_start carries the choices and catalog revision`() {
        val (actual, expected) = built(
            Rev2RajooStartRequest("2", "RAJ-EXT-01", 1, listOf(Rev2ChoiceAnswer("OPTIONAL", true))), "rajoo_start",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a later rajoo_start omits choices and revision`() {
        val (actual, _) = built(Rev2RajooStartRequest("2", "RAJ-EXT-01"), "rajoo_start")
        assertFalse(actual.has("ingredientChoices"))
        assertFalse(actual.has("exceptionListRevision"))
        assertFalse(actual.has("reason"))
    }

    @Test
    fun `capture names the exact active session`() {
        val (actual, expected) = built(Rev2RajooCaptureRequest("2", "TAG-RESIN", session, BigDecimal("10")), "rajoo_capture")
        assertEquals(expected, actual)
    }

    @Test
    fun `an Add decision carries the job revision and the session`() {
        val (actual, expected) = built(
            Rev2RajooAdjustIngredientRequest("2", session, 1, "Add 5 kg ADDITIVE for the full Rajoo job",
                Rev2Adjustment(sourceCode = "TAG-ADD", targetQuantity = BigDecimal("5"))),
            "rajoo_add",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a Substitute decision names the replaced row and the substitute source`() {
        val (actual, expected) = built(
            Rev2RajooAdjustIngredientRequest("2", session, 2, "Replace uncaptured OPTIONAL full-job target with 100 kg ALT",
                Rev2Adjustment(Rev2Adjustment.KIND_SUBSTITUTE, "OPTIONAL", "TAG-ALT", BigDecimal("100"))),
            "rajoo_substitute",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `Less and More are Quantity decisions with the new full-job total`() {
        val (less, expectedLess) = built(
            Rev2RajooAdjustIngredientRequest("2", session, 3, "Reduce full-job RESIN target from 600 to 580 kg",
                Rev2Adjustment(Rev2Adjustment.KIND_QUANTITY, materialCode = "RESIN", targetQuantity = BigDecimal("580"))),
            "rajoo_less",
        )
        assertEquals(expectedLess, less)
        val (more, expectedMore) = built(
            Rev2RajooAdjustIngredientRequest("2", session, 4, "Increase full-job RESIN target from 580 to 600 kg",
                Rev2Adjustment(Rev2Adjustment.KIND_QUANTITY, materialCode = "RESIN", targetQuantity = BigDecimal("600"))),
            "rajoo_more",
        )
        assertEquals(expectedMore, more)
    }

    @Test
    fun `an Omit decision names the row and carries no target`() {
        val (actual, expected) = built(
            Rev2RajooAdjustIngredientRequest("2", session, 5, "Omit uncaptured added ADDITIVE from the full Rajoo job",
                Rev2Adjustment(Rev2Adjustment.KIND_OMIT, materialCode = "ADDITIVE")),
            "rajoo_omit",
        )
        // Station 2's example spells the absent target as null; the app omits null keys.
        val adjustment = expected["adjustment"].asJsonObject
        assertTrue(adjustment["targetQuantity"].isJsonNull)
        adjustment.remove("targetQuantity")
        assertEquals(expected, actual)
    }

    @Test
    fun `finish and close send the same machine code`() {
        val (finish, expectedFinish) = built(Rev2RajooFinishRequest("2", "RAJ-EXT-01", session), "rajoo_finish")
        assertEquals(expectedFinish, finish)
        val (close, expectedClose) = built(Rev2RajooCloseRequest("2", "RAJ-EXT-01"), "rajoo_close")
        assertEquals(expectedClose, close)
    }
}
