package com.wickedapp.rokidtg.voice

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import java.io.File

class VoiceNoteEncoder(outFile: File) {

    companion object {
        private const val OUT_SR = 48_000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES_48K = OUT_SR * FRAME_MS / 1000 // 960
        private const val FRAME_SAMPLES_16K = AudioCapturer.SAMPLE_RATE * FRAME_MS / 1000 // 320
        private const val BITRATE = 24_000
    }

    private val encoder = OpusEncoder(OUT_SR, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(BITRATE)
        setComplexity(5) // mid; CPU vs quality trade-off OK for glasses
    }
    private val ogg = OggWriter(outFile)
    private val packetBuf = ByteArray(4_000)

    private val resampler = LinearUpsampler16to48()
    private val frameAccum = ArrayDeque<Short>(FRAME_SAMPLES_48K * 4)
    private var totalInputSamples16k: Long = 0
    private val levels = mutableListOf<Int>()

    fun feed(pcm16k: ShortArray) {
        totalInputSamples16k += pcm16k.size
        // RMS for waveform display
        var s = 0L
        for (v in pcm16k) s += (v.toLong() * v.toLong())
        levels += kotlin.math.sqrt((s / pcm16k.size.toDouble())).toInt()

        // Upsample 16k → 48k
        val up = resampler.process(pcm16k)
        for (v in up) frameAccum.addLast(v)

        // Drain whole 960-sample frames
        while (frameAccum.size >= FRAME_SAMPLES_48K) {
            val frame = ShortArray(FRAME_SAMPLES_48K)
            for (i in 0 until FRAME_SAMPLES_48K) frame[i] = frameAccum.removeFirst()
            val n = encoder.encode(frame, 0, FRAME_SAMPLES_48K, packetBuf, 0, packetBuf.size)
            if (n > 0) {
                ogg.writePacket(packetBuf.copyOf(n), FRAME_SAMPLES_48K, isLast = false)
            }
        }
    }

    fun finishWithDuration(): Pair<Int, ByteArray> {
        // Pad-and-flush any partial accumulated samples to one final frame
        if (frameAccum.isNotEmpty()) {
            val frame = ShortArray(FRAME_SAMPLES_48K)
            for (i in 0 until FRAME_SAMPLES_48K) {
                frame[i] = if (frameAccum.isNotEmpty()) frameAccum.removeFirst() else 0
            }
            val n = encoder.encode(frame, 0, FRAME_SAMPLES_48K, packetBuf, 0, packetBuf.size)
            if (n > 0) ogg.writePacket(packetBuf.copyOf(n), FRAME_SAMPLES_48K, isLast = true)
        }
        ogg.close()
        val durationS = (totalInputSamples16k / AudioCapturer.SAMPLE_RATE).toInt()
        val maxL = (levels.maxOrNull() ?: 1).coerceAtLeast(1)
        val waveform = ByteArray(levels.size) { i ->
            ((levels[i] * 31L / maxL).coerceAtMost(31)).toByte()
        }
        return durationS to waveform
    }
}

/** Linear upsample 16k → 48k by 3×. Output length = input length × 3. */
private class LinearUpsampler16to48 {
    private var prev: Short = 0
    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 3)
        for (i in input.indices) {
            val cur = input[i].toInt()
            val p = prev.toInt()
            // 3 samples per input sample at interpolated steps 1/3, 2/3, 3/3
            out[i * 3]     = (p + (cur - p) * 1 / 3).toShort()
            out[i * 3 + 1] = (p + (cur - p) * 2 / 3).toShort()
            out[i * 3 + 2] = cur.toShort()
            prev = input[i]
        }
        return out
    }
}
