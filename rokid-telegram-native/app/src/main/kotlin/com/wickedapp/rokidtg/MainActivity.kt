package com.wickedapp.rokidtg

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.databinding.ActivityMainBinding
import com.wickedapp.rokidtg.service.TelegramService
import com.wickedapp.rokidtg.ui.AuthFragment
import com.wickedapp.rokidtg.ui.BannerHost
import com.wickedapp.rokidtg.ui.ChatFragment
import com.wickedapp.rokidtg.ui.ChatListFragment
import com.wickedapp.rokidtg.ui.FullMessageFragment
import com.wickedapp.rokidtg.ui.MediaViewerFragment
import com.wickedapp.rokidtg.ui.input.GestureSink
import com.wickedapp.rokidtg.ui.input.InputRouter
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast
import com.wickedapp.rokidtg.voice.VoiceHelperBridge
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber

class MainActivity : AppCompatActivity(), GestureSink {
    private lateinit var binding: ActivityMainBinding
    private lateinit var router: InputRouter

    private var svc: TelegramService.LocalBinder? = null
    private var bound = false

    /** Lazily created, shared VoiceHelperBridge. Closed in onDestroy. */
    private var voiceBridge: VoiceHelperBridge? = null
    private var pendingOpenChatId: Long? = null

    /**
     * Set to true when voice helper ready-timeout fires; disables Path 1 (voice→text)
     * for the rest of the session so the user isn't stuck re-triggering a broken helper.
     */
    var voiceHelperDisabled: Boolean = false

    fun getOrCreateBridge(): VoiceHelperBridge {
        return voiceBridge ?: VoiceHelperBridge(port = 0).also { voiceBridge = it }
    }

    /** Returns the current service binder, or throws if not yet bound. Used by fragments. */
    fun requireService(): TelegramService.LocalBinder =
        svc ?: error("TelegramService not yet bound")

    /** Returns the current service binder, or null if not yet bound. Safe for cold-start. */
    fun optionalService(): TelegramService.LocalBinder? = svc

