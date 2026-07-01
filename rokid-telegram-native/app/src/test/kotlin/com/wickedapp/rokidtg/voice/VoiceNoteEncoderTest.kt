package com.wickedapp.rokidtg.voice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sin

class VoiceNoteEncoderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun encodes_one_second_sine_to_nonempty_ogg() {
        val f = tmp.newFile("voice.ogg")
        val enc = VoiceNoteEncoder(f)
        // 1s of 440 Hz sine at 16 kHz, mono
        val samples = ShortArray(16_000) { i ->
            (sin(2 * Math.PI * 440 * i / 16_000.0) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        // Feed in 1024-sample chunks like AudioCapturer would
        var off = 0
        while (off < samples.size) {
            val n = minOf(1024, samples.size - off)
            enc.feed(samples.copyOfRange(off, off + n))
            off += n
        }
        val (dur, wave) = enc.finishWithDuration()
        assertEquals(1, dur)
        assertTrue(wave.isNotEmpty())

        // File size: 1s @ 24kbps Opus in Ogg should be well over 2000 bytes
        assertTrue("File too small: ${f.length()}", f.length() > 2000)

        val bytes = f.readBytes()

        // Magic at start
        assertArrayEquals("OggS".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, 4))

        // Last Ogg page must have EOS bit set (header_type = 0x04).
        // Find the last occurrence of the "OggS" capture pattern and check byte at offset +5.
        val magic = "OggS".toByteArray(Charsets.US_ASCII)
        var lastOggSOffset = -1
        for (i in bytes.indices.reversed()) {
            if (i + 4 <= bytes.size &&
                bytes[i]     == magic[0] &&
                bytes[i + 1] == magic[1] &&
                bytes[i + 2] == magic[2] &&
                bytes[i + 3] == magic[3]
            ) {
                lastOggSOffset = i
                break
            }
        }
        assertTrue("No OggS page found", lastOggSOffset >= 0)
        val headerType = bytes[lastOggSOffset + 5].toInt() and 0xFF
        assertTrue(
            "Last Ogg page missing EOS bit (header_type=0x${headerType.toString(16)})",
            headerType and 0x04 != 0
        )

        // Waveform values must all be in [0, 31]
        assertTrue("Waveform values out of range", wave.all { it in 0..31 })
    }

    @Test fun exact_multiple_of_frame_size_has_eos_page() {
        // 16000 samples = 50 × 320-sample (16k) frames = 50 × 960-sample (48k) frames exactly.
        // After feed(), frameAccum is empty. finishWithDuration() must still write an EOS page.
        val f = tmp.newFile("exact.ogg")
        val enc = VoiceNoteEncoder(f)
        val samples = ShortArray(16_000) { i ->
            (sin(2 * Math.PI * 440 * i / 16_000.0) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        // Feed in exact 320-sample chunks so frameAccum drains completely each time
        var off = 0
        while (off < samples.size) {
            enc.feed(samples.copyOfRange(off, off + 320))
            off += 320
        }
        enc.finishWithDuration()

        val bytes = f.readBytes()
        val magic = "OggS".toByteArray(Charsets.US_ASCII)
        var lastOggSOffset = -1
        for (i in bytes.indices.reversed()) {
            if (i + 4 <= bytes.size &&
                bytes[i]     == magic[0] &&
                bytes[i + 1] == magic[1] &&
                bytes[i + 2] == magic[2] &&
                bytes[i + 3] == magic[3]
            ) {
                lastOggSOffset = i
                break
            }
        }
        assertTrue("No OggS page found", lastOggSOffset >= 0)
        val headerType = bytes[lastOggSOffset + 5].toInt() and 0xFF
        assertTrue(
            "Exact-frame-multiple recording missing EOS bit (header_type=0x${headerType.toString(16)})",
            headerType and 0x04 != 0
        )
    }
}
