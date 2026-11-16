package com.example.core.psx

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/**
 * PlayStation 1 GPU (1024x512 16-bit VRAM).
 * Supports full GP0 polygon, line, rectangle, texture (4/8/15-bit CLUT), Gouraud shading,
 * semi-transparency blending, VRAM blits, and GP1 display modes.
 */
class Ps1Gpu {
    val vram = ShortArray(1024 * 512)

    // Display settings (GP1)
    var displayEnabled = false
    var displayX = 0
    var displayY = 0
    var displayHRes = 320
    var displayVRes = 240
    var is24BitDepth = false
    var isPalMode = false
    var isInterlaced = false
    var dmaDirection = 0

    // Drawing settings (GP0 E1..E6)
    var texPageX = 0
    var texPageY = 0
    var semiTransparencyMode = 0
    var texDepth = 0 // 0=4bit, 1=8bit, 2=15bit
    var dithering = false
    var drawToDisplay = false
    var texDisable = false
    var texWindowMaskX = 0
    var texWindowMaskY = 0
    var texWindowOffsetX = 0
    var texWindowOffsetY = 0
    var drawAreaLeft = 0
    var drawAreaTop = 0
    var drawAreaRight = 1023
    var drawAreaBottom = 511
    var drawOffsetX = 0
    var drawOffsetY = 0
    var setMaskBit = false
    var checkMaskBit = false

    // Command state machine
    private val cmdBuf = IntArray(16)
    private var cmdIndex = 0
    private var cmdRemaining = 0
    private var currentOpcode = 0
    private var isPolyline = false

    // VRAM CPU transfer state
    private enum class TransferMode { COMMAND, CPU_TO_VRAM, VRAM_TO_CPU }
    private var transferMode = TransferMode.COMMAND
    private var xferX = 0
    private var xferY = 0
    private var xferW = 0
    private var xferH = 0
    private var xferCurX = 0
    private var xferCurY = 0

    private var gpuReadLatch = 0
    private var oddEvenBit = 0

    fun reset() {
        vram.fill(0)
        displayEnabled = false
        displayX = 0
        displayY = 0
        displayHRes = 320
        displayVRes = 240
        is24BitDepth = false
        isPalMode = false
        isInterlaced = false
        dmaDirection = 0
        texPageX = 0; texPageY = 0
        semiTransparencyMode = 0; texDepth = 0
        dithering = false; drawToDisplay = false; texDisable = false
        texWindowMaskX = 0; texWindowMaskY = 0; texWindowOffsetX = 0; texWindowOffsetY = 0
        drawAreaLeft = 0; drawAreaTop = 0; drawAreaRight = 1023; drawAreaBottom = 511
        drawOffsetX = 0; drawOffsetY = 0
        setMaskBit = false; checkMaskBit = false
        cmdIndex = 0; cmdRemaining = 0; isPolyline = false
        transferMode = TransferMode.COMMAND
        gpuReadLatch = 0
    }

    fun toggleField() {
        oddEvenBit = oddEvenBit xor 1
    }

    fun readStat(): Int {
        var stat = 0x1C000000 // Ready for cmd, DMA, data
        stat = stat or (texPageX and 0xF)
        stat = stat or ((texPageY and 1) shl 4)
        stat = stat or ((semiTransparencyMode and 3) shl 5)
        stat = stat or ((texDepth and 3) shl 7)
        if (dithering) stat = stat or (1 shl 9)
        if (drawToDisplay) stat = stat or (1 shl 10)
        if (setMaskBit) stat = stat or (1 shl 11)
        if (checkMaskBit) stat = stat or (1 shl 12)
        stat = stat or (1 shl 13) // interlace field
        if (is24BitDepth) stat = stat or (1 shl 21)
        if (isInterlaced) stat = stat or (1 shl 22)
        if (!displayEnabled) stat = stat or (1 shl 23)
        if (isPalMode) stat = stat or (1 shl 20)
        stat = stat or ((dmaDirection and 3) shl 29)
        stat = stat or ((oddEvenBit and 1) shl 31)
        // Resolution bits
        when (displayHRes) {
            256 -> Unit
            320 -> stat = stat or (1 shl 16)
            368 -> stat = stat or (1 shl 14)
            512 -> stat = stat or (2 shl 16)
            640 -> stat = stat or (3 shl 16)
        }
        if (displayVRes > 256) stat = stat or (1 shl 19)
        if (transferMode == TransferMode.VRAM_TO_CPU) stat = stat or (1 shl 27)
        return stat
    }

