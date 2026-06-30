package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.MessageRepo
import com.wickedapp.rokidtg.data.MsgRow
import com.wickedapp.rokidtg.data.TdClientFacade
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import androidx.annotation.RequiresPermission

class ChatFragment(
    private val td: TdClientFacade,
    val chatId: Long,
    private val chatTitle: String,
) : Fragment() {

    private lateinit var adapter: MsgAdapter
    private lateinit var repo: MessageRepo
    private var composer: ComposerOverlay? = null
    private var sendVoiceNote: ((java.io.File, Int, ByteArray) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.peer).text = chatTitle

        val list = view.findViewById<RecyclerView>(R.id.messages)
        list.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true   // newest message at bottom
        }
        adapter = MsgAdapter()
        list.adapter = adapter

        repo = MessageRepo(td, chatId, viewLifecycleOwner.lifecycleScope)

        viewLifecycleOwner.lifecycleScope.launch {
            repo.messages.collect { adapter.submit(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch { repo.loadHistory() }

        // Wire composer overlay — lazily obtains the shared VoiceHelperBridge from MainActivity.
        val bridge = (requireActivity() as? MainActivity)?.getOrCreateBridge()
        if (bridge != null) {
            composer = ComposerOverlay(view, td, chatId, bridge)
        }

        // Voice-note send callback used by ComposerOverlay.stopAndSendVoiceNote.
        sendVoiceNote = { file, dur, wave ->
            td.send(TdApi.SendMessage().apply {
                this.chatId = this@ChatFragment.chatId
                inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                    voiceNote = TdApi.InputFileLocal(file.absolutePath)
                    duration = dur
                    waveform = wave
                    caption = null
                    selfDestructType = null
                }
            }) { result ->
                if (result is TdApi.Error) {
                    Timber.tag("VoiceNote").e("sendVoiceNote failed: %d %s", result.code, result.message)
                }
            }
        }
    }

    override fun onDestroyView() {
        composer = null
        sendVoiceNote = null
        super.onDestroyView()
    }

    /** Called by MainActivity when SWIPE_BACK is received while this fragment is active. */
    fun pageUp() {
        viewLifecycleOwner.lifecycleScope.launch { repo.loadOlder() }
    }

    /**
     * Called by MainActivity on TWO_DOUBLE_TAP gesture.
     * Returns true if the overlay handled the toggle (i.e. this fragment is active).
     */
    fun onVoiceToggle(): Boolean = composer?.toggleVoice() ?: false

    /**
     * Called by MainActivity on SETTINGS gesture (two-finger long-press).
     * Toggles hold-to-record voice note mode: first call starts recording, second stops and sends.
     */
    fun onVoiceNoteToggle() {
        val c = composer ?: return
        val cb = sendVoiceNote ?: return
        if (c.isRecording()) {
            c.stopAndSendVoiceNote(cb)
        } else {
            c.startVoiceNote(cb)
        }
    }

    /**
     * Called by MainActivity.dispatchKeyEvent on printable key events.
     * Shows the composer overlay and appends the character.
     */
    fun onPrintableKey(event: KeyEvent): Boolean {
        val ch = event.unicodeChar
        if (ch == 0) return false
        val c = composer ?: return false
        c.show()
        c.appendChar(ch.toChar())
        return true
    }
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

class MsgAdapter : RecyclerView.Adapter<MsgAdapter.TextVH>() {

    private val rows = mutableListOf<MsgRow>()

    fun submit(list: List<MsgRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message_text, parent, false)
        return TextVH(v)
    }

    override fun onBindViewHolder(holder: TextVH, position: Int) {
        holder.bind(rows[position])
    }

    class TextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt: TextView = v.findViewById(R.id.text)

        fun bind(row: MsgRow) {
            // Task 13 will split into per-type ViewHolders with dedicated layouts.
            // For v1 we render all types in the single text cell with descriptive placeholders.
            txt.text = when (row) {
                is MsgRow.Text        -> row.text
                is MsgRow.Photo       -> "(photo)"
                is MsgRow.Video       -> "(video)"
                is MsgRow.Voice       -> "(voice note)"
                is MsgRow.Unsupported -> "(${row.label})"
            }
        }
    }
}
