package com.wickedapp.rokidtg.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepoTest {

    // --------------------------------------------------------------------------
    // 1. loadHistory() populates messages flow, sorted by id ascending
    // --------------------------------------------------------------------------
    @Test
    fun `loadHistory populates messages sorted ascending by id`() = runTest {
        val td = FakeMessageTdLib()
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = MessageRepo(td, chatId = 100L, scope = repoScope)

        // Queue a batch with ids out of order to verify sorting
        td.queueMessages(listOf(
            FakeMessageTdLib.textMsg(id = 30L, chatId = 100L, text = "C"),
            FakeMessageTdLib.textMsg(id = 10L, chatId = 100L, text = "A"),
            FakeMessageTdLib.textMsg(id = 20L, chatId = 100L, text = "B"),
        ))

        repo.loadHistory()
        advanceUntilIdle()

        val ids = repo.messages.first().map { it.id }
        assertEquals(listOf(10L, 20L, 30L), ids)

        repoScope.cancel()
    }

    // --------------------------------------------------------------------------
    // 2. loadOlder() prepends older messages without removing newer ones
    // --------------------------------------------------------------------------
    @Test
    fun `loadOlder prepends messages and preserves existing ones`() = runTest {
        val td = FakeMessageTdLib()
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = MessageRepo(td, chatId = 100L, scope = repoScope)

        // Initial load: ids 20, 30
        td.queueMessages(listOf(
            FakeMessageTdLib.textMsg(id = 20L, chatId = 100L, text = "B"),
            FakeMessageTdLib.textMsg(id = 30L, chatId = 100L, text = "C"),
        ))
        repo.loadHistory()
        advanceUntilIdle()

        // Older page: ids 5, 10 (older than 20)
        td.queueMessages(listOf(
            FakeMessageTdLib.textMsg(id = 5L,  chatId = 100L, text = "older-A"),
            FakeMessageTdLib.textMsg(id = 10L, chatId = 100L, text = "older-B"),
        ))
        repo.loadOlder()
        advanceUntilIdle()

        val ids = repo.messages.first().map { it.id }
        // All 4 messages, sorted ascending: 5,10,20,30
        assertEquals(listOf(5L, 10L, 20L, 30L), ids)

        repoScope.cancel()
    }

    // --------------------------------------------------------------------------
    // 3. UpdateNewMessage for the correct chatId appears in messages
    // --------------------------------------------------------------------------
    @Test
    fun `UpdateNewMessage for matching chatId is added to messages`() = runTest {
        val td = FakeMessageTdLib()
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = MessageRepo(td, chatId = 100L, scope = repoScope)

        // Start with one loaded message
        td.queueMessages(listOf(
            FakeMessageTdLib.textMsg(id = 10L, chatId = 100L, text = "hello"),
        ))
        repo.loadHistory()
        advanceUntilIdle()

        // Emit an inbound message for the same chat
        td.emitNewMessage(chatId = 100L, msgId = 99L, text = "world")
        advanceUntilIdle()

        val msgs = repo.messages.first()
        val ids = msgs.map { it.id }
        assertTrue("new message id=99 should be present", 99L in ids)
        // Both old and new
        assertEquals(listOf(10L, 99L), ids)
        val newRow = msgs.first { it.id == 99L }
        assertTrue(newRow is MsgRow.Text)
        assertEquals("world", (newRow as MsgRow.Text).text)

        repoScope.cancel()
    }

    // --------------------------------------------------------------------------
    // 4. UpdateNewMessage for a DIFFERENT chatId is IGNORED
    // --------------------------------------------------------------------------
    @Test
    fun `UpdateNewMessage for different chatId is ignored`() = runTest {
        val td = FakeMessageTdLib()
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = MessageRepo(td, chatId = 100L, scope = repoScope)

        td.queueMessages(listOf(
            FakeMessageTdLib.textMsg(id = 10L, chatId = 100L, text = "hello"),
        ))
        repo.loadHistory()
        advanceUntilIdle()

        // Emit for a DIFFERENT chat (chatId=999)
        td.emitNewMessage(chatId = 999L, msgId = 55L, text = "not for us")
        advanceUntilIdle()

        val ids = repo.messages.first().map { it.id }
        // Should still contain only id=10; id=55 must not appear
        assertEquals(listOf(10L), ids)

        repoScope.cancel()
    }

    @Test
    fun `sticker emoji and generic non-text messages render as readable rows`() = runTest {
        val td = FakeMessageTdLib()
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = MessageRepo(td, chatId = 100L, scope = repoScope)

        val sticker = TdApi.MessageSticker().apply {
            this.sticker = TdApi.Sticker().apply {
                emoji = "🔥"
                sticker = TdApi.File().apply { id = 777 }
            }
        }
        val dice = TdApi.MessageDice().apply {
            emoji = "🎲"
            value = 6
        }
        td.queueMessages(listOf(
            FakeMessageTdLib.msg(id = 10L, chatId = 100L, content = sticker),
            FakeMessageTdLib.msg(id = 20L, chatId = 100L, content = dice),
        ))

        repo.loadHistory()
        advanceUntilIdle()

        val rows = repo.messages.first()
        val stickerRow = rows[0]
        assertTrue(stickerRow is MsgRow.Sticker)
        assertEquals("🔥", (stickerRow as MsgRow.Sticker).emoji)
        assertTrue(stickerRow.label.contains("sticker"))
        val diceRow = rows[1]
        assertTrue(diceRow is MsgRow.Unsupported)
        assertEquals("🎲 dice 6", (diceRow as MsgRow.Unsupported).label)

        repoScope.cancel()
    }
}
