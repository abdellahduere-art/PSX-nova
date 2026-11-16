package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PlayStation 1 Geometry Transformation Engine (COP2).
 * Implements all 32 data registers, 32 control registers, UNR division, and full GTE commands.
 */
class GteCop2 {
    // 32 Data Registers
    val dataRegs = IntArray(32)
    // 32 Control Registers
    val ctrlRegs = IntArray(32)

    private var sf = 0
    private var lm = false

    fun reset() {
        dataRegs.fill(0)
        ctrlRegs.fill(0)
    }

    fun readData(reg: Int): Int {
        return when (reg and 31) {
            1, 3, 5 -> dataRegs[reg].toShort().toInt()
            8, 9, 10, 11 -> dataRegs[reg].toShort().toInt()
            15 -> dataRegs[14] // SXYP mirrors SXY2
            28, 29 -> {
                val r = lim5(dataRegs[9] shr 7)
                val g = lim5(dataRegs[10] shr 7)
                val b = lim5(dataRegs[11] shr 7)
                r or (g shl 5) or (b shl 10)
            }
            31 -> {
                // LZCR: count leading bits matching bit 31 of LZCS (reg 30)
                val v = dataRegs[30]
                val sign = v ushr 31
                var count = 0
                var tmp = v
                for (i in 0 until 32) {
                    if ((tmp ushr 31) != sign) break
                    count++
                    tmp = tmp shl 1
                }
                count
            }
            else -> dataRegs[reg and 31]
        }
    }

    fun writeData(reg: Int, value: Int) {
        val r = reg and 31
        when (r) {
            1, 3, 5, 8, 9, 10, 11 -> dataRegs[r] = value.toShort().toInt()
            7, 16, 17, 18, 19 -> dataRegs[r] = value and 0xFFFF
            15 -> {
                // SXYP FIFO push
                dataRegs[12] = dataRegs[13]
                dataRegs[13] = dataRegs[14]
                dataRegs[14] = value
                dataRegs[15] = value
            }
            28 -> {
                // IRGB -> IR1, IR2, IR3
                dataRegs[28] = value
                dataRegs[9] = (value and 0x1F) shl 7
                dataRegs[10] = ((value ushr 5) and 0x1F) shl 7
                dataRegs[11] = ((value ushr 10) and 0x1F) shl 7
            }
            29, 31 -> { /* read-only */ }
            else -> dataRegs[r] = value
        }
    }

    fun readCtrl(reg: Int): Int = ctrlRegs[reg and 31]

    fun writeCtrl(reg: Int, value: Int) {
        val r = reg and 31
        when (r) {
            4, 12, 20, 26, 27, 29, 30 -> ctrlRegs[r] = value.toShort().toInt()
            31 -> ctrlRegs[31] = (value and 0x7FFFF000) or (if ((value and 0x7F87E000) != 0) (1 shl 31) else 0)
            else -> ctrlRegs[r] = value
        }
    }

    fun execute(cmd: Int) {
        sf = if ((cmd and (1 shl 19)) != 0) 12 else 0
        lm = (cmd and (1 shl 10)) != 0
        ctrlRegs[31] = 0

        when (cmd and 0x3F) {
            0x01 -> rtps(0, setMac0 = true)
            0x06 -> nclip()
            0x0C -> op()
            0x10 -> dpcs(useFifo = false)
            0x11 -> intpl()
            0x12 -> mvmva(cmd)
            0x13 -> ncds(0)
            0x14 -> cdp()
            0x16 -> ncdt()
            0x1B -> nccs(0)
            0x1C -> cc()
            0x1E -> ncs(0)
            0x20 -> nct()
            0x28 -> sqr()
            0x29 -> dcpl()
            0x2A -> dpct()
            0x2D -> avsz3()
            0x2E -> avsz4()
            0x30 -> rtpt()
            0x3D -> gpf()
            0x3E -> gpl()
            0x3F -> ncct()
        }
        updateFlagChecksum()
    }

