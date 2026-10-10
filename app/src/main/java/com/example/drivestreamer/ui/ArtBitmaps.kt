package com.example.drivestreamer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Shared artwork decoding for the screens that show cover art. */
object ArtBitmaps {

    /**
     * Decodes [bytes] at no more than about [maxSide] pixels on each side
     * (covers can be several megapixels; there's no need to hold that in
     * memory to draw a 48dp thumbnail). Returns null if it isn't an image.
     */
    fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}