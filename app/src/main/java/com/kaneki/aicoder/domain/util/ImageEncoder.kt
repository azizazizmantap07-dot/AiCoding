package com.kaneki.aicoder.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.kaneki.aicoder.domain.model.ImageAttachment
import java.io.ByteArrayOutputStream

/**
 * Membaca URI gambar, meresize agar hemat token/bandwidth, lalu encode Base64.
 * Batas sisi terpanjang [maxSidePx] (default 1280) cukup untuk vision model
 * modern tanpa mengirim file multi-megabyte.
 */
object ImageEncoder {

    private const val DEFAULT_MAX_SIDE = 1280
    private const val JPEG_QUALITY = 82

    fun encodeUri(
        context: Context,
        uri: Uri,
        maxSidePx: Int = DEFAULT_MAX_SIDE
    ): Result<ImageAttachment> = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("Gagal membaca gambar")

        val scaled = scaleDown(bitmap, maxSidePx)
        if (scaled !== bitmap) bitmap.recycle()

        val baos = ByteArrayOutputStream()
        val outMime = if (mime.contains("png") && !hasTransparency(scaled)) {
            "image/jpeg"
        } else if (mime.contains("png")) {
            "image/png"
        } else {
            "image/jpeg"
        }
        if (outMime == "image/png") {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        }
        scaled.recycle()

        val bytes = baos.toByteArray()
        if (bytes.isEmpty()) throw IllegalStateException("Gambar kosong setelah kompresi")
        // Batasi ~4 MB base64 mentah agar request tidak terlalu besar
        if (bytes.size > 4 * 1024 * 1024) {
            throw IllegalStateException("Gambar terlalu besar setelah kompresi (>4 MB)")
        }

        ImageAttachment(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mimeType = outMime,
            localUri = uri.toString()
        )
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        if (longest <= 0) return 1
        while (longest / sample > maxSide * 2) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        // Sampling cepat beberapa piksel sudut
        val points = listOf(
            0 to 0,
            bitmap.width - 1 to 0,
            0 to bitmap.height - 1,
            bitmap.width / 2 to bitmap.height / 2
        )
        return points.any { (x, y) ->
            val px = bitmap.getPixel(
                x.coerceIn(0, bitmap.width - 1),
                y.coerceIn(0, bitmap.height - 1)
            )
            (px ushr 24) < 255
        }
    }
}
