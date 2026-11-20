package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.SaraShaderStyle
import com.example.viewmodel.SaraMoodState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive 3D-stylized full-body girl character ("Maya / Sara") powered by transparent 3D CGI
 * pose sprites with real-time perspective orbit drag, breathing, dance/wave sway, audio-reactive
 * holographic pedestal + equalizer halo, lip-sync pulse glow, and floating mood particles.
 */
@Composable
fun Sara3DCharacter(
    moodState: SaraMoodState,
    audioAmplitude: Float,
    shaderStyle: SaraShaderStyle,
    modifier: Modifier = Modifier,
    onTapCharacter: () -> Unit = {}
) {
    var targetYawDeg by remember { mutableFloatStateOf(0f) }
    var targetPitchDeg by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val smoothYawDeg by animateFloatAsState(
        targetValue = targetYawDeg,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 210f),
        label = "yawDeg"
    )
    val smoothPitchDeg by animateFloatAsState(
        targetValue = targetPitchDeg,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 210f),
        label = "pitchDeg"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "mayaMotion")
    val gesturePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gesturePhase"
    )
    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathPhase"
    )

    val animatedAmp by animateFloatAsState(
        targetValue = audioAmplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 65),
        label = "amp"
    )

    val primaryGlow = when (moodState) {
        SaraMoodState.LISTENING -> shaderStyle.accentColor
        SaraMoodState.THINKING -> shaderStyle.secondaryColor
        SaraMoodState.SPEAKING -> Color(0xFFF472B6)
        SaraMoodState.ACTION_SUCCESS -> Color(0xFF34D399)
        SaraMoodState.WAVE_HI -> Color(0xFF38BDF8)
        SaraMoodState.ANGRY -> Color(0xFFEF4444)
        SaraMoodState.LOVE -> Color(0xFFF472B6)
        SaraMoodState.IDLE -> shaderStyle.primaryColor
    }
    val secondaryGlow = when (moodState) {
        SaraMoodState.ANGRY -> Color(0xFFF97316)
        SaraMoodState.LOVE -> Color(0xFFFB7185)
        else -> shaderStyle.secondaryColor
    }

    // Dynamic 3D kinetic transform values driven by mood & live audio
    val breathScale = 1f + sin(breathPhase) * 0.014f + when (moodState) {
        SaraMoodState.LOVE -> abs(sin(gesturePhase * 2f)) * 0.025f
        SaraMoodState.SPEAKING -> animatedAmp * 0.028f
        else -> 0f
    }
    val bounceY = when (moodState) {
        SaraMoodState.ACTION_SUCCESS -> -abs(sin(gesturePhase * 3f)) * 18f
        SaraMoodState.WAVE_HI -> -abs(sin(gesturePhase * 2f)) * 9f
        SaraMoodState.LOVE -> sin(gesturePhase * 2f) * 6f
        SaraMoodState.ANGRY -> abs(sin(gesturePhase * 5f)) * 5f
        SaraMoodState.SPEAKING -> -animatedAmp * 7f
        else -> sin(breathPhase) * 4f
    }
    val swayX = when (moodState) {
        SaraMoodState.ACTION_SUCCESS -> sin(gesturePhase * 3f) * 12f
        SaraMoodState.LOVE -> sin(gesturePhase * 1.5f) * 7f
        SaraMoodState.ANGRY -> sin(gesturePhase * 7f) * 4f
        else -> sin(breathPhase * 0.5f) * 3f
    }
    val tiltZ = when (moodState) {
        SaraMoodState.WAVE_HI -> -3.5f + sin(gesturePhase * 3f) * 3.2f
        SaraMoodState.LOVE -> sin(gesturePhase * 2f) * 2.8f
        SaraMoodState.ACTION_SUCCESS -> sin(gesturePhase * 3f) * 4.5f
        SaraMoodState.ANGRY -> sin(gesturePhase * 7f) * 2.2f
        SaraMoodState.THINKING -> 3.5f
        SaraMoodState.SPEAKING -> sin(gesturePhase * 4f) * (1.2f + animatedAmp * 2.5f)
        else -> sin(gesturePhase * 0.5f) * 1.3f
    }
    val autoYawDeg = if (isDragging) 0f else when (moodState) {
        SaraMoodState.WAVE_HI -> -6f + sin(gesturePhase * 2f) * 5f
        SaraMoodState.ACTION_SUCCESS -> sin(gesturePhase * 2f) * 10f
        SaraMoodState.LOVE -> sin(gesturePhase * 1.5f) * 7f
        SaraMoodState.THINKING -> 8f
        else -> sin(gesturePhase * 0.5f) * 4.5f
    }

    val targetDrawableRes = when (moodState) {
        SaraMoodState.WAVE_HI -> R.drawable.img_maya_wave
        SaraMoodState.LOVE -> R.drawable.img_maya_love
        SaraMoodState.ANGRY -> R.drawable.img_maya_angry
        else -> R.drawable.img_maya_idle
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = "Maya interactive 3D full-body girl character" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapCharacter() },
                    onDoubleTap = {
                        targetYawDeg = 0f
                        targetPitchDeg = 0f
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        targetYawDeg = 0f
                        targetPitchDeg = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        targetYawDeg = 0f
                        targetPitchDeg = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    targetYawDeg = (targetYawDeg + dragAmount.x * 0.24f).coerceIn(-32f, 32f)
                    targetPitchDeg = (targetPitchDeg - dragAmount.y * 0.14f).coerceIn(-14f, 14f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Back layer: Studio aura, live voice-reactive equalizer ring, and holographic pedestal
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f + swayX * 0.4f
            val cy = h * 0.46f
            val unit = minOf(w, h) * 0.22f

            // Soft moody studio glow behind Maya
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.34f + animatedAmp * 0.22f),
                        secondaryGlow.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = unit * 2.45f
                ),
                radius = unit * 2.45f,
                center = Offset(cx, cy)
            )

            // Reactive voice equalizer halo around her silhouette
            drawAudioHaloBehindMaya(
                centerX = cx,
                centerY = cy,
                baseRadius = unit * 1.38f,
                amp = animatedAmp,
                phase = gesturePhase,
                moodState = moodState,
                primary = primaryGlow,
                secondary = secondaryGlow
            )

            // Holographic stage pedestal directly under her sneakers
            drawHologramPedestal(
                centerX = cx,
                floorY = h * 0.90f,
                rx = unit * (1.28f + animatedAmp * 0.15f),
                ry = unit * 0.24f,
                primaryGlow = primaryGlow,
                secondaryGlow = secondaryGlow,
                phase = gesturePhase
            )
        }

        // Mid layer: Full-body 3D CGI Maya character with smooth mood crossfade & kinetic 3D transforms
        Crossfade(
            targetState = targetDrawableRes,
            animationSpec = tween(durationMillis = 280),
            label = "mayaPoseCrossfade",
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, bottom = 22.dp)
        ) { resId ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = "Maya 3D Assistant Character",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .graphicsLayer {
                            cameraDistance = 14f * density
                            transformOrigin = TransformOrigin(0.5f, 0.85f)
                            translationX = swayX
                            translationY = bounceY
                            scaleX = breathScale
                            scaleY = breathScale
                            rotationZ = tiltZ
                            rotationY = smoothYawDeg + autoYawDeg
                            rotationX = smoothPitchDeg
                        }
                )
            }
        }

        // Foreground layer: Lip-sync glow ring / Thinking orbs / Love hearts / Anger sparks
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f + swayX
            val unit = minOf(w, h) * 0.22f

            if (moodState == SaraMoodState.SPEAKING && animatedAmp > 0.08f) {
                val speakRingRadius = unit * (0.28f + animatedAmp * 0.22f)
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.28f * animatedAmp),
                    radius = speakRingRadius,
                    center = Offset(cx, h * 0.24f + bounceY),
                    style = Stroke(width = 3f)
                )
            }

            drawMayaMoodParticles(
                centerX = cx,
                headY = h * 0.18f + bounceY,
                unit = unit,
                phase = gesturePhase,
                moodState = moodState,
                primary = primaryGlow,
                secondary = secondaryGlow
            )
        }
    }
}

