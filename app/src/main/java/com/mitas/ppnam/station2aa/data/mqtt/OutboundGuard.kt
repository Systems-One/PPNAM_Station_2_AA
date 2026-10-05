package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonElement

/** An outgoing message carried a field whose name contains "password", at [path]. */
class PlaintextCredentialException(val path: String) :
    IllegalArgumentException("Refusing to publish a message with a credential field at '$path'")

/** An outgoing message exceeded Station 2's request size limit. */
class OversizedPayloadException(val byteCount: Int) :
    IllegalArgumentException("Refusing to publish a $byteCount-byte request (limit ${OutboundGuard.MAX_PAYLOAD_BYTES})")

/**
 * Checks every request before it reaches the broker.
 *
 * Station 2 rejects a top-level field whose name contains `password` with
 * `password_field_forbidden`, and a request over 65,536 UTF-8 bytes with `invalid_envelope`. Catching
 * both here matters more than the server's rejection: once a password is published it is in the
 * broker's logs and every subscriber's buffer. This guard is stricter than the server — any depth —
 * and throws rather than scrubbing, so a build defect cannot ship quietly. Only NAMES are checked;
 * the SCRAM proof is the mechanism that replaced the password, not a copy of it.
 */
object OutboundGuard {

    /** Contract §2: at most 65,536 UTF-8 bytes — a multibyte name counts by its bytes. */
    const val MAX_PAYLOAD_BYTES = 65_536

    fun assertNoCredentialFields(element: JsonElement, path: String = "") {
        when {
            element.isJsonObject -> for ((name, value) in element.asJsonObject.entrySet()) {
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (name.contains("password", ignoreCase = true)) throw PlaintextCredentialException(childPath)
                assertNoCredentialFields(value, childPath)
            }
            element.isJsonArray -> element.asJsonArray.forEachIndexed { index, item ->
                assertNoCredentialFields(item, "$path[$index]")
            }
        }
    }

    fun assertWithinSize(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_PAYLOAD_BYTES) throw OversizedPayloadException(bytes)
    }
}