    private fun updateFlagChecksum() {
        val f = ctrlRegs[31]
        if ((f and 0x7F87E000) != 0) {
            ctrlRegs[31] = f or (1 shl 31)
        }
    }

    private fun vx(idx: Int): Int = dataRegs[idx * 2].toShort().toInt()
    private fun vy(idx: Int): Int = (dataRegs[idx * 2] shr 16).toShort().toInt()
    private fun vz(idx: Int): Int = dataRegs[idx * 2 + 1].toShort().toInt()

    private fun matVal(ctrlBase: Int, elem: Int): Int {
        // 3x3 matrix packed as 5 words (9 signed shorts)
        val regIdx = ctrlBase + (elem / 2)
        val raw = ctrlRegs[regIdx]
        return if ((elem and 1) == 0) raw.toShort().toInt() else (raw shr 16).toShort().toInt()
    }

    private fun trX(): Long = ctrlRegs[5].toLong() shl 12
    private fun trY(): Long = ctrlRegs[6].toLong() shl 12
    private fun trZ(): Long = ctrlRegs[7].toLong() shl 12

    private fun fcR(): Long = ctrlRegs[21].toLong() shl 12
    private fun fcG(): Long = ctrlRegs[22].toLong() shl 12
    private fun fcB(): Long = ctrlRegs[23].toLong() shl 12

    private fun bkR(): Long = ctrlRegs[13].toLong() shl 12
    private fun bkG(): Long = ctrlRegs[14].toLong() shl 12
    private fun bkB(): Long = ctrlRegs[15].toLong() shl 12

    private fun rtps(vecIdx: Int, setMac0: Boolean) {
        val x = vx(vecIdx).toLong()
        val y = vy(vecIdx).toLong()
        val z = vz(vecIdx).toLong()

        val rx = check44(0, trX() + matVal(0, 0) * x + matVal(0, 1) * y + matVal(0, 2) * z)
        val ry = check44(1, trY() + matVal(0, 3) * x + matVal(0, 4) * y + matVal(0, 5) * z)
        val rz = check44(2, trZ() + matVal(0, 6) * x + matVal(0, 7) * y + matVal(0, 8) * z)

        dataRegs[25] = (rx shr sf).toInt()
        dataRegs[26] = (ry shr sf).toInt()
        dataRegs[27] = (rz shr sf).toInt()

        val sz = limC((rz shr 12).toInt())
        dataRegs[16] = dataRegs[17]
        dataRegs[17] = dataRegs[18]
        dataRegs[18] = dataRegs[19]
        dataRegs[19] = sz

        dataRegs[9] = limB(1, dataRegs[25], lm)
        dataRegs[10] = limB(2, dataRegs[26], lm)
        // IR3 check uses unshifted rz>>12 for flag, stores shifted mac3
        limB(3, (rz shr 12).toInt(), false)
        dataRegs[11] = limBNoFlag(dataRegs[27], lm)

        val h = ctrlRegs[26] and 0xFFFF
        val div = unrDivide(h, sz).toLong()

        val sxLong = (div * dataRegs[9] + ctrlRegs[24].toLong()) shr 16
        val syLong = (div * dataRegs[10] + ctrlRegs[25].toLong()) shr 16
        val sx = limG(0, sxLong.toInt())
        val sy = limG(1, syLong.toInt())

        val sxy = (sx and 0xFFFF) or (sy shl 16)
        dataRegs[12] = dataRegs[13]
        dataRegs[13] = dataRegs[14]
        dataRegs[14] = sxy
        dataRegs[15] = sxy

        if (setMac0) {
            val dqa = ctrlRegs[27].toShort().toLong()
            val dqb = ctrlRegs[28].toLong()
            val mac0 = div * dqa + dqb
            dataRegs[24] = mac0.toInt()
            dataRegs[8] = limH((mac0 shr 12).toInt())
        }
    }

    private fun rtpt() {
        rtps(0, setMac0 = false)
        rtps(1, setMac0 = false)
        rtps(2, setMac0 = true)
    }

