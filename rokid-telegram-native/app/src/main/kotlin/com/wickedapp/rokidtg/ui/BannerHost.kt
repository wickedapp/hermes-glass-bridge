package com.wickedapp.rokidtg.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.wickedapp.rokidtg.R

/**
 * Singleton banner host that pins a single dismissible banner to the top of the safe area
 * (topMargin = R.dimen.safe_top = 160px from canvas top).
 *
 * Thread-safe: [show] may be called from any thread; all UI mutations are posted to main looper.
 * Subsequent [show] calls replace the current banner (cancel previous hide timer, show new text).
 */
object BannerHost {

    enum class Kind { INFO, WARN }

    private var container: ViewGroup? = null
    private var bannerView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Call once in MainActivity.onCreate. Stores the root FrameLayout as the banner container. */
    fun attach(activity: Activity) {
        container = activity.findViewById(R.id.root)
    }

    /**
     * Show a banner with [text] and [kind] for [durationMs] ms.
     * Safe to call from any thread. Replaces any currently-showing banner.
     */
    fun show(text: String, kind: Kind = Kind.INFO, durationMs: Long = 2500) {
        handler.post {
            val c = container ?: return@post
            val v = bannerView ?: run {
                val inflated = View.inflate(c.context, R.layout.view_banner, null)
                bannerView = inflated
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = c.resources.getDimensionPixelSize(R.dimen.safe_top)
                c.addView(inflated, lp)
                inflated
            }
            v.findViewById<TextView>(R.id.bannerText).apply {
                this.text = text
                setTextColor(
                    c.context.getColor(
                        if (kind == Kind.WARN) R.color.primary else R.color.primary_50
                    )
                )
            }
            v.visibility = View.VISIBLE
            // Cancel any pending hide, then arm a new one.
            handler.removeCallbacksAndMessages(HIDE_TOKEN)
            handler.postAtTime({ v.visibility = View.GONE }, HIDE_TOKEN, android.os.SystemClock.uptimeMillis() + durationMs)
        }
    }

    private val HIDE_TOKEN = Any()
}
