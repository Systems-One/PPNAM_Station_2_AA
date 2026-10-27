package com.mitas.ppnam.station2aa.ui.preparation

import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.validateAmount
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import java.math.BigDecimal

/** What a tapped ingredient row's decision does (contract §6), for a General PREP or a Rajoo job. */
enum class IngredientDecisionKind {
    /** Less or More ingredient: a new full-job total. */
    Quantity,

    /** Target zero and excluded. */
    Omit,
}

/**
 * The operator's decision on one row, before it is sent. Pure: [problems] lists every reason it
 * cannot be sent yet. [totalText] is always the NEW TOTAL for the whole scope (the preparation, or
 * the full Rajoo job), never an extra amount on top (contract §6).
 */
data class IngredientDecision(
    val material: PrepMaterial,
    /** Included rows with a positive target other than [material]: Omit must leave at least one. */
    val otherPositiveRows: Int,
    val kind: IngredientDecisionKind = IngredientDecisionKind.Quantity,
    val totalText: String = "",
    val reason: String = "",
) {
    val unit: String get() = material.unit.ifBlank { "kg" }

    /** A row with captured stock keeps it: it can't be omitted. */
    val canOmit: Boolean get() = material.collected.signum() == 0 && otherPositiveRows > 0

    /** The parsed new total, when it is a valid positive amount. */
    val newTotal: BigDecimal?
        get() = (validateAmount(totalText, null, null, unit) as? AmountCheck.Valid)?.amount

    /** Proposed total minus the current target (negative is Less, positive is More). */
    val difference: BigDecimal? get() = newTotal?.subtract(material.required)

    /** What would still be needed after the change. */
    val remainingAfter: BigDecimal? get() = newTotal?.subtract(material.collected)?.max(BigDecimal.ZERO)

    val problems: List<String>
        get() = buildList {
            when (kind) {
                IngredientDecisionKind.Quantity -> {
                    val check = validateAmount(totalText, null, null, unit)
                    val total = (check as? AmountCheck.Valid)?.amount
                    when {
                        check is AmountCheck.Invalid -> add(check.problem.message)
                        total == null -> Unit
                        total > RajooUseCase.MAX_TARGET -> add("At most ${RajooUseCase.MAX_TARGET.toPlainString()} $unit")
                        total.compareTo(material.required) == 0 -> add("That is the current total — enter a different one")
                        total < material.collected ->
                            add("Can't go below the ${material.collected.plainText()} $unit already collected")
                    }
                }
                IngredientDecisionKind.Omit -> when {
                    material.collected.signum() > 0 ->
                        add("${material.collected.plainText()} $unit is already collected — change the total instead")
                    otherPositiveRows == 0 -> add("At least one ingredient must stay")
                }
            }
            if (reason.trim().isEmpty()) add("Enter a reason")
            if (reason.trim().length > GeneralCollectionUseCase.ADJUST_REASON_MAX) {
                add("Reason is longer than ${GeneralCollectionUseCase.ADJUST_REASON_MAX} characters")
            }
        }

    val canSend: Boolean get() = problems.isEmpty()

    companion object {
        /** A decision on [material], or null when the row can't be changed (excluded). */
        fun on(material: PrepMaterial, rows: List<PrepMaterial>): IngredientDecision? {
            if (material.excluded) return null
            val others = rows.count { it.code != material.code && !it.excluded && it.required.signum() > 0 }
            return IngredientDecision(material, others)
        }
    }
}
