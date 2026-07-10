package com.wickedapp.rokidtg.data

sealed class MsgRow {
    abstract val id: Long
    abstract val date: Int
    abstract val isOutgoing: Boolean
    abstract val senderLabel: String

    data class Text(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val text: String,
    ) : MsgRow()

    data class Photo(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val fileId: Int,
        val width: Int,
        val height: Int,
    ) : MsgRow()

    data class Video(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val fileId: Int,
        val durationS: Int,
    ) : MsgRow()

    data class Voice(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val fileId: Int,
        val durationS: Int,
    ) : MsgRow()

    data class Sticker(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val fileId: Int?,
        val emoji: String,
        val label: String,
    ) : MsgRow()

    data class Unsupported(
        override val id: Long,
        override val date: Int,
        override val isOutgoing: Boolean,
        override val senderLabel: String,
        val label: String,
    ) : MsgRow()
}
