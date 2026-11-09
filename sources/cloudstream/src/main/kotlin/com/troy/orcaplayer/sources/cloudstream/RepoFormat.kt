package com.troy.orcaplayer.sources.cloudstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A repository's repo.json (19.2). */
@Serializable
data class RepoManifest(
    val name: String = "",
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
    val iconUrl: String? = null,
)

/** One entry of a plugins.json list. */
@Serializable
data class PluginEntry(
    val url: String,
    val status: Int = 1,
    val version: Int = 0,
    val apiVersion: Int = 1,
    val name: String,
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repositoryUrl: String? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val tvTypes: List<String>? = null,
    val fileSize: Long? = null,
    /** Not part of every repository; checked when present. */
    @SerialName("fileHash") val fileHash: String? = null,
) {
    val compatible: Boolean get() = apiVersion <= RepoFormat.HOST_API_VERSION
    val down: Boolean get() = status == 0
}

object RepoFormat {
    /** The plugin API version this host provides. Newer extensions are marked incompatible. */
    const val HOST_API_VERSION = 1

    /** Extensions larger than this are refused. */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    fun parseRepo(text: String): RepoManifest = json.decodeFromString(RepoManifest.serializer(), text.trim().removePrefix("\uFEFF"))

    fun parsePlugins(text: String): List<PluginEntry> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PluginEntry.serializer()), text.trim().removePrefix("\uFEFF"))
            .filter { it.url.isNotBlank() && it.internalName.isNotBlank() }

    /**
     * What the user typed, as a repo.json URL: an https link, or a `cloudstreamrepo://` link.
     * Short codes are not resolved, because that would ask a third-party service.
     */
    fun normalizeRepoUrl(input: String): String? {
        val t = input.trim()
        val url = when {
            t.startsWith("cloudstreamrepo://", true) -> "https://" + t.substringAfter("://")
            t.startsWith("https://", true) || t.startsWith("http://", true) -> t
            else -> return null
        }
        return url.takeIf { runCatching { java.net.URI(it).host }.getOrNull()?.isNotBlank() == true }
    }

    /** Relative plugin list and file URLs are resolved against the repository URL. */
    fun resolve(base: String, ref: String): String = runCatching { java.net.URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    /** A safe file name for an extension, from its internal name. */
    fun fileName(internalName: String): String =
        internalName.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("").take(80) + ".cs3"

    /** "Name" with characters that could break an ID replaced. */
    fun providerKey(internalName: String, providerName: String): String =
        (internalName + "." + providerName).map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

    /** The SHA-256 a repository gives for a file ("sha256-…", "sha256:…" or bare hex), or null when it gives none or something else. */
    fun expectedSha256(fileHash: String?): String? =
        fileHash?.trim()?.lowercase()?.removePrefix("sha256:")?.removePrefix("sha256-")?.takeIf { h -> h.length == 64 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
