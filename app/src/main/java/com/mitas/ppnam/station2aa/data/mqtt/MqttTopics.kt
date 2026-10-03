package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Per-station namespace topic structure (2026-08-17 topic restructure — payloads unchanged):
 *
 *   PPNAM/station_2                          station presence (retained online/offline, LWT)
 *   PPNAM/station_2/{deviceId}               device presence (retained online/offline, LWT)
 *   PPNAM/station_2/{deviceId}/req/{requestType}
 *   PPNAM/station_2/{deviceId}/res/{responseType}
 *
 * A handheld subscribes to PPNAM/station_2/{ownDeviceId}/res/+ and the station base topic
 * PPNAM/station_2. Presence lives on the base topic nodes — there is no /status sub-topic.
 */
object MqttTopics {

    private const val STATION_BASE = "PPNAM/station_2"

    /** Station 2's presence topic is its base node — a fixed literal in the contract. */
    const val STATION_PRESENCE = STATION_BASE

    fun request(deviceId: String, requestType: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(requestType, "requestType")
        return "$STATION_BASE/$deviceId/req/$requestType"
    }

    fun responseWildcard(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "$STATION_BASE/$deviceId/res/+"
    }

    /** The device's base node — carries its retained presence payload and Last Will. */
    fun devicePresence(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "$STATION_BASE/$deviceId"
    }

    fun responseTypeOf(topic: String): String = topic.substringAfterLast('/')

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}