    fun readData(): Int {
        if (transferMode == TransferMode.VRAM_TO_CPU) {
            val lo = readVramWord()
            val hi = readVramWord()
            return lo or (hi shl 16)
        }
        return gpuReadLatch
    }

    private fun readVramWord(): Int {
        val x = (xferX + xferCurX) and 1023
        val y = (xferY + xferCurY) and 511
        val px = vram[y * 1024 + x].toInt() and 0xFFFF
        xferCurX++
        if (xferCurX >= xferW) {
            xferCurX = 0
            xferCurY++
            if (xferCurY >= xferH) {
                transferMode = TransferMode.COMMAND
            }
        }
        return px
    }

    fun writeGp1(value: Int) {
        val op = (value ushr 24) and 0xFF
        val param = value and 0xFFFFFF
        when (op) {
            0x00 -> {
                // Soft reset GPU
                cmdIndex = 0; cmdRemaining = 0; isPolyline = false
                transferMode = TransferMode.COMMAND
                displayEnabled = false
                texPageX = 0; texPageY = 0; semiTransparencyMode = 0; texDepth = 0
                drawAreaLeft = 0; drawAreaTop = 0; drawAreaRight = 1023; drawAreaBottom = 511
                drawOffsetX = 0; drawOffsetY = 0
            }
            0x01 -> {
                cmdIndex = 0; cmdRemaining = 0; isPolyline = false
                transferMode = TransferMode.COMMAND
            }
            0x02 -> { /* Ack GPU IRQ */ }
            0x03 -> displayEnabled = (param and 1) == 0
            0x04 -> dmaDirection = param and 3
            0x05 -> {
                displayX = param and 0x3FF
                displayY = (param ushr 10) and 0x1FF
            }
            0x06 -> { /* Display horizontal range */ }
            0x07 -> { /* Display vertical range */ }
            0x08 -> {
                val h1 = param and 3
                val v = (param ushr 2) and 1
                isPalMode = ((param ushr 3) and 1) != 0
                is24BitDepth = ((param ushr 4) and 1) != 0
                isInterlaced = ((param ushr 5) and 1) != 0
                val h2 = (param ushr 6) and 1
                displayHRes = if (h2 != 0) 368 else when (h1) {
                    0 -> 256
                    1 -> 320
                    2 -> 512
                    else -> 640
                }
                displayVRes = if (v != 0 && isInterlaced) 480 else if (isPalMode) 256 else 240
            }
            0x10 -> {
                gpuReadLatch = when (param and 7) {
                    2 -> (texWindowMaskX and 0x1F) or ((texWindowMaskY and 0x1F) shl 5) or
                        ((texWindowOffsetX and 0x1F) shl 10) or ((texWindowOffsetY and 0x1F) shl 15)
                    3 -> (drawAreaLeft and 0x3FF) or ((drawAreaTop and 0x1FF) shl 10)
                    4 -> (drawAreaRight and 0x3FF) or ((drawAreaBottom and 0x1FF) shl 10)
                    5 -> (drawOffsetX and 0x7FF) or ((drawOffsetY and 0x7FF) shl 11)
                    7 -> 2 // GPU Type 2
                    else -> 0
                }
            }
        }
    }

    fun writeGp0(value: Int) {
        if (transferMode == TransferMode.CPU_TO_VRAM) {
            writeVramPixelWord(value and 0xFFFF)
            if (transferMode == TransferMode.CPU_TO_VRAM) {
                writeVramPixelWord((value ushr 16) and 0xFFFF)
            }
            return
        }

        if (isPolyline) {
            if ((value and 0xF000F000.toInt()) == 0x50005000) {
                isPolyline = false
                cmdIndex = 0
                cmdRemaining = 0
                return
            }
            // Minimal polyline support
            if (cmdIndex < cmdBuf.size) {
                cmdBuf[cmdIndex++] = value
            }
            return
        }

        if (cmdRemaining == 0) {
            currentOpcode = (value ushr 24) and 0xFF
            cmdIndex = 0
            cmdBuf[cmdIndex++] = value
            cmdRemaining = commandLength(currentOpcode) - 1
            if (cmdRemaining == 0) {
                executeGp0()
            }
        } else {
            if (cmdIndex < cmdBuf.size) {
                cmdBuf[cmdIndex++] = value
            }
            cmdRemaining--
            if (cmdRemaining == 0) {
                executeGp0()
            }
        }
    }

