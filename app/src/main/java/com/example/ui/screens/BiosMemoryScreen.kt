package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.psx.MemoryCardSlotInfo
import com.example.data.EmulatorSettings
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsCircleRed
import com.example.ui.theme.PsTriangleGreen
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceBright
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun BiosMemoryScreen(
    settings: EmulatorSettings,
    memCard1: List<MemoryCardSlotInfo>,
    memCard2: List<MemoryCardSlotInfo>,
    onPickBios: () -> Unit,
    onToggleExternalBios: (Boolean) -> Unit,
    onClearBios: () -> Unit,
    onFormatMemCard: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmFormatSlot by remember { mutableIntStateOf(-1) }

    if (confirmFormatSlot >= 0) {
        AlertDialog(
            onDismissRequest = { confirmFormatSlot = -1 },
            title = { Text("Formatear Memory Card ${confirmFormatSlot + 1}") },
            text = {
                Text("Se borrarán todas las partidas guardadas en la ranura ${confirmFormatSlot + 1} (128 KB). Esta acción no se puede deshacer.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onFormatMemCard(confirmFormatSlot)
                        confirmFormatSlot = -1
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PsCircleRed)
                ) {
                    Text("Formatear", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFormatSlot = -1 }) {
                    Text("Cancelar")
                }
            }
        )
    }

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
                    text = "BIOS y Memory Cards",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Arquitectura de sistema PS1 (2 MB RAM · 1 MB VRAM · 512 KB SPU)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }

        // BIOS Card
        item {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyanContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = ElectricCyan)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Motor de BIOS",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (settings.useExternalBios && settings.externalBiosName.isNotBlank()) {
                                    "BIOS Externa: ${settings.externalBiosName}"
                                } else {
                                    "PSX Nova HLE Kernel (Listo para jugar)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = PsTriangleGreen
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Activo",
                            tint = PsTriangleGreen
                        )
                    }

                    Text(
                        text = "Puedes jugar al instante usando la BIOS HLE integrada o cargar una BIOS oficial de 512 KB (ej. SCPH1001.BIN) para arranque LLE completo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    if (settings.externalBiosName.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Usar BIOS externa al iniciar",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                            Switch(
                                checked = settings.useExternalBios,
                                onCheckedChange = onToggleExternalBios,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ObsidianBg,
                                    checkedTrackColor = ElectricCyan
                                )
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onPickBios,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("load_bios_button")
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cargar BIOS (.bin)", fontWeight = FontWeight.Bold)
                        }
                        if (settings.externalBiosName.isNotBlank()) {
                            OutlinedButton(
                                onClick = onClearBios,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Usar HLE", color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Memory Cards
        item {
            MemoryCardPanel(
                slotIndex = 0,
                entries = memCard1,
                onFormat = { confirmFormatSlot = 0 }
            )
        }

        item {
            MemoryCardPanel(
                slotIndex = 1,
                entries = memCard2,
                onFormat = { confirmFormatSlot = 1 }
            )
        }
    }
}

@Composable
private fun MemoryCardPanel(
    slotIndex: Int,
    entries: List<MemoryCardSlotInfo>,
    onFormat: () -> Unit
) {
    val usedBlocks = entries.sumOf { it.blockCount }.coerceIn(0, 15)

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateSurfaceBright),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SdStorage, contentDescription = null, tint = ElectricCyan)
                    }
                    Column {
                        Text(
                            text = "Memory Card — Ranura ${slotIndex + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "$usedBlocks / 15 bloques ocupados (128 KB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                OutlinedButton(
                    onClick = onFormat,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("format_memcard_$slotIndex")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = PsCircleRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Formatear", color = TextPrimary)
                }
            }

            // 15-block PS1 Memory Card visual bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (b in 0 until 15) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (b < usedBlocks) ElectricCyan else SlateSurface)
                    )
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = "Tarjeta formateada y lista para guardar partidas desde los juegos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SlateSurface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${entry.blockCount} blq",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}
