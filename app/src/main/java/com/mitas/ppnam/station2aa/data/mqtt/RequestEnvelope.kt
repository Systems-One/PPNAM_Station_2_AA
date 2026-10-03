package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson

/** Payload for a request with no message-specific fields. */
object EmptyPayload

/**
 * Builds a rev2.1 request as one flat JSON object: the caller's message-specific payload with the
 * envelope merged in.
 *
 * Callers never construct envelopes. Only the transport knows the device id, the session and the
 * clock, so only the transport writes those fields — which is also why envelope fields are written
 * last and always win over anything of the same name in the payload.
 *
 * `sessionId` is written only when non-blank: Station 2 has no notion of an empty session, and the
 * contract's rule is that an unused optional field is omitted, never sent as `""`.
 *
 * Throws [PlaintextCredentialException] or [OversizedPayloadException] rather than publishing — see
 * [OutboundGuard].
 */
object RequestEnvelope {

    fun build(
        gson: Gson,
        payload: Any,
        messageId: String,
        deviceId: String,
        sessionId: String?,
        timestampUtc: String,
    ): String {
        val obj = gson.toJsonTree(payload).asJsonObject
        OutboundGuard.assertNoCredentialFields(obj)
        obj.addProperty("schemaVersion", MqttSchema.VERSION)
        obj.addProperty("deviceId", deviceId)
        obj.addProperty("messageId", messageId)
        obj.addProperty("timestampUtc", timestampUtc)
        sessionId?.takeIf { it.isNotBlank() }?.let { obj.addProperty("sessionId", it) }
        return gson.toJson(obj).also(OutboundGuard::assertWithinSize)
    }
}
