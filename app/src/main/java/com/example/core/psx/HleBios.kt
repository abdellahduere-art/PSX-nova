package com.example.core.psx

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PlayStation 1 High-Level Emulation (HLE) Kernel & BIOS.
 * Allows booting commercial & homebrew .iso/.bin/PS-X EXE games immediately
 * without requiring an external BIOS dump, while remaining 100% toggleable
 * with an external 512KB PS1 BIOS ROM.
 */
class HleBios(
    private val cpu: R3000ACpu,
    private val bus: MemoryBus,
    private val gpu: Ps1Gpu,
    private val cdrom: Ps1Cdrom,
    private val sio: Ps1Sio
) {
    private data class OpenFile(
        val entry: IsoFileEntry?,
        val data: ByteArray?,
        var pos: Int = 0
    )

    private data class BiosEvent(
        val classId: Int,
        val spec: Int,
        val mode: Int,
        val funcAddr: Int,
        var enabled: Boolean = false,
        var occurred: Boolean = false
    )

    private var heapStart = 0x80100000.toInt()
    private var heapSize = 0x000E0000
    private var nextAlloc = 0x80100000.toInt()

    private val openFiles = mutableMapOf<Int, OpenFile>()
    private var nextFd = 3

    private val events = mutableMapOf<Int, BiosEvent>()
    private var nextEventId = 0xF1000001.toInt()

    private var padBuf1Addr = 0
    private var padBuf2Addr = 0
    private var padActive = false

    private var searchEntries: List<IsoFileEntry> = emptyList()
    private var searchIndex = 0

    fun reset() {
        openFiles.clear()
        nextFd = 3
        events.clear()
        nextEventId = 0xF1000001.toInt()
        padBuf1Addr = 0
        padBuf2Addr = 0
        padActive = false
        heapStart = 0x80100000.toInt()
        nextAlloc = heapStart

        installTrampolines()
    }

    private fun installTrampolines() {
        // Install minimal MIPS JR $ra + NOP stubs at A0, B0, C0 & 0x80000080
        // 0x03E00008 = jr $ra, 0x00000000 = nop
        for (addr in intArrayOf(0x000000A0, 0x000000B0, 0x000000C0)) {
            bus.writeRam32(addr, 0x03E00008)
            bus.writeRam32(addr + 4, 0x00000000)
        }
        // Exception vector at 0x80000080: minimal safe return via COP0 RFE
        bus.writeRam32(0x80000080.toInt(), 0x401A7000) // mfc0 $k0, EPC
        bus.writeRam32(0x80000084.toInt(), 0x00000000) // nop
        bus.writeRam32(0x80000088.toInt(), 0x03400008) // jr $k0
        bus.writeRam32(0x8000008C.toInt(), 0x42000010) // rfe
    }

    fun bootDisc(disc: DiscImage): Boolean {
        reset()
        cdrom.disc = disc

        val exeHeader: PsxExeHeader? = if (disc.isPsxExe()) {
            disc.readPsxExe()
        } else {
            val bootPath = disc.metadata.bootPath
            val bytes = if (bootPath != null) disc.readFile(bootPath) else disc.readFile("PSX.EXE")
            bytes?.let { DiscImage.parsePsxExe(it) }
        }

        if (exeHeader != null) {
            loadExeIntoRam(exeHeader)
            return true
        }
        return false
    }

    private fun loadExeIntoRam(exe: PsxExeHeader) {
        val physTarget = exe.tAddr and 0x1FFFFF
        val copyLen = exe.payload.size.coerceAtMost(bus.ram.size - physTarget)
        if (copyLen > 0) {
            System.arraycopy(exe.payload, 0, bus.ram, physTarget, copyLen)
        }
        // Clear BSS if needed
        if (exe.sSize > 0) {
            val bssPhys = exe.sAddr and 0x1FFFFF
            val bssEnd = (bssPhys + exe.sSize).coerceAtMost(bus.ram.size)
            for (i in bssPhys until bssEnd) bus.ram[i] = 0
        }

        val sp = if (exe.spBase != 0) exe.spBase + exe.spOffset else 0x801FFF00.toInt()
        cpu.reset(exe.pc0)
        cpu.gpr[28] = exe.gp0
        cpu.gpr[29] = sp
        cpu.gpr[30] = sp
        cpu.cop0[12] = 0x00000401 // IEc=1, IM2=1

        val endAddr = (exe.tAddr and 0x1FFFFF) + copyLen
        heapStart = ((endAddr + 4095) and 0x1FF000) or 0x80000000.toInt()
        nextAlloc = heapStart
        heapSize = (0x801F0000.toInt() - heapStart).coerceAtLeast(0x10000)

        // Ensure GPU defaults are friendly for games assuming BIOS init
        gpu.writeGp1(0x03000000) // Enable display
        gpu.writeGp1(0x08000001) // 320x240 NTSC
    }

    fun onVBlank() {
        if (padActive && padBuf1Addr != 0) {
            val phys = padBuf1Addr and 0x1FFFFF
            bus.ram[phys] = 0x00 // success
            bus.ram[phys + 1] = if (sio.analogMode) 0x73 else 0x41
            bus.ram[phys + 2] = (sio.buttonState and 0xFF).toByte()
            bus.ram[phys + 3] = ((sio.buttonState ushr 8) and 0xFF).toByte()
            if (sio.analogMode) {
                bus.ram[phys + 4] = sio.rightStickX.toByte()
                bus.ram[phys + 5] = sio.rightStickY.toByte()
                bus.ram[phys + 6] = sio.leftStickX.toByte()
                bus.ram[phys + 7] = sio.leftStickY.toByte()
            }
        }
        // Mark VBlank events occurred
        for (ev in events.values) {
            if (ev.enabled && ev.classId == 0xF2000003.toInt()) {
                ev.occurred = true
            }
        }
    }

    fun handleVector(physAddr: Int): Boolean {
        val func = cpu.gpr[9] and 0xFF // $t1
        when (physAddr) {
            0xA0 -> handleA0(func)
            0xB0 -> handleB0(func)
            0xC0 -> handleC0(func)
            0x1FC00000 -> return false
            else -> return false
        }
        // Return to $ra unless function replaced $pc (e.g. LoadExec / ReturnFromException)
        if (physAddr == 0xA0 && func == 0x44) return true
        cpu.pc = cpu.gpr[31]
        cpu.nextPc = cpu.pc + 4
        return true
    }

    private fun handleA0(fn: Int) {
        val a0 = cpu.gpr[4]
        val a1 = cpu.gpr[5]
        val a2 = cpu.gpr[6]
        when (fn) {
            0x00 -> { // open(filename, accessmode)
                val name = readCString(a0)
                cpu.gpr[2] = openFile(name)
            }
            0x01 -> { // lseek(fd, offset, whence)
                val f = openFiles[a0]
                if (f != null && f.data != null) {
                    f.pos = when (a2) {
                        0 -> a1
                        1 -> f.pos + a1
                        2 -> f.data.size + a1
                        else -> f.pos
                    }.coerceIn(0, f.data.size)
                    cpu.gpr[2] = f.pos
                } else {
                    cpu.gpr[2] = -1
                }
            }
            0x02 -> { // read(fd, dst, length)
                val f = openFiles[a0]
                if (f != null && f.data != null) {
                    val avail = (f.data.size - f.pos).coerceAtLeast(0).coerceAtMost(a2)
                    val dstPhys = a1 and 0x1FFFFF
                    val safeCopy = avail.coerceAtMost(bus.ram.size - dstPhys)
                    if (safeCopy > 0) {
                        System.arraycopy(f.data, f.pos, bus.ram, dstPhys, safeCopy)
                        f.pos += safeCopy
                    }
                    cpu.gpr[2] = safeCopy
                } else {
                    cpu.gpr[2] = -1
                }
            }
            0x03 -> cpu.gpr[2] = a2 // write
            0x04 -> { // close(fd)
                openFiles.remove(a0)
                cpu.gpr[2] = a0
            }
            0x12 -> cpu.gpr[2] = a0 // FileInit
            0x13 -> cpu.gpr[2] = 0 // SaveState
            0x15 -> { // strcat
                val s1 = readCString(a0)
                val s2 = readCString(a1)
                writeCString(a0, s1 + s2)
                cpu.gpr[2] = a0
            }
            0x17 -> { // strcmp
                val s1 = readCString(a0)
                val s2 = readCString(a1)
                cpu.gpr[2] = s1.compareTo(s2)
            }
            0x18 -> { // strncmp
                val s1 = readCString(a0).take(a2)
                val s2 = readCString(a1).take(a2)
                cpu.gpr[2] = s1.compareTo(s2)
            }
            0x19 -> { // strcpy
                writeCString(a0, readCString(a1))
                cpu.gpr[2] = a0
            }
            0x1B -> cpu.gpr[2] = readCString(a0).length // strlen
            0x28 -> { // bzero(dst, len)
                val p = a0 and 0x1FFFFF
                for (i in 0 until a1.coerceAtMost(bus.ram.size - p)) bus.ram[p + i] = 0
            }
            0x2A -> { // memcpy(dst, src, len)
                memCopy(a0, a1, a2)
                cpu.gpr[2] = a0
            }
            0x2B -> { // memset(dst, fill, len)
                val p = a0 and 0x1FFFFF
                val fill = a1.toByte()
                for (i in 0 until a2.coerceAtMost(bus.ram.size - p)) bus.ram[p + i] = fill
                cpu.gpr[2] = a0
            }
            0x2C -> { // memmove
                memCopy(a0, a1, a2)
                cpu.gpr[2] = a0
            }
            0x33 -> { // malloc
                val aligned = (a0 + 7) and 7.inv()
                val ptr = nextAlloc
                nextAlloc += aligned
                cpu.gpr[2] = ptr
            }
            0x34 -> Unit // free
            0x39 -> { // InitHeap(addr, size)
                heapStart = a0
                heapSize = a1
                nextAlloc = a0
            }
            0x44 -> { /* FlushCache */ }
            0x49 -> gpu.writeGp1(a0) // GPU_cw / GP1
            0x54 -> cpu.gpr[2] = 0 // _cd_init
            0x70 -> cpu.gpr[2] = 1 // _bu_init
            0x71 -> cpu.gpr[2] = 1 // _96_init
            0x72 -> cpu.gpr[2] = 1 // _96_remove
            0xA0 -> Unit // SetConf
            0xA1 -> Unit // GetConf
            0xB4 -> { // GetSystemInfo
                cpu.gpr[2] = when (a0) {
                    0 -> 0x19980610
                    1 -> 0x02
                    else -> 0
                }
            }
            else -> cpu.gpr[2] = 0
        }
    }

    private fun handleB0(fn: Int) {
        val a0 = cpu.gpr[4]
        val a1 = cpu.gpr[5]
        val a2 = cpu.gpr[6]
        val a3 = cpu.gpr[7]
        when (fn) {
            0x07 -> { // DeliverEvent(class, spec)
                for (ev in events.values) {
                    if (ev.enabled && ev.classId == a0 && ev.spec == a1) {
                        ev.occurred = true
                    }
                }
            }
            0x08 -> { // OpenEvent(class, spec, mode, func)
                val id = nextEventId++
                events[id] = BiosEvent(a0, a1, a2, a3)
                cpu.gpr[2] = id
            }
            0x09 -> { events.remove(a0); cpu.gpr[2] = 1 }
            0x0A -> { // WaitEvent(event)
                val ev = events[a0]
                if (ev != null) ev.occurred = false
                cpu.gpr[2] = 1
            }
            0x0B -> { // TestEvent(event)
                val ev = events[a0]
                if (ev != null && ev.occurred) {
                    ev.occurred = false
                    cpu.gpr[2] = 1
                } else {
                    cpu.gpr[2] = 0
                }
            }
            0x0C -> { events[a0]?.enabled = true; cpu.gpr[2] = 1 }
            0x0D -> { events[a0]?.enabled = false; cpu.gpr[2] = 1 }
            0x12 -> { // InitPAD(buf1, siz1, buf2, siz2)
                padBuf1Addr = a0
                padBuf2Addr = a2
                cpu.gpr[2] = 1
            }
            0x13 -> { padActive = true; cpu.gpr[2] = 1 } // StartPAD
            0x14 -> { padActive = false } // StopPAD
            0x15 -> { padBuf1Addr = a0; padActive = true; cpu.gpr[2] = 1 } // PAD_init
            0x16 -> cpu.gpr[2] = (sio.buttonState and 0xFFFF).inv() // PAD_dr
            0x17 -> { // ReturnFromException
                val sr = cpu.cop0[12]
                cpu.cop0[12] = (sr and 0xFFFFFFF0.toInt()) or ((sr ushr 2) and 0xF)
                cpu.pc = cpu.cop0[14]
                cpu.nextPc = cpu.pc + 4
            }
            0x32 -> cpu.gpr[2] = openFile(readCString(a0)) // open
            0x33 -> { // lseek
                val f = openFiles[a0]
                if (f != null && f.data != null) {
                    f.pos = when (a2) {
                        0 -> a1; 1 -> f.pos + a1; 2 -> f.data.size + a1; else -> f.pos
                    }.coerceIn(0, f.data.size)
                    cpu.gpr[2] = f.pos
                } else cpu.gpr[2] = -1
            }
            0x34 -> { // read
                val f = openFiles[a0]
                if (f != null && f.data != null) {
                    val avail = (f.data.size - f.pos).coerceAtLeast(0).coerceAtMost(a2)
                    val dstPhys = a1 and 0x1FFFFF
                    val safeCopy = avail.coerceAtMost(bus.ram.size - dstPhys)
                    if (safeCopy > 0) {
                        System.arraycopy(f.data, f.pos, bus.ram, dstPhys, safeCopy)
                        f.pos += safeCopy
                    }
                    cpu.gpr[2] = safeCopy
                } else cpu.gpr[2] = -1
            }
            0x35 -> cpu.gpr[2] = a2 // write
            0x36 -> { openFiles.remove(a0); cpu.gpr[2] = a0 } // close
            0x42 -> { // firstfile(name, direntry)
                searchEntries = cdrom.disc?.listDirectory() ?: emptyList()
                searchIndex = 0
                cpu.gpr[2] = fillDirEntry(a1)
            }
            0x43 -> cpu.gpr[2] = fillDirEntry(a1) // nextfile
            0x4A -> cpu.gpr[2] = 1 // InitCARD
            0x4B -> cpu.gpr[2] = 1 // StartCARD
            0x4C -> cpu.gpr[2] = 1 // StopCARD
            0x4E -> { // _card_write(chan, sector, buf)
                val card = if (a0 == 0) sio.memCard1 else sio.memCard2
                val off = (a1 and 0x3FF) * 128
                val srcPhys = a2 and 0x1FFFFF
                System.arraycopy(bus.ram, srcPhys, card, off, 128)
                sio.saveMemoryCards()
                cpu.gpr[2] = 1
            }
            0x4F -> { // _card_read(chan, sector, buf)
                val card = if (a0 == 0) sio.memCard1 else sio.memCard2
                val off = (a1 and 0x3FF) * 128
                val dstPhys = a2 and 0x1FFFFF
                System.arraycopy(card, off, bus.ram, dstPhys, 128)
                cpu.gpr[2] = 1
            }
            0x5B -> Unit // ChangeClearPAD
            else -> cpu.gpr[2] = 0
        }
    }

    private fun handleC0(fn: Int) {
        when (fn) {
            0x02, 0x03 -> cpu.gpr[2] = 1 // SysEnqIntRP / SysDeqIntRP
            0x0A -> Unit // ChangeClearRCnt
            else -> cpu.gpr[2] = 0
        }
    }

    private fun fillDirEntry(dirEntryAddr: Int): Int {
        if (searchIndex >= searchEntries.size || dirEntryAddr == 0) return 0
        val entry = searchEntries[searchIndex++]
        val phys = dirEntryAddr and 0x1FFFFF
        val nameBytes = entry.name.toByteArray(Charsets.US_ASCII)
        val nLen = nameBytes.size.coerceAtMost(19)
        for (i in 0 until 20) bus.ram[phys + i] = if (i < nLen) nameBytes[i] else 0
        bus.writeRam32(phys + 20, if (entry.isDirectory) 0x10 else 0x00)
        bus.writeRam32(phys + 24, entry.size)
        bus.writeRam32(phys + 28, entry.lba)
        return dirEntryAddr
    }

    private fun openFile(rawName: String): Int {
        val disc = cdrom.disc ?: return -1
        val entry = disc.findFile(rawName) ?: return -1
        val data = disc.readFile(entry) ?: return -1
        val fd = nextFd++
        openFiles[fd] = OpenFile(entry, data, 0)
        return fd
    }

    private fun readCString(addr: Int): String {
        val sb = StringBuilder()
        var p = addr and 0x1FFFFF
        var count = 0
        while (count++ < 256 && p < bus.ram.size) {
            val c = bus.ram[p++].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeCString(addr: Int, text: String) {
        var p = addr and 0x1FFFFF
        for (ch in text) {
            if (p >= bus.ram.size - 1) break
            bus.ram[p++] = ch.code.toByte()
        }
        bus.ram[p] = 0
    }

    private fun memCopy(dst: Int, src: Int, len: Int) {
        val d = dst and 0x1FFFFF
        val s = src and 0x1FFFFF
        val safe = len.coerceAtLeast(0).coerceAtMost(minOf(bus.ram.size - d, bus.ram.size - s))
        if (safe > 0) System.arraycopy(bus.ram, s, bus.ram, d, safe)
    }
}
