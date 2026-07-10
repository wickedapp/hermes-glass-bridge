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
     * Lazy-load only the newest visible slice. Older messages are pulled in small
     * batches only when the user moves upward past the oldest loaded row.
     */
    suspend fun loadHistory() {
        loadInitial(limit = INITIAL_BBS_HISTORY_LIMIT)
    }

    suspend fun loadInitial(limit: Int = INITIAL_BBS_HISTORY_LIMIT): Int {
        var received = load(fromMessageId = 0L, limit = limit)
        if (received < limit) {
            // First GetChatHistory after OpenChat often returns only cached lastMessage.
            // Retry once after TDLib has had a beat to fetch the actual latest slice.
            delay(250)
            received += load(fromMessageId = 0L, limit = limit)
        }
        return received
    }

    /** Prepend a small batch older than the oldest cached message. */
    suspend fun loadOlder(limit: Int = OLDER_BBS_HISTORY_LIMIT): Int {
        return loadOlderInternal(limit = limit)
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
        if (msgs.isEmpty()) {
            Timber.tag("MsgRepo").i(
                "GetChatHistory chat=%d from=%d limit=%d -> 0 msgs inserted=0 totalCount=%d",
                chatId, fromMessageId, limit, result.totalCount
            )
            return 0
        }

        val rows = msgs.map { toRow(it) }
        val inserted = synchronized(cache) {
            var count = 0
            rows.forEach { row ->
                if (!cache.containsKey(row.id)) count += 1
                cache[row.id] = row
            }
            _messages.value = cache.values.toList()
            count
        }

        Timber.tag("MsgRepo").i(
            "GetChatHistory chat=%d from=%d limit=%d -> %d msgs inserted=%d totalCount=%d",
            chatId, fromMessageId, limit, msgs.size, inserted, result.totalCount
        )

        // Mark as viewed — non-critical, fire-and-forget
        val ids = msgs.map { it.id }.toLongArray()
        td.send(TdApi.ViewMessages(chatId, ids, null, true)) {}
        return inserted
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

            is TdApi.MessageSticker -> {
                val sticker = c.sticker
                val emoji = sticker?.emoji?.takeIf { it.isNotBlank() }.orEmpty()
                val labelText = listOf(emoji, text(R.string.message_sticker)).filter { it.isNotBlank() }.joinToString(" ")
                MsgRow.Sticker(m.id, m.date, m.isOutgoing, label, sticker?.sticker?.id, emoji, labelText)
            }

            is TdApi.MessageAnimatedEmoji -> MsgRow.Sticker(
                m.id,
                m.date,
                m.isOutgoing,
                label,
                c.animatedEmoji?.sticker?.sticker?.id,
                c.emoji.orEmpty(),
                c.emoji?.takeIf { it.isNotBlank() } ?: text(R.string.message_emoji)
            )

            else -> MsgRow.Unsupported(m.id, m.date, m.isOutgoing, label, contentSummary(c))
        }
        requestSenderNameIfNeeded(m)
        return row
    }

    private fun contentSummary(c: TdApi.MessageContent): String = when (c) {
        is TdApi.MessageAnimation -> bracket(text(R.string.message_animation), c.caption?.text)
        is TdApi.MessageAudio -> bracket(text(R.string.message_audio), listOf(c.audio?.performer, c.audio?.title, c.caption?.text).firstNonBlank())
        is TdApi.MessageDocument -> bracket(text(R.string.message_document), listOf(c.document?.fileName, c.caption?.text).firstNonBlank())
        is TdApi.MessageVideoNote -> bracket(text(R.string.message_video_note), c.videoNote?.duration?.let { "${it}s" })
        is TdApi.MessageDice -> listOf(c.emoji, text(R.string.message_dice), c.value.takeIf { it > 0 }?.toString()).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        is TdApi.MessageContact -> bracket(text(R.string.message_contact), listOf(c.contact?.firstName, c.contact?.lastName, c.contact?.phoneNumber).filterNotNull().filter { it.isNotBlank() }.joinToString(" "))
        is TdApi.MessageLocation -> bracket(text(R.string.message_location), c.location?.let { "%.4f,%.4f".format(it.latitude, it.longitude) })
        is TdApi.MessageVenue -> bracket(text(R.string.message_location), c.venue?.title)
        is TdApi.MessagePoll -> bracket(text(R.string.message_poll), c.poll?.question?.text)
        is TdApi.MessageCall -> text(R.string.message_call)
        is TdApi.MessageInvoice -> bracket(text(R.string.message_invoice), c.productInfo?.title)
        is TdApi.MessageGame -> bracket(text(R.string.message_game), c.game?.title)
        is TdApi.MessageExpiredPhoto -> text(R.string.message_expired_photo)
        is TdApi.MessageExpiredVideo -> text(R.string.message_expired_video)
        is TdApi.MessageUnsupported -> text(R.string.message_unsupported)
        is TdApi.MessageCustomServiceAction -> c.text.takeIf { it.isNotBlank() } ?: text(R.string.message_service)
        is TdApi.MessageBasicGroupChatCreate -> bracket(text(R.string.message_service), c.title)
        is TdApi.MessageSupergroupChatCreate -> bracket(text(R.string.message_service), c.title)
        is TdApi.MessageChatChangeTitle -> bracket(text(R.string.message_service), c.title)
        is TdApi.MessageChatAddMembers -> text(R.string.message_service)
        is TdApi.MessageChatDeleteMember -> text(R.string.message_service)
        is TdApi.MessageChatJoinByLink -> text(R.string.message_service)
        is TdApi.MessageChatJoinByRequest -> text(R.string.message_service)
        is TdApi.MessagePinMessage -> text(R.string.message_pinned)
        is TdApi.MessageScreenshotTaken -> text(R.string.message_screenshot)
        else -> bracket(humanizeMessageClass(c.javaClass.simpleName), null)
    }

    private fun bracket(kind: String, detail: String?): String =
        if (detail.isNullOrBlank()) "[$kind]" else "[$kind] $detail"

    private fun List<String?>.firstNonBlank(): String? = firstOrNull { !it.isNullOrBlank() }

    private fun humanizeMessageClass(simpleName: String): String {
        val raw = simpleName.removePrefix("Message").ifBlank { text(R.string.message_unsupported) }
        return raw.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
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
        is MsgRow.Sticker -> copy(senderLabel = name)
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
            R.string.message_sticker -> "sticker"
            R.string.message_emoji -> "emoji"
            R.string.message_animation -> "GIF"
            R.string.message_audio -> "audio"
            R.string.message_document -> "file"
            R.string.message_video_note -> "video note"
            R.string.message_dice -> "dice"
            R.string.message_contact -> "contact"
            R.string.message_location -> "location"
            R.string.message_poll -> "poll"
            R.string.message_call -> "call"
            R.string.message_invoice -> "invoice"
            R.string.message_game -> "game"
            R.string.message_service -> "service"
            R.string.message_pinned -> "pinned message"
            R.string.message_screenshot -> "screenshot taken"
            R.string.message_expired_photo -> "expired photo"
            R.string.message_expired_video -> "expired video"
            else -> ""
        }
}

private const val INITIAL_BBS_HISTORY_LIMIT = 14
private const val OLDER_BBS_HISTORY_LIMIT = 10

/** Returns 0 if the map is empty (GetChatHistory interprets 0 as "from newest"). */
private fun TreeMap<Long, *>.firstKey2(): Long = if (isEmpty()) 0L else firstKey()
