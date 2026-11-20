package com.example.ui.components

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.ui.theme.SaraShaderStyle
import com.example.viewmodel.SaraMoodState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class Vec3(val x: Float, val y: Float, val z: Float)

private data class ProjectedPoint(val x: Float, val y: Float, val z: Float, val scale: Float)

private fun rotateVec3(v: Vec3, yawRad: Float, pitchRad: Float, rollRad: Float = 0f): Vec3 {
    // Yaw around Y
    val cosY = cos(yawRad)
    val sinY = sin(yawRad)
    val x1 = v.x * cosY + v.z * sinY
    val z1 = -v.x * sinY + v.z * cosY

    // Pitch around X
    val cosP = cos(pitchRad)
    val sinP = sin(pitchRad)
    val y2 = v.y * cosP - z1 * sinP
    val z2 = v.y * sinP + z1 * cosP

    // Roll around Z
    val cosR = cos(rollRad)
    val sinR = sin(rollRad)
    val x3 = x1 * cosR - y2 * sinR
    val y3 = x1 * sinR + y2 * cosR
    return Vec3(x3, y3, z2)
}

private fun project3D(
    v: Vec3,
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    focalLength: Float = 4.2f
): ProjectedPoint {
    val denom = (focalLength - v.z).coerceAtLeast(0.8f)
    val scale = focalLength / denom
    return ProjectedPoint(
        x = centerX + v.x * unitPx * scale,
        y = centerY + v.y * unitPx * scale,
        z = v.z,
        scale = scale
    )
}

/**
 * Interactive real-time 3D-projected holographic character for Sara.
 * Supports touch-to-orbit 3D rotation, autonomous breathing, blinking,
 * holographic orbital gyroscopes, and live lip-sync + audio-reactive halo.
 */
