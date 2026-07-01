package com.wickedapp.rokidtg.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal interface over TdLibClient so ChatRepo can be unit-tested with FakeChatTdLib.
 */
interface TdClientFacade {
    val updates: SharedFlow<TdApi.Update>
    fun send(query: TdApi.Function<*>, handler: (TdApi.Object) -> Unit)
}

data class ChatRow(
    val id: Long,
    val title: String,
    val preview: String,
    val unreadCount: Int,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
)

class ChatRepo(
    private val td: TdClientFacade,
    private val scope: CoroutineScope,
    private val prefs: ChatPrefs? = null,
) {

    private val cache = ConcurrentHashMap<Long, ChatRow>()
    private val _chats = MutableStateFlow<List<ChatRow>>(emptyList())
    val chats: StateFlow<List<ChatRow>> = _chats

    init {
        scope.launch { subscribeUpdates() }
    }

    suspend fun loadInitial() {
        td.send(TdApi.LoadChats(TdApi.ChatListMain(), 50)) {}
        td.send(TdApi.GetChats(TdApi.ChatListMain(), 50)) { resp ->
            if (resp is TdApi.Chats) {
                for (id in resp.chatIds) {
                    td.send(TdApi.GetChat(id)) { c ->
                        if (c is TdApi.Chat) upsert(c)
                    }
                }
            }
        }
    }

    suspend fun search(query: String): List<ChatRow> =
        kotlin.coroutines.suspendCoroutine { cont ->
            // v1.8.65: SearchChatsOnServer(String, SearchChatTypeFilter?, int)
            td.send(TdApi.SearchChatsOnServer(query, null, 20)) { resp ->
                if (resp !is TdApi.Chats || resp.chatIds.isEmpty()) {
                    cont.resumeWith(Result.success(emptyList()))
                    return@send
                }
                val out = ConcurrentHashMap<Long, ChatRow>()
                val remaining = AtomicInteger(resp.chatIds.size)
                for (id in resp.chatIds) {
                    td.send(TdApi.GetChat(id)) { c ->
                        if (c is TdApi.Chat) {
                            out[c.id] = chatToRow(c)
                            upsert(c)
                        }
                        if (remaining.decrementAndGet() == 0) {
                            cont.resumeWith(
                                Result.success(out.values.sortedByDescending { it.timestamp })
                            )
                        }
                    }
                }
            }
        }

    private fun upsert(c: TdApi.Chat) {
        cache[c.id] = chatToRow(c)
        publish()
    }

    fun refreshControls() {
        cache.replaceAll { _, row ->
            row.copy(isPinned = isPinned(row.id), isMuted = isMuted(row.id))
        }
        publish()
    }

    private fun publish() {
        val pinned = prefs?.pinnedChatIds().orEmpty()
        val pinnedIndex = pinned.withIndex().associate { it.value to it.index }
        _chats.value = cache.values.sortedWith(
            compareBy<ChatRow> { pinnedIndex[it.id] ?: Int.MAX_VALUE }
                .thenByDescending { it.timestamp }
        )
    }

    private fun chatToRow(c: TdApi.Chat): ChatRow = ChatRow(
        id = c.id,
        title = c.title,
        preview = (c.lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
        unreadCount = c.unreadCount,
        timestamp = c.lastMessage?.date?.toLong()?.times(1000L) ?: 0L,
        isPinned = isPinned(c.id),
        isMuted = isMuted(c.id),
    )

    private fun isPinned(chatId: Long): Boolean = prefs?.isPinned(chatId) == true
    private fun isMuted(chatId: Long): Boolean = prefs?.isMuted(chatId) == true

    private suspend fun subscribeUpdates() {
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect { upd ->
            td.send(TdApi.GetChat(upd.message.chatId)) { c ->
                if (c is TdApi.Chat) upsert(c)
            }
        }
    }
}
