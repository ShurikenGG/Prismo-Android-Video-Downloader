package com.prismo.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

data class SavedFile(val uri: Uri, val displayName: String, val mime: String)

object MediaStorage {
    const val FOLDER = "Prismo"

    suspend fun saveToDownloads(ctx: Context, src: File): SavedFile {
        val mime = mimeFor(src)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(ctx, src, mime)
        else saveLegacy(ctx, src, mime)
    }

    // Android 10+: MediaStore, no storage permission needed.
    private fun saveScoped(ctx: Context, src: File, mime: String): SavedFile {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open output stream")
            out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        var name = src.name
        var path: String? = null
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                path = c.getString(1)
            }
        }
        // Ask the media scanner to index it so Gallery/Files pick it up immediately.
        path?.let { runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(it), arrayOf(mime), null) } }
        return SavedFile(uri, name, mime)
    }

    // Android 7-9: public Downloads folder + WRITE_EXTERNAL_STORAGE.
    private suspend fun saveLegacy(ctx: Context, src: File, mime: String): SavedFile {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path} (storage permission?)")
        val dest = uniqueFile(dir, src.name)
        src.copyTo(dest, overwrite = false)
        val scanned: Uri? = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Uri?> { cont ->
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime)) { _, u ->
                    if (cont.isActive) cont.resume(u)
                }
            }
        }
        return SavedFile(scanned ?: Uri.fromFile(dest), dest.name, mime)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
