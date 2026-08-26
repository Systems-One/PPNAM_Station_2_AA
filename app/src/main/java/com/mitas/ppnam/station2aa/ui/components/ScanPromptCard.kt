package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun ScanPromptCard(
    message: String,
    modifier: Modifier = Modifier
) {
    // A slow endless pulse is exactly the vestibular-trigger category reduced motion exists
    // for; a static ring still says "the reader is listening" without the oscillation. The
    // branch is stable for the composition's lifetime (see rememberReducedMotion), so the
    // transition is simply never created when animations are off rather than run and ignored.
    val reducedMotion = rememberReducedMotion()
    val ringProgress = if (reducedMotion) {
        0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "scan_rings")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing)
            ),
            label = "ring_progress"
        ).value
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, GraphiteBorder)
    ) {
        Column(
            modifier = Modifier
                .padding(40.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(88.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.width / 2f
                    if (reducedMotion) {
                        drawCircle(
                            color = AmberPrimary.copy(alpha = 0.35f),
                            radius = maxRadius * 0.7f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else {
                        repeat(3) { i ->
                            val phase = (ringProgress + i / 3f) % 1f
                            val radius = phase * maxRadius
                            val alpha = (1f - phase) * 0.45f
                            drawCircle(
                                color = AmberPrimary.copy(alpha = alpha),
                                radius = radius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Filled.WifiTethering,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}
