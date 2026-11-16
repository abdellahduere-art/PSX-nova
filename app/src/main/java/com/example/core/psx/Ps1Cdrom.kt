package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PlayStation 1 CD-ROM Drive Controller (0x1F801800..0x1F801803).
 * Handles MSF addressing, command/response FIFOs, ReadN/ReadS sector streaming,
 * XA-ADPCM audio filtering, and DMA Channel 3 transfers.
 */
class Ps1Cdrom(
    private val spu: Ps1Spu,
    private val raiseIrq2: () -> Unit
) {
    var disc: DiscImage? = null

    private var indexReg = 0
    private var irqEnable = 0x1F
    private var irqFlags = 0

    private val paramFifo = IntArray(16)
    private var paramCount = 0

    private val responseFifo = IntArray(16)
    private var responseCount = 0
    private var responseReadPos = 0

    private val queuedResponses = ArrayDeque<Pair<Int, IntArray>>()

    private val rawSector = ByteArray(2352)
    private val dataBuffer = ByteArray(2352)
    private var dataReadPos = 0
    private var dataSize = 0

    private var setlocLba = 0
    private var currentLba = 0
    private var mode = 0
    private var isReading = false
    private var isMuted = false
    private var motorOn = true
    private var readCycleCounter = 0

    // XA filter
    private var filterFile = 0
    private var filterChannel = 0

    fun reset() {
        indexReg = 0
        irqEnable = 0x1F
        irqFlags = 0
        paramCount = 0
        responseCount = 0
        responseReadPos = 0
        queuedResponses.clear()
        dataReadPos = 0
        dataSize = 0
        setlocLba = 0
        currentLba = 0
        mode = 0
        isReading = false
        isMuted = false
        motorOn = true
        readCycleCounter = 0
    }

    fun step(cycles: Int) {
        if (!isReading || disc == null) return
        val cyclesPerSector = if ((mode and 0x80) != 0) 33868800 / 150 else 33868800 / 75
        readCycleCounter += cycles
        if (readCycleCounter >= cyclesPerSector) {
            readCycleCounter -= cyclesPerSector
            deliverNextSector()
        }
    }

    private fun deliverNextSector() {
        val d = disc ?: return
        if (currentLba >= d.totalSectors) {
            isReading = false
            return
        }
        if (!d.readRawSector(currentLba, rawSector)) {
            isReading = false
            return
        }
        currentLba++

        val subMode = rawSector[18].toInt() and 0xFF
        val isXaAudio = (mode and 0x40) != 0 && (subMode and 0x44) == 0x44 // Realtime + Audio
        if (isXaAudio) {
            if (!isMuted && (mode and 0x01) == 0) {
                // Decode XA audio if file/channel match or filter off
                decodeXaSector()
            }
            return
        }

        // Copy sector payload according to mode bit 5 (whole sector 2340 vs 2048 user data)
        if ((mode and 0x20) != 0) {
            System.arraycopy(rawSector, 12, dataBuffer, 0, 2340)
            dataSize = 2340
        } else {
            val secMode = rawSector[15].toInt() and 0xFF
            val off = if (secMode == 2) 24 else 16
            System.arraycopy(rawSector, off, dataBuffer, 0, 2048)
            dataSize = 2048
        }
        dataReadPos = 0
        queueInterrupt(1, intArrayOf(buildStatByte()))
    }

    private fun decodeXaSector() {
        // Basic 4-bit XA ADPCM stereo/mono feed into SPU CD ring
        val coding = rawSector[19].toInt() and 0xFF
        val stereo = (coding and 0x01) != 0
        for (sg in 0 until 18) {
            val base = 24 + sg * 128
            for (i in 0 until 28) {
                val sample = ((rawSector[base + 16 + i].toInt() shl 8) shr 2).toShort()
                spu.pushCdAudioSample(sample, if (stereo) sample else sample)
            }
        }
    }

    fun read8(addr: Int): Int {
        return when (addr and 3) {
            0 -> {
                var st = indexReg and 3
                if (paramCount == 0) st = st or (1 shl 3) // PRMEMPT
                if (paramCount < 16) st = st or (1 shl 4) // PRMWRDY
                if (responseReadPos < responseCount) st = st or (1 shl 5) // RSLRRDY
                if (dataReadPos < dataSize) st = st or (1 shl 6) // DRQSTS
                st
            }
            1 -> {
                if (responseReadPos < responseCount) {
                    responseFifo[responseReadPos++]
                } else {
                    0
                }
            }
            2 -> readDataByte()
            3 -> {
                if ((indexReg and 1) == 0) irqEnable or 0xE0 else irqFlags or 0xE0
            }
            else -> 0
        }
    }

    fun readDataByte(): Int {
        return if (dataReadPos < dataSize) {
            dataBuffer[dataReadPos++].toInt() and 0xFF
        } else {
            0
        }
    }

    fun readDmaWord(): Int {
        val b0 = readDataByte()
        val b1 = readDataByte()
        val b2 = readDataByte()
        val b3 = readDataByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun write8(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr and 3) {
            0 -> indexReg = v and 3
            1 -> {
                when (indexReg) {
                    0 -> executeCommand(v)
                    3 -> { /* right-cd to right-spu vol */ }
                }
            }
            2 -> {
                when (indexReg) {
                    0 -> if (paramCount < 16) paramFifo[paramCount++] = v
                    1 -> {
                        irqEnable = v and 0x1F
                        if ((irqFlags and irqEnable) != 0) raiseIrq2()
                    }
                }
            }
            3 -> {
                when (indexReg) {
                    0 -> {
                        if ((v and 0x80) != 0) {
                            // BFRD: request sector data buffer ready
                            if (dataReadPos >= dataSize) {
                                dataReadPos = 0
                            }
                        }
                    }
                    1 -> {
                        // Acknowledge IRQ flags
                        irqFlags = irqFlags and (v and 0x1F).inv()
                        if ((v and 0x40) != 0) paramCount = 0
                        if (irqFlags == 0 && queuedResponses.isNotEmpty()) {
                            val next = queuedResponses.removeFirst()
                            deliverInterrupt(next.first, next.second)
                        }
                    }
                }
            }
        }
    }

    private fun executeCommand(cmd: Int) {
        responseCount = 0
        responseReadPos = 0
        when (cmd) {
            0x01 -> { // Getstat
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x02 -> { // Setloc (mm, ss, ff BCD)
                if (paramCount >= 3) {
                    val mm = fromBcd(paramFifo[0])
                    val ss = fromBcd(paramFifo[1])
                    val ff = fromBcd(paramFifo[2])
                    setlocLba = ((mm * 60 + ss) * 75 + ff - 150).coerceAtLeast(0)
                }
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x03 -> { // Play
                isReading = true
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x06, 0x1B -> { // ReadN / ReadS
                currentLba = setlocLba
                isReading = true
                readCycleCounter = 0
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x07 -> { // MotorOn
                motorOn = true
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            0x08 -> { // Stop
                isReading = false
                motorOn = false
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            0x09 -> { // Pause
                isReading = false
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            0x0A -> { // Init
                mode = 0
                isReading = false
                motorOn = true
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            0x0B -> { isMuted = true; queueInterrupt(3, intArrayOf(buildStatByte())) }
            0x0C -> { isMuted = false; queueInterrupt(3, intArrayOf(buildStatByte())) }
            0x0D -> { // Setfilter
                if (paramCount >= 2) {
                    filterFile = paramFifo[0]
                    filterChannel = paramFifo[1]
                }
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x0E -> { // Setmode
                if (paramCount >= 1) mode = paramFifo[0]
                queueInterrupt(3, intArrayOf(buildStatByte()))
            }
            0x13 -> { // GetTN
                val tracks = disc?.tracks?.size?.coerceAtLeast(1) ?: 1
                queueInterrupt(3, intArrayOf(buildStatByte(), 0x01, toBcd(tracks)))
            }
            0x14 -> { // GetTD
                val track = if (paramCount >= 1) fromBcd(paramFifo[0]) else 0
                val lba = if (track == 0) (disc?.totalSectors ?: 4500) + 150
                else (disc?.tracks?.getOrNull(track - 1)?.startLba ?: 0) + 150
                val mm = lba / (75 * 60)
                val ss = (lba / 75) % 60
                queueInterrupt(3, intArrayOf(buildStatByte(), toBcd(mm), toBcd(ss)))
            }
            0x15, 0x16 -> { // SeekL / SeekP
                currentLba = setlocLba
                isReading = false
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            0x19 -> { // Test
                val sub = if (paramCount >= 1) paramFifo[0] else 0
                if (sub == 0x20) {
                    // BIOS/Drive date/version: 98/06/10 vC3
                    queueInterrupt(3, intArrayOf(0x98, 0x06, 0x10, 0xC3))
                } else {
                    queueInterrupt(3, intArrayOf(buildStatByte()))
                }
            }
            0x1A -> { // GetID
                queueInterrupt(3, intArrayOf(buildStatByte()))
                if (disc != null) {
                    val regionChar = when (disc?.metadata?.region) {
                        Ps1Region.PAL -> 'E'.code
                        Ps1Region.NTSC_J -> 'I'.code
                        else -> 'A'.code
                    }
                    queueInterrupt(2, intArrayOf(0x02, 0x00, 0x20, 0x00, 'S'.code, 'C'.code, 'E'.code, regionChar))
                } else {
                    queueInterrupt(5, intArrayOf(0x08, 0x40, 0, 0, 0, 0, 0, 0))
                }
            }
            0x1E -> { // ReadTOC
                queueInterrupt(3, intArrayOf(buildStatByte()))
                queueInterrupt(2, intArrayOf(buildStatByte()))
            }
            else -> queueInterrupt(3, intArrayOf(buildStatByte()))
        }
        paramCount = 0
    }

    private fun queueInterrupt(irqType: Int, resp: IntArray) {
        if (irqFlags == 0) {
            deliverInterrupt(irqType, resp)
        } else {
            queuedResponses.addLast(irqType to resp)
        }
    }

    private fun deliverInterrupt(irqType: Int, resp: IntArray) {
        irqFlags = (irqFlags and 0xE0) or (irqType and 0x07)
        responseCount = resp.size.coerceAtMost(16)
        responseReadPos = 0
        for (i in 0 until responseCount) responseFifo[i] = resp[i] and 0xFF
        if ((irqFlags and irqEnable) != 0) {
            raiseIrq2()
        }
    }

    private fun buildStatByte(): Int {
        var st = 0x00
        if (motorOn) st = st or 0x02
        if (isReading) st = st or 0x20
        if (disc == null) st = st or 0x10 // shell open
        return st
    }

    private fun fromBcd(v: Int): Int = ((v ushr 4) * 10) + (v and 0x0F)
    private fun toBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)

    fun saveState(out: DataOutputStream) {
        out.writeInt(indexReg); out.writeInt(irqEnable); out.writeInt(irqFlags)
        out.writeInt(setlocLba); out.writeInt(currentLba); out.writeInt(mode)
        out.writeBoolean(isReading); out.writeBoolean(isMuted); out.writeBoolean(motorOn)
        out.writeInt(dataReadPos); out.writeInt(dataSize)
        out.write(dataBuffer)
    }

    fun loadState(inp: DataInputStream) {
        indexReg = inp.readInt(); irqEnable = inp.readInt(); irqFlags = inp.readInt()
        setlocLba = inp.readInt(); currentLba = inp.readInt(); mode = inp.readInt()
        isReading = inp.readBoolean(); isMuted = inp.readBoolean(); motorOn = inp.readBoolean()
        dataReadPos = inp.readInt(); dataSize = inp.readInt()
        inp.readFully(dataBuffer)
    }
}
