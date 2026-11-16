package com.example.ui.screens

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.psx.Ps1Button
import com.example.data.AspectRatioMode
import com.example.data.EmulatorSettings
import com.example.data.SaveStateEntity
import com.example.ui.EmulationUiState
import com.example.ui.components.DualShockOverlay
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsCircleRed
import com.example.ui.theme.PsTriangleGreen
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceBright
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorPlayScreen(
    state: EmulationUiState,
    settings: EmulatorSettings,
    saveStates: List<SaveStateEntity>,
    onButtonChanged: (Ps1Button, Boolean) -> Unit,
    onStickChanged: (Boolean, Float, Float) -> Unit,
    onHardwareKey: (Int, Boolean) -> Boolean,
    onTogglePause: () -> Unit,
    onToggleFastForward: () -> Unit,
    onToggleAnalog: () -> Unit,
    onToggleScanlines: () -> Unit,
    onSaveSlot: (Int) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onResetConsole: () -> Unit,
    onSwapDisc: () -> Unit,
    onExitEmulation: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showMenuSheet by remember { mutableStateOf(false) }

    BackHandler {
        if (showMenuSheet) {
            showMenuSheet = false
            if (state.isPaused) onTogglePause()
        } else {
            if (!state.isPaused) onTogglePause()
            showMenuSheet = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val isDown = ev.type == KeyEventType.KeyDown
                val isUp = ev.type == KeyEventType.KeyUp
                if (isDown || isUp) onHardwareKey(ev.nativeKeyEvent.keyCode, isDown) else false
            }
    ) {
        if (isLandscape) {
            // Landscape: PS1 Display centered full-height, DualShock floating over edges
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Ps1VideoSurface(
                    state = state,
                    aspectRatioMode = settings.aspectRatio,
                    bilinear = settings.bilinearFilter,
                    modifier = Modifier.fillMaxHeight()
                )
            }

            DualShockOverlay(
                isLandscape = true,
                analogMode = settings.analogPadMode,
                opacity = settings.controllerOpacity,
                hapticsEnabled = settings.hapticsEnabled,
                onButtonChanged = onButtonChanged,
                onStickChanged = onStickChanged,
                onToggleAnalog = onToggleAnalog,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            )
        } else {
            // Portrait: Crisp console viewport on top, tactile DualShock deck below
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.46f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Ps1VideoSurface(
                        state = state,
                        aspectRatioMode = settings.aspectRatio,
                        bilinear = settings.bilinearFilter,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.54f)
                        .background(ObsidianBg)
                ) {
                    DualShockOverlay(
                        isLandscape = false,
                        analogMode = settings.analogPadMode,
                        opacity = settings.controllerOpacity,
                        hapticsEnabled = settings.hapticsEnabled,
                        onButtonChanged = onButtonChanged,
                        onStickChanged = onStickChanged,
                        onToggleAnalog = onToggleAnalog,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Top HUD + Quick Control Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.showFpsHud) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, SlateOutline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${state.fps} FPS",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PsTriangleGreen
                        )
                        Text(
                            text = state.resolutionLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = state.biosModeLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = ElectricCyan
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickHudIconButton(
                    icon = Icons.Default.FastForward,
                    contentDescription = "Avance rápido",
                    active = state.isFastForward,
                    onClick = onToggleFastForward,
                    tag = "emu_ff_button"
                )
                QuickHudIconButton(
                    icon = Icons.Default.Save,
                    contentDescription = "Guardado rápido",
                    active = false,
                    onClick = { onSaveSlot(0) },
                    tag = "emu_quicksave_button"
                )
                QuickHudIconButton(
                    icon = Icons.Default.Restore,
                    contentDescription = "Carga rápida",
                    active = false,
                    onClick = { onLoadSlot(0) },
                    tag = "emu_quickload_button"
                )
                QuickHudIconButton(
                    icon = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = "Pausar",
                    active = state.isPaused,
                    onClick = onTogglePause,
                    tag = "emu_pause_button"
                )
                QuickHudIconButton(
                    icon = Icons.Default.Menu,
                    contentDescription = "Menú de consola",
                    active = showMenuSheet,
                    onClick = {
                        if (!state.isPaused) onTogglePause()
                        showMenuSheet = true
                    },
                    tag = "emu_menu_button"
                )
            }
        }

        // Error overlay if disc failed to open
        if (state.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SlateSurfaceElevated)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Error al iniciar disco",
                        style = MaterialTheme.typography.titleLarge,
                        color = PsCircleRed
                    )
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Button(onClick = onExitEmulation) {
                        Text("Volver a Biblioteca")
                    }
                }
            }
        }

        if (showMenuSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showMenuSheet = false
                    if (state.isPaused) onTogglePause()
                },
                containerColor = SlateSurface,
                contentWindowInsets = { WindowInsets(0) }
            ) {
                ConsoleQuickMenuSheet(
                    state = state,
                    scanlines = settings.scanlines,
                    analogMode = settings.analogPadMode,
                    saveStates = saveStates,
                    onResume = {
                        showMenuSheet = false
                        if (state.isPaused) onTogglePause()
                    },
                    onSaveSlot = { slot ->
                        onSaveSlot(slot)
                    },
                    onLoadSlot = { slot ->
                        showMenuSheet = false
                        onLoadSlot(slot)
                    },
                    onToggleScanlines = onToggleScanlines,
                    onToggleAnalog = onToggleAnalog,
                    onSwapDisc = {
                        showMenuSheet = false
                        onSwapDisc()
                    },
                    onReset = {
                        showMenuSheet = false
                        onResetConsole()
                    },
                    onExit = {
                        showMenuSheet = false
                        onExitEmulation()
                    }
                )
            }
        }
    }
}

