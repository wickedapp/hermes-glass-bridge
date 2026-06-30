package com.wickedapp.rokidtg.voice

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceHelperBridgeTest {
    @Test fun interim_and_final_delivered_in_order() {
        val bridge = VoiceHelperBridge(port = 0) // ephemeral
        val readyL = CountDownLatch(1)
        val finalL = CountDownLatch(1)
        val received = mutableListOf<String>()

        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) { received += "i:$text" }
            override fun onFinal(text: String)   { received += "f:$text"; finalL.countDown() }
            override fun onError(code: String, msg: String) {}
            override fun onTimeout(stage: String) {}
        })

        val client = object : WebSocketClient(URI("ws://127.0.0.1:${bridge.boundPort}")) {
            override fun onOpen(h: ServerHandshake) {
                send("""{"type":"ready"}""")
                readyL.countDown()
            }
            override fun onMessage(m: String?) {}
            override fun onClose(c: Int, r: String?, remote: Boolean) {}
            override fun onError(e: Exception?) {}
        }
        client.connectBlocking(2, TimeUnit.SECONDS)
        assertTrue(readyL.await(2, TimeUnit.SECONDS))

        client.send("""{"type":"interim","text":"你"}""")
        client.send("""{"type":"interim","text":"你好"}""")
        client.send("""{"type":"final","text":"你好世界"}""")
        assertTrue(finalL.await(3, TimeUnit.SECONDS))

        assertEquals(listOf("i:你", "i:你好", "f:你好世界"), received)
        bridge.close()
    }
}