    private fun writeVramPixelWord(pixel: Int) {
        val x = (xferX + xferCurX) and 1023
        val y = (xferY + xferCurY) and 511
        val idx = y * 1024 + x
        if (!checkMaskBit || (vram[idx].toInt() and 0x8000) == 0) {
            val out = if (setMaskBit) (pixel or 0x8000) else pixel
            vram[idx] = out.toShort()
        }
        xferCurX++
        if (xferCurX >= xferW) {
            xferCurX = 0
            xferCurY++
            if (xferCurY >= xferH) {
                transferMode = TransferMode.COMMAND
            }
        }
    }

    private fun commandLength(op: Int): Int {
        return when (op) {
            0x00, 0x01, 0x03 -> 1
            0x02 -> 3
            in 0x20..0x23 -> 4
            in 0x24..0x27 -> 7
            in 0x28..0x2B -> 5
            in 0x2C..0x2F -> 9
            in 0x30..0x33 -> 6
            in 0x34..0x37 -> 9
            in 0x38..0x3B -> 8
            in 0x3C..0x3F -> 12
            in 0x40..0x47 -> 3
            in 0x48..0x4F -> { isPolyline = true; 1 }
            in 0x50..0x57 -> 4
            in 0x58..0x5F -> { isPolyline = true; 1 }
            in 0x60..0x63 -> 3
            in 0x64..0x67 -> 4
            in 0x68..0x6B -> 2
            in 0x6C..0x6F -> 3
            in 0x70..0x73 -> 2
            in 0x74..0x77 -> 3
            in 0x78..0x7B -> 2
            in 0x7C..0x7F -> 3
            0x80 -> 4
            0xA0 -> 3
            0xC0 -> 3
            else -> 1
        }
    }

    private fun executeGp0() {
        val op = currentOpcode
        when {
            op == 0x02 -> fillVramRect()
            op in 0x20..0x3F -> drawPolygon(op)
            op in 0x40..0x47 -> drawFlatLine(op)
            op in 0x50..0x57 -> drawShadedLine(op)
            op in 0x60..0x7F -> drawRect(op)
            op == 0x80 -> copyVramToVram()
            op == 0xA0 -> startCpuToVram()
            op == 0xC0 -> startVramToCpu()
            op == 0xE1 -> {
                val p = cmdBuf[0]
                texPageX = p and 0xF
                texPageY = (p ushr 4) and 1
                semiTransparencyMode = (p ushr 5) and 3
                texDepth = (p ushr 7) and 3
                dithering = ((p ushr 9) and 1) != 0
                drawToDisplay = ((p ushr 10) and 1) != 0
                texDisable = ((p ushr 11) and 1) != 0
            }
            op == 0xE2 -> {
                val p = cmdBuf[0]
                texWindowMaskX = p and 0x1F
                texWindowMaskY = (p ushr 5) and 0x1F
                texWindowOffsetX = (p ushr 10) and 0x1F
                texWindowOffsetY = (p ushr 15) and 0x1F
            }
            op == 0xE3 -> {
                val p = cmdBuf[0]
                drawAreaLeft = p and 0x3FF
                drawAreaTop = (p ushr 10) and 0x1FF
            }
            op == 0xE4 -> {
                val p = cmdBuf[0]
                drawAreaRight = p and 0x3FF
                drawAreaBottom = (p ushr 10) and 0x1FF
            }
            op == 0xE5 -> {
                val p = cmdBuf[0]
                drawOffsetX = signExtend11(p and 0x7FF)
                drawOffsetY = signExtend11((p ushr 11) and 0x7FF)
            }
            op == 0xE6 -> {
                val p = cmdBuf[0]
                setMaskBit = (p and 1) != 0
                checkMaskBit = (p and 2) != 0
            }
        }
    }

    private fun signExtend11(v: Int): Int = if ((v and 0x400) != 0) (v or 0xFFFFF800.toInt()) else v

    private fun coordX(word: Int): Int = signExtend11(word and 0x7FF) + drawOffsetX
    private fun coordY(word: Int): Int = signExtend11((word ushr 16) and 0x7FF) + drawOffsetY

