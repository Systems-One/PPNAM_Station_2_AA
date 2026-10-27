package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CollectionException
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Job
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Material
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2MixProgress
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Source
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.model.ActivePreparation
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.model.MachineKind
import com.mitas.ppnam.station2aa.domain.model.PrepSummary
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.Preparation
import com.mitas.ppnam.station2aa.domain.model.RajooJob
import java.time.Instant

internal fun Rev2Snapshot.toJobLookupSnapshot(): JobLookupSnapshot = JobLookupSnapshot(
    jobs = jobs.map { JobSummary(it.id, it.product, it.requiredMixes, it.closed, it.mixProgress?.toDomain()) },
    detail = job?.let { job ->
        JobDetail(
            jobCard = job.id,
            product = job.product,
            unit = job.unit,
            outputPerMix = job.outputPerMix,
            requiredMixes = job.requiredMixes,
            allocatedMixes = job.allocatedMixes,
            capturedAtUtc = job.capturedAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() },
            closed = job.closed,
            materials = job.materials.map {
                JobMaterial(it.code, it.name, it.unit, it.perMix.toDouble(), it.required.toDouble(), it.collected.toDouble(),
                    it.excluded, it.originalRequired?.toDouble())
            },
            preparations = preparations.filter { it.jobId == job.id }.map {
                JobPreparation(it.id, it.mixCount, it.mixed, it.produced, it.stage)
            },
            mixProgress = job.mixProgress?.toDomain(),
        )
    },
)

internal fun Rev2MixProgress.toDomain(): MixProgress = MixProgress(
    requiredMixes = requiredMixes,
    allocatedMixes = allocatedMixes,
    activeMixes = activeMixes,
    availableToPrepareMixes = availableToPrepareMixes,
    remainingToFinishMixes = remainingToFinishMixes,
    collectedMixes = collectedMixes,
    confirmedMixes = confirmedMixes,
    mixedMixes = mixedMixes,
    producedMixes = producedMixes,
    activePreparations = activePreparations.map {
        ActivePreparation(it.id, it.mixCount, it.stage, it.mixed, it.produced, it.remainingToFinishMixes)
    },
)

internal fun Rev2Snapshot.toPrepSnapshot(): PrepSnapshot {
    val rajoo = job?.takeIf { mode.equals("Rajoo", ignoreCase = true) || it.mode.equals("Rajoo", ignoreCase = true) }
        ?.toRajooJob()
    val base = toGeneralPrepSnapshot()
    return if (rajoo == null) base else base.copy(rajoo = rajoo, preparation = rajoo.asPreparation())
}

private fun Rev2Material.toDomain() = PrepMaterial(code, name, unit, perMix, required, collected, excluded,
    originalRequired, isModified)

private fun Rev2Job.toRajooJob() = RajooJob(
    id = id, product = product, unit = unit, overallQuantity = overallQuantity,
    machineId = rajooMachineId?.takeIf { it.isNotBlank() },
    activeSessionId = activeSessionId?.takeIf { it.isNotBlank() },
    closed = closed, collectionRevision = collectionRevision,
    choicesFrozen = ingredientChoices != null,
    materials = materials.map { it.toDomain() },
)

/**
 * The Rajoo job as the collect screen's "preparation": id is the JC, and the stage is Collecting
 * only while a session is active (so scanning a pallet is possible exactly then).
 */
internal fun RajooJob.asPreparation() = Preparation(
    id = id, jobId = id, mixCount = 0, mixed = 0, produced = 0,
    stage = when {
        closed -> PrepStage.Completed
        hasActiveSession -> PrepStage.Collecting
        else -> PrepStage.Unknown
    },
    stageRaw = when {
        closed -> "Closed"
        hasActiveSession -> "Session active"
        else -> "No active session"
    },
    collectionRevision = collectionRevision, startedAtUtc = null, startedBy = null, confirmedAtUtc = null,
    materials = materials, cycleId = activeSessionId,
)

private fun Rev2Snapshot.toGeneralPrepSnapshot(): PrepSnapshot = PrepSnapshot(
    lookup = toJobLookupSnapshot(),
    preparation = preparation?.let { p ->
        Preparation(
            id = p.id, jobId = p.jobId, mixCount = p.mixCount, mixed = p.mixed, produced = p.produced,
            stage = PrepStage.fromWire(p.stage), stageRaw = p.stage, collectionRevision = p.collectionRevision,
            startedAtUtc = p.startedAtUtc, startedBy = p.startedBy, confirmedAtUtc = p.confirmedAtUtc,
            materials = p.materials.map { it.toDomain() },
            mixerId = p.mixerId, productionId = p.productionId, cycleId = p.cycleId,
        )
    },
    requiredChoices = requiredIngredientChoices.map { ChoicePrompt(it.code, it.description, it.note) },
    exceptionListRevision = exceptionListRevision,
    collectionExceptions = collectionExceptions.map { it.toDomain() },
    commandExceptions = commandExceptions.map { it.toDomain() },
    resultTargetId = result?.targetId,
    resultMessage = result?.message.orEmpty(),
    machines = machines.map {
        Machine(it.id, it.name, it.area, it.code, it.enabled, MachineKind.fromWire(it.kind), it.isProduction,
            it.pairId, it.destinations)
    },
    preparations = preparations.map { PrepSummary(it.id, it.jobId, PrepStage.fromWire(it.stage), it.mixerId, it.productionId) },
)

/** A non-positive bag size is treated as unconfigured. */
internal fun Rev2Source.toDomain(): SourceInfo = SourceInfo(
    code = code, materialCode = materialCode, name = name, unit = unit,
    bagSize = bagSize?.takeIf { it.signum() > 0 }, available = available, usable = usable, reason = reason,
)

private fun Rev2CollectionException.toDomain() =CollectionExceptionRecord(
    id, timestampUtc, operatorId, device, messageId, jobId, batchId, kind, details, reason,
    reviewedAtUtc, reviewedBy, reviewReason,
)
