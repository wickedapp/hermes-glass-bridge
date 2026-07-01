package com.wickedapp.rokidtg.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TdLibClientTest {
    @Test fun queue_handler_invoked_on_response() = runBlocking {
        val client = FakeTdLibClient()
        var received: String? = null
        client.send(query = "ping") { received = it as String }
        client.deliver("ping" to "pong")
        assertEquals("pong", received)
    }

    @Test fun updates_flow_emits_in_order() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeTdLibClient()
        val collected = mutableListOf<String>()
        // backgroundScope inherits UnconfinedTestDispatcher — starts collecting immediately
        val job = backgroundScope.launch { client.updates.collect { collected += it } }
        client.deliverUpdate("u1")
        client.deliverUpdate("u2")
        job.cancel()
        assertEquals(listOf("u1", "u2"), collected)
    }
}