    private fun fillVramRect() {
        val color24 = cmdBuf[0] and 0xFFFFFF
        val c16 = rgb24To15(color24).toShort()
        val x0 = (cmdBuf[1] and 0x3F0)
        val y0 = ((cmdBuf[1] ushr 16) and 0x1FF)
        val w = (((cmdBuf[2] and 0x3FF) + 0xF) and 0x3F0).coerceAtLeast(0)
        val h = ((cmdBuf[2] ushr 16) and 0x1FF)
        for (dy in 0 until h) {
            val row = ((y0 + dy) and 511) * 1024
            for (dx in 0 until w) {
                vram[row + ((x0 + dx) and 1023)] = c16
            }
        }
    }

    private fun copyVramToVram() {
        val sx = cmdBuf[1] and 0x3FF
        val sy = (cmdBuf[1] ushr 16) and 0x1FF
        val dx = cmdBuf[2] and 0x3FF
        val dy = (cmdBuf[2] ushr 16) and 0x1FF
        val w = ((cmdBuf[3] and 0xFFFF) - 1 and 0x3FF) + 1
        val h = (((cmdBuf[3] ushr 16) and 0xFFFF) - 1 and 0x1FF) + 1
        for (yy in 0 until h) {
            val sRow = ((sy + yy) and 511) * 1024
            val dRow = ((dy + yy) and 511) * 1024
            for (xx in 0 until w) {
                val px = vram[sRow + ((sx + xx) and 1023)].toInt() and 0xFFFF
                val dIdx = dRow + ((dx + xx) and 1023)
                if (!checkMaskBit || (vram[dIdx].toInt() and 0x8000) == 0) {
                    vram[dIdx] = (if (setMaskBit) (px or 0x8000) else px).toShort()
                }
            }
        }
    }

    private fun startCpuToVram() {
        xferX = cmdBuf[1] and 0x3FF
        xferY = (cmdBuf[1] ushr 16) and 0x1FF
        xferW = (((cmdBuf[2] and 0xFFFF) - 1) and 0x3FF) + 1
        xferH = ((((cmdBuf[2] ushr 16) and 0xFFFF) - 1) and 0x1FF) + 1
        xferCurX = 0; xferCurY = 0
        transferMode = TransferMode.CPU_TO_VRAM
    }

    private fun startVramToCpu() {
        xferX = cmdBuf[1] and 0x3FF
        xferY = (cmdBuf[1] ushr 16) and 0x1FF
        xferW = (((cmdBuf[2] and 0xFFFF) - 1) and 0x3FF) + 1
        xferH = ((((cmdBuf[2] ushr 16) and 0xFFFF) - 1) and 0x1FF) + 1
        xferCurX = 0; xferCurY = 0
        transferMode = TransferMode.VRAM_TO_CPU
    }

    private fun drawPolygon(op: Int) {
        val shaded = (op and 0x10) != 0
        val quad = (op and 0x08) != 0
        val textured = (op and 0x04) != 0
        val semiTrans = (op and 0x02) != 0
        val rawTex = (op and 0x01) != 0

        val nVerts = if (quad) 4 else 3
        val vx = IntArray(4); val vy = IntArray(4)
        val vc = IntArray(4); val vu = IntArray(4); val vv = IntArray(4)

        var idx = 0
        var clutWord = 0
        var tpageWord = 0

        for (i in 0 until nVerts) {
            vc[i] = if (i == 0 || shaded) (cmdBuf[idx++] and 0xFFFFFF) else vc[0]
            val xy = cmdBuf[idx++]
            vx[i] = coordX(xy)
            vy[i] = coordY(xy)
            if (textured) {
                val uv = cmdBuf[idx++]
                vu[i] = uv and 0xFF
                vv[i] = (uv ushr 8) and 0xFF
                if (i == 0) clutWord = uv ushr 16
                if (i == 1) tpageWord = uv ushr 16
            }
        }

        if (textured) {
            texPageX = tpageWord and 0xF
            texPageY = (tpageWord ushr 4) and 1
            semiTransparencyMode = (tpageWord ushr 5) and 3
            texDepth = (tpageWord ushr 7) and 3
        }

        val clutX = (clutWord and 0x3F) * 16
        val clutY = (clutWord ushr 6) and 0x1FF

        rasterTriangle(
            vx[0], vy[0], vc[0], vu[0], vv[0],
            vx[1], vy[1], vc[1], vu[1], vv[1],
            vx[2], vy[2], vc[2], vu[2], vv[2],
            shaded, textured, semiTrans, rawTex, clutX, clutY
        )
        if (quad) {
            rasterTriangle(
                vx[1], vy[1], vc[1], vu[1], vv[1],
                vx[2], vy[2], vc[2], vu[2], vv[2],
                vx[3], vy[3], vc[3], vu[3], vv[3],
                shaded, textured, semiTrans, rawTex, clutX, clutY
            )
        }
    }

