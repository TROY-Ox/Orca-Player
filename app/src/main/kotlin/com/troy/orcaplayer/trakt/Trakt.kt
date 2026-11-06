package com.troy.orcaplayer.trakt

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class TraktTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 7_776_000,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis() / 1000,
    val user: String? = null,
) {
    val expired: Boolean get() = System.currentTimeMillis() / 1000 > createdAt + expiresIn - 86_400
}

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

/** What a title is on Trakt: IMDb or TMDB ids, and the episode for series. */
data class TraktRef(val type: String, val imdb: String?, val tmdb: Int?, val season: Int? = null, val episode: Int? = null)

sealed interface PollResult {
    data object Pending : PollResult
    data object SlowDown : PollResult
    data object Expired : PollResult
    data object Denied : PollResult
    data class Done(val tokens: TraktTokens) : PollResult
}

/**
 * Trakt (20.9) with the user's own API app: device-code sign-in (works on TV), scrobbling, and
 * importing history and the watchlist. Tokens are kept encrypted on the device.
 */
class Trakt private constructor(context: Context) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(app.filesDir, "trakt/tokens.bin")
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(app, "orca_trakt", "orca_trakt_keys")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://orca_trakt_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val _tokens = MutableStateFlow<TraktTokens?>(null)
    val tokens: StateFlow<TraktTokens?> = _tokens.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        _tokens.value = runCatching {
            if (file.exists()) json.decodeFromString(TraktTokens.serializer(), aead.decrypt(file.readBytes(), AD).decodeToString()) else null
        }.getOrNull()
    }

    private fun save(t: TraktTokens?) {
        if (t == null) file.delete() else {
            file.parentFile?.mkdirs()
            file.writeBytes(aead.encrypt(json.encodeToString(TraktTokens.serializer(), t).encodeToByteArray(), AD))
        }
        _tokens.value = t
    }

    suspend fun signOut(clientId: String, clientSecret: String) = withContext(Dispatchers.IO) {
        val t = _tokens.value
        if (t != null && clientId.isNotBlank()) {
            runCatching {
                post("oauth/revoke", buildJsonObject { put("token", t.accessToken); put("client_id", clientId); put("client_secret", clientSecret) }, clientId, null).close()
            }
        }
        save(null)
    }

    suspend fun deviceCode(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        post("oauth/device/code", buildJsonObject { put("client_id", clientId) }, clientId, null).use { res ->
            if (!res.isSuccessful) throw IOException("Trakt answered ${res.code}. Check the client ID.")
            json.decodeFromString(DeviceCode.serializer(), res.body?.string().orEmpty())
        }
    }

    suspend fun poll(code: DeviceCode, clientId: String, clientSecret: String): PollResult = withContext(Dispatchers.IO) {
        post("oauth/device/token", buildJsonObject { put("code", code.deviceCode); put("client_id", clientId); put("client_secret", clientSecret) }, clientId, null).use { res ->
            when (res.code) {
                200 -> {
                    val t = json.decodeFromString(TraktTokens.serializer(), res.body?.string().orEmpty())
                    val named = t.copy(user = runCatching { username(t.accessToken, clientId) }.getOrNull())
                    save(named)
                    PollResult.Done(named)
                }
                400 -> PollResult.Pending
                429 -> PollResult.SlowDown
                404, 409, 410 -> PollResult.Expired
                418 -> PollResult.Denied
                else -> throw IOException("Trakt answered ${res.code}")
            }
        }
    }

    private fun username(token: String, clientId: String): String? = get("users/settings", clientId, token).use { res ->
        if (!res.isSuccessful) return null
        val body = json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject ?: return null
        ((body["user"] as? JsonObject)?.get("username") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    /** A valid access token, refreshed when it's close to expiring. */
    private fun access(clientId: String, clientSecret: String): String? {
        val t = _tokens.value ?: return null
        if (!t.expired) return t.accessToken
        val res = post(
            "oauth/token",
            buildJsonObject {
                put("refresh_token", t.refreshToken); put("client_id", clientId); put("client_secret", clientSecret)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
            },
            clientId, null,
        )
        res.use {
            if (!it.isSuccessful) {
                if (it.code == 401) save(null)
                return null
            }
            val fresh = json.decodeFromString(TraktTokens.serializer(), it.body?.string().orEmpty()).copy(user = t.user)
            save(fresh)
            return fresh.accessToken
        }
    }

    /** Scrobbling: [action] is start, pause or stop; [progress] is 0–100. */
    suspend fun scrobble(action: String, ref: TraktRef, progress: Double, clientId: String, clientSecret: String) = withContext(Dispatchers.IO) {
        val token = access(clientId, clientSecret) ?: return@withContext
        val body = buildJsonObject {
            if (ref.type == "movie") {
                putJsonObject("movie") { putJsonObject("ids") { ids(ref) } }
            } else {
                putJsonObject("show") { putJsonObject("ids") { ids(ref) } }
                putJsonObject("episode") { ref.season?.let { put("season", it) }; ref.episode?.let { put("number", it) } }
            }
            put("progress", progress.coerceIn(0.0, 100.0))
        }
        runCatching { post("scrobble/$action", body, clientId, token).close() }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.ids(ref: TraktRef) {
        ref.imdb?.let { put("imdb", it) }
        ref.tmdb?.let { put("tmdb", it) }
    }

    @Serializable
    data class Ids(val imdb: String? = null, val tmdb: Int? = null, val trakt: Int? = null)

    @Serializable
    data class Media(val title: String = "", val year: Int? = null, val ids: Ids = Ids())

    @Serializable
    data class ListEntry(val type: String = "", val movie: Media? = null, val show: Media? = null)

    @Serializable
    data class WatchedEntry(val movie: Media? = null, val show: Media? = null, val seasons: List<WatchedSeason> = emptyList())

    @Serializable
    data class WatchedSeason(val number: Int = 0, val episodes: List<WatchedEpisode> = emptyList())

    @Serializable
    data class WatchedEpisode(val number: Int = 0)

    suspend fun watchlist(clientId: String, clientSecret: String): List<ListEntry> = list("sync/watchlist", clientId, clientSecret)

    suspend fun watchedMovies(clientId: String, clientSecret: String): List<WatchedEntry> = watched("sync/watched/movies", clientId, clientSecret)

    suspend fun watchedShows(clientId: String, clientSecret: String): List<WatchedEntry> = watched("sync/watched/shows", clientId, clientSecret)

    private suspend fun list(path: String, clientId: String, clientSecret: String): List<ListEntry> = withContext(Dispatchers.IO) {
        val token = access(clientId, clientSecret) ?: throw IOException("Not signed in")
        get(path, clientId, token).use { res ->
            if (!res.isSuccessful) throw IOException("Trakt answered ${res.code}")
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ListEntry.serializer()), res.body?.string().orEmpty())
        }
    }

    private suspend fun watched(path: String, clientId: String, clientSecret: String): List<WatchedEntry> = withContext(Dispatchers.IO) {
        val token = access(clientId, clientSecret) ?: throw IOException("Not signed in")
        get(path, clientId, token).use { res ->
            if (!res.isSuccessful) throw IOException("Trakt answered ${res.code}")
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WatchedEntry.serializer()), res.body?.string().orEmpty())
        }
    }

    private fun request(path: String, clientId: String, token: String?) = Request.Builder()
        .url("https://api.trakt.tv/$path")
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", clientId)
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun get(path: String, clientId: String, token: String?) = http.newCall(request(path, clientId, token).build()).execute()

    private fun post(path: String, body: JsonObject, clientId: String, token: String?) =
        http.newCall(request(path, clientId, token).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()

    companion object {
        private val AD = "orca-trakt-v1".encodeToByteArray()
        @Volatile private var instance: Trakt? = null
        fun get(context: Context): Trakt = instance ?: synchronized(this) { instance ?: Trakt(context).also { instance = it } }

        /** Trakt ids for what's playing: Stremio-style IMDb ids ("tt123", "tt123:1:2") or the library's TMDB id. */
        fun refFor(titleType: String?, titleId: String?, episodeId: String?, tmdb: Int? = null, season: Int? = null, episode: Int? = null): TraktRef? {
            val imdb = titleId?.takeIf { it.startsWith("tt") }?.substringBefore(':')
            if (imdb == null && tmdb == null) return null
            val parts = episodeId?.split(':')
            val s = season ?: parts?.getOrNull(1)?.toIntOrNull()
            val e = episode ?: parts?.getOrNull(2)?.toIntOrNull()
            val isShow = titleType == "series" || titleType == "SERIES" || (s != null && e != null)
            if (isShow && (s == null || e == null)) return null
            return TraktRef(if (isShow) "episode" else "movie", imdb, tmdb, s, e)
        }
    }
}
