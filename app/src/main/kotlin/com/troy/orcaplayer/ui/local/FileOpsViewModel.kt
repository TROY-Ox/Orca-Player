package com.troy.orcaplayer.ui.local

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.sources.local.LocalKeys
import com.troy.orcaplayer.sources.local.LocalMedia
import com.troy.orcaplayer.vault.Vault
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A long file job, for the progress bar: bytes done of total, and the file being worked on. */
data class FileProgress(val labelRes: Int, val done: Long, val total: Long, val name: String)

/**
 * File manager actions from the Local tab (20.4): rename, move, copy, delete, share and hide in
 * the private folder. Files the app doesn't own change through MediaStore requests, so Android asks
 * the user once per batch.
 */
@HiltViewModel
class FileOpsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val resolver: ContentResolver get() = context.contentResolver
    val vault: Vault by lazy { Vault.get(context) }

    private val _consent = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    /** The UI shows this system request; its answer comes back through [onConsent]. */
    val consent: SharedFlow<IntentSender> = _consent.asSharedFlow()
    private var afterConsent: (suspend (Boolean) -> Unit)? = null

    private val _progress = MutableStateFlow<FileProgress?>(null)
    val progress: StateFlow<FileProgress?> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** Bumped after every change, so lists that aren't watching MediaStore reload. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    private fun isDocument(uri: Uri) = DocumentsContract.isDocumentUri(context, uri)

    fun onConsent(ok: Boolean) {
        val next = afterConsent ?: return
        afterConsent = null
        viewModelScope.launch { next(ok) }
    }

    private suspend fun ask(sender: IntentSender, then: suspend (Boolean) -> Unit) {
        afterConsent = then
        _consent.emit(sender)
    }

    // Delete

    fun delete(items: List<LocalMedia>, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch { deleteNow(items.map { Uri.parse(it.uri) }, onDone = onDone) }

    private suspend fun deleteNow(uris: List<Uri>, message: Int = R.string.files_deleted, onDone: (Boolean) -> Unit) {
        val (docs, media) = uris.partition(::isDocument)
        var ok = withContext(Dispatchers.IO) {
            docs.all { runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false) }
        }
        if (media.isEmpty()) return finish(ok, message, onDone)
        if (Build.VERSION.SDK_INT >= 30) {
            ask(MediaStore.createDeleteRequest(resolver, media).intentSender) { granted -> finish(ok && granted, message, onDone) }
            return
        }
        for (u in media) {
            val r = withContext(Dispatchers.IO) { runCatching { resolver.delete(u, null, null) } }
            val e = r.exceptionOrNull()
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                // Android 10 asks for one file at a time.
                ask(e.userAction.actionIntent.intentSender) { granted ->
                    if (granted) withContext(Dispatchers.IO) { runCatching { resolver.delete(u, null, null) } }
                    deleteNow(media.drop(media.indexOf(u) + 1), message, onDone)
                }
                return
            }
            if (r.isFailure) ok = false
        }
        finish(ok, message, onDone)
    }

    private suspend fun finish(ok: Boolean, message: Int, onDone: (Boolean) -> Unit) {
        _changed.value++
        if (!ok || message != 0) _messages.emit(if (ok) message else R.string.files_failed)
        onDone(ok)
    }

    // Rename

    fun rename(item: LocalMedia, newName: String) = viewModelScope.launch {
        val name = sanitize(newName, item.extension)
        if (name.isBlank()) return@launch
        val uri = Uri.parse(item.uri)
        if (isDocument(uri)) {
            val ok = withContext(Dispatchers.IO) { runCatching { DocumentsContract.renameDocument(resolver, uri, name) != null }.getOrDefault(false) }
            return@launch finish(ok, R.string.files_renamed) {}
        }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        suspend fun update(): Boolean = withContext(Dispatchers.IO) { runCatching { resolver.update(uri, values, null, null) > 0 }.getOrDefault(false) }
        when {
            Build.VERSION.SDK_INT >= 30 -> ask(MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender) { granted ->
                finish(granted && update(), R.string.files_renamed) {}
            }
            Build.VERSION.SDK_INT == 29 -> {
                val r = withContext(Dispatchers.IO) { runCatching { resolver.update(uri, values, null, null) } }
                val e = r.exceptionOrNull()
                if (e is RecoverableSecurityException) {
                    ask(e.userAction.actionIntent.intentSender) { granted -> finish(granted && update(), R.string.files_renamed) {} }
                } else {
                    finish(r.getOrDefault(0) > 0, R.string.files_renamed) {}
                }
            }
            else -> {
                // Android 8 and 9: rename the file itself, then let MediaStore see the new name.
                val ok = withContext(Dispatchers.IO) {
                    val path = item.path ?: return@withContext false
                    val from = java.io.File(path)
                    val to = java.io.File(from.parentFile, name)
                    !to.exists() && from.renameTo(to) && run {
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(from.absolutePath, to.absolutePath), null, null)
                        true
                    }
                }
                finish(ok, R.string.files_renamed) {}
            }
        }
    }

    private fun sanitize(name: String, ext: String): String {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
        if (clean.isEmpty()) return ""
        return if (ext.isNotEmpty() && !clean.lowercase().endsWith(".$ext")) "$clean.$ext" else clean
    }

    // Copy and move into a folder the user picks

    fun copyTo(items: List<LocalMedia>, tree: Uri, move: Boolean) = viewModelScope.launch {
        val total = items.sumOf { it.sizeBytes.coerceAtLeast(0) }
        var done = 0L
        val copied = mutableListOf<LocalMedia>()
        val ok = withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            items.all { m ->
                runCatching {
                    val target = DocumentsContract.createDocument(resolver, parent, m.mimeType ?: "application/octet-stream", m.name) ?: error("create")
                    resolver.openInputStream(Uri.parse(m.uri))!!.use { input ->
                        resolver.openOutputStream(target)!!.use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                _progress.value = FileProgress(if (move) R.string.files_moving else R.string.files_copying, done, total, m.name)
                            }
                        }
                    }
                    copied += m
                }.isSuccess
            }
        }
        _progress.value = null
        if (move && copied.isNotEmpty()) {
            deleteNow(copied.map { Uri.parse(it.uri) }, R.string.files_moved) { }
            if (!ok) _messages.emit(R.string.files_failed)
        } else {
            finish(ok, if (move) R.string.files_moved else R.string.files_copied) {}
        }
    }

    // Share

    fun shareIntent(items: List<LocalMedia>): Intent {
        val uris = ArrayList(items.map { Uri.parse(it.uri) })
        val type = if (items.all { it.isAudio }) "audio/*" else if (items.none { it.isAudio }) "video/*" else "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    // Private folder

    /** Encrypts the files into the private folder, then asks Android to remove the originals. */
    fun hide(items: List<LocalMedia>) = viewModelScope.launch {
        val total = items.sumOf { it.sizeBytes.coerceAtLeast(0) }
        var before = 0L
        val hidden = mutableListOf<LocalMedia>()
        for (m in items) {
            val uri = Uri.parse(m.uri)
            val r = runCatching {
                val key = withContext(Dispatchers.IO) { LocalKeys.forUri(resolver, uri, m.uri) }
                vault.add(m.name, m.sizeBytes, m.mimeType, m.durationMs, key, m.isAudio, { resolver.openInputStream(uri) ?: error("open") }) { d ->
                    _progress.value = FileProgress(R.string.vault_hiding, before + d, total, m.name)
                }
            }
            before += m.sizeBytes.coerceAtLeast(0)
            if (r.isSuccess) hidden += m
        }
        _progress.value = null
        if (hidden.isEmpty()) return@launch _messages.emit(R.string.files_failed)
        deleteNow(hidden.map { Uri.parse(it.uri) }, 0) { removed ->
            viewModelScope.launch { _messages.emit(if (removed) R.string.vault_hidden else R.string.vault_hidden_kept) }
        }
    }

    fun unhide(ids: List<String>) = viewModelScope.launch {
        var ok = true
        for (id in ids) {
            val item = vault.item(id) ?: continue
            runCatching { vault.unhide(id) { d -> _progress.value = FileProgress(R.string.vault_unhiding, d, item.sizeBytes, item.name) } }
                .onFailure { ok = false }
        }
        _progress.value = null
        finish(ok, R.string.vault_unhidden) {}
    }

    fun deleteHidden(ids: List<String>) = viewModelScope.launch {
        withContext(Dispatchers.IO) { ids.forEach(vault::delete) }
        _messages.emit(R.string.files_deleted)
    }

    fun renameHidden(id: String, name: String) = viewModelScope.launch(Dispatchers.IO) { vault.rename(id, name) }
}
