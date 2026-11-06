package com.troy.orcaplayer.library

import android.content.Context
import com.troy.orcaplayer.core.data.LibraryDao
import com.troy.orcaplayer.core.data.LibraryItemEntity
import com.troy.orcaplayer.core.data.LibraryShowEntity
import com.troy.orcaplayer.core.data.LibraryWatchedEntity
import com.troy.orcaplayer.sources.local.LocalMediaRepository
import com.troy.orcaplayer.sources.network.ShareConnection
import com.troy.orcaplayer.sources.network.ShareMedia
import com.troy.orcaplayer.sources.network.ShareSessions
import com.troy.orcaplayer.sources.network.ShareType
import com.troy.orcaplayer.sources.network.ShareUris
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Where a scan is: null when idle. */
data class ScanState(val found: Int = 0, val matching: Int = 0, val matched: Int = 0, val error: String? = null)

/** Online lookups follow the user's settings. [key] is blank when there's no TMDB key. */
data class MatchOptions(val online: Boolean, val key: String, val language: String) {
    val enabled: Boolean get() = online && key.isNotBlank()
}

/**
 * The media library (20.1): scans local videos and the network shares the user chose, groups
 * episodes into series, and fills in posters, descriptions and ratings from TMDB.
 */
class Library(context: Context, private val dao: LibraryDao) {
    private val app = context.applicationContext
    private val local = LocalMediaRepository(app)
    private val shares = ShareSessions.get(app)
    private val lock = Mutex()

    val items: Flow<List<LibraryItemEntity>> = dao.observeItems()
    val shows: Flow<List<LibraryShowEntity>> = dao.observeShows()
    fun watched(profileId: String): Flow<Map<String, Long>> = dao.observeWatched(profileId).map { l -> l.associate { it.itemId to it.at } }

    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan.asStateFlow()