@Composable
fun Sara3DCharacter(
    moodState: SaraMoodState,
    audioAmplitude: Float,
    shaderStyle: SaraShaderStyle,
    modifier: Modifier = Modifier,
    onTapCharacter: () -> Unit = {}
) {
    var targetYaw by remember { mutableFloatStateOf(0f) }
    var targetPitch by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val smoothYaw by animateFloatAsState(
        targetValue = targetYaw,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 220f),
        label = "yaw"
    )
    val smoothPitch by animateFloatAsState(
        targetValue = targetPitch,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 220f),
        label = "pitch"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "sara3d")
    val orbitPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitPhase"
    )
    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathPhase"
    )

    var blinkFactor by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3400L)
            blinkFactor = 1f
            delay(110L)
            blinkFactor = 0f
            delay(180L)
            if (moodState == SaraMoodState.LISTENING) {
                delay(1200L)
            }
        }
    }

    val animatedBlink by animateFloatAsState(
        targetValue = blinkFactor,
        animationSpec = tween(durationMillis = 90),
        label = "blink"
    )

    val animatedAmp by animateFloatAsState(
        targetValue = audioAmplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 70),
        label = "amp"
    )

    val primaryGlow = when (moodState) {
        SaraMoodState.LISTENING -> shaderStyle.accentColor
        SaraMoodState.THINKING -> shaderStyle.secondaryColor
        SaraMoodState.SPEAKING -> shaderStyle.primaryColor
        SaraMoodState.ACTION_SUCCESS -> Color(0xFF34D399)
        SaraMoodState.IDLE -> shaderStyle.primaryColor
    }
    val secondaryGlow = shaderStyle.secondaryColor

    Box(
        modifier = modifier
            .semantics { contentDescription = "Sara interactive 3D hologram character" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapCharacter() },
                    onDoubleTap = {
                        targetYaw = 0f
                        targetPitch = 0f
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        targetYaw = 0f
                        targetPitch = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        targetYaw = 0f
                        targetPitch = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    targetYaw = (targetYaw + dragAmount.x * 0.008f).coerceIn(-0.85f, 0.85f)
                    targetPitch = (targetPitch - dragAmount.y * 0.006f).coerceIn(-0.45f, 0.45f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = minOf(w, h)
            val unitPx = minDim * 0.34f
            val bobY = sin(breathPhase) * 0.032f
            val centerX = w / 2f
            val centerY = h * 0.48f + bobY * unitPx

            // Autonomous subtle head motion layered with user 3D drag
            val autoYaw = if (isDragging) 0f else when (moodState) {
                SaraMoodState.THINKING -> sin(orbitPhase * 2f) * 0.14f
                SaraMoodState.LISTENING -> sin(orbitPhase * 1.5f) * 0.07f
                else -> sin(orbitPhase) * 0.08f
            }
            val autoPitch = if (isDragging) 0f else when (moodState) {
                SaraMoodState.THINKING -> 0.08f
                SaraMoodState.LISTENING -> -0.05f
                else -> cos(breathPhase) * 0.03f
            }

            val yaw = smoothYaw + autoYaw
            val pitch = smoothPitch + autoPitch
            val roll = if (moodState == SaraMoodState.LISTENING) sin(orbitPhase * 2f) * 0.04f else 0f

            // 1. Deep volumetric aura glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.28f + animatedAmp * 0.22f),
                        secondaryGlow.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset(centerX, centerY),
                    radius = unitPx * (1.75f + animatedAmp * 0.35f)
                ),
                radius = unitPx * (1.75f + animatedAmp * 0.35f),
                center = Offset(centerX, centerY)
            )

            // 2. Holographic base projection cone & floor ring
            drawHologramPedestal(
                centerX = centerX,
                centerY = centerY + unitPx * 1.15f,
                unitPx = unitPx,
                primaryGlow = primaryGlow,
                secondaryGlow = secondaryGlow,
                phase = orbitPhase,
                amp = animatedAmp
            )

            // 3. Back half of 3D orbital gyroscope rings (z < 0)
            drawOrbitalRing3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                radius = 1.28f + animatedAmp * 0.14f,
                tiltX = 1.15f,
                tiltZ = orbitPhase * 0.5f,
                yaw = yaw,
                pitch = pitch,
                drawBackHalf = true,
                color = secondaryGlow.copy(alpha = 0.45f),
                nodeColor = primaryGlow
            )
            drawOrbitalRing3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                radius = 1.14f + animatedAmp * 0.10f,
                tiltX = -0.95f,
                tiltZ = -orbitPhase * 0.8f,
                yaw = yaw,
                pitch = pitch,
                drawBackHalf = true,
                color = primaryGlow.copy(alpha = 0.45f),
                nodeColor = shaderStyle.accentColor
            )

            // 4. Audio-reactive radial 3D waveform halo
            drawAudioWaveHalo(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                amp = animatedAmp,
                phase = orbitPhase,
                moodState = moodState,
                primary = primaryGlow,
                secondary = secondaryGlow
            )

            // 5. Back hair crown & flowing cyber strands (behind head in 3D)
            drawBackHairVolume3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                primary = primaryGlow,
                secondary = secondaryGlow
            )

            // 6. 3D Sculpted Shoulders & Holographic Collar
            drawTorsoAndNeck3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                primary = primaryGlow,
                secondary = secondaryGlow
            )

            // 7. 3D Sculpted Head Sphere / Cranium with Phong Specular & Rim Lighting
            drawHeadVolume3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                primary = primaryGlow,
                secondary = secondaryGlow,
                amp = animatedAmp
            )

            // 8. 3D Cyber Earpiece Pods (sorted by z)
            drawEarpiecePods3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                primary = primaryGlow,
                accent = shaderStyle.accentColor,
                amp = animatedAmp
            )

            // 9. Facial Features in 3D (Eyes, Irises, Brows, Nose Bridge, Animated Mouth)
            drawFacialFeatures3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                blink = animatedBlink,
                mouthOpen = animatedAmp,
                moodState = moodState,
                primary = primaryGlow,
                accent = shaderStyle.accentColor,
                orbitPhase = orbitPhase
            )

            // 10. Front swept hair strands & holographic tiara arc
            drawFrontHairAndTiara3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                primary = primaryGlow,
                secondary = secondaryGlow
            )

            // 11. Front half of 3D orbital gyroscope rings (z >= 0)
            drawOrbitalRing3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                radius = 1.28f + animatedAmp * 0.14f,
                tiltX = 1.15f,
                tiltZ = orbitPhase * 0.5f,
                yaw = yaw,
                pitch = pitch,
                drawBackHalf = false,
                color = secondaryGlow.copy(alpha = 0.85f),
                nodeColor = primaryGlow
            )
            drawOrbitalRing3D(
                centerX = centerX,
                centerY = centerY,
                unitPx = unitPx,
                radius = 1.14f + animatedAmp * 0.10f,
                tiltX = -0.95f,
                tiltZ = -orbitPhase * 0.8f,
                yaw = yaw,
                pitch = pitch,
                drawBackHalf = false,
                color = primaryGlow.copy(alpha = 0.9f),
                nodeColor = shaderStyle.accentColor
            )
        }
    }
}

