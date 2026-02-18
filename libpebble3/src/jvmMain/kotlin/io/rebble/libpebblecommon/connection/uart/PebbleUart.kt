package io.rebble.libpebblecommon.connection.uart

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleConnectionResult
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * UART transport connector for Pebble communication.
 * Implements TransportConnector interface for UART+COBS communication.
 *
 * Lifecycle:
 * 1. connect() - Opens UART, creates sender/receiver, starts PPoG
 * 2. PPoG handles reset handshake and protocol negotiation
 * 3. disconnect() - Closes PPoG and UART
 *
 * @param devicePath Path to UART device (e.g., "/dev/ttyS2")
 * @param scope Coroutine scope for connection lifetime
 * @param ppog PPoG state machine instance
 * @param pPoGStream PPoG stream for inbound/outbound packets
 */
class PebbleUart(
    private val devicePath: String,
    private val scope: ConnectionCoroutineScope,
    private val ppog: PPoG,
    private val pPoGStream: PPoGStream,
) : TransportConnector {
    private val logger = Logger.withTag("PebbleUart/$devicePath")

    private val uart = UartDevice(devicePath)
    private var receiver: UartPPoGReceiver? = null
    var sender: UartPPoGPacketSender? = null
        private set

    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    /**
     * Connect to Pebble via UART.
     *
     * Steps:
     * 1. Open UART device and configure with stty
     * 2. Create packet sender
     * 3. Start packet receiver
     * 4. Launch PPoG state machine (handles reset handshake)
     *
     * @param lastError Previous connection error (unused for UART)
     * @return Connection result (Success or Failure)
     */
    override suspend fun connect(lastError: ConnectionFailureReason?): PebbleConnectionResult {
        logger.d("connect() to $devicePath")

        return try {
            // 1. Open UART device
            uart.open()
            logger.d("UART opened successfully")

            // 2. Create sender (will be used by PPoG)
            sender = UartPPoGPacketSender(uart)
            logger.d("Packet sender created")

            // 3. Start receiver
            receiver = UartPPoGReceiver(uart, pPoGStream, scope)
            receiver?.start()
            logger.d("Packet receiver started")

            // 4. Start PPoG state machine
            // This will:
            // - Initiate or wait for ResetRequest
            // - Complete reset handshake
            // - Open PebbleProtocolRunner
            scope.launch {
                try {
                    logger.d("Starting PPoG state machine")
                    ppog.run()
                    logger.d("PPoG completed")
                } catch (e: Exception) {
                    logger.e("PPoG error: ${e.message}", e)
                    _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                }
            }

            PebbleConnectionResult.Success
        } catch (e: Exception) {
            logger.e("UART connection failed: ${e.message}", e)
            PebbleConnectionResult.Failed(ConnectionFailureReason.FailedToConnect)
        }
    }

    /**
     * Disconnect from Pebble.
     * Closes PPoG and UART device.
     */
    override suspend fun disconnect() {
        logger.d("disconnect()")

        // Close PPoG first
        ppog.close()

        // Close UART device
        uart.close()

        // Complete disconnected deferred
        if (!_disconnected.isCompleted) {
            _disconnected.complete(ConnectionFailureReason.FailedToConnect)
        }
    }
}
