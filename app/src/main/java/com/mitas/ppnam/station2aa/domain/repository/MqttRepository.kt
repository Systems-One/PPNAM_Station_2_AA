package com.mitas.ppnam.station2aa.domain.repository

import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    /**
     * Whether Station 2 itself has announced `online` on its retained presence topic.
     *
     * Distinct from [connectionState], which only reports the broker link. The broker can be up
     * while Station 2 is down, in which case every request will time out.
     */
    val stationOnline: StateFlow<Boolean>
    /**
     * Station 2's clock minus this device's clock, in milliseconds, as of the last response
     * carrying a parseable timestamp. `null` when no such response has arrived yet.
     *
     * Every request carries a `timestampUtc`, and a badly drifted clock makes timestamps hard to
     * reconcile with Station 2's logs. This surfaces that as a clock problem rather than leaving it
     * to be discovered later. Detection only — never auto-correct.
     */
    val clockSkewMillis: StateFlow<Long?>
    /**
     * Latched true when Station 2 answers anything with `client_upgrade_required` — the reader
     * build is too old for the workflow it attempted. There is no un-latch short of installing
     * the required build; surfacing it as state (not a one-shot error) is the point.
     */
    val upgradeRequired: StateFlow<Boolean>
    /** Clears the [upgradeRequired] latch (the gate's "Close app"), so a relaunch re-evaluates against the backend. */
    fun clearUpgradeRequired() {}
    suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        responseClass: Class<T>,
    ): MqttOutcome<T>
    /**
     * Registers the single handler for rev2.1 server pushes — messages with no
     * `inResponseToMessageId`, currently `active_job_cards_invalidated`. The transport does not
     * interpret them: the push is a hint to issue a fresh authenticated `read`, and only the layer
     * that owns the displayed list can do that.
     */
    fun setServerPushHandler(handler: (topic: String, envelope: ResponseEnvelope, raw: String) -> Unit)
    suspend fun connect()
    fun disconnect()
    suspend fun reconnectWith(settings: AppSettings): Result<Unit>
}
