package com.wickedapp.rokidtg.ui.input

object SpriteBroadcast {
    const val ACTION_CLICK         = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
    const val ACTION_DOWN          = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
    const val ACTION_UP            = "com.android.action.ACTION_SPRITE_BUTTON_UP"
    const val ACTION_DOUBLE_CLICK  = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
    const val ACTION_AI_START      = "com.android.action.ACTION_AI_START"
    // Yes, the Rokid v0.0.1 docs really have the typo "androidid":
    const val ACTION_LONG_PRESS    = "com.androidid.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
    const val ACTION_TWO_TAP       = "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"
    const val ACTION_TWO_DOUBLE    = "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"
    const val ACTION_TWO_FORWARD   = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
    const val ACTION_TWO_BACK      = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
    const val ACTION_SETTINGS_KEY  = "com.android.action.ACTION_SETTINGS_KEY"

    enum class Gesture {
        TAP, TWO_TAP, TWO_DOUBLE_TAP,
        SWIPE_FORWARD, SWIPE_BACK,
        SETTINGS, AI_START,
        BUTTON_CLICK, BUTTON_LONG,
        BACK
    }

    fun fromAction(action: String?): Gesture? = when (action) {
        ACTION_CLICK         -> Gesture.BUTTON_CLICK
        ACTION_DOUBLE_CLICK  -> Gesture.BACK
        ACTION_AI_START      -> Gesture.AI_START
        ACTION_LONG_PRESS    -> Gesture.BUTTON_LONG
        ACTION_TWO_TAP       -> Gesture.TWO_TAP
        ACTION_TWO_DOUBLE    -> Gesture.TWO_DOUBLE_TAP
        ACTION_TWO_FORWARD   -> Gesture.SWIPE_FORWARD
        ACTION_TWO_BACK      -> Gesture.SWIPE_BACK
        ACTION_SETTINGS_KEY  -> Gesture.SETTINGS
        else                 -> null
    }
}
