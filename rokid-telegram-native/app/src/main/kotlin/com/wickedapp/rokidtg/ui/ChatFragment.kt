package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
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
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private enum class WindowSlot { HEADER, MESSAGES, REPLY }
    private enum class HeaderAction { BACK, PIN, MUTE }
    private var focusedWindow: WindowSlot = WindowSlot.MESSAGES
    private var activeWindow: WindowSlot? = null
    private var focusedHeaderAction: HeaderAction = HeaderAction.BACK
    private var modeHint: TextView? = null
    private var messageWindow: View? = null
    private var replyWindow: View? = null
    private var pinButton: TextView? = null
    private var muteButton: TextView? = null
    private var selectedMessagePosition: Int = RecyclerView.NO_POSITION
    private var isLoadingOlderMessages = false
    private var requestedOlderLoad = false
    private var lastRenderedFirstMessageId: Long? = null
    private var didInitialScrollToNewest = false
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreviewRunnable: Runnable? = null
    private var messagePreviewPopup: View? = null
    private var messagePreviewText: TextView? = null
    private var messagePreviewImage: ImageView? = null
    private var messagePreviewVideo: VideoView? = null
    private var previewToken = 0

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
        pinButton = view.findViewById<TextView>(R.id.header_pin).also { btn ->
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { togglePin() }
        }
        muteButton = view.findViewById<TextView>(R.id.header_mute).also { btn ->
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { toggleMute() }
        }
        refreshHeaderControls()
        modeHint = view.findViewById(R.id.mode_hint)
        messageWindow = view.findViewById(R.id.message_window)
        replyWindow = view.findViewById(R.id.reply_window)
        messagePreviewPopup = view.findViewById(R.id.message_preview_popup)
        messagePreviewText = view.findViewById(R.id.message_preview_text)
        messagePreviewImage = view.findViewById(R.id.message_preview_image)
        messagePreviewVideo = view.findViewById(R.id.message_preview_video)

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
            r.messages.collect { rows ->
                val previousFirst = lastRenderedFirstMessageId
                val insertedBeforePreviousFirst = previousFirst?.let { firstId ->
                    rows.indexOfFirst { it.id == firstId }.takeIf { it > 0 } ?: 0
                } ?: 0
                Timber.tag("ChatFragment").i(
                    "chat=%d rows=%d selected=%d title=%s insertedBeforeFirst=%d requestedOlder=%s loadingOlder=%s",
                    chatId, rows.size, selectedMessagePosition, chatTitle,
                    insertedBeforePreviousFirst, requestedOlderLoad, isLoadingOlderMessages
                )
                adapter.submit(rows) {
                    if (requestedOlderLoad && insertedBeforePreviousFirst > 0) {
                        requestedOlderLoad = false
                        selectedMessagePosition = insertedBeforePreviousFirst - 1
                        Timber.tag("ChatFragment").i(
                            "olderLoad rendered insertedBeforePreviousFirst=%d target=%d size=%d",
                            insertedBeforePreviousFirst, selectedMessagePosition, rows.size
                        )
                        list.post { focusMessageAt(selectedMessagePosition, forceScroll = true) }
                    } else {
                        if (selectedMessagePosition != RecyclerView.NO_POSITION) {
                            selectedMessagePosition = selectedMessagePosition.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                        }
                        if (!didInitialScrollToNewest && rows.isNotEmpty()) {
                            didInitialScrollToNewest = true
                            selectedMessagePosition = rows.lastIndex
                            scrollMessagesToNewest(list)
                        }
                    }
                    lastRenderedFirstMessageId = rows.firstOrNull()?.id
                }
            }
        }
        // BBS density: load enough recent messages to fill the compact terminal viewport;
        // prepend older batches only when active message selection moves above the oldest loaded row.
        if (r.messages.value.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { r.loadHistory() }
        }

        // Wire the reply state machine. Captures the chatId locally for the OGG-send callback
        // so ReplyPanel doesn't need a reference back to the fragment.
        val tdClient = td ?: return
        val bridge = (requireActivity() as? MainActivity)?.getOrCreateBridge()
        if (bridge != null) {
            val sendVoiceNote: (java.io.File, Int, ByteArray, (Boolean) -> Unit) -> Unit = { file, dur, wave, done ->
                Timber.tag("VoiceNote").i(
                    "sendVoiceNote file=%s bytes=%d dur=%d waveform=%d",
                    file.absolutePath,
                    file.length(),
                    dur,
                    wave.size
                )
                if (!file.exists() || file.length() <= 128L) {
                    Timber.tag("VoiceNote").e("sendVoiceNote aborted: invalid local ogg file")
                    done(false)
                } else {
                    tdClient.send(TdApi.SendMessage().apply {
                    this.chatId = this@ChatFragment.chatId
                    inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(file.absolutePath)
                        duration = dur.coerceAtLeast(1)
                        waveform = wave.take(100).toByteArray()
                        caption = null
                        selfDestructType = null
                    }
                }) { result ->
                    when (result) {
                        is TdApi.Error -> {
                            Timber.tag("VoiceNote").e("sendVoiceNote failed: %d %s", result.code, result.message)
                            done(false)
                        }
                        is TdApi.Message -> {
                            Timber.tag("VoiceNote").i("sendVoiceNote accepted localMessageId=%d state=%s", result.id, result.sendingState?.javaClass?.simpleName ?: "none")
                            viewLifecycleOwner.lifecycleScope.launch {
                                val confirmed = withTimeoutOrNull(20_000L) {
                                    tdClient.updates.first { update ->
                                        when (update) {
                                            is TdApi.UpdateMessageSendSucceeded -> update.oldMessageId == result.id && update.message.chatId == this@ChatFragment.chatId
                                            is TdApi.UpdateMessageSendFailed -> update.oldMessageId == result.id && update.message.chatId == this@ChatFragment.chatId
                                            else -> false
                                        }
                                    }
                                }
                                when (confirmed) {
                                    is TdApi.UpdateMessageSendSucceeded -> {
                                        Timber.tag("VoiceNote").i("sendVoiceNote confirmed remoteMessageId=%d", confirmed.message.id)
                                        done(true)
                                    }
                                    is TdApi.UpdateMessageSendFailed -> {
                                        Timber.tag("VoiceNote").e("sendVoiceNote send failed old=%d error=%d %s", confirmed.oldMessageId, confirmed.error.code, confirmed.error.message)
                                        done(false)
                                    }
                                    else -> {
                                        Timber.tag("VoiceNote").w("sendVoiceNote not confirmed within timeout localMessageId=%d", result.id)
                                        done(false)
                                    }
                                }
                            }
                        }
                        else -> {
                            Timber.tag("VoiceNote").w("sendVoiceNote unexpected result: %s", result)
                            done(false)
                        }
                    }
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

    private fun scrollMessagesToNewest(list: RecyclerView) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        list.post { list.scrollToPosition(last) }
        list.postDelayed({ list.scrollToPosition(last) }, 120)
    }

    fun showVoiceTranscriptFromHandoff(text: String): Boolean {
        val panel = replyPanel ?: return false
        focusWindow(WindowSlot.REPLY)
        activeWindow = WindowSlot.REPLY
        replyWindow?.requestFocus()
        panel.showFinalTranscript(text)
        return true
    }

    override fun onDestroyView() {
        hideMessagePreview()
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

    private fun downloadFile(fileId: Int, tag: String, onComplete: (File) -> Unit) {
        val tdClient = td ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val updateJob = launch {
                try {
                    val update = tdClient.updates
                        .filterIsInstance<TdApi.UpdateFile>()
                        .first { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                    onComplete(File(update.file.local.path))
                } catch (e: Exception) {
                    Timber.tag(tag).w(e, "UpdateFile collect cancelled or failed")
                }
            }
            tdClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
                when (result) {
                    is TdApi.Error -> Timber.tag(tag).e("DownloadFile failed: %d %s", result.code, result.message)
                    is TdApi.File -> if (result.local.isDownloadingCompleted) {
                        updateJob.cancel()
                        viewLifecycleOwner.lifecycleScope.launch { onComplete(File(result.local.path)) }
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
        val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
        val lm = list.layoutManager as? LinearLayoutManager
        if (lm != null && lm.findFirstVisibleItemPosition() <= 1) {
            viewLifecycleOwner.lifecycleScope.launch { repo?.loadOlder() }
        }
        list.smoothScrollBy(0, -128)
    }

    /**
     * Window-focus controller for the conversation page.
     *
     * Header controls are intentionally flat: when the top bar is focused,
     * Up/Down moves Back ⇄ Pin ⇄ Mute ⇄ Messages and Enter activates the
     * selected icon. This matches the glasses' one-click confirm / double-click
     * cancel model; Pin/Mute should not require an extra "enter header" step.
     * Active message mode: Up/Down scrolls messages only.
     * Active reply mode: Up/Down moves inside reply controls.
     */
    fun onWindowGesture(g: SpriteBroadcast.Gesture): Boolean {
        return when (g) {
            SpriteBroadcast.Gesture.SWIPE_FORWARD -> {
                syncActiveReplyFromFocus()
                if (activeWindow == null) moveFlatFocus(+1) else operateActive(+1)
                true
            }
            SpriteBroadcast.Gesture.SWIPE_BACK -> {
                syncActiveReplyFromFocus()
                if (activeWindow == null) moveFlatFocus(-1) else operateActive(-1)
                true
            }
            SpriteBroadcast.Gesture.TAP -> {
                val focused = view?.findFocus()
                val replyRoot = replyWindow
                if (focused?.id == R.id.btn_reply && focusedWindow == WindowSlot.REPLY && activeWindow == null) {
                    enterFocusedWindow()
                } else if (focused != null && replyRoot != null && isDescendantOf(focused, replyRoot) && focused !== replyRoot) {
                    if (replyPanel?.activateFocusedAction(focused.id) != true) focused.performClick()
                } else if (focusedWindow == WindowSlot.HEADER && activeWindow == null) {
                    performHeaderAction()
                } else if (activeWindow == null) {
                    enterFocusedWindow()
                } else {
                    focused?.performClick()
                }
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

    private fun syncActiveReplyFromFocus() {
        val focused = view?.findFocus() ?: return
        val replyRoot = replyWindow ?: return
        val panel = replyPanel ?: return
        if (activeWindow == null && panel.currentState() != ReplyPanel.State.DEFAULT && isDescendantOf(focused, replyRoot)) {
            focusedWindow = WindowSlot.REPLY
            activeWindow = WindowSlot.REPLY
        }
    }

    private fun moveFlatFocus(delta: Int) {
        when (focusedWindow) {
            WindowSlot.HEADER -> moveHeaderOrLeave(delta)
            WindowSlot.MESSAGES -> {
                if (delta < 0) {
                    focusedWindow = WindowSlot.HEADER
                    focusHeaderAction(HeaderAction.MUTE)
                    updateHeaderHint()
                } else focusWindow(WindowSlot.REPLY)
            }
            WindowSlot.REPLY -> if (delta < 0) focusWindow(WindowSlot.MESSAGES) else focusWindow(WindowSlot.REPLY)
        }
    }

    private fun moveHeaderOrLeave(delta: Int) {
        val order = listOf(HeaderAction.BACK, HeaderAction.PIN, HeaderAction.MUTE)
        val idx = order.indexOf(focusedHeaderAction).coerceAtLeast(0)
        val next = idx + delta
        when {
            next < 0 -> focusHeaderAction(HeaderAction.BACK)
            next > order.lastIndex -> focusWindow(WindowSlot.MESSAGES)
            else -> focusHeaderAction(order[next])
        }
        if (focusedWindow == WindowSlot.HEADER) updateHeaderHint()
    }

    private fun focusWindow(slot: WindowSlot) {
        hideMessagePreview()
        focusedWindow = slot
        activeWindow = null
        view?.findViewById<RecyclerView>(R.id.messages)?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        when (slot) {
            WindowSlot.HEADER -> {
                setHeaderControlsFocusable(true)
                focusHeaderAction(focusedHeaderAction)
            }
            WindowSlot.MESSAGES -> {
                setHeaderControlsFocusable(true)
                messageWindow?.requestFocus()
            }
            WindowSlot.REPLY -> {
                setHeaderControlsFocusable(true)
                replyPanel?.focusCurrentState()
            }
        }
        modeHint?.text = when (slot) {
            WindowSlot.HEADER -> getHeaderHint(focusedHeaderAction)
            WindowSlot.MESSAGES -> getString(R.string.chat_hint_messages)
            WindowSlot.REPLY -> getString(R.string.chat_hint_reply)
        }
    }

    private fun enterFocusedWindow() {
        hideMessagePreview()
        when (focusedWindow) {
            WindowSlot.HEADER -> performHeaderAction()
            WindowSlot.MESSAGES -> {
                activeWindow = WindowSlot.MESSAGES
                val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
                // Active scrolling mode is a message-item focus trap: header controls are
                // disabled, but message cards stay focusable so Enter can open media /
                // play voice notes.
                setHeaderControlsFocusable(false)
                list.isFocusable = true
                list.isFocusableInTouchMode = true
                list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                selectedMessagePosition = (adapter.itemCount - 1).coerceAtLeast(0)
                focusMessageAt(selectedMessagePosition)
                modeHint?.text = getString(R.string.chat_hint_messages_active)
            }
            WindowSlot.REPLY -> {
                activeWindow = WindowSlot.REPLY
                replyPanel?.openMenu()
                modeHint?.text = getString(R.string.chat_hint_reply_active)
            }
        }
    }

    private fun togglePin() {
        val binder = (requireActivity() as? MainActivity)?.optionalService() ?: return
        val ok = binder.togglePinned(chatId)
        if (!ok) {
            BannerHost.show(getString(R.string.pin_limit_warning), BannerHost.Kind.WARN)
        } else {
            val pinned = binder.getChatPrefs().isPinned(chatId)
            BannerHost.show(getString(if (pinned) R.string.pin_enabled else R.string.pin_disabled), BannerHost.Kind.INFO)
        }
        refreshHeaderControls()
    }

    private fun toggleMute() {
        val binder = (requireActivity() as? MainActivity)?.optionalService() ?: return
        val muted = binder.toggleMuted(chatId)
        BannerHost.show(getString(if (muted) R.string.mute_enabled else R.string.mute_disabled), BannerHost.Kind.INFO)
        refreshHeaderControls()
    }

    private fun refreshHeaderControls() {
        val prefs = (requireActivity() as? MainActivity)?.optionalService()?.getChatPrefs()
        val pinned = prefs?.isPinned(chatId) == true
        val muted = prefs?.isMuted(chatId) == true
        pinButton?.apply {
            text = getString(if (pinned) R.string.action_pin_on else R.string.action_pin)
            contentDescription = getString(if (pinned) R.string.action_unpin_desc else R.string.action_pin_desc)
            setTextColor(context.getColor(if (pinned) R.color.primary else R.color.primary_50))
        }
        muteButton?.apply {
            text = getString(if (muted) R.string.action_mute_on else R.string.action_mute)
            contentDescription = getString(if (muted) R.string.action_unmute_desc else R.string.action_mute_desc)
            setTextColor(context.getColor(if (muted) R.color.primary else R.color.primary_50))
        }
        if (focusedWindow == WindowSlot.HEADER) updateHeaderHint()
    }

    private fun performHeaderAction() {
        when (focusedHeaderAction) {
            HeaderAction.BACK -> requireActivity().onBackPressedDispatcher.onBackPressed()
            HeaderAction.PIN -> togglePin()
            HeaderAction.MUTE -> toggleMute()
        }
    }

    private fun updateHeaderHint() {
        modeHint?.text = getHeaderHint(focusedHeaderAction)
    }

    private fun getHeaderHint(action: HeaderAction): String = when (action) {
        HeaderAction.BACK -> getString(R.string.chat_hint_header_back)
        HeaderAction.PIN -> getString(R.string.chat_hint_header_pin)
        HeaderAction.MUTE -> getString(R.string.chat_hint_header_mute)
    }

    private fun exitActiveWindow() {
        hideMessagePreview()
        if (activeWindow == WindowSlot.REPLY) replyPanel?.onBack()
        if (activeWindow == WindowSlot.MESSAGES) {
            view?.findViewById<RecyclerView>(R.id.messages)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setHeaderControlsFocusable(true)
        }
        val slot = activeWindow ?: focusedWindow
        activeWindow = null
        focusWindow(slot)
    }

    private fun operateActive(delta: Int) {
        when (activeWindow) {
            WindowSlot.HEADER -> moveFocusInsideHeader(delta)
            WindowSlot.MESSAGES -> moveFocusInsideMessages(delta)
            WindowSlot.REPLY -> moveFocusInsideReply(delta)
            else -> Unit
        }
    }

    private fun moveFocusInsideHeader(delta: Int) {
        val order = listOf(HeaderAction.BACK, HeaderAction.PIN, HeaderAction.MUTE)
        val idx = order.indexOf(focusedHeaderAction).coerceAtLeast(0)
        val next = (idx + delta).coerceIn(0, order.lastIndex)
        focusHeaderAction(order[next])
    }

    private fun setHeaderControlsFocusable(enabled: Boolean) {
        view?.findViewById<ImageView>(R.id.header_back)?.apply {
            isFocusable = enabled
            isFocusableInTouchMode = false
        }
        pinButton?.apply {
            isFocusable = enabled
            isFocusableInTouchMode = false
        }
        muteButton?.apply {
            isFocusable = enabled
            isFocusableInTouchMode = false
        }
    }

    private fun focusHeaderAction(action: HeaderAction) {
        focusedWindow = WindowSlot.HEADER
        focusedHeaderAction = action
        when (action) {
            HeaderAction.BACK -> view?.findViewById<ImageView>(R.id.header_back)?.requestFocus()
            HeaderAction.PIN -> pinButton?.requestFocus()
            HeaderAction.MUTE -> muteButton?.requestFocus()
        }
    }

    private fun moveFocusInsideMessages(delta: Int) {
        hideMessagePreview()
        val count = adapter.itemCount
        if (count <= 0) return
        if (selectedMessagePosition == RecyclerView.NO_POSITION) {
            selectedMessagePosition = (count - 1).coerceAtLeast(0)
        }
        if (delta < 0 && selectedMessagePosition <= 0) {
            if (isLoadingOlderMessages) return
            isLoadingOlderMessages = true
            requestedOlderLoad = true
            Timber.tag("ChatFragment").i("olderLoad start selected=%d size=%d", selectedMessagePosition, count)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val inserted = repo?.loadOlder() ?: 0
                    if (inserted <= 0) {
                        requestedOlderLoad = false
                        selectedMessagePosition = 0
                        focusMessageAt(0, forceScroll = true)
                    }
                } finally {
                    isLoadingOlderMessages = false
                }
            }
            return
        }
        val step = if (delta > 0) 1 else -1
        selectedMessagePosition = (selectedMessagePosition + step).coerceIn(0, count - 1)
        focusMessageAt(selectedMessagePosition)
    }

    private fun focusedMessageAdapterPosition(list: RecyclerView): Int {
        val focused = view?.findFocus() ?: return RecyclerView.NO_POSITION
        if (!isDescendantOf(focused, list)) return RecyclerView.NO_POSITION
        val item = generateSequence(focused as View?) { it.parent as? View }
            .firstOrNull { it.parent === list }
            ?: return RecyclerView.NO_POSITION
        return list.getChildAdapterPosition(item)
    }

    private fun firstVisibleMessagePosition(list: RecyclerView): Int {
        val lm = list.layoutManager as? LinearLayoutManager ?: return 0
        return lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: 0
    }

    private fun lastVisibleMessagePosition(list: RecyclerView): Int {
        val lm = list.layoutManager as? LinearLayoutManager ?: return (adapter.itemCount - 1).coerceAtLeast(0)
        return lm.findLastVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: (adapter.itemCount - 1).coerceAtLeast(0)
    }

    private fun focusMessageAt(position: Int, forceScroll: Boolean = false) {
        hideMessagePreview()
        val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
        val target = position.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        selectedMessagePosition = target
        fun requestVisible(): Boolean {
            val holder = list.findViewHolderForAdapterPosition(target) ?: return false
            val item = holder.itemView
            val targetView = findFocusableMessageChild(item) ?: item
            targetView.isFocusableInTouchMode = true
            targetView.requestFocusFromTouch()
            return true
        }
        val visibleNow = requestVisible()
        if (!visibleNow || forceScroll) {
            list.smoothScrollToPosition(target)
            list.postDelayed({ requestVisible() }, 120)
            list.postDelayed({ if (requestVisible()) scheduleMessagePreview(target) }, 260)
        } else {
            scheduleMessagePreview(target)
        }
    }

    private fun scheduleMessagePreview(position: Int) {
        pendingPreviewRunnable?.let { previewHandler.removeCallbacks(it) }
        pendingPreviewRunnable = Runnable {
            if (activeWindow == WindowSlot.MESSAGES && selectedMessagePosition == position) {
                showMessagePreview(position)
            }
        }.also { previewHandler.postDelayed(it, 1500L) }
    }

    private fun showMessagePreview(position: Int) {
        val row = adapter.rowAt(position) ?: return
        val token = ++previewToken
        messagePreviewText?.apply {
            visibility = View.VISIBLE
            text = row.toPreviewText()
        }
        messagePreviewImage?.visibility = View.GONE
        messagePreviewVideo?.apply {
            stopPlayback()
            visibility = View.GONE
        }
        messagePreviewPopup?.visibility = View.VISIBLE
        when (row) {
            is MsgRow.Photo -> downloadFile(row.fileId, "PhotoPreview") { file ->
                if (previewToken == token && activeWindow == WindowSlot.MESSAGES && selectedMessagePosition == position) {
                    messagePreviewText?.visibility = View.GONE
                    messagePreviewImage?.apply {
                        setImageURI(Uri.fromFile(file))
                        visibility = View.VISIBLE
                    }
                }
            }
            is MsgRow.Video -> downloadFile(row.fileId, "VideoPreview") { file ->
                if (previewToken == token && activeWindow == WindowSlot.MESSAGES && selectedMessagePosition == position) {
                    messagePreviewText?.visibility = View.GONE
                    messagePreviewVideo?.apply {
                        visibility = View.VISIBLE
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                }
            }
            is MsgRow.Voice -> downloadFile(row.fileId, "VoicePreview") { file ->
                if (previewToken == token && activeWindow == WindowSlot.MESSAGES && selectedMessagePosition == position) {
                    playerPool().playVoice(file)
                }
            }
            else -> Unit
        }
    }

    private fun hideMessagePreview() {
        pendingPreviewRunnable?.let { previewHandler.removeCallbacks(it) }
        pendingPreviewRunnable = null
        previewToken += 1
        messagePreviewVideo?.stopPlayback()
        messagePreviewPopup?.visibility = View.GONE
    }

    private fun findFocusableMessageChild(root: View): View? {
        if (root.isFocusable) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFocusableMessageChild(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun pageDown() {
        val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
        list.smoothScrollBy(0, 128)
    }

    private fun moveFocusInsideReply(delta: Int) {
        if (replyPanel?.moveFocus(delta) == true) return
        val dir = if (delta > 0) View.FOCUS_DOWN else View.FOCUS_UP
        val cur = view?.findFocus()
        val replyRoot = replyWindow
        val next = cur?.focusSearch(dir)
        if (next != null && replyRoot != null && isDescendantOf(next, replyRoot)) {
            next.isFocusableInTouchMode = true
            next.requestFocusFromTouch()
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

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<MsgRow>, onCommitted: () -> Unit = {}) {
        val oldRows = rows.toList()
        val newRows = list.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldRows.size
            override fun getNewListSize(): Int = newRows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldRows[oldItemPosition].id == newRows[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldRows[oldItemPosition] == newRows[newItemPosition]
        })
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
        onCommitted()
    }

    override fun getItemId(position: Int): Long = rows[position].id

    override fun getItemCount(): Int = rows.size

    fun rowAt(position: Int): MsgRow? = rows.getOrNull(position)

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
        private val bubble: View = v.findViewById(R.id.bubble)
        private val time: android.widget.TextView = v.findViewById(R.id.time)
        private val sender: android.widget.TextView = v.findViewById(R.id.sender)
        private val txt: android.widget.TextView = v.findViewById(R.id.text)

        fun bind(row: MsgRow) {
            val me = itemView.context.getString(R.string.sender_me)
            time.text = formatBbsMessageTime(row.date)
            sender.text = if (row.isOutgoing) me else row.senderLabel
            sender.isSelected = true
            txt.text = when (row) {
                is MsgRow.Text        -> row.text
                is MsgRow.Unsupported -> "(${row.label})"
                else                  -> "(${itemView.context.getString(R.string.message_unsupported)})"
            }
        }
    }

    class PhotoVH(v: View) : RecyclerView.ViewHolder(v) {
        // Inner LinearLayout holds focus + bg highlight; wire click there so TAP-on-focused fires.
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val time: android.widget.TextView = v.findViewById(R.id.time)
        private val senderView: android.widget.TextView = v.findViewById(R.id.sender)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Photo, onTap: (Int) -> Unit) {
            val label = itemView.context.getString(R.string.message_photo)
            val me = itemView.context.getString(R.string.sender_me)
            val sender = if (row.isOutgoing) me else row.senderLabel
            time.text = formatBbsMessageTime(row.date)
            senderView.text = sender
            senderView.isSelected = true
            hint.text = "[$label]"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val time: android.widget.TextView = v.findViewById(R.id.time)
        private val senderView: android.widget.TextView = v.findViewById(R.id.sender)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Video, onTap: (Int) -> Unit) {
            val label = itemView.context.getString(R.string.message_video)
            val me = itemView.context.getString(R.string.sender_me)
            val sender = if (row.isOutgoing) me else row.senderLabel
            time.text = formatBbsMessageTime(row.date)
            senderView.text = sender
            senderView.isSelected = true
            hint.text = "[$label ${row.durationS}s]"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }

    class VoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = (v as ViewGroup).getChildAt(0)
        private val time: android.widget.TextView = v.findViewById(R.id.time)
        private val senderView: android.widget.TextView = v.findViewById(R.id.sender)
        private val hint: android.widget.TextView = v.findViewById(R.id.hint)

        fun bind(row: MsgRow.Voice, onTap: (Int) -> Unit) {
            val label = itemView.context.getString(R.string.message_voice)
            val me = itemView.context.getString(R.string.sender_me)
            val sender = if (row.isOutgoing) me else row.senderLabel
            time.text = formatBbsMessageTime(row.date)
            senderView.text = sender
            senderView.isSelected = true
            hint.text = "[$label ${row.durationS}s]"
            card.setOnClickListener { onTap(row.fileId) }
        }
    }
}

private fun formatBbsMessageTime(dateSeconds: Int): String {
    if (dateSeconds <= 0) return "--:--"
    return BBS_MESSAGE_TIME_FORMAT.get()!!.format(Date(dateSeconds * 1000L))
}

private fun MsgRow.toPreviewText(): String {
    val body = when (this) {
        is MsgRow.Text -> text
        is MsgRow.Photo -> "[photo] ${width}x${height}"
        is MsgRow.Video -> "[video] ${durationS}s"
        is MsgRow.Voice -> "[voice] ${durationS}s"
        is MsgRow.Unsupported -> "[$label]"
    }
    return "${formatBbsMessageTime(date)}  ${senderLabel}\n$body"
}

private val BBS_MESSAGE_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.US)
}