    private fun nclip() {
        val sx0 = dataRegs[12].toShort().toLong()
        val sy0 = (dataRegs[12] shr 16).toShort().toLong()
        val sx1 = dataRegs[13].toShort().toLong()
        val sy1 = (dataRegs[13] shr 16).toShort().toLong()
        val sx2 = dataRegs[14].toShort().toLong()
        val sy2 = (dataRegs[14] shr 16).toShort().toLong()

        val mac0 = sx0 * sy1 + sx1 * sy2 + sx2 * sy0 - sx0 * sy2 - sx1 * sy0 - sx2 * sy1
        dataRegs[24] = mac0.toInt()
    }

    private fun op() {
        val d1 = matVal(0, 0).toLong()
        val d2 = matVal(0, 4).toLong()
        val d3 = matVal(0, 8).toLong()
        val ir1 = dataRegs[9].toLong()
        val ir2 = dataRegs[10].toLong()
        val ir3 = dataRegs[11].toLong()

        val m1 = (d2 * ir3 - d3 * ir2) shr sf
        val m2 = (d3 * ir1 - d1 * ir3) shr sf
        val m3 = (d1 * ir2 - d2 * ir1) shr sf

        dataRegs[25] = m1.toInt()
        dataRegs[26] = m2.toInt()
        dataRegs[27] = m3.toInt()

        dataRegs[9] = limB(1, m1.toInt(), lm)
        dataRegs[10] = limB(2, m2.toInt(), lm)
        dataRegs[11] = limB(3, m3.toInt(), lm)
    }

    private fun avsz3() {
        val zsf3 = ctrlRegs[29].toShort().toLong()
        val mac0 = zsf3 * (dataRegs[17] + dataRegs[18] + dataRegs[19]).toLong()
        dataRegs[24] = mac0.toInt()
        dataRegs[7] = limD((mac0 shr 12).toInt())
    }

    private fun avsz4() {
        val zsf4 = ctrlRegs[30].toShort().toLong()
        val mac0 = zsf4 * (dataRegs[16] + dataRegs[17] + dataRegs[18] + dataRegs[19]).toLong()
        dataRegs[24] = mac0.toInt()
        dataRegs[7] = limD((mac0 shr 12).toInt())
    }

    private fun mvmva(cmd: Int) {
        val mx = (cmd ushr 17) and 3
        val v = (cmd ushr 15) and 3
        val cv = (cmd ushr 13) and 3

        val vxVal: Long
        val vyVal: Long
        val vzVal: Long
        if (v < 3) {
            vxVal = vx(v).toLong()
            vyVal = vy(v).toLong()
            vzVal = vz(v).toLong()
        } else {
            vxVal = dataRegs[9].toLong()
            vyVal = dataRegs[10].toLong()
            vzVal = dataRegs[11].toLong()
        }

        val tx: Long
        val ty: Long
        val tz: Long
        when (cv) {
            0 -> { tx = trX(); ty = trY(); tz = trZ() }
            1 -> { tx = bkR(); ty = bkG(); tz = bkB() }
            2 -> { tx = fcR(); ty = fcG(); tz = fcB() }
            else -> { tx = 0; ty = 0; tz = 0 }
        }

        val matBase = when (mx) {
            0 -> 0
            1 -> 8
            2 -> 16
            else -> 0
        }

        val m00: Long; val m01: Long; val m02: Long
        val m10: Long; val m11: Long; val m12: Long
        val m20: Long; val m21: Long; val m22: Long
        if (mx < 3) {
            m00 = matVal(matBase, 0).toLong(); m01 = matVal(matBase, 1).toLong(); m02 = matVal(matBase, 2).toLong()
            m10 = matVal(matBase, 3).toLong(); m11 = matVal(matBase, 4).toLong(); m12 = matVal(matBase, 5).toLong()
            m20 = matVal(matBase, 6).toLong(); m21 = matVal(matBase, 7).toLong(); m22 = matVal(matBase, 8).toLong()
        } else {
            val r = (dataRegs[6] and 0xFF).toLong() shl 4
            val ir0 = dataRegs[8].toLong()
            m00 = -r; m01 = r; m02 = ir0
            m10 = matVal(0, 1).toLong(); m11 = matVal(0, 1).toLong(); m12 = matVal(0, 1).toLong()
            m20 = matVal(0, 2).toLong(); m21 = matVal(0, 2).toLong(); m22 = matVal(0, 2).toLong()
        }

        val mac1 = ((tx + m00 * vxVal + m01 * vyVal + m02 * vzVal) shr sf).toInt()
        val mac2 = ((ty + m10 * vxVal + m11 * vyVal + m12 * vzVal) shr sf).toInt()
        val mac3 = ((tz + m20 * vxVal + m21 * vyVal + m22 * vzVal) shr sf).toInt()

        dataRegs[25] = mac1
        dataRegs[26] = mac2
        dataRegs[27] = mac3
        dataRegs[9] = limB(1, mac1, lm)
        dataRegs[10] = limB(2, mac2, lm)
        dataRegs[11] = limB(3, mac3, lm)
    }

