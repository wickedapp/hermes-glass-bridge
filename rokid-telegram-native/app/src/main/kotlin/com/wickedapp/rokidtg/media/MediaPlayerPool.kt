package com.wickedapp.rokidtg.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Single-slot ExoPlayer pool.  Only one file plays at a time; starting a new
 * file releases the previous player automatically.
 *
 * Audio attributes follow the task spec:
 *   - Voice notes: USAGE_MEDIA + CONTENT_TYPE_SPEECH
 *   - (Video audio is handled inside MediaViewerFragment with CONTENT_TYPE_MOVIE)
 */
class MediaPlayerPool(private val ctx: Context) {

    private var player: ExoPlayer? = null

    /** Release any current player, then start playing [file] as a voice note. */
    fun playVoice(file: File) {
        stop()
        val p = ExoPlayer.Builder(ctx).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        p.prepare()
        p.play()
        player = p
    }

    /** Release the current player (if any) and clear the slot. */
    fun stop() {
        player?.release()
        player = null
    }
}
