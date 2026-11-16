package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.psx.DiscImage
import com.example.core.psx.MemoryCardSlotInfo
import com.example.core.psx.Ps1Button
import com.example.core.psx.Ps1Console
import com.example.core.psx.Ps1Sio
import com.example.data.EmulatorRepository
import com.example.data.EmulatorSettings
import com.example.data.GameEntity
import com.example.data.RegionMode
import com.example.data.SaveStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class LibraryFilter(val label: String) {
    ALL("Todos"),
    ISO(".ISO"),
    BIN(".BIN"),
    FAVORITES("Favoritos")
}

data class LibraryUiState(
    val games: List<GameEntity> = emptyList(),
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val isImporting: Boolean = false
)

data class EmulationUiState(
    val isActive: Boolean = false,
    val activeGame: GameEntity? = null,
    val isPaused: Boolean = false,
    val isFastForward: Boolean = false,
    val fps: Int = 0,
    val resolutionLabel: String = "320×240",
    val cpuPcHex: String = "0x80000000",
    val biosModeLabel: String = "BIOS HLE",
    val frameTick: Long = 0L,
    val imageBitmap: ImageBitmap? = null,
    val error: String? = null
)

class EmulatorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmulatorRepository(application)

    private val searchQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val isImporting = MutableStateFlow(false)

    val settings: StateFlow<EmulatorSettings> = repository.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, EmulatorSettings())

    val libraryState: StateFlow<LibraryUiState> = combine(
        repository.allGames,
        searchQuery,
        filter,
        isImporting
    ) { games, q, f, importing ->
        val filtered = games.filter { g ->
            val matchesQuery = q.isBlank() ||
                g.title.contains(q, ignoreCase = true) ||
                g.serialId.contains(q, ignoreCase = true)
            val matchesFilter = when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.ISO -> g.formatLabel.contains("ISO", ignoreCase = true)
                LibraryFilter.BIN -> g.formatLabel.contains("BIN", ignoreCase = true)
                LibraryFilter.FAVORITES -> g.isFavorite
            }
            matchesQuery && matchesFilter
        }
        LibraryUiState(
            games = filtered,
            searchQuery = q,
            filter = f,
            isImporting = importing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    private val _emulationState = MutableStateFlow(EmulationUiState())
    val emulationState: StateFlow<EmulationUiState> = _emulationState.asStateFlow()

    private val _activeSaveStates = MutableStateFlow<List<SaveStateEntity>>(emptyList())
    val activeSaveStates: StateFlow<List<SaveStateEntity>> = _activeSaveStates.asStateFlow()

    private val _memCard1 = MutableStateFlow<List<MemoryCardSlotInfo>>(emptyList())
    val memCard1: StateFlow<List<MemoryCardSlotInfo>> = _memCard1.asStateFlow()

    private val _memCard2 = MutableStateFlow<List<MemoryCardSlotInfo>>(emptyList())
    val memCard2: StateFlow<List<MemoryCardSlotInfo>> = _memCard2.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val console = Ps1Console(repository.memCardDir)
    private val emuExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PsxNovaCoreThread").apply { priority = Thread.MAX_PRIORITY }
    }
    private val loopRunning = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)

    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var useBufA = true
    private var lastResW = 0
    private var lastResH = 0

    init {
        refreshMemoryCards()
    }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    fun setFilter(f: LibraryFilter) {
        filter.value = f
    }

    fun updateSettings(transform: (EmulatorSettings) -> EmulatorSettings) {
        viewModelScope.launch {
            repository.settingsStore.update(transform)
            val s = settings.value
            console.spu.audioEnabled = s.audioEnabled
            console.sio.analogMode = s.analogPadMode
        }
    }

    fun importGameFile(uri: Uri, autoLaunch: Boolean = false, onLaunched: (Long) -> Unit = {}) {
        viewModelScope.launch {
            isImporting.value = true
            val res = repository.importGameUri(uri)
            isImporting.value = false
            res.onSuccess { game ->
                _messages.tryEmit("Juego añadido: ${game.title} (${game.formatLabel})")
                if (autoLaunch) {
                    onLaunched(game.id)
                }
            }.onFailure { err ->
                _messages.tryEmit(err.message ?: "Error al cargar el archivo")
            }
        }
    }

    fun scanFolder(treeUri: Uri) {
        viewModelScope.launch {
            isImporting.value = true
            val res = repository.scanFolderTree(treeUri)
            isImporting.value = false
            res.onSuccess { list ->
                if (list.isEmpty()) {
                    _messages.tryEmit("No se encontraron archivos .iso o .bin compatibles en la carpeta")
                } else {
                    _messages.tryEmit("Se importaron ${list.size} juego(s) a tu biblioteca")
                }
            }.onFailure { err ->
                _messages.tryEmit(err.message ?: "Error al escanear la carpeta")
            }
        }
    }

    fun toggleFavorite(game: GameEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(game.id, !game.isFavorite)
        }
    }

    fun deleteGame(game: GameEntity) {
        viewModelScope.launch {
            repository.deleteGame(game.id)
            _messages.tryEmit("Eliminado de la biblioteca: ${game.title}")
        }
    }

    fun importExternalBios(uri: Uri) {
        viewModelScope.launch {
            val res = repository.importExternalBios(uri)
            res.onSuccess { name ->
                _messages.tryEmit("BIOS cargada correctamente: $name")
            }.onFailure { err ->
                _messages.tryEmit(err.message ?: "Error al cargar la BIOS")
            }
        }
    }

    fun clearExternalBios() {
        viewModelScope.launch {
            repository.clearExternalBios()
            _messages.tryEmit("BIOS externa eliminada. Modo HLE activo.")
        }
    }

    fun refreshMemoryCards() {
        viewModelScope.launch(Dispatchers.IO) {
            val tempSio = Ps1Sio(repository.memCardDir) {}
            _memCard1.value = tempSio.listMemoryCardEntries(0)
            _memCard2.value = tempSio.listMemoryCardEntries(1)
        }
    }

    fun formatMemoryCard(slot: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = if (slot == 0) console.sio.memCard1 else console.sio.memCard2
            console.sio.formatMemoryCard(target)
            console.sio.saveMemoryCards()
            refreshMemoryCards()
            _messages.tryEmit("Memory Card Ranura ${slot + 1} formateada")
        }
    }

    fun launchGameById(gameId: Long) {
        viewModelScope.launch {
            val game = repository.getGameById(gameId)
            if (game == null) {
                _emulationState.value = EmulationUiState(error = "Juego no encontrado")
                return@launch
            }
            repository.markPlayed(gameId)
            observeSaveStates(gameId)
            startEmulationSession(game)
        }
    }

    private fun observeSaveStates(gameId: Long) {
        viewModelScope.launch {
            repository.observeSaveStates(gameId).collect { list ->
                _activeSaveStates.value = list
            }
        }
    }

    private fun startEmulationSession(game: GameEntity) {
        stopEmulationLoop()
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.settingsStore.settings.first()
            val discResult = runCatching { repository.openDiscForGame(game) }
            val disc = discResult.getOrElse { err ->
                _emulationState.value = EmulationUiState(
                    isActive = true,
                    activeGame = game,
                    error = "No se pudo abrir la imagen: ${err.message}"
                )
                return@launch
            }

            val extBios = if (curSettings.useExternalBios) repository.readExternalBiosBytes() else null
            console.setExternalBios(extBios)
            console.spu.audioEnabled = curSettings.audioEnabled
            console.sio.analogMode = curSettings.analogPadMode
            console.gpu.isPalMode = when (curSettings.regionMode) {
                RegionMode.PAL -> true
                RegionMode.NTSC -> false
                RegionMode.AUTO -> disc.metadata.region.isPal
            }

            val useExt = extBios != null && curSettings.useExternalBios
            console.loadDisc(disc, forceExternalBios = useExt)
            if (curSettings.audioEnabled) console.startAudio()

            pausedFlag.set(false)
            _emulationState.value = EmulationUiState(
                isActive = true,
                activeGame = game,
                isPaused = false,
                isFastForward = false,
                biosModeLabel = if (useExt) "BIOS LLE" else "BIOS HLE",
                error = null
            )
            startEmulationLoop()
        }
    }

    fun swapDiscUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = repository.resolveDisplayName(uri)
            runCatching {
                DiscImage.open(getApplication<Application>().contentResolver, uri, name)
            }.onSuccess { newDisc ->
                console.swapDisc(newDisc)
                _messages.tryEmit("Disco cambiado a: $name")
            }.onFailure { err ->
                _messages.tryEmit("No se pudo cambiar el disco: ${err.message}")
            }
        }
    }

    private fun startEmulationLoop() {
        if (!loopRunning.compareAndSet(false, true)) return
        emuExecutor.execute {
            var frameCount = 0
            var fpsTimer = System.nanoTime()
            var currentFps = 60
            var tick = 0L

            while (loopRunning.get()) {
                if (pausedFlag.get()) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                val curSettings = settings.value
                val isFast = _emulationState.value.isFastForward
                val speed = if (isFast) curSettings.fastForwardMultiplier else 1
                val frameStartNs = System.nanoTime()

                var result = console.runFrame(scanlines = curSettings.scanlines)
                for (step in 1 until speed) {
                    result = console.runFrame(scanlines = curSettings.scanlines)
                }

                val skip = curSettings.frameSkip
                if (skip == 0 || (tick % (skip + 1)) == 0L) {
                    val bmp = obtainBitmap(result.width, result.height)
                    bmp.setPixels(console.framePixels, 0, result.width, 0, 0, result.width, result.height)
                    tick++
                    frameCount++
                    val now = System.nanoTime()
                    if (now - fpsTimer >= 1_000_000_000L) {
                        currentFps = frameCount
                        frameCount = 0
                        fpsTimer = now
                    }
                    _emulationState.value = _emulationState.value.copy(
                        fps = currentFps,
                        resolutionLabel = "${result.width}×${result.height}",
                        cpuPcHex = "0x%08X".format(result.cpuPc),
                        frameTick = tick,
                        imageBitmap = bmp.asImageBitmap()
                    )
                } else {
                    tick++
                }

                if (!isFast) {
                    val targetNs = if (result.isPal) 20_000_000L else 16_666_667L
                    val elapsedNs = System.nanoTime() - frameStartNs
                    val sleepMs = (targetNs - elapsedNs) / 1_000_000L
                    if (sleepMs > 1L) {
                        try {
                            Thread.sleep(sleepMs)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun obtainBitmap(w: Int, h: Int): Bitmap {
        if (w != lastResW || h != lastResH || bufA == null || bufB == null) {
            bufA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bufB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            lastResW = w
            lastResH = h
        }
        useBufA = !useBufA
        return if (useBufA) bufA!! else bufB!!
    }

    private fun stopEmulationLoop() {
        loopRunning.set(false)
    }

    fun togglePause() {
        val next = !pausedFlag.get()
        pausedFlag.set(next)
        if (next) console.stopAudio() else if (settings.value.audioEnabled) console.startAudio()
        _emulationState.value = _emulationState.value.copy(isPaused = next)
    }

    fun setPaused(paused: Boolean) {
        pausedFlag.set(paused)
        if (paused) console.stopAudio() else if (settings.value.audioEnabled) console.startAudio()
        _emulationState.value = _emulationState.value.copy(isPaused = paused)
    }

    fun toggleFastForward() {
        val next = !_emulationState.value.isFastForward
        _emulationState.value = _emulationState.value.copy(isFastForward = next)
    }

    fun resetActiveConsole() {
        val wasPaused = pausedFlag.get()
        pausedFlag.set(true)
        emuExecutor.execute {
            val useExt = settings.value.useExternalBios
            console.resetConsole(forceExternalBios = useExt)
            if (!wasPaused) pausedFlag.set(false)
            _emulationState.value = _emulationState.value.copy(isPaused = wasPaused)
            _messages.tryEmit("Consola reiniciada")
        }
    }

    fun saveStateSlot(slot: Int) {
        val game = _emulationState.value.activeGame ?: return
        val wasPaused = pausedFlag.get()
        pausedFlag.set(true)
        viewModelScope.launch(Dispatchers.IO) {
            val stateFile = File(repository.saveStateDir, "game_${game.id}_slot_$slot.psxstate")
            val thumbFile = File(repository.saveStateDir, "game_${game.id}_slot_$slot.png")
            runCatching {
                console.saveState(stateFile)
                val bmp = if (useBufA) bufA else bufB
                if (bmp != null) {
                    FileOutputStream(thumbFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
                    }
                }
                repository.saveStateMetadata(
                    SaveStateEntity(
                        gameId = game.id,
                        slotIndex = slot,
                        statePath = stateFile.absolutePath,
                        thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null
                    )
                )
            }.onSuccess {
                _messages.tryEmit("Estado guardado en Ranura ${slot + 1}")
            }.onFailure { err ->
                _messages.tryEmit("Error al guardar estado: ${err.message}")
            }
            if (!wasPaused) pausedFlag.set(false)
        }
    }

    fun loadStateSlot(slot: Int) {
        val game = _emulationState.value.activeGame ?: return
        pausedFlag.set(true)
        viewModelScope.launch(Dispatchers.IO) {
            val entity = repository.getSaveState(game.id, slot)
            if (entity == null) {
                _messages.tryEmit("Ranura ${slot + 1} vacía")
                pausedFlag.set(false)
                return@launch
            }
            val ok = console.loadState(File(entity.statePath))
            if (ok) {
                _messages.tryEmit("Estado cargado desde Ranura ${slot + 1}")
            } else {
                _messages.tryEmit("No se pudo cargar el estado")
            }
            pausedFlag.set(false)
            _emulationState.value = _emulationState.value.copy(isPaused = false)
        }
    }

    fun stopEmulation() {
        stopEmulationLoop()
        emuExecutor.execute {
            console.shutdown()
            refreshMemoryCards()
        }
        _emulationState.value = EmulationUiState(isActive = false)
    }

    fun setControllerButton(button: Ps1Button, pressed: Boolean) {
        console.sio.setButton(button, pressed)
    }

    fun setControllerStick(isLeft: Boolean, x: Float, y: Float) {
        console.sio.setStick(isLeft, x, y)
    }

    fun handleHardwareKey(keyCode: Int, isDown: Boolean): Boolean {
        val mapped = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Ps1Button.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Ps1Button.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Ps1Button.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Ps1Button.RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> Ps1Button.CROSS
            KeyEvent.KEYCODE_BUTTON_B -> Ps1Button.CIRCLE
            KeyEvent.KEYCODE_BUTTON_X -> Ps1Button.SQUARE
            KeyEvent.KEYCODE_BUTTON_Y -> Ps1Button.TRIANGLE
            KeyEvent.KEYCODE_BUTTON_L1 -> Ps1Button.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> Ps1Button.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> Ps1Button.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> Ps1Button.R2
            KeyEvent.KEYCODE_BUTTON_START -> Ps1Button.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> Ps1Button.SELECT
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Ps1Button.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Ps1Button.R3
            else -> null
        } ?: return false
        console.sio.setButton(mapped, isDown)
        return true
    }

    fun handleHardwareMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        console.sio.setStick(isLeft = true, xNorm = lx, yNorm = ly)
        console.sio.setStick(isLeft = false, xNorm = rx, yNorm = ry)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        console.sio.setButton(Ps1Button.LEFT, hatX < -0.5f)
        console.sio.setButton(Ps1Button.RIGHT, hatX > 0.5f)
        console.sio.setButton(Ps1Button.UP, hatY < -0.5f)
        console.sio.setButton(Ps1Button.DOWN, hatY > 0.5f)
        return true
    }

    override fun onCleared() {
        super.onCleared()
        stopEmulationLoop()
        console.shutdown()
        emuExecutor.shutdownNow()
    }
}