private fun DrawScope.drawHologramPedestal(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    primaryGlow: Color,
    secondaryGlow: Color,
    phase: Float,
    amp: Float
) {
    val rx = unitPx * (0.95f + amp * 0.12f)
    val ry = unitPx * 0.22f
    // Beam cone from emitter
    val conePath = Path().apply {
        moveTo(centerX - rx * 0.72f, centerY)
        lineTo(centerX + rx * 0.72f, centerY)
        lineTo(centerX + rx * 1.15f, centerY - unitPx * 1.25f)
        lineTo(centerX - rx * 1.15f, centerY - unitPx * 1.25f)
        close()
    }
    drawPath(
        path = conePath,
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, primaryGlow.copy(alpha = 0.12f + amp * 0.08f)),
            startY = centerY - unitPx * 1.25f,
            endY = centerY
        )
    )

    // Outer glowing ellipse
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                primaryGlow.copy(alpha = 0.45f),
                secondaryGlow.copy(alpha = 0.18f),
                Color.Transparent
            ),
            center = Offset(centerX, centerY),
            radius = rx
        ),
        topLeft = Offset(centerX - rx, centerY - ry),
        size = Size(rx * 2f, ry * 2f)
    )
    drawOval(
        color = primaryGlow.copy(alpha = 0.75f),
        topLeft = Offset(centerX - rx * 0.78f, centerY - ry * 0.72f),
        size = Size(rx * 1.56f, ry * 1.44f),
        style = Stroke(width = 2.5f)
    )
    val pulseRadius = ((phase / (2f * PI.toFloat())) % 1f)
    drawOval(
        color = secondaryGlow.copy(alpha = (1f - pulseRadius) * 0.6f),
        topLeft = Offset(centerX - rx * (0.5f + pulseRadius * 0.5f), centerY - ry * (0.5f + pulseRadius * 0.5f)),
        size = Size(rx * (1f + pulseRadius), ry * (1f + pulseRadius)),
        style = Stroke(width = 2f)
    )
}

