package com.example.core.psx

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.util.Locale

enum class DiscFormat(val label: String, val sectorSize: Int) {
    ISO_2048("ISO (2048 B)", 2048),
    BIN_RAW_2352("BIN RAW (2352 B)", 2352),
    BIN_MODE2_2336("BIN M2 (2336 B)", 2336),
    BIN_RAW_2448("BIN+SUB (2448 B)", 2448),
    PSX_EXE("PS-X EXE", 2048)
}

enum class Ps1Region(val label: String, val isPal: Boolean) {
    NTSC_U("NTSC-U (USA)", false),
    PAL("PAL (EUR)", true),
    NTSC_J("NTSC-J (JPN)", false),
    HOMEBREW("HOMEBREW", false)
}

data class CueTrack(
    val number: Int,
    val mode: String,
    val isAudio: Boolean,
    val sectorSize: Int,
    val startLba: Int,
    val fileName: String? = null
)

data class IsoFileEntry(
    val name: String,
    val lba: Int,
    val size: Int,
    val isDirectory: Boolean
)

data class DiscMetadata(
    val title: String,
    val serialId: String,
    val region: Ps1Region,
    val format: DiscFormat,
    val bootPath: String?,
    val fileSizeBytes: Long,
    val totalSectors: Int,
    val systemCnfRaw: String? = null
)

data class PsxExeHeader(
    val pc0: Int,
    val gp0: Int,
    val tAddr: Int,
    val tSize: Int,
    val sAddr: Int,
    val sSize: Int,
    val spBase: Int,
    val spOffset: Int,
    val payload: ByteArray
)

interface SectorSource : Closeable {
    val sizeBytes: Long
    fun readAt(offset: Long, dst: ByteArray, dstOffset: Int = 0, length: Int = dst.size - dstOffset): Int
}

class ByteArraySectorSource(private val data: ByteArray) : SectorSource {
    override val sizeBytes: Long get() = data.size.toLong()

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= data.size) return 0
        val avail = (data.size - offset.toInt()).coerceAtMost(length)
        System.arraycopy(data, offset.toInt(), dst, dstOffset, avail)
        return avail
    }

    override fun close() = Unit
}

class ChannelSectorSource(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
    private val channel: FileChannel,
    override val sizeBytes: Long
) : SectorSource {
    private val lock = Any()

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= sizeBytes) return 0
        val toRead = length.toLong().coerceAtMost(sizeBytes - offset).toInt()
        if (toRead <= 0) return 0
        val buf = ByteBuffer.wrap(dst, dstOffset, toRead)
        synchronized(lock) {
            var pos = offset
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n <= 0) break
                pos += n
            }
        }
        return toRead - buf.remaining()
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { fis.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(contentResolver: ContentResolver, uri: Uri): SectorSource {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("No se pudo abrir el descriptor del archivo: $uri")
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            val size = runCatching { channel.size() }.getOrElse { pfd.statSize }
            if (size <= 0L) {
                // Fallback for non-seekable virtual providers: buffer into memory if small
                pfd.close()
                fis.close()
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("No se pudo leer el archivo seleccionado")
                return ByteArraySectorSource(bytes)
            }
            return ChannelSectorSource(pfd, fis, channel, size)
        }
    }
}

