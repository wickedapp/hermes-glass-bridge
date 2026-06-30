package com.wickedapp.rokidtg.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import timber.log.Timber

class AudioCapturer {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_MASK = 0x6000FC // 8-channel Rokid mask
        const val CHANNELS_TOTAL = 8
        const val MONO_CHANNELS_KEPT = 2 // ch 0/1
    }

    @Volatile private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(onMono16k: (ShortArray) -> Unit, onError: ((String) -> Unit)? = null) {
        // getMinBufferSize does not accept custom multi-channel masks reliably.
        // Hardcode 80 ms of 8-channel 16-bit PCM @ 16 kHz = 25,600 bytes.
        val buf = 8 /*channels*/ * 2 /*bytes per sample*/ * SAMPLE_RATE / 10 // 80ms
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            ).setBufferSizeInBytes(buf).build()
        rec = r
        running = true
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            Timber.tag("AudioCapturer").w(e, "startRecording failed — mic busy")
            running = false
            rec = null
            r.release()
            onError?.invoke("mic_busy")
            return
        }
        // Verify recording actually started (ERROR_INVALID_OPERATION sets state to STOPPED).
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Timber.tag("AudioCapturer").w("startRecording did not start — mic busy (state=%d)", r.recordingState)
            running = false
            rec = null
            r.release()
            onError?.invoke("mic_busy")
            return
        }
        thread = Thread {
            // Each frame: CHANNELS_TOTAL * sizeof(short) bytes; we keep ch 0/1 mixed to mono.
            val frame = ShortArray(1024 * CHANNELS_TOTAL)
            val mono = ShortArray(1024)
            while (running) {
                val read = r.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) { Timber.w("AudioRecord read=%d", read); continue }
                val frames = read / CHANNELS_TOTAL
                for (i in 0 until frames) {
                    // mix ch 0 + ch 1 → mono with /2 to avoid clipping
                    val l = frame[i * CHANNELS_TOTAL].toInt()
                    val r2 = frame[i * CHANNELS_TOTAL + 1].toInt()
                    mono[i] = ((l + r2) / 2).toShort()
                }
                onMono16k(mono.copyOf(frames))
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(1000); thread = null
        rec?.stop(); rec?.release(); rec = null
    }
}
