package org.hogel.pocketssh.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed bigram counts that drive the dynamic shortcut suggestions.
 *
 * Each row counts the number of times [next] has been observed immediately
 * after [prev] under the foreground command [context]. The row-head pseudo
 * token [BOL] is recorded as the [prev] of the first token on a line so the
 * same table also supplies "what command to start a new line with" candidates.
 *
 * The table is intentionally tiny — no FK, no indexes beyond the primary key.
 * All hot reads are served by `(context, prev)` look-ups against the PK.
 */
class BigramStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    /** Increment the count for `(context, prev) -> next`, inserting if missing. */
    fun record(context: String, prev: String, next: String) {
        if (next.isEmpty()) return
        helper.writableDatabase.execSQL(
            "INSERT INTO bigram(context, prev, next, count, last_used) VALUES(?,?,?,1,?) " +
                "ON CONFLICT(context, prev, next) DO UPDATE SET " +
                "count = count + 1, last_used = excluded.last_used",
            arrayOf(context, prev, next, System.currentTimeMillis()),
        )
    }

    /**
     * Drop bigrams that have fallen out of use: within each `(context, prev)`
     * group keep the [keepTop] highest-count rows, and delete any of the rest
     * that have not been recorded in the last [maxAgeMillis]. Rows still in the
     * per-group top [keepTop] are never touched however stale, so a command you
     * lean on but haven't run lately keeps its suggestions. Ranking ties break
     * the same way [topNext] orders, so a kept row is always one that could
     * still surface in the suggestion bar.
     */
    fun prune(keepTop: Int = DEFAULT_KEEP_TOP, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        helper.writableDatabase.execSQL(
            "DELETE FROM bigram WHERE last_used < ? AND (" +
                "SELECT COUNT(*) FROM bigram AS b2 WHERE b2.context = bigram.context " +
                "AND b2.prev = bigram.prev AND (b2.count > bigram.count " +
                "OR (b2.count = bigram.count AND b2.next < bigram.next))) >= ?",
            arrayOf(cutoff, keepTop),
        )
    }

    /**
     * Return up to [limit] candidates for `(context, prev)` ordered by descending
     * count. Entries below [minCount] are filtered out so a single accidental
     * keystroke never sticks around in the suggestion bar.
     */
    fun topNext(
        context: String,
        prev: String,
        limit: Int,
        minCount: Int = DEFAULT_MIN_COUNT,
    ): List<String> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT next FROM bigram WHERE context=? AND prev=? AND count>=? " +
                "ORDER BY count DESC, next ASC LIMIT ?",
            arrayOf(context, prev, minCount.toString(), limit.toString()),
        )
        val results = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) results.add(c.getString(0))
        }
        return results
    }

    /** Wipe every row. Used by the "clear learned suggestions" settings action. */
    fun clear() {
        helper.writableDatabase.delete("bigram", null, null)
    }

    /** Delete a single learned bigram so it stops appearing in suggestions. */
    fun delete(context: String, prev: String, next: String) {
        helper.writableDatabase.delete(
            "bigram",
            "context=? AND prev=? AND next=?",
            arrayOf(context, prev, next),
        )
    }

    /** Every row in the table, in primary-key order. Used by settings export. */
    fun snapshot(): List<Bigram> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, prev, next, count FROM bigram ORDER BY context, prev, next",
            null,
        )
        val rows = mutableListOf<Bigram>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += Bigram(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
            }
        }
        return rows
    }

    /** Rows scoped to a single [context], ordered by descending count. */
    fun snapshotByContext(context: String): List<Bigram> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, prev, next, count FROM bigram WHERE context=? " +
                "ORDER BY count DESC, prev ASC, next ASC",
            arrayOf(context),
        )
        val rows = mutableListOf<Bigram>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += Bigram(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
            }
        }
        return rows
    }

    /** Distinct contexts and how many bigrams are stored under each, alphabetical. */
    fun contextSummaries(): List<ContextSummary> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, COUNT(*) FROM bigram GROUP BY context ORDER BY context ASC",
            null,
        )
        val rows = mutableListOf<ContextSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += ContextSummary(c.getString(0), c.getInt(1))
            }
        }
        return rows
    }

    /**
     * Replace every row with [rows] in a single transaction. Used by settings
     * import so a partial failure can't leave a half-written table.
     */
    fun replaceAll(rows: List<Bigram>) {
        val db = helper.writableDatabase
        // Imported rows carry no usage time; seed them with the import time so a
        // freshly restored backup gets a full window before prune() can touch it.
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("bigram", null, null)
            val stmt = db.compileStatement(
                "INSERT INTO bigram(context, prev, next, count, last_used) VALUES(?,?,?,?,?)",
            )
            for (r in rows) {
                stmt.bindString(1, r.context)
                stmt.bindString(2, r.prev)
                stmt.bindString(3, r.next)
                stmt.bindLong(4, r.count.toLong())
                stmt.bindLong(5, now)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class Bigram(val context: String, val prev: String, val next: String, val count: Int)

    data class ContextSummary(val context: String, val count: Int)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE bigram (
                    context TEXT NOT NULL,
                    prev TEXT NOT NULL,
                    next TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0,
                    last_used INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(context, prev, next)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // last_used backs the age-based prune(). Backfill existing rows
                // to the upgrade time so pre-migration history gets a fresh
                // window instead of being pruned away on the first run.
                db.execSQL("ALTER TABLE bigram ADD COLUMN last_used INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE bigram SET last_used = ?",
                    arrayOf(System.currentTimeMillis()),
                )
            }
        }
    }

    companion object {
        const val BOL = "<BOL>"
        const val ENTER = "<ENTER>"
        const val UNKNOWN_CONTEXT = "(unknown)"
        private const val DB_NAME = "pocket_ssh.db"
        private const val DB_VERSION = 2
        private const val DEFAULT_MIN_COUNT = 2

        /** Per-`(context, prev)` group rows kept by [prune] regardless of age. */
        private const val DEFAULT_KEEP_TOP = 10

        /** A bigram outside the per-group top is pruned after going unused this long. */
        private const val DEFAULT_MAX_AGE_MILLIS = 10L * 24 * 60 * 60 * 1000
    }
}
