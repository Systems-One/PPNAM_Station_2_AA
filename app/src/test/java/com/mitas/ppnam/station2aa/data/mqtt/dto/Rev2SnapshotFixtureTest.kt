package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rev2SnapshotFixtureTest {

    private fun dataOf(name: String): JsonObject =
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"].asJsonObject

    private fun snapshot(name: String): Rev2Snapshot = WireJson.gson.fromJson(dataOf(name), Rev2Snapshot::class.java)

    private fun declared(cls: Class<*>): Set<String> = cls.declaredFields.map { it.name }.toSet()

    /** Every key Station 2 sends must have a home in the DTO, so a contract addition is noticed. */
    private fun assertKeysDeclared(where: String, json: JsonObject, cls: Class<*>) {
        val missing = json.keySet() - declared(cls)
        assertTrue("$where: undeclared ${cls.simpleName} fields $missing", missing.isEmpty())
    }

    @Test
    fun `every snapshot key in every example is declared`() {
        val workflow = ContractFixtures.pairs().filter { it.startsWith("general") || it.startsWith("rajoo") || it == "final_capture" }
        for (name in workflow) {
            val data = JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"]
            if (data == null || data.isJsonNull) continue
            val obj = data.asJsonObject
            assertKeysDeclared(name, obj, Rev2Snapshot::class.java)
            obj["job"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { job ->
                assertKeysDeclared("$name.job", job, Rev2Job::class.java)
                job["materials"].asJsonArray.forEach { assertKeysDeclared("$name.job.materials", it.asJsonObject, Rev2Material::class.java) }
                job["mixProgress"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { mp ->
                    assertKeysDeclared("$name.mixProgress", mp, Rev2MixProgress::class.java)
                    mp["activePreparations"].asJsonArray.forEach { assertKeysDeclared("$name.activePreparations", it.asJsonObject, Rev2ActivePreparation::class.java) }
                }
            }
            obj["preparation"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { assertKeysDeclared("$name.preparation", it, Rev2Preparation::class.java) }
            obj["jobs"].asJsonArray.forEach { assertKeysDeclared("$name.jobs", it.asJsonObject, Rev2JobSummary::class.java) }
            obj["machines"].asJsonArray.forEach { assertKeysDeclared("$name.machines", it.asJsonObject, Rev2Machine::class.java) }
            obj["collectionExceptions"].asJsonArray.forEach { assertKeysDeclared("$name.collectionExceptions", it.asJsonObject, Rev2CollectionException::class.java) }
            obj["result"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { assertKeysDeclared("$name.result", it, Rev2CommandResult::class.java) }
        }
    }

    @Test
    fun `prepare 4 of 60 shows 4 active and 56 available to prepare`() {
        val s = snapshot("general_prepare")
        val mp = s.job!!.mixProgress!!
        assertEquals(60, mp.requiredMixes)
        assertEquals(4, mp.allocatedMixes)
        assertEquals(4, mp.activeMixes)
        assertEquals(56, mp.availableToPrepareMixes)
        assertEquals(60, mp.remainingToFinishMixes)
        assertEquals(1, mp.activePreparationCount)
        assertEquals(s.preparation!!.id, mp.activePreparations.single().id)
        assertEquals("Collecting", mp.activePreparations.single().stage)
        assertEquals(4, s.jobs.single().mixProgress!!.activeMixes)
        assertEquals("General", s.mode)
        assertTrue(s.capabilities!!.receiptRecovery)
    }

    @Test
    fun `an operator-added row has originalRequired zero and a server-attributed exception`() {
        val s = snapshot("general_add")
        val added = s.preparation!!.materials.last()
        assertEquals("ADDITIVE", added.code)
        assertEquals(0.0, added.originalRequired!!, 0.0)
        assertEquals(4.0, added.required, 0.0)
        val ex = s.commandExceptions.single()
        assertEquals("Ingredient added", ex.kind)
        assertEquals("scanner_1", ex.device)
        assertNull(ex.reviewedAtUtc)
        assertEquals(1, s.preparation!!.collectionRevision)
    }

    @Test
    fun `a manager review shows on the scanner as reviewed`() {
        val s = snapshot("general_read_reviewed")
        assertTrue(s.collectionExceptions.isNotEmpty())
        assertTrue(s.collectionExceptions.any { it.reviewedBy != null && it.reviewedAtUtc != null && it.reviewReason != null })
    }

    @Test
    fun `recovery outcomes parse with the nested original result`() {
        val committed = snapshot("general_recover_committed").recovery!!
        assertEquals("committed", committed.outcome)
        assertTrue(committed.result!!.success)
        val sealed = snapshot("general_recover_not_executed").recovery!!
        assertEquals("not_executed", sealed.outcome)
        assertEquals("receipt_sealed", sealed.result!!.errorCode)
        assertFalse(sealed.result!!.success)
    }

    @Test
    fun `Rajoo has no mix progress and full-job targets`() {
        val s = snapshot("rajoo_read")
        assertEquals("Rajoo", s.mode)
        assertNull(s.job!!.mixProgress)
        assertTrue(s.job!!.materials.all { it.perMix == 0.0 })
        assertNotNull(s.job!!.collectionRevision)
    }

    @Test
    fun `machines and catalog parse`() {
        val s = snapshot("general_lookup")
        val pair = s.machines.first { it.id == "DOL-MIX-01" }
        assertEquals("FixedPair", pair.kind)
        assertEquals("DOL-01", pair.pairId)
        assertEquals("OPTIONAL", s.requiredIngredientChoices.single().code)
        assertEquals(1, s.exceptionListRevision)
    }
}
