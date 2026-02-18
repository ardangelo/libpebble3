package io.rebble.libpebblecommon.test

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.uart.UartDevice
import io.rebble.libpebblecommon.util.CobsStreamDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Standalone test program for UART communication.
 * Tests basic UART + COBS without full libpebble3 infrastructure.
 *
 * Usage:
 * ```
 * java -cp libpebble3-jvm.jar io.rebble.libpebblecommon.test.UartTestMainKt
 * ```
 */
fun main(args: Array<String>) {
    val logger = Logger.withTag("UartTest")
    val devicePath = args.getOrNull(0) ?: "/dev/ttyS2"

    logger.i("UART Test Program")
    logger.i("Device: $devicePath")
    logger.i("Press Ctrl+C to exit\n")

    val uart = UartDevice(devicePath)
    val decoder = CobsStreamDecoder()

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.i("Shutting down...")
        uart.close()
    })

    runBlocking {
        try {
            // Open UART
            uart.open()
            logger.i("UART opened successfully")

            // Start reading in background
            val readJob = launch(Dispatchers.IO) {
                var frameCount = 0
                while (isActive) {
                    try {
                        val byte = uart.read()
                        val frame = decoder.feed(byte)

                        if (frame != null) {
                            frameCount++
                            logger.i("Frame #$frameCount received (${frame.size} bytes)")

                            // Check for firmware test prefix: 0xBEEFCAFE
                            if (frame.size >= 4 &&
                                frame[0].toInt() and 0xFF == 0xBE &&
                                frame[1].toInt() and 0xFF == 0xEF &&
                                frame[2].toInt() and 0xFF == 0xCA &&
                                frame[3].toInt() and 0xFF == 0xFE
                            ) {
                                logger.i("  ✓ Test prefix detected!")

                                // Print message (skip 4-byte prefix)
                                if (frame.size > 4) {
                                    val message = frame.sliceArray(4 until frame.size)
                                        .map { it.toInt().toChar() }.joinToString("")
                                    logger.i("  Message: $message")
                                }
                            }

                            // Print hex dump
                            val hex = frame.joinToString(" ") {
                                "%02X".format(it.toInt() and 0xFF)
                            }
                            logger.d("  Hex: $hex")
                        }
                    } catch (e: Exception) {
                        logger.e("Read error: ${e.message}", e)
                        break
                    }
                }
            }

            logger.i("\nWaiting for data...")
            logger.i("Firmware test commands:")
            logger.i("  k230 ppouart test       - Send test message with 0xBEEFCAFE prefix")
            logger.i("  k230 ppouart data 0     - Send data packet with SN=0")
            logger.i("  k230 stats              - Show TX/RX statistics\n")

            // Wait for read job to complete
            readJob.join()
        } catch (e: Exception) {
            logger.e("Fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}
