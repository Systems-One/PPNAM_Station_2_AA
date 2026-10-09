package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareFormTest {

    private val optional = ChoicePrompt("OPTIONAL", "Optional", "")

    private fun form(
        available: Int = 56,
        prompts: List<ChoicePrompt> = listOf(optional),
        answers: Map<String, Boolean?> = mapOf("OPTIONAL" to true),
        text: String = "4",
        reason: String = "",
        revision: Int? = 1,
        materials: List<String> = listOf("A", "OPTIONAL"),
    ) = PrepareForm("JC1", available, prompts, answers, text, reason, revision, materials)

    @Test
    fun `N must be a whole number from 1 to available`() {
        for (bad in listOf("0", "57", "4.0", "", "abc")) {
            assertTrue("'$bad'", form(text = bad).problems.isNotEmpty())
        }
        assertTrue(form(text = "4").problems.isEmpty())
        assertEquals(4, form(text = "4").mixCount)
        assertNull(form(text = "4.0").mixCount)
    }

    @Test
    fun `every prompted code must be answered explicitly`() {
        val f = form(answers = mapOf("OPTIONAL" to null))
        assertEquals(listOf("Choose Include or Exclude for OPTIONAL"), f.problems)
        assertEquals(listOf("Choose Include or Exclude for OPTIONAL"), form(answers = emptyMap()).problems)
    }

    @Test
    fun `excluding every material is refused before sending`() {
        val f = form(answers = mapOf("A" to false, "OPTIONAL" to false), prompts = listOf(ChoicePrompt("A", "", ""), optional))
        assertTrue(f.problems.isNotEmpty())
        val ok = form(answers = mapOf("OPTIONAL" to false))
        assertTrue(ok.problems.isEmpty())
    }

    @Test
    fun `prompts without a revision are refused`() {
        assertEquals(listOf("Reload the job \u2014 Station 2 sent no ingredient list revision"), form(revision = null).problems)
    }

    @Test
    fun `no prompts sends an empty list`() {
        val f = form(prompts = emptyList(), answers = emptyMap(), revision = null)
        assertTrue(f.problems.isEmpty())
        assertTrue(f.answersForWire().isEmpty())
    }

    @Test
    fun `answers go on the wire in prompt order`() {
        val f = form(prompts = listOf(optional, ChoicePrompt("B", "", "")), answers = mapOf("B" to false, "OPTIONAL" to true))
        assertEquals(listOf(Rev2ChoiceAnswer("OPTIONAL", true), Rev2ChoiceAnswer("B", false)), f.answersForWire())
    }

    @Test
    fun `nothing to prepare when available is 0`() {
        val f = form(available = 0, text = "1")
        assertTrue(f.problems.isNotEmpty())
        assertTrue(f.problems.any { it.contains("Nothing") })
    }

    @Test
    fun `reason over 1000 characters is a problem`() {
        assertTrue(form(reason = "x".repeat(1000)).problems.isEmpty())
        assertTrue(form(reason = "x".repeat(1001)).problems.isNotEmpty())
    }

    @Test
    fun `rebase keeps still-prompted answers, drops vanished codes, clamps nothing silently`() {
        val old = form(
            available = 60, text = "50",
            prompts = listOf(optional, ChoicePrompt("GONE", "", "")),
            answers = mapOf("OPTIONAL" to false, "GONE" to true),
        )
        val snap = snapshot().let { s ->
            s.copy(lookup = s.lookup.copy(detail = s.lookup.detail!!.copy(
                mixProgress = s.lookup.detail!!.mixProgress!!.copy(availableToPrepareMixes = 10))))
        }
        val r = old.rebasedOn(snap)
        assertEquals(mapOf<String, Boolean?>("OPTIONAL" to false), r.answers)
        assertEquals("50", r.mixCountText)
        assertEquals(10, r.available)
        assertTrue(r.problems.isNotEmpty())
    }

    @Test
    fun `from builds the form from general_lookup`() {
        val f = PrepareForm.from(snapshot())!!
        assertEquals(60, f.available)
        assertEquals(listOf("OPTIONAL"), f.prompts.map { it.code })
        assertNull(f.answers["OPTIONAL"])
        assertEquals(1, f.exceptionListRevision)
    }

    private fun snapshot() = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("general_lookup_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    ).toPrepSnapshot()
}
