package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.MessageRepo
import com.wickedapp.rokidtg.service.TdLibClient
import com.wickedapp.rokidtg.data.MsgRow
import com.wickedapp.rokidtg.media.MediaPlayerPool
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

class ChatFragment : Fragment() {

    companion object {
        private const val ARG_CHAT_ID    = "chatId"
        private const val ARG_CHAT_TITLE = "chatTitle"

        fun newInstance(chatId: Long, chatTitle: String): ChatFragment =
            ChatFragment().also {
                it.arguments = Bundle().apply {
                    putLong(ARG_CHAT_ID, chatId)
                    putString(ARG_CHAT_TITLE, chatTitle)
                }
            }
    }

    val chatId: Long get() = requireArguments().getLong(ARG_CHAT_ID)
    private val chatTitle: String get() = requireArguments().getString(ARG_CHAT_TITLE, "")

    private val td: TdLibClient?
        get() = (requireActivity() as MainActivity).optionalService()?.getClient()

    private lateinit var adapter: MsgAdapter

    /** Service-scoped MessageRepo — retained across back-stack entries. */
    private val repo: MessageRepo? get() =
        (requireActivity() as? MainActivity)?.optionalService()?.getMessageRepo(chatId)

    private var composer: ComposerOverlay? = null
    private var sendVoiceNote: ((java.io.File, Int, ByteArray) -> Unit)? = null

