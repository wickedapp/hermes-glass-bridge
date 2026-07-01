package com.wickedapp.rokidtg.ui

import android.content.Intent
import android.os.SystemClock
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.TdClientFacade
import com.wickedapp.rokidtg.voice.AudioCapturer
import com.wickedapp.rokidtg.voice.VoiceHelperBridge
import com.wickedapp.rokidtg.voice.VoiceNoteEncoder
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

/**
 * Bottom reply state machine:
 *   DEFAULT       → single "回复" button (focusable, sits below message list)
 *   MENU          → 语音 / 文字 / 键盘 (three reply-mode buttons)
 *   VOICE         → live recording timer + 发送 / 取消
 *   TEXT          → live transcript from Sprite voicehelper + 发送 / 取消
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
    private val onSendVoiceNote: (File, Int, ByteArray) -> Unit,
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
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = ((SystemClock.elapsedRealtime() - recordStartMs) / 1000).toInt()
            voiceStatus.text = "● %d:%02d".format(s / 60, s % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    // Voice-to-text (Sprite helper)
    private var textActive = false
    private var textFinal: String? = null

    init {
        btnReply.setOnClickListener { go(State.MENU) }

        btnVoice.setOnClickListener { startVoiceReply() }
        btnText.setOnClickListener  { startTextReply() }
        btnBt.setOnClickListener    { startBtReply() }

        btnVoice.setOnFocusChangeListener { _, has -> if (has) BannerHost.show("Voice", BannerHost.Kind.INFO) }
        btnText.setOnFocusChangeListener  { _, has -> if (has) BannerHost.show("Dictate", BannerHost.Kind.INFO) }
        btnBt.setOnFocusChangeListener    { _, has -> if (has) BannerHost.show("Keyboard", BannerHost.Kind.INFO) }

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

    /** Open reply actions directly. Used when the bottom Reply button is activated from window focus. */
    fun openMenu() {
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

    fun showFinalTranscript(text: String) {
        textActive = true
        textFinal = text
        go(State.TEXT)
        textTranscript.text = "確認發送？\n$text"
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
        when (next) {
            State.DEFAULT -> btnReply.requestFocus()
            State.MENU    -> btnVoice.requestFocus()
            State.VOICE   -> btnVoiceSend.requestFocus()
            State.TEXT    -> btnTextSend.requestFocus()
            State.BT      -> btInput.requestFocus()
        }
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
                    BannerHost.show("麦克风被系统占用", BannerHost.Kind.WARN)
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
        onSendVoiceNote(file, dur, wave)
        go(State.DEFAULT)
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
    // TEXT (voice-to-text via Sprite helper)
    // ------------------------------------------------------------------

    private fun startTextReply() {
        Timber.tag("ReplyPanel").i("startTextReply")
        val activity = ctx as? MainActivity
        if (activity?.voiceHelperDisabled == true) {
            BannerHost.show("语音助手未就绪", BannerHost.Kind.WARN)
            return
        }
        textActive = true
        textFinal = null
        textTranscript.text = ctx.getString(R.string.composer_listening)
        textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
        go(State.TEXT)
        startBridge()
        launchHelper()
    }

    private fun bringAppToFront() {
        runCatching {
            (ctx as? Activity)?.let { activity ->
                runCatching {
                    val am = activity.getSystemService(ActivityManager::class.java)
                    am.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                    Timber.tag("ReplyPanel").i("moveTaskToFront task=%d", activity.taskId)
                }.onFailure { Timber.tag("ReplyPanel").w(it, "moveTaskToFront failed") }
            }
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            Timber.tag("ReplyPanel").i("startActivity bringAppToFront")
            ctx.startActivity(intent)
            postFullScreenReturn(intent)
        }
    }

    private fun postFullScreenReturn(intent: Intent) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val channelId = "voice_return"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Voice return", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                ctx,
                48761,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Telegram 語音輸入完成")
                .setContentText("返回確認發送")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(48761, n)
        }.onFailure { Timber.tag("ReplyPanel").w(it, "full-screen return notification failed") }
    }

    private fun startBridge() {
        Timber.tag("ReplyPanel").i("startBridge port=%d", bridge.boundPort)
        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) {
                root.post {
                    textTranscript.text = text
                    textTranscript.setTextColor(ctx.getColor(R.color.primary_50))
                }
            }
            override fun onFinal(text: String) {
                root.post {
                    ctx.getSharedPreferences("voice_helper", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong("pending_chat_id", chatId)
                        .putString("pending_transcript", text)
                        .apply()
                    bringAppToFront()
                    root.postDelayed({ bringAppToFront() }, 750)
                    showFinalTranscript(text)
                }
            }
            override fun onError(code: String, msg: String) {
                Timber.tag("Voice").w("bridge error %s: %s", code, msg)
                root.post {
                    bringAppToFront()
                    cancelText()
                }
            }
            override fun onTimeout(stage: String) {
                Timber.tag("Voice").w("bridge timeout at stage=%s", stage)
                root.post {
                    bringAppToFront()
                    when (stage) {
                        "ready" -> {
                            BannerHost.show("语音助手未就绪", BannerHost.Kind.WARN)
                            (ctx as? MainActivity)?.voiceHelperDisabled = true
                        }
                        "transcript" -> BannerHost.show("没听清", BannerHost.Kind.WARN)
                    }
                    cancelText()
                }
            }
        })
    }

    /**
     * [VERIFY:launch-intent] The previous startActivity(SpriteMainActivity, appId=…) shape
     * moves Sprite launcher to the foreground and pushes Rokid TG to the background —
     * user perceives it as an app shutdown. Switching to a broadcast so we stay
     * foreground while the Sprite runtime opens the mini-app in the background.
     * If the broadcast is also silently dropped by the ROM, the bridge will time out at
     * stage="ready" and the BannerHost fallback ("语音助手未就绪") kicks in.
     */
    private fun launchHelper() {
        runCatching {
            Timber.tag("ReplyPanel").i("launchHelper tg-voice-helper-v05")
            val intent = Intent("com.rokid.os.sprite.jsai.OPEN_PAGE").apply {
                component = android.content.ComponentName(
                    "com.rokid.os.sprite.assistserver",
                    "com.rokid.os.sprite.jsai.JsaiService"
                )
                // Sprite skips onNewIntent when the same AIX path is already open. Alternate
                // between two identical pushed helper paths so every Dictate action is a real
                // page load and can auto-return after ASR.
                val prefs = ctx.getSharedPreferences("voice_helper", android.content.Context.MODE_PRIVATE)
                val useA = prefs.getBoolean("next_helper_a", false)
                prefs.edit().putBoolean("next_helper_a", !useA).apply()
                val helperPath = if (useA)
                    "/sdcard/Download/tg-voice-helper-v05a.aix"
                else
                    "/sdcard/Download/tg-voice-helper-v05b.aix"
                putExtra("open_params", helperPath)
                putExtra("test_run_id", "tg_native_tdlib_voice_${System.currentTimeMillis()}")
            }
            ctx.startService(intent)
        }.onFailure { e ->
            Timber.tag("Voice").w(e, "launchHelper failed")
        }
    }

    private fun sendTextTranscript() {
        val text = textFinal?.takeIf { it.isNotBlank() } ?: return
        sendTextMessage(text)
        bridge.cancel()
        textActive = false
        textFinal = null
        go(State.DEFAULT)
    }

    private fun cancelText() {
        bridge.cancel()
        textActive = false
        textFinal = null
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
