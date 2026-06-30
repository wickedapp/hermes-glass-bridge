package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.media.MediaCache
import timber.log.Timber
import java.io.File

/**
 * Fullscreen fragment for viewing a downloaded photo or playing a video.
 * Covers ChatFragment (and its ComposerOverlay) for the duration it is on screen.
 * Back-press via the standard back-stack pops this fragment and restores the chat.
 */
class MediaViewerFragment : Fragment() {

    enum class Kind { PHOTO, VIDEO }

    companion object {
        private const val ARG_FILE_PATH = "filePath"
        private const val ARG_KIND      = "kind"

        fun newInstance(filePath: String, kindName: String): MediaViewerFragment =
            MediaViewerFragment().also {
                it.arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                    putString(ARG_KIND, kindName)
                }
            }
    }

    private val file: File get() = File(requireArguments().getString(ARG_FILE_PATH)!!)
    private val kind: Kind get() = Kind.valueOf(requireArguments().getString(ARG_KIND)!!)

    private var player: ExoPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_media_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val img = view.findViewById<ImageView>(R.id.image)
        val pv  = view.findViewById<PlayerView>(R.id.video)

        when (kind) {
            Kind.PHOTO -> {
                img.visibility = View.VISIBLE
                pv.visibility  = View.GONE
                try {
                    img.setImageBitmap(MediaCache.decodeForGlasses(file))
                } catch (e: Exception) {
                    Timber.tag("Media").e(e, "Failed to decode photo: %s", file.absolutePath)
                }
            }
            Kind.VIDEO -> {
                img.visibility = View.GONE
                pv.visibility  = View.VISIBLE
                val p = ExoPlayer.Builder(requireContext()).build().also { player = it }
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                pv.player = p
                p.prepare()
                p.play()
            }
        }
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        super.onDestroyView()
    }
}
