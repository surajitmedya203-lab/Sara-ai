package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ObsidianNight
import com.example.ui.theme.SaraShaderStyle
import com.example.ui.theme.WarmCoralRose
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Symmetrical live audio waveform visualizer that reacts to microphone input volume (or TTS output)
 * using a rolling amplitude history buffer and smooth spring/tween interpolation.
 */
@Composable
fun VoiceWaveformStrip(
    audioLevel: Float,
    isActive: Boolean,
    shaderStyle: SaraShaderStyle,
    modifier: Modifier = Modifier
) {
    val barCount = 29
    val samples = remember { FloatArray(barCount) { 0.08f } }
    var tick by remember { mutableIntStateOf(0) }

    val smoothedLevel by animateFloatAsState(
        targetValue = if (isActive) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 480f),
        label = "waveformLevel"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "waveIdle")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    LaunchedEffect(isActive) {
        if (!isActive) {
            for (i in samples.indices) samples[i] = 0.06f
            tick++
            return@LaunchedEffect
        }
        while (true) {
            // Shift outward from center for a crisp symmetrical voice ripple
            val mid = barCount / 2
            for (i in 0 until mid) {
                samples[i] = samples[i + 1]
                samples[barCount - 1 - i] = samples[barCount - 2 - i]
            }
            val reactiveFloor = 0.14f + 0.06f * sin(wavePhase * 2f)
            samples[mid] = maxOf(reactiveFloor, smoothedLevel)
            tick++
            delay(45L)
        }
    }

    val desc = if (isActive) "Active microphone volume waveform" else "Idle waveform"
    val primaryColor = shaderStyle.primaryColor
    val accentColor = shaderStyle.accentColor
    val secondaryColor = shaderStyle.secondaryColor

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .semantics { contentDescription = desc }
            .testTag("listening_waveform_strip")
    ) {
        // Read tick so Canvas redraws smoothly on each sample step
        @Suppress("UNUSED_VARIABLE")
        val frame = tick

        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val slotWidth = w / barCount
        val barWidth = (slotWidth * 0.56f).coerceIn(3.dp.toPx(), 7.dp.toPx())
        val midIndex = (barCount - 1) / 2f

        // Subtle center baseline
        drawLine(
            color = primaryColor.copy(alpha = 0.22f),
            start = Offset(0f, centerY),
            end = Offset(w, centerY),
            strokeWidth = 1.2.dp.toPx()
        )

        val gradientBrush = Brush.horizontalGradient(
            colors = listOf(secondaryColor, accentColor, primaryColor, accentColor, secondaryColor)
        )

        for (i in 0 until barCount) {
            val distanceNorm = abs(i - midIndex) / midIndex
            val envelope = 1f - (distanceNorm * 0.48f)
            val ripple = 0.08f * sin(wavePhase * 2f + i * 0.55f)
            val rawSample = (samples[i] + if (isActive) ripple else 0f).coerceIn(0.08f, 1f)
            val barHeight = (h * 0.88f * rawSample * envelope).coerceAtLeast(4.dp.toPx())
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val y = centerY - barHeight / 2f

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = (0.45f + rawSample * 0.55f).coerceIn(0.35f, 1f)
            )
        }
    }
}

/**
 * Tactile microphone orb that emits multi-ring concentric pulses scaled dynamically
 * by live microphone input volume while actively listening.
 */
@Composable
fun ReactiveMicOrbButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    shaderStyle: SaraShaderStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reactiveLevel by animateFloatAsState(
        targetValue = when {
            isListening -> audioLevel.coerceIn(0.12f, 1f)
            isSpeaking -> audioLevel.coerceIn(0.1f, 0.85f)
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "micReactiveLevel"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "micOrbPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    val orbScale = when {
        isListening -> 1f + reactiveLevel * 0.14f
        isSpeaking -> 1f + reactiveLevel * 0.08f
        else -> 1f
    }

    val orbBrush = when {
        isListening -> Brush.linearGradient(listOf(shaderStyle.accentColor, shaderStyle.primaryColor))
        isSpeaking -> Brush.linearGradient(listOf(WarmCoralRose, shaderStyle.secondaryColor))
        else -> Brush.linearGradient(listOf(shaderStyle.primaryColor, shaderStyle.secondaryColor))
    }

    val ringBaseColor = when {
        isListening -> shaderStyle.accentColor
        isSpeaking -> WarmCoralRose
        else -> shaderStyle.primaryColor
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(76.dp)
    ) {
        if (isListening || isSpeaking) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = 27.dp.toPx()
                val maxExpand = (size.minDimension / 2f) - baseRadius

                // Volume-reactive outer halo
                drawCircle(
                    color = ringBaseColor.copy(alpha = 0.14f + reactiveLevel * 0.22f),
                    radius = baseRadius + maxExpand * (0.25f + reactiveLevel * 0.75f),
                    center = center
                )

                // Two staggered expanding concentric ripple rings driven by mic volume
                for (ringIndex in 0..1) {
                    val phase = (pulseProgress + ringIndex * 0.5f) % 1f
                    val ringRadius = baseRadius + maxExpand * phase * (0.55f + reactiveLevel * 0.45f)
                    val ringAlpha = ((1f - phase) * (0.35f + reactiveLevel * 0.55f)).coerceIn(0f, 0.9f)
                    drawCircle(
                        color = ringBaseColor.copy(alpha = ringAlpha),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = (1.6f + reactiveLevel * 2.2f).dp.toPx())
                    )
                }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(58.dp)
                .scale(orbScale)
                .clip(CircleShape)
                .background(orbBrush)
                .clickable(onClick = onClick)
                .testTag("mic_button")
        ) {
            Icon(
                imageVector = when {
                    isListening -> Icons.Default.MicOff
                    isSpeaking -> Icons.Default.Stop
                    else -> Icons.Default.Mic
                },
                contentDescription = stringResource(id = R.string.mic_content_desc),
                tint = if (MaterialTheme.colorScheme.background == ObsidianNight) {
                    ObsidianNight
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier.size(27.dp)
            )
        }
    }
}
