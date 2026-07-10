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
        preview = c.lastMessage?.content?.let { preview(it) } ?: "",
        unreadCount = c.unreadCount,
        timestamp = c.lastMessage?.date?.toLong()?.times(1000L) ?: 0L,
        isPinned = isPinned(c.id),
        isMuted = isMuted(c.id),
    )

    private fun preview(c: TdApi.MessageContent): String = when (c) {
        is TdApi.MessageText -> c.text?.text.orEmpty().ifBlank { "[text]" }
        is TdApi.MessagePhoto -> bracket("photo", c.caption?.text)
        is TdApi.MessageVideo -> bracket("video", listOf(c.video?.duration?.let { "${it}s" }, c.caption?.text).firstNonBlank())
        is TdApi.MessageVoiceNote -> bracket("voice", c.voiceNote?.duration?.let { "${it}s" })
        is TdApi.MessageSticker -> listOf(c.sticker?.emoji, "[sticker]").filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        is TdApi.MessageAnimatedEmoji -> c.emoji?.takeIf { it.isNotBlank() } ?: "[emoji]"
        is TdApi.MessageDice -> listOf(c.emoji, "dice", c.value.takeIf { it > 0 }?.toString()).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        is TdApi.MessageAnimation -> bracket("GIF", c.caption?.text)
        is TdApi.MessageAudio -> bracket("audio", listOf(c.audio?.performer, c.audio?.title, c.caption?.text).firstNonBlank())
        is TdApi.MessageDocument -> bracket("file", listOf(c.document?.fileName, c.caption?.text).firstNonBlank())
        is TdApi.MessageVideoNote -> bracket("video note", c.videoNote?.duration?.let { "${it}s" })
        is TdApi.MessageContact -> bracket("contact", listOf(c.contact?.firstName, c.contact?.lastName, c.contact?.phoneNumber).filterNotNull().filter { it.isNotBlank() }.joinToString(" "))
        is TdApi.MessageLocation -> "[location]"
        is TdApi.MessageVenue -> bracket("location", c.venue?.title)
        is TdApi.MessagePoll -> bracket("poll", c.poll?.question?.text)
        is TdApi.MessageCustomServiceAction -> c.text.takeIf { it.isNotBlank() } ?: "[service]"
        is TdApi.MessageUnsupported -> "[unsupported]"
        else -> bracket(c.javaClass.simpleName.removePrefix("Message").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase(), null)
    }

    private fun bracket(kind: String, detail: String?): String = if (detail.isNullOrBlank()) "[$kind]" else "[$kind] $detail"

    private fun List<String?>.firstNonBlank(): String? = firstOrNull { !it.isNullOrBlank() }

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
