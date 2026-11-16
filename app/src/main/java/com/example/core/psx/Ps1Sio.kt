package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

enum class Ps1Button(val bitMask: Int) {
    SELECT(1 shl 0),
    L3(1 shl 1),
    R3(1 shl 2),
    START(1 shl 3),
    UP(1 shl 4),
    RIGHT(1 shl 5),
    DOWN(1 shl 6),
    LEFT(1 shl 7),
    L2(1 shl 8),
    R2(1 shl 9),
    L1(1 shl 10),
    R1(1 shl 11),
    TRIANGLE(1 shl 12),
    CIRCLE(1 shl 13),
    CROSS(1 shl 14),
    SQUARE(1 shl 15)
}

data class MemoryCardSlotInfo(
    val title: String,
    val blockCount: Int,
    val firstFrame: Int
)

/**
 * SIO0 Controller & Dual 128 KB PS1 Memory Card Interface (0x1F801040..0x1F80104E).
 */
class Ps1Sio(
    private val memCardDir: File? = null,
    private val raiseIrq7: () -> Unit
) {
    // 16-bit active-low button mask (1 = released, 0 = pressed)
    @Volatile var buttonState: Int = 0xFFFF
    @Volatile var analogMode: Boolean = true
    @Volatile var leftStickX: Int = 0x80
    @Volatile var leftStickY: Int = 0x80
    @Volatile var rightStickX: Int = 0x80
    @Volatile var rightStickY: Int = 0x80

    val memCard1 = ByteArray(128 * 1024)
    val memCard2 = ByteArray(128 * 1024)

    private var ctrlReg = 0
    private var modeReg = 0x0D
    private var baudReg = 0x88
    private var txTarget = 0 // 0=none, 1=pad, 2=memcard
    private var portSelect = 0
    private var stepIdx = 0
    private var rxByte = 0xFF
    private var rxReady = false
    private var irqPending = false

    // Memory card command state
    private var mcCmd = 0
    private var mcAddrHi = 0
    private var mcAddrLo = 0
    private var mcChecksum = 0
    private val mcSectorBuf = ByteArray(128)
    private var mcFlagByte = 0x08

    init {
        formatMemoryCard(memCard1)
        formatMemoryCard(memCard2)
        loadMemoryCards()
    }

    fun reset() {
        ctrlReg = 0
        txTarget = 0
        stepIdx = 0
        rxByte = 0xFF
        rxReady = false
        irqPending = false
    }

    fun setButton(button: Ps1Button, pressed: Boolean) {
        buttonState = if (pressed) {
            buttonState and button.bitMask.inv()
        } else {
            buttonState or button.bitMask
        }
    }

    fun setStick(isLeft: Boolean, xNorm: Float, yNorm: Float) {
        val bx = ((xNorm.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(0, 255)
        val by = ((yNorm.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(0, 255)
        if (isLeft) {
            leftStickX = bx; leftStickY = by
        } else {
            rightStickX = bx; rightStickY = by
        }
    }

    fun read8(addr: Int): Int {
        return when (addr and 0xF) {
            0 -> { rxReady = false; rxByte }
            else -> read16(addr) and 0xFF
        }
    }

    fun read16(addr: Int): Int {
        return when (addr and 0xF) {
            0 -> { rxReady = false; rxByte }
            4 -> {
                var st = 0x05 // TX ready 1 & 2
                if (rxReady) st = st or 0x02
                if (irqPending) st = st or (1 shl 9)
                st
            }
            8 -> modeReg
            10 -> ctrlReg
            14 -> baudReg
            else -> 0
        }
    }

    fun write8(addr: Int, value: Int) {
        when (addr and 0xF) {
            0 -> handleTxByte(value and 0xFF)
            else -> write16(addr, value)
        }
    }

    fun write16(addr: Int, value: Int) {
        val v = value and 0xFFFF
        when (addr and 0xF) {
            0 -> handleTxByte(v and 0xFF)
            8 -> modeReg = v
            10 -> {
                ctrlReg = v
                if ((v and 0x10) != 0) irqPending = false
                if ((v and 0x40) != 0) reset()
                if ((v and 0x02) == 0) {
                    txTarget = 0
                    stepIdx = 0
                }
                portSelect = (v ushr 13) and 1
            }
            14 -> baudReg = v
        }
    }

    private fun handleTxByte(b: Int) {
        rxReady = true
        if ((ctrlReg and 0x02) == 0) {
            rxByte = 0xFF
            return
        }

        if (stepIdx == 0) {
            txTarget = when (b) {
                0x01 -> if (portSelect == 0) 1 else 0
                0x81 -> 2
                else -> 0
            }
            rxByte = 0xFF
            if (txTarget != 0) {
                stepIdx = 1
                triggerAckIrq()
            }
            return
        }

        if (txTarget == 1) {
            handlePadByte(b)
        } else if (txTarget == 2) {
            handleMemCardByte(b)
        } else {
            rxByte = 0xFF
        }
    }

    private fun handlePadByte(b: Int) {
        val btn = buttonState
        val isAnalog = analogMode
        val idLo = if (isAnalog) 0x73 else 0x41
        val maxStep = if (isAnalog) 8 else 4

        rxByte = when (stepIdx) {
            1 -> {
                if (b == 0x42) idLo else { txTarget = 0; 0xFF }
            }
            2 -> 0x5A
            3 -> btn and 0xFF
            4 -> (btn ushr 8) and 0xFF
            5 -> rightStickX
            6 -> rightStickY
            7 -> leftStickX
            8 -> leftStickY
            else -> 0xFF
        }

        if (stepIdx < maxStep && txTarget == 1) {
            stepIdx++
            triggerAckIrq()
        } else {
            txTarget = 0
            stepIdx = 0
        }
    }

    private fun handleMemCardByte(b: Int) {
        val card = if (portSelect == 0) memCard1 else memCard2
        when (stepIdx) {
            1 -> {
                mcCmd = b
                rxByte = mcFlagByte
                stepIdx++
                triggerAckIrq()
            }
            2 -> { rxByte = 0x5A; stepIdx++; triggerAckIrq() }
            3 -> { rxByte = 0x5D; stepIdx++; triggerAckIrq() }
            4 -> {
                mcAddrHi = b
                rxByte = 0x00
                stepIdx++
                triggerAckIrq()
            }
            5 -> {
                mcAddrLo = b
                mcChecksum = mcAddrHi xor mcAddrLo
                rxByte = mcAddrHi
                stepIdx++
                triggerAckIrq()
            }
            else -> {
                val frame = ((mcAddrHi shl 8) or mcAddrLo) and 0x3FF
                val frameOff = frame * 128
                if (mcCmd == 0x52) { // Read 128 bytes
                    when (stepIdx) {
                        6 -> { rxByte = 0x5C; stepIdx++; triggerAckIrq() }
                        7 -> { rxByte = 0x5D; stepIdx++; triggerAckIrq() }
                        8 -> { rxByte = mcAddrHi; stepIdx++; triggerAckIrq() }
                        9 -> { rxByte = mcAddrLo; stepIdx++; triggerAckIrq() }
                        in 10..137 -> {
                            val idx = stepIdx - 10
                            val d = card[frameOff + idx].toInt() and 0xFF
                            mcChecksum = mcChecksum xor d
                            rxByte = d
                            stepIdx++
                            triggerAckIrq()
                        }
                        138 -> { rxByte = mcChecksum and 0xFF; stepIdx++; triggerAckIrq() }
                        else -> { rxByte = 0x47; txTarget = 0; stepIdx = 0 }
                    }
                } else if (mcCmd == 0x57) { // Write 128 bytes
                    when (stepIdx) {
                        in 6..133 -> {
                            val idx = stepIdx - 6
                            mcSectorBuf[idx] = b.toByte()
                            mcChecksum = mcChecksum xor b
                            rxByte = 0x00
                            stepIdx++
                            triggerAckIrq()
                        }
                        134 -> {
                            rxByte = 0x00
                            stepIdx++
                            triggerAckIrq()
                        }
                        135 -> { rxByte = 0x5C; stepIdx++; triggerAckIrq() }
                        136 -> { rxByte = 0x5D; stepIdx++; triggerAckIrq() }
                        else -> {
                            System.arraycopy(mcSectorBuf, 0, card, frameOff, 128)
                            mcFlagByte = 0x00
                            saveMemoryCards()
                            rxByte = 0x47
                            txTarget = 0
                            stepIdx = 0
                        }
                    }
                } else {
                    rxByte = 0xFF
                    txTarget = 0
                    stepIdx = 0
                }
            }
        }
    }

    private fun triggerAckIrq() {
        if ((ctrlReg and 0x1000) != 0) {
            irqPending = true
            raiseIrq7()
        }
    }

    fun formatMemoryCard(card: ByteArray) {
        card.fill(0)
        // Frame 0: Header ("MC" + checksum)
        card[0] = 'M'.code.toByte()
        card[1] = 'C'.code.toByte()
        card[127] = computeXor(card, 0, 127)
        // Frames 1..15: Free directory entries
        for (i in 1..15) {
            val off = i * 128
            card[off] = 0xA0.toByte() // Free block
            card[off + 8] = 0xFF.toByte()
            card[off + 9] = 0xFF.toByte()
            card[off + 127] = computeXor(card, off, 127)
        }
        // Frames 16..35: Broken sector list (none = FFFFFFFF)
        for (i in 16..35) {
            val off = i * 128
            card[off] = 0xFF.toByte(); card[off + 1] = 0xFF.toByte()
            card[off + 2] = 0xFF.toByte(); card[off + 3] = 0xFF.toByte()
            card[off + 127] = computeXor(card, off, 127)
        }
    }

    fun listMemoryCardEntries(slot: Int): List<MemoryCardSlotInfo> {
        val card = if (slot == 0) memCard1 else memCard2
        if (card[0] != 'M'.code.toByte() || card[1] != 'C'.code.toByte()) return emptyList()
        val list = mutableListOf<MemoryCardSlotInfo>()
        for (i in 1..15) {
            val off = i * 128
            val state = card[off].toInt() and 0xFF
            if (state == 0x51) { // First link block of a save
                val sizeBytes = (card[off + 4].toInt() and 0xFF) or
                    ((card[off + 5].toInt() and 0xFF) shl 8) or
                    ((card[off + 6].toInt() and 0xFF) shl 16)
                val blocks = ((sizeBytes + 8191) / 8192).coerceIn(1, 15)
                val rawName = String(card, off + 10, 20, Charsets.US_ASCII)
                    .trim('\u0000', ' ')
                list.add(
                    MemoryCardSlotInfo(
                        title = rawName.ifBlank { "Partida bloque $i" },
                        blockCount = blocks,
                        firstFrame = i
                    )
                )
            }
        }
        return list
    }

    private fun computeXor(buf: ByteArray, offset: Int, len: Int): Byte {
        var x = 0
        for (i in 0 until len) x = x xor (buf[offset + i].toInt() and 0xFF)
        return x.toByte()
    }

    fun saveMemoryCards() {
        val dir = memCardDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "slot1.mcr").writeBytes(memCard1)
            File(dir, "slot2.mcr").writeBytes(memCard2)
        }
    }

    fun loadMemoryCards() {
        val dir = memCardDir ?: return
        runCatching {
            val f1 = File(dir, "slot1.mcr")
            if (f1.exists() && f1.length() == memCard1.size.toLong()) {
                System.arraycopy(f1.readBytes(), 0, memCard1, 0, memCard1.size)
            }
            val f2 = File(dir, "slot2.mcr")
            if (f2.exists() && f2.length() == memCard2.size.toLong()) {
                System.arraycopy(f2.readBytes(), 0, memCard2, 0, memCard2.size)
            }
        }
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(ctrlReg); out.writeInt(modeReg); out.writeInt(baudReg)
        out.write(memCard1)
        out.write(memCard2)
    }

    fun loadState(inp: DataInputStream) {
        ctrlReg = inp.readInt(); modeReg = inp.readInt(); baudReg = inp.readInt()
        inp.readFully(memCard1)
        inp.readFully(memCard2)
    }
}
