package com.troy.orcaplayer.downloads

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadPlace
import com.troy.orcaplayer.downloads.logic.Naming
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel

/** An open file to write a download into at any position. */
class SinkChannel(val channel: FileChannel, private val pfd: ParcelFileDescriptor?) : Closeable {
    override fun close() {
        runCatching { channel.force(false) }
        runCatching { channel.close() }
        runCatching { pfd?.close() }
    }
}

/** Where a file download's bytes go while downloading and once finished (16.5). */
object Sinks {
    const val APP_FOLDER = "ORCA Player"
    const val DOWNLOADS_FOLDER = "Downloads"
    private const val PART = ".part"

    fun privateDir(context: Context): File = File(context.filesDir, "downloads").apply { mkdirs() }

    /** Subtitles of downloads saved where the gallery can't take them. */
    fun privateSubtitleDir(context: Context): File = File(context.filesDir, "download-subtitles").apply { mkdirs() }

    private fun usePublicFiles(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 9 and older: the public Movies folder with the storage permission, else the app's own external folder. */
    @Suppress("DEPRECATION")
    private fun legacyPublicDir(context: Context): File {
        val dir = if (usePublicFiles(context)) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$APP_FOLDER/$DOWNLOADS_FOLDER")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, DOWNLOADS_FOLDER)
        }
        return dir.apply { mkdirs() }
    }

    /** Free bytes where [item] will be written. */
    fun freeBytes(context: Context, item: DownloadItem): Long = runCatching {
        val dir = when {
            item.isStream || item.place == DownloadPlace.PRIVATE -> context.filesDir
            item.place == DownloadPlace.PUBLIC && Build.VERSION.SDK_INT < 29 -> legacyPublicDir(context)
            else -> Environment.getExternalStorageDirectory()
        }
        StatFs(dir.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun freeBytesTotal(context: Context): Pair<Long, Long> = runCatching {
        val s = StatFs(Environment.getExternalStorageDirectory().path)
        s.availableBytes to s.totalBytes
    }.getOrDefault(0L to 0L)

    data class Created(val uri: String?, val path: String?)

    /** Makes the file a download writes into. For the gallery it is a pending entry nobody sees until it is done. */
    fun create(context: Context, item: DownloadItem): Created {
        val mime = item.mimeType ?: Naming.mimeForExtension(item.fileName.substringAfterLast('.', "mp4"))
        return when (item.place) {
            DownloadPlace.PRIVATE -> {
                val f = unique(File(privateDir(context), item.fileName))
                val part = File(f.path + PART)
                part.createNewFile()
                Created(Uri.fromFile(part).toString(), part.path)
            }
            DownloadPlace.PUBLIC -> if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER/$DOWNLOADS_FOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (mime.startsWith("audio/")) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: throw StorageFailure("gallery insert failed")
                Created(uri.toString(), null)
            } else {
                val f = unique(File(legacyPublicDir(context), item.fileName))
                val part = File(f.path + PART)
                part.createNewFile()
                Created(Uri.fromFile(part).toString(), part.path)
            }
            DownloadPlace.FOLDER -> {
                val tree = Uri.parse(item.folderUri ?: throw StorageFailure("no folder"))
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, item.fileName)
                    ?: throw StorageFailure("could not create the file in the folder")
                Created(doc.toString(), null)
            }
        }
    }

    private fun unique(f: File): File {
        if (!f.exists() && !File(f.path + PART).exists()) return f
        val base = f.name.substringBeforeLast('.')
        val ext = f.name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val c = File(f.parentFile, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            if (!c.exists() && !File(c.path + PART).exists()) return c
            n++
        }
    }

    /** Opens the download's file for writing at any position. */
    fun open(context: Context, item: DownloadItem): SinkChannel {
        val path = item.outputPath
        if (path != null) {
            val f = File(path)
            if (!f.exists()) throw StorageFailure("missing")
            return SinkChannel(java.io.RandomAccessFile(f, "rw").channel, null)
        }
        val uri = Uri.parse(item.outputUri ?: throw StorageFailure("missing"))
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw StorageFailure("could not open")
        return SinkChannel(FileOutputStream(pfd.fileDescriptor).channel, pfd)
    }

