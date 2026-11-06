package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/** Which kind of source produced a title, stream or subtitle. */
@Serializable
enum class SourceKind { LOCAL, LINK, STREMIO_ADDON, CLOUDSTREAM_EXTENSION, NETWORK_SHARE, DOWNLOAD }

/** Points back to the source that produced something, so the player never needs to know source details. */
@Serializable
data class SourceRef(
    val kind: SourceKind,
    /** Addon manifest ID, extension ID, share ID, or "local". */
    val sourceId: String,
    val sourceName: String = sourceId,
)
