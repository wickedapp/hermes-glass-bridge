package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.ChatRepo
import com.wickedapp.rokidtg.data.ChatRow
import kotlinx.coroutines.launch

class ChatListFragment(
    private val repo: ChatRepo,
    private val onOpenChat: (Long) -> Unit,
) : Fragment() {

    private lateinit var adapter: Adapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_chat_list, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = Adapter(onOpenChat)
        list.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            repo.chats.collect { adapter.submit(it) }
        }
        lifecycleScope.launch { repo.loadInitial() }
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
