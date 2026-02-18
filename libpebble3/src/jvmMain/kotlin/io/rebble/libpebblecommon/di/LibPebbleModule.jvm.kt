package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.uart.PebbleUart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Platform-specific module for jvmMain (UART mode).
 * Provides minimal stubs and UART-specific implementations.
 */
actual val platformModule: Module = module {
    // UART-specific BlePlatformConfig
    // Even though UART isn't BLE, PPoG still uses this config
    single {
        BlePlatformConfig(
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
    }

    // Bluetooth state stub (always enabled for UART mode)
    single {
        object : BluetoothStateProvider {
            private val _state = MutableStateFlow(BluetoothState.Enabled)
            override val state: StateFlow<BluetoothState> = _state
            override fun init() {
                // UART doesn't need BT initialization
            }
        }
    } bind BluetoothStateProvider::class

    // In ConnectionScope: provide UART-specific implementations
    scope<ConnectionScope> {
        // Create UART device (shared between sender and transport)
        scoped {
            io.rebble.libpebblecommon.connection.uart.UartDevice(
                devicePath = "/dev/ttyS2"  // TODO: make configurable
            )
        }

        // Create UART packet sender (wraps the device)
        scoped<PPoGPacketSender> {
            io.rebble.libpebblecommon.connection.uart.UartPPoGPacketSender(
                uart = get()  // Get the UartDevice
            )
        }

        // Use PebbleUart as the transport connector
        scoped<TransportConnector> {
            PebbleUart(
                uartDevice = get(),  // Get the UartDevice
                scope = get(),
                ppog = get(),
                pPoGStream = get()
            )
        }
    }
}
