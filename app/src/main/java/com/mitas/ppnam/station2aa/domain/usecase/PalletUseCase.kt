package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PalletUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    suspend fun lookup(palletRfidTag: String): Result<PalletInfo> =
        mqttRepository.request(
            requestType = "pallet_lookup_requested",
            responseType = "pallet_lookup_result",
            payload = PalletLookupPayload(palletRfidTag = palletRfidTag),
            correlationKey = palletRfidTag,
            responseClass = PalletLookupResultResponse::class.java,
        ).toResult()

    /**
     * Registers that a pallet physically arrived at Station 2 after a missed door read.
     *
     * Local-only: writes movement, exception and audit records, and never posts to SAP. The returned
     * pallet may still be unusable — recovery does not clear a block.
     */
    suspend fun recoverToHolding(
        palletRfidTag: String,
        collectionId: String?,
        auditReason: String,
    ): Result<PalletInfo> =
        mqttRepository.request(
            requestType = "holding_recovery_requested",
            responseType = "holding_recovery_result",
            payload = HoldingRecoveryPayload(
                palletRfidTag = palletRfidTag,
                collectionId = collectionId,
                auditReason = auditReason,
            ),
            correlationKey = collectionId ?: palletRfidTag,
            responseClass = PalletLookupResultResponse::class.java,
        ).toResult()

    private fun MqttOutcome<PalletLookupResultResponse>.toResult(): Result<PalletInfo> = when (this) {
        // accepted means Station 2 answered — not that the answer was favourable. A lookup that
        // correctly found nothing is a success carrying found = false.
        is MqttOutcome.Accepted -> Result.success(body.toPalletInfo())
        is MqttOutcome.Rejected -> Result.failure(Exception(reason ?: "Station 2 rejected the request"))
        is MqttOutcome.NoResponse -> Result.failure(Exception(kind.message()))
    }

    private fun PalletLookupResultResponse.toPalletInfo() = PalletInfo(
        found = found,
        // Straight through from the response. Re-deriving these is a contract violation.
        usable = usable,
        recoverable = recoverable,
        palletRfidTag = palletRfidTag.orEmpty(),
        palletId = palletId.orEmpty(),
        productCode = productCode.orEmpty(),
        productName = productName.orEmpty(),
        batchNumber = batchNumber.orEmpty(),
        remainingQuantity = remainingQuantity,
        remainingBags = remainingBags,
        unit = unit.orEmpty(),
        localLocation = localLocation.orEmpty(),
        palletState = PalletState.fromWire(palletState),
        blocked = blocked,
    )
}
