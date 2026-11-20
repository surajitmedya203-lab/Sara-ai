package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.SaraShaderStyle
import com.example.viewmodel.SaraViewModel

@Composable
fun VoiceProfileScreen(
    viewModel: SaraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val enrollment by viewModel.enrollmentState.collectAsStateWithLifecycle()
    val liveAmp by viewModel.enrollmentAmplitude.collectAsStateWithLifecycle()

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var nameInput by remember(prefs.userName) { mutableStateOf(prefs.userName) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    val runWithMicPermission: (() -> Unit) -> Unit = { action ->
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingAction = action
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Voice ID & Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "সারা-কে আপনার কণ্ঠ চিনিয়ে দিন এবং থ্রিডি অ্যাসিস্ট্যান্ট কাস্টমাইজ করুন",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Voiceprint Enrollment Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(
                    1.dp,
                    if (prefs.isVoiceEnrolled) EmeraldPulse.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (prefs.isVoiceEnrolled) Icons.Default.VerifiedUser else Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = if (prefs.isVoiceEnrolled) EmeraldPulse else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (prefs.isVoiceEnrolled) "আপনার ভয়েস প্রোফাইল সক্রিয়" else "ভয়েস রিকগনিশন সেটআপ (Voice ID)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(id = R.string.voice_enroll_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Calibration progress or live waveform bar
                    if (enrollment.isCapturing) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Recording acoustic voiceprint...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LinearProgressIndicator(
                                progress = { liveAmp.coerceIn(0.08f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    } else {
                        val progress = if (prefs.isVoiceEnrolled) 1f else enrollment.samplesCaptured / 3f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }

                    if (enrollment.statusMessage.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = enrollment.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { runWithMicPermission { viewModel.captureEnrollmentSample() } },
                            enabled = !enrollment.isCapturing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("enroll_voice_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = if (prefs.isVoiceEnrolled) "Re-enroll (3x)" else "Record Sample (${enrollment.samplesCaptured}/3)"
                            )
                        }

                        if (prefs.isVoiceEnrolled) {
                            OutlinedButton(
                                onClick = { runWithMicPermission { viewModel.verifyCurrentSpeaker() } },
                                enabled = !enrollment.isCapturing,
                                modifier = Modifier.testTag("verify_voice_button")
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Verify")
                            }
                        }
                    }

                    if (prefs.isVoiceEnrolled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Voice ID Lock Badge", style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = prefs.voiceLockEnabled,
                                    onCheckedChange = { viewModel.updateVoiceLock(it) }
                                )
                                OutlinedButton(
                                    onClick = { viewModel.resetVoiceProfile() },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reset")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Personalization & Sara 3D Shader Style
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Personalization", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Sara will call you") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { viewModel.updateUserName(nameInput) }) {
                            Text("Save")
                        }
                    }

                    Text("Voice Language (কথা বলার ভাষা)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = prefs.languageCode == "bn-BD",
                            onClick = { viewModel.updateLanguage("bn-BD") },
                            label = { Text("বাংলা (BD)") }
                        )
                        FilterChip(
                            selected = prefs.languageCode == "bn-IN",
                            onClick = { viewModel.updateLanguage("bn-IN") },
                            label = { Text("বাংলা (IN)") }
                        )
                        FilterChip(
                            selected = prefs.languageCode == "en-US",
                            onClick = { viewModel.updateLanguage("en-US") },
                            label = { Text("English") }
                        )
                    }

                    Text("3D Character Hologram Theme", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SaraShaderStyle.entries.forEach { style ->
                            val selected = prefs.shaderStyleId == style.id
                            Surface(
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) style.primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.updateShaderStyle(style.id) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(style.primaryColor)
                                    )
                                    Text(
                                        text = style.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speak Responses Aloud (TTS)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "সারা উত্তর দেওয়ার সময় মুখে কথা বলবে ও ঠোঁট নাড়াবে",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefs.speakResponses,
                            onCheckedChange = { viewModel.updateSpeakResponses(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Obsidian Dark Mode", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "হলোগ্রাফিক ডার্ক ইন্টারফেস",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefs.darkTheme,
                            onCheckedChange = { viewModel.updateDarkTheme(it) }
                        )
                    }
                }
            }
        }

        // Firebase AI Notice Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = stringResource(id = R.string.sample_ai_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
