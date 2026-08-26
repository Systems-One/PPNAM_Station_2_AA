package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.annotations.SerializedName

/** `mixing_overview_requested` — every filter optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val jobCardNumber: String? = null,
    val collectionId: String? = null,
)

/** Equipment `status`. */
object EquipmentStatus {
    const val AVAILABLE = "Available"
    const val IN_USE = "InUse"
    const val DISABLED = "Disabled"
}

data class EquipmentDto(
    val currentJobCardNumber: String? = null,
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val machineCode: String = "",
    val displayName: String = "",
    val isEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    /** [EquipmentStatus] — rendered verbatim, never inferred locally (§13.7). */
    val status: String = "",
    val productLayer: Int? = null,
    val currentCycleId: String? = null,

    /**
     * Whether THIS handheld may scan this machine right now.
     *
     * The contract's rule is absolute — "Android must not infer availability or scan permission
     * locally" — so this is the only thing that may gate the scan affordance.
     */
    val scanAllowed: Boolean = false,

    val currentMixBatchIds: List<String> = emptyList(),
    val validDestinationMachineCodes: List<String> = emptyList(),
    val routeDescription: String = "",
    val currentCollectionId: String? = null,
    val currentProductionRunId: String? = null,
    val fixedDestinationMachineCode: String? = null,
)

/** Mix `status`. 4.1 adds `Quarantined` for a force-closed mix. */
object MixStatus {
    /** What Station 2 actually reports for a finished, unassigned mix. */
    const val READY_FOR_ALLOCATION = "ReadyForAllocation"
    const val READY_FOR_TRANSFER = "ReadyForTransfer"
    const val READY_FOR_PRODUCTION = "ReadyForProduction"
    const val QUARANTINED = "Quarantined"
    const val CANCELLED = "Cancelled"
}

/** `completionMode` on a mix. */
object CompletionMode {
    const val NORMAL = "Normal"
    const val FORCE_CLOSED = "ForceClosed"
}

data class ReadyMixDto(
    val jobCardNumber: String = "",
    val mixBatchId: String = "",
    val collectionId: String = "",
    val mixingArea: String = "",
    /**
     * The mixer this batch came off.
     *
     * Station 2 spells it `mixerCode` on a mix and `sourceMixerCode` only on a run input — the
     * contract's `readyMixes[]` section says "source mixer" in prose and names no key, which is
     * how this ended up reading the wrong one and silently rendering an empty mixer. The alternate
     * keeps both spellings parsing if either side moves.
     */
    @SerializedName(value = "mixerCode", alternate = ["sourceMixerCode"])
    val sourceMixerCode: String = "",
    val mixerDisplayName: String = "",
    val productLayer: Int? = null,
    /** [MixStatus]. */
    val status: String = "",
    /** The ONLY legitimate source of destination choices (§13.8). */
    val validNextMachineCodes: List<String> = emptyList(),
    val nextStepDescription: String = "",

    // ---- 4.1 force-close quarantine ---------------------------------------------------------

    /** [CompletionMode]. */
    val completionMode: String? = null,
) {
    /**
     * Whether this mix may be sent to a production machine.
     *
     * Backend issue B2 was that a force-closed cycle still yielded a usable mix. 4.1 makes such a
     * mix `Quarantined` and "never assignable until an audited Manager/Admin Release or Discard",
     * so the app must not offer it as a source — the operator cannot clear it from the handheld.
     */
    val isAssignable: Boolean
        get() = status != MixStatus.QUARANTINED &&
            status != MixStatus.CANCELLED &&
            completionMode != CompletionMode.FORCE_CLOSED
}

data class ActiveCycleDto(
    val jobCardNumber: String = "",
    val cycleId: String = "",
    val machineCode: String = "",
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val collectionId: String? = null,
    val mixBatchIds: List<String> = emptyList(),
    val productionRunId: String? = null,
    val startedAtUtc: String = "",
    val startedByOperatorId: String = "",
    val mixBatchId: String = "",
    val destinationMachineCode: String? = null,
    val productLayer: Int? = null,
    val status: String = "",
)

/**
 * One source feeding a production run.
 *
 * A JANDI 4 run takes the drum plus one Main mix, and a Rajoo run takes one layer per started
 * gravimetric mixer — each from its own completed collection. Their job cards may legitimately
 * differ, which is why a run carries a list of inputs rather than one JC.
 */
data class RunInputDto(
    val inputRole: String = "",
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val mixBatchId: String = "",
    val sourceMixerCode: String = "",
    val productLayer: Int? = null,
)

/**
 * The single JANDI drum. Once filled it stays reserved until JANDI 4 consumes it, so there is
 * exactly one of these in an area overview, not a list.
 */
data class JandiDrumDto(
    val status: String = "",
    /**
     * Null until the drum is filled — an idle drum arrives as explicit JSON nulls, not omissions,
     * so these cannot be non-null with a `""` default: Gson writes the null straight over the
     * default and the first non-null consumer throws.
     */
    val jobCardNumber: String? = null,
    val collectionId: String? = null,
    val mixBatchId: String? = null,
    val activeTransferCycleId: String? = null,
    val filledAtUtc: String? = null,
    val scanGuidance: String = "",
)

