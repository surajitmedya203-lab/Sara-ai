package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.Sara3DCharacter
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.SaraShaderStyle
import com.example.viewmodel.ConversationBubble
import com.example.viewmodel.SaraMoodState
import com.example.viewmodel.SaraViewModel
import com.example.voice.SpeechUiState

@Composable
fun AssistantStageScreen(
    viewModel: SaraViewModel,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val moodState by viewModel.moodState.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val micRms by viewModel.micRmsLevel.collectAsStateWithLifecycle()
    val ttsMouth by viewModel.ttsMouthAmplitude.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val needsContactPerm by viewModel.needsContactPermissionPrompt.collectAsStateWithLifecycle()
    val lastUsedSampleAi by viewModel.lastUsedSampleAi.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val shaderStyle = remember(prefs.shaderStyleId) { SaraShaderStyle.fromId(prefs.shaderStyleId) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceListening()
        }
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.dismissContactPermissionPrompt()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val liveAudioAmp = when {
        speechState is SpeechUiState.Listening -> maxOf(0.18f, micRms)
        isSpeaking -> ttsMouth
        else -> 0f
    }

    val onMicTap: () -> Unit = {
        if (speechState is SpeechUiState.Listening) {
            viewModel.stopVoiceListening()
        } else {
            val hasMic = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                viewModel.startVoiceListening()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Subtle generated holographic studio backdrop
        Image(
            painter = painterResource(id = R.drawable.img_sara_backdrop_1795067994242),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (prefs.darkTheme) 0.55f else 0.14f)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top HUD Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            RoundedCornerShape(20.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "SARA 3D",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (prefs.isVoiceEnrolled) {
                        Surface(
                            color = EmeraldPulse.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = "Voiceprint active",
                                    tint = EmeraldPulse,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Voice ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldPulse
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (telemetry.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${telemetry.batteryPercent}%",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .testTag("language_toggle_chip")
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                val next = if (prefs.languageCode.startsWith("bn")) "en-US" else "bn-BD"
                                viewModel.updateLanguage(next)
                            }
                    ) {
                        Text(
                            text = if (prefs.languageCode.startsWith("bn")) "বাংলা" else "EN",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Sample AI Notice Banner (shown when generative AI fallback is active)
            AnimatedVisibility(visible = lastUsedSampleAi) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.sample_ai_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Contact Permission Rationale Banner
            AnimatedVisibility(visible = needsContactPerm) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "কন্টাক্টস থেকে কল/SMS করতে অনুমতি দিন",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }) {
                            Text("Allow")
                        }
                    }
                }
            }

            // Interactive 3D Sara Hologram Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isWideScreen) 290.dp else 255.dp),
                contentAlignment = Alignment.Center
            ) {
                Sara3DCharacter(
                    moodState = moodState,
                    audioAmplitude = liveAudioAmp,
                    shaderStyle = shaderStyle,
                    onTapCharacter = onMicTap,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("sara_3d_character")
                )

                // Mood & Live Transcript Floating Pill
                val moodText = when (val st = speechState) {
                    is SpeechUiState.Listening -> {
                        if (st.partialText.isNotBlank()) "\"${st.partialText}\""
                        else if (prefs.languageCode.startsWith("bn")) "শুনছি... বলুন" else "Listening..."
                    }
                    else -> when (moodState) {
                        SaraMoodState.THINKING -> if (prefs.languageCode.startsWith("bn")) "প্রসেস করছি..." else "Thinking..."
                        SaraMoodState.SPEAKING -> if (prefs.languageCode.startsWith("bn")) "সারা কথা বলছে..." else "Speaking..."
                        SaraMoodState.ACTION_SUCCESS -> if (prefs.languageCode.startsWith("bn")) "কাজ সম্পন্ন" else "Done"
                        else -> if (prefs.languageCode.startsWith("bn")) "ট্যাপ করে কথা বলুন • ড্র্যাগ করে 3D ঘোরান" else "Tap to speak • Drag to rotate 3D"
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Text(
                        text = moodText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            // Conversation Bubbles
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { bubble ->
                    ChatBubbleItem(bubble = bubble)
                }
            }

            // Quick A-to-Z Voice Command Chips
            val quickCommands = remember(prefs.languageCode) {
                if (prefs.languageCode.startsWith("bn")) {
                    listOf(
                        "ফ্ল্যাশলাইট অন করো",
                        "ফ্ল্যাশলাইট বন্ধ করো",
                        "ব্যাটারি কত?",
                        "সকাল ৭টায় অ্যালার্ম দাও",
                        "এখন কয়টা বাজে?",
                        "সকালের রুটিন চালাও",
                        "YouTube খোলো",
                        "আমার কাজ দেখাও"
                    )
                } else {
                    listOf(
                        "Turn on flashlight",
                        "Turn off flashlight",
                        "Battery status",
                        "Set alarm for 7:00 AM",
                        "What time is it?",
                        "Run morning brief",
                        "Open YouTube",
                        "Show my tasks"
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickCommands.forEach { cmd ->
                    AssistChip(
                        onClick = { viewModel.processUserCommand(cmd) },
                        label = {
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        )
                    )
                }
            }

            // Bottom Command Input + Tactile Pulsing Mic Orb
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = if (prefs.languageCode.startsWith("bn")) {
                                "সারা-কে কমান্ড লিখুন বা মাইকে বলুন..."
                            } else {
                                stringResource(id = R.string.ask_sara_hint)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.processUserCommand(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    trailingIcon = {
                        if (inputText.isNotBlank()) {
                            FilledIconButton(
                                onClick = {
                                    viewModel.processUserCommand(inputText)
                                    inputText = ""
                                },
                                modifier = Modifier.testTag("send_command_button"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send command"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input")
                )

                MicOrbButton(
                    isListening = speechState is SpeechUiState.Listening,
                    isSpeaking = isSpeaking,
                    onClick = {
                        if (isSpeaking) {
                            viewModel.ttsManager.stop()
                        } else {
                            onMicTap()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(bubble: ConversationBubble) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (bubble.isUser) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (bubble.isUser) 18.dp else 4.dp,
                bottomEnd = if (bubble.isUser) 4.dp else 18.dp
            ),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .border(
                    1.dp,
                    if (bubble.isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    },
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (bubble.isUser) 18.dp else 4.dp,
                        bottomEnd = if (bubble.isUser) 4.dp else 18.dp
                    )
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!bubble.isUser && bubble.badge != null) {
                    Text(
                        text = if (bubble.isSampleAi) "SARA • SAMPLE AI" else "SARA • ${bubble.badge}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bubble.isSampleAi) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Text(
                    text = bubble.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (bubble.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun MicOrbButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.14f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val orbBrush = when {
        isListening -> Brush.linearGradient(listOf(Color(0xFF00F5D4), Color(0xFF38BDF8)))
        isSpeaking -> Brush.linearGradient(listOf(Color(0xFFFB7185), Color(0xFF8B5CF6)))
        else -> Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF8B5CF6)))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(62.dp)
            .scale(pulseScale)
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
            tint = Color(0xFF03101F),
            modifier = Modifier.size(28.dp)
        )
    }
}
