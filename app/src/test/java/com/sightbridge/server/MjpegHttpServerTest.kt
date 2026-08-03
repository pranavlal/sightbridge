package com.sightbridge.server

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class MjpegHttpServerTest {

    @Test
    fun testServerLifecycleAndConnection() {
        val server = MjpegHttpServer(port = 8899, bindToLocalhostOnly = true, maxClients = 2)
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
        val server = MjpegHttpServer(port = 8898, bindToLocalhostOnly = true, maxClients = 1)
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
}
