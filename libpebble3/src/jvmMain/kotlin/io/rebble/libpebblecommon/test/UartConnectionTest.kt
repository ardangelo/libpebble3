package io.rebble.libpebblecommon.test

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.platformModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.system.exitProcess

/**
 * Test program for full UART transport using Koin DI.
 *
 * This properly initializes all components via dependency injection:
 * - UartDevice
 * - UartPPoGPacketSender
 * - PPoG state machine
 * - PebbleUart transport connector
 *
 * Usage:
 * ```
 * java -jar libpebble3-jvm-fat.jar [device_path]
 * ```
 */
fun main(args: Array<String>) {
    val logger = Logger.withTag("UartConnectionTest")
    val devicePath = args.getOrNull(0) ?: "/dev/ttyS2"

    logger.i("=== UART Connection Test with PPoG ===")
    logger.i("Device: $devicePath")
    logger.i("Press Ctrl+C to exit\n")

    // Create global coroutine scope
    val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Initialize Koin with minimal modules
    val koin = startKoin {
        allowOverride(true)  // Allow test module to override platformModule
        modules(
            platformModule,  // UART-specific platform module
            testModule(globalScope, devicePath)  // Test-specific config with device path
        )
    }.koin

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.i("\nShutting down...")
        stopKoin()
    })

    runBlocking {
        try {
            // Create connection scope with parameters
            val connectionScope = koin.createScope<ConnectionScope>()

            // Get components from DI
            val transportConnector = connectionScope.get<TransportConnector>()
            val pebbleProtocolStreams = connectionScope.get<PebbleProtocolStreams>()

            logger.i("Components initialized via DI")
            logger.i("- Transport: ${transportConnector::class.simpleName}")
            logger.i("- PPoG: Ready")
            logger.i("- Sender: Ready\n")

            // Connect!
            logger.i("=== Starting UART Connection ===")
            logger.i("Firmware should send ResetRequest...")
            logger.i("We will respond with ResetComplete...")
            logger.i("Then PebbleProtocol session opens\n")

            val result = transportConnector.connect(null)

            if (result is io.rebble.libpebblecommon.connection.PebbleConnectionResult.Success) {
                logger.i("✓ Connection successful!")
                logger.i("PPoG handshake in progress...\n")

                // Wait for disconnection
                transportConnector.disconnected.await()
                logger.i("Disconnected")
            } else {
                logger.e("✗ Connection failed")
                exitProcess(1)
            }

        } catch (e: Exception) {
            logger.e("Fatal error: ${e.message}", e)
            e.printStackTrace()
            exitProcess(1)
        }
    }
}

/**
 * Test-specific module providing minimal config.
 *
 * @param globalScope Global coroutine scope for connection lifetime
 * @param devicePath UART device path (overrides platformModule default)
 */
private fun testModule(globalScope: CoroutineScope, devicePath: String) = module {
    // Config flow
    single<kotlinx.coroutines.flow.StateFlow<LibPebbleConfig>> {
        MutableStateFlow(LibPebbleConfig())
    }

    single {
        BleConfigFlow(get())
    }

    // Connection scope components (shared by all connections)
    scope<ConnectionScope> {
        // Override UART device with custom path
        scoped {
            io.rebble.libpebblecommon.connection.uart.UartDevice(devicePath)
        }
        scoped {
            ConnectionCoroutineScope(globalScope.coroutineContext)
        }

        scoped {
            PebbleProtocolStreams()
        }

        scoped {
            PPoGStream()
        }

        scoped {
            PPoG(
                pebbleProtocolStreams = get(),
                pPoGStream = get(),
                pPoGPacketSender = get(),  // UartPPoGPacketSender from platformModule
                bleConfig = get(),
                blePlatformConfig = get(),
                scope = get()
            )
        }
    }
}
