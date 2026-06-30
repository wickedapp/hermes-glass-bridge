package com.wickedapp.rokidtg.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi

/**
 * Test double for TdLibClient, exposing only the methods ChatRepo calls.
 *
 * ChatRepo calls:
 *   send(TdApi.LoadChats, handler)
 *   send(TdApi.GetChats, handler)
 *   send(TdApi.GetChat, handler)
 *   send(TdApi.SearchChatsOnServer, handler)
 *   updates: SharedFlow<TdApi.Update>
 */
class FakeChatTdLib : TdClientFacade {
    // pending GetChat handlers keyed by chatId
    private val pendingGetChat = mutableMapOf<Long, (TdApi.Object) -> Unit>()
    // pending GetChats handler (only one at a time in tests)
    private var pendingGetChats: ((TdApi.Object) -> Unit)? = null
    // pending LoadChats handler
    private var pendingLoadChats: ((TdApi.Object) -> Unit)? = null

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    // Queued chat rows to deliver when GetChats + GetChat are called
    private var queuedChats: List<ChatRow> = emptyList()

    fun queueChats(chats: List<ChatRow>) {
        queuedChats = chats
    }

    override fun send(query: TdApi.Function<*>, handler: (TdApi.Object) -> Unit) {
        when (query) {
            is TdApi.LoadChats -> {
                // Deliver Ok immediately, then deliver Chats response for GetChats
                handler(TdApi.Ok())
            }
            is TdApi.GetChats -> {
                val ids = queuedChats.map { it.id }.toLongArray()
                handler(TdApi.Chats(ids.size, ids))
            }
            is TdApi.GetChat -> {
                val chatId = query.chatId
                val row = queuedChats.find { it.id == chatId }
                if (row != null) {
                    handler(makeTdChat(row))
                } else {
                    // check dynamic updates map
                    pendingGetChat[chatId] = handler
                }
            }
            is TdApi.SearchChatsOnServer -> {
                // Not tested in ChatRepoTest; return empty
                handler(TdApi.Chats(0, LongArray(0)))
            }
            else -> {
                // ignore unknown queries
            }
        }
    }

    /** Emit a new-message update, which triggers ChatRepo to call GetChat(chatId). */
    suspend fun emitNewMessage(chatId: Long, preview: String, ts: Long) {
        // Update our queued chats so GetChat returns updated data
        queuedChats = queuedChats.map { row ->
            if (row.id == chatId) row.copy(preview = preview, timestamp = ts) else row
        }
        val msg = TdApi.Message().apply {
            this.chatId = chatId
            this.date = (ts / 1000).toInt()
            this.content = TdApi.MessageText(
                TdApi.FormattedText(preview, emptyArray()),
                null,
                null,
            )
        }
        val upd = TdApi.UpdateNewMessage(msg)
        _updates.emit(upd)
    }

    // Build a TdApi.Chat from a ChatRow
    private fun makeTdChat(row: ChatRow): TdApi.Chat {
        val chat = TdApi.Chat()
        chat.id = row.id
        chat.title = row.title
        chat.unreadCount = row.unreadCount
        if (row.preview.isNotEmpty() || row.timestamp > 0) {
            val msg = TdApi.Message()
            msg.chatId = row.id
            msg.date = (row.timestamp / 1000).toInt()
            msg.content = TdApi.MessageText(
                TdApi.FormattedText(row.preview, emptyArray()),
                null,
                null,
            )
            chat.lastMessage = msg
        }
        return chat
    }
}