    private fun rasterTriangle(
        x0: Int, y0: Int, c0: Int, u0: Int, v0: Int,
        x1: Int, y1: Int, c1: Int, u1: Int, v1: Int,
        x2: Int, y2: Int, c2: Int, u2: Int, v2: Int,
        shaded: Boolean, textured: Boolean, semiTrans: Boolean, rawTex: Boolean,
        clutX: Int, clutY: Int
    ) {
        val minX = minOf(x0, x1, x2).coerceAtLeast(drawAreaLeft)
        val maxX = maxOf(x0, x1, x2).coerceAtMost(drawAreaRight)
        val minY = minOf(y0, y1, y2).coerceAtLeast(drawAreaTop)
        val maxY = maxOf(y0, y1, y2).coerceAtMost(drawAreaBottom)
        if (minX > maxX || minY > maxY) return

        // Reject excessively large triangles (>1024x512 PS1 HW constraint)
        if (maxOf(abs(x0 - x1), abs(x1 - x2), abs(x2 - x0)) > 1023 ||
            maxOf(abs(y0 - y1), abs(y1 - y2), abs(y2 - y0)) > 511
        ) return

        var ax = x0; var ay = y0; var ac = c0; var au = u0; var av = v0
        var bx = x1; var by = y1; var bc = c1; var bu = u1; var bv = v1
        var cx = x2; var cy = y2; var cc = c2; var cu = u2; var cv = v2

        var area = edge(ax, ay, bx, by, cx, cy)
        if (area == 0) return
        if (area < 0) {
            val tx = bx; val ty = by; val tc = bc; val tu = bu; val tv = bv
            bx = cx; by = cy; bc = cc; bu = cu; bv = cv
            cx = tx; cy = ty; cc = tc; cu = tu; cv = tv
            area = -area
        }

        val r0 = ac and 0xFF; val g0 = (ac ushr 8) and 0xFF; val b0 = (ac ushr 16) and 0xFF
        val r1 = bc and 0xFF; val g1 = (bc ushr 8) and 0xFF; val b1 = (bc ushr 16) and 0xFF
        val r2 = cc and 0xFF; val g2 = (cc ushr 8) and 0xFF; val b2 = (cc ushr 16) and 0xFF

        for (y in minY..maxY) {
            val rowOff = (y and 511) * 1024
            for (x in minX..maxX) {
                val w0 = edge(bx, by, cx, cy, x, y)
                val w1 = edge(cx, cy, ax, ay, x, y)
                val w2 = edge(ax, ay, bx, by, x, y)
                if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                    val rr: Int
                    val gg: Int
                    val bb: Int
                    if (shaded) {
                        rr = ((w0.toLong() * r0 + w1.toLong() * r1 + w2.toLong() * r2) / area).toInt()
                        gg = ((w0.toLong() * g0 + w1.toLong() * g1 + w2.toLong() * g2) / area).toInt()
                        bb = ((w0.toLong() * b0 + w1.toLong() * b1 + w2.toLong() * b2) / area).toInt()
                    } else {
                        rr = r0; gg = g0; bb = b0
                    }

                    if (textured) {
                        val uu = ((w0.toLong() * au + w1.toLong() * bu + w2.toLong() * cu) / area).toInt() and 0xFF
                        val vv = ((w0.toLong() * av + w1.toLong() * bv + w2.toLong() * cv) / area).toInt() and 0xFF
                        val texel = sampleTexture(uu, vv, clutX, clutY)
                        if ((texel and 0xFFFF) == 0) continue // transparent black
                        val tr = (texel and 0x1F) shl 3
                        val tg = ((texel ushr 5) and 0x1F) shl 3
                        val tb = ((texel ushr 10) and 0x1F) shl 3
                        val outR = if (rawTex) tr else (tr * rr) shr 7
                        val outG = if (rawTex) tg else (tg * gg) shr 7
                        val outB = if (rawTex) tb else (tb * bb) shr 7
                        val texSemi = (texel and 0x8000) != 0
                        plotPixel(rowOff + (x and 1023), outR.coerceAtMost(255), outG.coerceAtMost(255), outB.coerceAtMost(255), semiTrans && texSemi, texSemi)
                    } else {
                        plotPixel(rowOff + (x and 1023), rr, gg, bb, semiTrans, false)
                    }
                }
            }
        }
    }

    private fun edge(ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int): Int {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    }

    private fun drawRect(op: Int) {
        val textured = (op and 0x04) != 0
        val semiTrans = (op and 0x02) != 0
        val rawTex = (op and 0x01) != 0
        val sizeMode = (op ushr 3) and 3

        val color24 = cmdBuf[0] and 0xFFFFFF
        val x0 = coordX(cmdBuf[1])
        val y0 = coordY(cmdBuf[1])
        var idx = 2

        var u0 = 0; var v0 = 0; var clutX = 0; var clutY = 0
        if (textured) {
            val uv = cmdBuf[idx++]
            u0 = uv and 0xFF
            v0 = (uv ushr 8) and 0xFF
            val clut = uv ushr 16
            clutX = (clut and 0x3F) * 16
            clutY = (clut ushr 6) and 0x1FF
        }

        val w: Int; val h: Int
        when (sizeMode) {
            1 -> { w = 1; h = 1 }
            2 -> { w = 8; h = 8 }
            3 -> { w = 16; h = 16 }
            else -> {
                val wh = cmdBuf[idx]
                w = wh and 0x3FF
                h = (wh ushr 16) and 0x1FF
            }
        }

        val r0 = color24 and 0xFF
        val g0 = (color24 ushr 8) and 0xFF
        val b0 = (color24 ushr 16) and 0xFF

        val startX = x0.coerceAtLeast(drawAreaLeft)
        val endX = (x0 + w - 1).coerceAtMost(drawAreaRight)
        val startY = y0.coerceAtLeast(drawAreaTop)
        val endY = (y0 + h - 1).coerceAtMost(drawAreaBottom)

        for (y in startY..endY) {
            val rowOff = (y and 511) * 1024
            val tv = (v0 + (y - y0)) and 0xFF
            for (x in startX..endX) {
                if (textured) {
                    val tu = (u0 + (x - x0)) and 0xFF
                    val texel = sampleTexture(tu, tv, clutX, clutY)
                    if ((texel and 0xFFFF) == 0) continue
                    val tr = (texel and 0x1F) shl 3
                    val tg = ((texel ushr 5) and 0x1F) shl 3
                    val tb = ((texel ushr 10) and 0x1F) shl 3
                    val outR = if (rawTex) tr else (tr * r0) shr 7
                    val outG = if (rawTex) tg else (tg * g0) shr 7
                    val outB = if (rawTex) tb else (tb * b0) shr 7
                    val texSemi = (texel and 0x8000) != 0
                    plotPixel(rowOff + (x and 1023), outR.coerceAtMost(255), outG.coerceAtMost(255), outB.coerceAtMost(255), semiTrans && texSemi, texSemi)
                } else {
                    plotPixel(rowOff + (x and 1023), r0, g0, b0, semiTrans, false)
                }
            }
        }
    }

    private fun drawFlatLine(op: Int) {
        val semiTrans = (op and 0x02) != 0
        val c = cmdBuf[0] and 0xFFFFFF
        val x0 = coordX(cmdBuf[1]); val y0 = coordY(cmdBuf[1])
        val x1 = coordX(cmdBuf[2]); val y1 = coordY(cmdBuf[2])
        rasterLine(x0, y0, c, x1, y1, c, semiTrans)
    }

    private fun drawShadedLine(op: Int) {
        val semiTrans = (op and 0x02) != 0
        val c0 = cmdBuf[0] and 0xFFFFFF
        val x0 = coordX(cmdBuf[1]); val y0 = coordY(cmdBuf[1])
        val c1 = cmdBuf[2] and 0xFFFFFF
        val x1 = coordX(cmdBuf[3]); val y1 = coordY(cmdBuf[3])
        rasterLine(x0, y0, c0, x1, y1, c1, semiTrans)
    }

    private fun rasterLine(x0: Int, y0: Int, c0: Int, x1: Int, y1: Int, c1: Int, semiTrans: Boolean) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtMost(1024)
        if (steps == 0) {
            if (x0 in drawAreaLeft..drawAreaRight && y0 in drawAreaTop..drawAreaBottom) {
                plotPixel((y0 and 511) * 1024 + (x0 and 1023), c0 and 0xFF, (c0 ushr 8) and 0xFF, (c0 ushr 16) and 0xFF, semiTrans, false)
            }
            return
        }
        val r0 = c0 and 0xFF; val g0 = (c0 ushr 8) and 0xFF; val b0 = (c0 ushr 16) and 0xFF
        val r1 = c1 and 0xFF; val g1 = (c1 ushr 8) and 0xFF; val b1 = (c1 ushr 16) and 0xFF
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            if (x in drawAreaLeft..drawAreaRight && y in drawAreaTop..drawAreaBottom) {
                val rr = r0 + (r1 - r0) * i / steps
                val gg = g0 + (g1 - g0) * i / steps
                val bb = b0 + (b1 - b0) * i / steps
                plotPixel((y and 511) * 1024 + (x and 1023), rr, gg, bb, semiTrans, false)
            }
        }
    }

    private fun sampleTexture(uIn: Int, vIn: Int, clutX: Int, clutY: Int): Int {
        val u = (uIn and (texWindowMaskX * 8).inv()) or ((texWindowOffsetX and texWindowMaskX) * 8)
        val v = (vIn and (texWindowMaskY * 8).inv()) or ((texWindowOffsetY and texWindowMaskY) * 8)
        val pageBaseX = texPageX * 64
        val pageBaseY = texPageY * 256
        val vy = (pageBaseY + (v and 0xFF)) and 511

        return when (texDepth) {
            0 -> { // 4-bit CLUT
                val word = vram[vy * 1024 + ((pageBaseX + (u ushr 2)) and 1023)].toInt() and 0xFFFF
                val palIdx = (word ushr ((u and 3) * 4)) and 0xF
                vram[(clutY and 511) * 1024 + ((clutX + palIdx) and 1023)].toInt() and 0xFFFF
            }
            1 -> { // 8-bit CLUT
                val word = vram[vy * 1024 + ((pageBaseX + (u ushr 1)) and 1023)].toInt() and 0xFFFF
                val palIdx = (word ushr ((u and 1) * 8)) and 0xFF
                vram[(clutY and 511) * 1024 + ((clutX + palIdx) and 1023)].toInt() and 0xFFFF
            }
            else -> { // 15-bit direct
                vram[vy * 1024 + ((pageBaseX + (u and 0xFF)) and 1023)].toInt() and 0xFFFF
            }
        }
    }

    private fun plotPixel(idx: Int, rIn: Int, gIn: Int, bIn: Int, semiTrans: Boolean, preserveMask: Boolean) {
        if (checkMaskBit && (vram[idx].toInt() and 0x8000) != 0) return
        var r = rIn
        var g = gIn
        var b = bIn
        if (semiTrans) {
            val bg = vram[idx].toInt() and 0xFFFF
            val br = (bg and 0x1F) shl 3
            val bgG = ((bg ushr 5) and 0x1F) shl 3
            val bb = ((bg ushr 10) and 0x1F) shl 3
            when (semiTransparencyMode) {
                0 -> { r = (br + r) ushr 1; g = (bgG + g) ushr 1; b = (bb + b) ushr 1 }
                1 -> { r = (br + r).coerceAtMost(255); g = (bgG + g).coerceAtMost(255); b = (bb + b).coerceAtMost(255) }
                2 -> { r = (br - r).coerceAtLeast(0); g = (bgG - g).coerceAtLeast(0); b = (bb - b).coerceAtLeast(0) }
                3 -> { r = (br + (r ushr 2)).coerceAtMost(255); g = (bgG + (g ushr 2)).coerceAtMost(255); b = (bb + (b ushr 2)).coerceAtMost(255) }
            }
        }
        val mask = if (setMaskBit || preserveMask) 0x8000 else 0
        val p15 = ((r ushr 3) and 0x1F) or (((g ushr 3) and 0x1F) shl 5) or (((b ushr 3) and 0x1F) shl 10) or mask
        vram[idx] = p15.toShort()
    }

    private fun rgb24To15(c24: Int): Int {
        val r = (c24 ushr 3) and 0x1F
        val g = (c24 ushr 11) and 0x1F
        val b = (c24 ushr 19) and 0x1F
        return r or (g shl 5) or (b shl 10)
    }

    fun renderFrame(outPixels: IntArray, scanlines: Boolean) {
        val w = displayHRes
        val h = displayVRes
        if (!displayEnabled) {
            outPixels.fill(0xFF000000.toInt(), 0, w * h)
            return
        }
        for (y in 0 until h) {
            val vramY = (displayY + y) and 511
            val vRow = vramY * 1024
            val oRow = y * w
            val dim = scanlines && (y and 1) == 1
            if (is24BitDepth) {
                for (x in 0 until w) {
                    val byteOff = x * 3
                    val wordIdx = byteOff ushr 1
                    val w0 = vram[vRow + ((displayX + wordIdx) and 1023)].toInt() and 0xFFFF
                    val w1 = vram[vRow + ((displayX + wordIdx + 1) and 1023)].toInt() and 0xFFFF
                    val r: Int; val g: Int; val b: Int
                    if ((byteOff and 1) == 0) {
                        r = w0 and 0xFF
                        g = (w0 ushr 8) and 0xFF
                        b = w1 and 0xFF
                    } else {
                        r = (w0 ushr 8) and 0xFF
                        g = w1 and 0xFF
                        b = (w1 ushr 8) and 0xFF
                    }
                    outPixels[oRow + x] = if (dim) {
                        (0xFF shl 24) or (((r * 3) ushr 2) shl 16) or (((g * 3) ushr 2) shl 8) or ((b * 3) ushr 2)
                    } else {
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            } else {
                for (x in 0 until w) {
                    val px = vram[vRow + ((displayX + x) and 1023)].toInt() and 0xFFFF
                    var r = (px and 0x1F) shl 3
                    var g = ((px ushr 5) and 0x1F) shl 3
                    var b = ((px ushr 10) and 0x1F) shl 3
                    r = r or (r ushr 5)
                    g = g or (g ushr 5)
                    b = b or (b ushr 5)
                    if (dim) {
                        r = (r * 3) ushr 2
                        g = (g * 3) ushr 2
                        b = (b * 3) ushr 2
                    }
                    outPixels[oRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    fun saveState(out: DataOutputStream) {
        out.writeBoolean(displayEnabled)
        out.writeInt(displayX); out.writeInt(displayY)
        out.writeInt(displayHRes); out.writeInt(displayVRes)
        out.writeBoolean(is24BitDepth); out.writeBoolean(isPalMode); out.writeBoolean(isInterlaced)
        out.writeInt(dmaDirection)
        out.writeInt(texPageX); out.writeInt(texPageY)
        out.writeInt(semiTransparencyMode); out.writeInt(texDepth)
        out.writeBoolean(dithering); out.writeBoolean(drawToDisplay); out.writeBoolean(texDisable)
        out.writeInt(drawAreaLeft); out.writeInt(drawAreaTop); out.writeInt(drawAreaRight); out.writeInt(drawAreaBottom)
        out.writeInt(drawOffsetX); out.writeInt(drawOffsetY)
        out.writeBoolean(setMaskBit); out.writeBoolean(checkMaskBit)
        val byteBuf = ByteArray(vram.size * 2)
        for (i in vram.indices) {
            val s = vram[i].toInt()
            byteBuf[i * 2] = (s and 0xFF).toByte()
            byteBuf[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        out.write(byteBuf)
    }

    fun loadState(inp: DataInputStream) {
        displayEnabled = inp.readBoolean()
        displayX = inp.readInt(); displayY = inp.readInt()
        displayHRes = inp.readInt(); displayVRes = inp.readInt()
        is24BitDepth = inp.readBoolean(); isPalMode = inp.readBoolean(); isInterlaced = inp.readBoolean()
        dmaDirection = inp.readInt()
        texPageX = inp.readInt(); texPageY = inp.readInt()
        semiTransparencyMode = inp.readInt(); texDepth = inp.readInt()
        dithering = inp.readBoolean(); drawToDisplay = inp.readBoolean(); texDisable = inp.readBoolean()
        drawAreaLeft = inp.readInt(); drawAreaTop = inp.readInt(); drawAreaRight = inp.readInt(); drawAreaBottom = inp.readInt()
        drawOffsetX = inp.readInt(); drawOffsetY = inp.readInt()
        setMaskBit = inp.readBoolean(); checkMaskBit = inp.readBoolean()
        val byteBuf = ByteArray(vram.size * 2)
        inp.readFully(byteBuf)
        for (i in vram.indices) {
            vram[i] = ((byteBuf[i * 2].toInt() and 0xFF) or ((byteBuf[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }
}
