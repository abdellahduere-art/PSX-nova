package com.example.core.psx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PlayStation 1 SPU (Sound Processing Unit).
 * 512 KB Sound RAM, 24 ADPCM voices with pitch, ADSR, loop/repeat, plus XA/CD-DA mixing.
 */
class Ps1Spu {
    val spuRam = ByteArray(512 * 1024)
    private val voiceRegs = IntArray(24 * 8) // 8 16-bit regs per voice (0x1F801C00..0x1F801D7F)
    private val voiceSamplePos = DoubleArray(24)
    private val voicePrev1 = IntArray(24)
    private val voicePrev2 = IntArray(24)
    private val voiceActive = BooleanArray(24)
    private val voiceEnv = FloatArray(24)
    private val voicePcmBlock = IntArray(24 * 28)
    private val voiceBlockAddr = IntArray(24) { -1 }

    var spuControl = 0
    var spuStatus = 0
    var transferAddr = 0
    var transferPtr = 0
    var mainVolL = 0x3FFF
    var mainVolR = 0x3FFF
    var cdVolL = 0x3FFF
    var cdVolR = 0x3FFF
    var irqAddr = 0

    var audioEnabled = true
    private var audioTrack: AudioTrack? = null
    private val framePcm = ShortArray(735 * 2) // ~44100 / 60 stereo samples
    private var cdRingL = ShortArray(4096)
    private var cdRingR = ShortArray(4096)
    private var cdWritePos = 0
    private var cdReadPos = 0

    fun reset() {
        spuRam.fill(0)
        voiceRegs.fill(0)
        voiceSamplePos.fill(0.0)
        voicePrev1.fill(0)
        voicePrev2.fill(0)
        voiceActive.fill(false)
        voiceEnv.fill(0f)
        voiceBlockAddr.fill(-1)
        spuControl = 0
        spuStatus = 0
        transferAddr = 0
        transferPtr = 0
        cdWritePos = 0
        cdReadPos = 0
    }

    fun startAudio() {
        if (!audioEnabled || audioTrack != null) return
        runCatching {
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            track.play()
            audioTrack = track
        }
    }

    fun stopAudio() {
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
    }

    fun pushCdAudioSample(left: Short, right: Short) {
        val next = (cdWritePos + 1) and 4095
        if (next != cdReadPos) {
            cdRingL[cdWritePos] = left
            cdRingR[cdWritePos] = right
            cdWritePos = next
        }
    }

    fun read16(addr: Int): Int {
        val off = addr and 0x3FF
        if (off < 0x180) {
            val v = off ushr 4
            val r = (off and 0xF) ushr 1
            return voiceRegs[v * 8 + r] and 0xFFFF
        }
        return when (off) {
            0x180 -> mainVolL and 0xFFFF
            0x182 -> mainVolR and 0xFFFF
            0x1A6 -> (transferAddr ushr 3) and 0xFFFF
            0x1AA -> spuControl and 0xFFFF
            0x1AE -> (spuControl and 0x3F)
            0x1B0 -> cdVolL and 0xFFFF
            0x1B2 -> cdVolR and 0xFFFF
            else -> 0
        }
    }

    fun write16(addr: Int, value: Int) {
        val off = addr and 0x3FF
        val v16 = value and 0xFFFF
        if (off < 0x180) {
            val v = off ushr 4
            val r = (off and 0xF) ushr 1
            voiceRegs[v * 8 + r] = v16
            return
        }
        when (off) {
            0x180 -> mainVolL = v16.toShort().toInt()
            0x182 -> mainVolR = v16.toShort().toInt()
            0x188 -> keyOn(v16, 0)
            0x18A -> keyOn(v16, 16)
            0x18C -> keyOff(v16, 0)
            0x18E -> keyOff(v16, 16)
            0x1A4 -> irqAddr = (v16 * 8) and 0x7FFFF
            0x1A6 -> {
                transferAddr = (v16 * 8) and 0x7FFFF
                transferPtr = transferAddr
            }
            0x1A8 -> {
                writeRam16(transferPtr, v16)
                transferPtr = (transferPtr + 2) and 0x7FFFF
            }
            0x1AA -> {
                spuControl = v16
                spuStatus = v16 and 0x3F
            }
            0x1B0 -> cdVolL = v16.toShort().toInt()
            0x1B2 -> cdVolR = v16.toShort().toInt()
        }
    }

    fun writeDmaWord(word: Int) {
        writeRam16(transferPtr, word and 0xFFFF)
        writeRam16((transferPtr + 2) and 0x7FFFF, (word ushr 16) and 0xFFFF)
        transferPtr = (transferPtr + 4) and 0x7FFFF
    }

    fun readDmaWord(): Int {
        val lo = readRam16(transferPtr)
        val hi = readRam16((transferPtr + 2) and 0x7FFFF)
        transferPtr = (transferPtr + 4) and 0x7FFFF
        return lo or (hi shl 16)
    }

