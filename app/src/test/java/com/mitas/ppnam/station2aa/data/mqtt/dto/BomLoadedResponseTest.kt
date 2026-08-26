package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BomLoadedResponseTest {

    private val gson = Gson()

    /**
     * Regression for the 2026-07-29 field failure: `bom_loaded` for JC 510019340 was reported as
     * "Station 2 sent an unreadable response".
     *
     * `bagSizeOptions[]` is an array of OBJECTS on the wire (backend `BagSizeOptionMessage`), and
     * was declared here as `List<String>`. Gson cannot read an object into a String, so ONE line
     * carrying options aborted the parse of the WHOLE message — every ingredient, the summary and
     * the collection id went with it. The other three lines sent `[]`, which is why every earlier
     * capture and the backend simulator (which never emits options at all) parsed cleanly.
     */
    @Test
    fun `a line offering several bag sizes does not abort the whole message`() {
        val json = """
            {"jobCardNumber":"510019340","collectionId":"COL_000040","collectionStatus":"Collecting",
             "ingredients":[
               {"lineNumber":0,"materialCode":"1600000039","bagSize":null,
                "bagSizeIsVariable":false,"bagSizeOptions":[]},
               {"lineNumber":2,"materialCode":"1500000316","bagSize":"25.000 kg",
                "bagSizeIsVariable":false,
                "bagSizeOptions":[{"bagSize":25.000,"availableBagCount":8,
                                   "availableQuantity":200.000,"unit":"kg"}]}
             ]}
        """.trimIndent()

        val r = gson.fromJson(json, BomLoadedResponse::class.java)

        assertEquals("COL_000040", r.collectionId)
        assertEquals(2, r.ingredients.size)
        assertTrue(r.ingredients[0].bagSizeOptions.isEmpty())

        val option = r.ingredients[1].bagSizeOptions.single()
        assertEquals(25.0, option.bagSize, 0.001)
        assertEquals(8.0, option.availableBagCount, 0.001)
        assertEquals(200.0, option.availableQuantity, 0.001)
        assertEquals("kg", option.unit)
    }
}
