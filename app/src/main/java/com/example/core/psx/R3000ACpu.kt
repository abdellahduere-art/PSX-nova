package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PlayStation 1 MIPS R3000A CPU Core.
 * Implements all MIPS I instructions, Load Delay Slots, Branch Delay Slots,
 * COP0 exceptions/interrupts, COP2 (GTE), and optional HLE BIOS vector hooks.
 */
class R3000ACpu(
    private val bus: MemoryBus,
    val gte: GteCop2 = GteCop2()
) {
    val gpr = IntArray(32)
    var hi = 0
    var lo = 0
    var pc = 0xBFC00000.toInt()
    var nextPc = 0xBFC00004.toInt()
    var currentPc = 0xBFC00000.toInt()

    // COP0 registers
    val cop0 = IntArray(32)

    // Load delay slot
    private var loadReg = 0
    private var loadVal = 0

    // Branch delay slot tracking
    private var inBranchDelay = false
    private var branchTaken = false

    // HLE BIOS handler hook (null when running external LLE BIOS)
    var hleHandler: ((vectorAddr: Int) -> Boolean)? = null

    fun reset(startPc: Int = 0xBFC00000.toInt()) {
        gpr.fill(0)
        cop0.fill(0)
        cop0[15] = 0x00000002 // PRId
        hi = 0; lo = 0
        pc = startPc
        nextPc = startPc + 4
        currentPc = startPc
        loadReg = 0
        loadVal = 0
        inBranchDelay = false
        branchTaken = false
        gte.reset()
    }

    fun step(): Int {
        currentPc = pc

        // HLE BIOS vector trap if active
        val physPc = currentPc and 0x1FFFFFFF
        val hook = hleHandler
        if (hook != null && (physPc == 0xA0 || physPc == 0xB0 || physPc == 0xC0 || physPc == 0x1FC00000)) {
            if (hook(physPc)) {
                return 4
            }
        }

        // Check hardware interrupts (IEc = bit 0, IM2 = bit 10 of COP0 SR[12])
        if (bus.isIrqPending()) {
            cop0[13] = cop0[13] or (1 shl 10)
            val sr = cop0[12]
            if ((sr and 1) != 0 && (sr and (1 shl 10)) != 0) {
                triggerException(0)
                return 2
            }
        } else {
            cop0[13] = cop0[13] and (1 shl 10).inv()
        }

        val instr = bus.read32(currentPc)
        pc = nextPc
        nextPc = pc + 4

        val pendingLoadReg = loadReg
        val pendingLoadVal = loadVal
        loadReg = 0
        loadVal = 0

        val wasInDelay = inBranchDelay
        val wasTaken = branchTaken
        inBranchDelay = false
        branchTaken = false

        executeInstruction(instr, wasInDelay, wasTaken)

        if (pendingLoadReg != 0) {
            gpr[pendingLoadReg] = pendingLoadVal
        }
        gpr[0] = 0
        return 1
    }

    private fun setReg(r: Int, v: Int) {
        if (r != 0) {
            gpr[r] = v
            if (loadReg == r) loadReg = 0 // cancel pending load if written directly
        }
    }

    private fun scheduleLoad(r: Int, v: Int) {
        if (r != 0) {
            loadReg = r
            loadVal = v
        }
    }

    private fun branchTo(offset: Int, condition: Boolean) {
        inBranchDelay = true
        if (condition) {
            nextPc = pc + (offset shl 2)
            branchTaken = true
        }
    }

    private fun executeInstruction(instr: Int, wasDelay: Boolean, wasTaken: Boolean) {
        val op = (instr ushr 26) and 0x3F
        val rs = (instr ushr 21) and 0x1F
        val rt = (instr ushr 16) and 0x1F
        val rd = (instr ushr 11) and 0x1F
        val shamt = (instr ushr 6) and 0x1F
        val funct = instr and 0x3F
        val imm = instr and 0xFFFF
        val simm = instr.toShort().toInt()
        val target = instr and 0x03FFFFFF

        when (op) {
            0x00 -> when (funct) { // SPECIAL
                0x00 -> setReg(rd, gpr[rt] shl shamt) // SLL
                0x02 -> setReg(rd, gpr[rt] ushr shamt) // SRL
                0x03 -> setReg(rd, gpr[rt] shr shamt) // SRA
                0x04 -> setReg(rd, gpr[rt] shl (gpr[rs] and 0x1F)) // SLLV
                0x06 -> setReg(rd, gpr[rt] ushr (gpr[rs] and 0x1F)) // SRLV
                0x07 -> setReg(rd, gpr[rt] shr (gpr[rs] and 0x1F)) // SRAV
                0x08 -> { // JR
                    inBranchDelay = true
                    branchTaken = true
                    nextPc = gpr[rs]
                }
                0x09 -> { // JALR
                    val ret = nextPc
                    inBranchDelay = true
                    branchTaken = true
                    nextPc = gpr[rs]
                    setReg(rd, ret)
                }
                0x0C -> triggerException(8, wasDelay) // SYSCALL
                0x0D -> triggerException(9, wasDelay) // BREAK
                0x10 -> setReg(rd, hi) // MFHI
                0x11 -> hi = gpr[rs] // MTHI
                0x12 -> setReg(rd, lo) // MFLO
                0x13 -> lo = gpr[rs] // MTLO
                0x18 -> { // MULT
                    val res = gpr[rs].toLong() * gpr[rt].toLong()
                    lo = res.toInt()
                    hi = (res ushr 32).toInt()
                }
                0x19 -> { // MULTU
                    val res = (gpr[rs].toLong() and 0xFFFFFFFFL) * (gpr[rt].toLong() and 0xFFFFFFFFL)
                    lo = res.toInt()
                    hi = (res ushr 32).toInt()
                }
                0x1A -> { // DIV
                    val n = gpr[rs]
                    val d = gpr[rt]
                    if (d == 0) {
                        hi = n
                        lo = if (n >= 0) -1 else 1
                    } else if (n == Int.MIN_VALUE && d == -1) {
                        lo = Int.MIN_VALUE
                        hi = 0
                    } else {
                        lo = n / d
                        hi = n % d
                    }
                }
                0x1B -> { // DIVU
                    val n = gpr[rs].toLong() and 0xFFFFFFFFL
                    val d = gpr[rt].toLong() and 0xFFFFFFFFL
                    if (d == 0L) {
                        hi = n.toInt()
                        lo = -1
                    } else {
                        lo = (n / d).toInt()
                        hi = (n % d).toInt()
                    }
                }
                0x20, 0x21 -> setReg(rd, gpr[rs] + gpr[rt]) // ADD / ADDU
                0x22, 0x23 -> setReg(rd, gpr[rs] - gpr[rt]) // SUB / SUBU
                0x24 -> setReg(rd, gpr[rs] and gpr[rt]) // AND
                0x25 -> setReg(rd, gpr[rs] or gpr[rt]) // OR
                0x26 -> setReg(rd, gpr[rs] xor gpr[rt]) // XOR
                0x27 -> setReg(rd, (gpr[rs] or gpr[rt]).inv()) // NOR
                0x2A -> setReg(rd, if (gpr[rs] < gpr[rt]) 1 else 0) // SLT
                0x2B -> { // SLTU
                    val a = gpr[rs].toLong() and 0xFFFFFFFFL
                    val b = gpr[rt].toLong() and 0xFFFFFFFFL
                    setReg(rd, if (a < b) 1 else 0)
                }
            }
            0x01 -> { // REGIMM
                val link = (rt and 0x1E) == 0x10
                val cond = if ((rt and 1) == 0) gpr[rs] < 0 else gpr[rs] >= 0
                if (link) setReg(31, nextPc)
                branchTo(simm, cond)
            }
            0x02 -> { // J
                inBranchDelay = true
                branchTaken = true
                nextPc = (pc and 0xF0000000.toInt()) or (target shl 2)
            }
            0x03 -> { // JAL
                setReg(31, nextPc)
                inBranchDelay = true
                branchTaken = true
                nextPc = (pc and 0xF0000000.toInt()) or (target shl 2)
            }
            0x04 -> branchTo(simm, gpr[rs] == gpr[rt]) // BEQ
            0x05 -> branchTo(simm, gpr[rs] != gpr[rt]) // BNE
            0x06 -> branchTo(simm, gpr[rs] <= 0) // BLEZ
            0x07 -> branchTo(simm, gpr[rs] > 0) // BGTZ
            0x08, 0x09 -> setReg(rt, gpr[rs] + simm) // ADDI / ADDIU
            0x0A -> setReg(rt, if (gpr[rs] < simm) 1 else 0) // SLTI
            0x0B -> { // SLTIU
                val a = gpr[rs].toLong() and 0xFFFFFFFFL
                val b = simm.toLong() and 0xFFFFFFFFL
                setReg(rt, if (a < b) 1 else 0)
            }
            0x0C -> setReg(rt, gpr[rs] and imm) // ANDI
            0x0D -> setReg(rt, gpr[rs] or imm) // ORI
            0x0E -> setReg(rt, gpr[rs] xor imm) // XORI
            0x0F -> setReg(rt, imm shl 16) // LUI
            0x10 -> handleCop0(instr, rs, rt, rd)
            0x12 -> handleCop2(instr, rs, rt, rd)
            0x20 -> { // LB
                val addr = gpr[rs] + simm
                if ((cop0[12] and 0x10000) == 0) scheduleLoad(rt, bus.read8(addr).toByte().toInt())
            }
            0x21 -> { // LH
                val addr = gpr[rs] + simm
                if ((cop0[12] and 0x10000) == 0) scheduleLoad(rt, bus.read16(addr).toShort().toInt())
            }
            0x22 -> { // LWL
                if ((cop0[12] and 0x10000) == 0) {
                    val addr = gpr[rs] + simm
                    val aligned = bus.read32(addr and 0xFFFFFFFC.toInt())
                    val cur = if (loadReg == rt) loadVal else gpr[rt]
                    val res = when (addr and 3) {
                        0 -> (cur and 0x00FFFFFF) or (aligned shl 24)
                        1 -> (cur and 0x0000FFFF) or (aligned shl 16)
                        2 -> (cur and 0x000000FF) or (aligned shl 8)
                        else -> aligned
                    }
                    scheduleLoad(rt, res)
                }
            }
            0x23 -> { // LW
                val addr = gpr[rs] + simm
                if ((cop0[12] and 0x10000) == 0) scheduleLoad(rt, bus.read32(addr))
            }
            0x24 -> { // LBU
                val addr = gpr[rs] + simm
                if ((cop0[12] and 0x10000) == 0) scheduleLoad(rt, bus.read8(addr) and 0xFF)
            }
            0x25 -> { // LHU
                val addr = gpr[rs] + simm
                if ((cop0[12] and 0x10000) == 0) scheduleLoad(rt, bus.read16(addr) and 0xFFFF)
            }
            0x26 -> { // LWR
                if ((cop0[12] and 0x10000) == 0) {
                    val addr = gpr[rs] + simm
                    val aligned = bus.read32(addr and 0xFFFFFFFC.toInt())
                    val cur = if (loadReg == rt) loadVal else gpr[rt]
                    val res = when (addr and 3) {
                        0 -> aligned
                        1 -> (cur and 0xFF000000.toInt()) or (aligned ushr 8)
                        2 -> (cur and 0xFFFF0000.toInt()) or (aligned ushr 16)
                        else -> (cur and 0xFFFFFF00.toInt()) or (aligned ushr 24)
                    }
                    scheduleLoad(rt, res)
                }
            }
            0x28 -> if ((cop0[12] and 0x10000) == 0) bus.write8(gpr[rs] + simm, gpr[rt]) // SB
            0x29 -> if ((cop0[12] and 0x10000) == 0) bus.write16(gpr[rs] + simm, gpr[rt]) // SH
            0x2A -> if ((cop0[12] and 0x10000) == 0) { // SWL
                val addr = gpr[rs] + simm
                val alignedAddr = addr and 0xFFFFFFFC.toInt()
                val cur = bus.read32(alignedAddr)
                val v = gpr[rt]
                val res = when (addr and 3) {
                    0 -> (cur and 0xFFFFFF00.toInt()) or (v ushr 24)
                    1 -> (cur and 0xFFFF0000.toInt()) or (v ushr 16)
                    2 -> (cur and 0xFF000000.toInt()) or (v ushr 8)
                    else -> v
                }
                bus.write32(alignedAddr, res)
            }
            0x2B -> if ((cop0[12] and 0x10000) == 0) bus.write32(gpr[rs] + simm, gpr[rt]) // SW
            0x2E -> if ((cop0[12] and 0x10000) == 0) { // SWR
                val addr = gpr[rs] + simm
                val alignedAddr = addr and 0xFFFFFFFC.toInt()
                val cur = bus.read32(alignedAddr)
                val v = gpr[rt]
                val res = when (addr and 3) {
                    0 -> v
                    1 -> (cur and 0x000000FF) or (v shl 8)
                    2 -> (cur and 0x0000FFFF) or (v shl 16)
                    else -> (cur and 0x00FFFFFF) or (v shl 24)
                }
                bus.write32(alignedAddr, res)
            }
            0x32 -> { // LWC2 (GTE load)
                if ((cop0[12] and 0x10000) == 0) {
                    gte.writeData(rt, bus.read32(gpr[rs] + simm))
                }
            }
            0x3A -> { // SWC2 (GTE store)
                if ((cop0[12] and 0x10000) == 0) {
                    bus.write32(gpr[rs] + simm, gte.readData(rt))
                }
            }
        }
    }

    private fun handleCop0(instr: Int, rs: Int, rt: Int, rd: Int) {
        when (rs) {
            0x00 -> scheduleLoad(rt, cop0[rd]) // MFC0
            0x04 -> cop0[rd] = gpr[rt] // MTC0
            0x10 -> { // RFE
                if ((instr and 0x3F) == 0x10) {
                    val sr = cop0[12]
                    cop0[12] = (sr and 0xFFFFFFF0.toInt()) or ((sr ushr 2) and 0xF)
                }
            }
        }
    }

    private fun handleCop2(instr: Int, rs: Int, rt: Int, rd: Int) {
        if ((rs and 0x10) != 0) {
            gte.execute(instr)
            return
        }
        when (rs) {
            0x00 -> scheduleLoad(rt, gte.readData(rd)) // MFC2
            0x02 -> scheduleLoad(rt, gte.readCtrl(rd)) // CFC2
            0x04 -> gte.writeData(rd, gpr[rt]) // MTC2
            0x06 -> gte.writeCtrl(rd, gpr[rt]) // CTC2
        }
    }

    fun triggerException(causeCode: Int, inDelay: Boolean = inBranchDelay) {
        val sr = cop0[12]
        cop0[12] = (sr and 0xFFFFFFC0.toInt()) or ((sr shl 2) and 0x3C)
        cop0[13] = (cop0[13] and 0xFFFFFF00.toInt()) or (causeCode shl 2)
        if (inDelay) {
            cop0[14] = currentPc - 4
            cop0[13] = cop0[13] or (1 shl 31)
        } else {
            cop0[14] = currentPc
            cop0[13] = cop0[13] and (1 shl 31).inv()
        }
        val handler = if ((sr and (1 shl 22)) != 0) 0xBFC00180.toInt() else 0x80000080.toInt()
        pc = handler
        nextPc = handler + 4
        inBranchDelay = false
    }

    fun saveState(out: DataOutputStream) {
        for (v in gpr) out.writeInt(v)
        out.writeInt(hi); out.writeInt(lo)
        out.writeInt(pc); out.writeInt(nextPc); out.writeInt(currentPc)
        for (v in cop0) out.writeInt(v)
        out.writeInt(loadReg); out.writeInt(loadVal)
        out.writeBoolean(inBranchDelay); out.writeBoolean(branchTaken)
        gte.saveState(out)
    }

    fun loadState(inp: DataInputStream) {
        for (i in gpr.indices) gpr[i] = inp.readInt()
        hi = inp.readInt(); lo = inp.readInt()
        pc = inp.readInt(); nextPc = inp.readInt(); currentPc = inp.readInt()
        for (i in cop0.indices) cop0[i] = inp.readInt()
        loadReg = inp.readInt(); loadVal = inp.readInt()
        inBranchDelay = inp.readBoolean(); branchTaken = inp.readBoolean()
        gte.loadState(inp)
    }
}