object CueSheetParser {
    private val FILE_REGEX = Regex("""FILE\s+"([^"]+)"\s+\w+""", RegexOption.IGNORE_CASE)
    private val TRACK_REGEX = Regex("""TRACK\s+(\d+)\s+([A-Z0-9/]+)""", RegexOption.IGNORE_CASE)
    private val INDEX_REGEX = Regex("""INDEX\s+01\s+(\d+):(\d+):(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(cueText: String): List<CueTrack> {
        val tracks = mutableListOf<CueTrack>()
        var currentFile: String? = null
        var pendingTrackNum = -1
        var pendingMode = "MODE2/2352"
        for (rawLine in cueText.lineSequence()) {
            val line = rawLine.trim()
            FILE_REGEX.find(line)?.let {
                currentFile = it.groupValues[1]
            }
            TRACK_REGEX.find(line)?.let {
                pendingTrackNum = it.groupValues[1].toIntOrNull() ?: 1
                pendingMode = it.groupValues[2].uppercase(Locale.US)
            }
            INDEX_REGEX.find(line)?.let { m ->
                if (pendingTrackNum > 0) {
                    val mm = m.groupValues[1].toIntOrNull() ?: 0
                    val ss = m.groupValues[2].toIntOrNull() ?: 0
                    val ff = m.groupValues[3].toIntOrNull() ?: 0
                    val lba = (mm * 60 + ss) * 75 + ff
                    val isAudio = pendingMode.contains("AUDIO")
                    val sectorSize = when {
                        pendingMode.contains("2048") -> 2048
                        pendingMode.contains("2336") -> 2336
                        pendingMode.contains("2448") -> 2448
                        else -> 2352
                    }
                    tracks.add(
                        CueTrack(
                            number = pendingTrackNum,
                            mode = pendingMode,
                            isAudio = isAudio,
                            sectorSize = sectorSize,
                            startLba = lba,
                            fileName = currentFile
                        )
                    )
                    pendingTrackNum = -1
                }
            }
        }
        return tracks
    }

    fun extractPrimaryBinFilename(cueText: String): String? {
        return FILE_REGEX.find(cueText)?.groupValues?.getOrNull(1)
    }
}

class DiscImage(
    private val source: SectorSource,
    val format: DiscFormat,
    val tracks: List<CueTrack> = emptyList(),
    val fallbackFileName: String = "PSX_GAME"
) : Closeable {

    val totalSectors: Int = if (format == DiscFormat.PSX_EXE) 1 else (source.sizeBytes / format.sectorSize).toInt().coerceAtLeast(1)

    val metadata: DiscMetadata by lazy { parseMetadata() }

    private val rawScratch = ByteArray(2448)

    fun isPsxExe(): Boolean = format == DiscFormat.PSX_EXE

    fun readPsxExe(): PsxExeHeader? {
        if (format != DiscFormat.PSX_EXE) return null
        val len = source.sizeBytes.toInt().coerceAtMost(8 * 1024 * 1024)
        val bytes = ByteArray(len)
        source.readAt(0L, bytes, 0, len)
        return parsePsxExe(bytes)
    }

    /**
     * Reads 2048 bytes of user data for logical sector LBA (ISO-9660 / Mode 1 / Mode 2 Form 1).
     */
    @Synchronized
    fun readUserSector(lba: Int, dest: ByteArray, destOffset: Int = 0): Boolean {
        if (lba < 0 || lba >= totalSectors || format == DiscFormat.PSX_EXE) return false
        val sectorOffset = lba.toLong() * format.sectorSize
        return when (format) {
            DiscFormat.ISO_2048 -> {
                source.readAt(sectorOffset, dest, destOffset, 2048) == 2048
            }
            DiscFormat.BIN_MODE2_2336 -> {
                // 8-byte subheader followed by 2048-byte user data
                source.readAt(sectorOffset + 8L, dest, destOffset, 2048) == 2048
            }
            DiscFormat.BIN_RAW_2352, DiscFormat.BIN_RAW_2448 -> {
                val read = source.readAt(sectorOffset, rawScratch, 0, 2352)
                if (read < 2064) return false
                val mode = rawScratch[15].toInt() and 0xFF
                val dataOffset = if (mode == 2) 24 else 16
                System.arraycopy(rawScratch, dataOffset, dest, destOffset, 2048)
                true
            }
            DiscFormat.PSX_EXE -> false
        }
    }

    /**
     * Reads a full 2352-byte raw CD sector (synthesizing sync/header if ISO-2048).
     */
    @Synchronized
    fun readRawSector(lba: Int, dest: ByteArray): Boolean {
        if (dest.size < 2352 || lba < 0 || lba >= totalSectors) return false
        val sectorOffset = lba.toLong() * format.sectorSize
        return when (format) {
            DiscFormat.BIN_RAW_2352, DiscFormat.BIN_RAW_2448 -> {
                source.readAt(sectorOffset, dest, 0, 2352) == 2352
            }
            DiscFormat.BIN_MODE2_2336 -> {
                fillSyncHeader(dest, lba + 150, mode = 2)
                source.readAt(sectorOffset, dest, 16, 2336) == 2336
            }
            DiscFormat.ISO_2048 -> {
                fillSyncHeader(dest, lba + 150, mode = 2)
                // Mode 2 Form 1 subheader (8 bytes)
                dest[16] = 0; dest[17] = 0; dest[18] = 0x08; dest[19] = 0
                dest[20] = 0; dest[21] = 0; dest[22] = 0x08; dest[23] = 0
                val n = source.readAt(sectorOffset, dest, 24, 2048)
                n > 0
            }
            DiscFormat.PSX_EXE -> false
        }
    }

    private fun fillSyncHeader(dest: ByteArray, absFrame: Int, mode: Int) {
        dest.fill(0, 0, 2352)
        dest[0] = 0x00
        for (i in 1..10) dest[i] = 0xFF.toByte()
        dest[11] = 0x00
        val mm = absFrame / (75 * 60)
        val ss = (absFrame / 75) % 60
        val ff = absFrame % 75
        dest[12] = toBcd(mm).toByte()
        dest[13] = toBcd(ss).toByte()
        dest[14] = toBcd(ff).toByte()
        dest[15] = mode.toByte()
    }

    private fun toBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)

    fun listDirectory(dirLba: Int? = null, dirSize: Int? = null): List<IsoFileEntry> {
        if (format == DiscFormat.PSX_EXE) return emptyList()
        val pvd = ByteArray(2048)
        if (!readUserSector(16, pvd)) return emptyList()
        if (pvd[1].toInt() != 'C'.code || pvd[2].toInt() != 'D'.code ||
            pvd[3].toInt() != '0'.code || pvd[4].toInt() != '0'.code || pvd[5].toInt() != '1'.code
        ) {
            return emptyList()
        }

        val rootLba = dirLba ?: readLe32(pvd, 156 + 2)
        val rootSize = dirSize ?: readLe32(pvd, 156 + 10)
        val entries = mutableListOf<IsoFileEntry>()
        val sectorBuf = ByteArray(2048)
        val numSectors = ((rootSize + 2047) / 2048).coerceIn(1, 64)

        for (s in 0 until numSectors) {
            if (!readUserSector(rootLba + s, sectorBuf)) break
            var offset = 0
            while (offset < 2048) {
                val recLen = sectorBuf[offset].toInt() and 0xFF
                if (recLen == 0) break
                if (offset + recLen > 2048) break
                val extentLba = readLe32(sectorBuf, offset + 2)
                val dataLen = readLe32(sectorBuf, offset + 10)
                val flags = sectorBuf[offset + 25].toInt() and 0xFF
                val nameLen = sectorBuf[offset + 32].toInt() and 0xFF
                if (nameLen > 0 && offset + 33 + nameLen <= 2048) {
                    val rawName = String(sectorBuf, offset + 33, nameLen, Charsets.ISO_8859_1)
                    if (rawName != "\u0000" && rawName != "\u0001") {
                        val cleanName = rawName.substringBefore(';').uppercase(Locale.US)
                        entries.add(
                            IsoFileEntry(
                                name = cleanName,
                                lba = extentLba,
                                size = dataLen,
                                isDirectory = (flags and 0x02) != 0
                            )
                        )
                    }
                }
                offset += recLen
            }
        }
        return entries
    }

    fun findFile(cdromPath: String): IsoFileEntry? {
        if (format == DiscFormat.PSX_EXE) return null
        val normalized = cdromPath
            .removePrefix("cdrom:")
            .removePrefix("CDROM:")
            .replace('\\', '/')
            .trimStart('/')
            .substringBefore(';')
            .uppercase(Locale.US)
        if (normalized.isEmpty()) return null

        val parts = normalized.split('/').filter { it.isNotEmpty() }
        var currentEntries = listDirectory()
        for (i in parts.indices) {
            val part = parts[i]
            val match = currentEntries.firstOrNull { it.name == part } ?: return null
            if (i == parts.lastIndex) return match
            if (!match.isDirectory) return null
            currentEntries = listDirectory(match.lba, match.size)
        }
        return null
    }

    fun readFile(entry: IsoFileEntry, maxBytes: Int = 8 * 1024 * 1024): ByteArray? {
        if (entry.isDirectory || entry.size <= 0) return null
        val targetSize = entry.size.coerceAtMost(maxBytes)
        val out = ByteArray(targetSize)
        val sector = ByteArray(2048)
        var remaining = targetSize
        var outOffset = 0
        var lba = entry.lba
        while (remaining > 0) {
            if (!readUserSector(lba, sector)) break
            val copyLen = remaining.coerceAtMost(2048)
            System.arraycopy(sector, 0, out, outOffset, copyLen)
            outOffset += copyLen
            remaining -= copyLen
            lba++
        }
        return out
    }

    fun readFile(cdromPath: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray? {
        val entry = findFile(cdromPath) ?: return null
        return readFile(entry, maxBytes)
    }

    private fun parseMetadata(): DiscMetadata {
        if (format == DiscFormat.PSX_EXE) {
            val baseName = fallbackFileName.substringBeforeLast('.')
            return DiscMetadata(
                title = baseName.ifBlank { "PS-X Executable" },
                serialId = "HOMEBREW",
                region = Ps1Region.HOMEBREW,
                format = format,
                bootPath = fallbackFileName,
                fileSizeBytes = source.sizeBytes,
                totalSectors = 1
            )
        }

        val pvd = ByteArray(2048)
        var volumeLabel = ""
        if (readUserSector(16, pvd) &&
            pvd[1].toInt() == 'C'.code && pvd[2].toInt() == 'D'.code && pvd[3].toInt() == '0'.code
        ) {
            volumeLabel = String(pvd, 40, 32, Charsets.ISO_8859_1).trim()
        }

        val cnfBytes = readFile("SYSTEM.CNF", 8192)
        val cnfText = cnfBytes?.toString(Charsets.ISO_8859_1)
        var bootPath: String? = null
        if (cnfText != null) {
            for (line in cnfText.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("BOOT", ignoreCase = true)) {
                    bootPath = trimmed.substringAfter('=').trim()
                    break
                }
            }
        }
        if (bootPath == null) {
            // Fallback to PSX.EXE if present
            if (findFile("PSX.EXE") != null) {
                bootPath = "cdrom:\\PSX.EXE;1"
            }
        }

        val serialFromBoot = bootPath
            ?.substringAfterLast('\\')
            ?.substringAfterLast('/')
            ?.substringBefore(';')
            ?.replace("_", "-")
            ?.replace(".", "")
            ?.uppercase(Locale.US)
            ?: ""

        val formattedSerial = if (serialFromBoot.length >= 9 && serialFromBoot[4] == '-') {
            // e.g. SLUS-00707
            serialFromBoot.take(10)
        } else if (serialFromBoot.isNotEmpty()) {
            serialFromBoot
        } else {
            "PSX-DISC"
        }

        val region = when {
            formattedSerial.startsWith("SLUS") || formattedSerial.startsWith("SCUS") || formattedSerial.startsWith("LSP") -> Ps1Region.NTSC_U
            formattedSerial.startsWith("SLES") || formattedSerial.startsWith("SCES") || formattedSerial.startsWith("SLED") -> Ps1Region.PAL
            formattedSerial.startsWith("SLPM") || formattedSerial.startsWith("SLPS") || formattedSerial.startsWith("SCPS") -> Ps1Region.NTSC_J
            else -> Ps1Region.NTSC_U
        }

        val cleanFileTitle = fallbackFileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
        val displayTitle = when {
            cleanFileTitle.isNotBlank() && !cleanFileTitle.equals("document", ignoreCase = true) -> cleanFileTitle
            volumeLabel.isNotBlank() -> volumeLabel
            else -> formattedSerial
        }

        return DiscMetadata(
            title = displayTitle,
            serialId = formattedSerial,
            region = region,
            format = format,
            bootPath = bootPath,
            fileSizeBytes = source.sizeBytes,
            totalSectors = totalSectors,
            systemCnfRaw = cnfText
        )
    }

    private fun readLe32(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        private val SYNC_PATTERN = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00
        )

        fun open(
            contentResolver: ContentResolver,
            uri: Uri,
            fileName: String,
            tracks: List<CueTrack> = emptyList()
        ): DiscImage {
            val source = ChannelSectorSource.open(contentResolver, uri)
            val format = detectFormat(source, fileName)
            return DiscImage(source, format, tracks, fileName)
        }

        fun fromBytes(
            bytes: ByteArray,
            fileName: String = "TEST.BIN",
            tracks: List<CueTrack> = emptyList()
        ): DiscImage {
            val source = ByteArraySectorSource(bytes)
            val format = detectFormat(source, fileName)
            return DiscImage(source, format, tracks, fileName)
        }

        fun detectFormat(source: SectorSource, fileName: String = ""): DiscFormat {
            val probe = ByteArray(64)
            source.readAt(0L, probe, 0, 64)
            // 1. Check PS-X EXE magic
            if (probe.size >= 8 &&
                probe[0] == 'P'.code.toByte() && probe[1] == 'S'.code.toByte() &&
                probe[2] == '-'.code.toByte() && probe[3] == 'X'.code.toByte() &&
                probe[4] == ' '.code.toByte() && probe[5] == 'E'.code.toByte() &&
                probe[6] == 'X'.code.toByte() && probe[7] == 'E'.code.toByte()
            ) {
                return DiscFormat.PSX_EXE
            }

            // 2. Check raw sync pattern at offset 0
            if (matchesSync(probe, 0)) {
                val probe2448 = ByteArray(16)
                if (source.sizeBytes >= 2464 && source.readAt(2448L, probe2448, 0, 16) == 16 && matchesSync(probe2448, 0)) {
                    return DiscFormat.BIN_RAW_2448
                }
                return DiscFormat.BIN_RAW_2352
            }

            // 3. Check CD001 primary volume descriptor at LBA 16 for different sector strides
            if (hasCd001At(source, 16L * 2048L + 1L)) return DiscFormat.ISO_2048
            if (hasCd001At(source, 16L * 2352L + 24L + 1L) || hasCd001At(source, 16L * 2352L + 16L + 1L)) {
                return DiscFormat.BIN_RAW_2352
            }
            if (hasCd001At(source, 16L * 2336L + 8L + 1L)) return DiscFormat.BIN_MODE2_2336
            if (hasCd001At(source, 16L * 2448L + 24L + 1L)) return DiscFormat.BIN_RAW_2448

            // 4. Size divisibility & extension heuristics
            val lower = fileName.lowercase(Locale.US)
            return when {
                lower.endsWith(".iso") && source.sizeBytes % 2048L == 0L -> DiscFormat.ISO_2048
                source.sizeBytes % 2352L == 0L && (lower.endsWith(".bin") || lower.endsWith(".img")) -> DiscFormat.BIN_RAW_2352
                source.sizeBytes % 2048L == 0L -> DiscFormat.ISO_2048
                source.sizeBytes % 2352L == 0L -> DiscFormat.BIN_RAW_2352
                else -> DiscFormat.BIN_RAW_2352
            }
        }

        private fun matchesSync(buf: ByteArray, offset: Int): Boolean {
            if (offset + SYNC_PATTERN.size > buf.size) return false
            for (i in SYNC_PATTERN.indices) {
                if (buf[offset + i] != SYNC_PATTERN[i]) return false
            }
            return true
        }

        private fun hasCd001At(source: SectorSource, offset: Long): Boolean {
            if (offset + 5 > source.sizeBytes) return false
            val buf = ByteArray(5)
            if (source.readAt(offset, buf, 0, 5) < 5) return false
            return buf[0] == 'C'.code.toByte() &&
                buf[1] == 'D'.code.toByte() &&
                buf[2] == '0'.code.toByte() &&
                buf[3] == '0'.code.toByte() &&
                buf[4] == '1'.code.toByte()
        }

        fun parsePsxExe(bytes: ByteArray): PsxExeHeader? {
            if (bytes.size < 0x800) return null
            if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'S'.code.toByte() ||
                bytes[2] != '-'.code.toByte() || bytes[3] != 'X'.code.toByte()
            ) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pc0 = bb.getInt(0x10)
            val gp0 = bb.getInt(0x14)
            val tAddr = bb.getInt(0x18)
            val tSize = bb.getInt(0x1C)
            val sAddr = bb.getInt(0x28)
            val sSize = bb.getInt(0x2C)
            val spBase = bb.getInt(0x30)
            val spOffset = bb.getInt(0x34)
            val actualPayloadLen = (bytes.size - 0x800).coerceAtMost(tSize.coerceAtLeast(0))
            val payload = ByteArray(actualPayloadLen)
            System.arraycopy(bytes, 0x800, payload, 0, actualPayloadLen)
            return PsxExeHeader(pc0, gp0, tAddr, tSize, sAddr, sSize, spBase, spOffset, payload)
        }
    }

    override fun close() {
        source.close()
    }
}