private fun DrawScope.drawAudioHaloBehindMaya(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    amp: Float,
    phase: Float,
    moodState: SaraMoodState,
    primary: Color,
    secondary: Color
) {
    val barCount = 42
    val boost = when (moodState) {
        SaraMoodState.LISTENING, SaraMoodState.SPEAKING -> 0.18f + amp * 0.45f
        SaraMoodState.LOVE, SaraMoodState.WAVE_HI, SaraMoodState.ACTION_SUCCESS -> 0.16f
        SaraMoodState.ANGRY -> 0.22f
        else -> 0.07f
    }
    for (i in 0 until barCount) {
        val angle = (i.toFloat() / barCount) * (2f * PI.toFloat())
        val wave = sin(angle * 4f + phase * 3f) * 0.5f + 0.5f
        val len = baseRadius * (0.04f + boost * wave)
        val start = Offset(centerX + cos(angle) * baseRadius, centerY + sin(angle) * baseRadius)
        val end = Offset(centerX + cos(angle) * (baseRadius + len), centerY + sin(angle) * (baseRadius + len))
        drawLine(
            color = (if (i % 2 == 0) primary else secondary).copy(alpha = 0.26f + wave * 0.34f),
            start = start,
            end = end,
            strokeWidth = 2.6f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawHologramPedestal(
    centerX: Float,
    floorY: Float,
    rx: Float,
    ry: Float,
    primaryGlow: Color,
    secondaryGlow: Color,
    phase: Float
) {
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                primaryGlow.copy(alpha = 0.44f),
                secondaryGlow.copy(alpha = 0.16f),
                Color.Transparent
            ),
            center = Offset(centerX, floorY),
            radius = rx
        ),
        topLeft = Offset(centerX - rx, floorY - ry),
        size = Size(rx * 2f, ry * 2f)
    )
    drawOval(
        color = primaryGlow.copy(alpha = 0.72f),
        topLeft = Offset(centerX - rx * 0.78f, floorY - ry * 0.72f),
        size = Size(rx * 1.56f, ry * 1.44f),
        style = Stroke(width = 2.4f)
    )
    val pulse = (phase / (2f * PI.toFloat())) % 1f
    drawOval(
        color = secondaryGlow.copy(alpha = (1f - pulse) * 0.52f),
        topLeft = Offset(centerX - rx * (0.5f + pulse * 0.5f), floorY - ry * (0.5f + pulse * 0.5f)),
        size = Size(rx * (1f + pulse), ry * (1f + pulse)),
        style = Stroke(width = 1.8f)
    )
}

