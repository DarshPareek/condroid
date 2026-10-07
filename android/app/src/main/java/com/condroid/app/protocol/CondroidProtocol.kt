package com.condroid.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object CondroidProtocol {
    const val PROTOCOL_MAGIC: Short = 0x4344.toShort()
    const val PROTOCOL_VERSION: Byte = 1
    const val STATE_PACKET_SIZE = 31
    const val PING_PACKET_SIZE = 12
    const val PONG_PACKET_SIZE = 16
    const val RUMBLE_PACKET_SIZE = 8
    const val SLOT_INFO_PACKET_SIZE = 6

    const val TYPE_STATE: Byte = 1
    const val TYPE_PING: Byte = 2
    const val TYPE_PONG: Byte = 3
    const val TYPE_RUMBLE: Byte = 4
    const val TYPE_DISCONNECT: Byte = 5
    const val TYPE_SLOT_INFO: Byte = 6


    /**
     * Compute CRC8 checksum matching host implementation
     */
    fun computeChecksum(buf: ByteArray, length: Int): Byte {
        var crc = 0xFF
        for (i in 0 until length) {
            crc = crc xor (buf[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc.toByte()
    }

    /**
     * Serializes ControllerState into a pre-allocated byte buffer for zero GC allocations.
     */
    fun serializeState(state: ControllerState, out: ByteArray): Int {
        if (out.size < STATE_PACKET_SIZE) return 0
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        bb.putShort(PROTOCOL_MAGIC)
        bb.put(PROTOCOL_VERSION)
        bb.put(TYPE_STATE)
        bb.putInt(state.seq)
        bb.putInt(state.timestampMs)
        bb.putShort(state.buttons.toShort())
        bb.put(state.leftTrigger.toByte())
        bb.put(state.rightTrigger.toByte())
        bb.putShort(state.leftStickX)
        bb.putShort(state.leftStickY)
        bb.putShort(state.rightStickX)
        bb.putShort(state.rightStickY)
        bb.putShort(state.gyroX)
        bb.putShort(state.gyroY)
        bb.putShort(state.gyroZ)

        val checksum = computeChecksum(out, 30)
        out[30] = checksum

        return STATE_PACKET_SIZE
    }

    /**
     * Serializes a Ping packet
     */
    fun serializePing(seq: Int, timestampMs: Int, out: ByteArray): Int {
        if (out.size < PING_PACKET_SIZE) return 0
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(PROTOCOL_MAGIC)
        bb.put(PROTOCOL_VERSION)
        bb.put(TYPE_PING)
        bb.putInt(seq)
        bb.putInt(timestampMs)
        return PING_PACKET_SIZE
    }

    /**
     * Serializes a Disconnect packet
     */
    fun serializeDisconnect(out: ByteArray): Int {
        if (out.size < 4) return 0
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(PROTOCOL_MAGIC)
        bb.put(PROTOCOL_VERSION)
        bb.put(TYPE_DISCONNECT)
        return 4
    }

    data class RumbleEvent(val weakMagnitude: Int, val strongMagnitude: Int, val durationMs: Int)

    fun parseRumble(buf: ByteArray, length: Int): RumbleEvent? {
        if (length < RUMBLE_PACKET_SIZE) return null
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.short
        if (magic != PROTOCOL_MAGIC) return null
        val version = bb.get()
        if (version != PROTOCOL_VERSION) return null
        val type = bb.get()
        if (type != TYPE_RUMBLE) return null

        val weak = bb.get().toInt() and 0xFF
        val strong = bb.get().toInt() and 0xFF
        val duration = bb.short.toInt() and 0xFFFF
        return RumbleEvent(weak, strong, duration)
    }

    data class PongEvent(val seq: Int, val clientTimestampMs: Int, val serverTimestampMs: Int)

    fun parsePong(buf: ByteArray, length: Int): PongEvent? {
        if (length < PONG_PACKET_SIZE) return null
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.short
        if (magic != PROTOCOL_MAGIC) return null
        val version = bb.get()
        if (version != PROTOCOL_VERSION) return null
        val type = bb.get()
        if (type != TYPE_PONG) return null

        val seq = bb.int
        val clientTs = bb.int
        val serverTs = bb.int
        return PongEvent(seq, clientTs, serverTs)
    }

    data class SlotInfoEvent(val playerSlot: Int, val totalSlots: Int)

    fun parseSlotInfo(buf: ByteArray, length: Int): SlotInfoEvent? {
        if (length < SLOT_INFO_PACKET_SIZE) return null
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.short
        if (magic != PROTOCOL_MAGIC) return null
        val version = bb.get()
        if (version != PROTOCOL_VERSION) return null
        val type = bb.get()
        if (type != TYPE_SLOT_INFO) return null

        val slot = bb.get().toInt() and 0xFF
        val total = bb.get().toInt() and 0xFF
        return SlotInfoEvent(slot, total)
    }
}
