package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PlayStation 1 Memory Bus, Interrupt Controller, Timers (0/1/2), and 7-Channel DMA Controller.
 */
class MemoryBus(
    val gpu: Ps1Gpu,
    val spu: Ps1Spu,
    val cdrom: Ps1Cdrom,
    val sio: Ps1Sio,
    val mdec: Ps1Mdec
) {
    val ram = ByteArray(2 * 1024 * 1024)
    val scratchpad = ByteArray(1024)
    val bios = ByteArray(512 * 1024)

    var hasExternalBios = false

    // Interrupt Controller (0x1F801070 / 0x1F801074)
    var iStat = 0
    var iMask = 0

    // DMA Controller (0x1F801080..0x1F8010FF)
    private val dmaMadr = IntArray(7)
    private val dmaBcr = IntArray(7)
    private val dmaChcr = IntArray(7)
    private var dmaDpcr = 0x07654321
    private var dmaDicr = 0

    // Timers 0, 1, 2 (0x1F801100..0x1F80112F)
    private val timerCounter = IntArray(3)
    private val timerMode = IntArray(3)
    private val timerTarget = IntArray(3)
    private var timerDiv8 = 0

    var cacheControl = 0

    fun reset() {
        ram.fill(0)
        scratchpad.fill(0)
        iStat = 0
        iMask = 0
        dmaMadr.fill(0)
        dmaBcr.fill(0)
        dmaChcr.fill(0)
        dmaDpcr = 0x07654321
        dmaDicr = 0
        timerCounter.fill(0)
        timerMode.fill(0)
        timerTarget.fill(0)
        timerDiv8 = 0
    }

    fun raiseIrq(bit: Int) {
        iStat = iStat or (1 shl bit)
    }

    fun isIrqPending(): Boolean = (iStat and iMask) != 0

    fun stepTimers(cycles: Int, inHBlank: Boolean) {
        // Timer 0 (dotclock or sysclock)
        val m0 = timerMode[0]
        if ((m0 and 0x100) == 0) {
            advanceTimer(0, cycles)
        } else {
            advanceTimer(0, cycles) // approximate dotclock
        }

        // Timer 1 (HBlank or sysclock)
        val m1 = timerMode[1]
        if ((m1 and 0x100) == 0) {
            advanceTimer(1, cycles)
        } else if (inHBlank) {
            advanceTimer(1, 1)
        }

        // Timer 2 (sysclock or sysclock/8)
        val m2 = timerMode[2]
        if ((m2 and 0x200) != 0) {
            timerDiv8 += cycles
            val ticks = timerDiv8 ushr 3
            timerDiv8 = timerDiv8 and 7
            if (ticks > 0) advanceTimer(2, ticks)
        } else {
            advanceTimer(2, cycles)
        }
    }

    private fun advanceTimer(idx: Int, delta: Int) {
        if (delta <= 0) return
        val mode = timerMode[idx]
        var cnt = timerCounter[idx] + delta
        val target = timerTarget[idx] and 0xFFFF
        val resetOnTarget = (mode and 0x0008) != 0

        if (target > 0 && timerCounter[idx] < target && cnt >= target) {
            timerMode[idx] = timerMode[idx] or (1 shl 11) // reached target
            if ((mode and 0x0010) != 0) {
                raiseIrq(4 + idx)
            }
            if (resetOnTarget) {
                cnt %= target
            }
        }
        if (cnt > 0xFFFF) {
            timerMode[idx] = timerMode[idx] or (1 shl 12) // overflow
            if ((mode and 0x0020) != 0) {
                raiseIrq(4 + idx)
            }
            cnt = cnt and 0xFFFF
        }
        timerCounter[idx] = cnt
    }

    fun read8(addr: Int): Int {
        val phys = addr and 0x1FFFFFFF
        return when {
            phys < 0x00800000 -> ram[phys and 0x1FFFFF].toInt() and 0xFF
            phys in 0x1F800000..0x1F8003FF -> scratchpad[phys and 0x3FF].toInt() and 0xFF
            phys in 0x1FC00000..0x1FC7FFFF -> bios[phys and 0x7FFFF].toInt() and 0xFF
            phys in 0x1F801040..0x1F80104F -> sio.read8(phys)
            phys in 0x1F801800..0x1F801803 -> cdrom.read8(phys)
            phys in 0x1F801000..0x1F802FFF -> {
                val word = read32(addr and 0xFFFFFFFC.toInt())
                (word ushr ((phys and 3) * 8)) and 0xFF
            }
            else -> 0xFF
        }
    }

    fun read16(addr: Int): Int {
        val phys = addr and 0x1FFFFFFF
        return when {
            phys < 0x00800000 -> {
                val p = phys and 0x1FFFFE
                (ram[p].toInt() and 0xFF) or ((ram[p + 1].toInt() and 0xFF) shl 8)
            }
            phys in 0x1F800000..0x1F8003FF -> {
                val p = phys and 0x3FE
                (scratchpad[p].toInt() and 0xFF) or ((scratchpad[p + 1].toInt() and 0xFF) shl 8)
            }
            phys in 0x1FC00000..0x1FC7FFFF -> {
                val p = phys and 0x7FFFE
                (bios[p].toInt() and 0xFF) or ((bios[p + 1].toInt() and 0xFF) shl 8)
            }
            phys in 0x1F801040..0x1F80104F -> sio.read16(phys)
            phys in 0x1F801C00..0x1F801FFF -> spu.read16(phys)
            phys in 0x1F801000..0x1F802FFF -> {
                val word = read32(addr and 0xFFFFFFFC.toInt())
                (word ushr ((phys and 2) * 8)) and 0xFFFF
            }
            else -> 0
        }
    }

    fun read32(addr: Int): Int {
        val phys = addr and 0x1FFFFFFF
        return when {
            phys < 0x00800000 -> {
                val p = phys and 0x1FFFFC
                (ram[p].toInt() and 0xFF) or
                    ((ram[p + 1].toInt() and 0xFF) shl 8) or
                    ((ram[p + 2].toInt() and 0xFF) shl 16) or
                    ((ram[p + 3].toInt() and 0xFF) shl 24)
            }
            phys in 0x1F800000..0x1F8003FF -> {
                val p = phys and 0x3FC
                (scratchpad[p].toInt() and 0xFF) or
                    ((scratchpad[p + 1].toInt() and 0xFF) shl 8) or
                    ((scratchpad[p + 2].toInt() and 0xFF) shl 16) or
                    ((scratchpad[p + 3].toInt() and 0xFF) shl 24)
            }
            phys in 0x1FC00000..0x1FC7FFFF -> {
                val p = phys and 0x7FFFC
                (bios[p].toInt() and 0xFF) or
                    ((bios[p + 1].toInt() and 0xFF) shl 8) or
                    ((bios[p + 2].toInt() and 0xFF) shl 16) or
                    ((bios[p + 3].toInt() and 0xFF) shl 24)
            }
            phys in 0x1F801040..0x1F80104F -> sio.read16(phys)
            phys == 0x1F801070 -> iStat
            phys == 0x1F801074 -> iMask
            phys in 0x1F801080..0x1F8010EC -> readDma(phys)
            phys == 0x1F8010F0 -> dmaDpcr
            phys == 0x1F8010F4 -> dmaDicr
            phys in 0x1F801100..0x1F80112F -> readTimer(phys)
            phys == 0x1F801810 -> gpu.readData()
            phys == 0x1F801814 -> gpu.readStat()
            phys == 0x1F801820 -> mdec.readData()
            phys == 0x1F801824 -> mdec.readStatus()
            phys in 0x1F801C00..0x1F801FFF -> spu.read16(phys) or (spu.read16(phys + 2) shl 16)
            addr == 0xFFFE0130.toInt() -> cacheControl
            else -> 0
        }
    }

    fun write8(addr: Int, value: Int) {
        val phys = addr and 0x1FFFFFFF
        val b = value.toByte()
        when {
            phys < 0x00800000 -> ram[phys and 0x1FFFFF] = b
            phys in 0x1F800000..0x1F8003FF -> scratchpad[phys and 0x3FF] = b
            phys in 0x1F801040..0x1F80104F -> sio.write8(phys, value)
            phys in 0x1F801800..0x1F801803 -> cdrom.write8(phys, value)
            phys in 0x1F801000..0x1F802FFF -> {
                val aligned = addr and 0xFFFFFFFC.toInt()
                val shift = (phys and 3) * 8
                val cur = read32(aligned) and (0xFF shl shift).inv()
                write32(aligned, cur or ((value and 0xFF) shl shift))
            }
        }
    }

    fun write16(addr: Int, value: Int) {
        val phys = addr and 0x1FFFFFFF
        when {
            phys < 0x00800000 -> {
                val p = phys and 0x1FFFFE
                ram[p] = (value and 0xFF).toByte()
                ram[p + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            phys in 0x1F800000..0x1F8003FF -> {
                val p = phys and 0x3FE
                scratchpad[p] = (value and 0xFF).toByte()
                scratchpad[p + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            phys in 0x1F801040..0x1F80104F -> sio.write16(phys, value)
            phys == 0x1F801070 -> iStat = iStat and (value and 0x7FF)
            phys == 0x1F801074 -> iMask = value and 0x7FF
            phys in 0x1F801100..0x1F80112F -> writeTimer(phys, value and 0xFFFF)
            phys in 0x1F801C00..0x1F801FFF -> spu.write16(phys, value)
        }
    }

    fun write32(addr: Int, value: Int) {
        val phys = addr and 0x1FFFFFFF
        when {
            phys < 0x00800000 -> {
                val p = phys and 0x1FFFFC
                ram[p] = (value and 0xFF).toByte()
                ram[p + 1] = ((value ushr 8) and 0xFF).toByte()
                ram[p + 2] = ((value ushr 16) and 0xFF).toByte()
                ram[p + 3] = ((value ushr 24) and 0xFF).toByte()
            }
            phys in 0x1F800000..0x1F8003FF -> {
                val p = phys and 0x3FC
                scratchpad[p] = (value and 0xFF).toByte()
                scratchpad[p + 1] = ((value ushr 8) and 0xFF).toByte()
                scratchpad[p + 2] = ((value ushr 16) and 0xFF).toByte()
                scratchpad[p + 3] = ((value ushr 24) and 0xFF).toByte()
            }
            phys in 0x1F801040..0x1F80104F -> sio.write16(phys, value)
            phys == 0x1F801070 -> iStat = iStat and (value and 0x7FF)
            phys == 0x1F801074 -> iMask = value and 0x7FF
            phys in 0x1F801080..0x1F8010EC -> writeDma(phys, value)
            phys == 0x1F8010F0 -> dmaDpcr = value
            phys == 0x1F8010F4 -> {
                val ack = (value ushr 24) and 0x7F
                dmaDicr = (dmaDicr and 0x7F000000.toInt()) or (value and 0x00FFFFFF)
                dmaDicr = dmaDicr and (ack shl 24).inv()
                updateDmaMasterIrq()
            }
            phys in 0x1F801100..0x1F80112F -> writeTimer(phys, value)
            phys == 0x1F801810 -> gpu.writeGp0(value)
            phys == 0x1F801814 -> gpu.writeGp1(value)
            phys == 0x1F801820 -> mdec.writeCommand(value)
            phys == 0x1F801824 -> mdec.writeControl(value)
            phys in 0x1F801C00..0x1F801FFF -> {
                spu.write16(phys, value and 0xFFFF)
                spu.write16(phys + 2, (value ushr 16) and 0xFFFF)
            }
            addr == 0xFFFE0130.toInt() -> cacheControl = value
        }
    }

    private fun readTimer(phys: Int): Int {
        val idx = ((phys - 0x1F801100) ushr 4).coerceIn(0, 2)
        return when (phys and 0xF) {
            0 -> timerCounter[idx] and 0xFFFF
            4 -> {
                val m = timerMode[idx]
                timerMode[idx] = m and 0xE7FF // clear reached flags on read
                m
            }
            8 -> timerTarget[idx] and 0xFFFF
            else -> 0
        }
    }

    private fun writeTimer(phys: Int, value: Int) {
        val idx = ((phys - 0x1F801100) ushr 4).coerceIn(0, 2)
        when (phys and 0xF) {
            0 -> timerCounter[idx] = value and 0xFFFF
            4 -> {
                timerMode[idx] = (value and 0x3FF) or (1 shl 10)
                timerCounter[idx] = 0
            }
            8 -> timerTarget[idx] = value and 0xFFFF
        }
    }

    private fun readDma(phys: Int): Int {
        val ch = ((phys - 0x1F801080) ushr 4).coerceIn(0, 6)
        return when (phys and 0xF) {
            0 -> dmaMadr[ch]
            4 -> dmaBcr[ch]
            8 -> dmaChcr[ch]
            else -> 0
        }
    }

    private fun writeDma(phys: Int, value: Int) {
        val ch = ((phys - 0x1F801080) ushr 4).coerceIn(0, 6)
        when (phys and 0xF) {
            0 -> dmaMadr[ch] = value and 0xFFFFFF
            4 -> dmaBcr[ch] = value
            8 -> {
                dmaChcr[ch] = value
                val enabled = (value and (1 shl 24)) != 0
                val trigger = (value and (1 shl 28)) != 0
                val syncMode = (value ushr 9) and 3
                if (enabled && (syncMode != 0 || trigger)) {
                    executeDma(ch)
                }
            }
        }
    }

    private fun executeDma(ch: Int) {
        val chcr = dmaChcr[ch]
        val syncMode = (chcr ushr 9) and 3
        val step = if ((chcr and (1 shl 1)) != 0) -4 else 4
        val toRam = (chcr and 1) == 0
        var addr = dmaMadr[ch] and 0x1FFFFC

        if (syncMode == 2 && ch == 2) {
            // GPU Linked-list DMA
            var safety = 0
            while (safety++ < 65536) {
                val header = readRam32(addr)
                val wordCount = header ushr 24
                for (i in 1..wordCount) {
                    val cmd = readRam32((addr + i * 4) and 0x1FFFFC)
                    gpu.writeGp0(cmd)
                }
                val next = header and 0xFFFFFF
                if (next == 0xFFFFFF || (next and 0x800000) != 0) break
                addr = next and 0x1FFFFC
            }
        } else {
            val words = if (syncMode == 0) {
                val bc = dmaBcr[ch] and 0xFFFF
                if (bc == 0) 0x10000 else bc
            } else {
                val bs = (dmaBcr[ch] and 0xFFFF).coerceAtLeast(1)
                val ba = ((dmaBcr[ch] ushr 16) and 0xFFFF).coerceAtLeast(1)
                bs * ba
            }

            if (ch == 6) {
                // OTC (Ordering Table Clear)
                var cur = addr
                for (i in 0 until words) {
                    val valToWrite = if (i == words - 1) 0xFFFFFF else ((cur - 4) and 0x1FFFFF)
                    writeRam32(cur, valToWrite)
                    cur = (cur - 4) and 0x1FFFFC
                }
            } else {
                for (i in 0 until words) {
                    when (ch) {
                        0 -> mdec.writeCommand(readRam32(addr))
                        1 -> writeRam32(addr, mdec.readData())
                        2 -> {
                            if (toRam) writeRam32(addr, gpu.readData())
                            else gpu.writeGp0(readRam32(addr))
                        }
                        3 -> {
                            if (toRam) writeRam32(addr, cdrom.readDmaWord())
                        }
                        4 -> {
                            if (toRam) writeRam32(addr, spu.readDmaWord())
                            else spu.writeDmaWord(readRam32(addr))
                        }
                    }
                    addr = (addr + step) and 0x1FFFFC
                }
            }
        }

        dmaChcr[ch] = chcr and ((1 shl 24) or (1 shl 28)).inv()
        val chMask = 1 shl (16 + ch)
        if ((dmaDicr and chMask) != 0) {
            dmaDicr = dmaDicr or (1 shl (24 + ch))
            if (updateDmaMasterIrq()) {
                raiseIrq(3)
            }
        }
    }

    private fun updateDmaMasterIrq(): Boolean {
        val irqEnable = (dmaDicr ushr 23) and 1
        val flags = (dmaDicr ushr 24) and 0x7F
        val enables = (dmaDicr ushr 16) and 0x7F
        val force = (dmaDicr ushr 15) and 1
        val masterFlag = force != 0 || (irqEnable != 0 && (flags and enables) != 0)
        dmaDicr = if (masterFlag) (dmaDicr or (1 shl 31)) else (dmaDicr and (1 shl 31).inv())
        return masterFlag
    }

    fun readRam32(physAddr: Int): Int {
        val p = physAddr and 0x1FFFFC
        return (ram[p].toInt() and 0xFF) or
            ((ram[p + 1].toInt() and 0xFF) shl 8) or
            ((ram[p + 2].toInt() and 0xFF) shl 16) or
            ((ram[p + 3].toInt() and 0xFF) shl 24)
    }

    fun writeRam32(physAddr: Int, value: Int) {
        val p = physAddr and 0x1FFFFC
        ram[p] = (value and 0xFF).toByte()
        ram[p + 1] = ((value ushr 8) and 0xFF).toByte()
        ram[p + 2] = ((value ushr 16) and 0xFF).toByte()
        ram[p + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(iStat); out.writeInt(iMask)
        out.writeInt(dmaDpcr); out.writeInt(dmaDicr)
        for (i in 0 until 7) {
            out.writeInt(dmaMadr[i]); out.writeInt(dmaBcr[i]); out.writeInt(dmaChcr[i])
        }
        for (i in 0 until 3) {
            out.writeInt(timerCounter[i]); out.writeInt(timerMode[i]); out.writeInt(timerTarget[i])
        }
        out.write(ram)
        out.write(scratchpad)
    }

    fun loadState(inp: DataInputStream) {
        iStat = inp.readInt(); iMask = inp.readInt()
        dmaDpcr = inp.readInt(); dmaDicr = inp.readInt()
        for (i in 0 until 7) {
            dmaMadr[i] = inp.readInt(); dmaBcr[i] = inp.readInt(); dmaChcr[i] = inp.readInt()
        }
        for (i in 0 until 3) {
            timerCounter[i] = inp.readInt(); timerMode[i] = inp.readInt(); timerTarget[i] = inp.readInt()
        }
        inp.readFully(ram)
        inp.readFully(scratchpad)
    }
}
