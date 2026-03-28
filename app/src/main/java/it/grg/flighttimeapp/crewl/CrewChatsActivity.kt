package it.grg.flighttimeapp.crewl

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class CrewChatsActivity : AppCompatActivity() {

    private val chatStore = CrewLayoverChatStore.shared
    private lateinit var adapter: CrewChatsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_chats)

        findViewById<ImageButton>(R.id.chatsBack).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.chatsRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CrewChatsAdapter(emptyList(), emptySet()) { thread ->
            val intent = Intent(this, CrewChatActivity::class.java).apply {
                putExtra(CrewChatActivity.EXTRA_THREAD_ID, thread.id)
                putExtra(CrewChatActivity.EXTRA_PEER_ID, thread.peerId)
                putExtra(CrewChatActivity.EXTRA_PEER_NAME, thread.peerNickname)
                putExtra(CrewChatActivity.EXTRA_PEER_COMPANY, thread.peerCompany ?: "")
            }
            startActivity(intent)
        }
        recycler.adapter = adapter
        attachChatSwipeToHide(recycler)

        chatStore.threads.observe(this, Observer { threads ->
            adapter.submit(threads, chatStore.unreadThreadIds.value ?: emptySet())
        })

        chatStore.startThreadsObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        chatStore.stopAllObservers()
    }

    private fun attachChatSwipeToHide(recycler: RecyclerView) {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.adapterPosition
                val thread = adapter.getItem(pos)
                if (thread != null) {
                    chatStore.deleteChat(thread.id, thread.peerId)
                } else {
                    adapter.notifyItemChanged(pos)
                }
            }
        })
        helper.attachToRecyclerView(recycler)
    }
}
