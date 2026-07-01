package com.wickedapp.rokidtg.data

import android.content.Context

/** Local glasses-first chat controls that TDLib does not need to own.
 *
 * Pinning is intentionally local: it lets the Rokid HUD keep five high-priority
 * conversations at the top without depending on the user's Telegram cloud pin
 * limits or desktop/mobile layout. Muting is also local to this glasses app.
 */
class ChatPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("chat_controls", Context.MODE_PRIVATE)

    fun pinnedChatIds(): List<Long> = prefs.getString(KEY_PINNED, "")
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .distinct()
        .take(MAX_PINNED)

    fun pinnedSet(): Set<Long> = pinnedChatIds().toSet()

    fun isPinned(chatId: Long): Boolean = chatId in pinnedSet()

    /** Returns false when the pin limit is already reached. */
    fun setPinned(chatId: Long, pinned: Boolean): Boolean {
        val current = pinnedChatIds().toMutableList()
        val changed = if (pinned) {
            if (chatId in current) false
            else {
                if (current.size >= MAX_PINNED) return false
                current.add(chatId)
                true
            }
        } else {
            current.remove(chatId)
        }
        if (changed) savePinned(current)
        return true
    }

    fun togglePinned(chatId: Long): Boolean = setPinned(chatId, !isPinned(chatId))

    fun mutedChatIds(): Set<Long> = prefs.getStringSet(KEY_MUTED, emptySet())
        .orEmpty()
        .mapNotNull { it.toLongOrNull() }
        .toSet()

    fun isMuted(chatId: Long): Boolean = chatId in mutedChatIds()

    fun setMuted(chatId: Long, muted: Boolean) {
        val next = mutedChatIds().toMutableSet()
        if (muted) next.add(chatId) else next.remove(chatId)
        prefs.edit().putStringSet(KEY_MUTED, next.map { it.toString() }.toSet()).apply()
    }

    fun toggleMuted(chatId: Long): Boolean {
        val next = !isMuted(chatId)
        setMuted(chatId, next)
        return next
    }

    private fun savePinned(ids: List<Long>) {
        prefs.edit().putString(KEY_PINNED, ids.distinct().take(MAX_PINNED).joinToString(",")).apply()
    }

    companion object {
        const val MAX_PINNED = 5
        private const val KEY_PINNED = "pinned_chat_ids"
        private const val KEY_MUTED = "muted_chat_ids"
    }
}
