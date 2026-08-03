package com.sightbridge.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * High-performance HTTP MJPEG Server for streaming Meta Glasses video feed.
 * Serves a multipart/x-mixed-replace JPEG stream over HTTP on /live.mjpeg.
 *
 * Supports two binding modes:
 * - bindToLocalhostOnly = true  => Binds to 127.0.0.1 (Strictly accessible by local Android apps on phone, e.g. The vOICe)
 * - bindToLocalhostOnly = false => Binds to 0.0.0.0 (Accessible across local Wi-Fi network)
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

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "MjpegServerThread") {
            try {
                val bindAddress = if (bindToLocalhostOnly) {
                    InetAddress.getByName("127.0.0.1")
                } else {
                    InetAddress.getByName("0.0.0.0")
                }

                serverSocket = ServerSocket(port, 50, bindAddress)
                Log.i(tag, "MJPEG Server running on ${bindAddress.hostAddress}:$port (LocalhostOnly=$bindToLocalhostOnly)")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "HttpClientThread") {
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
                // Keep connection open
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
        fun getPhoneIpAddress(context: Context): String? {
            return try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wifiManager.connectionInfo.ipAddress
                if (ip == 0) null else String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
