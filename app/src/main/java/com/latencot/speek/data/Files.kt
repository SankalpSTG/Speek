package com.latencot.speek.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Files on the phone: what you attach, what the agent sent, and opening either. */
class Files(private val context: Context) {

    /** Something picked to send, not sent yet. */
    data class Pending(val uri: Uri, val name: String, val size: Long)

    private val sent = File(context.filesDir, "sent")
    private val received = File(context.cacheDir, "received")
    private val camera = File(context.cacheDir, "camera")

    fun pending(uri: Uri): Pending {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            .use { c ->
                requireNotNull(c) { "Cannot read $uri" }
                require(c.moveToFirst()) { "Cannot read $uri" }
                val name = c.getString(0)
                return Pending(uri, name, if (c.isNull(1)) -1 else c.getLong(1))
            }
    }

    /** Keeps a copy of a picked file (picker grants do not last) and returns it with its bytes. */
    fun keep(p: Pending, id: String): Pair<File, ByteArray> {
        val bytes = context.contentResolver.openInputStream(p.uri).use { requireNotNull(it) { "Cannot read ${p.name}" }.readBytes() }
        val dest = File(File(sent, id), p.name.replace('/', '_'))
        dest.parentFile!!.mkdirs()
        dest.writeBytes(bytes)
        return dest to bytes
    }

    /** Where an agent's file lives once fetched; it exists only after [save]. */
    fun local(remote: String): File = File(File(received, sha(remote).take(16)), Media.name(remote))

    fun save(remote: String, bytes: ByteArray): File = local(remote).also {
        it.parentFile!!.mkdirs()
        it.writeBytes(bytes)
    }

    /** A fresh file for the camera to write into, and its shareable uri. */
    fun cameraTarget(): Pair<File, Uri> {
        camera.mkdirs()
        val f = File(camera, "photo-${System.currentTimeMillis()}.jpg")
        return f to uri(f)
    }

    /** Opens a file or URL in whatever app handles it. Returns why not, or null. */
    fun open(target: String): String? {
        val intent = if (Media.isUrl(target)) {
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
        } else {
            val f = File(target)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: ActivityNotFoundException) {
            "No app on this phone opens ${Media.name(target)}."
        }
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    companion object {
        fun mime(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(Media.ext(name)) ?: "application/octet-stream"

        /** A preview no larger than [max] px on its long side, or null if it is not an image. */
        fun thumbnail(f: File, max: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private fun sha(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
