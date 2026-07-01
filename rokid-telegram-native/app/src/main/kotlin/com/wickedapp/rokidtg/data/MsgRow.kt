package com.wickedapp.rokidtg.data

sealed class MsgRow {
    abstract val id: Long
    abstract val date: Int
    abstract val isOutgoing: Boolean

    data class Text(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        val text: String,
    ) : MsgRow()

    /** Placeholder — full viewer added in Task 13. */
    data class Photo(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        val fileId: Int,
        val width: Int,
        val height: Int,
    ) : MsgRow()

    /** Placeholder — full viewer added in Task 13. */
    data class Video(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        val fileId: Int,
        val durationS: Int,
    ) : MsgRow()

    /** Placeholder — full viewer added in Task 13. */
    data class Voice(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        val fileId: Int,
        val durationS: Int,
    ) : MsgRow()

    /** Any content type not yet handled — carries a human-readable label. */
    data class Unsupported(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        val label: String,
    ) : MsgRow()
}
