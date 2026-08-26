package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson

/** Payload for the contract's envelope-only requests (e.g. reader_logout_requested). */
object EmptyPayload

/**
 * Builds a contract v3.0 request as one flat JSON object: the caller's message-specific payload,
 * with the envelope merged in.
 *
 * Callers never construct envelopes. Only the transport knows the device id, the operator session
 * and the clock, so only the transport writes those fields — which is also why envelope fields are
 * written last and always win over anything of the same name in the payload.
 *
 * Gson omits nulls by default, which is exactly the contract's rule that an unused optional field
 * must be omitted rather than sent as null or "".
 *
 * `build()` additionally treats a blank (empty or whitespace-only) `correlationKey` as absent: a
 * caller deriving the key from an upstream field (e.g. a response's `collectionId`) can end up with
 * `""` when that field was itself omitted, and the contract requires absence, not `""`, in that case.
 */
object RequestEnvelope {

    fun build(
        gson: Gson,
        payload: Any,
        messageId: String,
        deviceId: String,
        operatorSessionId: String,
        timestampUtc: String,
        correlationKey: String?,
    ): String {
        val obj = gson.toJsonTree(payload).asJsonObject
        obj.addProperty("messageId", messageId)
        obj.addProperty("schemaVersion", MqttSchema.VERSION)
        obj.addProperty("deviceId", deviceId)
        obj.addProperty("operatorSessionId", operatorSessionId)
        obj.addProperty("timestampUtc", timestampUtc)
        correlationKey?.takeIf { it.isNotBlank() }?.let { obj.addProperty("correlationKey", it) }
        return gson.toJson(obj)
    }
}
