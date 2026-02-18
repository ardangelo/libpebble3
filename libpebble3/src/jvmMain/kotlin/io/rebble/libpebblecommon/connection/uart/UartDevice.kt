package io.rebble.libpebblecommon.connection.uart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * UART device abstraction for jvmMain.
 * Uses FileInputStream/OutputStream for UART I/O and stty for configuration.
 *
 * @param devicePath Path to UART device (e.g., "/dev/ttyS2")
 */
class UartDevice(private val devicePath: String) : AutoCloseable {
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    /**
     * Open and configure UART device.
     *
     * Uses stty to configure:
     * - 115200 baud
     * - 8N1 (8 data bits, no parity, 1 stop bit)
     * - Raw mode (no line processing)
     * - No echo
     * - No flow control
     */
    suspend fun open() = withContext(Dispatchers.IO) {
        // Configure UART with stty (alternative to termios JNI)
        val sttyResult = ProcessBuilder(
            "stty", "-F", devicePath,
            "115200",        // Baud rate
            "raw",           // Raw mode
            "-echo",         // No echo
            "-echoe",        // No erase echo
            "-echok"         // No kill echo
        ).start()

        val exitCode = sttyResult.waitFor()
        if (exitCode != 0) {
            throw IOException("stty configuration failed with exit code $exitCode")
        }

        // Open as file streams
        inputStream = FileInputStream(devicePath)
        outputStream = FileOutputStream(devicePath)
    }

    /**
     * Read one byte from UART.
     * Blocks until data is available.
     *
     * @return Byte read from UART
     * @throws IOException if UART is not open or EOF is reached
     */
    suspend fun read(): Byte = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IOException("UART not open")
        val byte = stream.read()
        if (byte == -1) throw IOException("UART EOF")
        byte.toByte()
    }

    /**
     * Write data to UART.
     * Flushes after writing to ensure data is sent immediately.
     *
     * @param data Data to write
     * @throws IOException if UART is not open
     */
    suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("UART not open")
        stream.write(data)
        stream.flush()
    }

    /**
     * Close UART device.
     */
    override fun close() {
        inputStream?.close()
        outputStream?.close()
        inputStream = null
        outputStream = null
    }
}
