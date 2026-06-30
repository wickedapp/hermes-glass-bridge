package com.wickedapp.rokidtg.voice

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VoiceHelperBridge(port: Int = 48761) {

    interface Listener {
        fun onInterim(text: String) {}
        fun onFinal(text: String) {}
        fun onError(code: String, msg: String) {}
        fun onTimeout(stage: String) {}
    }

    // For ephemeral port (port=0), find an available port
    private val actualPort = if (port == 0) {
        val socket = java.net.ServerSocket(0)
        val p = socket.localPort
        socket.close()
        p
    } else {
        port
    }

    @Volatile var boundPort: Int = actualPort; private set

    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val listener = AtomicReference<Listener?>(null)
    @Volatile private var readyTimer: ScheduledFuture<*>? = null
    @Volatile private var transcriptTimer: ScheduledFuture<*>? = null

    private val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", actualPort)) {
        override fun onOpen(c: WebSocket, h: ClientHandshake?) {}
        override fun onClose(c: WebSocket?, code: Int, r: String?, remote: Boolean) {}
        override fun onMessage(c: WebSocket, msg: String) {
            val l = listener.get() ?: return
            val o = runCatching { JSONObject(msg) }.getOrNull() ?: return
            when (o.optString("type")) {
                "ready" -> { readyTimer?.cancel(false); armTranscriptTimer() }
                "interim" -> l.onInterim(o.optString("text"))
                "final"   -> { transcriptTimer?.cancel(false); l.onFinal(o.optString("text")) }
                "error"   -> l.onError(o.optString("code"), o.optString("msg"))
            }
        }
        override fun onError(c: WebSocket?, e: Exception) { Timber.e(e, "ws err") }
        override fun onStart() { boundPort = address.port }
    }

    init {
        server.isReuseAddr = true
        server.start()
    }

    fun start(listener: Listener) {
        this.listener.set(listener)
        readyTimer = timers.schedule({
            this.listener.get()?.onTimeout("ready"); cancel()
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun armTranscriptTimer() {
        transcriptTimer = timers.schedule({
            listener.get()?.onTimeout("transcript"); cancel()
        }, 8000, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        readyTimer?.cancel(false); transcriptTimer?.cancel(false)
        listener.set(null)
        server.connections.forEach { it.send("""{"type":"close"}""") }
    }

    fun close() {
        cancel(); timers.shutdownNow(); runCatching { server.stop(500) }
    }
}