    private fun sqr() {
        val m1 = (dataRegs[9].toLong() * dataRegs[9]) shr sf
        val m2 = (dataRegs[10].toLong() * dataRegs[10]) shr sf
        val m3 = (dataRegs[11].toLong() * dataRegs[11]) shr sf
        dataRegs[25] = m1.toInt()
        dataRegs[26] = m2.toInt()
        dataRegs[27] = m3.toInt()
        dataRegs[9] = limB(1, m1.toInt(), lm)
        dataRegs[10] = limB(2, m2.toInt(), lm)
        dataRegs[11] = limB(3, m3.toInt(), lm)
    }

    private fun mulVecMat(matBase: Int, x: Long, y: Long, z: Long, bx: Long = 0, by: Long = 0, bz: Long = 0) {
        val m1 = ((bx + matVal(matBase, 0) * x + matVal(matBase, 1) * y + matVal(matBase, 2) * z) shr sf).toInt()
        val m2 = ((by + matVal(matBase, 3) * x + matVal(matBase, 4) * y + matVal(matBase, 5) * z) shr sf).toInt()
        val m3 = ((bz + matVal(matBase, 6) * x + matVal(matBase, 7) * y + matVal(matBase, 8) * z) shr sf).toInt()
        dataRegs[25] = m1
        dataRegs[26] = m2
        dataRegs[27] = m3
        dataRegs[9] = limB(1, m1, lm)
        dataRegs[10] = limB(2, m2, lm)
        dataRegs[11] = limB(3, m3, lm)
    }

    private fun ncs(idx: Int) {
        mulVecMat(8, vx(idx).toLong(), vy(idx).toLong(), vz(idx).toLong())
        mulVecMat(16, dataRegs[9].toLong(), dataRegs[10].toLong(), dataRegs[11].toLong(), bkR(), bkG(), bkB())
        pushRgb((dataRegs[25] shr 4), (dataRegs[26] shr 4), (dataRegs[27] shr 4))
    }

    private fun nct() {
        ncs(0); ncs(1); ncs(2)
    }

    private fun ncds(idx: Int) {
        mulVecMat(8, vx(idx).toLong(), vy(idx).toLong(), vz(idx).toLong())
        mulVecMat(16, dataRegs[9].toLong(), dataRegs[10].toLong(), dataRegs[11].toLong(), bkR(), bkG(), bkB())
        dcplInternal()
    }

    private fun ncdt() {
        ncds(0); ncds(1); ncds(2)
    }

    private fun nccs(idx: Int) {
        mulVecMat(8, vx(idx).toLong(), vy(idx).toLong(), vz(idx).toLong())
        mulVecMat(16, dataRegs[9].toLong(), dataRegs[10].toLong(), dataRegs[11].toLong(), bkR(), bkG(), bkB())
        ccInternal()
    }

