package io.rebble.libpebblecommon.test

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.uart.PebbleUart
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Test program for full PPoG protocol stack over UART.
 *
 * This tests:
 * - UART + COBS transport
 * - PPoG reset handshake
 * - PebbleProtocol packet routing
 * - Endpoint message handling
 *
 * Usage:
 * ```
 * java -jar libpebble3-jvm-fat.jar [device_path]
 * ```
 */
fun main(args: Array<String>) {
    val logger = Logger.withTag("PPoGTest")
    val devicePath = args.getOrNull(0) ?: "/dev/ttyS2"

    logger.i("PPoG UART Test Program")
    logger.i("Device: $devicePath")
    logger.i("Press Ctrl+C to exit\n")

    // Create coroutine scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connectionScope = ConnectionCoroutineScope(scope)

    // Create config (with defaults)
    val configFlow = MutableStateFlow(LibPebbleConfig())
    val bleConfigFlow = BleConfigFlow(configFlow)

    // Create BLE platform config for UART mode
    val blePlatformConfig = BlePlatformConfig(
        pinAddress = false,
        phoneRequestsPairing = false,
        writeConnectivityTrigger = false,
        initialMtu = 256 + 4,  // No MTU limit for UART
        desiredTxWindow = 4,
        desiredRxWindow = 4,
        useNativeMtu = false,
        sendPpogResetOnDisconnection = false,
        delayBleConnectionsAfterAppStart = false,
        delayBleDisconnections = false,
        fallbackToResetRequest = true,  // K230 initiates reset
        closeGattServerWhenBtDisabled = false,
        supportsBtClassic = false,
    )

    // Create protocol streams
    val pebbleProtocolStreams = PebbleProtocolStreams()
    val pPoGStream = PPoGStream()

    // Create PPoG state machine (will be wired with sender after UART connects)
    val ppog = PPoG(
        pebbleProtocolStreams = pebbleProtocolStreams,
        pPoGStream = pPoGStream,
        pPoGPacketSender = DummyPacketSender(), // Temporary, will be replaced
        bleConfig = bleConfigFlow,
        blePlatformConfig = blePlatformConfig,
        scope = connectionScope,
    )

    // Create UART transport
    val uart = PebbleUart(
        devicePath = devicePath,
        scope = connectionScope,
        ppog = ppog,
        pPoGStream = pPoGStream,
    )

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.i("Shutting down...")
        runBlocking {
            uart.disconnect()
        }
    })

    runBlocking {
        try {
            // Connect UART (this will start PPoG handshake)
            logger.i("Connecting to UART...")
            val result = uart.connect(null)

            if (result !is io.rebble.libpebblecommon.connection.PebbleConnectionResult.Success) {
                logger.e("Failed to connect")
                exitProcess(1)
            }

            logger.i("UART connected successfully")

            // Update PPoG with the real sender (now that UART is connected)
            val realSender = uart.sender ?: error("Sender not initialized")
            // Note: This is a hack - ideally PPoG should be created with the sender
            // But for testing, we're creating it in the right order

            logger.i("\n=== Waiting for PPoG handshake ===")
            logger.i("Firmware should send ResetRequest")
            logger.i("We will respond with ResetComplete")
            logger.i("Then PebbleProtocol session will open\n")

            // Monitor protocol streams
            scope.launch {
                while (isActive) {
                    try {
                        val ppPacket = pebbleProtocolStreams.inboundPPBytes.readByte()
                        logger.d("PP byte received: 0x%02X".format(ppPacket.toInt() and 0xFF))
                    } catch (e: Exception) {
                        logger.e("Error reading PP bytes: ${e.message}")
                        break
                    }
                }
            }

            // Wait for disconnect
            uart.disconnected.await()
            logger.i("Disconnected")

        } catch (e: Exception) {
            logger.e("Fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}

/**
 * Dummy packet sender for initial PPoG construction.
 * The real sender (UartPPoGPacketSender) is created after UART opens.
 */
private class DummyPacketSender : io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender {
    override suspend fun sendPacket(packet: ByteArray): Boolean = false
    override fun wasRestoredWithSubscribedCentral(): Boolean = false
}
