package com.wickedapp.rokidtg.voice

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket server bridge between the main app and the Sprite Ink voice-helper AIX.
 *
 * Security model (C4):
 * - Uses ephemeral port (port=0) so each app launch binds a fresh random port.
 *   Pass [boundPort] to the helper via Intent extra "port" before launching.
 * - Generates a per-session [sessionNonce] (UUID) on each [start] call.
 *   Pass [sessionNonce] via Intent extra "nonce". The helper must send
 *   {"type":"ready","nonce":"<value>"} and the bridge rejects mismatches.
 * - The WebSocket server is started lazily on [start] and stopped after the
 *   session ends (final / timeout / cancel), so the port is closed between sessions.
 *
 * File-based handshake fallback (if the Sprite SDK cannot read Intent extras):
 * Write {port, nonce} to the app's filesDir before launching the helper and let
 * the helper read it via its file API. The caller (MainActivity / ComposerOverlay)
 * is responsible for this if needed; [boundPort] and [sessionNonce] are public.
 */
class VoiceHelperBridge(port: Int = 0) {

    interface Listener {
        fun onInterim(text: String) {}
        fun onFinal(text: String) {}
        fun onError(code: String, msg: String) {}
        fun onTimeout(stage: String) {}
    }

    // Sprite Ink networking cannot reach Android loopback on this device. Keep the
    // bridge on the verified fixed port and bind all interfaces so the helper can
    // connect through the glasses WLAN/CXR-proxied address.
    private val resolvedPort: Int = if (port == 0) 48761 else port

    /** Port the WebSocket server is (or will be) bound to. */
    val boundPort: Int get() = resolvedPort

    /** Nonce for the current session. Refreshed on each [start] call. */
    @Volatile var sessionNonce: String = ""; private set

    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val listener = AtomicReference<Listener?>(null)
    @Volatile private var readyTimer: ScheduledFuture<*>? = null
    @Volatile private var transcriptTimer: ScheduledFuture<*>? = null
    @Volatile private var lastInterim: String = ""

    @Volatile private var server: WebSocketServer? = null
    @Volatile private var serverStarted = false

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start a new voice-helper session:
     * 1. Generates a fresh [sessionNonce].
     * 2. Starts the WebSocket server if not already running.
     * 3. Arms the ready-timeout timer.
     *
     * The caller should read [boundPort] and [sessionNonce] AFTER calling start()
     * and pass them to the helper process via Intent extras "port" and "nonce".
     */
    fun start(l: Listener) {
        sessionNonce = UUID.randomUUID().toString()
        lastInterim = ""
        listener.set(l)
        ensureServerRunning()
        readyTimer = timers.schedule({
            listener.get()?.onTimeout("ready")
            stopServer()
        }, 10_000, TimeUnit.MILLISECONDS)
    }

    /** Cancel current session and stop the WebSocket server. */
    fun cancel() {
        readyTimer?.cancel(false)
        transcriptTimer?.cancel(false)
        listener.set(null)
        server?.connections?.forEach { it.send("""{"type":"close"}""") }
        stopServer()
    }

    /** Full shutdown — call from activity/service onDestroy. */
    fun close() {
        cancel()
        stopServerInline()   // synchronous stop before killing the executor
        timers.shutdownNow()
    }

    /**
     * Stops the WebSocket server synchronously, inline.
     * Used only from [close] where the timers executor may already be shut down.
     * Normal session-end uses the async [stopServer] path instead.
     */
    private fun stopServerInline() {
        val srv = server ?: return
        server = null
        serverStarted = false
        runCatching { srv.stop(500) }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun ensureServerRunning() {
        if (serverStarted) return
        val srv = object : WebSocketServer(InetSocketAddress("0.0.0.0", resolvedPort)) {
            override fun onOpen(c: WebSocket, h: ClientHandshake?) {
                Timber.tag("Bridge").i("onOpen %s", c.remoteSocketAddress)
                // Rokid Sprite's WebSocket reaches this callback reliably, but on this ROM
                // its first JS send({type:'ready'}) can be lost while the Ink page is still
                // mounting. Treat the socket open itself as the readiness handshake so the
                // user never gets the false "語音助手未就緒" banner after the helper is visibly
                // listening. The explicit ready frame is still accepted below when it arrives.
                readyTimer?.cancel(false)
                transcriptTimer?.cancel(false)
                armTranscriptTimer()
            }
            override fun onClose(c: WebSocket?, code: Int, r: String?, remote: Boolean) {
                Timber.tag("Bridge").i("onClose code=%d reason=%s", code, r ?: "")
                promoteLastInterimIfAny("close")
            }

            override fun onMessage(c: WebSocket, msg: String) {
                Timber.tag("Bridge").i("onMessage %s", msg.take(160))
                val l = listener.get() ?: return
                val o = runCatching { JSONObject(msg) }.getOrNull() ?: return
                when (o.optString("type")) {
                    "ready" -> {
                        // Validate nonce
                        val rxNonce = o.optString("nonce")
                        if (rxNonce.isNotEmpty() && rxNonce != sessionNonce) {
                            Timber.tag("Bridge").w("nonce mismatch: expected=%s got=%s", sessionNonce, rxNonce)
                            c.send("""{"type":"close"}""")
                            return
                        }
                        readyTimer?.cancel(false)
                        transcriptTimer?.cancel(false)
                        armTranscriptTimer()
                    }
                    "interim"  -> {
                        val text = o.optString("text")
                        if (text.isNotBlank()) lastInterim = text
                        l.onInterim(text)
                        transcriptTimer?.cancel(false)
                        armTranscriptTimer(2_500)
                    }
                    "final"    -> {
                        transcriptTimer?.cancel(false)
                        val text = o.optString("text").ifBlank { lastInterim }
                        l.onFinal(text)
                        lastInterim = ""
                        stopServer()
                    }
                    "error"    -> l.onError(o.optString("code"), o.optString("msg"))
                }
            }

            override fun onError(c: WebSocket?, e: Exception) {
                Timber.tag("Bridge").e(e, "ws err")
            }

            override fun onStart() {
                Timber.tag("Bridge").i("ws server started on port %d", address.port)
            }
        }
        srv.isReuseAddr = true
        srv.start()
        server = srv
        serverStarted = true
    }

    private fun stopServer() {
        val srv = server ?: return
        server = null
        serverStarted = false
        timers.execute { runCatching { srv.stop(500) } }
    }

    private fun armTranscriptTimer(delayMs: Long = 30_000) {
        transcriptTimer = timers.schedule({
            if (!promoteLastInterimIfAny("quiet")) {
                listener.get()?.onTimeout("transcript")
            }
            stopServer()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun promoteLastInterimIfAny(reason: String): Boolean {
        val text = lastInterim
        if (text.isBlank()) return false
        Timber.tag("Bridge").w("promoting last interim to final after helper %s: %s", reason, text.take(80))
        transcriptTimer?.cancel(false)
        lastInterim = ""
        listener.get()?.onFinal(text)
        stopServer()
        return true
    }
}
