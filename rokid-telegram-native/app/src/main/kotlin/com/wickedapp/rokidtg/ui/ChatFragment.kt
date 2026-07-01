package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast
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

    private var replyPanel: ReplyPanel? = null
    private enum class WindowSlot { BACK, MESSAGES, REPLY }
    private var focusedWindow: WindowSlot = WindowSlot.MESSAGES
    private var activeWindow: WindowSlot? = null
    private var modeHint: TextView? = null
    private var messageWindow: View? = null
    private var replyWindow: View? = null

    /** Single-slot player for incoming voice notes; null until first use. */
    private var playerPool: MediaPlayerPool? = null
    private fun playerPool(): MediaPlayerPool =
        playerPool ?: MediaPlayerPool(requireContext()).also { playerPool = it }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.header_title).text = chatTitle
        val backWindow = view.findViewById<ImageView>(R.id.header_back)
        backWindow.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        modeHint = view.findViewById(R.id.mode_hint)
        messageWindow = view.findViewById(R.id.message_window)
        replyWindow = view.findViewById(R.id.reply_window)

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

        // Wire the reply state machine. Captures the chatId locally for the OGG-send callback
        // so ReplyPanel doesn't need a reference back to the fragment.
        val tdClient = td ?: return
        val bridge = (requireActivity() as? MainActivity)?.getOrCreateBridge()
        if (bridge != null) {
            val sendVoiceNote: (java.io.File, Int, ByteArray) -> Unit = { file, dur, wave ->
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
            replyPanel = ReplyPanel(view, tdClient, chatId, bridge, sendVoiceNote)
        }

        messageWindow?.setOnClickListener { enterFocusedWindow() }
        replyWindow?.setOnClickListener { enterFocusedWindow() }

        // Default to a focus container, not the scrollable list. User must Enter the
        // message window before Up/Down scrolls messages.
        list.post { focusWindow(WindowSlot.MESSAGES) }

        // Intercept back-press to collapse the reply panel (VOICE/TEXT/BT → MENU → DEFAULT)
        // before letting the system pop the fragment back to the chat list.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activeWindow != null) {
                        exitActiveWindow()
                        return
                    }
                    if (replyPanel?.onBack() == true) return
                    // Disable this callback and let the dispatcher do default pop.
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        )
    }

    override fun onDestroyView() {
        // Signal TDLib the chat is no longer on-screen so it can throttle updates.
        td?.send(TdApi.CloseChat(chatId)) {}
        // Release MediaPlayerPool if it was created to avoid ExoPlayer leaks.
        playerPool?.stop()
        playerPool = null
        replyPanel = null
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

    /**
     * Called by MainActivity when SWIPE_BACK is received while this fragment is active.
     * Walks focus toward the top of the message list one item at a time; once we're at
     * the topmost loaded message (focusSearch UP would leave the list), fetches the
     * next page of older history from TDLib instead of jumping to the header.
     */
    fun pageUp() {
        val list = view?.findViewById<RecyclerView>(R.id.messages)
        val cur = view?.findFocus()
        val next = cur?.focusSearch(View.FOCUS_UP)
        val staysInList = next != null && list != null && isDescendantOf(next, list)
        if (cur != null && next != null && next !== cur && staysInList) {
            next.requestFocus()
        } else {
            viewLifecycleOwner.lifecycleScope.launch { repo?.loadOlder() }
        }
    }

    /**
     * Window-focus controller for the conversation page.
     *
     * Focus mode: Up/Down moves Back ⇄ Message History ⇄ Reply; Enter activates.
     * Active message mode: Up/Down scroll/select messages; Enter opens focused media.
     * Active reply mode: Up/Down moves inside reply controls; Enter performs control.
     */
    fun onWindowGesture(g: SpriteBroadcast.Gesture): Boolean {
        return when (g) {
            SpriteBroadcast.Gesture.SWIPE_FORWARD -> {
                if (activeWindow == null) focusWindow(nextWindow(+1)) else operateActive(+1)
                true
            }
            SpriteBroadcast.Gesture.SWIPE_BACK -> {
                if (activeWindow == null) focusWindow(nextWindow(-1)) else operateActive(-1)
                true
            }
            SpriteBroadcast.Gesture.TAP -> {
                if (activeWindow == null) enterFocusedWindow() else view?.findFocus()?.performClick()
                true
            }
            SpriteBroadcast.Gesture.BACK -> {
                if (activeWindow != null) {
                    exitActiveWindow()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun nextWindow(delta: Int): WindowSlot {
        val order = listOf(WindowSlot.BACK, WindowSlot.MESSAGES, WindowSlot.REPLY)
        val idx = order.indexOf(focusedWindow).coerceAtLeast(0)
        val next = (idx + delta).coerceIn(0, order.lastIndex)
        return order[next]
    }

    private fun focusWindow(slot: WindowSlot) {
        focusedWindow = slot
        activeWindow = null
        view?.findViewById<RecyclerView>(R.id.messages)?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        when (slot) {
            WindowSlot.BACK -> view?.findViewById<ImageView>(R.id.header_back)?.requestFocus()
            WindowSlot.MESSAGES -> messageWindow?.requestFocus()
            WindowSlot.REPLY -> replyWindow?.requestFocus()
        }
        modeHint?.text = when (slot) {
            WindowSlot.BACK -> "焦點模式：Enter 返回列表 · ↓ 到消息窗口"
            WindowSlot.MESSAGES -> "焦點模式：Enter 進入消息窗口 · ↑↓換窗口"
            WindowSlot.REPLY -> "焦點模式：Enter 進入回覆窗口 · ↑ 到消息窗口"
        }
    }

    private fun enterFocusedWindow() {
        when (focusedWindow) {
            WindowSlot.BACK -> requireActivity().onBackPressedDispatcher.onBackPressed()
            WindowSlot.MESSAGES -> {
                activeWindow = WindowSlot.MESSAGES
                val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
                list.isFocusable = true
                list.isFocusableInTouchMode = true
                (list.findFocus() ?: list.getChildAt(0) ?: list).requestFocus()
                modeHint?.text = "消息窗口內：↑↓讀/滾動 · Enter 開啟媒體 · Back 退出窗口"
            }
            WindowSlot.REPLY -> {
                activeWindow = WindowSlot.REPLY
                val replyButton = view?.findViewById<View>(R.id.btn_reply)
                replyButton?.requestFocus() ?: replyWindow?.requestFocus()
                modeHint?.text = "回覆窗口內：↑↓選功能 · Enter 執行 · Back 退出/取消"
            }
        }
    }

    private fun exitActiveWindow() {
        if (activeWindow == WindowSlot.REPLY) replyPanel?.onBack()
        val slot = activeWindow ?: focusedWindow
        activeWindow = null
        focusWindow(slot)
    }

    private fun operateActive(delta: Int) {
        when (activeWindow) {
            WindowSlot.MESSAGES -> if (delta < 0) pageUp() else pageDown()
            WindowSlot.REPLY -> moveFocusInsideReply(delta)
            else -> Unit
        }
    }

    private fun pageDown() {
        val list = view?.findViewById<RecyclerView>(R.id.messages)
        val cur = view?.findFocus()
        val next = cur?.focusSearch(View.FOCUS_DOWN)
        val staysInList = next != null && list != null && isDescendantOf(next, list)
        if (cur != null && next != null && next !== cur && staysInList) {
            next.requestFocus()
        } else {
            list?.smoothScrollBy(0, 96)
        }
    }

    private fun moveFocusInsideReply(delta: Int) {
        val dir = if (delta > 0) View.FOCUS_DOWN else View.FOCUS_UP
        val cur = view?.findFocus()
        val next = cur?.focusSearch(dir)
        val replyRoot = replyWindow
        if (next != null && replyRoot != null && isDescendantOf(next, replyRoot)) {
            next.requestFocus()
        }
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var p: View? = child
        while (p != null) {
            if (p === ancestor) return true
            p = (p.parent as? View)
        }
        return false
    }

    /** Called by MainActivity on TWO_DOUBLE_TAP gesture — fast path to Reply menu. */
    fun onVoiceToggle(): Boolean {
        // Touchpad shortcut: bring up the reply menu so user can pick Voice/Text/BT.
        return false
    }

    /** Called by MainActivity on SETTINGS gesture — no-op in the new design. */
    fun onVoiceNoteToggle() { /* handled via Reply → Voice button */ }

    /** Returns true if the reply panel consumed back-press (collapsed one level). */
    fun onBackPressed(): Boolean = replyPanel?.onBack() ?: false

    /**
     * Called by MainActivity.dispatchKeyEvent on printable key events.
     * Routes to the ReplyPanel BT-input state.
     */
    fun onPrintableKey(event: KeyEvent): Boolean {
        val ch = event.unicodeChar
        if (ch == 0) return false
        val panel = replyPanel ?: return false
        panel.appendCharFromBtKeyboard(ch.toChar())
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
        // Inner LinearLayout holds focus + bg highlight; wire click there so TAP-on-focused fires.
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Photo, onTap: (Int) -> Unit) {
            hint.text = "(photo)"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Video, onTap: (Int) -> Unit) {
            hint.text = "(video ${row.durationS}s)"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Voice, onTap: (Int) -> Unit) {
            hint.text = "(voice ${row.durationS}s)"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }
}
