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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoTest {
    @Test fun loadInitial_then_live_update_changes_order() = runTest {
        val td = FakeChatTdLib()
        // Use the runTest scheduler so advanceUntilIdle drives ChatRepo's coroutines,
        // but give it its own Job so the infinite subscribeUpdates() doesn't block runTest.
        val repoScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = ChatRepo(td, repoScope)
        td.queueChats(listOf(
            ChatRow(1, "Alice", "hi",   0, 1_000),
            ChatRow(2, "Bob",   "yo",   0, 2_000),
        ))
        repo.loadInitial()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), repo.chats.first().map { it.id })
        td.emitNewMessage(chatId = 1, preview = "are you up?", ts = 3_000)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), repo.chats.first().map { it.id })
        repoScope.cancel()
    }
}
