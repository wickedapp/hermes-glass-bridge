package com.wickedapp.rokidtg.ui.input

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import timber.log.Timber

interface GestureSink {
    /** Return true if consumed. */
    fun onGesture(g: SpriteBroadcast.Gesture): Boolean
}

class InputRouter(
    private val activity: Activity,
    private val sink: GestureSink
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val gesture = SpriteBroadcast.fromAction(intent?.action) ?: return
            Timber.tag("Input").v("broadcast=%s", gesture)
            val consumed = sink.onGesture(gesture)
            if (consumed && isOrderedBroadcast) abortBroadcast()
        }
    }

    fun install() {
        val filter = IntentFilter().apply {
            addAction(SpriteBroadcast.ACTION_CLICK)
            addAction(SpriteBroadcast.ACTION_DOUBLE_CLICK)
            addAction(SpriteBroadcast.ACTION_AI_START)
            addAction(SpriteBroadcast.ACTION_LONG_PRESS)
            addAction(SpriteBroadcast.ACTION_TWO_TAP)
            addAction(SpriteBroadcast.ACTION_TWO_DOUBLE)
            addAction(SpriteBroadcast.ACTION_TWO_FORWARD)
            addAction(SpriteBroadcast.ACTION_TWO_BACK)
            addAction(SpriteBroadcast.ACTION_SETTINGS_KEY)
            priority = 100
        }
        activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun uninstall() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    /** Call from Activity.dispatchKeyEvent or onKeyDown. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> sink.onGesture(SpriteBroadcast.Gesture.TAP)
            KeyEvent.KEYCODE_DPAD_DOWN -> sink.onGesture(SpriteBroadcast.Gesture.SWIPE_FORWARD)
            KeyEvent.KEYCODE_DPAD_UP -> sink.onGesture(SpriteBroadcast.Gesture.SWIPE_BACK)
            KeyEvent.KEYCODE_BACK  -> sink.onGesture(SpriteBroadcast.Gesture.BACK)
            else -> false
        }
    }
}
