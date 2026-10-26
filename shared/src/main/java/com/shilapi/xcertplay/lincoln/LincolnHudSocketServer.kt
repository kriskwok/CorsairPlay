package com.shilapi.xcertplay.lincoln

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Baidu NaviSDK HUD socket the working build also serves, next to the navigation broadcasts.
 *
 * On this head unit the 小度车载OS HUD side can take its guidance from the navigation app's socket
 * instead of from a broadcast: it connects to one of [PORTS] on the loopback address, authenticates
 * with [AUTH_OK], and then receives framed JSON (`0xCC` style 4-byte length + body + CRLF) that
 * carries the maneuver (type 100) and the current road (type 104). DiPlay answers as if it were the
 * stock navigation app, so a cluster or HUD that never sees the broadcasts still gets the card.
 *
 * The extra `MSG_PING`/`MSG_AUTH` frames the client sends are answered with `MSG_PONG` and the
 * authentication reply, and a client that authenticates is replayed the current maneuver so it does
 * not stay blank until the next turn.
 *
 * The ports belong to the car: whatever cannot be bound is skipped and retried. The socket is
 * offered for the whole CarPlay session ([start] on connect, [stop] when the session drops), because
 * the cluster's navigation client connects on its own retry schedule and a client that only arrives
 * after the cluster's navigation view opened draws an empty "0 m" card until it is authenticated.
 * [end] therefore only tells the clients that the route finished; the server keeps serving so the
 * next route in the same drive reuses the connection.
 */
object LincolnHudSocketServer : LincolnHudSocket {
    private const val TAG = "DiPlay-Lincoln-Hud"
    private const val HOST = "127.0.0.1"
    private const val AUTH_OK = "BaiduNavi_LOVE_U"
    private const val SERVER_TYPE = 1
    private const val SERVER_VERSION = "1.0"

    internal const val MSG_PING = 10
    internal const val MSG_AUTH = 11
    internal const val MSG_AUTH_ACK = 12
    internal const val MSG_PONG = 14
    internal const val MSG_MANEUVER = 100
    internal const val MSG_ROAD = 104
    internal const val MSG_INIT = 108
    internal const val MSG_END = 109

    /** The port set the HUD side probes; binding a single one is enough for it to find us. */
    private val PORTS = intArrayOf(
        9394, 54321, 54322, 54323, 54324, 54325, 9293, 54326, 54327, 54328, 54329, 54330,
    )

    private const val STRAIGHT_INDEX = 1
    private const val CURRENT_ROAD_FALLBACK = "当前道路"
    private const val MAX_FRAME_BYTES = 262_144
    private const val RX_INIT_BYTES = 8_192
    private const val RX_CHUNK_BYTES = 1_024
    private const val ACCEPT_TIMEOUT_MILLIS = 300
    private const val BIND_RETRY_MILLIS = 500L

    private val msgId = AtomicInteger()
    private val clients = CopyOnWriteArrayList<Client>()
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    @Volatile private var acceptThread: Thread? = null
    /** A new run gets a new accept loop, so the one released by [end] cannot serve it as well. */
    @Volatile private var acceptGeneration = 0
    @Volatile private var lastIndex = LincolnClusterProtocol.NO_TURN_INDEX
    @Volatile private var lastDistanceMeters = 0
    @Volatile private var lastStreet = ""
    // Junction identity for the receiver's progress bar; not a position, never displayed.
    @Volatile private var lastLatitude: Double? = null
    @Volatile private var lastLongitude: Double? = null

    /** True while the socket is offered; the navigation output starts and ends it. */
    @Volatile var running = false
        private set

    override fun start() {
        if (running) return
        running = true
        val generation = ++acceptGeneration
        val thread = Thread({ listenLoop(generation) }, "DiPlayLincolnHudAccept")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        Log.i(TAG, "HUD socket server starting on ports ${PORTS.joinToString(",")}")
    }

    /** Publishes one maneuver to every authenticated client. A blank index sends nothing. */
    override fun update(
        index: Int,
        metres: Int,
        street: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        if (!running || !LincolnClusterProtocol.hasTurnIcon(index)) return
        lastIndex = index
        lastDistanceMeters = metres
        lastStreet = street
        lastLatitude = latitude
        lastLongitude = longitude
        broadcastManeuver()
    }

    /**
     * The route ended: tell the clients and clear the published instruction, but keep the socket
     * offered. The car's navigation client stays connected, so the next route needs no reconnect
     * (and no empty card while it does).
     */
    override fun end() {
        if (!running) return
        lastIndex = LincolnClusterProtocol.NO_TURN_INDEX
        lastDistanceMeters = 0
        lastStreet = ""
        lastLatitude = null
        lastLongitude = null
        Log.i(TAG, "sending nav end to HUD clients")
        runCatching { sendNavEnd() }
    }

    /** The CarPlay session dropped or the output was switched off: end guidance and release. */
    override fun stop() {
        if (!running) return
        end()
        running = false
        release()
    }

    override fun diagnostics(): String =
        "running=$running bound=[${servers.joinToString(",") { it.localPort.toString() }}]" +
            " clients=${clients.size} authed=${clients.count { it.authed }}"

