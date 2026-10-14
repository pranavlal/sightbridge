package com.sightbridge.server

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class MjpegHttpServerTest {

    @Test
    fun testServerLifecycleAndConnection() {
        val server = MjpegHttpServer(port = 8899, maxClients = 2)
        val startResult = server.start()

        assertTrue("Server should start successfully", startResult.isSuccess)
        assertTrue("isServerRunning should be true", server.isServerRunning())

        // Connect client 1
        val clientSocket1 = Socket("127.0.0.1", 8899)
        assertTrue("Client socket 1 should be connected", clientSocket1.isConnected)

        clientSocket1.close()
        server.stop()
        assertFalse("isServerRunning should be false after stop", server.isServerRunning())
    }

    @Test
    fun testConnectionCapacityLimit() {
        val server = MjpegHttpServer(port = 8898, maxClients = 1)
        server.start()

        // Client 1 connects (fills maxClients = 1 capacity)
        val client1 = Socket("127.0.0.1", 8898)
        assertTrue("Client 1 should connect", client1.isConnected)

        // Client 2 attempts connection (should be rejected)
        val client2 = Socket("127.0.0.1", 8898)
        val input = client2.getInputStream()
        val buffer = ByteArray(256)
        val readBytes = input.read(buffer)

        val response = String(buffer, 0, readBytes.coerceAtLeast(0))
        assertTrue("Response should contain HTTP 503", response.contains("503 Service Unavailable"))

        client1.close()
        client2.close()
        server.stop()
    }

    @Test
    fun testRejectsUnknownHttpPath() {
        val server = MjpegHttpServer(port = 8897)
        assertTrue(server.start().isSuccess)
        val client = Socket("127.0.0.1", 8897).apply { soTimeout = 2000 }
        client.getOutputStream().apply {
            write("GET /not-the-stream HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            flush()
        }
        val response = client.getInputStream().bufferedReader().readLine()
        assertTrue(response.contains("404 Not Found"))
        client.close()
        server.stop()
    }

    @Test
    fun testLegacyStreamPathStillWorks() {
        val server = MjpegHttpServer(port = 8894)
        assertTrue(server.start().isSuccess)
        try {
            Socket("localhost", 8894).use { client ->
                client.soTimeout = 2000
                client.getOutputStream().apply {
                    write("GET /live.mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    flush()
                }
                assertTrue(client.getInputStream().bufferedReader().readLine().contains("200 OK"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun testDisconnectedClientReleasesCapacity() {
        val server = MjpegHttpServer(port = 8896, maxClients = 1)
        assertTrue(server.start().isSuccess)
        val first = Socket("127.0.0.1", 8896).apply { soTimeout = 2000 }
        first.getOutputStream().apply {
            write("GET /mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            flush()
        }
        assertTrue(first.getInputStream().bufferedReader().readLine().contains("200 OK"))
        first.close()

        var response = ""
        for (attempt in 0 until 20) {
            Thread.sleep(25)
            val next = Socket("127.0.0.1", 8896).apply { soTimeout = 2000 }
            next.getOutputStream().apply {
                write("GET /mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            response = next.getInputStream().bufferedReader().readLine()
            next.close()
            if (response.contains("200 OK")) break
        }
        assertTrue("A disconnected client must release its capacity slot", response.contains("200 OK"))
        server.stop()
    }

    @Test
    fun testAlwaysBindsToLoopback() {
        val server = MjpegHttpServer(port = 8895)
        assertTrue(server.start().isSuccess)
        try {
            assertTrue("Camera stream must not listen on the network", server.isLoopbackBound())
        } finally {
            server.stop()
        }
    }
}
