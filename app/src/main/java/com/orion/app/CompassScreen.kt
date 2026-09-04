// ============================================================
// app/CompassScreen.kt
// Consumes CompassUiState only — no RFID/localization/vendor logic here
// (CLAUDE.md §12), mirroring EnrollmentScreen.kt's convention of a pure
// function of UI state plus callbacks. Portrait, high-contrast, minimal
// chrome — designed for an enterprise PDT rather than a phone.
//
// This is a proximity/trend ("hotter/colder") gauge, NOT a literal compass
// bearing arrow — NavigationState has no bearing field by design, per
// CLAUDE.md §10's single-antenna RSSI capability caveat. `proximity` is a
// unitless 0..1 relative value; it is never rendered as a fabricated
// distance (e.g. meters).
// ============================================================
package com.orion.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orion.core.navigation.Trend
import kotlin.math.cos
import kotlin.math.sin

/** Gauge sweeps 270° starting at "7 o'clock" (135°), leaving a gap at the bottom. */
private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_RANGE = 270f

@Composable
fun CompassScreen(state: CompassUiState, targetName: String, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = targetName,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                )

                when (state) {
                    is CompassUiState.Searching -> SearchingContent()
                    is CompassUiState.NoSignal -> NoSignalContent()
                    is CompassUiState.Guiding -> GuidingContent(state)
                    is CompassUiState.TargetAcquired -> TargetAcquiredContent(targetName)
                    is CompassUiState.Error -> ErrorContent(state.message)
                }
            }
        }
    }
}

@Composable
private fun SearchingContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "searching-sweep")
    val rawAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1400, easing = LinearEasing)),
        label = "sweep-angle"
    )
    // Continuous rotation wrapped into [0, 360) with the shared angle helper —
    // generic animation math, not a direction/bearing estimate.
    val sweepStart = normalizeDegrees(rawAngle.toDouble()).toFloat()

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val sweepColor = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            drawCircle(
                color = trackColor,
                radius = size.minDimension / 2 - strokeWidth / 2,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = sweepColor,
                startAngle = sweepStart,
                sweepAngle = 60f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }

    Text(
        text = "Searching for signal…",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp)
    )
}

@Composable
private fun NoSignalContent() {
    val mutedColor = MaterialTheme.colorScheme.outline

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            drawCircle(
                color = mutedColor,
                radius = size.minDimension / 2 - strokeWidth / 2,
                style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 18f)))
            )
        }
        Text(text = "!", style = MaterialTheme.typography.displayMedium, color = mutedColor)
    }

    Text(
        text = "Signal unavailable",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp)
    )
    Text(
        text = "Move around to reacquire the target's signal.",
        style = MaterialTheme.typography.bodyMedium,
        color = mutedColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun GuidingContent(state: CompassUiState.Guiding) {
    val proximity = state.proximity.coerceIn(0.0, 1.0)
    val animatedProximity by animateFloatAsState(
        targetValue = proximity.toFloat(),
        animationSpec = tween(durationMillis = 400),
        label = "proximity-fill"
    )
    val needleAngle = rememberAnimatedAngle(GAUGE_START_ANGLE + GAUGE_SWEEP_RANGE * animatedProximity)

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val needleColor = MaterialTheme.colorScheme.onSurface

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val arcTopLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP_RANGE,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = arcTopLeft,
                size = arcSize
            )
            drawArc(
                color = fillColor,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP_RANGE * animatedProximity,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = arcTopLeft,
                size = arcSize
            )

            val radius = (size.minDimension - strokeWidth) / 2
            val angleRad = Math.toRadians(needleAngle.toDouble())
            val center = Offset(size.width / 2, size.height / 2)
            val tip = Offset(
                x = center.x + radius * cos(angleRad).toFloat(),
                y = center.y + radius * sin(angleRad).toFloat()
            )
            drawCircle(color = needleColor, radius = strokeWidth / 2.2f, center = tip)
        }
    }

    Text(
        text = proximityLabel(proximity),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 20.dp)
    )
    TrendIndicator(state.trend)
    Text(
        text = confidenceLabel(state.confidence),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * Animates towards [targetAngleDegrees] taking the shortest rotation each time
 * the target changes (via [shortestAngleDelta]), then returns the displayed
 * angle normalized into `[0, 360)` for drawing. Generic rotation animation
 * helper — not a bearing/direction estimate.
 */
@Composable
private fun rememberAnimatedAngle(targetAngleDegrees: Float): Float {
    val animatable = remember { Animatable(targetAngleDegrees) }
    LaunchedEffect(targetAngleDegrees) {
        val current = animatable.value.toDouble()
        val delta = shortestAngleDelta(current, targetAngleDegrees.toDouble())
        animatable.animateTo(
            targetValue = (current + delta).toFloat(),
            animationSpec = tween(durationMillis = 450)
        )
    }
    return normalizeDegrees(animatable.value.toDouble()).toFloat()
}

@Composable
private fun TrendIndicator(trend: Trend) {
    val label = when (trend) {
        Trend.HOTTER -> "▲ Getting warmer"
        Trend.COLDER -> "▼ Getting colder"
        Trend.STEADY -> "● Hold steady"
        Trend.UNKNOWN -> null // neutral — nothing to report yet
    }
    Text(
        text = label ?: " ",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp)
    )
}

private fun proximityLabel(proximity: Double): String = when {
    proximity >= 0.75 -> "Very close"
    proximity >= 0.35 -> "Getting close"
    else -> "Far"
}

private fun confidenceLabel(confidence: Double): String {
    val qualitative = when {
        confidence >= 0.7 -> "High confidence"
        confidence >= 0.4 -> "Medium confidence"
        else -> "Low confidence"
    }
    val percent = (confidence.coerceIn(0.0, 1.0) * 100).toInt()
    return "$qualitative ($percent%)"
}

@Composable
private fun TargetAcquiredContent(targetName: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Text(
        text = "Target Found",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 24.dp)
    )
    Text(
        text = targetName,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ErrorContent(message: String) {
    Text(
        text = "Something went wrong",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp)
    )
}
