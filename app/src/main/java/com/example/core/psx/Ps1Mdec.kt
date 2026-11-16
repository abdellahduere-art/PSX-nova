package com.example.core.psx

/**
 * PlayStation 1 MDEC (Macroblock Decoder) for FMV video frames (0x1F801820..0x1F801824).
 */
class Ps1Mdec {
    private var status = 0x80040000.toInt()
    private var remainingWords = 0
    private var depth = 2 // 2=24bit, 3=15bit
    private val outWords = IntArray(256)
    private var outIdx = 0
    private var outCount = 0

    fun reset() {
        status = 0x80040000.toInt()
        remainingWords = 0
        outIdx = 0
        outCount = 0
    }

    fun readData(): Int {
        return if (outIdx < outCount) outWords[outIdx++] else 0
    }

    fun readStatus(): Int = status

    fun writeCommand(word: Int) {
        if (remainingWords > 0) {
            remainingWords--
            if (remainingWords == 0) {
                status = (status and (1 shl 29).inv()) or (1 shl 31)
            }
            return
        }
        val cmd = (word ushr 29) and 7
        when (cmd) {
            1 -> {
                depth = (word ushr 27) and 3
                remainingWords = word and 0xFFFF
                outIdx = 0
                outCount = 64
                outWords.fill(0)
            }
            2, 3 -> remainingWords = 0
        }
    }

    fun writeControl(word: Int) {
        if ((word and 0x80000000.toInt()) != 0) reset()
    }
}