    private fun release() {
        acceptGeneration++
        clients.forEach { it.closeQuietly() }
        clients.clear()
        closeServerSockets()
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "HUD socket server stopped")
    }

    private fun listenLoop(generation: Int) {
        while (running && generation == acceptGeneration) {
            if (servers.isEmpty() && !bindAllPorts()) {
                sleepQuiet(BIND_RETRY_MILLIS)
                continue
            }
            for (server in servers) {
                if (!running || generation != acceptGeneration) return
                try {
                    server.soTimeout = ACCEPT_TIMEOUT_MILLIS
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    val client = Client(socket)
                    clients.add(client)
                    client.startReader()
                    Log.i(TAG, "client connected localPort=${server.localPort} total=${clients.size}")
                } catch (_: SocketTimeoutException) {
                } catch (error: IOException) {
                    if (running) Log.w(TAG, "accept failed port=${server.localPort}", error)
                }
            }
        }
    }

    private fun bindAllPorts(): Boolean {
        val bound = StringBuilder()
        for (port in PORTS) {
            try {
                val server = ServerSocket()
                server.bind(InetSocketAddress(InetAddress.getByName(HOST), port))
                servers.add(server)
                if (bound.isNotEmpty()) bound.append(',')
                bound.append(port)
            } catch (_: IOException) {
                // Taken by the stock navigation app: it is fine to serve only what is free.
            }
        }
        if (servers.isEmpty()) {
            Log.w(TAG, "failed to bind any HUD port")
            return false
        }
        Log.i(TAG, "bound HUD ports: $bound")
        return true
    }

    private fun closeServerSockets() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun broadcastManeuver() {
        val envelope = runCatching {
            envelope(MSG_MANEUVER, maneuverData(lastIndex, lastDistanceMeters, lastStreet, lastLatitude, lastLongitude))
        }
            .getOrElse {
                Log.w(TAG, "maneuver envelope failed", it)
                return
            }
        send(envelope)
        broadcast(MSG_ROAD, roadData(currentRoad(lastStreet)))
    }

    private fun sendNavEnd() {
        broadcast(MSG_MANEUVER, endManeuverData())
        broadcast(MSG_ROAD, roadData(""))
    }

    private fun broadcast(type: Int, data: JSONObject) {
        val envelope = runCatching { envelope(type, data) }.getOrElse {
            Log.w(TAG, "broadcast envelope failed type=$type", it)
            return
        }
        send(envelope)
    }

    private fun send(envelope: JSONObject) {
        clients.forEach { if (it.authed) it.send(envelope) }
    }

    private fun envelope(type: Int, data: JSONObject): JSONObject = JSONObject()
        .put(
            "data",
            JSONObject().put("messageType", type).put("messageData", data),
        )
        .put("error", JSONObject().put("errorCode", 0).put("errorMessage", "ok"))
        .put("msgId", msgId.incrementAndGet())
        .put("sendTime", System.currentTimeMillis())

    /** The maneuver frame the tested build sends: name, distance in metres, roads and a spoken tip. */
    internal fun maneuverData(
        index: Int,
        metres: Int,
        street: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): JSONObject {
        val road = street.trim()
        val currentRoad = currentRoad(road)
        val name = LincolnClusterProtocol.turnName(index).ifEmpty { "turn_front" }
        return JSONObject()
            .put("maneuverName", name)
            .put("straight", index == STRAIGHT_INDEX)
            .put("distance", hudDistance(metres))
            .put("nextRoadName", road)
            .put("currentRoadName", currentRoad)
            .put("maneuverTips", maneuverTips(metres, currentRoad))
            .put("isHighWay", false)
            .put("laneCount", 0)
            .apply {
                // Some receivers take the junction identity from the same message the broadcast
                // adapter reads it from. Send it here too so both paths agree on one identity.
                latitude?.let { put(LincolnClusterProtocol.EXTRA_INFO_LATITUDE, it) }
                longitude?.let { put(LincolnClusterProtocol.EXTRA_INFO_LONGITUDE, it) }
            }
    }

    internal fun endManeuverData(): JSONObject = JSONObject()
        .put("maneuverName", "")
        .put("straight", true)
        .put("distance", 0)
        .put("nextRoadName", "")
        .put("currentRoadName", "")
        .put("isNaviEnd", true)

    internal fun roadData(currentRoad: String): JSONObject = JSONObject().put("currentRoadName", currentRoad)

    private fun currentRoad(street: String): String = street.trim().ifEmpty { CURRENT_ROAD_FALLBACK }

    /** Whole metres close up, then the step the panel's own formatting uses. */
    internal fun hudDistance(metres: Int): Int = when {
        metres < 0 -> 0
        metres < 1_000 -> metres
        metres < 100_000 -> (metres / 100) * 100
        else -> 1_000 * (metres / 1_000)
    }

    internal fun maneuverTips(metres: Int, road: String): String = when {
        metres > 0 && road.isNotEmpty() -> "${metres}米后进入$road"
        metres > 0 -> "${metres}米后"
        else -> "沿$road 行驶"
    }

    /** One HUD client: framed JSON in, framed JSON out, with its own reader thread. */
    private class Client(private val socket: Socket) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private var rxBuffer = ByteArray(RX_INIT_BYTES)
        private var rxLength = 0
        private var reader: Thread? = null

        @Volatile var authed = false
            private set

        fun startReader() {
            val thread = Thread({ readLoop() }, "DiPlayLincolnHudClient")
            thread.isDaemon = true
            reader = thread
            thread.start()
        }

        fun send(envelope: JSONObject) {
            try {
                val payload = envelope.toString().toByteArray(Charsets.UTF_8)
                val frame = ByteArray(payload.size + FRAME_OVERHEAD)
                frame[0] = (payload.size ushr 24).toByte()
                frame[1] = (payload.size ushr 16).toByte()
                frame[2] = (payload.size ushr 8).toByte()
                frame[3] = payload.size.toByte()
                System.arraycopy(payload, 0, frame, 4, payload.size)
                frame[payload.size + 4] = CR
                frame[payload.size + 5] = LF
                synchronized(output) {
                    output.write(frame)
                    output.flush()
                }
            } catch (error: IOException) {
                Log.w(TAG, "send failed", error)
                closeQuietly()
            }
        }

        fun closeQuietly() {
            authed = false
            clients.remove(this)
            runCatching { socket.close() }
        }

        private fun readLoop() {
            val chunk = ByteArray(RX_CHUNK_BYTES)
            try {
                while (running && !socket.isClosed) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    try {
                        appendRx(chunk, read)
                        consumeFrames()
                    } catch (error: Exception) {
                        Log.w(TAG, "readLoop error", error)
                    }
                }
            } catch (error: IOException) {
                // Closing the client on purpose (a route or session end) unblocks this read; that is
                // not worth a stack trace.
                if (running && !socket.isClosed) Log.w(TAG, "read failed", error)
            }
            closeQuietly()
        }

        private fun appendRx(data: ByteArray, count: Int) {
            var total = rxLength + count
            if (total > MAX_FRAME_BYTES) {
                Log.w(TAG, "rx buffer too large, reset")
                rxLength = 0
                total = count
            }
            if (total > rxBuffer.size) {
                var size = rxBuffer.size
                while (size < total) size *= 2
                rxBuffer = rxBuffer.copyOf(minOf(size, MAX_FRAME_BYTES))
            }
            System.arraycopy(data, 0, rxBuffer, rxLength, count)
            rxLength += count
        }

        /** Frames are a 4-byte big-endian length, the body, then CR LF. */
        private fun consumeFrames() {
            var offset = 0
            while (rxLength - offset >= FRAME_OVERHEAD) {
                val size = frameLengthAt(offset)
                if (size < 0 || size > MAX_FRAME_BYTES) {
                    Log.w(TAG, "invalid frame length, reset rx")
                    rxLength = 0
                    return
                }
                val total = size + FRAME_OVERHEAD
                if (rxLength - offset < total) break
                val bodyStart = offset + 4
                if (rxBuffer[bodyStart + size] != CR || rxBuffer[bodyStart + size + 1] != LF) {
                    Log.w(TAG, "frame CRLF mismatch, reset rx")
                    rxLength = 0
                    return
                }
                handleFrame(rxBuffer.copyOfRange(bodyStart, bodyStart + size))
                offset += total
            }
            if (offset > 0) {
                val remaining = rxLength - offset
                if (remaining > 0) System.arraycopy(rxBuffer, offset, rxBuffer, 0, remaining)
                rxLength = remaining
            }
        }

        private fun frameLengthAt(offset: Int): Int =
            ((rxBuffer[offset].toInt() and 0xFF) shl 24) or
                ((rxBuffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((rxBuffer[offset + 2].toInt() and 0xFF) shl 8) or
                (rxBuffer[offset + 3].toInt() and 0xFF)

        private fun handleFrame(body: ByteArray) {
            try {
                val data = JSONObject(String(body, Charsets.UTF_8)).getJSONObject("data")
                when (data.getInt("messageType")) {
                    MSG_PING -> send(envelope(MSG_PONG, JSONObject()))
                    MSG_AUTH -> {
                        val ack = JSONObject()
                            .put("serverType", SERVER_TYPE)
                            .put("serverVersion", SERVER_VERSION)
                            .put("repMsg", AUTH_OK)
                        send(envelope(MSG_AUTH_ACK, ack))
                        authed = true
                        Log.i(TAG, "client authed, replay nav state")
                        replay()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "handleFrame failed", error)
            }
        }

        private fun replay() {
            send(envelope(MSG_INIT, JSONObject()))
            val index = lastIndex
            if (LincolnClusterProtocol.hasTurnIcon(index)) {
                send(
                    envelope(
                        MSG_MANEUVER,
                        maneuverData(index, lastDistanceMeters, lastStreet, lastLatitude, lastLongitude),
                    ),
                )
                send(envelope(MSG_ROAD, roadData(currentRoad(lastStreet))))
            }
        }
    }

    private const val FRAME_OVERHEAD = 6
    private val CR = 13.toByte()
    private val LF = 10.toByte()
}
