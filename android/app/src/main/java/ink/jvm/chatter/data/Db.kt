package ink.jvm.chatter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A message as stored on the phone. seq is null until the server acks it. */
data class LocalMessage(
    val id: String,
    val seq: Long?,
    val from: Long,
    val kind: String,
    val text: String?,
    val media: MediaInfo?,
    val ts: Long,
    val status: Int,
    val reply: ReplyInfo? = null,
    val editedAt: Long? = null,
    val expiresAt: Long? = null,
    val to: String? = null,
    val once: Boolean = false,
) {
    val fromBot: Boolean get() = from == BOT_ID
    val toBot: Boolean get() = to == "bot"
    val isControl: Boolean get() = kind == "del" || kind == "clear" || kind == "react" || kind == "edit" || (kind == "recall" && text != null)
    /** Rendered as a centred system line rather than a bubble. */
    val isSystem: Boolean get() = kind == "ttl" || kind == "pat" || kind == "recall"
    /** Slash command typed on the assistant page (never shown as a question stub in the main chat). */
    val isBotCommand: Boolean get() = toBot && kind == "text" && (text?.startsWith("/") == true)

    companion object {
        const val PENDING = 0
        const val SENT = 1
        const val FAILED = 2
        /** Reserved server id of the in-chat assistant. */
        const val BOT_ID = 0L
    }
}

/** One emoji from one user on one message. */
data class Reaction(val from: Long, val emoji: String)

/** A message bookmarked on this phone; keeps its own copy so it survives deletion. */
data class Favorite(val id: String, val from: Long, val kind: String, val text: String?, val media: MediaInfo?, val ts: Long, val addedAt: Long)

/** Text queued for a later send (phone-local alarm). */
data class Scheduled(val id: String, val text: String, val at: Long, val toBot: Boolean)

/** One finished call, for the monthly statistics. */
data class CallStat(val ts: Long, val video: Boolean, val seconds: Long, val bytes: Long)

fun ChatMessage.toLocal() = LocalMessage(id, seq, from, kind, text, media, ts, LocalMessage.SENT, reply, editedAt, expiresAt, to, once == true)

/** Plain SQLite: messages + reactions, no ORM, nothing to code-generate. */
class Db(context: Context) : SQLiteOpenHelper(context, "chatter.db", null, 8) {

