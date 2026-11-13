package tv.cypher.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import tv.cypher.core.NowNext
import tv.cypher.core.Programme
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Programme guide in a plain SQLite table, imported by streaming the JSON so it is never held in memory. */
class GuideDb(ctx: Context) : SQLiteOpenHelper(ctx, "guide.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE epg (channel_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL)")
        db.execSQL("CREATE INDEX epg_channel_start ON epg (channel_id, start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Replaces the whole guide with the contents of a gzipped `epg.json.gz`. Returns the programme count. */
    fun importGzip(input: InputStream): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM epg")
            val stmt = db.compileStatement("INSERT INTO epg (channel_id, start, stop, title) VALUES (?, ?, ?, ?)")
            JsonReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    val id = r.nextName()
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val start = r.nextLong()
                        val stop = r.nextLong()
                        val title = r.nextString()
                        r.endArray()
                        stmt.clearBindings()
                        stmt.bindString(1, id); stmt.bindLong(2, start); stmt.bindLong(3, stop); stmt.bindString(4, title)
                        stmt.executeInsert()
                        count++
                    }
                    r.endArray()
                }
                r.endObject()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun isEmpty(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM epg LIMIT 1", null).use { !it.moveToFirst() }

    fun nowNext(channelId: String, nowSec: Long): Pair<Programme?, Programme?> {
        val rows = ArrayList<Programme>(3)
        readableDatabase.rawQuery(
            "SELECT start, stop, title FROM epg WHERE channel_id = ? AND stop > ? ORDER BY start LIMIT 3",
            arrayOf(channelId, nowSec.toString()),
        ).use { c -> while (c.moveToNext()) rows.add(Programme(c.getLong(0), c.getLong(1), c.getString(2))) }
        return NowNext.pick(rows, nowSec)
    }

    /** Programmes of one channel overlapping [fromSec, toSec). */
    fun schedule(channelId: String, fromSec: Long, toSec: Long): List<Programme> {
        val rows = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT start, stop, title FROM epg WHERE channel_id = ? AND stop > ? AND start < ? ORDER BY start",
            arrayOf(channelId, fromSec.toString(), toSec.toString()),
        ).use { c -> while (c.moveToNext()) rows.add(Programme(c.getLong(0), c.getLong(1), c.getString(2))) }
        return rows
    }
}
