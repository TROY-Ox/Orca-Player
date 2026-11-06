package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/** Every history, resume, My List, settings and source-enabled record carries a profile ID. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: String = DEFAULT_AVATAR,
    val isKids: Boolean = false,
    /** Salted hash of the PIN, never the PIN itself. Null when the profile has no PIN. */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val createdAt: Long = 0L,
    /** What a Kids profile may see and how long it may watch each day (18). */
    val kids: KidsRules = KidsRules(),
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_AVATAR = "orca"
    }
}
