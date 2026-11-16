package com.example.ui.screens

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.GameEntity
import com.example.ui.LibraryFilter
import com.example.ui.LibraryUiState
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsCircleRed
import com.example.ui.theme.PsTriangleGreen
import com.example.ui.theme.PsVioletAccent
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceBright
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    columns: Int,
    biosModeLabel: String,
    onSearchChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onPickRomFile: () -> Unit,
    onScanFolder: () -> Unit,
    onPlayGame: (GameEntity) -> Unit,
    onToggleFavorite: (GameEntity) -> Unit,
    onDeleteGame: (GameEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PSX NOVA",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "ISO · BIN · CUE · IMG · PS-X  |  $biosModeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
            if (state.isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = ElectricCyan,
                    strokeWidth = 2.5.dp
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onScanFolder,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SlateSurfaceElevated)
                            .testTag("scan_folder_header_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Escanear carpeta de juegos",
                            tint = TextPrimary
                        )
                    }
                    Button(
                        onClick = onPickRomFile,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBg
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("load_rom_header_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cargar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (state.games.isEmpty() && state.searchQuery.isBlank() && state.filter == LibraryFilter.ALL) {
            EmptyLibraryHero(
                onPickRomFile = onPickRomFile,
                onScanFolder = onScanFolder,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(columns) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = onSearchChange,
                            placeholder = { Text("Buscar por título o serial (ej. SLUS)...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SlateSurface,
                                unfocusedContainerColor = SlateSurface,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = SlateOutline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_games_input")
                        )

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LibraryFilter.entries) { f ->
                                val selected = state.filter == f
                                FilterChip(
                                    selected = selected,
                                    onClick = { onFilterChange(f) },
                                    label = { Text(f.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanContainer,
                                        selectedLabelColor = ElectricCyan,
                                        containerColor = SlateSurfaceElevated,
                                        labelColor = TextSecondary
                                    ),
                                    modifier = Modifier.testTag("filter_${f.name.lowercase()}")
                                )
                            }
                        }
                    }
                }

                if (state.games.isEmpty()) {
                    item(span = { GridItemSpan(columns) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Ningún juego coincide con el filtro actual.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    items(state.games, key = { it.id }) { game ->
                        GameLibraryCard(
                            game = game,
                            sizeText = Formatter.formatShortFileSize(context, game.fileSizeBytes),
                            onPlay = { onPlayGame(game) },
                            onToggleFavorite = { onToggleFavorite(game) },
                            onDelete = { onDeleteGame(game) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryHero(
    onPickRomFile: () -> Unit,
    onScanFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateOutline, RoundedCornerShape(24.dp))
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_console_hero_1794735361483),
                        contentDescription = "Consola PS1 retro",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, SlateSurface)
                                )
                            )
                    )
                    Surface(
                        color = CyanContainer.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "NÚCLEO R3000A · GTE · GPU · SPU",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.empty_library_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.empty_library_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = onPickRomFile,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("load_rom_button")
                    ) {
                        Icon(Icons.Default.Album, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_load_rom),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onScanFolder,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("scan_folder_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = ElectricCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_scan_folder),
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameLibraryCard(
    game: GameEntity,
    sizeText: String,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateOutline.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .clickable(onClick = onPlay)
            .testTag("game_card_${game.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(CyanContainer, PsVioletAccent.copy(alpha = 0.45f))
                            )
                        )
                        .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${game.serialId}  •  ${game.region}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (game.isFavorite) PsCircleRed else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaTagBadge(game.formatLabel, PsTriangleGreen)
                    MetaTagBadge(sizeText, TextSecondary)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Quitar juego",
                            tint = TextMuted
                        )
                    }
                    Button(
                        onClick = onPlay,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBg
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("play_game_button_${game.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Jugar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaTagBadge(text: String, color: Color) {
    Surface(
        color = SlateSurfaceBright,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
