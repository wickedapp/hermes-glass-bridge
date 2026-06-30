package com.wickedapp.rokidtg.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpriteBroadcastTest {
    @Test fun maps_known_actions() {
        assertEquals(SpriteBroadcast.Gesture.BUTTON_CLICK, SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_CLICK))
        assertEquals(SpriteBroadcast.Gesture.BACK,         SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_DOUBLE_CLICK))
        assertEquals(SpriteBroadcast.Gesture.SWIPE_FORWARD, SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_TWO_FORWARD))
        assertEquals(SpriteBroadcast.Gesture.AI_START,     SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_AI_START))
        assertEquals(SpriteBroadcast.Gesture.BUTTON_LONG,  SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_LONG_PRESS))
    }
    @Test fun returns_null_for_unknown() {
        assertNull(SpriteBroadcast.fromAction("com.android.action.MADE_UP"))
        assertNull(SpriteBroadcast.fromAction(null))
    }
}
