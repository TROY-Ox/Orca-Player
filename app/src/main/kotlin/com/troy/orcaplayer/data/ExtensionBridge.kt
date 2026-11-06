package com.troy.orcaplayer.data

import com.troy.orcaplayer.sources.stremio.AddonClient
import com.troy.orcaplayer.sources.stremio.RequestResult

/**
 * CloudStream extensions (extended build only, 19). Each extension provider is listed in the
 * Addon Manager as an installed source with a Stremio-style manifest whose URL starts with
 * [SCHEME]; the bridge supplies its client. The standard build has no bridge.
 */
interface ExtensionBridge {
    fun client(a: InstalledAddon, timeoutMs: Int, onRequest: (RequestResult) -> Unit): AddonClient

    companion object {
        const val SCHEME = "cs3:"

        fun handles(manifestUrl: String): Boolean = manifestUrl.startsWith(SCHEME)
    }
}
