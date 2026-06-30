package com.wickedapp.rokidtg.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File

object MediaCache {
    /**
     * Decodes a JPEG/PNG from [file] down-sampled to at most [maxW] pixels wide,
     * using RGB_565 config for minimal memory footprint on Rokid glasses (480px display).
     *
     * LRU/cache eviction is delegated to TDLib's own file cache (500 MB cap, Task 4).
     * We decode on demand; no in-memory bitmap cache for v1.
     */
    fun decodeForGlasses(file: File, maxW: Int = 480): Bitmap {
        // First pass: measure dimensions only (no pixel allocation).
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        // Compute the largest power-of-two inSampleSize that keeps width above maxW.
        var sample = 1
        while (opts.outWidth / sample > maxW * 2) sample *= 2

        // Second pass: decode at reduced resolution with RGB_565.
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bm = BitmapFactory.decodeFile(file.absolutePath, opts2)

        // If still wider than maxW (inSampleSize is coarse), scale precisely.
        return if (bm.width > maxW) {
            bm.scale(maxW, (bm.height * maxW.toFloat() / bm.width).toInt(), filter = false)
        } else {
            bm
        }
    }
}
