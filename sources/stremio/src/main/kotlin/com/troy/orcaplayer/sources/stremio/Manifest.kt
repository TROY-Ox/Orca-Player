package com.troy.orcaplayer.sources.stremio

import kotlinx.serialization.Serializable

@Serializable
data class ExtraDecl(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int = 1,
)

@Serializable
data class CatalogDecl(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<ExtraDecl> = emptyList(),
) {
    val key: String get() = "$type/$id"
    val supportsSearch: Boolean get() = extra.any { it.name == "search" }
    val supportsSkip: Boolean get() = extra.any { it.name == "skip" }
    /** Catalogs that need an extra (other than search) never go on Home; they live in Discover with that extra chosen. */
    val requiredExtras: List<ExtraDecl> get() = extra.filter { it.isRequired }
    val searchOnly: Boolean get() = extra.any { it.name == "search" && it.isRequired }
    val genres: List<String> get() = extra.firstOrNull { it.name == "genre" }?.options.orEmpty()
}

/** A resource with its own types and ID prefixes, when the manifest gives them in the long form. */
@Serializable
data class ResourceDecl(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

@Serializable
data class BehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

@Serializable
data class AddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<ResourceDecl> = emptyList(),
    val catalogs: List<CatalogDecl> = emptyList(),
    val addonCatalogs: List<CatalogDecl> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: BehaviorHints = BehaviorHints(),
) {
    fun hasResource(name: String): Boolean = resources.any { it.name == name } || (name == "catalog" && catalogs.isNotEmpty())

    /** Ask an addon only about IDs and types it says it supports (14.1). */
    fun supports(resource: String, type: String, id: String?): Boolean {
        val decl = resources.firstOrNull { it.name == resource } ?: return false
        val types = decl.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = decl.idPrefixes ?: idPrefixes
        if (id != null && !prefixes.isNullOrEmpty() && prefixes.none { id.startsWith(it) }) return false
        return true
    }

    companion object {
        /** Reads a manifest leniently. Throws [IllegalArgumentException] when it isn't a manifest at all. */
        fun parse(text: String): AddonManifest {
            val root = LenientJson.parse(text).obj() ?: throw IllegalArgumentException("Not JSON")
            val id = root.str("id") ?: throw IllegalArgumentException("Manifest has no id")
            val name = root.str("name") ?: id
            val types = root.strings("types")
            val resources = root.list("resources").mapNotNull { e ->
                e.text()?.let { ResourceDecl(it) } ?: e.obj()?.let { o ->
                    val n = o.str("name") ?: return@let null
                    ResourceDecl(n, o.strings("types").takeIf { o.field("types") != null }, o.strings("idPrefixes").takeIf { o.field("idPrefixes") != null })
                }
            }
            fun catalogs(key: String) = root.objects(key).mapNotNull { c ->
                val type = c.str("type") ?: return@mapNotNull null
                val cid = c.str("id") ?: return@mapNotNull null
                val extras = c.objects("extra").mapNotNull { x ->
                    val n = x.str("name") ?: return@mapNotNull null
                    ExtraDecl(n, x.bool("isRequired") ?: false, x.strings("options"), x.int("optionsLimit") ?: 1)
                }.toMutableList()
                // The older short form: extraSupported / extraRequired lists of names.
                c.strings("extraSupported").forEach { n -> if (extras.none { it.name == n }) extras += ExtraDecl(n) }
                val required = c.strings("extraRequired").toSet()
                val merged = extras.map { if (it.name in required) it.copy(isRequired = true) else it }
                CatalogDecl(type, cid, c.str("name") ?: cid, merged)
            }
            val hints = root.field("behaviorHints").obj()
            return AddonManifest(
                id = id,
                version = root.str("version") ?: "0.0.0",
                name = name,
                description = root.str("description"),
                logo = root.str("logo"),
                background = root.str("background"),
                types = types,
                resources = resources,
                catalogs = catalogs("catalogs"),
                addonCatalogs = catalogs("addonCatalogs"),
                idPrefixes = root.strings("idPrefixes").takeIf { root.field("idPrefixes") != null },
                behaviorHints = BehaviorHints(
                    adult = hints.bool("adult") ?: false,
                    p2p = hints.bool("p2p") ?: false,
                    configurable = hints.bool("configurable") ?: false,
                    configurationRequired = hints.bool("configurationRequired") ?: false,
                ),
            )
        }
    }
}

/** What changed between two versions of a manifest, for the "Updated" mark (15.4). */
data class ManifestChanges(
    val addedCatalogs: List<String>,
    val removedCatalogs: List<String>,
    val addedResources: List<String>,
    val removedResources: List<String>,
) {
    val isEmpty: Boolean get() = addedCatalogs.isEmpty() && removedCatalogs.isEmpty() && addedResources.isEmpty() && removedResources.isEmpty()

    companion object {
        fun between(old: AddonManifest, new: AddonManifest): ManifestChanges {
            val oc = old.catalogs.associateBy { it.key }
            val nc = new.catalogs.associateBy { it.key }
            val orr = old.resources.map { it.name }.toSet()
            val nr = new.resources.map { it.name }.toSet()
            return ManifestChanges(
                addedCatalogs = (nc.keys - oc.keys).map { nc.getValue(it).name },
                removedCatalogs = (oc.keys - nc.keys).map { oc.getValue(it).name },
                addedResources = (nr - orr).toList(),
                removedResources = (orr - nr).toList(),
            )
        }
    }
}
