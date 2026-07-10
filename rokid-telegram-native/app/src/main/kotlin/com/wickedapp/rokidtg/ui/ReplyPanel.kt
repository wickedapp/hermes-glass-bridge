package com.wickedapp.rokidtg.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresPermission
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.TdClientFacade
import com.wickedapp.rokidtg.voice.AudioCapturer
import com.wickedapp.rokidtg.voice.DictationError
import com.wickedapp.rokidtg.voice.DictationProvider
import com.wickedapp.rokidtg.voice.DictationSession
import com.wickedapp.rokidtg.voice.PhoneCxrDictationProvider
import com.wickedapp.rokidtg.voice.VoiceHelperBridge
import com.wickedapp.rokidtg.voice.VoiceNoteEncoder
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Bottom reply state machine:
 *   DEFAULT       → single "回复" button (focusable, sits below message list)
 *   MENU          → 语音 / 文字 / 键盘 (three reply-mode buttons)
 *   VOICE         → live recording timer + 发送 / 取消
 *   TEXT          → in-page speech recognizer + live transcript + 发送 / 取消
 *   BT            → EditText + 发送 (BT keyboard typing path)
 *
 * Back-press (or 取消) collapses one level: VOICE/TEXT/BT → MENU → DEFAULT.
 * Sending from any reply mode returns directly to DEFAULT.
 */
