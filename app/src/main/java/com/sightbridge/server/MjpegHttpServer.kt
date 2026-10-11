package com.sightbridge.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

/**
 * High-performance HTTP MJPEG Server for streaming camera frames over HTTP /mjpeg.
 * Fully thread-safe using Kotlin Coroutines on Dispatchers.IO.
 * Enforces active client limits (maxClients = 10) atomically to prevent connection exhaustion.
 */
class MjpegHttpServer(
    val port: Int = 8080,
    @Volatile var bindToLocalhostOnly: Boolean = true,
    private val maxClients: Int = 10,
    private val accessToken: String? = null
) {

    private val tag = "MjpegHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private data class Client(
        val socket: Socket,
        val output: OutputStream,
        val frames: Channel<ByteArray>,
        val writer: Job
    )

    private val clients = CopyOnWriteArrayList<Client>()
    private val activeConnections = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null

    init {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(maxClients > 0) { "maxClients must be positive" }
        require(bindToLocalhostOnly || !accessToken.isNullOrBlank()) { "LAN binding requires an access token" }
    }

    fun start(): Result<Unit> {
        if (isRunning) return Result.success(Unit)

        return try {
            val bindAddress = if (bindToLocalhostOnly) {
                InetAddress.getByName("127.0.0.1")
            } else {
                InetAddress.getByName("0.0.0.0")
            }

            val socket = ServerSocket(port, 50, bindAddress)
            serverSocket = socket
            isRunning = true

            Log.i(tag, "MJPEG Server running on ${bindAddress.hostAddress}:$port (LocalhostOnly=$bindToLocalhostOnly)")

            acceptJob = scope.launch {
                while (isRunning && isActive) {
                    try {
                        val clientSocket = socket.accept()
                        val accepted = activeConnections.incrementAndGet() <= maxClients

                        if (accepted) {
                            launch {
                                handleClient(clientSocket)
                            }
                        } else {
                            activeConnections.decrementAndGet()
                            Log.w(tag, "Max clients ($maxClients) reached. Rejecting connection from ${clientSocket.remoteSocketAddress}")
                            rejectClientConnection(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(tag, "Accept loop exception: ${e.message}")
                        }
                        break
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            isRunning = false
            Log.e(tag, "Failed to start MJPEG Server on port $port: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun rejectClientConnection(socket: Socket) {
        try {
            val outputStream = socket.getOutputStream()
            val header = ("HTTP/1.1 503 Service Unavailable\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: text/plain\r\n\r\n" +
                    "Maximum client connection capacity reached.\r\n")
            outputStream.write(header.toByteArray(Charsets.US_ASCII))
            outputStream.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: Socket) {
        var client: Client? = null
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            socket.keepAlive = true
            socket.tcpNoDelay = true
            val inputStream = socket.getInputStream()
            val request = readRequestHeader(inputStream)
            val requestLine = request.lineSequence().firstOrNull().orEmpty()
            val requestTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val path = requestTarget.substringBefore('?')
            if (!requestLine.startsWith("GET ") || (path != ENDPOINT_PATH && path != LEGACY_ENDPOINT_PATH)) {
                writeSimpleResponse(socket, "404 Not Found", "MJPEG endpoint is $ENDPOINT_PATH")
                return
            }
            if (!bindToLocalhostOnly && !hasValidToken(requestTarget)) {
                writeSimpleResponse(socket, "401 Unauthorized", "A valid stream access token is required")
                return
            }

            socket.soTimeout = 0
            val outputStream = socket.getOutputStream()
            
            val header = ("HTTP/1.1 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Max-Age: 0\r\n" +
                    "Expires: 0\r\n" +
                    "Cache-Control: no-cache, private, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")

            outputStream.write(header.toByteArray(Charsets.US_ASCII))
            outputStream.flush()

            val queue = Channel<ByteArray>(Channel.CONFLATED)
            val writer = scope.launch {
                try {
                    for (jpegBytes in queue) writeFrame(outputStream, jpegBytes)
                } catch (e: java.io.IOException) {
                    // A peer can disconnect while a frame is being written.
                    Log.d(tag, "Stream client disconnected during write: ${e.message}")
                } finally {
                    runCatching { socket.close() } // wake the reader and release the client slot
                }
            }
            client = Client(socket, outputStream, queue, writer)
            clients.add(client)
            Log.i(tag, "Client connected: ${socket.remoteSocketAddress}. Active clients: ${clients.size}")

            // A concurrent read reliably observes EOF/reset when the peer disconnects.
            while (isRunning && inputStream.read() >= 0) Unit
        } catch (e: Exception) {
            Log.d(tag, "Client disconnected: ${e.message}")
        } finally {
            client?.let {
                clients.remove(it)
                it.frames.close()
                it.writer.cancel()
            }
            try {
                socket.close()
            } catch (_: Exception) {}
            activeConnections.decrementAndGet()
        }
    }

    private fun readRequestHeader(input: java.io.InputStream): String {
        val bytes = ArrayList<Byte>()
        var trailing = 0
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            bytes.add(value.toByte())
            trailing = when {
                trailing == 0 && value == '\r'.code -> 1
                trailing == 1 && value == '\n'.code -> 2
                trailing == 2 && value == '\r'.code -> 3
                trailing == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (trailing == 4) return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        throw IllegalArgumentException("Incomplete or oversized HTTP request header")
    }

    private fun writeSimpleResponse(socket: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun hasValidToken(requestTarget: String): Boolean {
        val supplied = requestTarget.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?: return false
        return java.security.MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            accessToken.orEmpty().toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Broadcast a JPEG frame to all connected MJPEG HTTP clients.
     */
    fun pushJpegFrame(jpegBytes: ByteArray) {
        if (!isRunning || clients.isEmpty()) return
        clients.forEach { it.frames.trySend(jpegBytes) }
    }

    private fun writeFrame(stream: OutputStream, jpegBytes: ByteArray) {
        val frameHeader = ("--frame\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        stream.write(frameHeader)
        stream.write(jpegBytes)
        stream.write("\r\n".toByteArray(Charsets.US_ASCII))
        stream.flush()
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        acceptJob = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        // Explicitly close all connected client sockets
        for (client in clients) {
            client.frames.close()
            client.writer.cancel()
            try {
                client.socket.close()
            } catch (_: Exception) {}
        }
        clients.clear()

        Log.i(tag, "MJPEG Server stopped.")
    }

    fun isServerRunning(): Boolean = isRunning

    companion object {
        const val ENDPOINT_PATH = "/mjpeg"
        private const val LEGACY_ENDPOINT_PATH = "/live.mjpeg"
        private const val MAX_HEADER_BYTES = 8192
        private const val REQUEST_TIMEOUT_MS = 5000
        /**
         * Discovers local IPv4 address across all active non-loopback network interfaces.
         */
        fun getPhoneIpAddress(context: Context): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue

                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}
