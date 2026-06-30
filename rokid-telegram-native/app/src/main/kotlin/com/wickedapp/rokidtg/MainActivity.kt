package com.wickedapp.rokidtg

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.wickedapp.rokidtg.databinding.ActivityMainBinding
import com.wickedapp.rokidtg.ui.input.GestureSink
import com.wickedapp.rokidtg.ui.input.InputRouter
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast

class MainActivity : AppCompatActivity(), GestureSink {
    private lateinit var binding: ActivityMainBinding
    private lateinit var router: InputRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        router = InputRouter(this, this)
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
