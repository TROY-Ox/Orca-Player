package com.troy.orcaplayer.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** What a backup can hold (22). The user picks sections when making and when restoring one. */
enum class BackupSection {
    /** Settings, layouts, presets and per-video settings. */
    SETTINGS,
    /** Addons with their order and settings. */
    SOURCES,
    /** Saved links, folders and playlists of the URL player. */
    LINKS,
    MY_LIST,
    PROFILES,
    /** Network share connections; only in password-protected backups, because they hold passwords. */
    SHARES,
    /** Optional: resume positions, watched marks, skip marks and watch time. */
    HISTORY,
}

/** The backup's content: one entry per section, each a JSON value. */
@Serializable
data class BackupPayload(
    val version: Int = BackupFormat.VERSION,
    val app: String = "",
    val createdAt: Long = 0,
    /** The database version the tables came from, for migrating older files. */
    val dbVersion: Int = 0,
    val sections: Map<String, JsonElement> = emptyMap(),
) {
    val present: List<BackupSection> get() = BackupSection.entries.filter { it.name in sections }
}

/** The file on disk: either the payload in clear, or encrypted with a password. */
@Serializable
data class BackupEnvelope(
    val format: String = BackupFormat.FORMAT,
    val version: Int = BackupFormat.VERSION,
    val encrypted: Boolean = false,
    val payload: BackupPayload? = null,
    /** Base64 for encrypted files: the PBKDF2 salt, the AES-GCM nonce and the sealed payload. */
    val salt: String? = null,
    val iv: String? = null,
    val data: String? = null,
    val iterations: Int? = null,
)

class BackupPasswordException : Exception("Wrong password")
class BackupFormatException(message: String) : Exception(message)

object BackupFormat {
    const val FORMAT = "orca-player-backup"
    const val VERSION = 1
    private const val ITERATIONS = 210_000
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun write(payload: BackupPayload, password: String?): ByteArray {
        val envelope = if (password.isNullOrEmpty()) {
            BackupEnvelope(payload = payload)
        } else {
            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val iv = ByteArray(12).also(rnd::nextBytes)
            val clear = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
            val sealed = cipher(Cipher.ENCRYPT_MODE, password, salt, iv, ITERATIONS).doFinal(clear)
            val b64 = Base64.getEncoder()
            BackupEnvelope(encrypted = true, salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(sealed), iterations = ITERATIONS)
        }
        return json.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** Reads the envelope only, to tell whether a password is needed. */
    fun envelope(bytes: ByteArray): BackupEnvelope {
        val e = runCatching { json.decodeFromString(BackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw BackupFormatException("Not an ORCA Player backup") }
        if (e.format != FORMAT) throw BackupFormatException("Not an ORCA Player backup")
        if (e.version > VERSION) throw BackupFormatException("Made by a newer version of ORCA Player")
        return e
    }

    fun read(bytes: ByteArray, password: String?): BackupPayload {
        val e = envelope(bytes)
        if (!e.encrypted) return e.payload ?: throw BackupFormatException("Empty backup")
        if (password.isNullOrEmpty()) throw BackupPasswordException()
        val d = Base64.getDecoder()
        val clear = runCatching {
            cipher(Cipher.DECRYPT_MODE, password, d.decode(e.salt), d.decode(e.iv), e.iterations ?: ITERATIONS).doFinal(d.decode(e.data))
        }.getOrElse { throw BackupPasswordException() }
        return migrate(json.decodeFromString(BackupPayload.serializer(), clear.toString(Charsets.UTF_8)))
    }

    /** Upgrades payloads from older file versions. Version 1 is the first, so nothing to do yet. */
    fun migrate(p: BackupPayload): BackupPayload = p

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): Cipher {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    }
}
