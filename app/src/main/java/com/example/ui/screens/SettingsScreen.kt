package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.AspectRatioMode
import com.example.data.EmulatorSettings
import com.example.data.RegionMode
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurfaceBright
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: EmulatorSettings,
    onUpdateSettings: ((EmulatorSettings) -> EmulatorSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Ajustes del Emulador",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Configura video GPU, audio SPU, controles y región",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }

        // Video Section
        item {
            SettingsCard(title = "Video y GPU") {
                Text(
                    text = "Relación de aspecto",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AspectRatioMode.entries) { mode ->
                        FilterChip(
                            selected = settings.aspectRatio == mode,
                            onClick = { onUpdateSettings { it.copy(aspectRatio = mode) } },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanContainer,
                                selectedLabelColor = ElectricCyan,
                                containerColor = SlateSurfaceBright,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                SettingToggleRow(
                    title = "Filtro CRT Scanlines",
                    subtitle = "Líneas de exploración retro clásicas",
                    checked = settings.scanlines,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(scanlines = v) } }
                )

                SettingToggleRow(
                    title = "Suavizado Bilineal",
                    subtitle = "Suaviza los píxeles al escalar a pantallas HD",
                    checked = settings.bilinearFilter,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(bilinearFilter = v) } }
                )

                SettingToggleRow(
                    title = "Mostrar HUD de rendimiento",
                    subtitle = "Muestra FPS, resolución y dirección PC en juego",
                    checked = settings.showFpsHud,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showFpsHud = v) } }
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Salto de fotogramas (Frame Skip)", color = TextPrimary)
                        Text("${settings.frameSkip}", color = ElectricCyan)
                    }
                    Slider(
                        value = settings.frameSkip.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(frameSkip = v.roundToInt()) } },
                        valueRange = 0f..3f,
                        steps = 2,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                    )
                }
            }
        }

        // Audio & System Section
        item {
            SettingsCard(title = "Audio y Sistema") {
                SettingToggleRow(
                    title = "Audio SPU (44.1 kHz Estéreo)",
                    subtitle = "Síntesis ADPCM de 24 canales y CD-XA",
                    checked = settings.audioEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(audioEnabled = v) } }
                )

                Text(
                    text = "Estándar de región / vídeo",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RegionMode.entries) { reg ->
                        FilterChip(
                            selected = settings.regionMode == reg,
                            onClick = { onUpdateSettings { it.copy(regionMode = reg) } },
                            label = { Text(reg.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanContainer,
                                selectedLabelColor = ElectricCyan,
                                containerColor = SlateSurfaceBright,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Velocidad Avance Rápido", color = TextPrimary)
                        Text("${settings.fastForwardMultiplier}x", color = ElectricCyan)
                    }
                    Slider(
                        value = settings.fastForwardMultiplier.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(fastForwardMultiplier = v.roundToInt()) } },
                        valueRange = 2f..4f,
                        steps = 1,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                    )
                }
            }
        }

        // Controls Section
        item {
            SettingsCard(title = "Controles DualShock y Mandos") {
                SettingToggleRow(
                    title = "Modo Analógico DualShock",
                    subtitle = "Activa sticks analógicos L3 / R3 en pantalla",
                    checked = settings.analogPadMode,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(analogPadMode = v) } }
                )

                SettingToggleRow(
                    title = "Vibración háptica al pulsar",
                    subtitle = "Respuesta táctil en los botones virtuales",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(hapticsEnabled = v) } }
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Opacidad del mando virtual", color = TextPrimary)
                        Text("${(settings.controllerOpacity * 100).roundToInt()}%", color = ElectricCyan)
                    }
                    Slider(
                        value = settings.controllerOpacity,
                        onValueChange = { v -> onUpdateSettings { it.copy(controllerOpacity = v) } },
                        valueRange = 0.3f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                    )
                }

                Text(
                    text = "Mandos físicos Bluetooth / USB: Compatibilidad automática plug-and-play (Cruceta, Sticks, A/B/X/Y, L1/R1/L2/R2, Start y Select).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateOutline, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ElectricCyan
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBg,
                checkedTrackColor = ElectricCyan
            )
        )
    }
}