    /** True when the download's file is still there (a pending gallery entry can be cleaned up by the system). */
    fun exists(context: Context, item: DownloadItem): Boolean {
        item.outputPath?.let { return File(it).exists() }
        val uri = item.outputUri?.let(Uri::parse) ?: return false
        return runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    /** Publishes a finished file: drops ".part", or clears the gallery's pending flag. Returns the final URI and path. */
    fun finish(context: Context, item: DownloadItem): Created {
        val path = item.outputPath
        if (path != null) {
            val part = File(path)
            val target = if (path.endsWith(PART)) File(path.removeSuffix(PART)) else part
            if (part != target && !part.renameTo(target)) throw StorageFailure("rename failed")
            if (item.place == DownloadPlace.PUBLIC) {
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(item.mimeType ?: Naming.mimeForExtension(target.extension)), null)
            }
            return Created(Uri.fromFile(target).toString(), target.path)
        }
        val uri = Uri.parse(item.outputUri ?: throw StorageFailure("missing"))
        if (item.place == DownloadPlace.PUBLIC && Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return Created(uri.toString(), null)
    }

    /** The name the file really got (the gallery may add " (1)"). */
    fun displayName(context: Context, item: DownloadItem): String {
        item.outputPath?.let { return File(it.removeSuffix(PART)).name }
        val uri = item.outputUri?.let(Uri::parse) ?: return item.fileName
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: item.fileName
    }

    /** Deletes a download's file, finished or not. */
    fun delete(context: Context, uri: String?, path: String?) {
        path?.let { runCatching { File(it).delete() } }
        val u = uri?.let(Uri::parse) ?: return
        if (u.scheme == "file") {
            runCatching { File(u.path ?: return).delete() }
            return
        }
        runCatching {
            if (DocumentsContract.isDocumentUri(context, u)) DocumentsContract.deleteDocument(context.contentResolver, u)
            else context.contentResolver.delete(u, null, null)
        }
    }

    /**
     * Saves a subtitle next to the video. The gallery's Movies folder only takes videos, so where it
     * refuses, the subtitle is kept in the app and still loads with the download.
     */
    fun saveSubtitle(context: Context, item: DownloadItem, name: String, data: ByteArray): String {
        val mime = Naming.mimeForExtension(name.substringAfterLast('.', "srt"))
        when {
            item.place == DownloadPlace.FOLDER && item.folderUri != null -> runCatching {
                val tree = Uri.parse(item.folderUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: error("create")
                context.contentResolver.openOutputStream(doc)!!.use { it.write(data) }
                return doc.toString()
            }
            item.place == DownloadPlace.PUBLIC && Build.VERSION.SDK_INT >= 29 -> runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER/$DOWNLOADS_FOLDER")
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("insert")
                context.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
                return uri.toString()
            }
            item.outputPath != null || item.place == DownloadPlace.PRIVATE || (item.place == DownloadPlace.PUBLIC && Build.VERSION.SDK_INT < 29) -> runCatching {
                val dir = item.outputPath?.let { File(it).parentFile } ?: privateDir(context)
                val f = File(dir, name)
                f.writeBytes(data)
                return Uri.fromFile(f).toString()
            }
        }
        val f = File(privateSubtitleDir(context), "${item.id}-$name")
        f.writeBytes(data)
        return Uri.fromFile(f).toString()
    }

    /** Copies a finished download into the app's private folder and removes the original (16.7). */
    fun moveToPrivate(context: Context, item: DownloadItem): Created {
        val source = item.outputUri?.let(Uri::parse) ?: throw StorageFailure("missing")
        val name = displayName(context, item)
        val target = unique(File(privateDir(context), name))
        openInput(context, source).use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
        delete(context, item.outputUri, item.outputPath)
        return Created(Uri.fromFile(target).toString(), target.path)
    }

    private fun openInput(context: Context, uri: Uri): InputStream =
        if (uri.scheme == "file") File(uri.path!!).inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw StorageFailure("could not read")
}

class StorageFailure(message: String) : Exception(message)