class ReplyPanel(
    private val root: View,
    private val td: TdClientFacade,
    private val chatId: Long,
    private val bridge: VoiceHelperBridge,
    private val onSendVoiceNote: (File, Int, ByteArray, (Boolean) -> Unit) -> Unit,
    private val dictationProvider: DictationProvider = PhoneCxrDictationProvider(),
) {
    enum class State { DEFAULT, MENU, VOICE, TEXT, BT }

    private val panel: View         = root.findViewById(R.id.reply_panel)
    private val stateDefault: View  = root.findViewById(R.id.state_default)
    private val stateMenu: View     = root.findViewById(R.id.state_menu)
    private val stateVoice: View    = root.findViewById(R.id.state_voice)
    private val stateText: View     = root.findViewById(R.id.state_text)
    private val stateBt: View       = root.findViewById(R.id.state_bt)

    private val btnReply: TextView      = root.findViewById(R.id.btn_reply)
    private val btnVoice: TextView      = root.findViewById(R.id.btn_voice)
    private val btnText: TextView       = root.findViewById(R.id.btn_text)
    private val btnBt: TextView         = root.findViewById(R.id.btn_bt)

    private val voiceStatus: TextView   = root.findViewById(R.id.voice_status)
    private val btnVoiceSend: TextView  = root.findViewById(R.id.btn_voice_send)
    private val btnVoiceCancel: TextView = root.findViewById(R.id.btn_voice_cancel)

    private val textTranscript: TextView = root.findViewById(R.id.text_transcript)
    private val btnTextSend: TextView   = root.findViewById(R.id.btn_text_send)
    private val btnTextCancel: TextView = root.findViewById(R.id.btn_text_cancel)

    private val btInput: EditText       = root.findViewById(R.id.bt_input)
    private val btnBtSend: TextView     = root.findViewById(R.id.btn_bt_send)

    private val ctx get() = root.context

    private var state: State = State.DEFAULT

    // Voice (audio note) recording
    @Volatile private var recording = false
    private var capturer: AudioCapturer? = null
    private var encoder: VoiceNoteEncoder? = null
    private var outFile: File? = null
    private var recordStartMs = 0L
    private var menuFocusIndex = 0
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = ((SystemClock.elapsedRealtime() - recordStartMs) / 1000).toInt()
            voiceStatus.text = "● %d:%02d".format(s / 60, s % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    // Voice-to-text / Dictate
    private var textActive = false
    private var textFinal: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var dictationSessionId: String? = null

    init {
        btnReply.setOnClickListener { go(State.MENU) }

        btnVoice.setOnClickListener { startVoiceReply() }
        btnText.setOnClickListener  { startTextReply() }
        btnBt.setOnClickListener    { startBtReply() }

        btnVoiceSend.setOnClickListener   { stopAndSendVoice() }
        btnVoiceCancel.setOnClickListener { cancelVoice() }

        btnTextSend.setOnClickListener   { sendTextTranscript() }
        btnTextCancel.setOnClickListener { cancelText() }

        btnBtSend.setOnClickListener { sendBt() }
        btInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                sendBt(); true
            } else false
        }
        btInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                sendBt(); true
            } else false
        }
    }

    // ------------------------------------------------------------------
    // Public hooks
    // ------------------------------------------------------------------

    fun currentState(): State = state

    /** Focus the currently visible reply control; never focus the whole bottom reply section. */
    fun focusCurrentState() {
        focusForState(state)
    }

    /** Open reply actions directly. Used when the bottom Reply button is activated from window focus. */
    fun openMenu() {
        menuFocusIndex = 0
        go(State.MENU)
    }

    fun activateFocusedAction(focusedId: Int): Boolean = when (focusedId) {
        R.id.btn_voice -> { Timber.tag("ReplyPanel").i("activate Voice"); startVoiceReply(); true }
        R.id.btn_text -> { Timber.tag("ReplyPanel").i("activate Dictate"); startTextReply(); true }
        R.id.btn_bt -> { Timber.tag("ReplyPanel").i("activate Keyboard"); startBtReply(); true }
        R.id.btn_text_send -> { Timber.tag("ReplyPanel").i("activate Send transcript"); sendTextTranscript(); true }
        R.id.btn_text_cancel -> { Timber.tag("ReplyPanel").i("activate Redo/Cancel transcript"); cancelText(); true }
        else -> false
    }

    fun moveFocus(delta: Int): Boolean {
        val order = when (state) {
            State.DEFAULT -> listOf(btnReply)
            State.MENU -> listOf(btnVoice, btnText, btnBt)
            State.VOICE -> listOf(btnVoiceSend, btnVoiceCancel)
            State.TEXT -> listOf(btnTextSend, btnTextCancel)
            State.BT -> listOf(btInput, btnBtSend)
        }
        val step = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val nextIndex = if (state == State.MENU) {
            menuFocusIndex = (menuFocusIndex + step).coerceIn(0, order.lastIndex)
            menuFocusIndex
        } else {
            val focused = root.findFocus()
            val idx = order.indexOfFirst { it === focused }.takeIf { it >= 0 } ?: 0
            (idx + step).coerceIn(0, order.lastIndex)
        }
        val next = order[nextIndex]
        next.isFocusableInTouchMode = true
        next.requestFocusFromTouch()
        return true
    }

    fun showFinalTranscript(text: String) {
        textActive = true
        textFinal = text
        go(State.TEXT)
        textTranscript.text = text
        textTranscript.setTextColor(ctx.getColor(R.color.primary))
        btnTextSend.requestFocus()
    }

    /**
     * Handle back / BACK gesture. Collapses one level toward DEFAULT.
     * Returns true if consumed (so the activity doesn't pop the fragment).
     */
    fun onBack(): Boolean = when (state) {
        State.DEFAULT -> false
        State.MENU    -> { go(State.DEFAULT); true }
        State.VOICE   -> { cancelVoice(); true }
        State.TEXT    -> { cancelText(); true }
        State.BT      -> { cancelBt(); true }
    }

    /** BT-keyboard fast path: jump straight into BT input and append the typed char. */
    fun appendCharFromBtKeyboard(ch: Char) {
        if (state != State.BT) startBtReply()
        btInput.append(ch.toString())
    }

    // ------------------------------------------------------------------
    // State transitions
    // ------------------------------------------------------------------

    private fun go(next: State) {
        state = next
        stateDefault.visibility = if (next == State.DEFAULT) View.VISIBLE else View.GONE
        stateMenu.visibility    = if (next == State.MENU)    View.VISIBLE else View.GONE
        stateVoice.visibility   = if (next == State.VOICE)   View.VISIBLE else View.GONE
        stateText.visibility    = if (next == State.TEXT)    View.VISIBLE else View.GONE
        stateBt.visibility      = if (next == State.BT)      View.VISIBLE else View.GONE

        // Give focus to a sensible default for the new state so DPAD navigation works.
        focusForState(next)
    }

    private fun focusForState(target: State) {
        val focusTarget = when (target) {
            State.DEFAULT -> btnReply
            State.MENU    -> {
                menuFocusIndex = 0
                btnVoice
            }
            State.VOICE   -> btnVoiceSend
            State.TEXT    -> btnTextSend
            State.BT      -> btInput
        }
        focusTarget.isFocusableInTouchMode = true
        focusTarget.requestFocusFromTouch()
        focusTarget.post { focusTarget.requestFocusFromTouch() }
    }

    // ------------------------------------------------------------------
    // VOICE (audio note)
    // ------------------------------------------------------------------

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startVoiceReply() {
        if (recording) return
        val f = File(ctx.filesDir, "voice/out-${System.currentTimeMillis()}.ogg")
            .also { it.parentFile?.mkdirs() }
        outFile = f
        encoder = VoiceNoteEncoder(f)
        capturer = AudioCapturer().also { cap ->
            cap.start(
                onMono16k = { pcm -> encoder?.feed(pcm) },
                onError = {
                    BannerHost.show(ctx.getString(R.string.mic_busy), BannerHost.Kind.WARN)
                    root.post { cancelVoice() }
                }
            )
        }
        recording = true
        recordStartMs = SystemClock.elapsedRealtime()
        voiceStatus.text = ctx.getString(R.string.voice_recording_zero)
        go(State.VOICE)
        timerHandler.post(timerTick)
    }

    private fun stopAndSendVoice() {
        if (!recording) return
        timerHandler.removeCallbacks(timerTick)
        recording = false
        capturer?.stop(); capturer = null
        val enc = encoder ?: run { go(State.DEFAULT); return }
        encoder = null
        val (dur, wave) = enc.finishWithDuration()
        val file = outFile ?: run { go(State.DEFAULT); return }
        outFile = null
        voiceStatus.text = "sending…"
        onSendVoiceNote(file, dur, wave) { ok ->
            root.post {
                if (ok) go(State.DEFAULT) else {
                    BannerHost.show(ctx.getString(R.string.voice_send_failed), BannerHost.Kind.WARN)
                    go(State.MENU)
                }
            }
        }
    }

    private fun cancelVoice() {
        timerHandler.removeCallbacks(timerTick)
        recording = false
        capturer?.stop(); capturer = null
        encoder = null
        outFile?.delete()
        outFile = null
        go(State.MENU)
    }

    // ------------------------------------------------------------------
    // TEXT (in-page voice-to-text)
    // ------------------------------------------------------------------

    private fun startTextReply() {
        Timber.tag("ReplyPanel").i("startTextReply inline")
        textActive = true
        textFinal = null
        textTranscript.text = ctx.getString(R.string.composer_listening)
        textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
        go(State.TEXT)
        val forceSpriteHelper = isRokidDevice()
        val androidRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(ctx)
        Timber.tag("ReplyPanel").i(
            "dictate route forceSprite=%s androidRecognizer=%s manufacturer=%s model=%s",
            forceSpriteHelper,
            androidRecognizerAvailable,
            Build.MANUFACTURER,
            Build.MODEL
        )
        if (!forceSpriteHelper && androidRecognizerAvailable) {
            startInlineSpeechRecognizer()
        } else {
            Timber.tag("ReplyPanel").i("Using phone CXR companion for Dictate")
            startPhoneDictation()
        }
    }

    private fun startPhoneDictation() {
        val session = DictationSession(
            sessionId = UUID.randomUUID().toString(),
            chatId = chatId,
            lang = "zh-TW",
        )
        dictationSessionId = session.sessionId
        dictationProvider.start(session, object : DictationProvider.Callback {
            override fun onReady() {
                root.post {
                    if (!textActive || dictationSessionId != session.sessionId) return@post
                    textTranscript.text = ctx.getString(R.string.composer_listening)
                    textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                }
            }

            override fun onPartial(text: String) {
                root.post {
                    if (!textActive || dictationSessionId != session.sessionId) return@post
                    textTranscript.text = text
                    textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                }
            }

            override fun onFinal(text: String) {
                root.post {
                    if (!textActive || dictationSessionId != session.sessionId) return@post
                    dictationSessionId = null
                    showFinalTranscript(text)
                }
            }

            override fun onError(error: DictationError) {
                Timber.tag("ReplyPanel").w("phone dictation error=%s msg=%s; falling back to Sprite helper", error.code, error.message)
                root.post {
                    if (!textActive || dictationSessionId != session.sessionId) return@post
                    dictationSessionId = null
                    textTranscript.text = ctx.getString(R.string.composer_listening)
                    textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                    startBridge()
                    launchHiddenHelper()
                }
            }
        })
    }

    private fun isRokidDevice(): Boolean {
        val haystack = listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .joinToString(" ")
            .lowercase()
        return haystack.contains("rokid") || haystack.contains("yoda") || haystack.contains("sprite")
    }

    private fun startInlineSpeechRecognizer() {
        stopInlineSpeechRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.tag("ReplyPanel").i("inline ASR ready")
                    textTranscript.text = ctx.getString(R.string.composer_listening)
                }

                override fun onBeginningOfSpeech() {
                    Timber.tag("ReplyPanel").i("inline ASR speech begin")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    Timber.tag("ReplyPanel").i("inline ASR speech end")
                }

                override fun onError(error: Int) {
                    Timber.tag("ReplyPanel").w("inline ASR error=%d; falling back to hidden Sprite helper", error)
                    if (!textActive) return
                    stopInlineSpeechRecognizer()
                    startBridge()
                    launchHiddenHelper()
                }

                override fun onResults(results: Bundle?) {
                    val text = bestSpeechText(results)
                    Timber.tag("ReplyPanel").i("inline ASR final length=%d", text.length)
                    if (text.isBlank()) {
                        BannerHost.show(ctx.getString(R.string.voice_not_clear), BannerHost.Kind.WARN)
                        cancelText()
                    } else {
                        showFinalTranscript(text)
                        stopInlineSpeechRecognizer()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = bestSpeechText(partialResults)
                    if (text.isNotBlank()) {
                        textTranscript.text = text
                        textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            recognizer.startListening(intent)
        }
    }

    private fun bestSpeechText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    private fun stopInlineSpeechRecognizer() {
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
        speechRecognizer = null
    }

    private fun startBridge() {
        Timber.tag("ReplyPanel").i("startBridge port=%d", bridge.boundPort)
        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onReady() {
                root.post { bringNativeTaskToFront() }
            }

            override fun onInterim(text: String) {
                root.post {
                    if (!textActive) return@post
                    textTranscript.text = text
                    textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                    bringNativeTaskToFront()
                }
            }

            override fun onFinal(text: String) {
                root.post {
                    if (!textActive) return@post
                    bringNativeTaskToFront()
                    showFinalTranscript(text)
                }
            }

            override fun onError(code: String, msg: String) {
                Timber.tag("Voice").w("bridge error %s: %s", code, msg)
                root.post {
                    bringNativeTaskToFront()
                    cancelText()
                }
            }

            override fun onTimeout(stage: String) {
                Timber.tag("Voice").w("bridge timeout at stage=%s", stage)
                root.post {
                    bringNativeTaskToFront()
                    BannerHost.show(
                        ctx.getString(if (stage == "ready") R.string.voice_helper_not_ready else R.string.voice_not_clear),
                        BannerHost.Kind.WARN
                    )
                    cancelText()
                }
            }
        })
    }

    private fun launchHiddenHelper() {
        runCatching {
            val intent = Intent("com.rokid.os.sprite.jsai.OPEN_PAGE").apply {
                component = ComponentName(
                    "com.rokid.os.sprite.assistserver",
                    "com.rokid.os.sprite.jsai.JsaiService"
                )
                val prefs = ctx.getSharedPreferences("voice_helper", android.content.Context.MODE_PRIVATE)
                val useA = prefs.getBoolean("next_helper_a", false)
                prefs.edit().putBoolean("next_helper_a", !useA).apply()
                putExtra(
                    "open_params",
                    if (useA) "/sdcard/Download/tg-voice-helper-v05a.aix" else "/sdcard/Download/tg-voice-helper-v05b.aix"
                )
                putExtra("test_run_id", "tg_native_inline_voice_${System.currentTimeMillis()}")
            }
            Timber.tag("ReplyPanel").i("launchHiddenHelper")
            ctx.startService(intent)
            // Let JsaiActivity mount the Ink page first; pulling it back too early can
            // stop the helper before its WebSocket connects. After that first short
            // mount window, repeatedly return to native so the helper cannot strand
            // the user on the recognition page.
            listOf(300L, 650L, 1000L, 1500L, 2200L, 3500L, 5000L, 8000L).forEach { delayMs ->
                root.postDelayed({ bringNativeTaskToFront() }, delayMs)
            }
        }.onFailure { e ->
            Timber.tag("Voice").w(e, "launchHiddenHelper failed")
            BannerHost.show(ctx.getString(R.string.voice_helper_not_ready), BannerHost.Kind.WARN)
            cancelText()
        }
    }

    private fun bringNativeTaskToFront() {
        runCatching {
            (ctx as? Activity)?.let { activity ->
                val am = activity.getSystemService(ActivityManager::class.java)
                am.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
            }
        }.onFailure { Timber.tag("ReplyPanel").w(it, "moveTaskToFront failed") }
    }

    private fun sendTextTranscript() {
        val text = textFinal?.takeIf { it.isNotBlank() } ?: return
        sendTextMessage(text)
        textActive = false
        textFinal = null
        dictationSessionId?.let { dictationProvider.cancel(it) }
        dictationSessionId = null
        stopInlineSpeechRecognizer()
        bridge.cancel()
        go(State.DEFAULT)
    }

    private fun cancelText() {
        textActive = false
        textFinal = null
        dictationSessionId?.let { dictationProvider.cancel(it) }
        dictationSessionId = null
        stopInlineSpeechRecognizer()
        bridge.cancel()
        go(State.MENU)
    }

    // ------------------------------------------------------------------
    // BT (typed)
    // ------------------------------------------------------------------

    private fun startBtReply() {
        btInput.setText("")
        go(State.BT)
    }

    private fun sendBt() {
        val text = btInput.text.toString().trim()
        if (text.isEmpty()) return
        sendTextMessage(text)
        btInput.setText("")
        go(State.DEFAULT)
    }

    private fun cancelBt() {
        btInput.setText("")
        go(State.MENU)
    }

    // ------------------------------------------------------------------
    // TDLib send
    // ------------------------------------------------------------------

    private fun sendTextMessage(text: String) {
        val req = TdApi.SendMessage().apply {
            chatId = this@ReplyPanel.chatId
            inputMessageContent = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                false
            )
        }
        td.send(req) { result ->
            if (result is TdApi.Error) {
                Timber.tag("ReplyPanel").e("sendMessage failed: %s %s", result.code, result.message)
            }
        }
    }
}
