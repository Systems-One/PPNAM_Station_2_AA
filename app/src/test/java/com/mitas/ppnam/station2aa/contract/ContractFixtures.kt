package com.mitas.ppnam.station2aa.contract

import java.io.File

/** The Station 2 generated payloads for contract revision 2026-10-01 (see SOURCE.md beside them). */
object ContractFixtures {
    private const val DIR = "contract/2026-10-01"

    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("$DIR/$name")) { "No fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    fun names(): List<String> =
        File(javaClass.classLoader!!.getResource(DIR)!!.toURI()).list()!!.filter { it.endsWith(".json") }.sorted()

    /** Base names with both a request and a response file, e.g. "general_add". */
    fun pairs(): List<String> {
        val all = names().toSet()
        return all.filter { it.endsWith("_request.json") }
            .map { it.removeSuffix("_request.json") }
            .filter { "${it}_response.json" in all }
            .sorted()
    }
}
