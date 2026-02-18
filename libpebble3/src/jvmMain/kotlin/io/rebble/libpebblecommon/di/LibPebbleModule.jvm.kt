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
        // Use PebbleUart as the transport connector
        scoped<TransportConnector> {
            val uart = PebbleUart(
                devicePath = "/dev/ttyS2",  // TODO: make configurable
                scope = get(),
                ppog = get(),
                pPoGStream = get()
            )
            uart
        }

        // Use UartPPoGPacketSender as the packet sender
        scoped<PPoGPacketSender> {
            // Get the PebbleUart instance and return its sender
            val uart = get<TransportConnector>() as PebbleUart
            uart.sender ?: error("UART sender not initialized - call connect() first")
        }
    }
}
