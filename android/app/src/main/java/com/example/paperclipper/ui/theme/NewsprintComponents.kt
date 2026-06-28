package com.example.paperclipper.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/** The signature triple rule: a 2dp ink bar over a hairline, framing mastheads and section heads. */
@Composable
fun MastheadRule(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(NP.Ink))
        Spacer(Modifier.height(1.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(NP.Hair))
    }
}

/** A single hairline separator, as between list rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = NP.HairSoft) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A sharp-cornered editorial button. [filled] = oxidized red with cream type; otherwise an
 * ink-outlined ghost. [upper] uppercases the label (Compose has no CSS text-transform) — leave it
 * off for labels that UI tests match verbatim.
 */
@Composable
fun NewsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    ink: Boolean = false,
    enabled: Boolean = true,
    upper: Boolean = true,
) {
    val shape = RoundedCornerShape(2.dp)
    val solid = filled || ink
    val container = if (ink) NP.Ink else NP.Red
    val fg = when {
        ink -> NP.Paper
        filled -> NP.OnRed
        else -> NP.Ink
    }
    Box(
        modifier
            .clip(shape)
            .then(if (solid) Modifier.background(container) else Modifier.border(BorderStroke(1.dp, NP.Ink), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 13.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (upper) text.uppercase() else text,
            style = NewsType.button.copy(color = fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A compact mono uppercase pill for the lighter chrome buttons over images (Crop / Rotate). */
@Composable
fun ChromeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (accent) NP.Red else NP.Paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = NewsType.button.copy(color = if (accent) NP.OnRed else NP.Ink), maxLines = 1)
    }
}

/** The pulsing red marker shown beside a clipping that's still being read by the model. */
@Composable
fun AnalyzingDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 6.dp) {
    val transition = rememberInfiniteTransition(label = "analyzing")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "analyzing-alpha",
    )
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(NP.Red),
    )
}

/** Outlined-field colours tuned to the newsprint palette (cream fill, ink focus, red cursor). */
@Composable
fun newsTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = NP.Card,
    unfocusedContainerColor = NP.Card,
    disabledContainerColor = NP.Card,
    focusedBorderColor = NP.Ink,
    unfocusedBorderColor = NP.Hair,
    cursorColor = NP.Red,
    focusedTextColor = NP.Ink,
    unfocusedTextColor = NP.Ink,
    focusedPlaceholderColor = NP.Faint,
    unfocusedPlaceholderColor = NP.Faint,
    focusedLeadingIconColor = NP.Faint,
    unfocusedLeadingIconColor = NP.Faint,
    focusedTrailingIconColor = NP.Muted,
    unfocusedTrailingIconColor = NP.Muted,
)
