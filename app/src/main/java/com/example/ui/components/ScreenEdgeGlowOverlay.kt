package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SaraShaderStyle
import com.example.ui.theme.WarmCoralRose
import com.example.viewmodel.SaraMoodState

/**
 * In-app AI Edge Glow rendered cleanly above the app scaffold without intercepting touches.
 * Reacts to Maya's mood, microphone/TTS amplitude, and live Screen Share status.
 */
@Composable
fun ScreenEdgeGlowOverlay(
    enabled: Boolean,
    moodState: SaraMoodState,
    audioAmplitude: Float,
    isScreenSharing: Boolean,
    shaderStyle: SaraShaderStyle,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    val transition = rememberInfiniteTransition(label = "edgeGlowTransition")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    moodState == SaraMoodState.LISTENING -> 2100
                    moodState == SaraMoodState.THINKING -> 1700
                    isScreenSharing -> 2600
                    else -> 4200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "edgeGlowAngle"
    )

    val breathe by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgeGlowBreathe"
    )

    val baseIntensity = when {
        moodState == SaraMoodState.LISTENING -> 0.82f + audioAmplitude * 0.18f
        moodState == SaraMoodState.SPEAKING -> 0.78f + audioAmplitude * 0.22f
        moodState == SaraMoodState.THINKING -> 0.85f
        moodState == SaraMoodState.LOVE -> 0.80f
        moodState == SaraMoodState.ANGRY -> 0.85f
        isScreenSharing -> 0.75f
        else -> 0.42f
    } * breathe

    val edgeColors = when (moodState) {
        SaraMoodState.LOVE -> listOf(
            WarmCoralRose,
            Color(0xFFF472B6),
            LuminousViolet,
            WarmCoralRose
        )
        SaraMoodState.ANGRY -> listOf(
            Color(0xFFEF4444),
            Color(0xFFF97316),
            WarmCoralRose,
            Color(0xFFEF4444)
        )
        else -> listOf(
            shaderStyle.primaryColor,
            shaderStyle.accentColor,
            LuminousViolet,
            WarmCoralRose,
            EmeraldPulse,
            shaderStyle.primaryColor
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val corner = CornerRadius(28.dp.toPx(), 28.dp.toPx())
        val outerStroke = (14.dp + (audioAmplitude * 8f).dp).toPx()
        val midStroke = (6.dp + (audioAmplitude * 3f).dp).toPx()
        val coreStroke = 2.dp.toPx()

        val sweepBrush = Brush.sweepGradient(
            colors = edgeColors,
            center = Offset(w / 2f, h / 2f)
        )

        rotate(degrees = angle, pivot = Offset(w / 2f, h / 2f)) {
            drawRoundRect(
                brush = sweepBrush,
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
                cornerRadius = corner,
                alpha = (0.24f * baseIntensity).coerceIn(0.06f, 0.45f),
                style = Stroke(width = outerStroke)
            )
            drawRoundRect(
                brush = sweepBrush,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
                cornerRadius = corner,
                alpha = (0.48f * baseIntensity).coerceIn(0.14f, 0.72f),
                style = Stroke(width = midStroke)
            )
            drawRoundRect(
                brush = sweepBrush,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
                cornerRadius = corner,
                alpha = (0.88f * baseIntensity).coerceIn(0.28f, 0.96f),
                style = Stroke(width = coreStroke)
            )
        }
    }
}
