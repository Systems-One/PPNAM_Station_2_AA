package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveCycleDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveRunDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiDrumDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute
import com.mitas.ppnam.station2aa.data.mqtt.dto.LayerInputDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleFinishPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleForceClosePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyCollectionDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyMixDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.RunInputDto
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.ActiveRun
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.CollectedMaterial
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.JandiDrum
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.model.ReadyMix
import com.mitas.ppnam.station2aa.domain.model.RunInput
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The board's server operations (§7-§9). Every machine-cycle outcome carries the embedded
 * areaStatus so the board refreshes from the response itself — accepted or rejected —
 * with no extra overview round-trip.
 */
@Singleton
class MixingBoardUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val managerAuthorization: ManagerAuthorization,
) {

    suspend fun fetchOverview(
        area: MixingArea? = null,
        jobCardNumber: String? = null,
        collectionId: String? = null,
    ): Result<AreaOverview> =
        when (
            val outcome = mqttRepository.request(
                requestType = "mixing_overview_requested",
                responseType = "mixing_overview_result",
                payload = MixingOverviewPayload(
                    mixingArea = area?.wire,
                    jobCardNumber = jobCardNumber,
                    collectionId = collectionId,
                ),
                correlationKey = jobCardNumber ?: collectionId,
                responseClass = MixingOverviewResponse::class.java,
            )
        ) {
            // runCatching, because mapping is where a wire surprise lands: a field the server
            // sends as null against a non-null domain type throws inside the constructor, and an
            // exception raised here escapes the Result and kills the ViewModel's coroutine — the
            // board dies on an unreadable overview instead of showing "could not load". An empty
            // `jandiDrum` did exactly that.
            is MqttOutcome.Accepted -> runCatching { outcome.body.toAreaOverview() }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Overview rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /**
     * The collections that can start a mixer.
     *
     * ### Why this doesn't read `active_job_cards_requested`
     *
     * The mixing overview is the authoritative source for which collections can start a mixer,
     * and it is also paged — a "give me all ready collections" call has no way to honour that.
     */
    suspend fun fetchReadyCollections(area: MixingArea? = null): Result<List<ReadyCollection>> =
        fetchOverview(area = area).map { it.readyCollections }

    /**
     * The Rajoo dose sheet's rows: the collection's collected manual lines. Uses the §12
     * capture action `collection_resume_requested` — resuming a ReadyForMixing collection
     * replays its stored snapshot without touching state.
     */
    suspend fun fetchCollectedMaterials(collectionId: String): Result<List<CollectedMaterial>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "collection_resume_requested",
                responseType = "bom_loaded",
                payload = CollectionResumePayload(collectionId = collectionId),
                correlationKey = collectionId,
                responseClass = BomLoadedResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                outcome.body.ingredients
                    .filter { it.issueType != "im_Backflush" && it.collectedQuantity > 0.0 }
                    .map { CollectedMaterial(it.materialCode, it.materialName, it.collectedQuantity) }
            )
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load collection"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /** DOLCI, Mackie, and Main mixers: a completed collection is the only input. */
    suspend fun startMixerFromCollection(machineCode: String, collectionId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, collectionId = collectionId),
            correlationKey = collectionId,
        )

    /**
     * The JANDI shared mixer. The route decides the whole downstream lifecycle — JANDI 2/3 feed
     * directly, the drum route makes the mix ReadyForTransfer — so it is required at start, not
     * chosen afterwards.
     */
    suspend fun startJandiMixer(
        machineCode: String,
        collectionId: String,
        route: String,
    ): MachineCycleOutcome {
        if (route !in JandiRoute.ALL) {
            return rejectedLocally("Select JANDI 2, JANDI 3 or the drum before starting.")
        }
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                collectionId = collectionId,
                destinationMachineCode = route,
            ),
            correlationKey = collectionId,
        )
    }

    /**
     * One Rajoo layer. Each gravimetric mixer is one layer and starts from its own completed
     * collection; 1-5 positive dosing lines are required for every started layer.
     */
    suspend fun startRajooLayer(
        machineCode: String,
        collectionId: String,
        doses: List<LayerInput>,
    ): MachineCycleOutcome {
        val error = when {
            doses.isEmpty() -> "A Rajoo layer needs at least one dose line."
            doses.size > 5 -> "A Rajoo layer takes at most five dose lines."
            doses.any { it.dosingQuantity <= 0.0 } -> "Every dose must be a positive quantity."
            else -> null
        }
        if (error != null) return rejectedLocally(error)
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                collectionId = collectionId,
                layerInputs = doses.map { LayerInputDto(it.materialCode, it.dosingQuantity) },
            ),
            correlationKey = collectionId,
        )
    }

    /**
     * Decanting a ReadyForTransfer JANDI mix into the single drum.
     *
     * This shares a wire shape with [startProductionDestination] but is deliberately a separate
     * function: a transfer cycle and a production run start are different operations, the call
     * sites read correctly this way, and the two can diverge without a refactor. Do not collapse
     * them. (Ruling, 2026-07-28.)
     */
    suspend fun startDrumTransfer(machineCode: String, mixBatchId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, mixBatchId = mixBatchId),
            correlationKey = mixBatchId,
        )

    /**
     * A Main destination scan: assigns and starts exactly one ready mix on one production
     * machine. The separate destination-assignment request that once preceded this has been
     * withdrawn; this single call now does the whole job.
     */
    suspend fun startProductionDestination(machineCode: String, mixBatchId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(machineCode = machineCode, mixBatchId = mixBatchId),
            correlationKey = mixBatchId,
        )

    /**
     * JANDI 4 consumes the current drum plus exactly one ready Main mix, whose job cards may
     * differ. The Main input is named either exactly or by its source mixer code, which resolves
     * only when exactly one eligible output exists — otherwise the server answers
     * `ambiguous_main_mix`.
     */
    suspend fun startJandi4(
        machineCode: String,
        mainSourceMixBatchId: String? = null,
        mainSourceMixerCode: String? = null,
    ): MachineCycleOutcome {
        val byMix = !mainSourceMixBatchId.isNullOrBlank()
        val byMixer = !mainSourceMixerCode.isNullOrBlank()
        if (byMix && byMixer) {
            return rejectedLocally("Name the Main mix either exactly or by its source mixer, not both.")
        }
        if (!byMix && !byMixer) {
            return rejectedLocally("Name the Main mix either exactly or by its source mixer.")
        }
        return cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                mainSourceMixBatchId = mainSourceMixBatchId?.takeIf { byMix },
                mainSourceMixerCode = mainSourceMixerCode?.takeIf { byMixer },
            ),
            correlationKey = mainSourceMixBatchId?.takeIf { byMix } ?: mainSourceMixerCode?.takeIf { byMixer },
        )
    }

    /**
     * A refusal decided here, before anything was sent. areaStatus is null precisely because
     * nothing was attempted server-side — the board must keep the picture it already has.
     */
    private fun rejectedLocally(reason: String) =
        MachineCycleOutcome.Rejected(errorCode = null, reason = reason, areaStatus = null)

    suspend fun finish(machineCode: String, cycleId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_finish_requested",
            MachineCycleFinishPayload(machineCode = machineCode, cycleId = cycleId),
            correlationKey = cycleId,
        )

    /**
     * Force-closes a cycle under Manager/Admin authority.
     *
     * 4.1: the manager's password never reaches this message. It is proved in a SCRAM exchange
     * scoped to this exact cycle and action, and the single-use token that comes back is what
     * travels. A failed authorization is reported as a rejection rather than thrown — the operator
     * needs to see "that manager isn't allowed to force-close" the same way they'd see any other
     * refusal.
     */
    suspend fun forceClose(
        machineCode: String,
        cycleId: String,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ): MachineCycleOutcome {
        val token = managerAuthorization.authorize(
            managerUsername = managerUsername,
            managerPassword = managerPassword,
            action = ManagerAction.ForceClose,
            targetId = cycleId,
        ).getOrElse {
            // No areaStatus: nothing was attempted server-side, so the board must keep the
            // picture it already has rather than blank it.
            return MachineCycleOutcome.Rejected(
                errorCode = null,
                reason = it.message ?: "Manager authorization failed",
                areaStatus = null,
            )
        }

        return cycleRequest(
            "machine_cycle_force_close_requested",
            MachineCycleForceClosePayload(
                machineCode = machineCode,
                cycleId = cycleId,
                authorizationToken = token,
                auditReason = auditReason,
            ),
            correlationKey = cycleId,
        )
    }

    private suspend fun cycleRequest(requestType: String, payload: Any, correlationKey: String?): MachineCycleOutcome =
        when (
            val outcome = mqttRepository.request(
                requestType = requestType,
                responseType = "machine_cycle_result",
                payload = payload,
                correlationKey = correlationKey,
                responseClass = MachineCycleResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> MachineCycleOutcome.Accepted(
                action = outcome.body.action,
                machineCode = outcome.body.machineCode.orEmpty(),
                cycleId = outcome.body.cycleId,
                mixBatchId = outcome.body.mixBatchId,
                productionRunId = outcome.body.productionRunId,
                affectedMixBatchIds = outcome.body.affectedMixBatchIds,
                alreadyFinished = outcome.body.alreadyFinished,
                forceClosed = outcome.body.forceClosed,
                approverDisplayName = outcome.body.approverDisplayName,
                // Degrade the board picture, never the outcome: this cycle has already run on the
                // machine, so reporting it as failed would send the operator to re-scan a mixer
                // that is already going. WireJson stops a wire null reaching the mapping at all —
                // these guards are what keep a mapping bug from reaching the shop floor as a crash.
                areaStatus = runCatching { outcome.body.areaStatus.toAreaOverview() }
                    .getOrDefault(AreaOverview.EMPTY),
                destinationMachineCode = outcome.body.destinationMachineCode,
                resultingStatus = outcome.body.resultingStatus,
                productLayer = outcome.body.productLayer,
                inputs = runCatching { outcome.body.inputs.map { it.toRunInput() } }
                    .getOrDefault(emptyList()),
                sapIssuePrepared = outcome.body.sapIssuePrepared,
            )
            is MqttOutcome.Rejected -> MachineCycleOutcome.Rejected(
                errorCode = outcome.errorCode,
                reason = outcome.reason ?: "Machine cycle rejected",
                // Null already means "keep the board as it is", so a failed mapping degrades into
                // the existing quiet path rather than costing the operator the rejection reason.
                areaStatus = runCatching { outcome.body.areaStatus.toAreaOverviewOrNull() }.getOrNull(),
            )
            is MqttOutcome.NoResponse -> MachineCycleOutcome.Failed(outcome.kind.message())
        }

    /**
     * The embedded areaStatus as domain, or null when it is the empty default. An envelope/session
     * rejection carries no areaStatus (Gson defaults it to empty); a real business rejection always
     * embeds equipment (§8). Null tells the board to keep its current picture rather than blank it.
     */
    private fun MixingOverviewResponse.toAreaOverviewOrNull(): AreaOverview? =
        toAreaOverview().takeUnless {
            it.equipment.isEmpty() && it.activeCycles.isEmpty() &&
                it.readyMixes.isEmpty() && it.activeRuns.isEmpty()
        }

    // ---- DTO -> domain -------------------------------------------------------

    private fun MixingOverviewResponse.toAreaOverview() = AreaOverview(
        equipment = equipment.map { it.toEquipment() },
        activeCycles = activeCycles.map { it.toActiveCycle() },
        readyMixes = readyMixes.map { it.toReadyMix() },
        // §9 activeRuns[]: the contract's own shape is one row per run with an inputs[] list for
        // multi-JC accumulation — but a run that has accumulated inputs from several job cards
        // (e.g. a Rajoo run fed by three collections) has come back from Station 2 as one
        // activeRuns[] row PER input, all sharing the same productionRunId, rather than one row
        // with a 3-element inputs[]. The board's LazyColumn keys on productionRunId
        // (MixingBoardScreen.kt) and Compose crashes outright on a duplicate key, so group
        // defensively and recombine every row's inputs — this reconstructs the contract's
        // intended shape instead of merely working around the crash.
        activeRuns = activeRuns
            .groupBy { it.productionRunId }
            .map { (_, rows) ->
                rows.first().toActiveRun()
                    .copy(inputs = rows.flatMap { row -> row.inputs.map { it.toRunInput() } })
            },
        readyCollections = readyCollections.map { it.toReadyCollection() },
        jandiDrum = jandiDrum?.toJandiDrum(),
        nextAction = nextAction,
    )

    private fun EquipmentDto.toEquipment() = Equipment(
        machineCode = machineCode,
        displayName = displayName,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        isEnabled = isEnabled,
        isAvailable = isAvailable,
        status = status,
        productLayer = productLayer,
        currentCycleId = currentCycleId,
        currentJobCardNumber = currentJobCardNumber,
        currentMixBatchIds = currentMixBatchIds,
        validDestinationMachineCodes = validDestinationMachineCodes,
        routeDescription = routeDescription,
        scanAllowed = scanAllowed,
        currentCollectionId = currentCollectionId,
        currentProductionRunId = currentProductionRunId,
        fixedDestinationMachineCode = fixedDestinationMachineCode,
    )

    private fun ReadyMixDto.toReadyMix() = ReadyMix(
        mixBatchId = mixBatchId,
        collectionId = collectionId,
        area = MixingArea.fromWire(mixingArea),
        jobCardNumber = jobCardNumber,
        sourceMixerCode = sourceMixerCode,
        mixerDisplayName = mixerDisplayName,
        status = status,
        validNextMachineCodes = validNextMachineCodes,
        nextStepDescription = nextStepDescription,
        completionMode = completionMode,
        // Computed on the DTO from status + completionMode, so the "is this quarantined" rule
        // lives next to the wire fields it reads rather than being restated here.
        isAssignable = isAssignable,
    )

    private fun ReadyCollectionDto.toReadyCollection() = ReadyCollection(
        collectionId = collectionId,
        jobCardNumber = jobCardNumber,
        productName = productName,
        productCode = productCode,
        status = status,
        validMixerCodes = validMixerCodes,
        nextAction = nextAction,
    )

    private fun ActiveCycleDto.toActiveCycle() = ActiveCycle(
        cycleId = cycleId,
        machineCode = machineCode,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        jobCardNumber = jobCardNumber,
        collectionId = collectionId,
        mixBatchIds = mixBatchIds,
        productionRunId = productionRunId,
        startedAtUtc = startedAtUtc,
        startedByOperatorId = startedByOperatorId,
        mixBatchId = mixBatchId,
        destinationMachineCode = destinationMachineCode,
        productLayer = productLayer,
        status = status,
    )

    private fun ActiveRunDto.toActiveRun() = ActiveRun(
        productionRunId = productionRunId,
        machineCode = machineCode,
        status = status,
        startedAtUtc = startedAtUtc,
        inputs = inputs.map { it.toRunInput() },
    )

    private fun RunInputDto.toRunInput() = RunInput(
        inputRole = inputRole,
        jobCardNumber = jobCardNumber,
        productionOrderDocumentNumber = productionOrderDocumentNumber,
        collectionId = collectionId,
        mixBatchId = mixBatchId,
        sourceMixerCode = sourceMixerCode,
        productLayer = productLayer,
    )

    private fun JandiDrumDto.toJandiDrum() = JandiDrum(
        status = status,
        jobCardNumber = jobCardNumber,
        collectionId = collectionId,
        mixBatchId = mixBatchId,
        activeTransferCycleId = activeTransferCycleId,
        filledAtUtc = filledAtUtc,
        scanGuidance = scanGuidance,
    )
}