private fun DrawScope.drawOrbitalRing3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    radius: Float,
    tiltX: Float,
    tiltZ: Float,
    yaw: Float,
    pitch: Float,
    drawBackHalf: Boolean,
    color: Color,
    nodeColor: Color
) {
    val segments = 56
    val pts = ArrayList<ProjectedPoint>(segments + 1)
    for (i in 0..segments) {
        val theta = (i.toFloat() / segments) * (2f * PI.toFloat())
        val base = Vec3(cos(theta) * radius, 0f, sin(theta) * radius)
        val tilted = rotateVec3(base, tiltZ, tiltX)
        val world = rotateVec3(tilted, yaw * 0.6f, pitch * 0.6f)
        pts.add(project3D(world, centerX, centerY, unitPx))
    }

    for (i in 0 until segments) {
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val midZ = (p1.z + p2.z) * 0.5f
        val isBack = midZ < 0f
        if (isBack == drawBackHalf) {
            val depthAlpha = if (isBack) 0.45f else 1.0f
            drawLine(
                color = color.copy(alpha = (color.alpha * depthAlpha).coerceIn(0.1f, 1f)),
                start = Offset(p1.x, p1.y),
                end = Offset(p2.x, p2.y),
                strokeWidth = if (isBack) 2.2f else 3.8f,
                cap = StrokeCap.Round
            )
        }
    }

    // Orbiting glowing node
    val nodeIdx = 12
    val node = pts[nodeIdx]
    if ((node.z < 0f) == drawBackHalf) {
        drawCircle(
            color = nodeColor.copy(alpha = 0.35f),
            radius = 12f * node.scale,
            center = Offset(node.x, node.y)
        )
        drawCircle(
            color = Color.White,
            radius = 5f * node.scale,
            center = Offset(node.x, node.y)
        )
    }
}

private fun DrawScope.drawAudioWaveHalo(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    amp: Float,
    phase: Float,
    moodState: SaraMoodState,
    primary: Color,
    secondary: Color
) {
    val barCount = 44
    val baseRadius = unitPx * 0.88f
    val activeBoost = when (moodState) {
        SaraMoodState.LISTENING, SaraMoodState.SPEAKING -> 0.16f + amp * 0.34f
        SaraMoodState.THINKING -> 0.12f
        else -> 0.05f
    }
    for (i in 0 until barCount) {
        val angle = (i.toFloat() / barCount) * (2f * PI.toFloat())
        val wave = (sin(angle * 5f + phase * 4f) * 0.5f + 0.5f)
        val len = unitPx * (0.04f + activeBoost * wave)
        val start = Offset(
            centerX + cos(angle) * baseRadius,
            centerY + sin(angle) * baseRadius
        )
        val end = Offset(
            centerX + cos(angle) * (baseRadius + len),
            centerY + sin(angle) * (baseRadius + len)
        )
        val barColor = if (i % 2 == 0) primary else secondary
        drawLine(
            color = barColor.copy(alpha = 0.35f + wave * 0.45f),
            start = start,
            end = end,
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawBackHairVolume3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    primary: Color,
    secondary: Color
) {
    val backCenter = project3D(
        rotateVec3(Vec3(0f, 0.04f, -0.22f), yaw, pitch, roll),
        centerX,
        centerY,
        unitPx
    )
    val hairW = unitPx * 0.66f * backCenter.scale
    val hairH = unitPx * 0.78f * backCenter.scale

    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1E1B4B),
                secondary.copy(alpha = 0.65f),
                primary.copy(alpha = 0.35f)
            ),
            startY = backCenter.y - hairH,
            endY = backCenter.y + hairH
        ),
        topLeft = Offset(backCenter.x - hairW, backCenter.y - hairH * 0.76f),
        size = Size(hairW * 2f, hairH * 1.55f)
    )
}