    override fun onCreate(db: SQLiteDatabase) {
        createV8(db)
        db.execSQL(
            """CREATE TABLE messages(
                 id TEXT PRIMARY KEY,
                 seq INTEGER,
                 from_user INTEGER NOT NULL,
                 kind TEXT NOT NULL,
                 text TEXT,
                 media TEXT,
                 ts INTEGER NOT NULL,
                 status INTEGER NOT NULL,
                 reply_to TEXT,
                 reply_from INTEGER,
                 reply_text TEXT,
                 edited_at INTEGER,
                 expires_at INTEGER,
                 dest TEXT,
                 once INTEGER)"""
        )
        db.execSQL("CREATE INDEX idx_messages_ts ON messages(ts)")
        db.execSQL("CREATE INDEX idx_messages_seq ON messages(seq)")
        createReactions(db)
        createV7(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_from INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_text TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_seq ON messages(seq)")
        }
        if (oldVersion < 4) createReactions(db)
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edited_at INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE messages ADD COLUMN dest TEXT")
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE messages ADD COLUMN once INTEGER")
            createV7(db)
        }
        if (oldVersion < 8) createV8(db)
    }

    private fun createReactions(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reactions(target TEXT NOT NULL, from_user INTEGER NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY(target, from_user, emoji))")
    }

    /** 1.3: favourites, scheduled sends, voice transcripts, call statistics, viewed view-once ids. */
    /** 1.7: voice notes the user has listened to (the unread red dot). */
    private fun createV8(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS voice_played(id TEXT PRIMARY KEY)")
    }

    private fun createV7(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites(id TEXT PRIMARY KEY, from_user INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT, media TEXT, ts INTEGER NOT NULL, added_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled(id TEXT PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, to_bot INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcripts(id TEXT PRIMARY KEY, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS call_stats(ts INTEGER NOT NULL, video INTEGER NOT NULL, seconds INTEGER NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS once_seen(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    @Synchronized
    fun upsert(m: LocalMessage) {
        val cv = ContentValues().apply {
            put("id", m.id)
            if (m.seq == null) putNull("seq") else put("seq", m.seq)
            put("from_user", m.from)
            put("kind", m.kind)
            if (m.text == null) putNull("text") else put("text", m.text)
            if (m.media == null) putNull("media") else put("media", ProtoJson.encodeToString(MediaInfo.serializer(), m.media))
            put("ts", m.ts)
            put("status", m.status)
            val r = m.reply
            if (r == null) {
                putNull("reply_to"); putNull("reply_from"); putNull("reply_text")
            } else {
                put("reply_to", r.id); put("reply_from", r.from); put("reply_text", r.text)
            }
            if (m.editedAt == null) putNull("edited_at") else put("edited_at", m.editedAt)
            if (m.expiresAt == null) putNull("expires_at") else put("expires_at", m.expiresAt)
            if (m.to == null) putNull("dest") else put("dest", m.to)
            put("once", if (m.once) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun ack(id: String, seq: Long, ts: Long) {
        writableDatabase.execSQL(
            "UPDATE messages SET seq = ?, ts = ?, status = ? WHERE id = ?",
            arrayOf<Any>(seq, ts, LocalMessage.SENT, id)
        )
    }

    @Synchronized
    fun markFailed(id: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id = ? AND status = ?",
            arrayOf<Any>(LocalMessage.FAILED, id, LocalMessage.PENDING)
        )
    }

    @Synchronized
    fun markPending(id: String) {
        writableDatabase.execSQL("UPDATE messages SET status = ? WHERE id = ?", arrayOf<Any>(LocalMessage.PENDING, id))
    }

    /** Message editing: new text plus the edit timestamp (null = silent update, e.g. a live location). */
    @Synchronized
    fun updateText(id: String, text: String, editedAt: Long?) {
        if (editedAt == null) writableDatabase.execSQL("UPDATE messages SET text = ? WHERE id = ?", arrayOf<Any>(text, id))
        else writableDatabase.execSQL("UPDATE messages SET text = ?, edited_at = ? WHERE id = ?", arrayOf<Any>(text, editedAt, id))
    }

    /** Disappearing messages: drops rows past their expiry and returns their ids. */
    @Synchronized
    fun deleteExpired(now: Long): List<String> {
        val ids = readableDatabase.rawQuery("SELECT id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?", arrayOf(now.toString())).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
        if (ids.isNotEmpty()) {
            writableDatabase.delete("messages", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(now.toString()))
            writableDatabase.execSQL("DELETE FROM reactions WHERE target NOT IN (SELECT id FROM messages)")
        }
        return ids
    }

    /** Every visible message, oldest first (chat export). */
    @Synchronized
    fun all(): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE ORDER BY COALESCE(seq, 9223372036854775807), ts")

    @Synchronized
    fun delete(id: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
        writableDatabase.delete("reactions", "target = ?", arrayOf(id))
    }

    /** 1.7 撤回: the row stays as a placeholder (kind "recall", no text / media). */
    fun recall(id: String) {
        writableDatabase.execSQL("UPDATE messages SET kind = 'recall', text = NULL, media = NULL WHERE id = ? AND kind NOT IN ('del', 'clear', 'recall')", arrayOf(id))
        writableDatabase.delete("reactions", "target = ?", arrayOf(id))
    }

    fun markPlayed(id: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO voice_played(id) VALUES (?)", arrayOf(id)) }

    fun playedIds(): Set<String> = readableDatabase.rawQuery("SELECT id FROM voice_played", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** Applies a "clear" from the server: everything acknowledged up to [seq] goes; unsent messages stay. */
    @Synchronized
    fun clearUpTo(seq: Long) {
        writableDatabase.delete("messages", "seq IS NOT NULL AND seq <= ?", arrayOf(seq.toString()))
        writableDatabase.execSQL("DELETE FROM reactions WHERE target NOT IN (SELECT id FROM messages)")
    }

    // ---- reactions ----

    @Synchronized
    fun react(target: String, from: Long, emoji: String, on: Boolean) {
        if (on) {
            val cv = ContentValues().apply { put("target", target); put("from_user", from); put("emoji", emoji) }
            writableDatabase.insertWithOnConflict("reactions", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.delete("reactions", "target = ? AND from_user = ? AND emoji = ?", arrayOf(target, from.toString(), emoji))
        }
    }

    @Synchronized
    fun reactions(): Map<String, List<Reaction>> =
        readableDatabase.rawQuery("SELECT target, from_user, emoji FROM reactions", null).use { c ->
            val out = HashMap<String, MutableList<Reaction>>()
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() }.add(Reaction(c.getLong(1), c.getString(2)))
            out
        }

    @Synchronized
    fun maxSeq(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM messages", null).use {
        it.moveToFirst()
        it.getLong(0)
    }

    @Synchronized
    fun get(id: String): LocalMessage? = query("SELECT $COLS FROM messages WHERE id = ?", arrayOf(id)).firstOrNull()

    @Synchronized
    fun pending(): List<LocalMessage> = query("SELECT $COLS FROM messages WHERE status = ${LocalMessage.PENDING} ORDER BY ts")

    /** Newest [limit] visible messages in ascending order (control entries are never shown). */
    @Synchronized
    fun recent(limit: Int): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE ORDER BY COALESCE(seq, 9223372036854775807) DESC, ts DESC LIMIT $limit").asReversed()

    /** Visible messages with seq < [seq], newest [limit] of those, returned ascending. */
    @Synchronized
    fun before(seq: Long, limit: Int): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE AND seq IS NOT NULL AND seq < $seq ORDER BY seq DESC LIMIT $limit").asReversed()

    @Synchronized
    fun hasOlderThan(seq: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE $VISIBLE AND seq IS NOT NULL AND seq < ? LIMIT 1", arrayOf(seq.toString()))
            .use { it.moveToFirst() }

    /** Case-insensitive substring search over text, captions and file names; newest first. */
    @Synchronized
    fun search(q: String, limit: Int = 100): List<LocalMessage> = searchTyped(q, null, linksOnly = false, limit)

    /**
     * Search with an optional kind filter. Blank [q] returns the newest of those kinds
     * (used by the typed tabs). [linksOnly] keeps text that contains a URL.
     */
    @Synchronized
    fun searchTyped(q: String, kinds: List<String>?, linksOnly: Boolean, limit: Int = 200): List<LocalMessage> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        where.add(VISIBLE)
        if (!kinds.isNullOrEmpty()) where.add("kind IN (${kinds.joinToString(",") { "'${it.replace("'", "")}'" }})")
        if (linksOnly) where.add("(text LIKE '%http://%' OR text LIKE '%https://%')")
        val needle = q.trim()
        if (needle.isNotEmpty()) {
            val like = "%" + needle.replace("%", "\\%").replace("_", "\\_") + "%"
            where.add("(text LIKE ? ESCAPE '\\' OR media LIKE ? ESCAPE '\\')")
            args.add(like); args.add(like)
        }
        return query(
            "SELECT $COLS FROM messages WHERE ${where.joinToString(" AND ")} ORDER BY COALESCE(seq, 9223372036854775807) DESC, ts DESC LIMIT $limit",
            args.toTypedArray(),
        )
    }

    /** Visible messages on a local-day window [start, end). */
    @Synchronized
    fun onDay(start: Long, end: Long): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE AND ts >= $start AND ts < $end ORDER BY ts")

    /** Chat photos and videos, plus album uploads, newest first. Bot traffic stays out. */
    @Synchronized
    fun albumMessages(): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE AND kind IN ('image','video','album') AND (dest IS NULL OR dest != 'bot') AND from_user != ${LocalMessage.BOT_ID} AND media IS NOT NULL ORDER BY ts DESC")
            .filter { !it.once && it.media?.id?.isNotEmpty() == true }

    /** Everything said to or by the assistant, newest [limit], returned ascending. Slash commands and their replies are included. */
    @Synchronized
    fun botMessages(limit: Int): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE AND (from_user = ${LocalMessage.BOT_ID} OR dest = 'bot') ORDER BY COALESCE(seq, 9223372036854775807) DESC, ts DESC LIMIT $limit").asReversed()

    /** Only the assistant's answers newer than [seq] (what the badge on the robot icon counts). */
    @Synchronized
    fun botAnswersAfter(seq: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE $VISIBLE AND from_user = ${LocalMessage.BOT_ID} AND seq > ?", arrayOf(seq.toString()),
    ).use { it.moveToFirst(); it.getInt(0) }

    /** Ids of the questions the assistant has already quoted in an answer. */
    @Synchronized
    fun botRepliedIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT reply_to FROM messages WHERE from_user = ${LocalMessage.BOT_ID} AND reply_to IS NOT NULL", null,
    ).use { c ->
        val out = HashSet<String>()
        while (c.moveToNext()) out.add(c.getString(0))
        out
    }

    @Synchronized
    fun maxSeqFrom(userId: Long): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(seq), 0) FROM messages WHERE from_user = ? AND seq IS NOT NULL",
        arrayOf(userId.toString()),
    ).use {
        it.moveToFirst()
        it.getLong(0)
    }

    /** Peer messages newer than [seq]: what the unread divider and the jump badge count. */
    @Synchronized
    fun countFromAfter(userId: Long, seq: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE $VISIBLE AND from_user = ? AND seq > ?",
        arrayOf(userId.toString(), seq.toString()),
    ).use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("reactions", null, null)
        writableDatabase.delete("once_seen", null, null)
    }

    // ---- 1.3: media library, favourites, scheduled sends, transcripts, call stats, view-once ----

    /** Every image / video / file with an uploaded media id, newest first. */
    @Synchronized
    fun mediaMessages(): List<LocalMessage> =
        query("SELECT $COLS FROM messages WHERE $VISIBLE AND kind IN ('image','video','file','album') AND media IS NOT NULL ORDER BY ts DESC")
            .filter { it.media?.id?.isNotEmpty() == true }

    @Synchronized
    fun addFavorite(m: LocalMessage) {
        val cv = ContentValues().apply {
            put("id", m.id); put("from_user", m.from); put("kind", m.kind)
            if (m.text == null) putNull("text") else put("text", m.text)
            if (m.media == null) putNull("media") else put("media", ProtoJson.encodeToString(MediaInfo.serializer(), m.media))
            put("ts", m.ts); put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun removeFavorite(id: String) { writableDatabase.delete("favorites", "id = ?", arrayOf(id)) }

    @Synchronized
    fun isFavorite(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    @Synchronized
    fun favorites(): List<Favorite> = readableDatabase.rawQuery("SELECT id, from_user, kind, text, media, ts, added_at FROM favorites ORDER BY added_at DESC", null).use { c ->
        val out = ArrayList<Favorite>()
        while (c.moveToNext()) out.add(
            Favorite(
                c.getString(0), c.getLong(1), c.getString(2), if (c.isNull(3)) null else c.getString(3),
                if (c.isNull(4)) null else runCatching { ProtoJson.decodeFromString(MediaInfo.serializer(), c.getString(4)) }.getOrNull(),
                c.getLong(5), c.getLong(6),
            )
        )
        out
    }

    @Synchronized
    fun addScheduled(s: Scheduled) {
        val cv = ContentValues().apply { put("id", s.id); put("text", s.text); put("at", s.at); put("to_bot", if (s.toBot) 1 else 0) }
        writableDatabase.insertWithOnConflict("scheduled", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun removeScheduled(id: String) { writableDatabase.delete("scheduled", "id = ?", arrayOf(id)) }

    @Synchronized
    fun scheduled(): List<Scheduled> = readableDatabase.rawQuery("SELECT id, text, at, to_bot FROM scheduled ORDER BY at", null).use { c ->
        val out = ArrayList<Scheduled>()
        while (c.moveToNext()) out.add(Scheduled(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3) == 1))
        out
    }

    @Synchronized
    fun transcript(id: String): String? = readableDatabase.rawQuery("SELECT text FROM transcripts WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    fun setTranscript(id: String, text: String) {
        val cv = ContentValues().apply { put("id", id); put("text", text) }
        writableDatabase.insertWithOnConflict("transcripts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun addCallStat(s: CallStat) {
        val cv = ContentValues().apply { put("ts", s.ts); put("video", if (s.video) 1 else 0); put("seconds", s.seconds); put("bytes", s.bytes) }
        writableDatabase.insert("call_stats", null, cv)
    }

    @Synchronized
    fun callStatsSince(ts: Long): List<CallStat> = readableDatabase.rawQuery("SELECT ts, video, seconds, bytes FROM call_stats WHERE ts >= ? ORDER BY ts", arrayOf(ts.toString())).use { c ->
        val out = ArrayList<CallStat>()
        while (c.moveToNext()) out.add(CallStat(c.getLong(0), c.getInt(1) == 1, c.getLong(2), c.getLong(3)))
        out
    }

    @Synchronized
    fun markOnceSeen(id: String) {
        val cv = ContentValues().apply { put("id", id); put("at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("once_seen", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun onceSeen(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM once_seen WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun query(sql: String, args: Array<String>? = null): List<LocalMessage> = readableDatabase.rawQuery(sql, args).use { c ->
        val out = ArrayList<LocalMessage>(c.count)
        while (c.moveToNext()) {
            out.add(
                LocalMessage(
                    id = c.getString(0),
                    seq = if (c.isNull(1)) null else c.getLong(1),
                    from = c.getLong(2),
                    kind = c.getString(3),
                    text = if (c.isNull(4)) null else c.getString(4),
                    media = if (c.isNull(5)) null else runCatching {
                        ProtoJson.decodeFromString(MediaInfo.serializer(), c.getString(5))
                    }.getOrNull(),
                    ts = c.getLong(6),
                    status = c.getInt(7),
                    reply = if (c.isNull(8)) null else ReplyInfo(c.getString(8), if (c.isNull(9)) 0 else c.getLong(9), c.getString(10) ?: ""),
                    editedAt = if (c.isNull(11)) null else c.getLong(11),
                    expiresAt = if (c.isNull(12)) null else c.getLong(12),
                    to = if (c.isNull(13)) null else c.getString(13),
                    once = !c.isNull(14) && c.getInt(14) == 1,
                )
            )
        }
        out
    }

    private companion object {
        const val COLS = "id, seq, from_user, kind, text, media, ts, status, reply_to, reply_from, reply_text, edited_at, expires_at, dest, once"
        const val VISIBLE = "kind NOT IN ('del', 'clear', 'react', 'edit')"
    }
}