    /** Finds new and removed files, then matches what's new. Only one scan runs at a time. */
    suspend fun scan(options: MatchOptions) {
        if (lock.isLocked) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    _scan.value = ScanState()
                    val now = System.currentTimeMillis()
                    val existing = dao.items().associateBy { it.id }
                    val seen = HashSet<String>()
                    val scanned = HashSet<String>()
                    val fresh = ArrayList<LibraryItemEntity>()

                    fun add(f: FoundFile) {
                        seen += f.id
                        val old = existing[f.id]
                        if (old != null) {
                            if (old.seenAt < now - 60_000) fresh += old.copy(seenAt = now, sizeBytes = f.size, durationMs = f.durationMs.takeIf { it > 0 } ?: old.durationMs)
                            return
                        }
                        fresh += entityFor(f, now)
                        _scan.value = _scan.value?.copy(found = seen.size)
                    }

                    runCatching { local.scan() }.onSuccess { list ->
                        scanned += ORIGIN_LOCAL
                        list.asSequence()
                            .filter { !it.isAudio && (it.durationMs == 0L || it.durationMs >= MIN_DURATION_MS) }
                            .forEach { m ->
                                add(FoundFile(idOf(m.uri), m.uri, m.name, folderChain(m.path, m.folderName), ORIGIN_LOCAL, m.sizeBytes, m.durationMs))
                            }
                    }
                    for (conn in shares.store.connections.value.filter { it.inLibrary && it.type != ShareType.DLNA }) {
                        val ok = runCatching { walk(conn) { add(it) } }.isSuccess
                        if (ok) scanned += conn.id
                    }
                    if (fresh.isNotEmpty()) dao.upsertItems(fresh)
                    val gone = existing.values.filter { it.origin in scanned && it.id !in seen }.map { it.id }
                    gone.chunked(500).forEach { dao.deleteItems(it) }
                    dao.deleteEmptyShows()
                    ensureShows()
                    if (options.enabled) match(options)
                    _scan.value = null
                } catch (e: CancellationException) {
                    _scan.value = null
                    throw e
                } catch (e: Exception) {
                    _scan.value = ScanState(error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun clearError() { if (_scan.value?.error != null) _scan.value = null }

    private data class FoundFile(
        val id: String, val uri: String, val name: String, val folders: List<String>,
        val origin: String, val size: Long, val durationMs: Long,
    )

    private fun entityFor(f: FoundFile, now: Long): LibraryItemEntity {
        val p = NameParser.parse(f.name, f.folders)
        val episode = p.isEpisode
        return LibraryItemEntity(
            id = f.id, uri = f.uri, fileName = f.name, folder = f.folders.firstOrNull().orEmpty(), origin = f.origin,
            sizeBytes = f.size, durationMs = f.durationMs,
            kind = if (episode) KIND_EPISODE else KIND_MOVIE,
            title = p.title, year = p.year,
            season = if (episode) p.season ?: 1 else null,
            episode = p.episode,
            showKey = if (episode) NameParser.key(p.title) else null,
            tmdbId = null, overview = null, poster = null, backdrop = null, rating = null, genres = null,
            episodeTitle = null, still = null, collectionId = null, collectionName = null, imdbId = null,
            manual = false, matchedAt = 0, addedAt = now, seenAt = now,
        )
    }

    /** Folder names from the nearest up, from a file path ("/x/Show/Season 1/e.mkv" → Season 1, Show, x). */
    private fun folderChain(path: String?, fallback: String): List<String> {
        val parts = path?.split('/')?.filter { it.isNotEmpty() }?.dropLast(1)?.reversed()
        return if (parts.isNullOrEmpty()) listOf(fallback) else parts.take(4)
    }

    private fun walk(conn: ShareConnection, onFile: (FoundFile) -> Unit) {
        val client = shares.client(conn.id)
        var count = 0
        fun visit(path: String, folders: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || count > MAX_SHARE_FILES) return
            for (e in client.list(path)) {
                if (e.isFolder) {
                    visit(e.path, listOf(e.name) + folders, depth + 1)
                } else if (ShareMedia.isPlayable(e) && !ShareMedia.isAudio(e) && (e.size <= 0 || e.size >= MIN_SHARE_BYTES)) {
                    count++
                    val uri = ShareUris.build(conn, e.path)
                    onFile(FoundFile(idOf(uri), uri, e.name, folders.ifEmpty { listOf(conn.name) }, conn.id, e.size, 0))
                }
            }
        }
        visit(client.root, emptyList(), 0)
    }

    /** Every show key used by an episode gets a row, named after its episodes. */
    private suspend fun ensureShows() {
        val known = dao.shows().associateBy { it.showKey }
        dao.items().filter { it.kind == KIND_EPISODE && it.showKey != null && it.showKey !in known }
            .groupBy { it.showKey!! }
            .forEach { (key, eps) ->
                val name = eps.groupingBy { it.title }.eachCount().maxByOrNull { it.value }?.key ?: key
                dao.upsertShow(LibraryShowEntity(key, name, eps.firstNotNullOfOrNull { it.year }, null, null, null, null, null, null, false, 0))
            }
    }

    // --- Matching ---

    suspend fun match(options: MatchOptions, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!options.enabled) return@withContext
        val tmdb = Tmdb(options.key, options.language)
        val movies = dao.items().filter { it.kind == KIND_MOVIE && !it.manual && (force || it.matchedAt == 0L) }
        val shows = dao.shows().filter { !it.manual && (force || it.matchedAt == 0L) }
        val total = movies.size + shows.size
        var done = 0
        _scan.value = (_scan.value ?: ScanState()).copy(matching = total, matched = 0)
        for (m in movies) {
            runCatching { matchMovie(tmdb, m) }.onFailure { if (it is TmdbKeyException) throw it }
            done++
            _scan.value = _scan.value?.copy(matched = done)
            delay(PAUSE_MS)
        }
        for (s in shows) {
            runCatching { matchShow(tmdb, s) }.onFailure { if (it is TmdbKeyException) throw it }
            done++
            _scan.value = _scan.value?.copy(matched = done)
            delay(PAUSE_MS)
        }
    }

