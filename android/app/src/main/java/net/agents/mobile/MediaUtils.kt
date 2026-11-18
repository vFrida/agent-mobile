package net.agents.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

data class ImageAttachment(
    val mime: String,
    val filename: String,
    val dataUrl: String
)

private const val MAX_IMAGE_EDGE = 1600
private const val JPEG_QUALITY = 82

fun Context.prepareImage(uri: Uri): ImageAttachment {
    val resolver = contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE * 2) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalStateException("无法读取图片")

    val scaled = scaleDown(bitmap, MAX_IMAGE_EDGE)
    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
    }
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()

    return ImageAttachment(
        mime = "image/jpeg",
        filename = "image-${System.currentTimeMillis()}.jpg",
        dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    )
}

fun decodeImageSource(context: Context, source: String): Bitmap? {
    return runCatching {
        val bytes = when {
            source.startsWith("data:") -> {
                val encoded = source.substringAfter(',', "")
                if (encoded.isEmpty()) return null
                Base64.decode(encoded, Base64.DEFAULT)
            }
            source.startsWith("http://") || source.startsWith("https://") ->
                java.net.URL(source).openStream().use { it.readBytes() }
            else -> context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() }
        }
        bytes?.let { decodeScaled(it) }
    }.getOrNull()
}

private fun decodeScaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val edge = max(bitmap.width, bitmap.height)
    if (edge <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true
    )
}
