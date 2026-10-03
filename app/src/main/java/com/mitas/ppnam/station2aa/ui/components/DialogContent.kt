package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The body of any dialog that contains a text field.
 *
 * Material 3's `AlertDialogContent` lays its slots out as title / text / buttons in a Column and
 * gives the text slot `weight(1f, fill = false)`. That weight only does anything if the slot's own
 * content can shrink — an ordinary Column reports its full intrinsic height and the dialog grows
 * past the visible area, pushing the confirm/dismiss row underneath the keyboard. On the C72 the
 * force-close dialog's buttons sat at y=1167 with the IME open, so taps aimed at "Force close"
 * landed on keys instead and typed stray characters into the audit-reason field.
 *
 * Making the body scrollable lets that weight bite: the body shrinks to whatever height is left
 * once the IME has claimed its space, and the action buttons stay on screen and tappable.
 *
 * Use this for every credential/audit dialog. A dialog with no text input does not need it.
 */
@Composable
fun DialogFormColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
