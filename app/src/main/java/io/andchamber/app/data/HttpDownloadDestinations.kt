package io.andchamber.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Chooses the platform destination: MediaStore Downloads on API 29+, app external Downloads before. */
internal fun destinationFor(context: Context): HttpDownloadDestination =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreDownloadDestination(context.applicationContext)
    } else {
        val dir =
            context.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("No external storage for downloads")
        FileDownloadDestination(dir)
    }

/**
 * API 29+: inserts a pending MediaStore item, streams into it, then clears IS_PENDING on commit.
 * MediaStore renames on a name clash, so an existing file is never overwritten. Abort deletes the item.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaStoreDownloadDestination(
    private val context: Context,
) : HttpDownloadDestination {
    override fun create(
        fileName: String,
        mimeType: String,
    ): PendingDownload = MediaStorePending(context, fileName, mimeType)

    private class MediaStorePending(
        private val context: Context,
        private val fileName: String,
        private val mimeType: String,
    ) : PendingDownload {
        private val resolver = context.contentResolver
        private var item: android.net.Uri? = null

        override fun openStream(): OutputStream {
            val uri =
                item ?: resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )?.also { item = it }
                    ?: throw IOException("MediaStore insert failed")
            return resolver.openOutputStream(uri) ?: throw IOException("No output stream for download")
        }

        override fun commit(): String {
            val uri = item ?: throw IOException("Nothing written")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            // Published now: from here on abort() must never delete this item.
            item = null
            // Pending items are invisible to other apps until IS_PENDING is cleared; read back the final name.
            val finalName =
                runCatching {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
            return finalName ?: fileName
        }

        override fun abort() {
            val uri = item ?: return
            item = null
            runCatching { resolver.delete(uri, null, null) }
        }
    }
}

/**
 * Pre-API-29: writes a hidden temporary file in [directory], then renames it to a free name. The
 * final name is reserved with createNewFile() first, so an existing file is never replaced.
 */
internal class FileDownloadDestination(
    private val directory: File,
) : HttpDownloadDestination {
    override fun create(
        fileName: String,
        mimeType: String,
    ): PendingDownload = FilePending(directory, fileName)

    private class FilePending(
        private val directory: File,
        private val fileName: String,
    ) : PendingDownload {
        private var temp: File? = null
        private var reserved: File? = null

        override fun openStream(): OutputStream {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create download directory")
            val file = File(directory, ".$fileName.${UUID.randomUUID()}.part")
            temp = file
            return file.outputStream()
        }

        override fun commit(): String {
            val source = temp ?: throw IOException("Nothing written")
            var attempt = 0
            while (true) {
                val candidate = if (attempt == 0) fileName else numberedName(fileName, attempt)
                val target = File(directory, candidate)
                // createNewFile is atomic: it fails if the name is taken, so nothing existing is overwritten.
                if (target.createNewFile()) {
                    reserved = target
                    if (!source.renameTo(target)) throw IOException("Cannot move download into place")
                    temp = null
                    reserved = null
                    return candidate
                }
                attempt++
                if (attempt > MAX_NAME_ATTEMPTS) throw IOException("No free file name for download")
            }
        }

        override fun abort() {
            temp?.delete()
            temp = null
            reserved?.delete()
            reserved = null
        }
    }

    private companion object {
        const val MAX_NAME_ATTEMPTS = 1000
    }
}
