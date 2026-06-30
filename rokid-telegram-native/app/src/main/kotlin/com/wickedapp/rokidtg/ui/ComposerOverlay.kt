package com.wickedapp.rokidtg.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresPermission
import androidx.core.view.isVisible
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.TdClientFacade
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
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    input.setText("")
                }
                true
            } else {
                false
            }
        }
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

    /** Show the composer overlay. */
    fun show() {
        container.visibility = View.VISIBLE
    }

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
            cap.start { pcm -> encoder?.feed(pcm) }
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
                    sendIcon.isVisible = true
                }
            }

            override fun onError(code: String, msg: String) {
                Timber.tag("Voice").w("bridge error %s: %s", code, msg)
                root.post { cancel() }
            }

            override fun onTimeout(stage: String) {
                Timber.tag("Voice").w("bridge timeout at stage=%s", stage)
                root.post { cancel() }
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

    private fun hide() {
        active = false
        finalTranscript = null
        container.visibility = View.GONE
        sendIcon.isVisible = false
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
