package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

// Host-side copies of classes CloudStream keeps in its app rather than in the library module.
// Extensions call them by name, so they must exist with the same names and members, or loading
// fails with NoClassDefFoundError. (GPL-3.0, extended build only.)

const val PREFERENCES_NAME = "rebuild_preference"

/** JSON through the library's own Jackson mapper, looked up by reflection so this module needs no Jackson API. */
internal object HostJson {
    private val mapper: Any? by lazy {
        runCatching { Class.forName("com.lagradost.cloudstream3.MainAPIKt").getMethod("getMapper").invoke(null) }.getOrNull()
    }

    fun write(value: Any?): String? {
        if (value == null) return null
        val m = mapper ?: return value.toString()
        return runCatching { m.javaClass.getMethod("writeValueAsString", Any::class.java).invoke(m, value) as String }.getOrNull()
    }

    fun <T : Any> read(json: String, type: Class<T>): T? {
        val m = mapper ?: return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { m.javaClass.getMethod("readValue", String::class.java, Class::class.java).invoke(m, json, type) as T? }.getOrNull()
    }
}

object DataStore {
    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun Context.getSharedPrefs(): SharedPreferences = getPreferences(this)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)

    fun Context.getKeys(folder: String): List<String> {
        val fixed = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(fixed) }
    }

    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))

    fun Context.containsKey(folder: String, path: String): Boolean = containsKey(getFolderName(folder, path))

    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

    fun Context.removeKey(path: String) {
        runCatching { getSharedPrefs().edit().remove(path).apply() }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        return runCatching {
            val e = getSharedPrefs().edit()
            keys.forEach { e.remove(it) }
            e.apply()
            keys.size
        }.getOrDefault(0)
    }

    fun <T> Context.setKey(path: String, value: T) {
        runCatching { getSharedPrefs().edit().putString(path, HostJson.write(value)).apply() }
    }

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        val json = runCatching { getSharedPrefs().getString(path, null) }.getOrNull() ?: return null
        return HostJson.read(json, valueType)
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

    fun <T : Any> String.toKotlinObject(valueType: Class<T>): T =
        HostJson.read(this, valueType) ?: throw IllegalArgumentException("Could not parse JSON")

    inline fun <reified T : Any> String.toKotlinObject(): T = toKotlinObject(T::class.java)

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        if (!getSharedPrefs().contains(path)) return defVal
        return getKey(path, T::class.java)
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? = getKey(path, null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? = getKey(getFolderName(folder, path), null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        getKey(getFolderName(folder, path), defVal) ?: defVal
}
