package com.troy.orcaplayer.sources.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans MediaStore for videos and audio and listens for changes. Folders that MediaStore doesn't
 * index (for example SD card folders on some devices) can be added through the file picker
 * ([addTree]); those are listed with DocumentsContract.
 */
class LocalMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val prefs = app.getSharedPreferences("orca_local_folders", Context.MODE_PRIVATE)

    /** Emits a fresh list now and whenever MediaStore changes. */
    fun observe(): Flow<List<LocalMedia>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().map { scan() }.flowOn(Dispatchers.IO)

    suspend fun scan(): List<LocalMedia> = withContext(Dispatchers.IO) {
        val all = ArrayList<LocalMedia>()
        runCatching { all += query(audio = false) }
        runCatching { all += query(audio = true) }
        trees().forEach { tree -> runCatching { all += listTree(tree) } }
        all.distinctBy { it.uri }
    }

    private fun query(audio: Boolean): List<LocalMedia> {
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val hasBuckets = !audio || Build.VERSION.SDK_INT >= 29
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(COL_DATA)
            add(COL_DURATION)
            if (!audio) {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }
            if (hasBuckets) {
                add(COL_BUCKET_ID)
                add(COL_BUCKET_NAME)
            }
        }.toTypedArray()
        val selection = if (audio) "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'audio/%'" else null
        val out = ArrayList<LocalMedia>()
        resolver.query(base, columns, selection, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val data = c.getColumnIndex(COL_DATA)
            val duration = c.getColumnIndex(COL_DURATION)
            val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val bucketId = c.getColumnIndex(COL_BUCKET_ID)
            val bucketName = c.getColumnIndex(COL_BUCKET_NAME)
            while (c.moveToNext()) {
                val mediaId = c.getLong(id)
                val path = c.stringOrNull(data)
                val parent = path?.let { File(it).parentFile }
                val folderId = c.stringOrNull(bucketId) ?: parent?.path?.lowercase()?.hashCode()?.toString() ?: "root"
                val folderName = c.stringOrNull(bucketName) ?: parent?.name ?: "/"
                out += LocalMedia(
                    id = mediaId,
                    uri = ContentUris.withAppendedId(base, mediaId).toString(),
                    name = c.stringOrNull(name) ?: path?.substringAfterLast('/') ?: mediaId.toString(),
                    folderId = folderId,
                    folderName = folderName,
                    path = path,
                    sizeBytes = c.longOrZero(size),
                    durationMs = c.longOrZero(duration),
                    width = c.intOrZero(width),
                    height = c.intOrZero(height),
                    dateAddedSec = c.longOrZero(date),
                    mimeType = c.stringOrNull(mime),
                    isAudio = audio,
                )
            }
        }
        return out
    }

    // Folders added through the file picker (Storage Access Framework).

    fun trees(): List<Uri> = prefs.getStringSet(KEY_TREES, emptySet()).orEmpty().map(Uri::parse)

    fun addTree(tree: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putStringSet(KEY_TREES, (trees().map { it.toString() } + tree.toString()).toSet()).apply()
    }

    fun removeTree(tree: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putStringSet(KEY_TREES, trees().map { it.toString() }.filter { it != tree.toString() }.toSet()).apply()
    }

    private fun listTree(tree: Uri, maxDepth: Int = 4): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, folderName: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val subFolders = ArrayList<Pair<String, String>>()
            resolver.query(children, cols, null, null, null)?.use { c ->
                // A folder with a .nomedia file is hidden, as in MediaStore.
                val rows = ArrayList<Array<String?>>()
                var hidden = false
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (name == ".nomedia") hidden = true
                    rows += arrayOf(c.getString(0), name, c.getString(2), c.getLong(3).toString(), c.getLong(4).toString())
                }
                if (hidden) return
                rows.forEach { r ->
                    val id = r[0] ?: return@forEach
                    val name = r[1] ?: return@forEach
                    val mime = r[2]
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> subFolders += id to name
                        mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) -> {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            out += LocalMedia(
                                id = uri.toString().hashCode().toLong() or (1L shl 40),
                                uri = uri.toString(),
                                name = name,
                                folderId = "tree:${tree}:$docId",
                                folderName = folderName,
                                path = null,
                                sizeBytes = r[3]?.toLongOrNull() ?: 0L,
                                durationMs = 0L,
                                width = 0,
                                height = 0,
                                dateAddedSec = (r[4]?.toLongOrNull() ?: 0L) / 1000,
                                mimeType = mime,
                                isAudio = mime.startsWith("audio/"),
                            )
                        }
                    }
                }
            }
            if (depth < maxDepth) subFolders.forEach { (id, name) -> walk(id, name, depth + 1) }
        }
        walk(rootId, rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { rootId }, 0)
        return out
    }

    private companion object {
        const val KEY_TREES = "trees"

        // Literal column names: some of these moved between MediaStore classes across API levels.
        const val COL_DATA = "_data"
        const val COL_DURATION = "duration"
        const val COL_BUCKET_ID = "bucket_id"
        const val COL_BUCKET_NAME = "bucket_display_name"

        fun Cursor.stringOrNull(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)
        fun Cursor.longOrZero(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)
        fun Cursor.intOrZero(i: Int): Int = if (i < 0 || isNull(i)) 0 else getInt(i)
    }
}
