package com.example.paperclipper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.example.paperclipper.R

/**
 * "Editorial Newsprint" design language for Paper Clipper — warm newsprint paper, ink-black type,
 * hairline rules and a single oxidized red, set in Newsreader (serif) + Spline Sans Mono.
 *
 * Colours and text styles are exposed as plain top-level values (not behind a CompositionLocal) so
 * the @VisibleForTesting screen composables render identically whether they're hosted by
 * [NewsprintTheme] (production) or a bare MaterialTheme (Compose UI tests).
 */
object NP {
    val Board = Color(0xFFE7DDCA)     // warm board backing (behind the "paper")
    val Paper = Color(0xFFF3ECDF)     // primary app surface — the newsprint sheet
    val Card = Color(0xFFFAF6EC)      // raised card / input fields
    val PaperAlt = Color(0xFFEFE7D6)  // image wells, thumbnails
    val Ink = Color(0xFF1C1A15)       // primary type
    val InkSoft = Color(0xFF34302A)   // long-form body type
    val Muted = Color(0xFF6F6655)     // secondary type, section labels
    val Faint = Color(0xFF9C9483)     // placeholders, timestamps
    val Red = Color(0xFFB23A2E)       // the single accent — oxidized newsprint red
    val OnRed = Color(0xFFF7EFE0)     // type on red
    val Hair = Color(0xFFCABFA8)      // hairline borders (cards, inputs)
    val HairSoft = Color(0xFFDDD3C0)  // softer separators (list rules)
    val Frame = Color(0xFF15130F)     // dark device frame / over-image chrome
    val Scrim = Color(0xFF2A2620)     // dark capture backdrop base

    /** Radial "studio table" backdrop used behind the camera capture / crop / lasso screens. */
    val captureBackdrop: Brush
        get() = Brush.radialGradient(
            colors = listOf(Color(0xFF2C271D), Color(0xFF14110C)),
            radius = 1400f,
        )
}

// --- Fonts -------------------------------------------------------------------------------------
// Both families ship as variable TTFs (res/font); each weight is a named instance pinned via
// FontVariation on the 'wght' axis (supported from API 26, our minSdk).

private fun newsreader(weight: Int, italic: Boolean = false) = Font(
    resId = if (italic) R.font.newsreader_italic else R.font.newsreader,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun splineMono(weight: Int) = Font(
    resId = R.font.spline_sans_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Newsreader — the serif voice: mastheads, headlines, summaries, italic captions. */
val Newsreader = FontFamily(
    newsreader(300),
    newsreader(400),
    newsreader(500),
    newsreader(600),
    newsreader(400, italic = true),
    newsreader(500, italic = true),
)

/** Spline Sans Mono — the typewriter voice: labels, metadata, kicker, buttons. */
val SplineMono = FontFamily(
    splineMono(400),
    splineMono(500),
    splineMono(600),
)

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * The newsprint type ramp. Sizes mirror the design's phone mock-ups. Decorative labels are passed
 * already-uppercased by callers (Compose has no CSS text-transform); these styles only carry the
 * family / tracking / colour.
 */
object NewsType {
    // Serif — editorial
    val masthead = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 27.sp, color = NP.Ink)
    val title = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 27.sp, color = NP.Ink, lineHeightStyle = tightLineHeight)
    val headline = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 19.sp, color = NP.Ink, lineHeightStyle = tightLineHeight)
    val body = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, color = NP.InkSoft)
    val bodySmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp, color = NP.Muted)
    val serifItalic = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic, fontSize = 14.5.sp, lineHeight = 21.sp, color = NP.Muted)

    // Mono — labels / chrome
    val kicker = TextStyle(fontFamily = SplineMono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.6.sp, color = NP.Red)
    val label = TextStyle(fontFamily = SplineMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 1.6.sp, color = NP.Muted)
    val meta = TextStyle(fontFamily = SplineMono, fontWeight = FontWeight.Normal, fontSize = 9.5.sp, letterSpacing = 0.6.sp, color = NP.Faint)
    val button = TextStyle(fontFamily = SplineMono, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.9.sp)
}

private fun serif(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Newsreader, fontWeight = weight, fontSize = size.sp)

private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = SplineMono, fontWeight = weight, fontSize = size.sp, letterSpacing = 0.6.sp)

/** Material typography mapped onto the newsprint voices, so stock components stay on-brand. */
private val NewsprintTypography = Typography(
    displayLarge = serif(40, FontWeight.Medium),
    displayMedium = serif(34, FontWeight.Medium),
    displaySmall = serif(28, FontWeight.Medium),
    headlineLarge = serif(28, FontWeight.Medium),
    headlineMedium = serif(24, FontWeight.Medium),
    headlineSmall = serif(20, FontWeight.Medium),
    titleLarge = serif(22, FontWeight.Medium),
    titleMedium = serif(17, FontWeight.Medium),
    titleSmall = mono(13, FontWeight.Medium),
    bodyLarge = serif(16),
    bodyMedium = serif(14),
    bodySmall = serif(13),
    labelLarge = mono(12, FontWeight.Medium),
    labelMedium = mono(11),
    labelSmall = mono(10),
)

private val NewsprintColors = lightColorScheme(
    primary = NP.Red,
    onPrimary = NP.OnRed,
    secondary = NP.Ink,
    onSecondary = NP.Paper,
    tertiary = NP.Muted,
    onTertiary = NP.Paper,
    background = NP.Paper,
    onBackground = NP.Ink,
    surface = NP.Paper,
    onSurface = NP.Ink,
    surfaceVariant = NP.Card,
    onSurfaceVariant = NP.Muted,
    surfaceContainer = NP.Card,
    surfaceContainerHigh = NP.Card,
    surfaceContainerLow = NP.Paper,
    error = NP.Red,
    onError = NP.OnRed,
    outline = NP.Hair,
    outlineVariant = NP.HairSoft,
    scrim = NP.Frame,
)

/** Wraps content in the newsprint colour scheme + typography. Used by MainActivity. */
@Composable
fun NewsprintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NewsprintColors,
        typography = NewsprintTypography,
        content = content,
    )
}
