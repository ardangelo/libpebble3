package io.rebble.libpebblecommon.connection.uart

import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.util.Cobs

/**
 * PPoG packet sender for UART transport.
 * Sends packets over UART with COBS encoding and frame delimiters.
 *
 * Frame format: [0x00] [COBS-encoded packet] [0x00]
 *
 * @param uart UART device to send packets over
 */
class UartPPoGPacketSender(
    private val uart: UartDevice
) : PPoGPacketSender {

    /**
     * Send a PPoG packet over UART.
     *
     * @param packet Raw PPoG packet bytes to send
     * @return true if sent successfully, false on error
     */
    override suspend fun sendPacket(packet: ByteArray): Boolean {
        return try {
            // COBS encode the packet
            val encoded = Cobs.encode(packet)

            // Create frame: [0x00] + encoded + [0x00]
            val frame = ByteArray(encoded.size + 2)
            frame[0] = 0x00  // Start delimiter
            encoded.copyInto(frame, destinationOffset = 1)
            frame[frame.size - 1] = 0x00  // End delimiter

            // Write to UART
            uart.write(frame)
            true
        } catch (e: Exception) {
            // Log error if logging is available
            false
        }
    }

    /**
     * UART transport does not support connection restoration.
     * @return false
     */
    override fun wasRestoredWithSubscribedCentral(): Boolean = false
}
