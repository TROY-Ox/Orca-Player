package com.troy.orcaplayer.ui.common

/**
 * Lazy list keys that are always unique. Lists built from sources can repeat an item (the same
 * torrent for two files of a season pack, an episode listed twice), and a repeated key makes
 * Compose crash, so repeats get "#2", "#3" and so on.
 */
fun <T> List<T>.keyed(key: (T) -> String): List<Pair<String, T>> {
    val seen = HashMap<String, Int>()
    return map { item ->
        val base = key(item)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        (if (n == 1) base else "$base#$n") to item
    }
}
