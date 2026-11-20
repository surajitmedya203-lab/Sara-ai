package com.example.ui.screens

import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.EmeraldPulse
import com.example.viewmodel.SaraViewModel
import kotlin.math.roundToInt

private data class QuickToolItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun PhoneHubScreen(
    viewModel: SaraViewModel,
    modifier: Modifier = Modifier
) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val flashlightOn by viewModel.flashlightOn.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val commandLogs by viewModel.commandLogs.collectAsStateWithLifecycle()

    var volumeSlider by remember(telemetry.mediaVolumePercent) {
        mutableFloatStateOf(telemetry.mediaVolumePercent.toFloat())
    }
    var appQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceState()
    }

    val filteredApps = remember(installedApps, appQuery) {
        if (appQuery.isBlank()) installedApps.take(18)
        else installedApps.filter {
            it.label.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    val quickTools = remember {
        listOf(
            QuickToolItem("Alarm 7:00 AM", "অ্যালার্ম", Icons.Default.Alarm) {
                viewModel.processUserCommand("Set alarm for 7:00 AM")
            },
            QuickToolItem("5 Min Timer", "টাইমার", Icons.Default.Timer) {
                viewModel.processUserCommand("5 minute timer")
            },
            QuickToolItem("Phone Dialer", "কল করুন", Icons.Default.Call) {
                viewModel.processUserCommand("Call ")
            },
            QuickToolItem("Compose SMS", "মেসেজ", Icons.Default.Message) {
                viewModel.processUserCommand("Send SMS ")
            },
            QuickToolItem("Camera", "ক্যামেরা", Icons.Default.CameraAlt) {
                viewModel.processUserCommand("Open camera")
            },
            QuickToolItem("Calendar", "ইভেন্ট", Icons.Default.Event) {
                viewModel.processUserCommand("Schedule event")
            },
            QuickToolItem("Wi-Fi", "সেটিংস", Icons.Default.Wifi) {
                viewModel.openSettingsDirect(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")
            },
            QuickToolItem("Bluetooth", "কানেকশন", Icons.Default.Bluetooth) {
                viewModel.openSettingsDirect(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
            },
            QuickToolItem("Brightness", "ডিসপ্লে", Icons.Default.BrightnessHigh) {
                viewModel.openSettingsDirect(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings")
            },
            QuickToolItem("Location", "জিপিএস", Icons.Default.LocationOn) {
                viewModel.openSettingsDirect(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location Settings")
            }
        )
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
                text = "A-to-Z Phone Hub",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "সারা-র মাধ্যমে ফোনের সব হার্ডওয়্যার, সেটিংস ও অ্যাপ কন্ট্রোল করুন",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Telemetry Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryCard(
                    title = "Battery",
                    value = "${telemetry.batteryPercent}%",
                    subtitle = if (telemetry.isCharging) "Charging" else "On Battery",
                    icon = Icons.Default.BatteryChargingFull,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCard(
                    title = "Network",
                    value = telemetry.networkLabel,
                    subtitle = telemetry.currentTimeLabel,
                    icon = Icons.Default.Wifi,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCard(
                    title = "Storage",
                    value = telemetry.freeStorageGb,
                    subtitle = "Available",
                    icon = Icons.Default.Storage,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Hardware Controls Card (Flashlight, Volume, Sound Mode)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (flashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                contentDescription = "Flashlight",
                                tint = if (flashlightOn) EmeraldPulse else MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Flashlight (টর্চ লাইট)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (flashlightOn) "On • জ্বলছে" else "Off • বন্ধ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = flashlightOn,
                            onCheckedChange = { viewModel.toggleFlashlightDirect(it) },
                            modifier = Modifier.testTag("flashlight_switch")
                        )
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Media Volume",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text("Media Volume", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "${volumeSlider.roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = volumeSlider,
                            onValueChange = { volumeSlider = it },
                            onValueChangeFinished = { viewModel.setVolumeDirect(volumeSlider.roundToInt()) },
                            valueRange = 0f..100f
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Ring" to AudioManager.RINGER_MODE_NORMAL,
                            "Vibrate" to AudioManager.RINGER_MODE_VIBRATE,
                            "Silent" to AudioManager.RINGER_MODE_SILENT
                        ).forEach { (label, mode) ->
                            FilterChip(
                                selected = telemetry.ringModeLabel == label,
                                onClick = {
                                    viewModel.actionExecutor.setRingerMode(mode)
                                    viewModel.refreshDeviceState()
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Quick System Actions
        item {
            Text(
                text = "Quick System Actions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(quickTools) { tool ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .width(136.dp)
                            .clickable(onClick = tool.onClick)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = tool.icon,
                                contentDescription = tool.title,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = tool.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tool.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Installed Apps Launcher
        item {
            Text(
                text = "Launch Any Installed App (${installedApps.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search apps on phone...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .width(118.dp)
                            .clickable { viewModel.launchInstalledApp(app) }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = app.label,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Command Execution Log Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Action & Command History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (commandLogs.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear history"
                        )
                    }
                }
            }
        }

        if (commandLogs.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No commands executed yet. Speak to Sara or use any quick control above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(commandLogs, key = { it.id }) { log ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = log.intentCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.actionSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = log.spokenText,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = log.saraReply,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
