package com.mitas.ppnam.station2aa.data.mqtt

import android.util.Log

enum class Direction { OUT, IN }

/**
 * One log line per MQTT message: direction, topic, QoS, retain, device id, message type, rev2.1
 * `action`, result/error code and duration (base standard §7).
 *
 * [line] builds the string and [message] emits it, split so the format is unit-testable without an
 * Android logger. A payload always passes through [Redact] — there is no way to log a raw one.
 */
object MqttLog {

    private const val TAG = "MqttWire"

    fun line(
        direction: Direction,
        topic: String,
        qos: Int,
        retain: Boolean,
        deviceId: String,
        messageType: String,
        action: String? = null,
        result: String,
        durationMs: Long? = null,
        payload: String? = null,
    ): String = buildString {
        append("dir=").append(direction)
        append(" topic=").append(topic)
        append(" qos=").append(qos)
        append(" retain=").append(retain)
        append(" deviceId=").append(deviceId)
        append(" type=").append(messageType)
        if (!action.isNullOrBlank()) append(" action=").append(action)
        append(" result=").append(result)
        if (durationMs != null) append(" durationMs=").append(durationMs)
        if (payload != null) append(" payload=").append(Redact.payload(payload))
    }

    fun message(
        direction: Direction,
        topic: String,
        qos: Int,
        retain: Boolean,
        deviceId: String,
        messageType: String,
        action: String? = null,
        result: String,
        durationMs: Long? = null,
        payload: String? = null,
    ) {
        Log.i(TAG, line(direction, topic, qos, retain, deviceId, messageType, action, result, durationMs, payload))
    }
}
