package com.troy.orcaplayer.sources.stremio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Real addons send numbers as text and the reverse, nulls, single values where lists belong and
 * unknown fields. These helpers read whatever is there and never throw.
 */
internal object LenientJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.field(name: String): JsonElement? = this?.get(name)?.takeIf { it !is JsonNull }

/** Text from a string, number or boolean. Blank text counts as missing. */
internal fun JsonElement?.text(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    else -> null
}

internal fun JsonObject?.str(name: String): String? = field(name).text()

internal fun JsonObject?.long(name: String): Long? = when (val e = field(name)) {
    is JsonPrimitive -> e.longOrNull ?: e.doubleOrNull?.toLong() ?: e.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
    else -> null
}

internal fun JsonObject?.int(name: String): Int? = long(name)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

internal fun JsonObject?.bool(name: String): Boolean? = when (val e = field(name)) {
    is JsonPrimitive -> e.booleanOrNull ?: when (e.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> e.longOrNull?.let { it != 0L }
    }
    else -> null
}

/** A list, also when the source sent a single value. */
internal fun JsonObject?.list(name: String): List<JsonElement> = when (val e = field(name)) {
    is JsonArray -> e.filter { it !is JsonNull }
    null -> emptyList()
    else -> listOf(e)
}

/** A list of strings, also from "a, b" text or a single value. */
internal fun JsonObject?.strings(name: String): List<String> = when (val e = field(name)) {
    is JsonArray -> e.mapNotNull { it.text() ?: it.obj().str("name") }
    is JsonPrimitive -> e.text()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    else -> emptyList()
}

internal fun JsonObject?.objects(name: String): List<JsonObject> = list(name).mapNotNull { it.obj() }
