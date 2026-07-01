package com.wickedapp.rokidtg.data

import kotlinx.coroutines.CoroutineScope
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
     * Load the 30 most recent messages. fromMessageId=0 means "from newest".
     *
     * Retries on a short result because TDLib's first GetChatHistory after
     * OpenChat typically returns only the locally-cached lastMessage (often
     * just 1 row from the chat-list sync). The first call triggers the server
     * fetch; the actual history shows up on the next call. We keep calling
     * until the result is short — meaning we've reached the start of history.
     */
    suspend fun loadHistory() {
        val limit = 30
        // Initial newest-first fetch.
        val firstBatch = load(fromMessageId = 0L, limit = limit)
        if (firstBatch in 1 until limit) {
            // Likely returned only the cached lastMessage — pull older to force a real server fetch.
            val oldest = synchronized(cache) { cache.firstKey2() }
            load(fromMessageId = oldest, limit = limit)
        } else if (firstBatch == 0) {
            // Empty first call; retry once — server fetch should have arrived by now.
            load(fromMessageId = 0L, limit = limit)
        }
    }

    /** Prepend 30 messages older than the oldest cached message. */
    suspend fun loadOlder() {
        val oldest = synchronized(cache) { cache.firstKey2() }
        load(fromMessageId = oldest, limit = 30)
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
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, "photo(no-sizes)")
                }
            }

            is TdApi.MessageVideo -> {
                val vid = c.video
                if (vid != null) {
                    MsgRow.Video(m.id, m.date, m.isOutgoing, label, vid.video.id, vid.duration)
                } else {
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, "video(null)")
                }
            }

            is TdApi.MessageVoiceNote -> {
                val vn = c.voiceNote
                if (vn != null) {
                    MsgRow.Voice(m.id, m.date, m.isOutgoing, label, vn.voice.id, vn.duration)
                } else {
                    MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, "voice(null)")
                }
            }

            else -> MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, c.javaClass.simpleName)
        }
        requestSenderNameIfNeeded(m)
        return row
    }

    private fun senderLabel(m: TdApi.Message): String {
        if (m.isOutgoing) return "Me"
        val sig = runCatching { m.authorSignature }.getOrNull()
        if (!sig.isNullOrBlank()) return sig
        val key = senderKey(m) ?: return "對方"
        senderNames[key]?.let { return it }
        return when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> "User ${s.userId.toString().takeLast(4)}"
            is TdApi.MessageSenderChat -> "Chat ${s.chatId.toString().takeLast(4)}"
            else -> "對方"
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
                val fallback = "User ${s.userId.toString().takeLast(4)}"
                val full = listOf(user.firstName, user.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                    .ifBlank { user.usernames?.activeUsernames?.firstOrNull() ?: fallback }
                cacheSenderName(key, fallback, full)
            }
            is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(s.chatId)) { obj ->
                val chat = obj as? TdApi.Chat ?: return@send
                val fallback = "Chat ${s.chatId.toString().takeLast(4)}"
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
}

/** Returns 0 if the map is empty (GetChatHistory interprets 0 as "from newest"). */
private fun TreeMap<Long, *>.firstKey2(): Long = if (isEmpty()) 0L else firstKey()