private fun DrawScope.drawTorsoAndNeck3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    primary: Color,
    secondary: Color
) {
    val neckTop = project3D(rotateVec3(Vec3(0f, 0.44f, -0.02f), yaw * 0.6f, pitch * 0.5f), centerX, centerY, unitPx)
    val neckBot = project3D(rotateVec3(Vec3(0f, 0.64f, 0.02f), yaw * 0.4f, pitch * 0.4f), centerX, centerY, unitPx)
    val leftShoulder = project3D(rotateVec3(Vec3(-0.66f, 0.92f, 0.04f), yaw * 0.45f, pitch * 0.35f), centerX, centerY, unitPx)
    val rightShoulder = project3D(rotateVec3(Vec3(0.66f, 0.92f, 0.04f), yaw * 0.45f, pitch * 0.35f), centerX, centerY, unitPx)
    val chestMid = project3D(rotateVec3(Vec3(0f, 1.06f, 0.14f), yaw * 0.4f, pitch * 0.35f), centerX, centerY, unitPx)

    // Neck cylinder with 3D shading
    val neckW = unitPx * 0.145f
    val neckPath = Path().apply {
        moveTo(neckTop.x - neckW, neckTop.y)
        lineTo(neckTop.x + neckW, neckTop.y)
        lineTo(neckBot.x + neckW * 1.25f, neckBot.y)
        lineTo(neckBot.x - neckW * 1.25f, neckBot.y)
        close()
    }
    drawPath(
        path = neckPath,
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF15223B), Color(0xFF28466E), Color(0xFF10192E)),
            startX = neckTop.x - neckW,
            endX = neckTop.x + neckW
        )
    )

    // Holographic sculpted shoulders
    val torsoPath = Path().apply {
        moveTo(neckBot.x - neckW * 1.25f, neckBot.y)
        quadraticTo(leftShoulder.x * 0.55f + neckBot.x * 0.45f, neckBot.y + unitPx * 0.04f, leftShoulder.x, leftShoulder.y)
        quadraticTo(chestMid.x - unitPx * 0.35f, chestMid.y + unitPx * 0.04f, chestMid.x, chestMid.y)
        quadraticTo(chestMid.x + unitPx * 0.35f, chestMid.y + unitPx * 0.04f, rightShoulder.x, rightShoulder.y)
        quadraticTo(rightShoulder.x * 0.55f + neckBot.x * 0.45f, neckBot.y + unitPx * 0.04f, neckBot.x + neckW * 1.25f, neckBot.y)
        close()
    }
    drawPath(
        path = torsoPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF1A2A4A),
                Color(0xFF0F1A30),
                secondary.copy(alpha = 0.28f)
            ),
            start = Offset(leftShoulder.x, neckBot.y),
            end = Offset(rightShoulder.x, chestMid.y)
        )
    )
    drawPath(
        path = torsoPath,
        color = primary.copy(alpha = 0.65f),
        style = Stroke(width = 3f, join = StrokeJoin.Round)
    )

    // Glowing V-core emblem on chest
    val core = project3D(rotateVec3(Vec3(0f, 0.80f, 0.14f), yaw * 0.4f, pitch * 0.35f), centerX, centerY, unitPx)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, primary, Color.Transparent),
            center = Offset(core.x, core.y),
            radius = unitPx * 0.12f
        ),
        radius = unitPx * 0.12f,
        center = Offset(core.x, core.y)
    )
}

