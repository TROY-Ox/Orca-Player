package com.troy.orcaplayer.tools

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Where a saved file goes in the shared gallery folders. */
enum class SaveKind(val root: String, val folder: String) {
    CLIP(Environment.DIRECTORY_MOVIES, "Clips"),
    VIDEO(Environment.DIRECTORY_MOVIES, "Converted"),
    AUDIO(Environment.DIRECTORY_MUSIC, "Audio"),
    SCREENSHOT(Environment.DIRECTORY_PICTURES, "Screenshots"),
    SUBTITLE(Environment.DIRECTORY_DOCUMENTS, "Subtitles"),
}

/** Copies a finished file into the gallery (for example Movies/ORCA Player/Clips) and returns its URI. */
object MediaSaver {
    const val APP_FOLDER = "ORCA Player"

    /** True when saving needs the old storage permission (Android 9 and older). */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < 29

    fun save(context: Context, source: File, displayName: String, mime: String, kind: SaveKind): Uri {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = when (kind.root) {
                Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.root}/$APP_FOLDER/${kind.folder}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(kind.root), "$APP_FOLDER/${kind.folder}")
        dir.mkdirs()
        var target = File(dir, displayName)
        var n = 1
        while (target.exists()) {
            target = File(dir, displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.'))
            n++
        }
        source.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }
}
