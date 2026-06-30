package com.wickedapp.rokidtg.voice

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-stream Ogg writer for Opus-in-Ogg per RFC 7845.
 * Each call to writePacket pushes one Opus packet representing a fixed-duration frame
 * (we use 20 ms frames @ 48 kHz output = 960 samples per packet).
 */
class OggWriter(file: File, private val serial: Int = 0xACE0_BEEF.toInt()) {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private var pageSeq = 0
    private var granulePos: Long = 0
    private var closed = false

    init { writeOpusHeadPage(); writeOpusTagsPage() }

    /** Write one audio packet. samplesIn48k = samples this packet represents at 48 kHz (e.g. 960 for 20ms). */
    fun writePacket(packet: ByteArray, samplesIn48k: Int, isLast: Boolean = false) {
        granulePos += samplesIn48k
        val headerType = if (isLast) 0x04 else 0x00
        writePage(headerType, granulePos, listOf(packet))
    }

    fun close() {
        if (closed) return
        // Mark previous last page already written by caller via isLast=true.
        out.flush(); out.close()
        closed = true
    }

    // --- internals ---

    private fun writeOpusHeadPage() {
        // OpusHead packet (RFC 7845 §5.1)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1)                       // version
        head.put(1)                       // channel count = 1 (mono)
        head.putShort(0)                  // pre-skip = 0
        head.putInt(AudioCapturer.SAMPLE_RATE) // input sample rate (16 kHz)
        head.putShort(0)                  // output gain (Q7.8) = 0 dB
        head.put(0)                       // channel mapping family = 0 (mono/stereo)
        writePage(headerType = 0x02 /* BOS */, granule = 0, listOf(head.array()))
    }

    private fun writeOpusTagsPage() {
        // OpusTags packet (RFC 7845 §5.2) — minimal: "OpusTags" magic + vendor string + 0 user comments
        val vendor = "rokid-tg".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        tags.put("OpusTags".toByteArray(Charsets.US_ASCII))
        tags.putInt(vendor.size); tags.put(vendor)
        tags.putInt(0)
        writePage(headerType = 0x00, granule = 0, listOf(tags.array()))
    }

    private fun writePage(headerType: Int, granule: Long, packets: List<ByteArray>) {
        // Segment table: each packet is split into 255-byte segments; final segment may be <255 (ends the packet).
        val segs = mutableListOf<Int>()
        for (p in packets) {
            var len = p.size
            while (len >= 255) { segs += 255; len -= 255 }
            segs += len // 0..254 — final segment (0 if packet ended on a 255 boundary)
        }
        require(segs.size <= 255) { "page overflow; split caller-side" }

        val payloadSize = packets.sumOf { it.size }
        val pageSize = 27 + segs.size + payloadSize
        val page = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN)

        page.put("OggS".toByteArray(Charsets.US_ASCII))
        page.put(0)                       // stream structure version
        page.put(headerType.toByte())     // header type
        page.putLong(granule)
        page.putInt(serial)
        page.putInt(pageSeq++)
        page.putInt(0)                    // CRC placeholder
        page.put(segs.size.toByte())
        for (s in segs) page.put(s.toByte())
        for (p in packets) page.put(p)

        val crc = oggCrc32(page.array())
        page.putInt(22, crc)              // write CRC into placeholder slot
        out.write(page.array())
    }

    companion object {
        // Ogg's custom CRC-32 polynomial (0x04C11DB7), MSB-first, no reflection, no final XOR.
        private val CRC_TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var r = i shl 24
                repeat(8) {
                    r = if (r and 0x8000_0000.toInt() != 0) (r shl 1) xor 0x04C1_1DB7
                    else (r shl 1)
                }
                t[i] = r
            }
        }
        fun oggCrc32(data: ByteArray): Int {
            var crc = 0
            for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
