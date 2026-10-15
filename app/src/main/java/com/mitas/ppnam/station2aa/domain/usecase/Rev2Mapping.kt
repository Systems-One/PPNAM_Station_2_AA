package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CollectionException
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
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.Preparation
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

internal fun Rev2Snapshot.toPrepSnapshot(): PrepSnapshot = PrepSnapshot(
    lookup = toJobLookupSnapshot(),
    preparation = preparation?.let { p ->
        Preparation(
            id = p.id, jobId = p.jobId, mixCount = p.mixCount, mixed = p.mixed, produced = p.produced,
            stage = PrepStage.fromWire(p.stage), stageRaw = p.stage, collectionRevision = p.collectionRevision,
            startedAtUtc = p.startedAtUtc, startedBy = p.startedBy, confirmedAtUtc = p.confirmedAtUtc,
            materials = p.materials.map {
                PrepMaterial(it.code, it.name, it.unit, it.perMix, it.required, it.collected, it.excluded, it.originalRequired)
            },
        )
    },
    requiredChoices = requiredIngredientChoices.map { ChoicePrompt(it.code, it.description, it.note) },
    exceptionListRevision = exceptionListRevision,
    collectionExceptions = collectionExceptions.map { it.toDomain() },
    commandExceptions = commandExceptions.map { it.toDomain() },
    resultTargetId = result?.targetId,
    resultMessage = result?.message.orEmpty(),
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
