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
import com.wickedapp.rokidtg.data.ChatRepo
import com.wickedapp.rokidtg.data.ChatRow
import kotlinx.coroutines.launch

class ChatListFragment : Fragment() {

    companion object {
        fun newInstance(): ChatListFragment = ChatListFragment()
    }

    private lateinit var adapter: Adapter

    /** Resolved in onViewCreated via the service binder so it survives process death. */
    private val repo: ChatRepo? get() =
        (requireActivity() as? MainActivity)?.requireService()?.getChatRepo()

    private val onOpenChat: (Long) -> Unit get() = { chatId ->
        (requireActivity() as? MainActivity)?.openChat(chatId)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_chat_list, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        view.findViewById<TextView>(R.id.header_title).text = getString(R.string.app_name)
        // Hide the back arrow on the root chat list — nothing to go back to.
        view.findViewById<android.widget.ImageView>(R.id.header_back).visibility = View.GONE
        val subtitle = view.findViewById<TextView>(R.id.header_subtitle)

        val rv = view.findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = Adapter(onOpenChat)
        rv.adapter = adapter

        val searchBar = view.findViewById<View>(R.id.search_bar)
        val searchInput = view.findViewById<android.widget.EditText>(R.id.search_input)

        searchBar?.setOnClickListener {
            searchInput?.requestFocus()
        }
        searchBar?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                searchInput?.requestFocus(); true
            } else false
        }

        searchInput?.setOnEditorActionListener { _, _, _ ->
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val results = repo?.search(query) ?: emptyList()
                    adapter.submit(results)
                }
            }
            true
        }

        // Back key on the search input restores full chat list
        searchInput?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP) {
                searchInput.text.clear()
                searchInput.clearFocus()
                // Restore full list
                viewLifecycleOwner.lifecycleScope.launch {
                    repo?.chats?.value?.let { adapter.submit(it) }
                }
                true
            } else false
        }

        val r = repo ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            r.chats.collect {
                adapter.submit(it)
                subtitle.text = "${it.size}"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { r.loadInitial() }
    }

    class Adapter(private val onClick: (Long) -> Unit) :
        RecyclerView.Adapter<RowVH>() {
        private val rows = mutableListOf<ChatRow>()
        fun submit(list: List<ChatRow>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int): RowVH =
            RowVH(LayoutInflater.from(p.context).inflate(R.layout.item_chat_row, p, false), onClick)
        override fun onBindViewHolder(h: RowVH, pos: Int) = h.bind(rows[pos])
        override fun getItemCount() = rows.size
    }
}

class RowVH(v: View, private val onClick: (Long) -> Unit) : RecyclerView.ViewHolder(v) {
    private val title   = v.findViewById<TextView>(R.id.title)
    private val preview = v.findViewById<TextView>(R.id.preview)
    private val unread  = v.findViewById<ImageView>(R.id.unread)
    private var id: Long = 0

    init {
        v.setOnClickListener { onClick(id) }
    }

    fun bind(r: ChatRow) {
        id = r.id
        title.text = r.title
        preview.text = r.preview
        unread.visibility = if (r.unreadCount > 0) View.VISIBLE else View.GONE
    }
}