    /** Single-slot player for incoming voice notes; null until first use. */
    private var playerPool: MediaPlayerPool? = null
    private fun playerPool(): MediaPlayerPool =
        playerPool ?: MediaPlayerPool(requireContext()).also { playerPool = it }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.header_title).text = chatTitle
        view.findViewById<ImageView>(R.id.header_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val list = view.findViewById<RecyclerView>(R.id.messages)
        list.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true   // newest message at bottom
        }
        adapter = MsgAdapter(
            onOpenPhoto = { fileId -> downloadAndView(fileId, MediaViewerFragment.Kind.PHOTO) },
            onOpenVideo = { fileId -> downloadAndView(fileId, MediaViewerFragment.Kind.VIDEO) },
            onPlayVoice = { fileId -> downloadAndPlay(fileId) },
        )
        list.adapter = adapter

        // Tell TDLib the user is viewing this chat. Required for GetChatHistory to
        // pull from the server when the local cache only has the chat's lastMessage.
        td?.send(TdApi.OpenChat(chatId)) {}

        val r = repo ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            r.messages.collect { adapter.submit(it) }
        }
        // Only load history if cache is empty (service-scoped repo already has it on re-entry)
        if (r.messages.value.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { r.loadHistory() }
        }

        // Wire composer overlay — lazily obtains the shared VoiceHelperBridge from MainActivity.
        val tdClient = td ?: return
        val bridge = (requireActivity() as? MainActivity)?.getOrCreateBridge()
        if (bridge != null) {
            composer = ComposerOverlay(view, tdClient, chatId, bridge)
        }

        // Auto-focus the composer so a paired BT keyboard can type immediately on open.
        view.findViewById<android.widget.EditText>(R.id.composerInput)?.requestFocus()

        // Voice-note send callback used by ComposerOverlay.stopAndSendVoiceNote.
        sendVoiceNote = { file, dur, wave ->
            tdClient.send(TdApi.SendMessage().apply {
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
        // Signal TDLib the chat is no longer on-screen so it can throttle updates.
        td?.send(TdApi.CloseChat(chatId)) {}
        // Release MediaPlayerPool if it was created to avoid ExoPlayer leaks.
        playerPool?.stop()
        playerPool = null
        composer = null
        sendVoiceNote = null
        super.onDestroyView()
    }

    // -------------------------------------------------------------------------
    // Media download + open helpers
    // -------------------------------------------------------------------------

    /**
     * Fire-and-forget DownloadFile for [fileId] at priority 32.
     * Observes [TdApi.UpdateFile] updates until download completes, then
     * pushes [MediaViewerFragment] with the local file.
     */
    private fun downloadAndView(fileId: Int, kind: MediaViewerFragment.Kind) {
        val tdClient = td ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // Subscribe BEFORE sending the request to avoid losing UpdateFile emissions
            // that may fire synchronously when TDLib already has the file cached.
            val updateJob = launch {
                try {
                    val update = tdClient.updates
                        .filterIsInstance<TdApi.UpdateFile>()
                        .first { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                    openMediaViewer(File(update.file.local.path), kind)
                } catch (e: Exception) {
                    Timber.tag("Media").w(e, "UpdateFile collect cancelled or failed")
                }
            }
            tdClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                if (result is TdApi.Error) {
                    Timber.tag("Media").e("DownloadFile failed: %d %s", result.code, result.message)
                } else if (result is TdApi.File && result.local.isDownloadingCompleted) {
                    // File was already cached — result arrives synchronously; cancel the flow collector.
                    updateJob.cancel()
                    viewLifecycleOwner.lifecycleScope.launch {
                        openMediaViewer(File(result.local.path), kind)
                    }
                }
            }
        }
    }

    /**
     * Download [fileId] and play it via [MediaPlayerPool] once complete.
     */
    private fun downloadAndPlay(fileId: Int) {
        val tdClient = td ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // Subscribe BEFORE sending the request to avoid losing UpdateFile emissions
            // that may fire synchronously when TDLib already has the file cached.
            val updateJob = launch {
                try {
                    val update = tdClient.updates
                        .filterIsInstance<TdApi.UpdateFile>()
                        .first { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                    playerPool().playVoice(File(update.file.local.path))
                } catch (e: Exception) {
                    Timber.tag("Voice").w(e, "UpdateFile collect cancelled or failed")
                }
            }
            tdClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                if (result is TdApi.Error) {
                    Timber.tag("Voice").e("DownloadFile failed: %d %s", result.code, result.message)
                } else if (result is TdApi.File && result.local.isDownloadingCompleted) {
                    // File was already cached — result arrives synchronously; cancel the flow collector.
                    updateJob.cancel()
                    viewLifecycleOwner.lifecycleScope.launch {
                        playerPool().playVoice(File(result.local.path))
                    }
                }
            }
        }
    }

    private fun openMediaViewer(file: File, kind: MediaViewerFragment.Kind) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, MediaViewerFragment.newInstance(file.absolutePath, kind.name))
            .addToBackStack("media")
            .commitAllowingStateLoss()
    }

    // -------------------------------------------------------------------------
    // Gesture / key forwarding
    // -------------------------------------------------------------------------

    /** Called by MainActivity when SWIPE_BACK is received while this fragment is active. */
    fun pageUp() {
        viewLifecycleOwner.lifecycleScope.launch { repo?.loadOlder() }
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

private const val VT_TEXT  = 0
private const val VT_PHOTO = 1
private const val VT_VIDEO = 2
private const val VT_VOICE = 3

class MsgAdapter(
    private val onOpenPhoto: (Int) -> Unit = {},
    private val onOpenVideo: (Int) -> Unit = {},
    private val onPlayVoice: (Int) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<MsgRow>()

    fun submit(list: List<MsgRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is MsgRow.Photo       -> VT_PHOTO
        is MsgRow.Video       -> VT_VIDEO
        is MsgRow.Voice       -> VT_VOICE
        is MsgRow.Text,
        is MsgRow.Unsupported -> VT_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_PHOTO -> PhotoVH(inflater.inflate(R.layout.item_message_photo, parent, false))
            VT_VIDEO -> VideoVH(inflater.inflate(R.layout.item_message_video, parent, false))
            VT_VOICE -> VoiceVH(inflater.inflate(R.layout.item_message_voice, parent, false))
            else     -> TextVH(inflater.inflate(R.layout.item_message_text, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is TextVH  -> holder.bind(row)
            is PhotoVH -> holder.bind(row as MsgRow.Photo, onOpenPhoto)
            is VideoVH -> holder.bind(row as MsgRow.Video, onOpenVideo)
            is VoiceVH -> holder.bind(row as MsgRow.Voice, onPlayVoice)
        }
    }

    // ---- View Holders -------------------------------------------------------

    class TextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt: android.widget.TextView = v.findViewById(R.id.text)

        fun bind(row: MsgRow) {
            txt.text = when (row) {
                is MsgRow.Text        -> row.text
                is MsgRow.Unsupported -> "(${row.label})"
                else                  -> "(unsupported)"
            }
        }
    }

    class PhotoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Photo, onTap: (Int) -> Unit) {
            hint.text = "(photo)"
            itemView.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Video, onTap: (Int) -> Unit) {
            hint.text = "(video ${row.durationS}s)"
            itemView.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Voice, onTap: (Int) -> Unit) {
            hint.text = "(voice ${row.durationS}s)"
            itemView.setOnClickListener { onTap(row.fileId) }
        }
    }
}