private fun DrawScope.drawHeadVolume3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    primary: Color,
    secondary: Color,
    amp: Float
) {
    val headCenter = project3D(rotateVec3(Vec3(0f, 0f, 0.04f), yaw, pitch, roll), centerX, centerY, unitPx)
    val rx = unitPx * 0.46f * headCenter.scale
    val ry = unitPx * 0.56f * headCenter.scale

    // Sculpted jaw-tapered face contour
    val chin = project3D(rotateVec3(Vec3(0f, 0.53f, 0.14f), yaw, pitch, roll), centerX, centerY, unitPx)
    val leftCheek = project3D(rotateVec3(Vec3(-0.44f, 0.10f, 0.08f), yaw, pitch, roll), centerX, centerY, unitPx)
    val rightCheek = project3D(rotateVec3(Vec3(0.44f, 0.10f, 0.08f), yaw, pitch, roll), centerX, centerY, unitPx)
    val crown = project3D(rotateVec3(Vec3(0f, -0.54f, 0.05f), yaw, pitch, roll), centerX, centerY, unitPx)

    val facePath = Path().apply {
        moveTo(crown.x, crown.y)
        cubicTo(
            crown.x + rx * 0.88f, crown.y,
            rightCheek.x + rx * 0.12f, rightCheek.y - ry * 0.28f,
            rightCheek.x, rightCheek.y
        )
        cubicTo(
            rightCheek.x - rx * 0.05f, rightCheek.y + ry * 0.45f,
            chin.x + rx * 0.34f, chin.y - ry * 0.05f,
            chin.x, chin.y
        )
        cubicTo(
            chin.x - rx * 0.34f, chin.y - ry * 0.05f,
            leftCheek.x + rx * 0.05f, leftCheek.y + ry * 0.45f,
            leftCheek.x, leftCheek.y
        )
        cubicTo(
            leftCheek.x - rx * 0.12f, leftCheek.y - ry * 0.28f,
            crown.x - rx * 0.88f, crown.y,
            crown.x, crown.y
        )
        close()
    }

    // Specular light offset shifts dynamically opposite to head rotation for 3D realism
    val lightOffsetX = headCenter.x - rx * 0.32f - yaw * unitPx * 0.2f
    val lightOffsetY = headCenter.y - ry * 0.38f - pitch * unitPx * 0.2f

    drawPath(
        path = facePath,
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF476C9B),
                Color(0xFF1F3356),
                Color(0xFF0E182E)
            ),
            center = Offset(lightOffsetX, lightOffsetY),
            radius = ry * 1.65f
        )
    )

    // Holographic rim highlight
    drawPath(
        path = facePath,
        brush = Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = 0.85f + amp * 0.15f),
                secondary.copy(alpha = 0.6f),
                primary.copy(alpha = 0.8f)
            ),
            start = Offset(leftCheek.x, crown.y),
            end = Offset(rightCheek.x, chin.y)
        ),
        style = Stroke(width = 3.2f)
    )

    // Subtle cheek holographic contour lines
    val leftGlow = project3D(rotateVec3(Vec3(-0.26f, 0.13f, 0.35f), yaw, pitch, roll), centerX, centerY, unitPx)
    val rightGlow = project3D(rotateVec3(Vec3(0.26f, 0.13f, 0.35f), yaw, pitch, roll), centerX, centerY, unitPx)
    drawCircle(
        color = Color(0xFFFB7185).copy(alpha = 0.14f),
        radius = unitPx * 0.11f,
        center = Offset(leftGlow.x, leftGlow.y)
    )
    drawCircle(
        color = Color(0xFFFB7185).copy(alpha = 0.14f),
        radius = unitPx * 0.11f,
        center = Offset(rightGlow.x, rightGlow.y)
    )
}

private fun DrawScope.drawEarpiecePods3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    primary: Color,
    accent: Color,
    amp: Float
) {
    val pods = listOf(
        rotateVec3(Vec3(-0.48f, -0.02f, 0.02f), yaw, pitch, roll),
        rotateVec3(Vec3(0.48f, -0.02f, 0.02f), yaw, pitch, roll)
    ).sortedBy { it.z }

    for (pod in pods) {
        val p = project3D(pod, centerX, centerY, unitPx)
        val r = unitPx * 0.115f * p.scale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF283E66), Color(0xFF0E172B)),
                center = Offset(p.x - r * 0.25f, p.y - r * 0.25f),
                radius = r * 1.4f
            ),
            radius = r,
            center = Offset(p.x, p.y)
        )
        drawCircle(
            color = primary,
            radius = r,
            center = Offset(p.x, p.y),
            style = Stroke(width = 2.6f)
        )
        drawCircle(
            color = accent.copy(alpha = 0.55f + amp * 0.45f),
            radius = r * (0.45f + amp * 0.22f),
            center = Offset(p.x, p.y)
        )
    }
}

