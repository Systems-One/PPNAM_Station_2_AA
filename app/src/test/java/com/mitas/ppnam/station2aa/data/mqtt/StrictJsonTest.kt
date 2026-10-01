package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonTest {

    @Test
    fun `accepts an ordinary object`() {
        assertEquals("scanner_1", StrictJson.parse("""{"success":true,"deviceId":"scanner_1"}""").asJsonObject["deviceId"].asString)
    }

    @Test
    fun `rejects an exact duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"success":true,"success":false}""") }
    }

    @Test
    fun `rejects a case-insensitive duplicate property`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"success":true,"Success":false}""") }
    }

    @Test
    fun `rejects a duplicate nested inside data`() {
        assertThrows(DuplicatePropertyException::class.java) { StrictJson.parse("""{"data":{"job":{"id":"1","ID":"2"}}}""") }
    }

    @Test
    fun `rejects a duplicate inside an array element`() {
        assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"data":{"jobs":[{"id":"1"},{"id":"1","Id":"2"}]}}""")
        }
    }

    @Test
    fun `the same name at different depths is not a duplicate`() {
        val parsed = StrictJson.parse("""{"id":1,"data":{"id":2}}""").asJsonObject
        assertEquals(2, parsed["data"].asJsonObject["id"].asInt)
    }

    @Test
    fun `a long numeric id is not rounded`() {
        assertEquals("510019068000000001", StrictJson.parse("""{"n":510019068000000001}""").asJsonObject["n"].asString)
    }

    @Test
    fun `reports the path of the offending property`() {
        val thrown = assertThrows(DuplicatePropertyException::class.java) {
            StrictJson.parse("""{"data":{"job":{"a":1,"A":2}}}""")
        }
        assertEquals("data/job/A", thrown.path)
    }
}
