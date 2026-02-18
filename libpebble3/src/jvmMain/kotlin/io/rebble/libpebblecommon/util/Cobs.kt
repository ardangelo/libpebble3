package io.rebble.libpebblecommon.util

/**
 * COBS (Consistent Overhead Byte Stuffing) encoder/decoder.
 *
 * Ported from firmware cobs.c implementation.
 * Used for UART framing to eliminate null bytes (0x00) in packet data.
 */
object Cobs {
    /**
     * COBS encode data. Output does NOT include frame delimiters (0x00).
     * Caller must add 0x00 before and after encoded data.
     *
     * @param data Input data to encode
     * @return COBS-encoded data (without frame delimiters)
     */
    fun encode(data: ByteArray): ByteArray {
        if (data.isEmpty()) return byteArrayOf()

        val output = ByteArray(data.size + (data.size / 254) + 1)
        var writeIndex = 1
        var codeIndex = 0
        var code = 1

        for (byte in data) {
            if (byte.toInt() == 0) {
                output[codeIndex] = code.toByte()
                codeIndex = writeIndex++
                code = 1
            } else {
                output[writeIndex++] = byte
                code++
                if (code == 0xFF) {
                    output[codeIndex] = code.toByte()
                    codeIndex = writeIndex++
                    code = 1
                }
            }
        }

        output[codeIndex] = code.toByte()
        return output.copyOf(writeIndex)
    }

    /**
     * COBS decode data. Input should NOT include frame delimiters.
     * Returns null if decode fails (invalid COBS data).
     *
     * @param data COBS-encoded data (without frame delimiters)
     * @return Decoded data, or null if invalid
     */
    fun decode(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return byteArrayOf()

        val output = ByteArray(data.size)
        var writeIndex = 0
        var readIndex = 0

        while (readIndex < data.size) {
            val code = data[readIndex++].toInt() and 0xFF
            if (code == 0) return null  // Invalid COBS

            for (i in 1 until code) {
                if (readIndex >= data.size) return null
                output[writeIndex++] = data[readIndex++]
            }

            if (code < 0xFF && readIndex < data.size) {
                output[writeIndex++] = 0
            }
        }

        return output.copyOf(writeIndex)
    }
}

/**
 * Streaming COBS decoder for byte-by-byte input.
 * Used by UART receiver to decode frames on the fly.
 */
class CobsStreamDecoder {
    private val buffer = mutableListOf<Byte>()
    private var state = State.AWAITING_DELIMITER

    private enum class State {
        AWAITING_DELIMITER,
        READING_FRAME
    }

    /**
     * Feed one byte. Returns decoded frame when complete (on 0x00 delimiter).
     *
     * @param byte Input byte from UART
     * @return Decoded frame if complete, null otherwise
     */
    fun feed(byte: Byte): ByteArray? {
        when (state) {
            State.AWAITING_DELIMITER -> {
                if (byte.toInt() == 0) {
                    state = State.READING_FRAME
                    buffer.clear()
                }
            }
            State.READING_FRAME -> {
                if (byte.toInt() == 0) {
                    // Frame complete
                    val encoded = buffer.toByteArray()
                    buffer.clear()
                    state = State.AWAITING_DELIMITER
                    return Cobs.decode(encoded)
                } else {
                    buffer.add(byte)
                }
            }
        }
        return null
    }

    /**
     * Reset decoder state (e.g., after error).
     */
    fun reset() {
        buffer.clear()
        state = State.AWAITING_DELIMITER
    }
}
