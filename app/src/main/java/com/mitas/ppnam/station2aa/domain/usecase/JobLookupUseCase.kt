package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface JobLookupResult {
    data class Loaded(val snapshot: JobLookupSnapshot) : JobLookupResult

    /** [snapshot] is set when Station 2 answered with one anyway (a `rev2_rejected` reply does). */
    data class Failed(val message: String, val snapshot: JobLookupSnapshot? = null) : JobLookupResult
}

/**
 * The two General reads this app makes. Both are reads, so the transport's identical-bytes retry is
 * safe, and an uncertain outcome is resolved simply by reading again.
 */
@Singleton
class JobLookupUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    /** The job list, plus detail for [targetId] (a JC or preparation id) when given. */
    suspend fun read(targetId: String? = null): JobLookupResult {
        val target = targetId?.trim()?.takeIf { it.isNotEmpty() }
        val result = send(Rev2GeneralRequest(action = "read", targetId = target))
        if (target != null && result is JobLookupResult.Loaded && result.snapshot.detail == null) {
            return JobLookupResult.Failed("Station 2 has no General job $target", result.snapshot)
        }
        return result
    }

    /**
     * Loads one job card from SAP into Station 2 and returns it as detail. [rawJobCard] is what the
     * scanner or keyboard produced: a printed JC barcode is the Production Order number, and
     * DataWedge may append a newline.
     */
    suspend fun lookup(rawJobCard: String): JobLookupResult {
        val jobCard = rawJobCard.trim()
        // '0'..'9', not isDigit(): Station 2 accepts ASCII digits only, and isDigit() admits
        // full-width and other Unicode digits it would reject.
        if (jobCard.isEmpty() || !jobCard.all { it in '0'..'9' }) {
            return JobLookupResult.Failed(NOT_DIGITS_MESSAGE)
        }
        val result = send(Rev2GeneralRequest(action = "lookup", jobCard = jobCard))
        if (result is JobLookupResult.Loaded && result.snapshot.detail == null) {
            return JobLookupResult.Failed("Station 2 loaded $jobCard but returned no job", result.snapshot)
        }
        return result
    }

    private suspend fun send(request: Rev2GeneralRequest): JobLookupResult =
        when (val outcome = mqttRepository.request(
            requestType = REQUEST_TYPE,
            responseType = RESPONSE_TYPE,
            payload = request,
            responseClass = Rev2GeneralSnapshot::class.java,
        )) {
            is MqttOutcome.Accepted -> JobLookupResult.Loaded(outcome.body.toDomain())
            is MqttOutcome.Rejected -> JobLookupResult.Failed(outcome.message(), outcome.body?.toDomain())
            is MqttOutcome.NoResponse -> JobLookupResult.Failed(outcome.kind.message())
        }

    companion object {
        const val REQUEST_TYPE = "rev2_general_requested"
        const val RESPONSE_TYPE = "rev2_general_result"
        const val NOT_DIGITS_MESSAGE = "Scan or enter the production order number (digits only)"
    }
}

private fun MqttOutcome.Rejected<*>.message(): String = when (error) {
    ErrorCode.OUTCOME_UNCONFIRMED -> "Station 2 couldn't confirm that — try again"
    ErrorCode.OPERATOR_SESSION_INVALID -> "Your session has ended — sign in again"
    ErrorCode.CLIENT_UPGRADE_REQUIRED -> "This app needs updating before it can talk to Station 2"
    else -> operatorMessage ?: "Station 2 rejected the request"
}

private fun Rev2GeneralSnapshot.toDomain(): JobLookupSnapshot = JobLookupSnapshot(
    jobs = jobs.map { JobSummary(it.id, it.product, it.requiredMixes, it.closed) },
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
                JobMaterial(it.code, it.name, it.unit, it.perMix, it.required, it.collected, it.excluded)
            },
            preparations = preparations.filter { it.jobId == job.id }.map {
                JobPreparation(it.id, it.mixCount, it.mixed, it.produced, it.stage)
            },
        )
    },
)
