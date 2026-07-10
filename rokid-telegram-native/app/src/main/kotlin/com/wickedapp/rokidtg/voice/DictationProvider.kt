package com.wickedapp.rokidtg.voice

/**
 * Voice-to-text seam used by ReplyPanel.
 *
 * Telegram UI/TDLib/send flow stays on the glasses. Only ASR/dictation is
 * replaceable: phone CXR is the production path, Sprite helper remains fallback.
 */
interface DictationProvider {
    fun start(session: DictationSession, callback: Callback)
    fun cancel(sessionId: String)

    interface Callback {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onError(error: DictationError)
    }
}

data class DictationSession(
    val sessionId: String,
    val chatId: Long,
    val lang: String = "zh-TW",
)

data class DictationError(
    val code: String,
    val message: String = "",
) {
    companion object {
        val PhoneUnavailable = DictationError("phone_unavailable", "Phone companion is unavailable")
        val Timeout = DictationError("timeout", "Phone companion did not return ASR")
        val Empty = DictationError("empty", "No speech recognized")
    }
}
