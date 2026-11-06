package com.troy.orcaplayer.trakt

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.MyListEntity
import com.troy.orcaplayer.core.data.MyListRepository
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.MyListKeys
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.settings.ExtrasSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.library.Library
import com.troy.orcaplayer.ui.shell.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TraktUi {
    data object Idle : TraktUi
    data class Code(val code: DeviceCode) : TraktUi
    data class Busy(val label: Int) : TraktUi
    data class Message(val text: Int, val arg: String = "", val ok: Boolean = true) : TraktUi
}

@HiltViewModel
class TraktViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
    private val myList: MyListRepository,
    private val library: Library,
) : ViewModel() {
    private val trakt = Trakt.get(context)
    val tokens: StateFlow<TraktTokens?> = trakt.tokens
    private val _ui = MutableStateFlow<TraktUi>(TraktUi.Idle)
    val ui: StateFlow<TraktUi> = _ui.asStateFlow()
    private var polling: Job? = null

    init { viewModelScope.launch { trakt.load() } }

    private suspend fun keys(): Pair<String, String> {
        val v = settingsRepo.values(profiles.currentProfileId.value).first()
        return v[ExtrasSettings.TraktClientId].trim() to v[ExtrasSettings.TraktClientSecret].trim()
    }

    fun connect() {
        polling?.cancel()
        polling = viewModelScope.launch {
            val (id, secret) = keys()
            if (id.isEmpty() || secret.isEmpty()) {
                _ui.value = TraktUi.Message(R.string.trakt_need_keys, ok = false)
                return@launch
            }
            val code = runCatching { trakt.deviceCode(id) }.getOrElse {
                _ui.value = TraktUi.Message(R.string.trakt_error, it.message.orEmpty(), ok = false)
                return@launch
            }
            _ui.value = TraktUi.Code(code)
            var wait = code.interval.coerceAtLeast(1) * 1000L
            val end = System.currentTimeMillis() + code.expiresIn * 1000L
            while (System.currentTimeMillis() < end) {
                delay(wait)
                when (val r = runCatching { trakt.poll(code, id, secret) }.getOrElse { PollResult.Pending }) {
                    PollResult.Pending -> Unit
                    PollResult.SlowDown -> wait += 1000
                    PollResult.Expired -> { _ui.value = TraktUi.Message(R.string.trakt_expired, ok = false); return@launch }
                    PollResult.Denied -> { _ui.value = TraktUi.Message(R.string.trakt_denied, ok = false); return@launch }
                    is PollResult.Done -> { _ui.value = TraktUi.Message(R.string.trakt_connected, r.tokens.user.orEmpty()); return@launch }
                }
            }
            _ui.value = TraktUi.Message(R.string.trakt_expired, ok = false)
        }
    }

    fun cancel() {
        polling?.cancel()
        _ui.value = TraktUi.Idle
    }

    fun disconnect() {
        viewModelScope.launch {
            val (id, secret) = keys()
            trakt.signOut(id, secret)
            _ui.value = TraktUi.Idle
        }
    }

    /** The Trakt watchlist goes into My List. */
    fun importWatchlist() {
        viewModelScope.launch {
            _ui.value = TraktUi.Busy(R.string.trakt_importing)
            val (id, secret) = keys()
            val pid = profiles.currentProfileId.value
            runCatching {
                var added = 0
                val now = System.currentTimeMillis()
                trakt.watchlist(id, secret).forEach { e ->
                    val media = e.movie ?: e.show ?: return@forEach
                    val imdb = media.ids.imdb ?: return@forEach
                    val type = if (e.show != null) TitleType.SERIES else TitleType.MOVIE
                    myList.add(
                        MyListEntity(
                            profileId = pid, itemKey = MyListKeys.forTitle(type, imdb), name = media.title, titleType = type.name,
                            titleId = imdb, sourceName = "Trakt", year = media.year, addedAt = now - added,
                        ),
                    )
                    added++
                }
                added
            }.onSuccess { _ui.value = TraktUi.Message(R.string.trakt_imported_watchlist, it.toString()) }
                .onFailure { _ui.value = TraktUi.Message(R.string.trakt_error, it.message.orEmpty(), ok = false) }
        }
    }

    /** Movies and episodes watched on Trakt are marked watched in the library. */
    fun importHistory() {
        viewModelScope.launch {
            _ui.value = TraktUi.Busy(R.string.trakt_importing)
            val (id, secret) = keys()
            val pid = profiles.currentProfileId.value
            runCatching {
                val movies = trakt.watchedMovies(id, secret).mapNotNull { it.movie?.ids?.tmdb }.toSet()
                val shows = trakt.watchedShows(id, secret).mapNotNull { w ->
                    val tmdb = w.show?.ids?.tmdb ?: return@mapNotNull null
                    tmdb to w.seasons.flatMap { s -> s.episodes.map { s.number to it.number } }.toSet()
                }.toMap()
                val items = library.items.first()
                val watched = items.filter { i ->
                    val t = i.tmdbId ?: return@filter false
                    if (i.kind == Library.KIND_MOVIE) t in movies
                    else shows[t]?.contains((i.season ?: 1) to (i.episode ?: -1)) == true
                }.map { it.id }
                library.setWatched(pid, watched, true)
                watched.size
            }.onSuccess { _ui.value = TraktUi.Message(R.string.trakt_imported_history, it.toString()) }
                .onFailure { _ui.value = TraktUi.Message(R.string.trakt_error, it.message.orEmpty(), ok = false) }
        }
    }
}

/** Trakt account (20.9): device-code sign-in with the user's own API app, then imports. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraktScreen(onBack: () -> Unit, vm: TraktViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.trakt_title), onBack = onBack) {}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.trakt_intro), style = Troy.type.body, color = c.text2)
            when (val u = ui) {
                is TraktUi.Code -> {
                    Text(stringResource(R.string.trakt_go_to, u.code.verificationUrl), style = Troy.type.body, color = c.text)
                    Text(u.code.userCode, style = Troy.type.display.copy(fontSize = 40.sp), color = c.accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.trakt_waiting), style = Troy.type.bodySmall, color = c.text2)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.trakt_open_site), { open(context, u.code.verificationUrl) }, kind = ButtonKind.PRIMARY)
                        TroyButton(stringResource(R.string.dialog_cancel), vm::cancel, kind = ButtonKind.GHOST)
                    }
                }
                is TraktUi.Busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    Text(stringResource(u.label), style = Troy.type.body, color = c.text2)
                }
                is TraktUi.Message -> TroyAlert(stringResource(u.text, u.arg), if (u.ok) AlertKind.OK else AlertKind.DANGER, Modifier.fillMaxWidth())
                TraktUi.Idle -> Unit
            }
            val t = tokens
            if (t == null) {
                if (ui !is TraktUi.Code) TroyButton(stringResource(R.string.trakt_connect), vm::connect, kind = ButtonKind.PRIMARY)
            } else {
                Text(stringResource(R.string.trakt_signed_in, t.user ?: "Trakt"), style = Troy.type.body, color = c.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyButton(stringResource(R.string.trakt_import_watchlist), vm::importWatchlist)
                    TroyButton(stringResource(R.string.trakt_import_history), vm::importHistory)
                    TroyButton(stringResource(R.string.trakt_disconnect), vm::disconnect, kind = ButtonKind.GHOST)
                }
            }
            Text(stringResource(R.string.trakt_keys_hint), style = Troy.type.bodySmall, color = c.text3)
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
