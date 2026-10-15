package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot

/** Longest `reason` accepted on `prepare`. Spec open question Q5, proposed default. */
const val PREPARE_REASON_MAX = 1000

/**
 * The "prepare N mixes" form. Pure: nothing is defaulted, nothing is clamped. An unanswered
 * Include/Exclude stays null until the operator chooses (contract §7).
 */
data class PrepareForm(
    val jobCard: String,
    val available: Int,
    val prompts: List<ChoicePrompt>,
    /** null = unanswered. Never pre-filled (contract §7: explicit). */
    val answers: Map<String, Boolean?>,
    val mixCountText: String,
    val reason: String,
    val exceptionListRevision: Int?,
    /** Non-excluded job material codes, to block an all-excluded recipe before sending. */
    val materialCodes: List<String>,
) {
    val mixCount: Int?
        get() = mixCountText.trim().takeIf { Regex("[0-9]{1,9}").matches(it) }?.toInt()

    val problems: List<String>
        get() = buildList {
            if (available <= 0) {
                add("Nothing left to prepare for this job")
            } else {
                val n = mixCount
                if (n == null || n < 1 || n > available) add("N must be a whole number from 1 to $available")
            }
            prompts.forEach { if (answers[it.code] == null) add("Choose Include or Exclude for ${it.code}") }
            if (prompts.isNotEmpty() && exceptionListRevision == null) {
                add("Reload the job \u2014 Station 2 sent no ingredient list revision")
            }
            if (materialCodes.isNotEmpty() && materialCodes.all { answers[it] == false }) {
                add("Excluding every material leaves nothing to prepare")
            }
            if (reason.length > PREPARE_REASON_MAX) add("Reason is longer than $PREPARE_REASON_MAX characters")
        }

    /** In prompt order; only valid when [problems] is empty. */
    fun answersForWire(): List<Rev2ChoiceAnswer> =
        prompts.map { Rev2ChoiceAnswer(it.code, answers.getValue(it.code) ?: error("unanswered ${it.code}")) }

    /** Keep answers for codes still prompted; drop the rest (after a re-read). The typed N is never rewritten. */
    fun rebasedOn(snapshot: PrepSnapshot): PrepareForm {
        val fresh = from(snapshot) ?: return this
        return fresh.copy(
            answers = fresh.prompts.associate { it.code to answers[it.code] },
            mixCountText = mixCountText,
            reason = reason,
        )
    }

    companion object {
        fun from(snapshot: PrepSnapshot): PrepareForm? {
            val detail = snapshot.lookup.detail ?: return null
            return PrepareForm(
                jobCard = detail.jobCard,
                available = detail.mixProgress?.availableToPrepareMixes ?: 0,
                prompts = snapshot.requiredChoices,
                answers = snapshot.requiredChoices.associate { it.code to null },
                mixCountText = "",
                reason = "",
                exceptionListRevision = snapshot.exceptionListRevision,
                materialCodes = detail.materials.filterNot { it.excluded }.map { it.code },
            )
        }
    }
}
