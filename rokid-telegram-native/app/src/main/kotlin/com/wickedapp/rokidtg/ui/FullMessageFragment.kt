package com.wickedapp.rokidtg.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast

/** Fullscreen BBS-style reader for long text/generic Telegram messages. */
class FullMessageFragment : Fragment() {
    private var scroll: ScrollView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_full_message, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val text = requireArguments().getString(ARG_TEXT).orEmpty()
        view.setBackgroundColor(Color.BLACK)
        view.defaultFocusHighlightEnabled = false
        view.findViewById<TextView>(R.id.full_message_title).apply {
            this.text = title
            defaultFocusHighlightEnabled = false
        }
        view.findViewById<TextView>(R.id.full_message_text).apply {
            this.text = text
            setBackgroundColor(Color.BLACK)
            defaultFocusHighlightEnabled = false
            isFocusable = false
        }
        view.findViewById<ImageView>(R.id.full_message_back).setOnClickListener {
            closeReaderOnly()
        }
        scroll = view.findViewById(R.id.full_message_scroll)
        val scrollView = scroll ?: return
        scrollView.setBackgroundColor(Color.BLACK)
        scrollView.defaultFocusHighlightEnabled = false
        scrollView.isFocusable = false
        scrollView.isFocusableInTouchMode = false
        view.isFocusableInTouchMode = true
        view.requestFocus()
        // Tapping inside the reader should keep the user in the full-message view.
        // Only Back or the explicit back icon exits this overlay.
        view.setOnClickListener { /* consume, do not close */ }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scrollByPage(+1); true }
                KeyEvent.KEYCODE_DPAD_UP -> { scrollByPage(-1); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> true
                KeyEvent.KEYCODE_BACK -> {
                    closeReaderOnly(); true
                }
                else -> false
            }
        }
    }

    override fun onDestroyView() {
        scroll = null
        super.onDestroyView()
    }

    fun onWindowGesture(g: SpriteBroadcast.Gesture): Boolean = when (g) {
        SpriteBroadcast.Gesture.SWIPE_FORWARD -> { scrollByPage(+1); true }
        SpriteBroadcast.Gesture.SWIPE_BACK -> { scrollByPage(-1); true }
        SpriteBroadcast.Gesture.TAP,
        SpriteBroadcast.Gesture.BUTTON_CLICK,
        SpriteBroadcast.Gesture.TWO_TAP -> true
        SpriteBroadcast.Gesture.BACK -> {
            closeReaderOnly()
            true
        }
        else -> false
    }

    private fun scrollByPage(direction: Int) {
        val s = scroll ?: return
        val delta = (s.height * 0.75f).toInt().coerceAtLeast(80)
        s.smoothScrollBy(0, delta * direction)
    }

    private fun closeReaderOnly() {
        parentFragmentManager.setFragmentResult(
            RESULT_FULL_MESSAGE_CLOSED,
            Bundle().apply { putLong(ARG_CHAT_ID, requireArguments().getLong(ARG_CHAT_ID)) }
        )
        // Pop only the full-message reader. Do not delegate to Activity back, because
        // that can also hit the restored ChatFragment's back handler and appear to
        // jump two levels back to the chat list on Rokid gesture broadcasts.
        parentFragmentManager.popBackStack("full_message", FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"
        private const val ARG_CHAT_ID = "chatId"
        const val RESULT_FULL_MESSAGE_CLOSED = "full_message_closed"

        fun newInstance(title: String, text: String, chatId: Long): FullMessageFragment = FullMessageFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_TEXT, text)
                putLong(ARG_CHAT_ID, chatId)
            }
        }
    }
}
