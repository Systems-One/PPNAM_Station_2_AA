package com.mitas.ppnam.station2aa.ui.preparation

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PreparationFormatTest {

    private fun material(
        collected: String, required: String, excluded: Boolean = false,
        original: String? = required, perMix: String = "10",
    ) = PrepMaterial(
        code = "RESIN", name = "Resin", unit = "kg", perMix = BigDecimal(perMix),
        required = BigDecimal(required), collected = BigDecimal(collected),
        excluded = excluded, originalRequired = original?.let(::BigDecimal),
    )

    @Test
    fun `a normal row shows collected of required and what is left`() {
        assertEquals("10 of 40 kg · 30 kg to go", material("10", "40").progressLine())
    }

    @Test
    fun `quantities keep exact decimals and drop trailing zeros`() {
        assertEquals("0.25 of 40 kg · 39.75 kg to go", material("0.250", "40.00").progressLine())
    }

    @Test
    fun `a complete row says so`() {
        assertEquals("40 of 40 kg · complete", material("40", "40").progressLine())
    }

    @Test
    fun `an excluded row shows its original quantity`() {
        assertEquals("excluded — original 8 kg", material("0", "0", excluded = true, original = "8").progressLine())
    }

    @Test
    fun `an operator-added row is marked and shows its target`() {
        assertEquals(
            "added by operator · 0 of 8 kg",
            material("0", "8", original = "0", perMix = "0").progressLine(),
        )
    }

    private fun record(reviewedBy: String? = null, reviewedAt: String? = null, reason: String = "") =
        CollectionExceptionRecord(
            id = "e1", timestampUtc = "2026-10-01T13:40:59.547851Z", operatorId = "65cf955ae506431b9d0d0025ca9fe8fa",
            device = "scanner_1", messageId = "m", jobId = "1", batchId = "PREP_x", kind = "Ingredient omitted",
            details = "Omitted OPTIONAL", reason = reason, reviewedAtUtc = reviewedAt, reviewedBy = reviewedBy,
            reviewReason = null,
        )

    @Test
    fun `an open record lists header, details, reason and Open`() {
        assertEquals(
            listOf(
                "1 Oct 2026 15:40 · operator 65cf… · Ingredient omitted",
                "Omitted OPTIONAL",
                "Not needed today",
                "Open",
            ),
            record(reason = "Not needed today").lines(),
        )
    }

    @Test
    fun `a record without a reason has no reason line`() {
        assertEquals(3, record().lines().size)
    }

    @Test
    fun `a reviewed fixture record names the reviewer and the SAST time`() {
        val data = JsonParser.parseString(ContractFixtures.text("general_read_reviewed_response.json"))
            .asJsonObject["data"]
        val first = WireJson.gson.fromJson(data, Rev2Snapshot::class.java).toPrepSnapshot().collectionExceptions.first()
        val lines = first.lines()
        assertEquals("1 Oct 2026 15:40 · operator 65cf… · Ingredient added", lines.first())
        assertEquals("Reviewed by 211c… on 1 Oct 2026 15:40", lines.last())
    }

    private val sentAt = RecoveryResult.Resolved(
        PendingCommand(messageId = "m", action = "capture", targetId = "PREP_1", createdAtUtc = "2026-10-01T13:40:00Z"),
        RecoveryOutcome.NotExecuted, "", Rev2Snapshot(),
    )

    @Test
    fun `the replacement prompt names the code, quantity and SAST time`() {
        assertEquals(
            "Station 2 never received the scan of TAG-RESIN (10) at 1 Oct 2026 15:40. " +
                "It has been cancelled and can never happen later. Record this bag now?",
            ReplacementOffer.Capture(sentAt, "PREP_1", CaptureIntent("TAG-RESIN", BigDecimal("10.000"))).prompt(),
        )
        assertEquals(
            "Station 2 never received the scan of TAG-RESIN (whole bag). " +
                "It has been cancelled and can never happen later. Record this bag now?",
            ReplacementOffer.Capture(null, "PREP_1", CaptureIntent("TAG-RESIN", null)).prompt(),
        )
        assertEquals(
            "Station 2 never received the confirmation at 1 Oct 2026 15:40. " +
                "It has been cancelled and can never happen later. Confirm collection now?",
            ReplacementOffer.Confirm(sentAt, "PREP_1").prompt(),
        )
    }
}
