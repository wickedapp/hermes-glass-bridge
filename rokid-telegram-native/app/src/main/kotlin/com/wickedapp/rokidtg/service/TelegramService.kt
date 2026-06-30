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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

class TelegramService : LifecycleService() {

    private var client: TdLibClient? = null
    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized

    var notifications: NotificationCenter? = null
        private set

    inner class LocalBinder : Binder() {
        fun getClient(): TdLibClient? = client
        fun getAuthorizedFlow(): StateFlow<Boolean> = this@TelegramService.authorized
        fun getNotifications(): NotificationCenter? = notifications
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildOngoingNotif())

        val c = TdLibClient(
            dbDir    = File(filesDir, "tdlib/db"),
            filesDir = File(filesDir, "tdlib/files"),
            apiId    = BuildConfig.TG_API_ID,
            apiHash  = BuildConfig.TG_API_HASH,
        )
        client = c
        notifications = NotificationCenter(this, c, lifecycleScope)
        NetworkMonitor(this, c)

        lifecycleScope.launch {
            c.updates.filterIsInstance<TdApi.UpdateAuthorizationState>().collect { upd ->
                Timber.tag("TG").i("auth=%s", upd.authorizationState.javaClass.simpleName)
                _authorized.value = upd.authorizationState is TdApi.AuthorizationStateReady
            }
        }
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
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
            .setSmallIcon(R.drawable.ic_unread_dot)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHAN_ID  = "tg-ongoing"
    }
}
