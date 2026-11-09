package com.cosmos.screensaver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * Small copies of blocked pictures, so the owner can recognise one before unblocking it. Blocking
 * still deletes the picture itself; only this copy (a few tens of kilobytes) is kept, in app data
 * beside the blocks themselves, and it is deleted when the picture is unblocked.
 */
internal object BlockedThumbnails {
    private const val DIRECTORY = "blocked-thumbs"
    private const val LONG_EDGE = 480
    private const val QUALITY = 85

    private fun directory(context: Context) = File(context.applicationContext.filesDir, DIRECTORY)

    fun file(context: Context, sha256: String?): File? =
        sha256?.takeIf { it.matches(HASH) }?.let { File(directory(context), "$it.jpg") }

    /** Writes the copy from [source]. Never throws: a picture can always be blocked without one. */
    fun save(context: Context, source: File, sha256: String?) {
        val target = file(context, sha256) ?: return
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            val options = BitmapFactory.Options().apply {
                inSampleSize = LibraryFormat.thumbnailSampleSize(bounds.outWidth, bounds.outHeight, LONG_EDGE)
            }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return
            val scale = LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            partial.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!partial.renameTo(target)) partial.delete()
            if (small !== decoded) small.recycle()
            decoded.recycle()
        } catch (error: IOException) {
            // No copy; the block itself is unaffected.
        } catch (error: OutOfMemoryError) {
            // As above.
        }
    }

    fun delete(context: Context, sha256: String?) {
        file(context, sha256)?.delete()
    }

    /** Deletes copies whose picture is no longer blocked. */
    fun keepOnly(context: Context, blockedHashes: Set<String>) {
        directory(context).listFiles()?.forEach { file ->
            if (file.name.removeSuffix(".jpg") !in blockedHashes) file.delete()
        }
    }

    private val HASH = Regex("[0-9a-f]{64}")
}
