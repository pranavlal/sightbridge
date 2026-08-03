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

/**
 * High-performance HTTP MJPEG Server for streaming camera frames over HTTP /live.mjpeg.
 * Fully thread-safe using Kotlin Coroutines on Dispatchers.IO.
 */
class MjpegHttpServer(
    val port: Int = 8080,
    @Volatile var bindToLocalhostOnly: Boolean = true
) {

    private val tag = "MjpegHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val bindAddress = if (bindToLocalhostOnly) {
                    InetAddress.getByName("127.0.0.1")
                } else {
                    InetAddress.getByName("0.0.0.0")
                }

                serverSocket = ServerSocket(port, 50, bindAddress)
                Log.i(tag, "MJPEG Server running on ${bindAddress.hostAddress}:$port (LocalhostOnly=$bindToLocalhostOnly)")

                while (isRunning && isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Server socket error: ${e.message}", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
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

            clientStreams.add(outputStream)
            Log.i(tag, "Client connected: ${socket.remoteSocketAddress}. Active streams: ${clientStreams.size}")

            val inputStream = socket.getInputStream()
            val buffer = ByteArray(1024)
            while (isRunning && inputStream.read(buffer) != -1) {
                // Keep connection alive
            }
        } catch (e: Exception) {
            Log.d(tag, "Client disconnected: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Broadcast a JPEG frame to all connected MJPEG HTTP clients.
     */
    fun pushJpegFrame(jpegBytes: ByteArray) {
        if (!isRunning || clientStreams.isEmpty()) return

        val frameHeader = ("--frame\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)

        val frameFooter = "\r\n".toByteArray(Charsets.US_ASCII)
        val deadStreams = mutableListOf<OutputStream>()

        for (stream in clientStreams) {
            try {
                synchronized(stream) {
                    stream.write(frameHeader)
                    stream.write(jpegBytes)
                    stream.write(frameFooter)
                    stream.flush()
                }
            } catch (e: Exception) {
                deadStreams.add(stream)
            }
        }

        if (deadStreams.isNotEmpty()) {
            clientStreams.removeAll(deadStreams)
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
        clientStreams.clear()
        Log.i(tag, "MJPEG Server stopped.")
    }

    fun isServerRunning(): Boolean = isRunning

    companion object {
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
