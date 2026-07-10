package com.wickedapp.rokidtg.voice

import android.os.Handler
import android.os.Looper
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Production dictation path: ask the paired phone companion to do ASR over CXR.
 *
 * Protocol, Caps string fields:
 *   glasses -> phone: name "tg.dictate.start", [sessionId, chatId, lang]
 *   glasses -> phone: name "tg.dictate.cancel", [sessionId]
 *   phone -> glasses: name "tg.asr", [sessionId, event, payload]
 *
 * event ∈ ready | partial | final | error | end
 */
class PhoneCxrDictationProvider(
    private val bridgeFactory: () -> CXRServiceBridge = { sharedBridge },
    private val timeoutMs: Long = 12_000L,
) : DictationProvider {

    private val bridge: CXRServiceBridge by lazy(bridgeFactory)
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, DictationProvider.Callback>()
    @Volatile private var subscribed = false

    override fun start(session: DictationSession, callback: DictationProvider.Callback) {
        val rc = runCatching {
            ensureSubscribed()
            callbacks[session.sessionId] = callback
            val args = Caps().apply {
                write(session.sessionId)
                write(session.chatId.toString())
                write(session.lang)
            }
            bridge.sendMessage(MSG_DICTATE_START, args)
        }.onFailure { Timber.tag(TAG).w(it, "send dictate start failed") }
            .getOrDefault(-3)
        Timber.tag(TAG).i("send %s rc=%d session=%s", MSG_DICTATE_START, rc, session.sessionId)
        if (rc != 0) {
            callbacks.remove(session.sessionId)
            main.post { callback.onError(DictationError.PhoneUnavailable.copy(message = "CXR send failed rc=$rc")) }
            return
        }
        main.postDelayed({
            callbacks.remove(session.sessionId)?.onError(DictationError.Timeout)
        }, timeoutMs)
    }

    override fun cancel(sessionId: String) {
        callbacks.remove(sessionId)
        val args = Caps().apply { write(sessionId) }
        val rc = runCatching { bridge.sendMessage(MSG_DICTATE_CANCEL, args) }.getOrDefault(-3)
        Timber.tag(TAG).i("send %s rc=%d session=%s", MSG_DICTATE_CANCEL, rc, sessionId)
    }

    private fun ensureSubscribed() {
        if (subscribed) return
        synchronized(this) {
            if (subscribed) return
            bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(name: String?, id: String?, type: Int) {
                    Timber.tag(TAG).i("CXR connected name=%s id=%s type=%d", name, id, type)
                }
                override fun onDisconnected() { Timber.tag(TAG).w("CXR disconnected") }
                override fun onConnecting(name: String?, id: String?, type: Int) {
                    Timber.tag(TAG).i("CXR connecting name=%s id=%s type=%d", name, id, type)
                }
                override fun onARTCStatus(fps: Float, enabled: Boolean) = Unit
                override fun onRokidAccountChanged(account: String?) = Unit
            })
            val rc = bridge.subscribe(MSG_ASR, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleAsr(args)
            })
            subscribed = rc == 0 || rc == CXRServiceBridge.EDUP
            Timber.tag(TAG).i("subscribe %s rc=%d subscribed=%s", MSG_ASR, rc, subscribed)
        }
    }

    private fun handleAsr(args: Caps?) {
        val sessionId = args.stringAt(0)
        val event = args.stringAt(1)
        val payload = args.stringAt(2)
        Timber.tag(TAG).i("rx %s session=%s event=%s payloadLen=%d", MSG_ASR, sessionId, event, payload.length)
        if (sessionId.isBlank() || event.isBlank()) return
        when (event) {
            "ready", "partial" -> {
                val cb = callbacks[sessionId] ?: return
                main.post {
                    if (callbacks.containsKey(sessionId)) {
                        if (event == "ready") cb.onReady() else cb.onPartial(payload)
                    }
                }
            }
            "final" -> {
                val cb = callbacks.remove(sessionId) ?: return
                main.post { if (payload.isBlank()) cb.onError(DictationError.Empty) else cb.onFinal(payload) }
            }
            "error" -> {
                val cb = callbacks.remove(sessionId) ?: return
                main.post { cb.onError(DictationError(payload.ifBlank { "phone_error" })) }
            }
            "end" -> callbacks.remove(sessionId)
        }
    }

    private fun Caps?.stringAt(index: Int): String = runCatching {
        if (this == null || size() <= index) "" else at(index).getString().orEmpty()
    }.getOrDefault("")

    companion object {
        private const val TAG = "PhoneDictation"
        private const val MSG_DICTATE_START = "tg.dictate.start"
        private const val MSG_DICTATE_CANCEL = "tg.dictate.cancel"
        private const val MSG_ASR = "tg.asr"
        private val sharedBridge: CXRServiceBridge by lazy { CXRServiceBridge() }
    }
}
