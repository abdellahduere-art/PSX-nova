package com.example.core.psx

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class FrameResult(
    val width: Int,
    val height: Int,
    val cpuPc: Int,
    val isPal: Boolean
)

/**
 * Complete PlayStation 1 Console Orchestrator.
 * Coordinates R3000A CPU, COP2 GTE, GPU, SPU, CD-ROM, SIO0/MemoryCards, MDEC, Bus, and HLE/LLE BIOS.
 */
class Ps1Console(memCardDir: File? = null) {
    val gpu = Ps1Gpu()
    val spu = Ps1Spu()
    val mdec = Ps1Mdec()

    private lateinit var bus: MemoryBus
    lateinit var cdrom: Ps1Cdrom
    lateinit var sio: Ps1Sio
    lateinit var cpu: R3000ACpu
    lateinit var hleBios: HleBios

    var activeDisc: DiscImage? = null
        private set
    var isRunning = false
        private set

    // Framebuffer output (max PS1 resolution 640x480)
    val framePixels = IntArray(640 * 480)

    init {
        cdrom = Ps1Cdrom(spu) { bus.raiseIrq(2) }
        sio = Ps1Sio(memCardDir) { bus.raiseIrq(7) }
        bus = MemoryBus(gpu, spu, cdrom, sio, mdec)
        cpu = R3000ACpu(bus)
        hleBios = HleBios(cpu, bus, gpu, cdrom, sio)
    }

    fun setExternalBios(biosBytes: ByteArray?) {
        if (biosBytes != null && biosBytes.size >= 512 * 1024) {
            System.arraycopy(biosBytes, 0, bus.bios, 0, 512 * 1024)
            bus.hasExternalBios = true
            cpu.hleHandler = null
        } else {
            bus.bios.fill(0)
            bus.hasExternalBios = false
            cpu.hleHandler = { addr -> hleBios.handleVector(addr) }
        }
    }

    fun loadDisc(disc: DiscImage, forceExternalBios: Boolean = false): Boolean {
        activeDisc?.close()
        activeDisc = disc
        resetConsole(forceExternalBios)
        return true
    }

    fun swapDisc(disc: DiscImage) {
        activeDisc?.close()
        activeDisc = disc
        cdrom.disc = disc
    }

    fun resetConsole(forceExternalBios: Boolean = false) {
        bus.reset()
        gpu.reset()
        spu.reset()
        cdrom.reset()
        sio.reset()
        mdec.reset()

        val disc = activeDisc
        cdrom.disc = disc

        if (forceExternalBios && bus.hasExternalBios) {
            cpu.hleHandler = null
            cpu.reset(0xBFC00000.toInt())
        } else {
            cpu.hleHandler = { addr -> hleBios.handleVector(addr) }
            if (disc != null) {
                val booted = hleBios.bootDisc(disc)
                if (!booted && bus.hasExternalBios) {
                    cpu.hleHandler = null
                    cpu.reset(0xBFC00000.toInt())
                }
            } else {
                cpu.reset(0xBFC00000.toInt())
            }
        }
        isRunning = true
    }

    fun startAudio() = spu.startAudio()
    fun stopAudio() = spu.stopAudio()

    fun runFrame(scanlines: Boolean = false): FrameResult {
        val pal = gpu.isPalMode || (activeDisc?.metadata?.region == Ps1Region.PAL)
        val scanlineCount = if (pal) 314 else 263
        val vblankLine = if (pal) 256 else 240
        // ~33.8688 MHz / 60 Hz ≈ 564,480 cycles/frame (~2146 cycles per scanline)
        // Executed at ~1800 effective RISC ops/scanline in Kotlin for smooth mobile framerate
        val cyclesPerScanline = 1200

        for (line in 0 until scanlineCount) {
            var remaining = cyclesPerScanline
            while (remaining > 0) {
                val took = cpu.step()
                remaining -= took
            }
            bus.stepTimers(cyclesPerScanline, inHBlank = true)
            cdrom.step(cyclesPerScanline)

            if (line == vblankLine) {
                bus.raiseIrq(0) // VBlank IRQ
                if (cpu.hleHandler != null) {
                    hleBios.onVBlank()
                }
            }
        }

        gpu.toggleField()
        val w = gpu.displayHRes.coerceIn(256, 640)
        val h = gpu.displayVRes.coerceIn(240, 480)
        gpu.renderFrame(framePixels, scanlines)
        spu.mixAndOutputFrame(pal)

        return FrameResult(
            width = w,
            height = h,
            cpuPc = cpu.pc,
            isPal = pal
        )
    }

    fun saveState(file: File) {
        file.parentFile?.mkdirs()
        DataOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(file)))).use { out ->
            out.writeInt(STATE_MAGIC)
            out.writeInt(STATE_VERSION)
            cpu.saveState(out)
            bus.saveState(out)
            gpu.saveState(out)
            spu.saveState(out)
            cdrom.saveState(out)
            sio.saveState(out)
        }
    }

    fun loadState(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(file)))).use { inp ->
                val magic = inp.readInt()
                val version = inp.readInt()
                if (magic != STATE_MAGIC || version != STATE_VERSION) return false
                cpu.loadState(inp)
                bus.loadState(inp)
                gpu.loadState(inp)
                spu.loadState(inp)
                cdrom.loadState(inp)
                sio.loadState(inp)
                true
            }
        }.getOrDefault(false)
    }

    fun shutdown() {
        isRunning = false
        spu.stopAudio()
        activeDisc?.close()
        activeDisc = null
    }

    companion object {
        private const val STATE_MAGIC = 0x5053584E // "PSXN"
        private const val STATE_VERSION = 1
    }
}
