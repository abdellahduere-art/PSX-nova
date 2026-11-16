package com.example

import com.example.core.psx.CueSheetParser
import com.example.core.psx.DiscFormat
import com.example.core.psx.DiscImage
import com.example.core.psx.Ps1Button
import com.example.core.psx.Ps1Console
import com.example.core.psx.Ps1Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Ps1CoreTest {

    @Test
    fun cueSheetParserExtractsTracksAndBinFilename() {
        val cue = """
            FILE "Crash Bandicoot (USA).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 42:15:30
        """.trimIndent()

        val binName = CueSheetParser.extractPrimaryBinFilename(cue)
        val tracks = CueSheetParser.parse(cue)

        assertEquals("Crash Bandicoot (USA).bin", binName)
        assertEquals(2, tracks.size)
        assertEquals("MODE2/2352", tracks[0].mode)
        assertEquals(2352, tracks[0].sectorSize)
        assertEquals(false, tracks[0].isAudio)
        assertEquals(true, tracks[1].isAudio)
    }

    @Test
    fun bootRealMipsPsxExeFromIso2048AndBin2352Images() {
        val exeBytes = buildMipsDrawTestExe()

        // 1. Test ISO (2048 bytes/sector)
        val isoBytes = buildMinimalIsoImage(exeBytes, serial = "SLUS_001.23")
        val isoDisc = DiscImage.fromBytes(isoBytes, "TestGame.iso")
        assertEquals(DiscFormat.ISO_2048, isoDisc.format)
        assertEquals("SLUS-00123", isoDisc.metadata.serialId)
        assertEquals(Ps1Region.NTSC_U, isoDisc.metadata.region)

        val consoleIso = Ps1Console()
        consoleIso.loadDisc(isoDisc)
        val frameIso = consoleIso.runFrame()
        assertEquals(320, frameIso.width)
        assertEquals(240, frameIso.height)
        // Verify pixel (40, 40) inside our drawn 40x40 cyan rectangle (0x00FFFF00 -> R=0, G=255, B=255)
        val pxIso = consoleIso.framePixels[40 * 320 + 40]
        assertEquals(0xFF00FFFF.toInt(), pxIso)

        // 2. Test BIN RAW (2352 bytes/sector Mode 2 Form 1)
        val binBytes = convertIsoToRawBin2352(isoBytes)
        val binDisc = DiscImage.fromBytes(binBytes, "TestGame.bin")
        assertEquals(DiscFormat.BIN_RAW_2352, binDisc.format)
        assertEquals("SLUS-00123", binDisc.metadata.serialId)

        val consoleBin = Ps1Console()
        consoleBin.loadDisc(binDisc)
        consoleBin.runFrame()
        val pxBin = consoleBin.framePixels[40 * 320 + 40]
        assertEquals(0xFF00FFFF.toInt(), pxBin)

        // 3. Test GTE RTPS + SaveState roundtrip
        consoleBin.cpu.gte.writeCtrl(0, 0x1000) // R11 = 1.0
        consoleBin.cpu.gte.writeCtrl(2, 0x1000) // R22 = 1.0
        consoleBin.cpu.gte.writeCtrl(4, 0x1000) // R33 = 1.0
        consoleBin.cpu.gte.writeCtrl(26, 200) // H = 200
        consoleBin.cpu.gte.writeData(0, 0x00100020) // VX0=32, VY0=16
        consoleBin.cpu.gte.writeData(1, 100) // VZ0=100
        consoleBin.cpu.gte.execute(0x00080001) // RTPS (sf=1)
        val sz3 = consoleBin.cpu.gte.readData(19)
        assertEquals(100, sz3)

        val stateFile = File.createTempFile("psx_test", ".psxstate")
        stateFile.deleteOnExit()
        consoleBin.saveState(stateFile)
        assertTrue(stateFile.length() > 100)

        consoleBin.resetConsole()
        assertTrue(consoleBin.loadState(stateFile))
        assertEquals(100, consoleBin.cpu.gte.readData(19))

        consoleIso.shutdown()
        consoleBin.shutdown()
    }

    @Test
    fun controllerAndMemoryCardWorkCorrectly() {
        val console = Ps1Console()
        console.sio.setButton(Ps1Button.CROSS, true)
        assertEquals(0, console.sio.buttonState and Ps1Button.CROSS.bitMask)
        console.sio.setButton(Ps1Button.CROSS, false)
        assertEquals(Ps1Button.CROSS.bitMask, console.sio.buttonState and Ps1Button.CROSS.bitMask)

        assertEquals('M'.code.toByte(), console.sio.memCard1[0])
        assertEquals('C'.code.toByte(), console.sio.memCard1[1])
        assertNotNull(console.sio.listMemoryCardEntries(0))
    }

    /**
     * Builds a genuine 2048-byte aligned PS-X EXE containing real MIPS R3000A opcodes:
     * Writes GP0 0x60FFFF00 (cyan rectangle), XY=(20,20), WH=(40,40) to 0x1F801810, then loops.
     */
    private fun buildMipsDrawTestExe(): ByteArray {
        val codeWords = intArrayOf(
            // lui $t0, 0x1F80
            0x3C081F80,
            // ori $t0, $t0, 0x1810 (GPU GP0 port)
            0x35081810,
            // lui $t1, 0x60FF (cmd 0x60 rect, B=0xFF)
            0x3C0960FF,
            // ori $t1, $t1, 0xFF00 (G=0xFF, R=0x00) -> BGR cyan 0x00FFFF00
            0x3529FF00,
            // sw $t1, 0($t0)
            0xAD090000.toInt(),
            // lui $t1, 0x0014 (Y=20)
            0x3C090014,
            // ori $t1, $t1, 0x0014 (X=20)
            0x35290014,
            // sw $t1, 0($t0)
            0xAD090000.toInt(),
            // lui $t1, 0x0028 (H=40)
            0x3C090028,
            // ori $t1, $t1, 0x0028 (W=40)
            0x35290028,
            // sw $t1, 0($t0)
            0xAD090000.toInt(),
            // loop: b loop
            0x1000FFFF,
            // nop
            0x00000000
        )

        val payloadSize = 2048
        val exe = ByteArray(2048 + payloadSize)
        val header = ByteBuffer.wrap(exe).order(ByteOrder.LITTLE_ENDIAN)
        "PS-X EXE".toByteArray(Charsets.US_ASCII).copyInto(exe, 0)
        header.putInt(0x10, 0x80010000.toInt()) // pc0
        header.putInt(0x14, 0x80010000.toInt()) // gp0
        header.putInt(0x18, 0x80010000.toInt()) // t_addr
        header.putInt(0x1C, payloadSize) // t_size
        header.putInt(0x30, 0x801FFF00.toInt()) // sp_base

        for (i in codeWords.indices) {
            header.putInt(2048 + i * 4, codeWords[i])
        }
        return exe
    }

    private fun buildMinimalIsoImage(exeBytes: ByteArray, serial: String): ByteArray {
        val exeSectors = (exeBytes.size + 2047) / 2048
        val totalSectors = 24 + exeSectors
        val iso = ByteArray(totalSectors * 2048)

        // Sector 16: Primary Volume Descriptor
        val pvdOff = 16 * 2048
        iso[pvdOff] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(iso, pvdOff + 1)
        iso[pvdOff + 6] = 1
        // Root directory record at offset 156: LBA = 18, Size = 2048
        val rootRec = ByteBuffer.wrap(iso, pvdOff + 156, 34).slice().order(ByteOrder.LITTLE_ENDIAN)
        rootRec.put(0, 34)
        rootRec.putInt(2, 18)
        rootRec.putInt(10, 2048)
        rootRec.put(25, 2) // directory
        rootRec.put(32, 1)

        // Sector 18: Root Directory entries (SYSTEM.CNF at LBA 19, serial EXE at LBA 20)
        val cnfText = "BOOT = cdrom:\\$serial;1\r\nTCB = 4\r\nEVENT = 10\r\nSTACK = 801FFF00\r\n"
            .toByteArray(Charsets.US_ASCII)
        var dirPos = 18 * 2048
        dirPos += writeDirRecord(iso, dirPos, "SYSTEM.CNF;1", lba = 19, size = cnfText.size)
        writeDirRecord(iso, dirPos, "$serial;1", lba = 20, size = exeBytes.size)

        // Sector 19: SYSTEM.CNF
        cnfText.copyInto(iso, 19 * 2048)

        // Sector 20..: PS-X EXE
        exeBytes.copyInto(iso, 20 * 2048)
        return iso
    }

    private fun writeDirRecord(buf: ByteArray, offset: Int, name: String, lba: Int, size: Int): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val recLen = ((33 + nameBytes.size + 1) and 1.inv())
        val bb = ByteBuffer.wrap(buf, offset, recLen).slice().order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0, recLen.toByte())
        bb.putInt(2, lba)
        bb.putInt(10, size)
        bb.put(25, 0) // file
        bb.put(32, nameBytes.size.toByte())
        nameBytes.copyInto(buf, offset + 33)
        return recLen
    }

    private fun convertIsoToRawBin2352(iso: ByteArray): ByteArray {
        val sectors = iso.size / 2048
        val bin = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val out = s * 2352
            bin[out] = 0x00
            for (i in 1..10) bin[out + i] = 0xFF.toByte()
            bin[out + 11] = 0x00
            bin[out + 15] = 0x02 // Mode 2
            iso.copyInto(bin, destinationOffset = out + 24, startIndex = s * 2048, endIndex = (s + 1) * 2048)
        }
        return bin
    }
}
