package io.rebble.libpebblecommon.connection.uart

import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.util.CobsStreamDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UART PPoG packet receiver.
 * Background coroutine that reads UART bytes, COBS-decodes frames,
 * and sends them to PPoGStream for processing.
 *
 * @param uart UART device to read from
 * @param pPoGStream PPoG stream to send decoded packets to
 * @param scope Coroutine scope for background reading
 */
class UartPPoGReceiver(
    private val uart: UartDevice,
    private val pPoGStream: PPoGStream,
    private val scope: CoroutineScope
) {
    private val decoder = CobsStreamDecoder()

    /**
     * Start background UART reading loop.
     * Reads bytes from UART, performs streaming COBS decode,
     * and sends complete frames to PPoGStream.
     */
    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Read one byte from UART
                    val byte = uart.read()

                    // Feed to COBS decoder
                    val frame = decoder.feed(byte)

                    if (frame != null) {
                        // Complete frame decoded - send to PPoG for processing
                        pPoGStream.inboundPPoGBytesChannel.send(frame)
                    }
                } catch (e: Exception) {
                    // UART error - log and exit loop
                    // In production, might want to retry or notify error
                    break
                }
            }
        }
    }
}