data class ActiveRunDto(
    val productionRunId: String = "",
    val machineCode: String = "",
    val status: String = "",
    val startedAtUtc: String = "",
    val inputs: List<RunInputDto> = emptyList(),
)

/**
 * One collection in `readyCollections[]` that can start a mixer.
 *
 * One completed collection creates exactly one physical mix. The mixers it may legally start come
 * from [validMixerCodes] — server-decided, never inferred locally.
 */
data class ReadyCollectionDto(
    val collectionId: String = "",
    val jobCardNumber: String = "",
    val productCode: String = "",
    val productName: String = "",
    val status: String = "",
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
)

/** `mixing_overview_result`, and the `areaStatus` embedded in every machine result (§8). */
data class MixingOverviewResponse(
    val mixingArea: String? = null,
    val equipment: List<EquipmentDto> = emptyList(),
    /** Collections that can start a mixer. */
    val readyCollections: List<ReadyCollectionDto> = emptyList(),
    val activeCycles: List<ActiveCycleDto> = emptyList(),
    /**
     * Only PHYSICAL mixes whose mixer cycle has finished. The contract's warning is worth keeping
     * in sight: "Never synthesize a mix ID for a collection that has not started a mixer."
     */
    val readyMixes: List<ReadyMixDto> = emptyList(),
    val activeRuns: List<ActiveRunDto> = emptyList(),
    val jandiDrum: JandiDrumDto? = null,
    val nextAction: String = "",
)

data class LayerInputDto(
    val materialCode: String,
    val dosingQuantity: Double,
)

/** The three JANDI routes the shared mixer must be given before it starts. */
object JandiRoute {
    const val JANDI_2 = "JAN-02"
    const val JANDI_3 = "JAN-03"
    const val DRUM = "JAN-DRUM-01"

    val ALL = listOf(JANDI_2, JANDI_3, DRUM)
}

/**
 * `machine_cycle_start_requested` — one payload covering six variants.
 *
 * The optional fields could be combined illegally, so nothing constructs this directly: the six
 * named functions on [com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase] are the only
 * builders, and each populates exactly one legal combination.
 *
 * | Variant | Fields sent |
 * |---|---|
 * | DOLCI / Mackie / Main mixer | machineCode, collectionId |
 * | JANDI shared mixer | + destinationMachineCode |
 * | Rajoo layer | + layerInputs (1-5, required) |
 * | JANDI drum transfer | machineCode, mixBatchId |
 * | Main production destination | machineCode, mixBatchId |
 * | JANDI 4 | machineCode, mainSourceMixBatchId OR mainSourceMixerCode |
 */
data class MachineCycleStartPayload(
    val machineCode: String,
    val collectionId: String? = null,
    val destinationMachineCode: String? = null,
    val mixBatchId: String? = null,
    val mainSourceMixBatchId: String? = null,
    val mainSourceMixerCode: String? = null,
    val layerInputs: List<LayerInputDto>? = null,
)

/** `machine_cycle_finish_requested` — the exact scanned machine plus the server-issued cycle id (§9). */
data class MachineCycleFinishPayload(
    val machineCode: String,
    val cycleId: String,
)

/**
 * `machine_cycle_force_close_requested`.
 *
 * 4.1: the manager's password no longer travels here. They prove it in a SCRAM exchange scoped to
 * `Cycle:<cycleId>` / `machine_force_close`, and only the resulting single-use
 * [authorizationToken] is sent.
 *
 * The resulting mix is `Quarantined` and NOT assignable — see [ReadyMixDto.isAssignable].
 */
data class MachineCycleForceClosePayload(
    val machineCode: String,
    val cycleId: String,
    val authorizationToken: String,
    val auditReason: String,
)

/** `machine_cycle_result` — the unified §8 result for start, finish, and force-close. */
data class MachineCycleResultResponse(
    val jobCardNumber: String? = null,
    val action: String? = null,
    val mixingArea: String? = null,
    val equipmentRole: String? = null,
    val machineCode: String? = null,
    val cycleId: String? = null,
    val collectionId: String? = null,
    val mixBatchId: String? = null,
    val productionRunId: String? = null,
    val affectedMixBatchIds: List<String> = emptyList(),
    val alreadyFinished: Boolean = false,
    val forceClosed: Boolean = false,
    /** [CompletionMode] — `ForceClosed` means the resulting mix is quarantined. */
    val completionMode: String? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val sapIssueQueued: Boolean = false,
    val sapProductionOrderChanged: Boolean = false,

    val destinationMachineCode: String? = null,
    val productLayer: Int? = null,
    val resultingStatus: String? = null,
    val inputs: List<RunInputDto> = emptyList(),
    /** A local prepared-only preview. No Mixing action posts to SAP. */
    val sapIssuePrepared: Boolean = false,
    val sapPostingEnabled: Boolean = false,

    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
)
