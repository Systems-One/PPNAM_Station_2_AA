package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.InfoBlue
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import com.mitas.ppnam.station2aa.ui.theme.rememberReducedMotion

/**
 * The five states a status-driven card can be in, shared across every screen that shows job
 * cards, equipment, or areas as color-coded cards (design: 2026-07-30-android-ui-ux-overhaul §3.4).
 */
enum class StatusTone {
    Ready, Running, Warning, Danger, Idle;

    fun color(): Color = when (this) {
        Ready -> SuccessGreen
        Running -> InfoBlue
        Warning -> WarningOrange
        Danger -> DangerRed
        Idle -> TextMuted
    }
}

/**
 * The shared color-coded card shell: a tone-tinted border on the graphite surface. Chrome
 * (border/background/shape/click) is shared; layout inside is not, since different callers (a
 * job card, a machine card, an area card) need a different number of lines — `content` gets the
 * tone's accent color so callers can tint their own status text consistently with the border.
 */
@Composable
fun StatusCard(
    tone: StatusTone = StatusTone.Idle,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /**
     * Whether this is the current scan/tap target, independent of [tone]. A card can be e.g.
     * `Warning`-toned AND highlighted at once — status (what state something is in) and highlight
     * (is this the thing to act on right now) are two different questions, so they get two
     * parameters rather than overloading [tone] to answer both.
     */
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(accent: Color) -> Unit,
) {
    // The border IS the "what do I act on now" signal, so its state changes animate instead of
    // teleporting: a critically damped spring (no overshoot — a tap carries no momentum to
    // justify bounce) makes the highlight visibly travel from the old card to the new one.
    // Snap under reduced motion; the state change itself must never be dropped.
    val reducedMotion = rememberReducedMotion()
    val targetAccent = tone.color()
    val accentColor by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = if (reducedMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "status_accent",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            highlighted -> BrandTint
            tone == StatusTone.Idle -> GraphiteBorder
            else -> targetAccent
        },
        animationSpec = if (reducedMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "status_border_color",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            highlighted -> 3.dp
            tone == StatusTone.Idle -> 1.dp
            else -> 2.dp
        },
        animationSpec = if (reducedMotion) snap() else spring(
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = Dp.VisibilityThreshold,
        ),
        label = "status_border_width",
    )
    val shape = RoundedCornerShape(16.dp)
    var cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(enabled = enabled, onClick = onClick)
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = shape,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content(accentColor)
        }
    }
}
