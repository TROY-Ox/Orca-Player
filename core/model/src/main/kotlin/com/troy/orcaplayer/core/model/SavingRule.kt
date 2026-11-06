package com.troy.orcaplayer.core.model

/**
 * The saving rule (3.7). One function decides for every download, clip, audio extract,
 * conversion and export.
 */
data class SavingPolicy(
    /** Saving switched on at all. */
    val savingEnabled: Boolean = true,
    /** Profile-level switch. */
    val profileAllowsSaving: Boolean = true,
    /** Source IDs where the user turned saving off. */
    val blockedSources: Set<String> = emptySet(),
    /** The user has seen and accepted the one-time "you must have the right to save this" reminder. */
    val reminderAccepted: Boolean = false,
)

enum class SaveDecision {
    ALLOWED,
    /** Allowed once the one-time reminder is accepted. */
    NEEDS_REMINDER,
    DENIED_DRM,
    DENIED_BY_SOURCE,
    DENIED_BY_SETTINGS,
}

object SavingRule {
    fun canSave(item: PlayItem, policy: SavingPolicy): SaveDecision =
        canSave(item.source, item.drmProtected, item.downloadable, policy)

    fun canSave(
        source: SourceRef,
        drmProtected: Boolean,
        downloadable: Boolean,
        policy: SavingPolicy,
    ): SaveDecision {
        if (drmProtected) return SaveDecision.DENIED_DRM
        return when (source.kind) {
            SourceKind.LOCAL, SourceKind.NETWORK_SHARE, SourceKind.DOWNLOAD -> SaveDecision.ALLOWED
            SourceKind.LINK, SourceKind.STREMIO_ADDON, SourceKind.CLOUDSTREAM_EXTENSION -> when {
                !downloadable -> SaveDecision.DENIED_BY_SOURCE
                !policy.savingEnabled || !policy.profileAllowsSaving -> SaveDecision.DENIED_BY_SETTINGS
                source.sourceId in policy.blockedSources -> SaveDecision.DENIED_BY_SETTINGS
                !policy.reminderAccepted -> SaveDecision.NEEDS_REMINDER
                else -> SaveDecision.ALLOWED
            }
        }
    }
}
