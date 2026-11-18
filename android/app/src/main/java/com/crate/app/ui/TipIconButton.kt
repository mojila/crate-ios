package com.crate.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Space-saving replacement for text Buttons: a Material icon with a
 * long-press tooltip and a TalkBack description. `filled` marks the
 * currently-active tab / primary row action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipIconButton(
    icon: ImageVector,
    tip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tip) } },
        state = rememberTooltipState(),
    ) {
        if (filled) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                Icon(icon, contentDescription = tip)
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                Icon(icon, contentDescription = tip)
            }
        }
    }
}
