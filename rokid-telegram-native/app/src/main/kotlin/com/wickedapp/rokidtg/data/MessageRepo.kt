package com.wickedapp.rokidtg.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
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

    /** Load the 30 most recent messages (fromMessageId=0 means from newest). */
    suspend fun loadHistory() = load(fromMessageId = 0L, limit = 30)

    /** Prepend 30 messages older than the oldest cached message. */
    suspend fun loadOlder() {
        val oldest = synchronized(cache) { cache.firstKey2() }
        load(fromMessageId = oldest, limit = 30)
    }

    private suspend fun load(fromMessageId: Long, limit: Int) {
        val req = TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)

        val result: TdApi.Messages = suspendCoroutine { cont ->
            td.send(req) { r ->
                if (r is TdApi.Messages) cont.resumeWith(Result.success(r))
                else cont.resumeWithException(IllegalStateException("GetChatHistory failed: $r"))
            }
        }

        val msgs = result.messages ?: return
        if (msgs.isEmpty()) return

        msgs.forEach { put(toRow(it)) }

        // Mark as viewed — non-critical, fire-and-forget
        val ids = msgs.map { it.id }.toLongArray()
        td.send(TdApi.ViewMessages(chatId, ids, null, true)) {}
    }

    private fun put(row: MsgRow) {
        synchronized(cache) {
            cache[row.id] = row
            _messages.value = cache.values.toList()
        }
    }

    private fun toRow(m: TdApi.Message): MsgRow = when (val c = m.content) {
        is TdApi.MessageText -> MsgRow.Text(m.id, m.date, m.isOutgoing, c.text.text)

        is TdApi.MessagePhoto -> {
            // v1.8.65: Photo.sizes is PhotoSize[], PhotoSize.photo is TdApi.File with .id
            val sizes = c.photo?.sizes
            if (!sizes.isNullOrEmpty()) {
                val biggest = sizes.maxByOrNull { it.width * it.height } ?: sizes.last()
                MsgRow.Photo(m.id, m.date, m.isOutgoing, biggest.photo.id, biggest.width, biggest.height)
            } else {
                MsgRow.Unsupported(m.id, m.date, m.isOutgoing, "photo(no-sizes)")
            }
        }

        is TdApi.MessageVideo -> {
            // v1.8.65: MessageVideo.video is TdApi.Video, Video.video is TdApi.File
            val vid = c.video
            if (vid != null) {
                MsgRow.Video(m.id, m.date, m.isOutgoing, vid.video.id, vid.duration)
            } else {
                MsgRow.Unsupported(m.id, m.date, m.isOutgoing, "video(null)")
            }
        }

        is TdApi.MessageVoiceNote -> {
            // v1.8.65: MessageVoiceNote.voiceNote is TdApi.VoiceNote, VoiceNote.voice is TdApi.File
            val vn = c.voiceNote
            if (vn != null) {
                MsgRow.Voice(m.id, m.date, m.isOutgoing, vn.voice.id, vn.duration)
            } else {
                MsgRow.Unsupported(m.id, m.date, m.isOutgoing, "voice(null)")
            }
        }

        else -> MsgRow.Unsupported(m.id, m.date, m.isOutgoing, c.javaClass.simpleName)
    }
}

/** Returns 0 if the map is empty (GetChatHistory interprets 0 as "from newest"). */
private fun TreeMap<Long, *>.firstKey2(): Long = if (isEmpty()) 0L else firstKey()
