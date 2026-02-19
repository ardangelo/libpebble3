package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.*

/**
 * K230 Cyberdeck endpoint packets.
 *
 * Protocol format: [channel:1 byte][payload:N bytes]
 * Channels define logical communication streams for different purposes.
 */
sealed class K230Packet : PebblePacket(ProtocolEndpoint.K230_MESSAGE)

/**
 * Incoming packet from K230 processor.
 */
class K230IncomingPacket : K230Packet() {
    val channel = SUByte(m)
    val payload = SBytes(m, 0)  // Variable length payload
}

/**
 * Outgoing packet to K230 processor.
 */
class K230OutgoingPacket(channel: UByte, payload: UByteArray = UByteArray(0)) : K230Packet() {
    val channelField = SUByte(m, channel)
    val payloadField = SBytes(m, payload.size)

    init {
        payloadField.set(payload)
    }
}

fun k230PacketsRegister() {
    PacketRegistry.register(ProtocolEndpoint.K230_MESSAGE) { K230IncomingPacket() }
}
