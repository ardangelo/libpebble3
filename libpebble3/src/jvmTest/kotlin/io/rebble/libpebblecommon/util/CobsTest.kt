package io.rebble.libpebblecommon.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CobsTest {

    @Test
    fun testEncodeDecodeRoundTrip_Empty() {
        val data = byteArrayOf()
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncodeDecodeRoundTrip_SingleByte() {
        val data = byteArrayOf(0x42)
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncodeDecodeRoundTrip_WithZeros() {
        val data = byteArrayOf(0x00, 0x11, 0x00, 0x22, 0x00)
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncodeDecodeRoundTrip_TestPrefix() {
        // Firmware test prefix: 0xBE 0xEF 0xCA 0xFE
        val data = byteArrayOf(0xBE.toByte(), 0xEF.toByte(), 0xCA.toByte(), 0xFE.toByte())
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncodeDecodeRoundTrip_HelloMessage() {
        val data = "Hello PPOUART!".toByteArray()
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncodeDecodeRoundTrip_LongData() {
        val data = ByteArray(256) { it.toByte() }
        val encoded = Cobs.encode(data)
        val decoded = Cobs.decode(encoded)

        assertContentEquals(data, decoded)
    }

    @Test
    fun testEncode_NoZerosInOutput() {
        val data = byteArrayOf(0x00, 0x11, 0x00, 0x22, 0x00)
        val encoded = Cobs.encode(data)

        // Encoded data should not contain any 0x00 bytes
        assertEquals(false, encoded.contains(0x00))
    }

    @Test
    fun testDecode_InvalidData() {
        // Invalid COBS data (contains 0x00 in encoded stream)
        val invalid = byteArrayOf(0x01, 0x00, 0x02)
        val decoded = Cobs.decode(invalid)

        assertNull(decoded)
    }

    @Test
    fun testStreamDecoder_SingleFrame() {
        val decoder = CobsStreamDecoder()
        val originalData = byteArrayOf(0x42, 0x43, 0x44)
        val encoded = Cobs.encode(originalData)

        // Feed start delimiter
        var result = decoder.feed(0x00)
        assertNull(result)

        // Feed encoded data
        for (byte in encoded) {
            result = decoder.feed(byte)
            assertNull(result)
        }

        // Feed end delimiter - should return decoded frame
        result = decoder.feed(0x00)
        assertContentEquals(originalData, result)
    }

    @Test
    fun testStreamDecoder_MultipleFrames() {
        val decoder = CobsStreamDecoder()
        val frame1 = byteArrayOf(0x11, 0x22)
        val frame2 = byteArrayOf(0x33, 0x44)

        // Frame 1
        decoder.feed(0x00)  // Start delimiter
        for (byte in Cobs.encode(frame1)) {
            decoder.feed(byte)
        }
        val result1 = decoder.feed(0x00)  // End delimiter
        assertContentEquals(frame1, result1)

        // Frame 2
        decoder.feed(0x00)  // Start delimiter
        for (byte in Cobs.encode(frame2)) {
            decoder.feed(byte)
        }
        val result2 = decoder.feed(0x00)  // End delimiter
        assertContentEquals(frame2, result2)
    }

    @Test
    fun testStreamDecoder_Reset() {
        val decoder = CobsStreamDecoder()

        // Feed partial frame
        decoder.feed(0x00)
        decoder.feed(0x01)
        decoder.feed(0x02)

        // Reset
        decoder.reset()

        // Should be able to decode new frame after reset
        val data = byteArrayOf(0x42)
        decoder.feed(0x00)
        for (byte in Cobs.encode(data)) {
            decoder.feed(byte)
        }
        val result = decoder.feed(0x00)

        assertContentEquals(data, result)
    }
}