    private suspend fun matchMovie(tmdb: Tmdb, item: LibraryItemEntity) {
        val now = System.currentTimeMillis()
        val best = pick(tmdb.searchMovie(item.title, item.year).ifEmpty { if (item.year != null) tmdb.searchMovie(item.title) else emptyList() }, item.title, item.year)
        if (best == null) {
            dao.upsertItems(listOf(item.copy(matchedAt = now)))
            return
        }
        applyMovie(tmdb, item, best.id, now)
    }

    private suspend fun applyMovie(tmdb: Tmdb, item: LibraryItemEntity, id: Int, now: Long, manual: Boolean = item.manual) {
        val d = tmdb.movie(id)
        dao.upsertItems(
            listOf(
                item.copy(
                    tmdbId = d.id, title = d.title.ifBlank { item.title }, year = Tmdb.yearOf(d.releaseDate) ?: item.year,
                    overview = d.overview, poster = Tmdb.image(d.posterPath), backdrop = Tmdb.image(d.backdropPath, "w1280"),
                    rating = d.voteAverage, genres = d.genres.joinToString(", ") { it.name }.ifEmpty { null },
                    collectionId = d.collection?.id, collectionName = d.collection?.name, imdbId = d.imdbId,
                    durationMs = if (item.durationMs == 0L && d.runtime != null) d.runtime * 60_000L else item.durationMs,
                    manual = manual, matchedAt = now,
                ),
            ),
        )
    }

    private suspend fun matchShow(tmdb: Tmdb, show: LibraryShowEntity) {
        val now = System.currentTimeMillis()
        val best = pick(tmdb.searchTv(show.name, show.year).ifEmpty { if (show.year != null) tmdb.searchTv(show.name) else emptyList() }, show.name, show.year)
        if (best == null) {
            dao.upsertShow(show.copy(matchedAt = now))
            return
        }
        applyShow(tmdb, show, best.id, now)
    }

    private suspend fun applyShow(tmdb: Tmdb, show: LibraryShowEntity, id: Int, now: Long, manual: Boolean = show.manual) {
        val d = tmdb.tv(id)
        dao.upsertShow(
            show.copy(
                tmdbId = d.id, name = d.name.ifBlank { show.name }, year = Tmdb.yearOf(d.firstAirDate) ?: show.year,
                overview = d.overview, poster = Tmdb.image(d.posterPath), backdrop = Tmdb.image(d.backdropPath, "w1280"),
                rating = d.voteAverage, genres = d.genres.joinToString(", ") { it.name }.ifEmpty { null },
                manual = manual, matchedAt = now,
            ),
        )
        val episodes = dao.episodes(show.showKey)
        val updated = ArrayList<LibraryItemEntity>()
        for ((season, eps) in episodes.groupBy { it.season ?: 1 }) {
            val info = runCatching { tmdb.season(d.id, season) }.getOrNull()?.episodes?.associateBy { it.episodeNumber }
            for (e in eps) {
                val ep = info?.get(e.episode ?: -1)
                updated += e.copy(
                    tmdbId = d.id, title = d.name.ifBlank { e.title },
                    episodeTitle = ep?.name, overview = ep?.overview ?: e.overview, still = Tmdb.image(ep?.stillPath, "w300"),
                    poster = Tmdb.image(d.posterPath), backdrop = Tmdb.image(d.backdropPath, "w1280"),
                    durationMs = if (e.durationMs == 0L && ep?.runtime != null) ep.runtime * 60_000L else e.durationMs,
                    matchedAt = now,
                )
            }
            delay(PAUSE_MS)
        }
        if (updated.isNotEmpty()) dao.upsertItems(updated)
    }

