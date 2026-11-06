package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitas.ppnam.station2aa.R
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange

/**
 * The Home header card, suite standard C2 (reference: Station 5 `activity_main.xml` `cardHeader`).
 * A rounded 18dp card with a 1dp border: the Systems One logo (at most 180x48dp; it shrinks before
 * it reaches the gear), then the gear and the connection pill; below them the logout icon and
 * "name · role". Used on Home only: inner screens keep the back-arrow top bar.
 */
@Composable
fun HomeHeaderCard(
    status: ConnectionStatus,
    operatorName: String?,
    operatorRole: String?,
    onSettings: (() -> Unit)?,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, GraphiteBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // weight(1f) gives the logo only what is left after the gear and the pill: a wide
                // pill such as "Station 2 offline" shrinks the logo instead of pushing the gear
                // over it (the Station 5 regression this card was fixed for).
                Image(
                    painter = painterResource(R.drawable.s1_logo_white),
                    contentDescription = "Systems One",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .wrapContentWidth(Alignment.Start)
                        .widthIn(max = 180.dp)
                        .height(48.dp),
                )
                if (onSettings != null) {
                    // Toolbar icons are tap targets, not keyboard stops (audit S2-08).
                    IconButton(onClick = onSettings, modifier = Modifier.focusProperties { canFocus = false }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                        )
                    }
                }
                ConnectionPill(status)
            }
            if (operatorName != null) {
                // Full card width, not squeezed beside the gear and pill: there "Operator One ·
                // Operator" ellipsised to "Operator One · Opera…" on a 360dp handheld.
                // The logout icon is the affordance: without it this reads as a caption, and it
                // is one of the two routes to switching operator (Settings is the other).
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onLogoutClick)
                        .focusProperties { canFocus = false }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Log out",
                        tint = BrandTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (!operatorRole.isNullOrBlank()) "$operatorName · $operatorRole" else operatorName,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The connection pill shared by the top bar and the Home header card. */
@Composable
fun ConnectionPill(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val (dotColor, statusLabel) = when (status) {
        ConnectionStatus.Connected      -> SuccessGreen to "Connected"
        ConnectionStatus.Reconnecting   -> WarningOrange to "Reconnecting"
        ConnectionStatus.StationOffline -> WarningOrange to "Station 2 offline"
        ConnectionStatus.ClockSkewed    -> WarningOrange to "Clock out of sync"
        ConnectionStatus.Offline        -> DangerRed to "Offline"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(dotColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(color = dotColor)
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
