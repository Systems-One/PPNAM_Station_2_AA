package com.mitas.ppnam.station2aa.ui.preparation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/** The wording that differs between a General preparation and a full Rajoo job. */
enum class DecisionScope(val currentTotal: String, val newTotal: String, val noun: String, val hint: String) {
    Preparation(
        "Current preparation total", "New total for this preparation", "this preparation",
        "Tap an ingredient to change its preparation total or omit it",
    ),
    RajooJob(
        "Current full-job total", "New total for the whole job", "this job",
        "Tap an ingredient to change its full-job total or omit it",
    ),
}

/** The decision dialog's callbacks. */
class DecisionActions(
    val onKind: (IngredientDecisionKind) -> Unit,
    val onTotal: (String) -> Unit,
    val onReason: (String) -> Unit,
    val onSend: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Less / More / Omit for one row (contract §6). Shows the current target, what is collected, the
 * proposed TOTAL for the [scope], the difference and what would remain; nothing is captured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IngredientDecisionDialog(decision: IngredientDecision, scope: DecisionScope, busy: Boolean, actions: DecisionActions) {
    val m = decision.material
    val unit = decision.unit
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text("Change ${m.name}", color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.code, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Figure(scope.currentTotal, "${m.required.plainText()} $unit")
                Figure("Collected", "${m.collected.plainText()} $unit")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = decision.kind == IngredientDecisionKind.Quantity,
                        onClick = { actions.onKind(IngredientDecisionKind.Quantity) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Less / more") }
                    SegmentedButton(
                        selected = decision.kind == IngredientDecisionKind.Omit,
                        onClick = { actions.onKind(IngredientDecisionKind.Omit) },
                        enabled = decision.canOmit,
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Omit") }
                }
                if (decision.kind == IngredientDecisionKind.Quantity) {
                    OutlinedTextField(
                        value = decision.totalText, onValueChange = actions.onTotal,
                        label = { Text("${scope.newTotal} ($unit)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    decision.newTotal?.let { total ->
                        val diff = decision.difference!!
                        Figure("Proposed total", "${total.plainText()} $unit")
                        Figure(if (diff.signum() < 0) "Less" else "More", "${if (diff.signum() > 0) "+" else ""}${diff.plainText()} $unit")
                        Figure("Still needed after", "${decision.remainingAfter!!.plainText()} $unit")
                    }
                } else {
                    Text("Sets the target to 0 and excludes ${m.name} from ${scope.noun}.", color = TextMuted)
                }
                OutlinedTextField(
                    value = decision.reason, onValueChange = actions.onReason,
                    label = { Text("Reason (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                decision.problems.firstOrNull()?.takeIf { decision.totalText.isNotBlank() || decision.kind == IngredientDecisionKind.Omit }?.let {
                    Text(it, color = WarningOrange, style = MaterialTheme.typography.bodySmall)
                }
                Text("Logged as an exception for manager review. No stock is taken until you scan and record a bag.",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSend, enabled = decision.canSend && !busy) {
                Text(if (decision.kind == IngredientDecisionKind.Omit) "Omit ingredient" else "Change total",
                    color = if (decision.canSend && !busy) BrandTint else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancel) { Text("Cancel") } },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun Figure(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}
