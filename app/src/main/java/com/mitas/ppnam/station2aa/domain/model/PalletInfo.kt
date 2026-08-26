package com.mitas.ppnam.station2aa.domain.model

/**
 * Contract v3.0 `palletState` — the axis every pallet decision keys off. A closed vocabulary: the
 * contract's table is exhaustive and already carries an explicit Unknown member.
 *
 * Note `blocked` is NOT a state — it is a separate overlay, so a pallet can be Holding *and* blocked.
 */
enum class PalletState {
    /** Station 2 has it, available for collection. */
    Holding,

    /** In use by an active mix. */
    Mixing,

    /** Station 2 has no arrival record — the door read was missed, or it genuinely is still upstream. */
    AtStation1,

    /** Known pallet, indeterminate state. */
    Unknown,

    /** Fully depleted. */
    Consumed;

    companion object {
        /**
         * Degrades an unrecognised — or absent — value to [Unknown] rather than failing the lookup.
         *
         * The parameter is nullable because the wire genuinely sends null: a `pallet_lookup_result`
         * for an unknown tag answers `found: false` and omits every descriptive field, `palletState`
         * included. Gson does not honour Kotlin nullability (it writes JSON null straight into the
         * field by reflection, and a data-class default only applies when the key is *absent*), so
         * a non-null parameter here threw `NullPointerException` on the intrinsic check and took
         * the whole app down on every scan of an unrecognised pallet. Matches the already-nullable
         * [MixingArea.fromWire] and [SessionState.fromWire].
         */
        fun fromWire(raw: String?): PalletState =
            entries.firstOrNull { it.name == raw } ?: Unknown
    }
}

/**
 * A pallet as Station 2 sees it.
 *
 * [usable] and [recoverable] are computed by Station 2 and must be displayed, never recomputed:
 *  - usable     is decided by palletState AND blocked AND remainingQuantity
 *  - recoverable is decided by palletState alone
 *
 * They are independent. A blocked AtStation1 pallet is recoverable, and recovering it does not
 * unblock it — so a successful recovery can still leave usable = false.
 */
data class PalletInfo(
    val found: Boolean,
    val usable: Boolean,
    val recoverable: Boolean,
    val palletRfidTag: String,
    val palletId: String,
    val productCode: String,
    val productName: String,
    val batchNumber: String,
    val remainingQuantity: Double,
    val remainingBags: Double,
    val unit: String,
    val localLocation: String,
    val palletState: PalletState,
    val blocked: Boolean,
)
