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
        assertTrue(f.length() > 200) // OpusHead + OpusTags + ~50 frames worth
        val head = f.readBytes().copyOfRange(0, 4)
        assertArrayEquals("OggS".toByteArray(Charsets.US_ASCII), head)
    }
}
