package com.wickedapp.rokidtg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.BuildConfig
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.ChatPrefs
import com.wickedapp.rokidtg.data.ChatRepo
import com.wickedapp.rokidtg.data.MessageRepo
import com.wickedapp.rokidtg.ui.BannerHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

class TelegramService : LifecycleService() {

    private var client: TdLibClient? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized

    /** Last error surfaced from any auth submit call (cleared on next successful state transition). */
    private val _lastAuthError = MutableStateFlow<String?>(null)
    val lastAuthError: StateFlow<String?> = _lastAuthError

    var notifications: NotificationCenter? = null
        private set

    private lateinit var chatPrefs: ChatPrefs

    /** Single ChatRepo for this service lifetime; retains its live-update cache. */
    private var chatRepo: ChatRepo? = null

    /** Per-chat MessageRepo cache; keyed by chatId so history is retained across back-stack entries. */
    private val messageRepos = mutableMapOf<Long, MessageRepo>()
    private var networkMonitor: NetworkMonitor? = null

    inner class LocalBinder : Binder() {
        fun getClient(): TdLibClient? = client
        fun getAuthorizedFlow(): StateFlow<Boolean> = this@TelegramService.authorized
        fun getAuthStateFlow(): StateFlow<TdApi.AuthorizationState?> = this@TelegramService.authState
        fun getLastAuthErrorFlow(): StateFlow<String?> = this@TelegramService.lastAuthError
        fun getNotifications(): NotificationCenter? = notifications
        fun getChatPrefs(): ChatPrefs = chatPrefs

        fun clearAuthError() { _lastAuthError.value = null }

        fun submitPhoneNumber(phone: String) {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.SetAuthenticationPhoneNumber(phone, null), ::routeAuthResult)
        }

        fun submitCode(code: String) {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.CheckAuthenticationCode(code), ::routeAuthResult)
        }

        fun submitPassword(password: String) {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.CheckAuthenticationPassword(password), ::routeAuthResult)
        }

        fun submitEmailAddress(email: String) {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.SetAuthenticationEmailAddress(email), ::routeAuthResult)
        }

        fun submitEmailCode(code: String) {
            val c = client ?: return
            _lastAuthError.value = null
            val payload = TdApi.EmailAddressAuthenticationCode(code)
            c.send(TdApi.CheckAuthenticationEmailCode(payload), ::routeAuthResult)
        }

        fun submitRegistration(firstName: String, lastName: String) {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.RegisterUser(firstName, lastName, false), ::routeAuthResult)
        }

        /** Restart the auth flow from scratch (e.g. user wants to use a different phone). */
        fun resendAuthCode() {
            val c = client ?: return
            _lastAuthError.value = null
            c.send(TdApi.ResendAuthenticationCode(), ::routeAuthResult)
        }

        /** Returns (or lazily creates) the service-scoped ChatRepo. */
        fun getChatRepo(): ChatRepo? {
            val c = client ?: return null
            return chatRepo ?: ChatRepo(c, lifecycleScope, chatPrefs).also { chatRepo = it }
        }

        fun togglePinned(chatId: Long): Boolean {
            val ok = chatPrefs.togglePinned(chatId)
            chatRepo?.refreshControls()
            return ok
        }

        fun toggleMuted(chatId: Long): Boolean {
            val muted = chatPrefs.toggleMuted(chatId)
            chatRepo?.refreshControls()
            return muted
        }

        /** Returns (or lazily creates) a service-scoped MessageRepo for [chatId]. */
        fun getMessageRepo(chatId: Long): MessageRepo? {
            val c = client ?: return null
            return messageRepos.getOrPut(chatId) { MessageRepo(c, chatId, lifecycleScope) }
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildOngoingNotif())
        chatPrefs = ChatPrefs(this)

        val c = TdLibClient(
            dbDir    = File(filesDir, "tdlib/db"),
            filesDir = File(filesDir, "tdlib/files"),
            apiId    = BuildConfig.TG_API_ID,
            apiHash  = BuildConfig.TG_API_HASH,
        )
        client = c
        notifications = NotificationCenter(this, c, lifecycleScope, chatPrefs)
        networkMonitor = NetworkMonitor(this, c)

        lifecycleScope.launch {
            c.updates.filterIsInstance<TdApi.UpdateAuthorizationState>().collect { upd ->
                val s = upd.authorizationState
                Timber.tag("TG").i("auth=%s", s.javaClass.simpleName)
                _authState.value = s
                _authorized.value = s is TdApi.AuthorizationStateReady
                if (s is TdApi.AuthorizationStateClosed) {
                    BannerHost.show("Session ended. Sign in again.", BannerHost.Kind.WARN, 10_000)
                }
            }
        }
    }

    override fun onDestroy() {
        networkMonitor?.close()
        networkMonitor = null
        client?.close()
        client = null
        super.onDestroy()
    }

    private fun routeAuthResult(obj: TdApi.Object) {
        if (obj is TdApi.Error) {
            val msg = "${obj.code}: ${obj.message}"
            Timber.tag("TG").w("auth error %s", msg)
            _lastAuthError.value = msg
        }
    }

    private fun buildOngoingNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHAN_ID,
                "Telegram running",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHAN_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("connected")
            .setSmallIcon(R.drawable.ic_stat_rokid_tg)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHAN_ID  = "tg-ongoing"
    }
}
