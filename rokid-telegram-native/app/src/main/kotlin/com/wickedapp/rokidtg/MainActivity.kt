package com.wickedapp.rokidtg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.wickedapp.rokidtg.databinding.ActivityMainBinding
import com.wickedapp.rokidtg.service.TelegramService
import com.wickedapp.rokidtg.ui.input.GestureSink
import com.wickedapp.rokidtg.ui.input.InputRouter
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast

class MainActivity : AppCompatActivity(), GestureSink {
    private lateinit var binding: ActivityMainBinding
    private lateinit var router: InputRouter

    private var svc: TelegramService.LocalBinder? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            svc = b as TelegramService.LocalBinder
            bound = true
            binding.placeholder.text = "Service: bound"
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
        // Start service early in onCreate so it gets foreground exemption from the activity start
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

    override fun onGesture(g: SpriteBroadcast.Gesture): Boolean {
        binding.placeholder.text = "gesture: $g"
        return g == SpriteBroadcast.Gesture.TAP || g == SpriteBroadcast.Gesture.BACK
    }
}
