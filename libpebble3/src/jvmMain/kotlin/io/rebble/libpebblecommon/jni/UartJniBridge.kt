package io.rebble.libpebblecommon.jni

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.PebbleProtocolRunner
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.platformModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * JNI bridge for UART communication with Pebble.
 *
 * This class provides a simple interface for C++ code to:
 * - Initialize libpebble3 with UART transport
 * - Register callbacks for endpoint messages
 * - Send messages to endpoints
 *
 * Usage from C++ via JNI:
 * ```cpp
 * jclass bridgeClass = env->FindClass("io/rebble/libpebblecommon/jni/UartJniBridge");
 * jmethodID constructor = env->GetMethodID(bridgeClass, "<init>", "()V");
 * jobject bridge = env->NewObject(bridgeClass, constructor);
 * ```
 */
class UartJniBridge {
    private val logger = Logger.withTag("UartJniBridge")
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionScope: org.koin.core.scope.Scope? = null
    private var transportConnector: TransportConnector? = null
    private var allowUnknownEndpoints: Boolean = false

    // Native callback methods (implemented in C++)
    private external fun nativeOnEndpointMessage(endpoint: Int, data: ByteArray)
    private external fun nativeOnConnected()
    private external fun nativeOnSessionOpened()
    private external fun nativeOnDisconnected()

    /**
     * Initialize libpebble3 and connect to UART.
     *
     * @param devicePath Path to UART device (e.g., "/dev/ttyS1")
     * @return true if initialization succeeded, false otherwise
     */
    fun init(devicePath: String): Boolean {
        logger.i("Initializing UART bridge with device: $devicePath")

        return try {
            // Initialize Koin with modules
            startKoin {
                allowOverride(true)
                modules(
                    platformModule,
                    bridgeModule(globalScope, devicePath)
                )
            }

            logger.i("Koin initialized successfully")
            true
        } catch (e: Exception) {
            logger.e("Failed to initialize: ${e.message}", e)
            false
        }
    }

    /**
     * Connect to Pebble via UART.
     * Starts PPoG handshake and opens protocol session.
     *
     * @return true if connection succeeded, false otherwise
     */
    fun connect(): Boolean = runBlocking {
        logger.i("Connecting...")

        try {
            // Create connection scope
            connectionScope = org.koin.core.context.GlobalContext.get().createScope<ConnectionScope>()

            // Get transport connector
            transportConnector = connectionScope?.get<TransportConnector>()

            // Connect
            val result = transportConnector?.connect(null)

            if (result is io.rebble.libpebblecommon.connection.PebbleConnectionResult.Success) {
                logger.i("Connected successfully")

                // Notify C++ that UART is connected (handshake will happen async)
                try {
                    nativeOnConnected()
                } catch (e: Exception) {
                    logger.w("Native callback failed: ${e.message}")
                }

                // Start PebbleProtocolRunner to parse PP bytes into messages
                val protocolRunner = connectionScope?.get<PebbleProtocolRunner>()
                if (protocolRunner != null) {
                    globalScope.launch { protocolRunner.run() }
                    logger.i("PebbleProtocolRunner started")
                } else {
                    logger.w("PebbleProtocolRunner not available")
                }

                // Get protocol streams to receive all messages (including unknown endpoints)
                val protocolStreams = connectionScope?.get<PebbleProtocolStreams>()

                // Start collecting incoming messages
                if (protocolStreams != null) {
                    logger.i("Starting message collection from PebbleProtocolStreams")
                    var sessionOpenNotified = false
                    globalScope.launch {
                        logger.d("Message collector coroutine started")
                        try {
                            protocolStreams.inboundMessagesFlow.collect { msg ->
                                try {
                                    // Notify session opened on first message
                                    if (!sessionOpenNotified) {
                                        sessionOpenNotified = true
                                        logger.i("First message received - session is open")
                                        try {
                                            nativeOnSessionOpened()
                                        } catch (e: Exception) {
                                            logger.w("Native callback failed: ${e.message}")
                                        }
                                    }

                                    // Extract endpoint from PP header (bytes 2-3, big-endian)
                                    val rawBytes = msg.rawBytes
                                    val endpoint = ((rawBytes[2].toInt() and 0xFF) shl 8) or
                                                   (rawBytes[3].toInt() and 0xFF)
                                    val payload = rawBytes.asByteArray().copyOfRange(4, rawBytes.size)

                                    if (msg.packet != null) {
                                        // Known endpoint - always forward
                                        logger.i("<<< Received message on endpoint 0x${endpoint.toString(16)}, ${payload.size} bytes")
                                        handleEndpointMessage(endpoint, payload)
                                    } else if (allowUnknownEndpoints) {
                                        // Unknown endpoint - only forward if opted in
                                        logger.i("<<< Received raw message on unknown endpoint 0x${endpoint.toString(16)}, ${payload.size} bytes")
                                        handleEndpointMessage(endpoint, payload)
                                    } else {
                                        logger.d("Dropping unknown endpoint 0x${endpoint.toString(16)}, ${payload.size} bytes")
                                    }
                                } catch (e: Exception) {
                                    logger.w("Failed to process inbound message: ${e.message}", e)
                                }
                            }
                        } catch (e: Exception) {
                            logger.e("Message collection failed: ${e.message}", e)
                        }
                        logger.d("Message collector coroutine ended")
                    }
                    logger.i("Endpoint message handler registered")
                } else {
                    logger.w("PebbleProtocolStreams not available")
                }

                // Monitor for disconnection
                globalScope.launch {
                    transportConnector?.disconnected?.await()
                    logger.i("Disconnected")
                    try {
                        nativeOnDisconnected()
                    } catch (e: Exception) {
                        logger.w("Native callback failed: ${e.message}")
                    }
                }

                true
            } else {
                logger.e("Connection failed")
                false
            }
        } catch (e: Exception) {
            logger.e("Connection error: ${e.message}", e)
            false
        }
    }

