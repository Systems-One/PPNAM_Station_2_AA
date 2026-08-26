package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * Message-specific fields only. The transport injects the envelope — see RequestEnvelope.
 */
data class PalletLookupPayload(
    val palletRfidTag: String,
)

/**
 * `collectionId` is optional: when supplied, the pallet product must be valid for that collection's
 * manual BOM. It is null (and therefore omitted) when recovering outside a collection.
 *
 * Holding recovery carries an auditReason but NO manager credentials — it is not one of the
 * contract's privileged actions.
 */
data class HoldingRecoveryPayload(
    val palletRfidTag: String,
    val collectionId: String? = null,
    val auditReason: String,
)

/**
 * Shared by `pallet_lookup_result` and `holding_recovery_result` — an accepted recovery returns the
 * updated pallet fields from the lookup shape.
 *
 * `usable` and `recoverable` are Station 2's authoritative answers. Do not re-derive them.
 */
data class PalletLookupResultResponse(
    val found: Boolean = false,
    val usable: Boolean = false,
    val recoverable: Boolean = false,
    val palletRfidTag: String? = null,
    val palletId: String? = null,
    val productCode: String? = null,
    val productName: String? = null,
    val batchNumber: String? = null,
    val remainingQuantity: Double = 0.0,
    val remainingBags: Double = 0.0,
    val unit: String? = null,
    val localLocation: String? = null,
    /** Null for a not-found pallet — Station 2 omits every descriptive field in that case. */
    val palletState: String? = null,
    val blocked: Boolean = false,
)
