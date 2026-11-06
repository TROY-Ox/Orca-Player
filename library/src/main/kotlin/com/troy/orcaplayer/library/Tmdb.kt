package com.troy.orcaplayer.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The TMDB API (v3), with the user's own key: a v3 API key or a v4 read access token both work.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
class Tmdb(private val key: String, private val language: String, private val http: OkHttpClient = defaultHttp) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val bearer = key.startsWith("eyJ")

    fun searchMovie(query: String, year: Int? = null): List<TmdbResult> =
        get("search/movie") { addQueryParameter("query", query); year?.let { addQueryParameter("year", it.toString()) } }
            .let { json.decodeFromString(TmdbPage.serializer(), it).results }

    fun searchTv(query: String, year: Int? = null): List<TmdbResult> =
        get("search/tv") { addQueryParameter("query", query); year?.let { addQueryParameter("first_air_date_year", it.toString()) } }
            .let { json.decodeFromString(TmdbPage.serializer(), it).results }

    fun movie(id: Int): TmdbMovie = json.decodeFromString(TmdbMovie.serializer(), get("movie/$id") {})

    fun tv(id: Int): TmdbTv = json.decodeFromString(TmdbTv.serializer(), get("tv/$id") {})

    fun season(tvId: Int, season: Int): TmdbSeason = json.decodeFromString(TmdbSeason.serializer(), get("tv/$tvId/season/$season") {})

    private fun get(path: String, params: HttpUrl.Builder.() -> Unit): String {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", key)
            addQueryParameter("language", language)
            addQueryParameter("include_adult", "false")
            params()
        }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 401) throw TmdbKeyException()
            if (res.code == 429) throw IOException("TMDB is busy, try again later")
            if (!res.isSuccessful) throw IOException("TMDB answered ${res.code}")
            return res.body?.string().orEmpty()
        }
    }

    companion object {
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        }

        fun image(path: String?, size: String = "w500"): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

        fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()
    }
}

class TmdbKeyException : IOException("The TMDB key was refused")

@Serializable
data class TmdbPage(val results: List<TmdbResult> = emptyList())

@Serializable
data class TmdbResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
) {
    val displayName: String get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year: Int? get() = Tmdb.yearOf(releaseDate ?: firstAirDate)
}

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbCollection(val id: Int, val name: String = "")

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String = "",
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("belongs_to_collection") val collection: TmdbCollection? = null,
)

@Serializable
data class TmdbTv(
    val id: Int,
    val name: String = "",
    val overview: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
)
