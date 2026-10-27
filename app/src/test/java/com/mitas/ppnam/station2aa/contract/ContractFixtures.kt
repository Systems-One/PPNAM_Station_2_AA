package com.mitas.ppnam.station2aa.contract

import java.io.File

/** One revision's Station 2 generated payloads (see SOURCE.md beside them). */
open class ContractFixtureSet(private val dir: String) {

    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("$dir/$name")) { "No fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    fun names(): List<String> =
        File(javaClass.classLoader!!.getResource(dir)!!.toURI()).list()!!.filter { it.endsWith(".json") }.sorted()

    /** Base names with both a request and a response file, e.g. "general_add". */
    fun pairs(): List<String> {
        val all = names().toSet()
        return all.filter { it.endsWith("_request.json") }
            .map { it.removeSuffix("_request.json") }
            .filter { "${it}_response.json" in all }
            .sorted()
    }
}

/** The full example set for contract revision 2026-10-01. */
object ContractFixtures : ContractFixtureSet("contract/2026-10-01")

/** Revision 2026-10-21 additions: card login, compact `identify`, `isModified` materials, Rajoo. */
object ContractFixtures1021 : ContractFixtureSet("contract/2026-10-21")
