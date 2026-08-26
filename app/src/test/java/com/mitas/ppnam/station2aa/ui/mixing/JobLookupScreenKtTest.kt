package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class JobLookupScreenKtTest {

    private fun job(
        status: String = "",
        pendingApprovalCount: Int? = null,
    ) = ActiveJobCardSummary(
        jobCardNumber = "JC-1",
        collectionId = "COL-1",
        status = status,
        pendingApprovalCount = pendingApprovalCount,
    )

    @Test
    fun `Collecting maps to Running`() {
        assertEquals(StatusTone.Running, job(status = "Collecting").cardTone())
    }

    @Test
    fun `ReadyForMixing maps to Ready`() {
        assertEquals(StatusTone.Ready, job(status = "ReadyForMixing").cardTone())
    }

    @Test
    fun `Mixing maps to Running`() {
        assertEquals(StatusTone.Running, job(status = "Mixing").cardTone())
    }

    @Test
    fun `unknown status maps to Idle`() {
        assertEquals(StatusTone.Idle, job(status = "Cancelled").cardTone())
    }

    @Test
    fun `pending approval overrides status tone to Warning`() {
        assertEquals(StatusTone.Warning, job(status = "ReadyForMixing", pendingApprovalCount = 2).cardTone())
    }

    @Test
    fun `zero pending approval count does not force Warning`() {
        assertEquals(StatusTone.Ready, job(status = "ReadyForMixing", pendingApprovalCount = 0).cardTone())
    }
}
