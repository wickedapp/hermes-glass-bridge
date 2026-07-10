package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.wickedapp.rokidtg.R

/** Fullscreen BBS-style reader for long text/generic Telegram messages. */
class FullMessageFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_full_message, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val text = requireArguments().getString(ARG_TEXT).orEmpty()
        view.findViewById<TextView>(R.id.full_message_title).text = title
        view.findViewById<TextView>(R.id.full_message_text).text = text
        view.findViewById<ImageView>(R.id.full_message_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        val scroll = view.findViewById<ScrollView>(R.id.full_message_scroll)
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, 120); true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -120); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK -> {
                    requireActivity().onBackPressedDispatcher.onBackPressed(); true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"

        fun newInstance(title: String, text: String): FullMessageFragment = FullMessageFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_TEXT, text)
            }
        }
    }
}
