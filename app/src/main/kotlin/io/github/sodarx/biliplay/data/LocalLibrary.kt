package io.github.sodarx.biliplay.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.sodarx.biliplay.core.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class DownloadEntry(
    val id: String,
    val track: Track,
    val status: String,
    val progress: Int = 0,
    val uri: String = "",
    val exportUri: String = "",
    val error: String = "",
)

data class LibraryOverview(
    val history: List<Track>,
    val searchHistory: List<String>,
    val downloads: List<DownloadEntry>,
    val downloadTree: String,
)

class LocalLibrary(context: Context, val profileId: String) : AutoCloseable {
    val changes = MutableStateFlow(0L)
    private val helper =
        object :
            SQLiteOpenHelper(
                context,
                File(context.filesDir, "profiles/${validProfile(profileId)}/library.db")
                    .apply { parentFile!!.mkdirs() }
                    .absolutePath,
                null,
                1,
            ) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE kv(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE history(track_key TEXT PRIMARY KEY,track TEXT NOT NULL,position INTEGER NOT NULL,played_at INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE downloads(id TEXT PRIMARY KEY,track TEXT NOT NULL,status TEXT NOT NULL,progress INTEGER NOT NULL,uri TEXT NOT NULL,export_uri TEXT NOT NULL,error TEXT NOT NULL)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

    init {
        helper.setWriteAheadLoggingEnabled(true)
    }

    fun get(key: String, fallback: String = ""): String =
        helper.readableDatabase.rawQuery("SELECT value FROM kv WHERE name=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else fallback
        }

    fun put(key: String, value: String) {
        writeValue(key, value)
        changes.update { it + 1 }
    }

    private fun writeValue(key: String, value: String) {
        helper.writableDatabase.insertWithOnConflict(
            "kv",
            null,
            ContentValues().apply {
                put("name", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun overview() = LibraryOverview(history(), searchHistory(), downloads(), get("download_tree"))

    fun tracks(key: String) = TrackJson.decodeAll(get(key, "[]"))

    fun saveTracks(key: String, tracks: List<Track>) = put(key, TrackJson.encodeAll(tracks))

    fun history(): List<Track> =
        helper.readableDatabase
            .rawQuery("SELECT track FROM history ORDER BY played_at DESC LIMIT 1000", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) runCatching {
                        TrackJson.decode(JSONObject(c.getString(0)))
                    }
                        .getOrNull()
                        ?.let { add(it) }
                }
            }

    fun record(track: Track, position: Long) {
        writeHistory(track, position)
        changes.update { it + 1 }
    }

    private fun writeHistory(track: Track, position: Long) {
        helper.writableDatabase.insertWithOnConflict(
            "history",
            null,
            ContentValues().apply {
                put("track_key", track.key)
                put("track", TrackJson.encode(track).toString())
                put("position", position)
                put("played_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun checkpoint(values: Map<String, String>, history: Track?, position: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) -> writeValue(key, value) }
            if (history != null) writeHistory(history, position)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.update { it + 1 }
    }

    fun clearHistory() {
        helper.writableDatabase.delete("history", null, null)
        changes.update { it + 1 }
    }

    fun searchHistory(): List<String> = runCatching {
        val a = JSONArray(get("search_history", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }
        .getOrDefault(emptyList())

    fun rememberSearch(query: String) {
        put(
            "search_history",
            JSONArray(
                    (listOf(query) + searchHistory().filterNot { it.equals(query, true) }).take(50)
                )
                .toString(),
        )
    }

    fun deleteSearch(query: String) =
        put(
            "search_history",
            JSONArray(searchHistory().filterNot { it == query }).toString(),
        )

    fun downloads(): List<DownloadEntry> =
        helper.readableDatabase
            .rawQuery(
                "SELECT id,track,status,progress,uri,export_uri,error FROM downloads ORDER BY rowid DESC",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) runCatching {
                        DownloadEntry(
                            c.getString(0),
                            TrackJson.decode(JSONObject(c.getString(1))),
                            c.getString(2),
                            c.getInt(3),
                            c.getString(4),
                            c.getString(5),
                            c.getString(6),
                        )
                    }
                        .getOrNull()
                        ?.let { add(it) }
                }
            }

    fun download(entry: DownloadEntry) {
        helper.writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("track", TrackJson.encode(entry.track).toString())
                put("status", entry.status)
                put("progress", entry.progress)
                put("uri", entry.uri)
                put("export_uri", entry.exportUri)
                put("error", entry.error)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.update { it + 1 }
    }

    fun commitDownload(entry: DownloadEntry) {
        require(entry.status == "complete")
        val previous =
            downloads().filter {
                it.id != entry.id && it.status == "complete" && it.track.key == entry.track.key
            }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            download(entry)
            previous.forEach { db.delete("downloads", "id=?", arrayOf(it.id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.update { it + 1 }
    }

    fun forgetDownload(id: String) {
        helper.writableDatabase.delete("downloads", "id=?", arrayOf(id))
        changes.update { it + 1 }
    }

    override fun close() = helper.close()
}
