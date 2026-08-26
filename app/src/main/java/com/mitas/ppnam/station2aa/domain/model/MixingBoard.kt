package com.mitas.ppnam.station2aa.domain.model

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode

/** The five fixed v4.0 mixing areas (§6). Server-authoritative; never extended locally. */
enum class MixingArea(val wire: String, val display: String, val description: String) {
    Dolci("DolciBulkMixing", "DOLCI", "3 fixed direct-feed mixers"),
    Main("MainMixingRoom", "Main Mixing Room", "5 mixers · allocate to any area but Rajoo"),
    Jandi("JandiBulkMixing", "JANDI", "Bulk mixer + Jandi 4 composite"),
    Mackie("MackieBulkMixing", "Mackie", "1 fixed direct-feed mixer"),
    Rajoo("RajooMachineMixing", "Rajoo", "3-layer gravimetric · never from Main Room");

    companion object {
        fun fromWire(value: String?): MixingArea? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The server's three equipment roles (§6). Pass-through strings — unknown roles are tolerated —
 * but these three distinguish a [Mixer] (starts from a collection) from a [Transfer] (the JANDI
 * drum, starts a transfer cycle from a finished mix) and a [ProductionMachine] destination.
 */
object EquipmentRole {
    const val MIXER = "Mixer"
    const val TRANSFER = "Transfer"
    const val PRODUCTION_MACHINE = "ProductionMachine"
}

data class Equipment(
    val machineCode: String,
    val displayName: String,
    val area: MixingArea?,
    /** Mixer | Transfer | ProductionMachine — pass-through; unknown roles tolerated. */
    val role: String,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    /** Available | InUse | Disabled. */
    val status: String,
    val productLayer: Int?,
    val currentCycleId: String?,
    val currentJobCardNumber: String?,
    val currentMixBatchIds: List<String>,
    val validDestinationMachineCodes: List<String>,
    val routeDescription: String,
    /**
     * Whether this handheld may scan this machine now. Server-decided.
     *
     * The contract forbids inferring availability or scan permission locally, so this is the only
     * thing that may gate the scan affordance.
     */
    val scanAllowed: Boolean = false,
    val currentCollectionId: String? = null,
    val currentProductionRunId: String? = null,
    val fixedDestinationMachineCode: String? = null,
)

data class ReadyMix(
    val mixBatchId: String,
    val collectionId: String,
    val area: MixingArea?,
    val jobCardNumber: String,
    val sourceMixerCode: String,
    val mixerDisplayName: String,
    val status: String,
    val validNextMachineCodes: List<String>,
    val nextStepDescription: String,
    /** 4.1: `ForceClosed` marks a quarantined mix. */
    val completionMode: String? = null,
    /**
     * 4.1: false for a force-closed/quarantined mix, which may not be sent to production until a
     * Manager/Admin Release or Discard at the desk. Backend issue B2.
     */
    val isAssignable: Boolean = true,
)

data class ActiveCycle(
    val cycleId: String,
    val machineCode: String,
    val area: MixingArea?,
    val role: String,
    val jobCardNumber: String,
    val collectionId: String?,
    val mixBatchIds: List<String>,
    val productionRunId: String?,
    val startedAtUtc: String,
    val startedByOperatorId: String,
    val mixBatchId: String = "",
    val destinationMachineCode: String? = null,
    val productLayer: Int? = null,
    val status: String = "",
)

/** One source feeding a production run. Composite runs carry several, with differing job cards. */
data class RunInput(
    val inputRole: String,
    val jobCardNumber: String,
    val productionOrderDocumentNumber: String,
    val collectionId: String,
    val mixBatchId: String,
    val sourceMixerCode: String,
    val productLayer: Int?,
)

/** The single JANDI drum, reserved from fill until JANDI 4 consumes it. */
data class JandiDrum(
    val status: String,
    /** Null while the drum is empty — there is no batch on it to name. */
    val jobCardNumber: String?,
    val collectionId: String?,
    val mixBatchId: String?,
    val activeTransferCycleId: String?,
    val filledAtUtc: String?,
    val scanGuidance: String,
)

data class ActiveRun(
    val productionRunId: String,
    val machineCode: String,
    val status: String,
    val startedAtUtc: String,
    val inputs: List<RunInput> = emptyList(),
)

data class AreaOverview(
    val equipment: List<Equipment>,
    val activeCycles: List<ActiveCycle>,
    val readyMixes: List<ReadyMix>,
    val activeRuns: List<ActiveRun>,
    /** Collections that can start a mixer. */
    val readyCollections: List<ReadyCollection> = emptyList(),
    val jandiDrum: JandiDrum? = null,
    val nextAction: String = "",
) {
    companion object {
        val EMPTY = AreaOverview(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * A completed collection that can start a mixer.
 *
 * One completed collection creates exactly one physical mix. Starting another mix or Rajoo layer
 * requires another completed collection; collections may share a job card.
 */
data class ReadyCollection(
    val jobCardNumber: String,
    val collectionId: String,
    val productName: String,
    val productCode: String = "",
    val status: String = "",
    /** The mixer codes this collection may legally start. Server-decided; never inferred. */
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
)

/** One collected manual line of a collection — the Rajoo dose sheet's row source. */
data class CollectedMaterial(
    val materialCode: String,
    val materialName: String,
    val collectedQty: Double,
)

data class LayerInput(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * The outcome of one machine-cycle operation. [Accepted] always carries the embedded
 * areaStatus — a business rejection also embeds it (§8), since every area has equipment.
 * [Rejected.areaStatus] is null when the rejection was envelope/session-level (session_required,
 * message_expired, client_upgrade_required, message_id_reused…) and so carried no operational
 * area data — the board must keep its current picture rather than overwrite it with emptiness.
 * [Failed] means Station 2 never decided (timeout/transport) and carries nothing.
 */
sealed class MachineCycleOutcome {
    data class Accepted(
        val action: String?,
        val machineCode: String,
        val cycleId: String?,
        val mixBatchId: String?,
        val productionRunId: String?,
        val affectedMixBatchIds: List<String>,
        val alreadyFinished: Boolean,
        val forceClosed: Boolean,
        val approverDisplayName: String?,
        val areaStatus: AreaOverview,
        val destinationMachineCode: String? = null,
        val resultingStatus: String? = null,
        val productLayer: Int? = null,
        val inputs: List<RunInput> = emptyList(),
        val sapIssuePrepared: Boolean = false,
    ) : MachineCycleOutcome()

    data class Rejected(
        val errorCode: ErrorCode?,
        val reason: String,
        val areaStatus: AreaOverview?,
    ) : MachineCycleOutcome()

    data class Failed(val message: String) : MachineCycleOutcome()
}