    /**
     * Disconnect from Pebble and cleanup.
     */
    fun disconnect() = runBlocking {
        logger.i("Disconnecting...")
        transportConnector?.disconnect()
        connectionScope?.close()
        stopKoin()
    }

    /**
     * Send message to endpoint.
     *
     * @param endpoint Endpoint number (e.g., 0x0000 for meta protocol)
     * @param data Message data
     * @return true if sent successfully
     */
    fun sendMessage(endpoint: Int, data: ByteArray): Boolean {
        logger.d("sendMessage: endpoint=0x${endpoint.toString(16)}, size=${data.size}")
        // TODO: Implement message sending via PebbleProtocolRunner
        logger.w("sendMessage not yet implemented")
        return false
    }

    /**
     * Enable or disable forwarding of messages on unknown/unregistered endpoints.
     * By default, only known Pebble protocol endpoints are forwarded.
     *
     * @param allow true to forward unknown endpoint messages to the native callback
     */
    fun setAllowUnknownEndpoints(allow: Boolean) {
        logger.i("Allow unknown endpoints: $allow")
        allowUnknownEndpoints = allow
    }

    /**
     * Test method to receive endpoint messages.
     * This is called internally for testing, but will be wired to C++ callbacks.
     */
    internal fun handleEndpointMessage(endpoint: Int, data: ByteArray) {
        logger.d("Received message on endpoint 0x${endpoint.toString(16)}, size=${data.size}")
        try {
            nativeOnEndpointMessage(endpoint, data)
        } catch (e: Exception) {
            logger.w("Native callback failed: ${e.message}")
        }
    }

    companion object {
        // Note: Native methods are registered by C++ code via RegisterNatives()
        // No System.loadLibrary() needed - methods are in the executable
    }
}

/**
 * Bridge-specific Koin module.
 */
private fun bridgeModule(globalScope: CoroutineScope, devicePath: String) = module {
    // Config flow
    single<kotlinx.coroutines.flow.StateFlow<LibPebbleConfig>> {
        MutableStateFlow(LibPebbleConfig())
    }

    single {
        BleConfigFlow(get())
    }

    // Connection scope components
    scope<ConnectionScope> {
        // Override UART device with custom path
        scoped {
            io.rebble.libpebblecommon.connection.uart.UartDevice(devicePath)
        }

        scoped {
            ConnectionCoroutineScope(globalScope.coroutineContext)
        }

        // Provide a simple identifier for UART connection
        scoped<io.rebble.libpebblecommon.connection.PebbleIdentifier> {
            io.rebble.libpebblecommon.connection.PebbleSocketIdentifier("uart://$devicePath")
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
                pPoGPacketSender = get(),
                bleConfig = get(),
                blePlatformConfig = get(),
                scope = get()
            )
        }

        scoped {
            PebbleProtocolRunner(
                pebbleProtocolStreams = get(),
                identifier = get()
            )
        }

        // Register PebbleProtocolHandler
        scoped<io.rebble.libpebblecommon.connection.PebbleProtocolHandler> {
            io.rebble.libpebblecommon.connection.RealPebbleProtocolHandler(
                pebbleProtocolStreams = get(),
                connectionScope = get(),
                identifier = get()
            )
        }
    }
}
