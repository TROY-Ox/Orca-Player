package com.troy.orcaplayer.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.core.data.OrcaDatabase
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.sources.network.ShareConnection
import com.troy.orcaplayer.sources.network.ShareStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Base64

/**
 * Builds and restores backups (22): settings and layouts, sources, links, My List, profiles,
 * network shares and, optionally, history. Database tables are copied column by column, so a
 * file from an older version restores into the current tables: columns it lacks take their
 * defaults and columns this version dropped are skipped. Restoring merges: matching rows are
 * updated and new ones added; nothing is deleted.
 */
class BackupManager(
    private val context: Context,
    private val db: OrcaDatabase,
    private val settings: SettingsRepository,
) {
    private val files = mapOf(
        BackupSection.SOURCES to "sources/addons.json",
        BackupSection.LINKS to "sources/links.json",
    )
    private val tables = mapOf(
        BackupSection.PROFILES to listOf("profiles"),
        BackupSection.MY_LIST to listOf("my_list"),
        BackupSection.HISTORY to listOf("resume_positions", "watch_time", "skip_marks", "library_watched"),
    )

    /** Sections that can go in a backup; shares only with a password, since they hold passwords. */
    fun available(withPassword: Boolean): List<BackupSection> =
        BackupSection.entries.filter { it != BackupSection.SHARES || withPassword }

    suspend fun create(sections: Set<BackupSection>, password: String?): ByteArray = withContext(Dispatchers.IO) {
        val secrets = !password.isNullOrEmpty()
        val out = LinkedHashMap<String, JsonElement>()
        for (s in sections) {
            val value: JsonElement? = when (s) {
                BackupSection.SETTINGS -> BackupFormat.json.encodeToJsonElement(
                    MapSerializer(String.serializer(), String.serializer()), settings.exportAll(includeSecrets = secrets),
                )
                BackupSection.SOURCES, BackupSection.LINKS -> files[s]?.let { readFile(it) }
                BackupSection.SHARES -> if (!secrets) null else BackupFormat.json.encodeToJsonElement(
                    ListSerializer(ShareConnection.serializer()), ShareStore.get(context).connections.value,
                )
                else -> JsonObject(tables.getValue(s).associateWith { dumpTable(it, secrets) })
            }
            if (value != null) out[s.name] = value
        }
        val payload = BackupPayload(
            app = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(),
            dbVersion = db.openHelper.readableDatabase.version,
            sections = out,
        )
        BackupFormat.write(payload, password)
    }

    /** Puts the chosen sections back. The app restarts afterwards so every screen reloads. */
    suspend fun restore(payload: BackupPayload, sections: Set<BackupSection>) = withContext(Dispatchers.IO) {
        // Profiles go first, so rows that belong to a profile find it.
        val order = listOf(BackupSection.PROFILES) + BackupSection.entries.filter { it != BackupSection.PROFILES }
        for (s in order) {
            if (s !in sections) continue
            val value = payload.sections[s.name] ?: continue
            when (s) {
                BackupSection.SETTINGS -> settings.importAll(
                    BackupFormat.json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), value),
                )
                BackupSection.SOURCES, BackupSection.LINKS -> files[s]?.let { writeFile(it, value) }
                BackupSection.SHARES -> {
                    val store = ShareStore.get(context)
                    BackupFormat.json.decodeFromJsonElement(ListSerializer(ShareConnection.serializer()), value).forEach { store.save(it) }
                }
                else -> {
                    val w = db.openHelper.writableDatabase
                    w.beginTransaction()
                    try {
                        value.jsonObject.forEach { (table, rows) -> if (table in tables.getValue(s)) restoreTable(w, table, rows.jsonArray) }
                        w.setTransactionSuccessful()
                    } finally {
                        w.endTransaction()
                    }
                }
            }
        }
    }

    private fun readFile(path: String): JsonElement? {
        val f = File(context.filesDir, path)
        if (!f.exists()) return null
        return runCatching { BackupFormat.json.parseToJsonElement(f.readText()) }.getOrNull()
    }

    private fun writeFile(path: String, value: JsonElement) {
        val f = File(context.filesDir, path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".restore")
        tmp.writeText(value.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(value.toString())
            tmp.delete()
        }
    }

    private fun dumpTable(table: String, secrets: Boolean): JsonArray {
        val rows = ArrayList<JsonElement>()
        db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { c ->
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, JsonElement>()
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    row[name] = if (!secrets && table == "profiles" && name in PROFILE_SECRETS) JsonNull else cell(c, i)
                }
                rows += JsonObject(row)
            }
        }
        return JsonArray(rows)
    }

    private fun cell(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JsonObject(mapOf("blob" to JsonPrimitive(Base64.getEncoder().encodeToString(c.getBlob(i)))))
        else -> JsonPrimitive(c.getString(i))
    }

    private data class Column(val name: String, val pk: Int)

    private fun columns(w: SupportSQLiteDatabase, table: String): List<Column> {
        val list = ArrayList<Column>()
        w.query("PRAGMA table_info(`$table`)").use { c ->
            val n = c.getColumnIndexOrThrow("name")
            val p = c.getColumnIndexOrThrow("pk")
            while (c.moveToNext()) list += Column(c.getString(n), c.getInt(p))
        }
        return list
    }

    private fun profileIds(w: SupportSQLiteDatabase): Set<String> {
        val ids = HashSet<String>()
        w.query("SELECT id FROM profiles").use { c -> while (c.moveToNext()) ids += c.getString(0) }
        return ids
    }

    private fun restoreTable(w: SupportSQLiteDatabase, table: String, rows: JsonArray) {
        val cols = columns(w, table)
        if (cols.isEmpty()) return
        val names = cols.map { it.name }.toSet()
        val keys = cols.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
        val owners = if (table != "profiles" && "profileId" in names) profileIds(w) else null
        for (r in rows) {
            val row = r.jsonObject
            if (owners != null) {
                val owner = (row["profileId"] as? JsonPrimitive)?.content
                if (owner == null || owner !in owners) continue
            }
            val values = ContentValues()
            row.forEach { (k, v) -> if (k in names) put(values, k, v) }
            // A profile restored without its PIN keeps the PIN it has on this device.
            if (table == "profiles") PROFILE_SECRETS.forEach { if (row[it] == null || row[it] is JsonNull) values.remove(it) }
            if (values.size() == 0) continue
            val where = keys.joinToString(" AND ") { "`$it` = ?" }
            val args: Array<Any?> = keys.map { (row[it] as? JsonPrimitive)?.content }.toTypedArray()
            val updated = if (keys.isNotEmpty() && args.none { it == null }) w.update(table, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, values, where, args) else 0
            if (updated == 0) w.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, values)
        }
    }

    private fun put(v: ContentValues, k: String, e: JsonElement) {
        when {
            e is JsonNull -> v.putNull(k)
            e is JsonObject && e["blob"] != null -> v.put(k, Base64.getDecoder().decode((e["blob"] as JsonPrimitive).content))
            e is JsonPrimitive && e.isString -> v.put(k, e.content)
            e is JsonPrimitive -> e.longOrNull?.let { v.put(k, it) } ?: e.doubleOrNull?.let { v.put(k, it) } ?: e.booleanOrNull?.let { v.put(k, if (it) 1 else 0) } ?: v.put(k, e.content)
            else -> v.put(k, e.toString())
        }
    }

    private companion object {
        val PROFILE_SECRETS = setOf("pinHash", "pinSalt")
    }
}
