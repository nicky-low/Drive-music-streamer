package com.example.drivestreamer.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Turns album art into the colours for the Now Playing background.
 *
 * Deliberately small and dependency-free (no Palette library): shrink the
 * image, group its pixels by hue, and pick the hue that has the most
 * "vivid" pixels. Greys, near-black and near-white pixels are ignored so a
 * mostly-white cover with one red object gives red, not grey.
 */
object ArtColors {

    /** Used when there's no art (or a nothing-special grey one). */
    val DEFAULT_GRADIENT = intArrayOf(0xFF2E3350.toInt(), 0xFF1A1D2E.toInt(), 0xFF0B0C12.toInt())

    private const val SAMPLE = 32
    private const val HUE_BUCKETS = 12

    /**
     * Three colours, top to bottom: the art's colour darkened enough for
     * white text, fading to almost black. Always dark on purpose, so the
     * white controls on top stay readable whatever the cover looks like.
     */
    fun gradientFor(bitmap: Bitmap): IntArray {
        val base = dominantColor(bitmap)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        val hue = hsv[0]
        val sat = hsv[1].coerceAtMost(0.8f)
        // Never brighter than keeps white text readable, and never brighter
        // than the art itself, so a dark cover gives a dark background.
        val top = hsv[2].coerceIn(0.2f, 0.44f)
        return intArrayOf(
            Color.HSVToColor(floatArrayOf(hue, sat, top)),
            Color.HSVToColor(floatArrayOf(hue, sat * 0.9f, top * 0.55f)),
            Color.HSVToColor(floatArrayOf(hue, sat * 0.6f, top * 0.2f))
        )
    }

    private fun dominantColor(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        val pixels = IntArray(SAMPLE * SAMPLE)
        small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)

        val weight = DoubleArray(HUE_BUCKETS)
        val sumR = DoubleArray(HUE_BUCKETS)
        val sumG = DoubleArray(HUE_BUCKETS)
        val sumB = DoubleArray(HUE_BUCKETS)
        var allR = 0.0
        var allG = 0.0
        var allB = 0.0

        val hsv = FloatArray(3)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            allR += r
            allG += g
            allB += b

            Color.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation < 0.2f || value < 0.2f) continue // grey, black or white-ish

            val w = (saturation * value).toDouble()
            val bucket = (hsv[0] / (360f / HUE_BUCKETS)).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[bucket] += w
            sumR[bucket] += r * w
            sumG[bucket] += g * w
            sumB[bucket] += b * w
        }
        if (small !== bitmap) small.recycle()

        var best = -1
        for (i in 0 until HUE_BUCKETS) {
            if (best == -1 || weight[best] < weight[i]) best = i
        }
        // Barely any colour in the picture (black-and-white art): use the
        // overall average so the background still follows its brightness.
        if (best == -1 || weight[best] < 0.5) {
            val n = (SAMPLE * SAMPLE).toDouble()
            return Color.rgb((allR / n).toInt(), (allG / n).toInt(), (allB / n).toInt())
        }
        return Color.rgb(
            (sumR[best] / weight[best]).toInt(),
            (sumG[best] / weight[best]).toInt(),
            (sumB[best] / weight[best]).toInt()
        )
    }
}