    private fun ncct() {
        nccs(0); nccs(1); nccs(2)
    }

    private fun cc() {
        mulVecMat(16, dataRegs[9].toLong(), dataRegs[10].toLong(), dataRegs[11].toLong(), bkR(), bkG(), bkB())
        ccInternal()
    }

    private fun ccInternal() {
        val r = (dataRegs[6] and 0xFF).toLong() shl 4
        val g = ((dataRegs[6] ushr 8) and 0xFF).toLong() shl 4
        val b = ((dataRegs[6] ushr 16) and 0xFF).toLong() shl 4
        val m1 = ((r * dataRegs[9]) shr sf).toInt()
        val m2 = ((g * dataRegs[10]) shr sf).toInt()
        val m3 = ((b * dataRegs[11]) shr sf).toInt()
        dataRegs[25] = m1; dataRegs[26] = m2; dataRegs[27] = m3
        dataRegs[9] = limB(1, m1, lm); dataRegs[10] = limB(2, m2, lm); dataRegs[11] = limB(3, m3, lm)
        pushRgb(m1 shr 4, m2 shr 4, m3 shr 4)
    }

    private fun cdp() {
        mulVecMat(16, dataRegs[9].toLong(), dataRegs[10].toLong(), dataRegs[11].toLong(), bkR(), bkG(), bkB())
        dcplInternal()
    }

    private fun dcpl() = dcplInternal()

    private fun dcplInternal() {
        val r = (dataRegs[6] and 0xFF).toLong() shl 4
        val g = ((dataRegs[6] ushr 8) and 0xFF).toLong() shl 4
        val b = ((dataRegs[6] ushr 16) and 0xFF).toLong() shl 4
        val rIr = r * dataRegs[9]
        val gIr = g * dataRegs[10]
        val bIr = b * dataRegs[11]
        interpolateFarColor(rIr, gIr, bIr)
    }

    private fun dpcs(useFifo: Boolean) {
        val rgb = if (useFifo) dataRegs[20] else dataRegs[6]
        val r = (rgb and 0xFF).toLong() shl 16
        val g = ((rgb ushr 8) and 0xFF).toLong() shl 16
        val b = ((rgb ushr 16) and 0xFF).toLong() shl 16
        interpolateFarColor(r, g, b)
    }

    private fun dpct() {
        dpcs(useFifo = true)
        dpcs(useFifo = true)
        dpcs(useFifo = true)
    }

    private fun intpl() {
        val r = dataRegs[9].toLong() shl 12
        val g = dataRegs[10].toLong() shl 12
        val b = dataRegs[11].toLong() shl 12
        interpolateFarColor(r, g, b)
    }

    private fun interpolateFarColor(r16: Long, g16: Long, b16: Long) {
        val t1 = limB(1, ((fcR() - r16) shr sf).toInt(), false).toLong()
        val t2 = limB(2, ((fcG() - g16) shr sf).toInt(), false).toLong()
        val t3 = limB(3, ((fcB() - b16) shr sf).toInt(), false).toLong()
        val ir0 = dataRegs[8].toLong()
        val m1 = ((r16 + ir0 * t1) shr sf).toInt()
        val m2 = ((g16 + ir0 * t2) shr sf).toInt()
        val m3 = ((b16 + ir0 * t3) shr sf).toInt()
        dataRegs[25] = m1; dataRegs[26] = m2; dataRegs[27] = m3
        dataRegs[9] = limB(1, m1, lm); dataRegs[10] = limB(2, m2, lm); dataRegs[11] = limB(3, m3, lm)
        pushRgb(m1 shr 4, m2 shr 4, m3 shr 4)
    }