    private fun writeRam16(byteAddr: Int, value: Int) {
        val a = byteAddr and 0x7FFFE
        spuRam[a] = (value and 0xFF).toByte()
        spuRam[a + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun readRam16(byteAddr: Int): Int {
        val a = byteAddr and 0x7FFFE
        return (spuRam[a].toInt() and 0xFF) or ((spuRam[a + 1].toInt() and 0xFF) shl 8)
    }

    private fun keyOn(mask: Int, baseVoice: Int) {
        for (i in 0 until 16) {
            val v = baseVoice + i
            if (v >= 24) break
            if ((mask and (1 shl i)) != 0) {
                val startAddr = (voiceRegs[v * 8 + 3] and 0xFFFF) * 8
                voiceBlockAddr[v] = startAddr
                voiceSamplePos[v] = 0.0
                voicePrev1[v] = 0
                voicePrev2[v] = 0
                voiceEnv[v] = 1.0f
                voiceActive[v] = true
                decodeAdpcmBlock(v, startAddr)
            }
        }
    }

    private fun keyOff(mask: Int, baseVoice: Int) {
        for (i in 0 until 16) {
            val v = baseVoice + i
            if (v >= 24) break
            if ((mask and (1 shl i)) != 0) {
                voiceEnv[v] *= 0.25f
                if (voiceEnv[v] < 0.01f) {
                    voiceActive[v] = false
                }
            }
        }
    }

    private fun decodeAdpcmBlock(v: Int, addr: Int) {
        val base = addr and 0x7FFF0
        val header = spuRam[base].toInt() and 0xFF
        val shift = (header and 0x0F).coerceAtMost(12)
        val filter = ((header ushr 4) and 0x07).coerceAtMost(4)
        val f0 = ADPCM_F0[filter]
        val f1 = ADPCM_F1[filter]
        var p1 = voicePrev1[v]
        var p2 = voicePrev2[v]
        val outOff = v * 28

        for (i in 0 until 28) {
            val b = spuRam[base + 2 + (i ushr 1)].toInt() and 0xFF
            val nib = if ((i and 1) == 0) (b and 0xF) else (b ushr 4)
            var sample = (nib shl 28) shr (16 + shift)
            sample += (p1 * f0 + p2 * f1 + 32) shr 6
            sample = sample.coerceIn(-32768, 32767)
            p2 = p1
            p1 = sample
            voicePcmBlock[outOff + i] = sample
        }
        voicePrev1[v] = p1
        voicePrev2[v] = p2
    }

    fun mixAndOutputFrame(isPal: Boolean) {
        if (!audioEnabled || (spuControl and 0x8000) == 0) {
            return
        }
        val track = audioTrack ?: return
        val samples = if (isPal) 882 else 735
        val len = samples.coerceAtMost(framePcm.size / 2)

        for (s in 0 until len) {
            var sumL = 0
            var sumR = 0
            for (v in 0 until 24) {
                if (!voiceActive[v]) continue
                val base = v * 8
                val pitch = voiceRegs[base + 2] and 0xFFFF
                val step = pitch / 4096.0
                val sampleIdx = voiceSamplePos[v].toInt().coerceIn(0, 27)
                val pcm = (voicePcmBlock[v * 28 + sampleIdx] * voiceEnv[v]).toInt()
                val volL = (voiceRegs[base + 0].toShort().toInt()) shl 1
                val volR = (voiceRegs[base + 1].toShort().toInt()) shl 1
                sumL += (pcm * volL) shr 15
                sumR += (pcm * volR) shr 15

                voiceSamplePos[v] += step
                if (voiceSamplePos[v] >= 28.0) {
                    voiceSamplePos[v] -= 28.0
                    val curAddr = voiceBlockAddr[v]
                    val flags = spuRam[(curAddr + 1) and 0x7FFFF].toInt() and 0xFF
                    if ((flags and 0x04) != 0) {
                        voiceRegs[base + 7] = (curAddr ushr 3) and 0xFFFF
                    }
                    if ((flags and 0x01) != 0) {
                        if ((flags and 0x02) != 0) {
                            val loopAddr = (voiceRegs[base + 7] and 0xFFFF) * 8
                            voiceBlockAddr[v] = loopAddr
                            decodeAdpcmBlock(v, loopAddr)
                        } else {
                            voiceActive[v] = false
                        }
                    } else {
                        val nextAddr = (curAddr + 16) and 0x7FFFF
                        voiceBlockAddr[v] = nextAddr
                        decodeAdpcmBlock(v, nextAddr)
                    }
                }
            }
            if (cdReadPos != cdWritePos) {
                sumL += (cdRingL[cdReadPos].toInt() * cdVolL) shr 15
                sumR += (cdRingR[cdReadPos].toInt() * cdVolR) shr 15
                cdReadPos = (cdReadPos + 1) and 4095
            }
            framePcm[s * 2] = sumL.coerceIn(-32768, 32767).toShort()
            framePcm[s * 2 + 1] = sumR.coerceIn(-32768, 32767).toShort()
        }
        runCatching {
            track.write(framePcm, 0, len * 2, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(spuControl); out.writeInt(spuStatus)
        out.writeInt(transferAddr); out.writeInt(transferPtr)
        out.writeInt(mainVolL); out.writeInt(mainVolR)
        for (v in voiceRegs) out.writeInt(v)
        for (v in voiceActive) out.writeBoolean(v)
        out.write(spuRam)
    }

    fun loadState(inp: DataInputStream) {
        spuControl = inp.readInt(); spuStatus = inp.readInt()
        transferAddr = inp.readInt(); transferPtr = inp.readInt()
        mainVolL = inp.readInt(); mainVolR = inp.readInt()
        for (i in voiceRegs.indices) voiceRegs[i] = inp.readInt()
        for (i in voiceActive.indices) voiceActive[i] = inp.readBoolean()
        inp.readFully(spuRam)
    }

    companion object {
        private val ADPCM_F0 = intArrayOf(0, 60, 115, 98, 122)
        private val ADPCM_F1 = intArrayOf(0, 0, -52, -55, -60)
    }
}
