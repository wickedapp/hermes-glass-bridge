package com.wickedapp.rokidtg.data

import android.content.Context
import com.wickedapp.rokidtg.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.util.TreeMap
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MessageRepo(
    private val td: TdClientFacade,
    private val chatId: Long,
    scope: CoroutineScope,
    private val context: Context? = null,
) {
    // Keyed by message id (ascending) so values() is oldest-first
    private val cache = TreeMap<Long, MsgRow>()
    private val senderNames = mutableMapOf<String, String>()
    private val pendingSenderNames = mutableSetOf<String>()
    private val _messages = MutableStateFlow<List<MsgRow>>(emptyList())
    val messages: StateFlow<List<MsgRow>> = _messages

    init {
        scope.launch {
            td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect { upd ->
                if (upd.message.chatId == chatId) {
                    put(toRow(upd.message))
                }
            }
        }
    }

    /**
     * Load at least the 100 most recent messages when available. The chat view only
     * renders a few rows at a time, but the adapter must have a real history buffer
     * so active message mode can walk one row at a time instead of getting stuck at
     * the 3-5 visible rows.
     */
    suspend fun loadHistory() {
        loadHistory(minMessages = 100)
    }

    suspend fun loadHistory(minMessages: Int) {
        val limit = 50
        var received = load(fromMessageId = 0L, limit = limit)
        if (received == 0 || synchronized(cache) { cache.size } == 0) {
            // OpenChat sometimes only primes TDLib; give it a short beat then retry.
            delay(250)
            received = load(fromMessageId = 0L, limit = limit)
        }
        var safety = 0
        while (synchronized(cache) { cache.size } < minMessages && received > 0 && safety++ < 8) {
            val before = synchronized(cache) { cache.size }
            received = loadOlderInternal(limit)
            val after = synchronized(cache) { cache.size }
            if (after <= before) break
            if (received < limit) break
        }
    }

    /** Prepend messages older than the oldest cached message. */
    suspend fun loadOlder() {
        loadOlderInternal(limit = 50)
    }

    private suspend fun loadOlderInternal(limit: Int): Int {
        val oldest = synchronized(cache) { cache.firstKey2() }
        return load(fromMessageId = oldest, limit = limit)
    }

    /** Returns the number of messages received (0 if empty/error). */
    private suspend fun load(fromMessageId: Long, limit: Int): Int {
        val req = TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)

        val result: TdApi.Messages = suspendCoroutine { cont ->
            td.send(req) { r ->
                if (r is TdApi.Messages) cont.resumeWith(Result.success(r))
                else cont.resumeWithException(IllegalStateException("GetChatHistory failed: $r"))
            }
        }

        val msgs = result.messages ?: emptyArray()
        Timber.tag("MsgRepo").i("GetChatHistory chat=%d from=%d limit=%d -> %d msgs (totalCount=%d)",
            chatId, fromMessageId, limit, msgs.size, result.totalCount)
        if (msgs.isEmpty()) return 0

        msgs.forEach { put(toRow(it)) }

        // Mark as viewed — non-critical, fire-and-forget
        val ids = msgs.map { it.id }.toLongArray()
        td.send(TdApi.ViewMessages(chatId, ids, null, true)) {}
        return msgs.size
    }

    private fun put(row: MsgRow) {
        synchronized(cache) {
            cache[row.id] = row
            _messages.value = cache.values.toList()
        }
    }

    private fun toRow(m: TdApi.Message): MsgRow {
        val label = senderLabel(m)
        val row = when (val c = m.content) {
            is TdApi.MessageText -> MsgRow.Text(m.id, m.date, m.isOutgoing, label, c.text.text)

            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes
                if (!sizes.isNullOrEmpty()) {
                    val biggest = sizes.maxByOrNull { it.width * it.height } ?: sizes.last()
                    MsgRow.Photo(m.id, m.date, m.isOutgoing, label, biggest.photo.id, biggest.width, biggest.height)
                } else {
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, text(R.string.message_photo))
                }
            }

            is TdApi.MessageVideo -> {
                val vid = c.video
                if (vid != null) {
                    MsgRow.Video(m.id, m.date, m.isOutgoing, label, vid.video.id, vid.duration)
                } else {
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, text(R.string.message_video))
                }
            }

            is TdApi.MessageVoiceNote -> {
                val vn = c.voiceNote
                if (vn != null) {
                    MsgRow.Voice(m.id, m.date, m.isOutgoing, label, vn.voice.id, vn.duration)
                } else {
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, text(R.string.message_voice))
                }
            }

            else -> MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, c.javaClass.simpleName)
        }
        requestSenderNameIfNeeded(m)
        return row
    }

    private fun senderLabel(m: TdApi.Message): String {
        if (m.isOutgoing) return text(R.string.sender_me)
        val sig = runCatching { m.authorSignature }.getOrNull()
        if (!sig.isNullOrBlank()) return sig
        val key = senderKey(m) ?: return text(R.string.sender_unknown)
        senderNames[key]?.let { return it }
        return when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> text(R.string.sender_user_fallback, s.userId.toString().takeLast(4))
            is TdApi.MessageSenderChat -> text(R.string.sender_chat_fallback, s.chatId.toString().takeLast(4))
            else -> text(R.string.sender_unknown)
        }
    }

    private fun senderKey(m: TdApi.Message): String? = when (val s = m.senderId) {
        is TdApi.MessageSenderUser -> "u:${s.userId}"
        is TdApi.MessageSenderChat -> "c:${s.chatId}"
        else -> null
    }

    private fun requestSenderNameIfNeeded(m: TdApi.Message) {
        if (m.isOutgoing) return
        val key = senderKey(m) ?: return
        synchronized(senderNames) {
            if (senderNames.containsKey(key) || pendingSenderNames.contains(key)) return
            pendingSenderNames.add(key)
        }
        when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> td.send(TdApi.GetUser(s.userId)) { obj ->
                val user = obj as? TdApi.User ?: return@send
                val fallback = text(R.string.sender_user_fallback, s.userId.toString().takeLast(4))
                val full = listOf(user.firstName, user.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                    .ifBlank { user.usernames?.activeUsernames?.firstOrNull() ?: fallback }
                cacheSenderName(key, fallback, full)
            }
            is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(s.chatId)) { obj ->
                val chat = obj as? TdApi.Chat ?: return@send
                val fallback = text(R.string.sender_chat_fallback, s.chatId.toString().takeLast(4))
                cacheSenderName(key, fallback, chat.title.ifBlank { fallback })
            }
            else -> Unit
        }
    }

    private fun cacheSenderName(key: String, fallback: String, name: String) {
        synchronized(senderNames) {
            senderNames[key] = name
            pendingSenderNames.remove(key)
        }
        synchronized(cache) {
            var changed = false
            cache.replaceAll { _, row ->
                if (row.senderLabel == fallback) {
                    changed = true
                    row.withSenderLabel(name)
                } else row
            }
            if (changed) _messages.value = cache.values.toList()
        }
    }

    private fun MsgRow.withSenderLabel(name: String): MsgRow = when (this) {
        is MsgRow.Text -> copy(senderLabel = name)
        is MsgRow.Photo -> copy(senderLabel = name)
        is MsgRow.Video -> copy(senderLabel = name)
        is MsgRow.Voice -> copy(senderLabel = name)
        is MsgRow.Unsupported -> copy(senderLabel = name)
    }

    private fun text(resId: Int, vararg args: Any): String =
        context?.getString(resId, *args) ?: when (resId) {
            R.string.sender_me -> "Me"
            R.string.sender_unknown -> "Other"
            R.string.sender_user_fallback -> "User ${args.firstOrNull()?.toString().orEmpty()}"
            R.string.sender_chat_fallback -> "Chat ${args.firstOrNull()?.toString().orEmpty()}"
            R.string.message_photo -> "photo"
            R.string.message_video -> "video"
            R.string.message_voice -> "voice"
            else -> ""
        }
}

/** Returns 0 if the map is empty (GetChatHistory interprets 0 as "from newest"). */
private fun TreeMap<Long, *>.firstKey2(): Long = if (isEmpty()) 0L else firstKey()