    private fun gpf() {
        val ir0 = dataRegs[8].toLong()
        val m1 = ((ir0 * dataRegs[9]) shr sf).toInt()
        val m2 = ((ir0 * dataRegs[10]) shr sf).toInt()
        val m3 = ((ir0 * dataRegs[11]) shr sf).toInt()
        dataRegs[25] = m1; dataRegs[26] = m2; dataRegs[27] = m3
        dataRegs[9] = limB(1, m1, lm); dataRegs[10] = limB(2, m2, lm); dataRegs[11] = limB(3, m3, lm)
        pushRgb(m1 shr 4, m2 shr 4, m3 shr 4)
    }

    private fun gpl() {
        val ir0 = dataRegs[8].toLong()
        val m1 = (((dataRegs[25].toLong() shl sf) + ir0 * dataRegs[9]) shr sf).toInt()
        val m2 = (((dataRegs[26].toLong() shl sf) + ir0 * dataRegs[10]) shr sf).toInt()
        val m3 = (((dataRegs[27].toLong() shl sf) + ir0 * dataRegs[11]) shr sf).toInt()
        dataRegs[25] = m1; dataRegs[26] = m2; dataRegs[27] = m3
        dataRegs[9] = limB(1, m1, lm); dataRegs[10] = limB(2, m2, lm); dataRegs[11] = limB(3, m3, lm)
        pushRgb(m1 shr 4, m2 shr 4, m3 shr 4)
    }

    private fun pushRgb(r: Int, g: Int, b: Int) {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        val code = (dataRegs[6] ushr 24) and 0xFF
        val packed = cr or (cg shl 8) or (cb shl 16) or (code shl 24)
        dataRegs[20] = dataRegs[21]
        dataRegs[21] = dataRegs[22]
        dataRegs[22] = packed
    }

