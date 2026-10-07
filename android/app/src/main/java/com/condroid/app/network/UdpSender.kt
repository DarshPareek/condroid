@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.condroid.app.network

import android.os.Process
import android.os.SystemClock
import com.condroid.app.haptics.HapticEngine
import com.condroid.app.protocol.CondroidProtocol
import com.condroid.app.protocol.ControllerState
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

enum class TransportMode {
    AUTO, UDP, TCP
}

class UdpSender(
    private val hapticEngine: HapticEngine,
    private val onTelemetryUpdate: (rttMs: Float, sendRateHz: Float) -> Unit
) {
    var onSlotAssigned: ((slot: Int, totalSlots: Int) -> Unit)? = null

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null
    private var tcpIn: InputStream? = null

    private var hostAddress: InetAddress? = null
    private var hostPort: Int = 8448
    private var isTcpActive = false

    private val isRunning = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private var receiverThread: Thread? = null

    private val stateLock = Any()
    private val currentState = ControllerState()
    private var isDirty = false

    private val sendBuffer = ByteArray(64)
    private val pingBuffer = ByteArray(32)
    private val recvBuffer = ByteArray(64)

    private var sequenceNumber = 1
    private var targetIntervalNanos = 1_000_000_000L / 120L // Default 120 Hz polling rate

    fun connect(host: String, port: Int, targetRateHz: Int = 120, mode: TransportMode = TransportMode.AUTO) {
        disconnect()
        hostPort = port
        targetIntervalNanos = 1_000_000_000L / targetRateHz.coerceIn(60, 500)

        // If host is 127.0.0.1 (ADB reverse tunnel), always use TCP because ADB does not support UDP
        val useTcp = when (mode) {
            TransportMode.TCP -> true
            TransportMode.UDP -> false
            TransportMode.AUTO -> (host == "127.0.0.1" || host == "localhost")
        }

        Thread {
            try {
                hostAddress = InetAddress.getByName(host)
                isTcpActive = useTcp

                if (useTcp) {
                    val s = Socket(host, port)
                    s.tcpNoDelay = true // Disable Nagle's algorithm for sub-ms packet dispatch
                    s.sendBufferSize = 64 * 1024
                    s.soTimeout = 3000
                    tcpSocket = s
                    tcpOut = s.getOutputStream()
                    tcpIn = s.getInputStream()
                } else {
                    val s = DatagramSocket()
                    try {
                        s.trafficClass = 0x10 // IPTOS_LOWDELAY
                        s.sendBufferSize = 64 * 1024
                        s.soTimeout = 2000
                    } catch (_: Exception) {}
                    udpSocket = s
                }

                isRunning.set(true)
                startSenderLoop()
                startReceiverLoop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun disconnect() {
        if (!isRunning.getAndSet(false)) return

        // Send disconnect packet
        try {
            val len = CondroidProtocol.serializeDisconnect(sendBuffer)
            if (isTcpActive) {
                tcpOut?.write(sendBuffer, 0, len)
                tcpOut?.flush()
            } else {
                val s = udpSocket
                val addr = hostAddress
                if (s != null && addr != null) {
                    val packet = DatagramPacket(sendBuffer, len, addr, hostPort)
                    s.send(packet)
                }
            }
        } catch (_: Exception) {}

        try { tcpSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        tcpSocket = null
        tcpOut = null
        tcpIn = null
        udpSocket = null

        senderThread?.interrupt()
        receiverThread?.interrupt()
        senderThread = null
        receiverThread = null
    }

    fun isConnected(): Boolean = isRunning.get() && (udpSocket != null || tcpSocket != null)

    fun updateState(block: (ControllerState) -> Unit) {
        synchronized(stateLock) {
            block(currentState)
            isDirty = true
            (stateLock as java.lang.Object).notifyAll()
        }
    }

    private fun startSenderLoop() {
        senderThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val localState = ControllerState()
            var lastSendTime = System.nanoTime()
            var packetsSent = 0L
            var lastStatsTime = SystemClock.elapsedRealtime()
            var lastPingTime = 0L
            var pingSeq = 1

            while (isRunning.get()) {
                val now = System.nanoTime()
                val nowMs = SystemClock.elapsedRealtime()

                var shouldSend = false
                synchronized(stateLock) {
                    if (isDirty || (now - lastSendTime) >= targetIntervalNanos) {
                        localState.copyFrom(currentState)
                        localState.seq = sequenceNumber++
                        localState.timestampMs = (nowMs and 0x7FFFFFFF).toInt()
                        isDirty = false
                        shouldSend = true
                    } else {
                        val waitNanos = targetIntervalNanos - (now - lastSendTime)
                        if (waitNanos > 1_000_000L) {
                            try {
                                (stateLock as java.lang.Object).wait(waitNanos / 1_000_000L)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                    }
                }

                if (shouldSend) {
                    try {
                        val len = CondroidProtocol.serializeState(localState, sendBuffer)
                        if (isTcpActive) {
                            tcpOut?.write(sendBuffer, 0, len)
                            tcpOut?.flush()
                            lastSendTime = System.nanoTime()
                            packetsSent++
                        } else {
                            val s = udpSocket
                            val addr = hostAddress
                            if (s != null && addr != null && len > 0) {
                                val packet = DatagramPacket(sendBuffer, len, addr, hostPort)
                                s.send(packet)
                                lastSendTime = System.nanoTime()
                                packetsSent++
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Send periodic Ping (every 500ms) to measure RTT
                if (nowMs - lastPingTime >= 500L) {
                    lastPingTime = nowMs
                    try {
                        val pLen = CondroidProtocol.serializePing(pingSeq++, (nowMs and 0x7FFFFFFF).toInt(), pingBuffer)
                        if (isTcpActive) {
                            tcpOut?.write(pingBuffer, 0, pLen)
                            tcpOut?.flush()
                        } else {
                            val s = udpSocket
                            val addr = hostAddress
                            if (s != null && addr != null) {
                                s.send(DatagramPacket(pingBuffer, pLen, addr, hostPort))
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Periodic telemetry reporting
                if (nowMs - lastStatsTime >= 1000L) {
                    val rateHz = (packetsSent * 1000f) / (nowMs - lastStatsTime)
                    packetsSent = 0L
                    lastStatsTime = nowMs
                    onTelemetryUpdate(-1f, rateHz)
                }
            }
        }.apply {
            name = "CondroidSender"
            start()
        }
    }

    private fun startReceiverLoop() {
        receiverThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (isRunning.get()) {
                try {
                    val len = if (isTcpActive) {
                        val input = tcpIn ?: break
                        // Read 4-byte header
                        val readHdr = input.read(recvBuffer, 0, 4)
                        if (readHdr < 4) break
                        val magic = ((recvBuffer[1].toInt() and 0xFF) shl 8) or (recvBuffer[0].toInt() and 0xFF)
                        if (magic != 0x4344) break
                        val type = recvBuffer[3].toInt()
                        val expectedLen = when (type) {
                            1 -> CondroidProtocol.STATE_PACKET_SIZE
                            2 -> CondroidProtocol.PING_PACKET_SIZE
                            3 -> CondroidProtocol.PONG_PACKET_SIZE
                            4 -> CondroidProtocol.RUMBLE_PACKET_SIZE
                            6 -> CondroidProtocol.SLOT_INFO_PACKET_SIZE
                            else -> 4
                        }
                        val remaining = expectedLen - 4
                        if (remaining > 0) {
                            var totalRead = 0
                            while (totalRead < remaining) {
                                val r = input.read(recvBuffer, 4 + totalRead, remaining - totalRead)
                                if (r < 0) break
                                totalRead += r
                            }
                        }
                        expectedLen
                    } else {
                        val s = udpSocket ?: break
                        val packet = DatagramPacket(recvBuffer, recvBuffer.size)
                        s.receive(packet)
                        packet.length
                    }

                    // Check for SlotInfo packet
                    val slotInfo = CondroidProtocol.parseSlotInfo(recvBuffer, len)
                    if (slotInfo != null) {
                        onSlotAssigned?.invoke(slotInfo.playerSlot, slotInfo.totalSlots)
                        continue
                    }

                    // Check for Pong packet
                    val pong = CondroidProtocol.parsePong(recvBuffer, len)
                    if (pong != null) {
                        val nowMs = (SystemClock.elapsedRealtime() and 0x7FFFFFFF).toInt()
                        val rtt = (nowMs - pong.clientTimestampMs).toFloat().coerceAtLeast(0f)
                        onTelemetryUpdate(rtt, -1f)
                        continue
                    }

                    // Check for Rumble packet
                    val rumble = CondroidProtocol.parseRumble(recvBuffer, len)
                    if (rumble != null) {
                        hapticEngine.rumble(rumble.weakMagnitude, rumble.strongMagnitude, rumble.durationMs)
                        continue
                    }
                } catch (_: Exception) {}
            }
        }.apply {
            name = "CondroidReceiver"
            start()
        }
    }
}
