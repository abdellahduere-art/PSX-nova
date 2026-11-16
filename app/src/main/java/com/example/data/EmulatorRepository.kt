package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.core.psx.CueSheetParser
import com.example.core.psx.CueTrack
import com.example.core.psx.DiscImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class EmulatorRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val gameDao = db.gameDao()
    private val saveStateDao = db.saveStateDao()
    val settingsStore = SettingsStore(context)

    val memCardDir = File(context.filesDir, "memcards").apply { mkdirs() }
    val saveStateDir = File(context.filesDir, "savestates").apply { mkdirs() }
    val biosFile = File(context.filesDir, "bios/ps1_bios.bin").apply { parentFile?.mkdirs() }

    val allGames: Flow<List<GameEntity>> = gameDao.observeAllGames()

    fun observeSaveStates(gameId: Long): Flow<List<SaveStateEntity>> =
        saveStateDao.observeStatesForGame(gameId)

    suspend fun getGameById(id: Long): GameEntity? = gameDao.getGameById(id)

    suspend fun toggleFavorite(id: Long, favorite: Boolean) = gameDao.setFavorite(id, favorite)

    suspend fun markPlayed(id: Long) = gameDao.updateLastPlayed(id, System.currentTimeMillis())

    suspend fun deleteGame(id: Long) = gameDao.deleteGame(id)

    suspend fun saveStateMetadata(entity: SaveStateEntity) = saveStateDao.upsertState(entity)

    suspend fun getSaveState(gameId: Long, slot: Int): SaveStateEntity? =
        saveStateDao.getState(gameId, slot)

    suspend fun importGameUri(uri: Uri): Result<GameEntity> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = resolveDisplayName(uri)
            val lower = displayName.lowercase(Locale.US)
            if (lower.endsWith(".cue")) {
                throw IllegalArgumentException(
                    "Archivo .cue detectado. Selecciona el archivo .bin asociado o usa 'Escanear carpeta' para vincular .cue + .bin automáticamente."
                )
            }

            val existing = gameDao.getGameByUri(uri.toString())
            if (existing != null) return@runCatching existing

            val disc = DiscImage.open(context.contentResolver, uri, displayName)
            val meta = disc.use { it.metadata }

            val entity = GameEntity(
                title = meta.title,
                serialId = meta.serialId,
                region = meta.region.label,
                formatLabel = meta.format.label,
                fileSizeBytes = meta.fileSizeBytes,
                uriString = uri.toString(),
                cueUriString = null,
                lastPlayedAt = System.currentTimeMillis()
            )
            val newId = gameDao.upsertGame(entity)
            entity.copy(id = newId)
        }
    }

    suspend fun scanFolderTree(treeUri: Uri): Result<List<GameEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val docs = mutableListOf<Triple<Uri, String, Long>>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    docs.add(Triple(docUri, name, size))
                }
            }

            val cueByBinName = mutableMapOf<String, Uri>()
            for ((docUri, name, _) in docs) {
                if (name.lowercase(Locale.US).endsWith(".cue")) {
                    val text = context.contentResolver.openInputStream(docUri)?.bufferedReader()?.readText()
                    if (text != null) {
                        val binName = CueSheetParser.extractPrimaryBinFilename(text)
                        if (binName != null) {
                            cueByBinName[binName.lowercase(Locale.US)] = docUri
                        }
                    }
                }
            }

            val imported = mutableListOf<GameEntity>()
            val supportedExt = listOf(".iso", ".bin", ".img", ".mdf", ".exe", ".psx")
            for ((docUri, name, _) in docs) {
                val lower = name.lowercase(Locale.US)
                if (supportedExt.none { lower.endsWith(it) }) continue
                // Skip obvious BIOS files when scanning ROM folders
                if (lower.contains("scph") && lower.endsWith(".bin")) continue
                val existing = gameDao.getGameByUri(docUri.toString())
                if (existing != null) {
                    imported.add(existing)
                    continue
                }
                val cueUri = cueByBinName[lower]
                val tracks = if (cueUri != null) loadCueTracks(cueUri) else emptyList()
                val disc = runCatching {
                    DiscImage.open(context.contentResolver, docUri, name, tracks)
                }.getOrNull() ?: continue
                val meta = disc.use { it.metadata }
                val entity = GameEntity(
                    title = meta.title,
                    serialId = meta.serialId,
                    region = meta.region.label,
                    formatLabel = meta.format.label,
                    fileSizeBytes = meta.fileSizeBytes,
                    uriString = docUri.toString(),
                    cueUriString = cueUri?.toString()
                )
                val id = gameDao.upsertGame(entity)
                imported.add(entity.copy(id = id))
            }
            imported
        }
    }

    suspend fun openDiscForGame(game: GameEntity): DiscImage = withContext(Dispatchers.IO) {
        val uri = Uri.parse(game.uriString)
        val tracks = game.cueUriString?.let { loadCueTracks(Uri.parse(it)) } ?: emptyList()
        DiscImage.open(context.contentResolver, uri, game.title, tracks)
    }

    private fun loadCueTracks(cueUri: Uri): List<CueTrack> {
        return runCatching {
            val text = context.contentResolver.openInputStream(cueUri)?.bufferedReader()?.readText()
                ?: return emptyList()
            CueSheetParser.parse(text)
        }.getOrDefault(emptyList())
    }

    suspend fun importExternalBios(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = resolveDisplayName(uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("No se pudo leer el archivo BIOS")
            if (bytes.size < 512 * 1024) {
                throw IllegalArgumentException("El archivo BIOS debe ser de 512 KB (ej. SCPH1001.BIN)")
            }
            biosFile.parentFile?.mkdirs()
            biosFile.writeBytes(bytes.copyOfRange(0, 512 * 1024))
            settingsStore.update { it.copy(useExternalBios = true, externalBiosName = name) }
            name
        }
    }

    suspend fun clearExternalBios() = withContext(Dispatchers.IO) {
        if (biosFile.exists()) biosFile.delete()
        settingsStore.update { it.copy(useExternalBios = false, externalBiosName = "") }
    }

    fun readExternalBiosBytes(): ByteArray? {
        return if (biosFile.exists() && biosFile.length() >= 512 * 1024) {
            biosFile.readBytes()
        } else null
    }

    fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PSX_GAME.BIN"
    }
}