    private fun unrDivide(h: Int, sz3: Int): Int {
        if (sz3 * 2 <= h) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl 17)
            return 0x1FFFF
        }
        val z = Integer.numberOfLeadingZeros(sz3) - 16
        val n = h shl z
        var d = sz3 shl z
        val tableIdx = ((d - 0x7FC0) shr 7).coerceIn(0, 256)
        val u = UNR_TABLE[tableIdx] + 0x101
        d = (0x2000080 - d * u) ushr 8
        d = (0x0000080 + d * u) ushr 8
        val res = ((n.toLong() * d + 0x8000L) ushr 16).toInt()
        return res.coerceAtMost(0x1FFFF)
    }

    private fun check44(idx: Int, v: Long): Long {
        if (v > 0x7FFFFFFFFFFL) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl (30 - idx))
        } else if (v < -0x80000000000L) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl (27 - idx))
        }
        return v
    }

    private fun limB(idx: Int, v: Int, clampNonNeg: Boolean): Int {
        val min = if (clampNonNeg) 0 else -0x8000
        return when {
            v < min -> {
                ctrlRegs[31] = ctrlRegs[31] or (1 shl (25 - idx))
                min
            }
            v > 0x7FFF -> {
                ctrlRegs[31] = ctrlRegs[31] or (1 shl (25 - idx))
                0x7FFF
            }
            else -> v
        }
    }

    private fun limBNoFlag(v: Int, clampNonNeg: Boolean): Int {
        val min = if (clampNonNeg) 0 else -0x8000
        return v.coerceIn(min, 0x7FFF)
    }

    private fun limC(v: Int): Int = v.coerceIn(0, 0xFFFF)

    private fun limD(v: Int): Int {
        if (v < 0) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl 18)
            return 0
        }
        if (v > 0xFFFF) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl 18)
            return 0xFFFF
        }
        return v
    }

    private fun limG(idx: Int, v: Int): Int {
        if (v < -1024) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl (14 - idx))
            return -1024
        }
        if (v > 1023) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl (14 - idx))
            return 1023
        }
        return v
    }

    private fun limH(v: Int): Int {
        if (v < 0) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl 12)
            return 0
        }
        if (v > 4096) {
            ctrlRegs[31] = ctrlRegs[31] or (1 shl 12)
            return 4096
        }
        return v
    }

    private fun lim5(v: Int): Int = v.coerceIn(0, 31)

    fun saveState(out: DataOutputStream) {
        for (v in dataRegs) out.writeInt(v)
        for (v in ctrlRegs) out.writeInt(v)
    }

    fun loadState(inp: DataInputStream) {
        for (i in dataRegs.indices) dataRegs[i] = inp.readInt()
        for (i in ctrlRegs.indices) ctrlRegs[i] = inp.readInt()
    }

    companion object {
        private val UNR_TABLE = intArrayOf(
            0xFF, 0xFD, 0xFB, 0xF9, 0xF7, 0xF5, 0xF3, 0xF1, 0xEF, 0xEE, 0xEC, 0xEA, 0xE8, 0xE6, 0xE4, 0xE3,
            0xE1, 0xDF, 0xDD, 0xDC, 0xDA, 0xD8, 0xD6, 0xD5, 0xD3, 0xD1, 0xD0, 0xCE, 0xCD, 0xCB, 0xC9, 0xC8,
            0xC6, 0xC5, 0xC3, 0xC1, 0xC0, 0xBE, 0xBD, 0xBB, 0xBA, 0xB8, 0xB7, 0xB5, 0xB4, 0xB2, 0xB1, 0xB0,
            0xAE, 0xAD, 0xAB, 0xAA, 0xA9, 0xA7, 0xA6, 0xA4, 0xA3, 0xA2, 0xA0, 0x9F, 0x9E, 0x9C, 0x9B, 0x9A,
            0x99, 0x97, 0x96, 0x95, 0x94, 0x92, 0x91, 0x90, 0x8F, 0x8D, 0x8C, 0x8B, 0x8A, 0x89, 0x87, 0x86,
            0x85, 0x84, 0x83, 0x82, 0x81, 0x7F, 0x7E, 0x7D, 0x7C, 0x7B, 0x7A, 0x79, 0x78, 0x77, 0x75, 0x74,
            0x73, 0x72, 0x71, 0x70, 0x6F, 0x6E, 0x6D, 0x6C, 0x6B, 0x6A, 0x69, 0x68, 0x67, 0x66, 0x65, 0x64,
            0x63, 0x62, 0x61, 0x60, 0x5F, 0x5E, 0x5D, 0x5D, 0x5C, 0x5B, 0x5A, 0x59, 0x58, 0x57, 0x56, 0x55,
            0x54, 0x53, 0x53, 0x52, 0x51, 0x50, 0x4F, 0x4E, 0x4D, 0x4D, 0x4C, 0x4B, 0x4A, 0x49, 0x48, 0x48,
            0x47, 0x46, 0x45, 0x44, 0x43, 0x43, 0x42, 0x41, 0x40, 0x3F, 0x3F, 0x3E, 0x3D, 0x3C, 0x3C, 0x3B,
            0x3A, 0x39, 0x39, 0x38, 0x37, 0x36, 0x36, 0x35, 0x34, 0x33, 0x33, 0x32, 0x31, 0x31, 0x30, 0x2F,
            0x2E, 0x2E, 0x2D, 0x2C, 0x2C, 0x2B, 0x2A, 0x2A, 0x29, 0x28, 0x28, 0x27, 0x26, 0x26, 0x25, 0x24,
            0x24, 0x23, 0x22, 0x22, 0x21, 0x20, 0x20, 0x1F, 0x1E, 0x1E, 0x1D, 0x1D, 0x1C, 0x1B, 0x1B, 0x1A,
            0x19, 0x19, 0x18, 0x18, 0x17, 0x16, 0x16, 0x15, 0x15, 0x14, 0x14, 0x13, 0x12, 0x12, 0x11, 0x11,
            0x10, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x0D, 0x0C, 0x0C, 0x0B, 0x0A, 0x0A, 0x09, 0x09, 0x08, 0x08,
            0x07, 0x07, 0x06, 0x06, 0x05, 0x05, 0x04, 0x04, 0x03, 0x03, 0x02, 0x02, 0x01, 0x01, 0x00, 0x00,
            0x00
        )
    }
}
