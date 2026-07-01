package com.wickedapp.rokidtg.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.ChatPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class NotificationCenter(
    private val ctx: Context,
    private val td: TdLibClient,
    private val scope: CoroutineScope,
    private val prefs: ChatPrefs,
) {
    @Volatile var currentOpenChatId: Long? = null

    init {
        ensureChannel()
        scope.launch {
            td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect { upd ->
                val m = upd.message
                if (m.isOutgoing) return@collect
                if (m.chatId == currentOpenChatId) return@collect
                if (prefs.isMuted(m.chatId)) return@collect
                td.send(TdApi.GetChat(m.chatId)) { obj ->
                    if (obj is TdApi.Chat && obj.notificationSettings.muteFor == 0 && !prefs.isMuted(m.chatId)) {
                        post(obj, m)
                    }
                }
            }
        }
    }

    private fun post(chat: TdApi.Chat, m: TdApi.Message) {
        val preview = (m.content as? TdApi.MessageText)?.text?.text?.take(60)
            ?: m.content.javaClass.simpleName
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("openChatId", m.chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            m.chatId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, CHAN_BANNER)
            .setContentTitle(chat.title)
            .setContentText(preview)
            .setSmallIcon(R.drawable.ic_stat_rokid_tg)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify(m.chatId.hashCode(), notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHAN_BANNER,
            "New messages",
            NotificationManager.IMPORTANCE_HIGH,
        )
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHAN_BANNER = "tg-banner"
    }
}
