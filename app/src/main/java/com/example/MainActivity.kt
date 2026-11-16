package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.EmulatorViewModel
import com.example.ui.screens.BiosMemoryScreen
import com.example.ui.screens.EmulatorPlayScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsxNovaTheme
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextSecondary

object Routes {
    const val LIBRARY = "library"
    const val SYSTEM = "system"
    const val SETTINGS = "settings"
    const val PLAY = "play/{gameId}"
    fun play(gameId: Long) = "play/$gameId"
}

class MainActivity : ComponentActivity() {
    private val viewModel: EmulatorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            PsxNovaTheme {
                PsxNovaApp(viewModel = viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.emulationState.value.isActive) {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> viewModel.handleHardwareKey(event.keyCode, true)
                KeyEvent.ACTION_UP -> viewModel.handleHardwareKey(event.keyCode, false)
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (viewModel.emulationState.value.isActive && viewModel.handleHardwareMotion(ev)) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}

@Composable
fun PsxNovaApp(viewModel: EmulatorViewModel) {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route

    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val emuState by viewModel.emulationState.collectAsStateWithLifecycle()
    val saveStates by viewModel.activeSaveStates.collectAsStateWithLifecycle()
    val memCard1 by viewModel.memCard1.collectAsStateWithLifecycle()
    val memCard2 by viewModel.memCard2.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val romPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importGameFile(uri, autoLaunch = false)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.scanFolder(uri)
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importExternalBios(uri)
        }
    }

    val swapDiscLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.swapDiscUri(uri)
        }
    }

    val isPlayScreen = currentRoute?.startsWith("play") == true
    val biosLabel = if (settings.useExternalBios && settings.externalBiosName.isNotBlank()) {
        "BIOS: ${settings.externalBiosName}"
    } else {
        "BIOS HLE"
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val isWide = maxWidth >= 680.dp
        val gridColumns = when {
            maxWidth >= 960.dp -> 3
            maxWidth >= 600.dp -> 2
            else -> 1
        }

        Scaffold(
            containerColor = ObsidianBg,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isPlayScreen && !isWide) {
                    NavigationBar(
                        containerColor = SlateSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NavigationBarItem(
                            selected = currentRoute == Routes.LIBRARY,
                            onClick = {
                                navController.navigate(Routes.LIBRARY) {
                                    popUpTo(Routes.LIBRARY) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.LIBRARY) Icons.Filled.SportsEsports else Icons.Outlined.SportsEsports,
                                    contentDescription = stringResource(R.string.nav_library)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_library)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_library")
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.SYSTEM,
                            onClick = {
                                navController.navigate(Routes.SYSTEM) {
                                    popUpTo(Routes.LIBRARY)
                                    launchSingleTop = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.SYSTEM) Icons.Filled.Memory else Icons.Outlined.Memory,
                                    contentDescription = stringResource(R.string.nav_system)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_system)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_system")
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.SETTINGS,
                            onClick = {
                                navController.navigate(Routes.SETTINGS) {
                                    popUpTo(Routes.LIBRARY)
                                    launchSingleTop = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.nav_settings)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!isPlayScreen && isWide) {
                    NavigationRail(
                        containerColor = SlateSurface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        NavigationRailItem(
                            selected = currentRoute == Routes.LIBRARY,
                            onClick = {
                                navController.navigate(Routes.LIBRARY) {
                                    popUpTo(Routes.LIBRARY) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(Icons.Filled.SportsEsports, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_library)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute == Routes.SYSTEM,
                            onClick = {
                                navController.navigate(Routes.SYSTEM) {
                                    popUpTo(Routes.LIBRARY)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(Icons.Filled.Memory, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_system)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute == Routes.SETTINGS,
                            onClick = {
                                navController.navigate(Routes.SETTINGS) {
                                    popUpTo(Routes.LIBRARY)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = CyanContainer
                            )
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIBRARY
                    ) {
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                state = libraryState,
                                columns = gridColumns,
                                biosModeLabel = biosLabel,
                                onSearchChange = viewModel::setSearchQuery,
                                onFilterChange = viewModel::setFilter,
                                onPickRomFile = { romPickerLauncher.launch(arrayOf("*/*")) },
                                onScanFolder = { folderPickerLauncher.launch(null) },
                                onPlayGame = { game ->
                                    viewModel.launchGameById(game.id)
                                    navController.navigate(Routes.play(game.id))
                                },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onDeleteGame = viewModel::deleteGame,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                            )
                        }

                        composable(Routes.SYSTEM) {
                            BiosMemoryScreen(
                                settings = settings,
                                memCard1 = memCard1,
                                memCard2 = memCard2,
                                onPickBios = { biosPickerLauncher.launch(arrayOf("*/*")) },
                                onToggleExternalBios = { enabled ->
                                    viewModel.updateSettings { it.copy(useExternalBios = enabled) }
                                },
                                onClearBios = viewModel::clearExternalBios,
                                onFormatMemCard = viewModel::formatMemoryCard,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                            )
                        }

                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                settings = settings,
                                onUpdateSettings = viewModel::updateSettings,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                            )
                        }

                        composable(
                            route = Routes.PLAY,
                            arguments = listOf(navArgument("gameId") { type = NavType.LongType })
                        ) {
                            EmulatorPlayScreen(
                                state = emuState,
                                settings = settings,
                                saveStates = saveStates,
                                onButtonChanged = viewModel::setControllerButton,
                                onStickChanged = viewModel::setControllerStick,
                                onHardwareKey = viewModel::handleHardwareKey,
                                onTogglePause = viewModel::togglePause,
                                onToggleFastForward = viewModel::toggleFastForward,
                                onToggleAnalog = {
                                    viewModel.updateSettings { it.copy(analogPadMode = !it.analogPadMode) }
                                },
                                onToggleScanlines = {
                                    viewModel.updateSettings { it.copy(scanlines = !it.scanlines) }
                                },
                                onSaveSlot = viewModel::saveStateSlot,
                                onLoadSlot = viewModel::loadStateSlot,
                                onResetConsole = viewModel::resetActiveConsole,
                                onSwapDisc = { swapDiscLauncher.launch(arrayOf("*/*")) },
                                onExitEmulation = {
                                    viewModel.stopEmulation()
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