    /** Best search result: same name and year first, then same year, then the most popular. */
    private fun pick(results: List<TmdbResult>, title: String, year: Int?): TmdbResult? {
        if (results.isEmpty()) return null
        val key = NameParser.key(title)
        fun nameMatches(r: TmdbResult) = listOfNotNull(r.title, r.name, r.originalTitle, r.originalName).any { NameParser.key(it) == key }
        return results.firstOrNull { nameMatches(it) && (year == null || it.year == year) }
            ?: results.firstOrNull { year != null && it.year != null && kotlin.math.abs(it.year!! - year) <= 1 && nameMatches(it) }
            ?: results.firstOrNull { year != null && it.year == year }
            ?: results.first()
    }

    // --- Fix match and editing ---

    suspend fun search(options: MatchOptions, query: String, year: Int?, series: Boolean): List<TmdbResult> = withContext(Dispatchers.IO) {
        val tmdb = Tmdb(options.key, options.language)
        if (series) tmdb.searchTv(query, year) else tmdb.searchMovie(query, year)
    }

    suspend fun fixMovie(options: MatchOptions, itemId: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        val item = dao.item(itemId) ?: return@withContext
        applyMovie(Tmdb(options.key, options.language), item, tmdbId, System.currentTimeMillis(), manual = true)
    }

    suspend fun fixShow(options: MatchOptions, showKey: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        val show = dao.show(showKey) ?: return@withContext
        applyShow(Tmdb(options.key, options.language), show, tmdbId, System.currentTimeMillis(), manual = true)
    }

    /** Edit by hand: the new title and year stay, and online details are cleared until matched again. */
    suspend fun editMovie(itemId: String, title: String, year: Int?) = withContext(Dispatchers.IO) {
        val item = dao.item(itemId) ?: return@withContext
        dao.upsertItems(
            listOf(
                item.copy(
                    title = title, year = year, tmdbId = null, overview = null, poster = null, backdrop = null, rating = null,
                    genres = null, collectionId = null, collectionName = null, imdbId = null, manual = true, matchedAt = 0,
                ),
            ),
        )
    }

    suspend fun editShow(showKey: String, name: String, year: Int?) = withContext(Dispatchers.IO) {
        val show = dao.show(showKey) ?: return@withContext
        dao.upsertShow(show.copy(name = name, year = year, tmdbId = null, overview = null, poster = null, backdrop = null, rating = null, genres = null, manual = true, matchedAt = 0))
        dao.upsertItems(dao.episodes(showKey).map { it.copy(title = name, tmdbId = null, episodeTitle = null, still = null, poster = null, backdrop = null) })
    }

    /** "This is an episode" or "This is a movie", for files the name rules read the wrong way. */
    suspend fun setKind(itemId: String, episodeOf: String?, season: Int?, episode: Int?) = withContext(Dispatchers.IO) {
        val item = dao.item(itemId) ?: return@withContext
        val updated = if (episodeOf == null) {
            item.copy(kind = KIND_MOVIE, showKey = null, season = null, episode = null, episodeTitle = null, still = null, manual = true, matchedAt = 0)
        } else {
            item.copy(kind = KIND_EPISODE, title = episodeOf, showKey = NameParser.key(episodeOf), season = season ?: 1, episode = episode, manual = true, matchedAt = 0)
        }
        dao.upsertItems(listOf(updated))
        dao.deleteEmptyShows()
        ensureShows()
    }

    suspend fun setWatched(profileId: String, ids: List<String>, watched: Boolean) = withContext(Dispatchers.IO) {
        if (watched) dao.markWatched(ids.map { LibraryWatchedEntity(profileId, it, System.currentTimeMillis()) })
        else dao.unmarkWatched(profileId, ids)
    }

    /** Forget everything and start again from the files. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        dao.clearItems()
        dao.clearShows()
    }

    companion object {
        const val ORIGIN_LOCAL = "local"
        const val KIND_MOVIE = "MOVIE"
        const val KIND_EPISODE = "EPISODE"
        private const val MIN_DURATION_MS = 4 * 60_000L
        private const val MIN_SHARE_BYTES = 30L * 1024 * 1024
        private const val MAX_DEPTH = 6
        private const val MAX_SHARE_FILES = 20_000
        private const val PAUSE_MS = 120L

        fun idOf(uri: String): String = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
    }
}
