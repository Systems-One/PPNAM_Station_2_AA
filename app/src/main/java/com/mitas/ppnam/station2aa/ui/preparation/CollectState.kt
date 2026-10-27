package com.mitas.ppnam.station2aa.ui.preparation

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.BagGuide
import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.SourceFit
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.model.validateAmount
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer

/**
 * Where the operator is in collecting one pallet (simplified collect flow). A step that
 * [protectsPallet] holds work in progress: a new scan must not silently replace it.
 */
sealed interface CollectStep {
    data object Idle : CollectStep

    /** `identify` sent for [code]; waiting for Station 2. */
    data class Identifying(val code: String) : CollectStep

    /** An outstanding row of this PREP: take [amountText] kg ([guide] is display only). */
    data class Identified(
        val source: SourceInfo,
        val fit: SourceFit.InMix,
        val amountText: String,
        val guide: BagGuide?,
        val check: AmountCheck,
    ) : CollectStep

    /**
     * The pallet's material is not a row of this PREP: add it, with a required [reason]. Rajoo may
     * instead pick [substituteFor], the uncaptured row it replaces. [capAtStock] limits the amount
     * to the pallet's stock; a Rajoo full-job total may span several pallets, so it doesn't.
     */
    data class NotInMix(
        val source: SourceInfo,
        val amountText: String,
        val reason: String,
        val substituteFor: String? = null,
        val capAtStock: Boolean = true,
    ) : CollectStep {
        val check: AmountCheck
            get() = validateAmount(amountText, null, source.available.takeIf { capAtStock }, source.unit.ifBlank { "kg" })
        val canAdd: Boolean
            get() = check is AmountCheck.Valid && reason.trim().length in 1..GeneralCollectionUseCase.ADJUST_REASON_MAX
    }

    /** The pallet's material is an excluded row: it cannot be collected. */
    data class Excluded(val source: SourceInfo, val materialName: String) : CollectStep

    /** Station 2 says the pallet cannot be used ([reason]), or its row is already complete. */
    data class Unusable(val source: SourceInfo, val reason: String) : CollectStep

    data class Unknown(val message: String) : CollectStep

    /** Station 2 could not identify the pallet: the operator picks the ingredient row. */
    data class Fallback(
        val code: String,
        val pickedMaterial: String?,
        val amountText: String,
        val check: AmountCheck? = null,
        /** The identify got no reply in time (as opposed to being unsupported). */
        val timedOut: Boolean = false,
    ) : CollectStep {
        fun fallbackTitle(): String =
            if (timedOut) "Station 2 didn't answer in time — pick the ingredient"
            else "Station 2 couldn't identify this pallet — pick the ingredient"
    }

    /** [done] of [total] captures answered so far. */
    data class Submitting(val code: String, val name: String, val done: Int, val total: Int) : CollectStep

    /** A pallet the operator is part-way through: a new scan is refused with "Finish or cancel". */
    val protectsPallet: Boolean
        get() = this is Identifying || this is Identified || this is NotInMix || this is Fallback || this is Submitting
}

/** The outcome of the last submit, shown at the top of the list until the next scan. */
sealed interface ResultBanner {
    data class Accepted(
        val text: String,
        val completedMaterialCode: String? = null,
        val warning: String? = null,
    ) : ResultBanner

    /** [loggedLine] is the server's logged exception (date/time, operator), when it sent one. */
    data class Rejected(val text: String, val loggedLine: String? = null, val title: String = "Not recorded") : ResultBanner

    /** A red banner with no Station 2 reply behind it (e.g. "Not a machine — X"). */
    data class Error(val title: String, val detail: String? = null) : ResultBanner

    /** [recorded] of [total] captures went through ([recordedKg] kg); [rejection] stopped the rest. */
    data class Partial(
        val recorded: Int,
        val total: Int,
        val recordedKg: String,
        val rejection: String,
        val loggedLine: String? = null,
    ) : ResultBanner
}

/**
 * Mixer / production stage: a scanned machine waiting for the operator's tap. [protects] means a
 * new scan is refused with "Finish or cancel first".
 */
sealed interface MachineStep {
    data object Idle : MachineStep

    /** [scan] is StartMix, StartProduction, Finish or Disabled; [code] is the text that was scanned. */
    data class Confirm(val code: String, val scan: MachineScan) : MachineStep

    data object Sending : MachineStep
}

/** Stages in which a scan means a machine, not a pallet. */
internal val PrepStage.isMachineStage: Boolean
    get() = this == PrepStage.ReadyForMixer || this == PrepStage.Mixing || this == PrepStage.ReadyForNextMix ||
        this == PrepStage.ReadyForProduction || this == PrepStage.Producing

sealed interface OutcomeMessage {
    data class Info(val text: String) : OutcomeMessage
}

data class PreparationUiState(
    val prepId: String,
    val snapshot: PrepSnapshot? = null,
    val loading: Boolean = false,
    val readError: String? = null,
    val inFlight: Boolean = false,
    val step: CollectStep = CollectStep.Idle,
    val machine: MachineStep = MachineStep.Idle,
    val banner: ResultBanner? = null,
    /** Unresolved/ManagerReconcile outbox entries whose targetId is this PREP. */
    val blocking: List<PendingCommand> = emptyList(),
    val message: OutcomeMessage? = null,
    /**
     * A capture/confirm Station 2 never ran (spec §6), offered for an explicit replacement. Set
     * only after a fresh read; accepting it sends a NEW command, guarded like a tap.
     */
    val offer: ReplacementOffer? = null,
    /** A tapped row's Less/More/Omit decision being filled in (contract §6). */
    val decision: IngredientDecision? = null,
) {
    val stage: PrepStage? get() = snapshot?.preparation?.stage
    val canScan: Boolean get() = stage == PrepStage.Collecting && !inFlight && blocking.isEmpty()
    val canScanMachine: Boolean get() = stage?.isMachineStage == true && !inFlight && blocking.isEmpty()

    /**
     * Rows can be changed only while the PREP is unconfirmed and unassigned, collecting or awaiting
     * confirmation (contract §6), and the screen is otherwise idle.
     */
    val canDecide: Boolean
        get() = snapshot?.preparation?.let { p ->
            (p.stage == PrepStage.Collecting || p.stage == PrepStage.AwaitingConfirmation) &&
                p.confirmedAtUtc == null && p.mixerId == null && p.productionId == null
        } == true && !inFlight && blocking.isEmpty() && step == CollectStep.Idle

    /** Every non-excluded row is collected (and there is at least one). */
    val allCollected: Boolean
        get() = snapshot?.preparation?.materials.orEmpty().filterNot { it.excluded }
            .let { rows -> rows.isNotEmpty() && rows.all { it.isComplete } }

    /** "Mix complete": Station 2 is waiting for it (enough on its own), or every ingredient is collected. */
    val canConfirm: Boolean
        get() = (stage == PrepStage.AwaitingConfirmation || (stage == PrepStage.Collecting && allCollected)) &&
            !inFlight && blocking.isEmpty()
}
