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
 * Manages the composer overlay bar (overlay_composer.xml) that appears at the bottom of
 * ChatFragment. Driven by VoiceHelperBridge transcripts; sends via TDLib on final transcript.
 *
 * State machine (toggleVoice):
 *  - IDLE          → start voice (show + startBridge + launchHelper)
 *  - ACTIVE/empty  → cancel (hide, clear bridge)
 *  - ACTIVE/final  → send message via TDLib, then hide
 */
class ComposerOverlay(
    private val root: View,
    private val td: TdClientFacade,
    private val chatId: Long,
    private val bridge: VoiceHelperBridge,
) {
    private val container: View      = root.findViewById(R.id.composer_overlay)
    private val input: EditText      = root.findViewById(R.id.composerInput)
    private val sendIcon: ImageView  = root.findViewById(R.id.composer_send)

    init {
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                trySend(); true
            } else false
        }
        // Hardware ENTER from BT keyboard doesn't always fire IME action; catch the raw key.
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                trySend(); true
            } else false
        }
        sendIcon.setOnClickListener { trySend() }
    }

    private fun trySend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        onSend(text)
        input.setText("")
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

    /** Returns true if a voice note is currently being recorded. */
    fun isRecording(): Boolean = recording

    /** No-op kept for back-compat: the composer is always visible in the redesigned UI. */
    fun show() {}

    /** Start recording a voice note. Caller is responsible for holding RECORD_AUDIO permission. */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startVoiceNote(@Suppress("UNUSED_PARAMETER") onSendVoiceNote: (File, Int, ByteArray) -> Unit) {
        if (recording) return
        show()
        input.setText("录音中…")
        input.setTextColor(root.context.getColor(R.color.primary_50))
        val f = File(root.context.filesDir, "voice/out-${System.currentTimeMillis()}.ogg")
            .also { it.parentFile?.mkdirs() }
        outFile = f
        encoder = VoiceNoteEncoder(f)
        capturer = AudioCapturer().also { cap ->
            cap.start(
                onMono16k = { pcm -> encoder?.feed(pcm) },
                onError = {
                    // Mic grabbed by another app or system; surface to user via banner.
                    BannerHost.show("Mic in use by system", BannerHost.Kind.WARN)
                    root.post {
                        recording = false
                        capturer = null
                        encoder = null
                        hide()
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
        val enc = encoder ?: run { hide(); return }
        encoder = null
        val (dur, wave) = enc.finishWithDuration()
        val file = outFile ?: run { hide(); return }
        onSendVoiceNote(file, dur, wave)
        hide()
    }

    /** Called by ChatFragment.onVoiceToggle(). Returns true if consumed. */
    fun toggleVoice(): Boolean {
        return when {
            !active              -> { startVoice(); true }
            finalTranscript != null -> { sendAndHide(); true }
            else                 -> { cancel(); true }
        }
    }

    /** Called by ChatFragment.onPrintableKey() to append a character to the composer. */
    fun appendChar(ch: Char) {
        input.append(ch.toString())
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun startVoice() {
        // Disable Path 1 for rest of session if voice helper previously timed out on ready.
        val activity = root.context as? MainActivity
        if (activity?.voiceHelperDisabled == true) {
            BannerHost.show("Voice helper not ready", BannerHost.Kind.WARN)
            return
        }
        active = true
        finalTranscript = null
        setTranscript("", isFinal = false)
        show()
        startBridge()
        launchHelper()
    }

    private fun startBridge() {
        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) {
                root.post {
                    setTranscript(text, isFinal = false)
                }
            }

            override fun onFinal(text: String) {
                root.post {
                    finalTranscript = text
                    setTranscript(text, isFinal = true)
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
                            // Disable Path 1 for the rest of this session.
                            (root.context as? MainActivity)?.voiceHelperDisabled = true
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
            root.context.startActivity(intent)
        }.onFailure { e ->
            Timber.tag("Voice").w(e, "launchHelper failed — voice-helper.aix not installed or unreachable")
        }
    }

    private fun sendAndHide() {
        val text = finalTranscript ?: return
        val req = TdApi.SendMessage().apply {
            chatId = this@ComposerOverlay.chatId
            // v1.8.65: InputMessageText(FormattedText, LinkPreviewOptions?, boolean clearDraft)
            // Brief expected (text, disable_web_page_preview, clear_draft) but actual API uses
            // LinkPreviewOptions instead of a boolean for web preview. Pass null to use defaults.
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,  // linkPreviewOptions — null = default (preview enabled)
                false  // clearDraft
            )
        }
        td.send(req) { result ->
            if (result is TdApi.Error) {
                Timber.tag("Voice").e("sendMessage failed: %s %s", result.code, result.message)
            }
            // On success: UpdateNewMessage arrives via MessageRepo's existing update subscription.
        }
        hide()
    }

    private fun onSend(text: String) {
        val req = TdApi.SendMessage().apply {
            chatId = this@ComposerOverlay.chatId
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,  // linkPreviewOptions — null = default (preview enabled)
                false  // clearDraft
            )
        }
        td.send(req) { result ->
            if (result is TdApi.Error) {
                Timber.tag("Composer").e("sendMessage failed: %s %s", result.code, result.message)
            }
        }
    }

    private fun cancel() {
        bridge.cancel()
        hide()
    }

    /** Resets composer state. The bar itself stays visible — only the transcript/state clears. */
    private fun hide() {
        active = false
        finalTranscript = null
        setTranscript("", isFinal = false)
        bridge.cancel()
    }

    private fun setTranscript(text: String, isFinal: Boolean) {
        input.setText(text)
        input.setTextColor(
            if (isFinal) root.context.getColor(R.color.primary)
            else root.context.getColor(R.color.primary_50)
        )
    }
}
