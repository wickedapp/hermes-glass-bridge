package com.wickedapp.rokidtg.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi

/**
 * Test double for TdClientFacade used by MessageRepoTest.
 *
 * Handles:
 *   send(TdApi.GetChatHistory, handler) → delivers queued TdApi.Messages
 *   send(TdApi.ViewMessages,   handler) → no-op (Ok)
 *   updates SharedFlow         → emitNewMessage pushes UpdateNewMessage
 */
class FakeMessageTdLib : TdClientFacade {

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    /** Queue of message batches; each GetChatHistory call consumes the next batch. */
    private val messageQueue = ArrayDeque<List<TdApi.Message>>()

    /** Pre-load a batch that the next GetChatHistory call will receive. */
    fun queueMessages(msgs: List<TdApi.Message>) {
        messageQueue.addLast(msgs)
    }

    override fun send(query: TdApi.Function<*>, handler: (TdApi.Object) -> Unit) {
        when (query) {
            is TdApi.GetChatHistory -> {
                val batch = messageQueue.removeFirstOrNull() ?: emptyList()
                val arr = batch.toTypedArray()
                handler(TdApi.Messages(arr.size, arr))
            }
            is TdApi.ViewMessages -> handler(TdApi.Ok())
            else -> { /* ignore */ }
        }
    }

    /** Emit an UpdateNewMessage into the updates flow. */
    suspend fun emitNewMessage(chatId: Long, msgId: Long, text: String, date: Int = 0) {
        val msg = TdApi.Message().apply {
            id = msgId
            this.chatId = chatId
            this.date = date
            isOutgoing = false
            content = TdApi.MessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                null,
            )
        }
        _updates.emit(TdApi.UpdateNewMessage(msg))
    }

    // ---------------------------------------------------------------------------
    // Helpers to build TdApi.Message objects for test data
    // ---------------------------------------------------------------------------

    companion object {
        fun textMsg(id: Long, chatId: Long, text: String, date: Int = 0): TdApi.Message =
            TdApi.Message().apply {
                this.id = id
                this.chatId = chatId
                this.date = date
                isOutgoing = false
                content = TdApi.MessageText(
                    TdApi.FormattedText(text, emptyArray()),
                    null,
                    null,
                )
            }
    }
}
