package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Scrubs secrets from a payload before it reaches a log (base standard §7; rev2.1 also requires
 * session ids be redacted from MQTT logging).
 *
 * Unparseable input returns a fixed marker rather than the original text: a payload that failed to
 * parse is exactly the one most likely to be logged for investigation, and it can still contain a
 * complete secret.
 */
object Redact {

    const val PLACEHOLDER = "***"

    private val SECRET_KEYS = setOf(
        "sessionid",
        // SCRAM material — enough, together, to replay or brute-force a login.
        "clientproof", "serversignature", "clientfinalwithoutproof", "salt",
        "serverfirstmessage", "servernonce", "clientnonce", "challengeid",
    )

    fun payload(raw: String): String = try {
        redact(JsonParser.parseString(raw)).toString()
    } catch (e: Exception) {
        "<unparseable payload>"
    }

    private fun isSecret(name: String): Boolean {
        val key = name.lowercase()
        return key in SECRET_KEYS || key.contains("password")
    }

    private fun redact(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { out ->
            for ((name, value) in element.asJsonObject.entrySet()) {
                out.add(name, if (isSecret(name)) JsonPrimitive(PLACEHOLDER) else redact(value))
            }
        }
        element.isJsonArray -> JsonArray().also { out -> element.asJsonArray.forEach { out.add(redact(it)) } }
        else -> element
    }
}