private fun DrawScope.drawFacialFeatures3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    blink: Float,
    mouthOpen: Float,
    moodState: SaraMoodState,
    primary: Color,
    accent: Color,
    orbitPhase: Float
) {
    // 3D Eyes
    val eyeOffsets = listOf(-0.18f, 0.18f)
    val gazeX = sin(orbitPhase * 0.7f) * 0.016f
    val eyeOpenHeight = (1f - blink).coerceIn(0.06f, 1f)

    for (ex in eyeOffsets) {
        val eyeVec = rotateVec3(Vec3(ex, -0.05f, 0.43f), yaw, pitch, roll)
        if (eyeVec.z > -0.15f) {
            val ep = project3D(eyeVec, centerX, centerY, unitPx)
            val eyeW = unitPx * 0.095f * ep.scale * cos(yaw * 0.65f).coerceAtLeast(0.35f)
            val eyeH = unitPx * 0.068f * ep.scale * eyeOpenHeight

            // Eyebrow
            val browVec = rotateVec3(Vec3(ex, -0.17f - if (moodState == SaraMoodState.LISTENING) 0.02f else 0f, 0.43f), yaw, pitch, roll)
            val bp = project3D(browVec, centerX, centerY, unitPx)
            val browPath = Path().apply {
                moveTo(bp.x - eyeW * 1.05f, bp.y + unitPx * 0.01f)
                quadraticTo(bp.x, bp.y - unitPx * 0.03f, bp.x + eyeW * 1.05f, bp.y + unitPx * 0.01f)
            }
            drawPath(
                path = browPath,
                color = primary.copy(alpha = 0.9f),
                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
            )

            // Eye sclera / luminous socket
            drawOval(
                color = Color(0xFF060D1D),
                topLeft = Offset(ep.x - eyeW, ep.y - eyeH),
                size = Size(eyeW * 2f, eyeH * 2f)
            )
            drawOval(
                color = primary.copy(alpha = 0.85f),
                topLeft = Offset(ep.x - eyeW, ep.y - eyeH),
                size = Size(eyeW * 2f, eyeH * 2f),
                style = Stroke(width = 2.2f)
            )

            if (eyeOpenHeight > 0.2f) {
                val irisR = minOf(eyeW, eyeH) * 0.78f
                val ix = ep.x + gazeX * unitPx
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, accent, primary),
                        center = Offset(ix, ep.y),
                        radius = irisR
                    ),
                    radius = irisR,
                    center = Offset(ix, ep.y)
                )
                drawCircle(
                    color = Color(0xFF040A17),
                    radius = irisR * 0.42f,
                    center = Offset(ix, ep.y)
                )
                // Catchlight
                drawCircle(
                    color = Color.White,
                    radius = irisR * 0.24f,
                    center = Offset(ix - irisR * 0.3f, ep.y - irisR * 0.3f)
                )
            }
        }
    }

    // 3D Nose Bridge & Tip highlight
    val noseTop = project3D(rotateVec3(Vec3(0f, -0.02f, 0.46f), yaw, pitch, roll), centerX, centerY, unitPx)
    val noseTip = project3D(rotateVec3(Vec3(0f, 0.11f, 0.51f), yaw, pitch, roll), centerX, centerY, unitPx)
    drawLine(
        color = primary.copy(alpha = 0.42f),
        start = Offset(noseTop.x, noseTop.y),
        end = Offset(noseTip.x, noseTip.y),
        strokeWidth = 2.4f,
        cap = StrokeCap.Round
    )

    // 3D Animated Lip-Sync Mouth
    val mouthLeft = project3D(rotateVec3(Vec3(-0.12f, 0.25f, 0.42f), yaw, pitch, roll), centerX, centerY, unitPx)
    val mouthRight = project3D(rotateVec3(Vec3(0.12f, 0.25f, 0.42f), yaw, pitch, roll), centerX, centerY, unitPx)
    val mouthCenter = project3D(rotateVec3(Vec3(0f, 0.26f, 0.46f), yaw, pitch, roll), centerX, centerY, unitPx)

    val smileOffset = when (moodState) {
        SaraMoodState.ACTION_SUCCESS -> unitPx * 0.045f
        SaraMoodState.IDLE -> unitPx * 0.025f
        else -> unitPx * 0.018f
    }
    val aperture = (mouthOpen * unitPx * 0.085f).coerceAtLeast(1.5f)

    if (mouthOpen > 0.08f) {
        val mouthPath = Path().apply {
            moveTo(mouthLeft.x, mouthLeft.y)
            quadraticTo(mouthCenter.x, mouthCenter.y - aperture * 0.55f, mouthRight.x, mouthRight.y)
            quadraticTo(mouthCenter.x, mouthCenter.y + aperture + smileOffset * 0.4f, mouthLeft.x, mouthLeft.y)
            close()
        }
        drawPath(
            path = mouthPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0A1428), primary.copy(alpha = 0.55f))
            )
        )
        drawPath(
            path = mouthPath,
            color = Color(0xFFFB7185).copy(alpha = 0.9f),
            style = Stroke(width = 3f, join = StrokeJoin.Round)
        )
    } else {
        val smilePath = Path().apply {
            moveTo(mouthLeft.x, mouthLeft.y)
            quadraticTo(mouthCenter.x, mouthCenter.y + smileOffset, mouthRight.x, mouthRight.y)
        }
        drawPath(
            path = smilePath,
            color = Color(0xFFFB7185).copy(alpha = 0.92f),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawFrontHairAndTiara3D(
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    primary: Color,
    secondary: Color
) {
    val crownTop = project3D(rotateVec3(Vec3(0f, -0.58f, 0.08f), yaw, pitch, roll), centerX, centerY, unitPx)
    val leftTemple = project3D(rotateVec3(Vec3(-0.46f, -0.22f, 0.16f), yaw, pitch, roll), centerX, centerY, unitPx)
    val rightTemple = project3D(rotateVec3(Vec3(0.46f, -0.22f, 0.16f), yaw, pitch, roll), centerX, centerY, unitPx)
    val foreheadArc = project3D(rotateVec3(Vec3(0.06f, -0.34f, 0.40f), yaw, pitch, roll), centerX, centerY, unitPx)

    // Swept futuristic bangs
    val hairSweep = Path().apply {
        moveTo(leftTemple.x, leftTemple.y)
        cubicTo(
            leftTemple.x + unitPx * 0.05f, crownTop.y - unitPx * 0.06f,
            crownTop.x - unitPx * 0.1f, crownTop.y - unitPx * 0.08f,
            crownTop.x + unitPx * 0.12f, crownTop.y - unitPx * 0.04f
        )
        cubicTo(
            rightTemple.x - unitPx * 0.02f, crownTop.y + unitPx * 0.04f,
            rightTemple.x + unitPx * 0.08f, rightTemple.y,
            rightTemple.x, rightTemple.y + unitPx * 0.14f
        )
        quadraticTo(foreheadArc.x, foreheadArc.y - unitPx * 0.08f, leftTemple.x, leftTemple.y + unitPx * 0.08f)
        close()
    }

    drawPath(
        path = hairSweep,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF172554), secondary.copy(alpha = 0.85f), primary.copy(alpha = 0.7f)),
            start = Offset(leftTemple.x, crownTop.y),
            end = Offset(rightTemple.x, rightTemple.y)
        )
    )
    drawPath(
        path = hairSweep,
        color = primary.copy(alpha = 0.65f),
        style = Stroke(width = 2.2f)
    )

    // Holographic forehead circlet node
    val circlet = project3D(rotateVec3(Vec3(0f, -0.28f, 0.44f), yaw, pitch, roll), centerX, centerY, unitPx)
    drawCircle(
        color = primary.copy(alpha = 0.85f),
        radius = unitPx * 0.032f * circlet.scale,
        center = Offset(circlet.x, circlet.y)
    )
    drawCircle(
        color = Color.White,
        radius = unitPx * 0.015f * circlet.scale,
        center = Offset(circlet.x, circlet.y)
    )
}
