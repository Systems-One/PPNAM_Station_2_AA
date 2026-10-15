package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.RequestEnvelope
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Our serialization of each mutation equals Station 2's own generated request, field for field.
 * Byte equality is impossible: RequestEnvelope appends the envelope after the payload, so field
 * order differs, and the fingerprint is always of OUR bytes.
 */
class Rev2CollectionRequestTest {

    private fun built(payload: Any, fixture: String): Pair<JsonObject, JsonObject> {
        val expected = JsonParser.parseString(ContractFixtures.text("${fixture}_request.json")).asJsonObject
        val json = RequestEnvelope.build(
            gson = WireJson.gson, payload = payload,
            messageId = expected["messageId"].asString, deviceId = expected["deviceId"].asString,
            sessionId = expected["sessionId"].asString, timestampUtc = expected["timestampUtc"].asString,
        )
        return JsonParser.parseString(json).asJsonObject to expected
    }

    @Test
    fun `prepare matches the generated request`() {
        val (actual, expected) = built(
            Rev2PrepareRequest(
                targetId = "1", mixCount = 4, exceptionListRevision = 1,
                ingredientChoices = listOf(Rev2ChoiceAnswer("OPTIONAL", false)),
                reason = "Prepare four mixes without the optional ingredient",
            ),
            "general_prepare",
        )
        assertEquals(expected, actual)
        assertTrue(actual["mixCount"].asJsonPrimitive.isNumber)
        assertFalse(actual.has("includeTackifier"))
    }

    @Test
    fun `prepare with no choices still sends an empty list and omits a blank reason`() {
        val (actual, _) = built(
            Rev2PrepareRequest("1", 4, 1, emptyList(), reason = null), "general_prepare",
        )
        assertEquals(0, actual["ingredientChoices"].asJsonArray.size())
        assertFalse(actual.has("reason"))
    }

    @Test
    fun `capture matches the generated request`() {
        val (actual, expected) = built(
            Rev2CaptureRequest("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", "TAG-RESIN", BigDecimal("10")),
            "general_capture",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a whole bag omits quantity`() {
        val (actual, _) = built(Rev2CaptureRequest("PREP_1", "TAG-1"), "general_capture")
        assertFalse(actual.has("quantity"))
    }

    @Test
    fun `capture with a fraction writes a plain decimal`() {
        val json = RequestEnvelope.build(
            WireJson.gson, Rev2CaptureRequest("PREP_1", "TAG-1", BigDecimal("0.25")),
            "m-1", "scanner_1", "s", "2026-10-01T08:00:00.000000Z",
        )
        assertTrue(json, json.contains("\"quantity\":0.25"))
        assertFalse(json, Regex("[0-9]E").containsMatchIn(json)) // no exponent form (ids like PREP_1 hold an E)
    }

    @Test
    fun `confirm matches the generated request`() {
        val (actual, expected) = built(Rev2ConfirmRequest("PREP_440fbd8f9cd3411e9be943a33f1a3ceb"), "general_confirm")
        assertEquals(expected, actual)
    }
}
