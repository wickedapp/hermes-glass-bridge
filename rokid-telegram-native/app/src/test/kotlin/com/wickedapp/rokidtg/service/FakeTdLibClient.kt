package com.wickedapp.rokidtg.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Minimal fake matching the TdLibClient contract for unit tests. */
class FakeTdLibClient {
    private val pending = mutableMapOf<String, (Any) -> Unit>()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()
    fun send(query: String, handler: (Any) -> Unit) { pending[query] = handler }
    fun deliver(pair: Pair<String, Any>) { pending.remove(pair.first)?.invoke(pair.second) }
    suspend fun deliverUpdate(u: String) { _updates.emit(u) }
}
