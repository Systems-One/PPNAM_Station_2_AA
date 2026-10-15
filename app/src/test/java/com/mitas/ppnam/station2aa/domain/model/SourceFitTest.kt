package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class SourceFitTest {

    private fun mat(code: String, required: String, collected: String, excluded: Boolean = false) = PrepMaterial(
        code = code, name = code, unit = "kg", perMix = BigDecimal.ONE, required = BigDecimal(required),
        collected = BigDecimal(collected), excluded = excluded, originalRequired = BigDecimal(required),
    )

    private val outstanding = mat("RESIN", "50", "10")
    private val complete = mat("ADDITIVE", "4", "4")
    private val excluded = mat("OPTIONAL", "0", "0", excluded = true)

    private val snapshot = PrepSnapshot(
        lookup = JobLookupSnapshot(jobs = emptyList(), detail = null),
        preparation = Preparation(
            id = "PREP_1", jobId = "1", mixCount = 1, mixed = 0, produced = 0, stage = PrepStage.Collecting,
            stageRaw = "Collecting", collectionRevision = 3, startedAtUtc = null, startedBy = null, confirmedAtUtc = null,
            materials = listOf(outstanding, complete, excluded),
        ),
        requiredChoices = emptyList(), exceptionListRevision = null, collectionExceptions = emptyList(),
        commandExceptions = emptyList(), resultTargetId = null, resultMessage = "",
    )

    private fun source(materialCode: String) = SourceInfo(
        code = "TAG-1", materialCode = materialCode, name = "Name", unit = "kg", bagSize = BigDecimal("25"),
        available = BigDecimal("100"), usable = true, reason = "",
    )

    @Test
    fun `an outstanding row is in the mix, compared case-insensitively`() {
        assertEquals(SourceFit.InMix(outstanding), snapshot.classify(source("resin")))
    }

    @Test
    fun `a fully collected row is complete`() {
        assertEquals(SourceFit.Complete(complete), snapshot.classify(source("ADDITIVE")))
    }

    @Test
    fun `an excluded row is excluded`() {
        assertEquals(SourceFit.Excluded(excluded), snapshot.classify(source("OPTIONAL")))
    }

    @Test
    fun `an unknown material is not in the mix`() {
        assertEquals(SourceFit.NotInMix, snapshot.classify(source("OTHER")))
        assertEquals(SourceFit.NotInMix, snapshot.copy(preparation = null).classify(source("RESIN")))
    }
}
