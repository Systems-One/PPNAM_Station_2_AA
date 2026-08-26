package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientScanResultTest {

    private val gson = Gson()

    @Test
    fun `a bagged line reports the tolerance Station 2 applied`() {
        val json = """
            {"collectionId":"COL_000123","overCollectionToleranceBags":1.0,
             "ingredients":[{"lineNumber":0,"materialCode":"1600000301","bagSize":"25.000 kg","scannedBags":1.5}]}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals(1.0, r.overCollectionToleranceBags!!, 0.001)
        assertEquals(1.5, r.ingredients.single().scannedBags!!, 0.001)
    }

    @Test
    fun `a bulk line reports no tolerance at all`() {
        // null means no automatic tolerance applies and ANY over-collection needs approval —
        // materially different from a tolerance of zero.
        val json = """
            {"collectionId":"COL_000123","overCollectionToleranceBags":null,
             "ingredients":[{"lineNumber":0,"materialCode":"BULK-1","bagSize":null,"scannedBags":null}]}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertNull(r.overCollectionToleranceBags)
        assertNull(r.ingredients.single().bagSize)
        assertNull(r.ingredients.single().scannedBags)
    }

    @Test
    fun `an approved scan names the approver`() {
        val json = """
            {"collectionId":"COL_000123","approverUserId":"OP-012",
             "approverDisplayName":"Manager One","approverRole":"Manager"}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals("OP-012", r.approverUserId)
        assertEquals("Manager One", r.approverDisplayName)
    }

    @Test
    fun `an ordinary scan names no approver`() {
        val r = gson.fromJson("""{"collectionId":"COL_000123"}""", IngredientScanResultResponse::class.java)

        assertNull(r.approverUserId)
        assertNull(r.approverDisplayName)
    }

    @Test
    fun `the result carries the refreshed summary`() {
        // Required by the contract: the scan result is a workflow decision point.
        val json = """
            {"collectionId":"COL_000123",
             "collectionSummary":{"waitingProductCount":1,"summary":"1 product waiting for collection."}}
        """.trimIndent()

        val r = gson.fromJson(json, IngredientScanResultResponse::class.java)

        assertEquals("1 product waiting for collection.", r.collectionSummary.summary)
    }
}
