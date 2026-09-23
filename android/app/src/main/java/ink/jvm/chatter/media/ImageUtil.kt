package ink.jvm.chatter.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** Turns a picked photo into an upright, size-capped JPEG plus a small thumbnail. */
object ImageUtil {
    class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val mime: String = "image/jpeg")

    private const val MAX_FULL = 1600
    private const val MAX_THUMB = 400

    /**
     * Full image + thumbnail. GIFs are sent as-is so the animation survives; the thumb is still a JPEG.
     * [original] keeps the file byte-for-byte (bytes stay empty when it is over 40 MB: the caller streams it instead).
     */
    fun prepare(ctx: Context, uri: Uri, original: Boolean = false): Pair<Encoded, Encoded> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法读取图片")
        if (original) {
            val len = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
            val mime = bounds.outMimeType ?: "image/jpeg"
            val t = thumbOf(ctx, uri, bounds)
            val raw = if (len in 1..(40L * 1024 * 1024)) open(ctx, uri).use { it.readBytes() } else ByteArray(0)
            return Encoded(raw, bounds.outWidth, bounds.outHeight, mime) to t
        }
        if (bounds.outMimeType == "image/gif") {
            val raw = open(ctx, uri).use { it.readBytes() }
            if (raw.size <= 15 * 1024 * 1024) {
                val first = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IOException("无法解码图片")
                val t = fit(first, MAX_THUMB)
                return Encoded(raw, bounds.outWidth, bounds.outHeight, "image/gif") to Encoded(jpeg(t, 70), t.width, t.height)
            }
        }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_FULL) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open(ctx, uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("无法解码图片")
        val rotation = open(ctx, uri).use { runCatching { exifRotation(ExifInterface(it)) }.getOrDefault(0) }
        val upright = if (rotation == 0) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true
        )
        val full = fit(upright, MAX_FULL)
        val thumb = fit(full, MAX_THUMB)
        return Encoded(jpeg(full, 85), full.width, full.height) to Encoded(jpeg(thumb, 70), thumb.width, thumb.height)
    }

    private fun thumbOf(ctx: Context, uri: Uri, bounds: BitmapFactory.Options): Encoded {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_THUMB * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = open(ctx, uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("无法解码图片")
        val rotation = open(ctx, uri).use { runCatching { exifRotation(ExifInterface(it)) }.getOrDefault(0) }
        val upright = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val t = fit(upright, MAX_THUMB)
        return Encoded(jpeg(t, 70), t.width, t.height)
    }

    private fun open(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw IOException("无法打开图片")

    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun fit(b: Bitmap, max: Int): Bitmap {
        val scale = max.toFloat() / maxOf(b.width, b.height)
        if (scale >= 1f) return b
        val w = (b.width * scale).roundToInt().coerceAtLeast(1)
        val h = (b.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** Cover frame + duration of a picked video, both needed before the upload. */
    fun videoInfo(ctx: Context, uri: Uri): Triple<Encoded, Int, Pair<Int, Int>> {
        val mr = android.media.MediaMetadataRetriever()
        try {
            mr.setDataSource(ctx, uri)
            val dur = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            val w = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val frame = mr.getFrameAtTime(minOf(1_000_000L, dur * 1000L / 2), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("无法读取视频封面")
            val t = fit(frame, MAX_THUMB)
            val (fw, fh) = if (rot == 90 || rot == 270) h to w else w to h
            return Triple(Encoded(jpeg(t, 70), t.width, t.height), dur, fw to fh)
        } finally {
            runCatching { mr.release() }
        }
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
