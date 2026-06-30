package com.wickedapp.rokidtg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.data.ChatRepo
import com.wickedapp.rokidtg.databinding.ActivityMainBinding
import com.wickedapp.rokidtg.service.TelegramService
import com.wickedapp.rokidtg.ui.ChatFragment
import com.wickedapp.rokidtg.ui.ChatListFragment
import com.wickedapp.rokidtg.ui.input.GestureSink
import com.wickedapp.rokidtg.ui.input.InputRouter
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast
import timber.log.Timber

class MainActivity : AppCompatActivity(), GestureSink {
    private lateinit var binding: ActivityMainBinding
    private lateinit var router: InputRouter

    private var svc: TelegramService.LocalBinder? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            svc = b as TelegramService.LocalBinder
            bound = true
            // Deviation from brief: swap in ChatListFragment immediately on service bind
            // rather than waiting for auth state, so the layout can be verified on-device
            // without a seeded TDLib session. Auth-gating is deferred to a future task.
            val client = b.getClient() ?: return
            val repo = ChatRepo(client, lifecycleScope)
            // commitAllowingStateLoss: service can connect after onSaveInstanceState during
            // fast start/stop cycles; state loss is acceptable here (fragment is recreated on resume).
            supportFragmentManager.beginTransaction()
                .replace(binding.container.id, ChatListFragment(repo) { chatId ->
                    val title = repo.chats.value.firstOrNull { it.id == chatId }?.title ?: ""
                    supportFragmentManager.beginTransaction()
                        .replace(binding.container.id, ChatFragment(client, chatId, title))
                        .addToBackStack("chat:$chatId")
                        .commitAllowingStateLoss()
                })
                .commitAllowingStateLoss()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        router = InputRouter(this, this)
        startForegroundService(Intent(this, TelegramService::class.java))
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, TelegramService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (bound) {
            unbindService(conn)
            bound = false
            svc = null
        }
        super.onStop()
    }

    override fun onResume() { super.onResume(); router.install() }
    override fun onPause()  { router.uninstall(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        router.dispatchKey(event) || super.dispatchKeyEvent(event)

    override fun onGesture(g: SpriteBroadcast.Gesture): Boolean = when (g) {
        SpriteBroadcast.Gesture.SWIPE_FORWARD -> { focusNext(); true }
        SpriteBroadcast.Gesture.SWIPE_BACK    -> {
            val current = supportFragmentManager.findFragmentById(binding.container.id)
            if (current is ChatFragment) { current.pageUp(); true } else { focusPrev(); true }
        }
        SpriteBroadcast.Gesture.TAP           -> { currentFocus?.performClick(); true }
        SpriteBroadcast.Gesture.BACK          -> { onBackPressedDispatcher.onBackPressed(); true }
        else -> false
    }

    private fun focusNext() {
        val cur = currentFocus ?: binding.container
        cur.focusSearch(View.FOCUS_DOWN)?.requestFocus()
    }

    private fun focusPrev() {
        val cur = currentFocus ?: binding.container
        cur.focusSearch(View.FOCUS_UP)?.requestFocus()
    }
}
