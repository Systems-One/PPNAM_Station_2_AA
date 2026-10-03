package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorListResponse
import com.mitas.ppnam.station2aa.data.settings.OperatorDirectoryStore
import com.mitas.ppnam.station2aa.domain.model.OperatorEntry
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject

/**
 * The login screen's operator directory: asks Station 2 for the operators who can sign in with a
 * password (`operator_list_requested` -> `operator_list`, pre-login, no session) and keeps the
 * last accepted list on the device.
 *
 * A failed refresh must never break login: it leaves the cache alone, the screen keeps showing
 * whatever it had, and a typed username that is not listed still signs in.
 */
class OperatorDirectoryUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val store: OperatorDirectoryStore,
) {

    /** The last accepted list, or empty when the device has never heard one. */
    fun cached(): List<OperatorEntry> = store.load()

    /**
     * Requests a fresh list. Returns the normalised list on success (already persisted), or null
     * on any failure (rejection, timeout, no connection, malformed reply) with the cache untouched.
     */
    suspend fun refresh(): List<OperatorEntry>? {
        val outcome = mqttRepository.request(
            requestType = REQUEST_TYPE,
            responseType = RESPONSE_TYPE,
            payload = EmptyPayload,
            responseClass = OperatorListResponse::class.java,
        )
        val body = (outcome as? MqttOutcome.Accepted)?.body ?: return null
        val list = OperatorEntry.fromWire(body.operators)
        store.save(list)
        return list
    }

    companion object {
        const val REQUEST_TYPE = "operator_list_requested"
        const val RESPONSE_TYPE = "operator_list"
    }
}
