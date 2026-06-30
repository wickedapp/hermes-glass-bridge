package com.wickedapp.rokidtg.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresPermission
import androidx.core.view.isVisible
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.TdClientFacade
import com.wickedapp.rokidtg.ui.BannerHost
import com.wickedapp.rokidtg.voice.AudioCapturer
import com.wickedapp.rokidtg.voice.VoiceHelperBridge
import com.wickedapp.rokidtg.voice.VoiceNoteEncoder
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

/**
 * Bottom 80px composer strip per design spec §7: status/transcript display, not an editor.
 *
 * State machine:
 *   IDLE              → "说话以发送消息" hint visible
 *   LISTENING         → "正在听…" while bridge waits for the helper
 *   INTERIM           → live transcript at 50% green
 *   FINAL             → final transcript at 100% green + send icon; second toggleVoice sends
 *   RECORDING         → "● 录音中…" while voice-note encoder is active
 *   TYPING            → EditText takes over the bar (BT keyboard path)
 */
class ComposerOverlay(
    private val root: View,
    private val td: TdClientFacade,
    private val chatId: Long,
    private val bridge: VoiceHelperBridge,
) {
    private val container: View      = root.findViewById(R.id.composer_overlay)
    private val status: TextView     = root.findViewById(R.id.composer_status)
    private val hint: TextView       = root.findViewById(R.id.composer_hint)
    private val input: EditText      = root.findViewById(R.id.composerInput)
    private val sendIcon: ImageView  = root.findViewById(R.id.composer_send)

    private val ctx get() = root.context
    private val idleHintText: String get() = ctx.getString(R.string.composer_idle_hint)

    init {
        // Tap the composer area to start/cycle voice mode — fallback for users who don't
        // know about (or don't have) the two-finger double-tap gesture.
        container.setOnClickListener { toggleVoice() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                trySend(); true
            } else false
        }
        // Hardware ENTER from BT keyboard doesn't always fire IME action.
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                trySend(); true
            } else false
        }
        sendIcon.setOnClickListener {
            if (input.isVisible) trySend() else if (finalTranscript != null) sendAndHide()
        }

        renderIdle()
    }

    private fun trySend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        onSend(text)
        input.setText("")
        renderIdle()
    }

    private var active = false
    private var finalTranscript: String? = null

    // Voice-note recording state (separate from voice→text mode)
    @Volatile private var recording = false
    private var capturer: AudioCapturer? = null
    private var encoder: VoiceNoteEncoder? = null
    private var outFile: File? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun isRecording(): Boolean = recording

    /** No-op kept for back-compat: the composer is always visible. */
    fun show() {}

    /** Start recording a voice note. */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startVoiceNote(@Suppress("UNUSED_PARAMETER") onSendVoiceNote: (File, Int, ByteArray) -> Unit) {
        if (recording) return
        renderRecording()
        val f = File(ctx.filesDir, "voice/out-${System.currentTimeMillis()}.ogg")
            .also { it.parentFile?.mkdirs() }
        outFile = f
        encoder = VoiceNoteEncoder(f)
        capturer = AudioCapturer().also { cap ->
            cap.start(
                onMono16k = { pcm -> encoder?.feed(pcm) },
                onError = {
                    BannerHost.show("Mic in use by system", BannerHost.Kind.WARN)
                    root.post {
                        recording = false
                        capturer = null
                        encoder = null
                        renderIdle()
                    }
                }
            )
        }
        recording = true
    }

    /** Stop recording and send the voice note via the provided callback. */
    fun stopAndSendVoiceNote(onSendVoiceNote: (File, Int, ByteArray) -> Unit) {
        if (!recording) return
        recording = false
        capturer?.stop(); capturer = null
        val enc = encoder ?: run { renderIdle(); return }
        encoder = null
        val (dur, wave) = enc.finishWithDuration()
        val file = outFile ?: run { renderIdle(); return }
        onSendVoiceNote(file, dur, wave)
        renderIdle()
    }

    /** Called by ChatFragment.onVoiceToggle(). Returns true if consumed. */
    fun toggleVoice(): Boolean {
        return when {
            !active                  -> { startVoice(); true }
            finalTranscript != null  -> { sendAndHide(); true }
            else                     -> { cancel(); true }
        }
    }

    /** Called by ChatFragment.onPrintableKey() to switch to typing mode. */
    fun appendChar(ch: Char) {
        showInput()
        input.append(ch.toString())
    }

    // -------------------------------------------------------------------------
    // State renderers — single place that owns visibility of status/hint/input.
    // -------------------------------------------------------------------------

    private fun renderIdle() {
        status.visibility = View.VISIBLE
        status.text = ctx.getString(R.string.composer_idle_status)
        status.setTextColor(ctx.getColor(R.color.primary_50))
        hint.visibility = View.VISIBLE
        hint.text = idleHintText
        sendIcon.visibility = View.GONE
        hideInput()
    }

    private fun renderListening() {
        status.visibility = View.VISIBLE
        status.text = ctx.getString(R.string.composer_listening)
        status.setTextColor(ctx.getColor(R.color.primary_50))
        hint.visibility = View.VISIBLE
        hint.text = idleHintText
        sendIcon.visibility = View.GONE
        hideInput()
    }

    private fun renderTranscript(text: String, isFinal: Boolean) {
        status.visibility = View.VISIBLE
        status.text = text
        status.setTextColor(ctx.getColor(if (isFinal) R.color.primary else R.color.primary_50))
        if (isFinal) {
            hint.text = ctx.getString(R.string.composer_ready_to_send)
            sendIcon.visibility = View.VISIBLE
        } else {
            hint.text = idleHintText
            sendIcon.visibility = View.GONE
        }
        hideInput()
    }

    private fun renderRecording() {
        status.visibility = View.VISIBLE
        status.text = ctx.getString(R.string.composer_recording)
        status.setTextColor(ctx.getColor(R.color.primary))
        hint.visibility = View.VISIBLE
        hint.text = ""
        sendIcon.visibility = View.GONE
        hideInput()
    }

    private fun showInput() {
        if (input.isVisible) return
        status.visibility = View.GONE
        hint.visibility = View.GONE
        sendIcon.visibility = View.VISIBLE
        input.visibility = View.VISIBLE
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
    }

    private fun hideInput() {
        if (!input.isVisible) return
        input.visibility = View.GONE
        input.isFocusable = false
        input.isFocusableInTouchMode = false
    }

    // -------------------------------------------------------------------------
    // Voice-mode internals
    // -------------------------------------------------------------------------

    private fun startVoice() {
        val activity = ctx as? MainActivity
        if (activity?.voiceHelperDisabled == true) {
            BannerHost.show("Voice helper not ready", BannerHost.Kind.WARN)
            return
        }
        active = true
        finalTranscript = null
        renderListening()
        startBridge()
        launchHelper()
    }

    private fun startBridge() {
        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) {
                root.post { renderTranscript(text, isFinal = false) }
            }
            override fun onFinal(text: String) {
                root.post {
                    finalTranscript = text
                    renderTranscript(text, isFinal = true)
                }
            }
            override fun onError(code: String, msg: String) {
                Timber.tag("Voice").w("bridge error %s: %s", code, msg)
                root.post { cancel() }
            }
            override fun onTimeout(stage: String) {
                Timber.tag("Voice").w("bridge timeout at stage=%s", stage)
                root.post {
                    when (stage) {
                        "ready" -> {
                            BannerHost.show("Voice helper not ready", BannerHost.Kind.WARN)
                            (ctx as? MainActivity)?.voiceHelperDisabled = true
                        }
                        "transcript" -> BannerHost.show("Didn't catch that", BannerHost.Kind.WARN)
                    }
                    cancel()
                }
            }
        })
    }

    /** [VERIFY:launch-intent] — intent shape is untested without manual on-device gesture. */
    private fun launchHelper() {
        runCatching {
            val intent = Intent().apply {
                setClassName(
                    "com.rokid.os.sprite.launcher",
                    "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
                )
                putExtra("appId", "com.wickedapp.voicehelper")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.onFailure { e ->
            Timber.tag("Voice").w(e, "launchHelper failed — voice-helper.aix not installed or unreachable")
        }
    }

    private fun sendAndHide() {
        val text = finalTranscript ?: return
        val req = TdApi.SendMessage().apply {
            chatId = this@ComposerOverlay.chatId
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                false
            )
        }
        td.send(req) { result ->
            if (result is TdApi.Error) {
                Timber.tag("Voice").e("sendMessage failed: %s %s", result.code, result.message)
            }
        }
        active = false
        finalTranscript = null
        bridge.cancel()
        renderIdle()
    }

    private fun onSend(text: String) {
        val req = TdApi.SendMessage().apply {
            chatId = this@ComposerOverlay.chatId
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                false
            )
        }
        td.send(req) { result ->
            if (result is TdApi.Error) {
                Timber.tag("Composer").e("sendMessage failed: %s %s", result.code, result.message)
            }
        }
    }

    private fun cancel() {
        active = false
        finalTranscript = null
        bridge.cancel()
        renderIdle()
    }
}