@Composable
private fun Ps1VideoSurface(
    state: EmulationUiState,
    aspectRatioMode: AspectRatioMode,
    bilinear: Boolean,
    modifier: Modifier = Modifier
) {
    val ratioModifier = when (aspectRatioMode) {
        AspectRatioMode.RATIO_4_3 -> modifier.aspectRatio(4f / 3f)
        AspectRatioMode.RATIO_16_9 -> modifier.aspectRatio(16f / 9f)
        AspectRatioMode.STRETCH -> modifier.fillMaxSize()
    }

    val tick = state.frameTick
    val bmp = state.imageBitmap

    Canvas(modifier = ratioModifier.testTag("ps1_video_canvas")) {
        // Read tick so Compose invalidates draw on every emitted frame
        if (tick >= 0 && bmp != null) {
            drawImage(
                image = bmp,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = if (bilinear) FilterQuality.Low else FilterQuality.None
            )
        } else {
            drawRect(Color.Black)
        }
    }
}

@Composable
private fun QuickHudIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) CyanContainer.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = if (active) ElectricCyan else SlateOutline.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            )
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) ElectricCyan else TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ConsoleQuickMenuSheet(
    state: EmulationUiState,
    scanlines: Boolean,
    analogMode: Boolean,
    saveStates: List<SaveStateEntity>,
    onResume: () -> Unit,
    onSaveSlot: (Int) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onToggleScanlines: () -> Unit,
    onToggleAnalog: () -> Unit,
    onSwapDisc: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = state.activeGame?.title ?: "PSX Nova",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "${state.activeGame?.serialId ?: ""}  •  PC ${state.cpuPcHex}  •  ${state.resolutionLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Continuar", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reiniciar", color = TextPrimary)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggleScanlines,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(if (scanlines) "Scanlines: ON" else "Scanlines: OFF", color = ElectricCyan)
                }
                OutlinedButton(
                    onClick = onToggleAnalog,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(if (analogMode) "DualShock: ON" else "Digital: ON", color = ElectricCyan)
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onSwapDisc,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Icon(Icons.Default.Album, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cambiar Disco (.iso / .bin)", color = TextPrimary)
            }
        }

        item {
            Text(
                text = "Estados Guardados (5 Ranuras)",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }

        items(5) { slot ->
            val existing = saveStates.firstOrNull { it.slotIndex == slot }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SlateSurfaceElevated)
                    .border(1.dp, SlateOutline, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val thumbBmp = remember(existing?.thumbnailPath, existing?.savedAt) {
                    existing?.thumbnailPath?.let { p ->
                        val f = File(p)
                        if (f.exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null
                    }
                }
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceBright),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbBmp != null) {
                        Image(
                            bitmap = thumbBmp,
                            contentDescription = "Miniatura ranura ${slot + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "#${slot + 1}",
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextSecondary
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (slot == 0) "Ranura 1 (Rápida)" else "Ranura ${slot + 1}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (existing != null) dateFmt.format(Date(existing.savedAt)) else "Vacía",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (existing != null) ElectricCyan else TextSecondary
                    )
                }

                Button(
                    onClick = { onSaveSlot(slot) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SlateSurfaceBright, contentColor = TextPrimary),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("Guardar")
                }

                if (existing != null) {
                    Button(
                        onClick = { onLoadSlot(slot) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Cargar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = PsCircleRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Salir a la Biblioteca", color = PsCircleRed)
            }
        }
    }
}
