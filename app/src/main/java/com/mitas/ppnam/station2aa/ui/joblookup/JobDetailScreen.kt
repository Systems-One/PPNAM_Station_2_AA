package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.components.LabelValueRow
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.util.formatStationTimestamp

/** Read-only: no scanning, no actions, no mutations. */
@Composable
fun JobDetailScreen(
    jobCard: String,
    onBack: () -> Unit,
    viewModel: JobLookupViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(jobCard) { viewModel.openDetail(jobCard) }

    AppScaffold(
        title = jobCard,
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
        loading = state.detailLoading,
    ) { padding ->
        val detail = state.detail?.takeIf { it.jobCard == jobCard }
        when {
            detail != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (detail.product.isNotBlank()) {
                            Text(detail.product, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        }
                        LabelValueRow("Required mixes", detail.requiredMixes.toString())
                        LabelValueRow("Prepared mixes", detail.allocatedMixes.toString())
                        LabelValueRow(
                            "Output per mix",
                            if (detail.unit.isBlank()) formatQuantity(detail.outputPerMix)
                            else "${formatQuantity(detail.outputPerMix)} ${detail.unit}",
                        )
                        detail.capturedAtUtc?.let { LabelValueRow("Loaded", formatStationTimestamp(it.toString())) }
                        if (detail.closed) LabelValueRow("Status", "Closed")
                        state.detailError?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Materials", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    }
                }
                // Keyed by position as well as code: a BOM listing one code twice must not crash LazyColumn.
                itemsIndexed(detail.materials, key = { index, m -> "$index-${m.code}" }) { _, material ->
                    ListItem(
                        modifier = Modifier.alpha(if (material.excluded) 0.5f else 1f),
                        headlineContent = { Text(material.name.ifBlank { material.code }, color = TextPrimary) },
                        supportingContent = { Text(material.quantityLine(), color = TextMuted) },
                        trailingContent = { Text(if (material.excluded) "Excluded" else material.code, color = TextMuted) },
                    )
                }
                if (detail.preparations.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Preparations", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    }
                    items(detail.preparations, key = { it.id }) { prep ->
                        ListItem(
                            headlineContent = { Text(prep.id, color = TextPrimary) },
                            supportingContent = { Text(prep.summaryLine(), color = TextMuted) },
                        )
                    }
                }
            }
            state.detailError != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(state.detailError!!, color = TextMuted)
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