private fun DrawScope.drawMayaMoodParticles(
    centerX: Float,
    headY: Float,
    unit: Float,
    phase: Float,
    moodState: SaraMoodState,
    primary: Color,
    secondary: Color
) {
    when (moodState) {
        SaraMoodState.LOVE -> {
            for (i in 0 until 6) {
                val frac = ((phase / (2f * PI.toFloat())) + i / 6f) % 1f
                val hx = centerX + sin(frac * 6.28f + i) * unit * 1.05f
                val hy = headY + unit * 0.85f - frac * unit * 1.75f
                drawMiniHeart(
                    center = Offset(hx, hy),
                    radius = unit * (0.12f + (1f - frac) * 0.08f),
                    color = Color(0xFFF472B6).copy(alpha = (1f - frac) * 0.92f)
                )
            }
        }
        SaraMoodState.ANGRY -> {
            for (i in 0 until 4) {
                val ang = phase * 3f + i * 1.57f
                val sx = centerX + cos(ang) * unit * 0.98f
                val sy = headY + sin(ang * 2f) * unit * 0.34f
                drawAngerMark(
                    center = Offset(sx, sy),
                    sizePx = unit * 0.15f,
                    color = Color(0xFFEF4444).copy(alpha = 0.88f)
                )
            }
        }
        SaraMoodState.WAVE_HI, SaraMoodState.ACTION_SUCCESS -> {
            for (i in 0 until 6) {
                val ang = phase * 2f + i * (PI.toFloat() / 3f)
                val px = centerX + cos(ang) * unit * 1.15f
                val py = headY + unit * 0.45f + sin(ang * 1.5f) * unit * 0.65f
                drawCircle(
                    color = (if (i % 2 == 0) primary else secondary).copy(alpha = 0.72f),
                    radius = 4.5f,
                    center = Offset(px, py)
                )
            }
        }
        SaraMoodState.THINKING -> {
            for (i in 0 until 3) {
                val ang = phase * 2.5f + i * 2.1f
                drawCircle(
                    color = secondary.copy(alpha = 0.8f),
                    radius = 6f + i * 1.5f,
                    center = Offset(
                        centerX + cos(ang) * unit * 0.85f,
                        headY + sin(ang) * unit * 0.25f
                    )
                )
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawMiniHeart(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.75f)
        cubicTo(
            center.x - radius * 1.25f, center.y + radius * 0.05f,
            center.x - radius * 0.95f, center.y - radius * 0.95f,
            center.x, center.y - radius * 0.25f
        )
        cubicTo(
            center.x + radius * 0.95f, center.y - radius * 0.95f,
            center.x + radius * 1.25f, center.y + radius * 0.05f,
            center.x, center.y + radius * 0.75f
        )
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawAngerMark(center: Offset, sizePx: Float, color: Color) {
    val r = sizePx * 0.6f
    drawLine(color, Offset(center.x - r, center.y - r * 0.3f), Offset(center.x - r * 0.25f, center.y - r * 0.3f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x - r * 0.25f, center.y - r * 0.3f), Offset(center.x - r * 0.25f, center.y - r), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x + r * 0.25f, center.y - r), Offset(center.x + r * 0.25f, center.y - r * 0.3f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x + r * 0.25f, center.y - r * 0.3f), Offset(center.x + r, center.y - r * 0.3f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x - r, center.y + r * 0.3f), Offset(center.x - r * 0.25f, center.y + r * 0.3f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x - r * 0.25f, center.y + r * 0.3f), Offset(center.x - r * 0.25f, center.y + r), strokeWidth = 3f, cap = StrokeCap.Round)
}