    /** Opens a chat by ID; called from ChatListFragment adapter click. */
    fun openChat(chatId: Long) {
        val title = svc?.getChatRepo()?.chats?.value?.firstOrNull { it.id == chatId }?.title ?: ""
        svc?.getNotifications()?.currentOpenChatId = chatId
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, ChatFragment.newInstance(chatId, title))
            .addToBackStack("chat:$chatId")
            .commitAllowingStateLoss()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val binder = b as TelegramService.LocalBinder
            svc = binder
            bound = true
            lifecycleScope.launch {
                binder.getAuthStateFlow().filterNotNull().collect { state ->
                    routeForAuthState(state)
                    if (state is TdApi.AuthorizationStateReady) openPendingDeepLinkIfAny()
                }
            }
            openPendingDeepLinkIfAny()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            bound = false
        }
    }

    /**
     * Swap the root fragment based on authorization state. Only acts on category transitions
     * (auth-needed vs. authorized) so we don't churn the UI on every TDLib state tick.
     */
    private fun routeForAuthState(state: TdApi.AuthorizationState) {
        val current = supportFragmentManager.findFragmentById(binding.container.id)
        val needAuth = state !is TdApi.AuthorizationStateReady &&
                       state !is TdApi.AuthorizationStateLoggingOut &&
                       state !is TdApi.AuthorizationStateClosing &&
                       state !is TdApi.AuthorizationStateClosed
        Timber.tag("Nav").i("route state=%s needAuth=%s current=%s", state.javaClass.simpleName, needAuth, current?.javaClass?.simpleName)
        when {
            needAuth && current !is AuthFragment -> {
                supportFragmentManager.beginTransaction()
                    .replace(binding.container.id, AuthFragment.newInstance())
                    .commitAllowingStateLoss()
            }
            !needAuth && state is TdApi.AuthorizationStateReady &&
                    current !is ChatListFragment &&
                    current !is ChatFragment &&
                    current !is FullMessageFragment &&
                    current !is MediaViewerFragment -> {
                supportFragmentManager.beginTransaction()
                    .replace(binding.container.id, ChatListFragment.newInstance())
                    .commitAllowingStateLoss()
            }
            else -> { /* same category — let AuthFragment handle its own per-state UI */ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BannerHost.attach(this)
        router = InputRouter(this, this)
        startForegroundService(Intent(this, TelegramService::class.java))

        // Request RECORD_AUDIO at runtime (declared in manifest since Task 4).
        // If denied, voice notes silently fail; BannerHost (Task 15) will surface the error.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Register backstack listener once in onCreate (not in onServiceConnected,
        // which would leak listeners on each stop/start cycle).
        // The lambda uses svc?.getNotifications() for safe null-handling.
        supportFragmentManager.addOnBackStackChangedListener {
            val f = supportFragmentManager.findFragmentById(binding.container.id)
            svc?.getNotifications()?.currentOpenChatId = (f as? ChatFragment)?.chatId
        }

        // Handle deep-link from notification tap (cold-start).
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, TelegramService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (bound) {
            unbindService(conn)
            bound = false
            svc = null
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        binding.root.post { consumePendingVoiceHandoff() }
    }

    private fun handleDeepLink(intent: Intent?) {
        val chatId = intent?.getLongExtra("openChatId", -1L)?.takeIf { it != -1L } ?: return
        pendingOpenChatId = chatId
        if (svc?.getClient() == null) return
        openPendingDeepLinkIfAny()
    }

    private fun openPendingDeepLinkIfAny() {
        val chatId = pendingOpenChatId ?: return
        if (svc?.getClient() == null) return
        pendingOpenChatId = null
        val title = svc?.getChatRepo()?.chats?.value?.firstOrNull { it.id == chatId }?.title ?: ""
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, ChatFragment.newInstance(chatId, title))
            .addToBackStack("chat:$chatId")
            .commitAllowingStateLoss()
        svc?.getNotifications()?.currentOpenChatId = chatId
    }

    override fun onResume() {
        super.onResume()
        router.install()
        binding.root.post { consumePendingVoiceHandoff() }
    }
    override fun onPause()  { router.uninstall(); super.onPause() }

    private fun consumePendingVoiceHandoff() {
        val prefs = getSharedPreferences("voice_helper", Context.MODE_PRIVATE)
        val text = prefs.getString("pending_transcript", null)?.takeIf { it.isNotBlank() } ?: return
        val targetChatId = prefs.getLong("pending_chat_id", -1L)
        val current = supportFragmentManager.findFragmentById(binding.container.id)
        if (current is ChatFragment && (targetChatId == -1L || current.chatId == targetChatId)) {
            if (current.showVoiceTranscriptFromHandoff(text)) {
                Timber.tag("Voice").i("consumed pending transcript handoff: %s", text.take(80))
                prefs.edit().remove("pending_transcript").remove("pending_chat_id").apply()
            }
        }
    }

    override fun onDestroy() {
        voiceBridge?.close()
        voiceBridge = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BT keyboard ESC → back, even while an EditText holds focus. Checked first so
        // the EditText below can't swallow it.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        // If a text input is focused, let it consume keys first so EditText listeners
        // (OnEditorAction, OnKey, IME action) work — otherwise the router below would
        // swallow ENTER as Gesture.TAP before the EditText ever sees ACTION_DOWN.
        if (currentFocus is android.widget.EditText) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER ||
             event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
             event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
             event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
             event.keyCode == KeyEvent.KEYCODE_BACK)) {
            return router.dispatchKey(event) || super.dispatchKeyEvent(event)
        }
        // Check for printable key events while in ChatFragment
        if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) {
            val f = supportFragmentManager.findFragmentById(binding.container.id)
            if (f is ChatFragment && f.onPrintableKey(event)) {
                return true
            }
        }
        return router.dispatchKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onGesture(g: SpriteBroadcast.Gesture): Boolean = when (g) {
        SpriteBroadcast.Gesture.SWIPE_FORWARD -> {
            val current = supportFragmentManager.findFragmentById(binding.container.id)
            when (current) {
                is ChatFragment -> current.onWindowGesture(g)
                is FullMessageFragment -> current.onWindowGesture(g)
                else -> { focusNext(); true }
            }
        }
        SpriteBroadcast.Gesture.SWIPE_BACK    -> {
            val current = supportFragmentManager.findFragmentById(binding.container.id)
            when (current) {
                is ChatFragment -> current.onWindowGesture(g)
                is FullMessageFragment -> current.onWindowGesture(g)
                else -> { focusPrev(); true }
            }
        }
        SpriteBroadcast.Gesture.TAP, SpriteBroadcast.Gesture.BUTTON_CLICK, SpriteBroadcast.Gesture.TWO_TAP -> {
            val current = supportFragmentManager.findFragmentById(binding.container.id)
            when (current) {
                is ChatFragment -> current.onWindowGesture(SpriteBroadcast.Gesture.TAP)
                is FullMessageFragment -> current.onWindowGesture(g)
                else -> { currentFocus?.performClick(); true }
            }
        }
        SpriteBroadcast.Gesture.BACK          -> {
            val current = supportFragmentManager.findFragmentById(binding.container.id)
            when {
                current is ChatFragment && current.onWindowGesture(g) -> true
                current is FullMessageFragment && current.onWindowGesture(g) -> true
                else -> { onBackPressedDispatcher.onBackPressed(); true }
            }
        }
        SpriteBroadcast.Gesture.TWO_DOUBLE_TAP -> {
            val f = supportFragmentManager.findFragmentById(binding.container.id)
            (f as? ChatFragment)?.onVoiceToggle()?.let { true } ?: false
        }
        SpriteBroadcast.Gesture.SETTINGS -> {
            val f = supportFragmentManager.findFragmentById(binding.container.id)
            (f as? ChatFragment)?.onVoiceNoteToggle()
            true
        }
        else -> false
    }

    private fun focusNext() {
        val cur = currentFocus ?: binding.container
        cur.focusSearch(View.FOCUS_DOWN)?.requestFocus()
    }

    private fun focusPrev() {
        val cur = currentFocus ?: binding.container
        cur.focusSearch(View.FOCUS_UP)?.requestFocus()
    }
}
