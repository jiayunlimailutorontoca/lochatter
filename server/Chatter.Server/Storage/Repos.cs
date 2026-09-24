using Chatter.Server.Protocol;
using Microsoft.Data.Sqlite;

namespace Chatter.Server.Storage;

public sealed record UserRow(long Id, string Name, string PwHash, long CreatedAt, long? LastSeen);

public sealed class UserRepo(Db db)
{
    public const int MaxUsers = 2;
    /// <summary>Reserved id of the in-chat assistant. Not a human, not counted toward MaxUsers.</summary>
    public const long BotId = 0;
    public static bool IsBot(long id) => id == BotId;

    public UserRow? FindByName(string name)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, name, pw_hash, created_at, last_seen FROM users WHERE name = $n";
        cmd.Parameters.AddWithValue("$n", name);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new UserRow(r.GetInt64(0), r.GetString(1), r.GetString(2), r.GetInt64(3), r.IsDBNull(4) ? null : r.GetInt64(4)) : null;
    }

    public UserInfo? Get(long id)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, name FROM users WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new UserInfo(r.GetInt64(0), r.GetString(1)) : null;
    }

    /// <summary>The other user of the pair, or null while only one account exists.</summary>
    public UserInfo? PeerOf(long id)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, name FROM users WHERE id <> $id AND id <> 0 ORDER BY id LIMIT 1";
        cmd.Parameters.AddWithValue("$id", id);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new UserInfo(r.GetInt64(0), r.GetString(1)) : null;
    }

    public void EnsureBot()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR IGNORE INTO users(id, name, pw_hash, created_at) VALUES(0, '助手', '!', 0)";
        cmd.ExecuteNonQuery();
    }

    public bool SetBotName(string name)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE users SET name = $n WHERE id = 0";
        cmd.Parameters.AddWithValue("$n", name);
        return cmd.ExecuteNonQuery() == 1;
    }

    public long? LastSeen(long id)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT last_seen FROM users WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        var v = cmd.ExecuteScalar();
        return v is long l ? l : null;
    }

    public void SetLastSeen(long id, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE users SET last_seen = $ts WHERE id = $id";
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public List<UserRow> List()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, name, pw_hash, created_at, last_seen FROM users WHERE id <> 0 ORDER BY id";
        using var r = cmd.ExecuteReader();
        var list = new List<UserRow>();
        while (r.Read())
            list.Add(new UserRow(r.GetInt64(0), r.GetString(1), r.GetString(2), r.GetInt64(3), r.IsDBNull(4) ? null : r.GetInt64(4)));
        return list;
    }

    public int Count()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM users WHERE id <> 0";
        return (int)(long)cmd.ExecuteScalar()!;
    }

    public long Add(string name, string pwHash, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO users(name, pw_hash, created_at) VALUES($n, $h, $ts) RETURNING id";
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$h", pwHash);
        cmd.Parameters.AddWithValue("$ts", ts);
        return (long)cmd.ExecuteScalar()!;
    }

    public bool SetPassword(string name, string pwHash)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE users SET pw_hash = $h WHERE name = $n";
        cmd.Parameters.AddWithValue("$h", pwHash);
        cmd.Parameters.AddWithValue("$n", name);
        return cmd.ExecuteNonQuery() == 1;
    }

    public bool SetPasswordById(long id, string pwHash)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE users SET pw_hash = $h WHERE id = $id";
        cmd.Parameters.AddWithValue("$h", pwHash);
        cmd.Parameters.AddWithValue("$id", id);
        return cmd.ExecuteNonQuery() == 1;
    }

    public bool Rename(string oldName, string newName)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE users SET name = $new WHERE name = $old";
        cmd.Parameters.AddWithValue("$new", newName);
        cmd.Parameters.AddWithValue("$old", oldName);
        return cmd.ExecuteNonQuery() == 1;
    }

    /// <summary>Removes the user and everything that references them. Returns (messages, media rows) deleted.</summary>
    public (int Messages, int Media) Delete(long id)
    {
        using var c = db.Open();
        using var tx = c.BeginTransaction();
        int Run(string sql)
        {
            using var cmd = c.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = sql;
            cmd.Parameters.AddWithValue("$id", id);
            return cmd.ExecuteNonQuery();
        }
        var msgs = Run("DELETE FROM messages WHERE from_user = $id");
        var media = Run("DELETE FROM media WHERE owner = $id");
        Run("DELETE FROM tokens WHERE user_id = $id");
        Run("DELETE FROM read_marks WHERE user_id = $id");
        Run("DELETE FROM user_keys WHERE user_id = $id");
        Run("DELETE FROM users WHERE id = $id");
        tx.Commit();
        return (msgs, media);
    }
}

public sealed class TokenRepo(Db db)
{
    public void Insert(long userId, string tokenHash, string? device, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO tokens(user_id, token_hash, device, created_at, last_seen) VALUES($u, $h, $d, $ts, $ts)";
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$h", tokenHash);
        cmd.Parameters.AddWithValue("$d", (object?)device ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.ExecuteNonQuery();
    }

    public UserInfo? Lookup(string tokenHash)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT u.id, u.name FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = $h";
        cmd.Parameters.AddWithValue("$h", tokenHash);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new UserInfo(r.GetInt64(0), r.GetString(1)) : null;
    }

    public void Touch(string tokenHash, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE tokens SET last_seen = $ts WHERE token_hash = $h";
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$h", tokenHash);
        cmd.ExecuteNonQuery();
    }

    public sealed record TokenRow(long Id, string Hash, string? Device, long CreatedAt, long? LastSeen);

    public List<TokenRow> List(long userId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, token_hash, device, created_at, last_seen FROM tokens WHERE user_id = $u ORDER BY last_seen DESC";
        cmd.Parameters.AddWithValue("$u", userId);
        using var r = cmd.ExecuteReader();
        var list = new List<TokenRow>();
        while (r.Read()) list.Add(new TokenRow(r.GetInt64(0), r.GetString(1), r.IsDBNull(2) ? null : r.GetString(2), r.GetInt64(3), r.IsDBNull(4) ? null : r.GetInt64(4)));
        return list;
    }

    /// <summary>Deletes one of the user's tokens; returns its hash so caches and sockets can be dropped.</summary>
    public string? Revoke(long userId, long tokenId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM tokens WHERE id = $id AND user_id = $u RETURNING token_hash";
        cmd.Parameters.AddWithValue("$id", tokenId);
        cmd.Parameters.AddWithValue("$u", userId);
        return cmd.ExecuteScalar() as string;
    }

    /// <summary>Deletes every token of the user except [keepHash]; returns the removed hashes.</summary>
    public List<string> RevokeOthers(long userId, string keepHash)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM tokens WHERE user_id = $u AND token_hash <> $keep RETURNING token_hash";
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$keep", keepHash);
        using var r = cmd.ExecuteReader();
        var list = new List<string>();
        while (r.Read()) list.Add(r.GetString(0));
        return list;
    }

    public int RevokeAll(long userId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM tokens WHERE user_id = $u";
        cmd.Parameters.AddWithValue("$u", userId);
        return cmd.ExecuteNonQuery();
    }

    /// <summary>Deletes every token whose device label is exactly [device]; returns the removed hashes.</summary>
    public List<string> RevokeDevice(long userId, string device)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM tokens WHERE user_id = $u AND device = $d RETURNING token_hash";
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$d", device);
        using var r = cmd.ExecuteReader();
        var list = new List<string>();
        while (r.Read()) list.Add(r.GetString(0));
        return list;
    }
}

public sealed class MessageRepo(Db db)
{
    private const string SelectCols = """
        SELECT m.seq, m.client_id, m.from_user, m.kind, m.text, m.created_at,
               md.id, md.mime, md.size, md.width, md.height, md.duration_ms, md.thumb_id, md.name,
               m.reply_to, m.reply_from, m.reply_text, m.edited_at, m.expires_at, m.dest, m.once
        FROM messages m LEFT JOIN media md ON md.id = m.media_id
        """;

    public long LastSeq()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT COALESCE(MAX(seq), 0) FROM messages";
        return (long)cmd.ExecuteScalar()!;
    }

    public long ReadUpto(long userId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT upto_seq FROM read_marks WHERE user_id = $u";
        cmd.Parameters.AddWithValue("$u", userId);
        return cmd.ExecuteScalar() is long l ? l : 0;
    }

    /// <summary>Moves the read mark forward only; returns the effective value.</summary>
    public long SetReadUpto(long userId, long upto, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO read_marks(user_id, upto_seq, updated_at) VALUES($u, $s, $ts)
            ON CONFLICT(user_id) DO UPDATE SET upto_seq = MAX(upto_seq, excluded.upto_seq), updated_at = excluded.updated_at
            RETURNING upto_seq
            """;
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$s", upto);
        cmd.Parameters.AddWithValue("$ts", ts);
        return (long)cmd.ExecuteScalar()!;
    }

    public ChatMessage? Get(string clientId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = SelectCols + " WHERE m.client_id = $cid";
        cmd.Parameters.AddWithValue("$cid", clientId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? Map(r) : null;
    }

    /// <summary>Short human summary of a message, used as the stored quote text. Encrypted texts are kept whole so the client can decrypt them.</summary>
    public static string Preview(ChatMessage m) => m.Kind switch
    {
        "text" when IsE2e(m.Text) => m.Text,
        "image" when IsE2e(m.Text) => "[图片]",
        "image" => string.IsNullOrWhiteSpace(m.Text) ? "[图片]" : "[图片] " + Clip(m.Text, 100),
        "album" when IsE2e(m.Text) => "[相册]",
        "album" => string.IsNullOrWhiteSpace(m.Text) ? "[相册]" : "[相册] " + Clip(m.Text, 100),
        "audio" => "[语音]",
        "video" => "[视频]",
        "react" => "[表情回应]",
        "file" => "[文件] " + (m.Media?.Name ?? ""),
        "call" => "[通话]",
        "sticker" => "[表情]",
        "pat" => "[拍一拍]",
        "recall" => "[已撤回]",
        "card" => Clip(FirstLine(m.Text ?? ""), 120),
        "location" => "[位置] " + LocationAddress(m.Text),
        _ => Clip(m.Text ?? "", 120),
    };

    /// <summary>Either end-to-end text generation: "e2e:" (v1, static key) or "e2e2:" (v2, epoch keys). The server never looks inside.</summary>
    public static bool IsE2e(string? t) => t is not null && (t.StartsWith("e2e:", StringComparison.Ordinal) || t.StartsWith("e2e2:", StringComparison.Ordinal));

    private static string Clip(string s, int max) => s.Length <= max ? s : s[..max] + "…";

    /// <summary>Address part of a plaintext "lat,lng|accuracy|address|live" location; the generic label for encrypted or address-less ones.</summary>
    private static string LocationAddress(string? text)
    {
        if (text is null || IsE2e(text)) return "位置";
        var parts = text.Split('|');
        var address = parts.Length > 2 ? parts[2].Trim() : "";
        return address.Length == 0 ? "位置" : Clip(address, 60);
    }

    private static string FirstLine(string s)
    {
        var nl = s.IndexOf('\n');
        return (nl < 0 ? s : s[..nl]).TrimEnd('\r').Trim();
    }

    /// <summary>
    /// Keeps at most one edit record per message and author: drops older "edit" rows by [from] whose text starts with "&lt;target&gt;|".
    /// Called right before a new edit row is inserted. Gaps in seq are expected.
    /// </summary>
    public int SquashEdits(long from, string target)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = @"DELETE FROM messages WHERE kind = 'edit' AND from_user = $from AND text LIKE $prefix ESCAPE '\'";
        cmd.Parameters.AddWithValue("$from", from);
        cmd.Parameters.AddWithValue("$prefix", target.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "|%");
        return cmd.ExecuteNonQuery();
    }

    /// <summary>Inserts unless the client id was seen before. Created=false means a duplicate (resend after reconnect).</summary>
    public (ChatMessage Msg, bool Created) Insert(long from, string clientId, string kind, string? text, string? mediaId, long ts, string? replyTo, long? expiresAt = null, string? dest = null, bool? once = null)
    {
        using var c = db.Open();
        using var tx = c.BeginTransaction();
        bool created = false;
        using (var probe = c.CreateCommand())
        {
            // Check first: INSERT OR IGNORE on an AUTOINCREMENT table burns a seq even when ignored.
            probe.Transaction = tx;
            probe.CommandText = "SELECT 1 FROM messages WHERE client_id = $cid";
            probe.Parameters.AddWithValue("$cid", clientId);
            if (probe.ExecuteScalar() is null) created = true;
        }
        if (created)
        {
            long? replyFrom = null;
            string? replyText = null;
            if (replyTo is not null)
            {
                using var q = c.CreateCommand();
                q.Transaction = tx;
                q.CommandText = SelectCols + " WHERE m.client_id = $cid";
                q.Parameters.AddWithValue("$cid", replyTo);
                using var qr = q.ExecuteReader();
                if (qr.Read())
                {
                    var quoted = Map(qr);
                    replyFrom = quoted.From;
                    replyText = Preview(quoted);
                }
                else
                {
                    replyTo = null; // quoted message unknown (deleted or never existed): store a plain message
                }
            }
            using var cmd = c.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO messages(client_id, from_user, kind, text, media_id, created_at, reply_to, reply_from, reply_text, expires_at, dest, once)
                VALUES($cid, $from, $kind, $text, $media, $ts, $rto, $rfrom, $rtext, $exp, $dest, $once)
                """;
            cmd.Parameters.AddWithValue("$cid", clientId);
            cmd.Parameters.AddWithValue("$from", from);
            cmd.Parameters.AddWithValue("$kind", kind);
            cmd.Parameters.AddWithValue("$text", (object?)text ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$media", (object?)mediaId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$ts", ts);
            cmd.Parameters.AddWithValue("$rto", (object?)replyTo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$rfrom", (object?)replyFrom ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$rtext", (object?)replyText ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$exp", (object?)expiresAt ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$dest", (object?)dest ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$once", once is true ? (object)1 : DBNull.Value);
            created = cmd.ExecuteNonQuery() == 1;
        }
        ChatMessage msg;
        using (var cmd = c.CreateCommand())
        {
            cmd.Transaction = tx;
            cmd.CommandText = SelectCols + " WHERE m.client_id = $cid";
            cmd.Parameters.AddWithValue("$cid", clientId);
            using var r = cmd.ExecuteReader();
            if (!r.Read()) throw new InvalidOperationException("message vanished after insert");
            msg = Map(r);
        }
        tx.Commit();
        return (msg, created);
    }

    /// <summary>Replaces the text of the sender's own text or location message (a live share updates its bubble). False when it does not exist or is not editable.</summary>
    public bool Edit(string clientId, long from, string text, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE messages SET text = $t, edited_at = $ts WHERE client_id = $cid AND from_user = $from AND kind IN ('text', 'location')";
        cmd.Parameters.AddWithValue("$t", text);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$cid", clientId);
        cmd.Parameters.AddWithValue("$from", from);
        return cmd.ExecuteNonQuery() == 1;
    }

    /// <summary>Messages whose disappearing-timer has run out (oldest first).</summary>
    public List<(string Id, long From)> Expired(long now, int limit)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT client_id, from_user FROM messages WHERE expires_at IS NOT NULL AND expires_at <= $now AND kind IN ('text','image','album','audio','video','file','call','sticker','pat','card','location') ORDER BY expires_at LIMIT $limit";
        cmd.Parameters.AddWithValue("$now", now);
        cmd.Parameters.AddWithValue("$limit", limit);
        using var r = cmd.ExecuteReader();
        var list = new List<(string, long)>();
        while (r.Read()) list.Add((r.GetString(0), r.GetInt64(1)));
        return list;
    }

    /// <summary>Removes one message; returns its media id (if any) so the caller can drop orphaned files.</summary>
    /// <summary>1.7 撤回: the row stays (kind "recall", no text / media) so late syncs see the placeholder. Returns the media id to drop.</summary>
    public string? Recall(string clientId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE messages SET kind = 'recall', text = NULL, media_id = NULL WHERE client_id = $cid AND kind NOT IN ('del', 'clear', 'recall') RETURNING media_id";
        cmd.Parameters.AddWithValue("$cid", clientId);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return r.IsDBNull(0) ? null : r.GetString(0);
    }

    public (bool Deleted, string? MediaId) Delete(string clientId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM messages WHERE client_id = $cid AND kind NOT IN ('del', 'clear') RETURNING media_id";
        cmd.Parameters.AddWithValue("$cid", clientId);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return (false, null);
        return (true, r.IsDBNull(0) ? null : r.GetString(0));
    }

    /// <summary>Wipes every message. Returns the media ids the wiped rows referenced.</summary>
    public List<string> DeleteAll()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM messages RETURNING media_id";
        using var r = cmd.ExecuteReader();
        var ids = new List<string>();
        while (r.Read()) if (!r.IsDBNull(0)) ids.Add(r.GetString(0));
        return ids;
    }

    /// <summary>The newest [limit] messages with seq &lt; before, returned in ascending order.</summary>
    public List<ChatMessage> Before(long before, int limit)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = SelectCols + " WHERE m.seq < $before ORDER BY m.seq DESC LIMIT $limit";
        cmd.Parameters.AddWithValue("$before", before);
        cmd.Parameters.AddWithValue("$limit", limit);
        using var r = cmd.ExecuteReader();
        var list = new List<ChatMessage>();
        while (r.Read()) list.Add(Map(r));
        list.Reverse();
        return list;
    }

    public List<ChatMessage> After(long since, int limit)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = SelectCols + " WHERE m.seq > $since ORDER BY m.seq LIMIT $limit";
        cmd.Parameters.AddWithValue("$since", since);
        cmd.Parameters.AddWithValue("$limit", limit);
        using var r = cmd.ExecuteReader();
        var list = new List<ChatMessage>();
        while (r.Read()) list.Add(Map(r));
        return list;
    }

    private static ChatMessage Map(SqliteDataReader r)
    {
        MediaInfo? media = r.IsDBNull(6)
            ? null
            : new MediaInfo(
                r.GetString(6), r.GetString(7), r.GetInt64(8),
                r.IsDBNull(9) ? null : r.GetInt32(9),
                r.IsDBNull(10) ? null : r.GetInt32(10),
                r.IsDBNull(11) ? null : r.GetInt32(11),
                r.IsDBNull(12) ? null : r.GetString(12),
                r.IsDBNull(13) ? null : r.GetString(13));
        ReplyInfo? reply = r.IsDBNull(14)
            ? null
            : new ReplyInfo(r.GetString(14), r.IsDBNull(15) ? 0 : r.GetInt64(15), r.IsDBNull(16) ? "" : r.GetString(16));
        return new ChatMessage(
            r.GetInt64(0), r.GetString(1), r.GetInt64(2), r.GetString(3),
            r.IsDBNull(4) ? null : r.GetString(4), media, r.GetInt64(5), reply,
            r.IsDBNull(17) ? null : r.GetInt64(17), r.IsDBNull(18) ? null : r.GetInt64(18),
            r.FieldCount > 19 && !r.IsDBNull(19) ? r.GetString(19) : null,
            r.FieldCount > 20 && !r.IsDBNull(20) && r.GetInt64(20) != 0 ? true : null);
    }
}

/// <summary>Two-person shared key/value store (assistant quick commands, anniversaries, sticker favourites). Values are opaque.</summary>
public sealed class SharedRepo(Db db)
{
    public List<SharedItem> All()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT key, value, updated_at FROM shared ORDER BY key";
        using var r = cmd.ExecuteReader();
        var list = new List<SharedItem>();
        while (r.Read()) list.Add(new SharedItem(r.GetString(0), r.GetString(1), r.GetInt64(2)));
        return list;
    }

    public SharedItem? Get(string key)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT key, value, updated_at FROM shared WHERE key = $k";
        cmd.Parameters.AddWithValue("$k", key);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new SharedItem(r.GetString(0), r.GetString(1), r.GetInt64(2)) : null;
    }

    /// <summary>Upsert; returns the stored row.</summary>
    public SharedItem Set(string key, string value, long ts, long by)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO shared(key, value, updated_at, updated_by) VALUES($k, $v, $ts, $by)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, updated_by = excluded.updated_by
            RETURNING key, value, updated_at
            """;
        cmd.Parameters.AddWithValue("$k", key);
        cmd.Parameters.AddWithValue("$v", value);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$by", by);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) throw new InvalidOperationException("shared row vanished after upsert");
        return new SharedItem(r.GetString(0), r.GetString(1), r.GetInt64(2));
    }
}

public sealed class KeyRepo(Db db)
{
    public KeyInfo? Get(long userId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT user_id, pub_key, updated_at FROM user_keys WHERE user_id = $u";
        cmd.Parameters.AddWithValue("$u", userId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new KeyInfo(r.GetInt64(0), r.GetString(1), r.GetInt64(2)) : null;
    }

    public void Set(long userId, string pubKey, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO user_keys(user_id, pub_key, updated_at) VALUES($u, $k, $ts) ON CONFLICT(user_id) DO UPDATE SET pub_key = excluded.pub_key, updated_at = excluded.updated_at";
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$k", pubKey);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.ExecuteNonQuery();
    }
}

public sealed record PushRow(long UserId, string Provider, string Secret, int IntervalSec, long UpdatedAt, string Style);

public sealed class PushRepo(Db db)
{
    public PushRow? Get(long userId)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT user_id, provider, secret, interval_sec, updated_at, style FROM push_pref WHERE user_id = $u";
        cmd.Parameters.AddWithValue("$u", userId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? new PushRow(r.GetInt64(0), r.GetString(1), r.GetString(2), r.GetInt32(3), r.GetInt64(4), r.IsDBNull(5) ? "text" : r.GetString(5)) : null;
    }

    public void Set(long userId, string provider, string secret, int intervalSec, string style, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO push_pref(user_id, provider, secret, interval_sec, updated_at, style) VALUES($u, $p, $s, $i, $ts, $style)
            ON CONFLICT(user_id) DO UPDATE SET provider = excluded.provider, secret = excluded.secret, interval_sec = excluded.interval_sec, updated_at = excluded.updated_at, style = excluded.style
            """;
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$p", provider);
        cmd.Parameters.AddWithValue("$s", secret);
        cmd.Parameters.AddWithValue("$i", intervalSec);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$style", style);
        cmd.ExecuteNonQuery();
    }

    public void PutKey(int s, int r, string keyB64, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO push_key(s, r, key, updated_at) VALUES($s, $r, $k, $ts)
            ON CONFLICT(s, r) DO UPDATE SET key = excluded.key, updated_at = excluded.updated_at
            """;
        cmd.Parameters.AddWithValue("$s", s);
        cmd.Parameters.AddWithValue("$r", r);
        cmd.Parameters.AddWithValue("$k", keyB64);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.ExecuteNonQuery();
    }

    public byte[]? Key(int s, int r)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT key FROM push_key WHERE s = $s AND r = $r";
        cmd.Parameters.AddWithValue("$s", s);
        cmd.Parameters.AddWithValue("$r", r);
        var v = cmd.ExecuteScalar() as string;
        if (string.IsNullOrEmpty(v)) return null;
        try
        {
            var raw = Convert.FromBase64String(v);
            return raw.Length == 32 ? raw : null;
        }
        catch (FormatException)
        {
            return null;
        }
    }

    public List<PushRow> All()
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            SELECT p.user_id, u.name, p.provider, p.secret, p.interval_sec, p.updated_at, p.style
            FROM push_pref p JOIN users u ON u.id = p.user_id ORDER BY p.user_id
            """;
        using var r = cmd.ExecuteReader();
        var list = new List<PushRow>();
        while (r.Read())
            list.Add(new PushRow(r.GetInt64(0), r.GetString(2), r.GetString(3), r.GetInt32(4), r.GetInt64(5), r.IsDBNull(6) ? "text" : r.GetString(6)));
        return list;
    }
}

public sealed class SettingsRepo(Db db)
{
    public string? Get(string key)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT value FROM settings WHERE key = $k";
        cmd.Parameters.AddWithValue("$k", key);
        return cmd.ExecuteScalar() as string;
    }

    public void Set(string key, string value)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO settings(key, value) VALUES($k, $v) ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        cmd.Parameters.AddWithValue("$k", key);
        cmd.Parameters.AddWithValue("$v", value);
        cmd.ExecuteNonQuery();
    }
}

public sealed class MediaRepo(Db db)
{
    /// <summary>Content-addressed: a re-upload of the same bytes keeps the first row but refreshes created_at, so the orphan grace period restarts.</summary>
    public void Insert(MediaInfo m, long owner, long ts)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO media(id, owner, mime, size, width, height, duration_ms, thumb_id, created_at, name)
            VALUES($id, $owner, $mime, $size, $w, $h, $d, $thumb, $ts, $name)
            ON CONFLICT(id) DO UPDATE SET created_at = excluded.created_at
            """;
        cmd.Parameters.AddWithValue("$id", m.Id);
        cmd.Parameters.AddWithValue("$owner", owner);
        cmd.Parameters.AddWithValue("$mime", m.Mime);
        cmd.Parameters.AddWithValue("$size", m.Size);
        cmd.Parameters.AddWithValue("$w", (object?)m.Width ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$h", (object?)m.Height ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$d", (object?)m.DurationMs ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$thumb", (object?)m.ThumbId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$ts", ts);
        cmd.Parameters.AddWithValue("$name", (object?)m.Name ?? DBNull.Value);
        cmd.ExecuteNonQuery();
    }

    public MediaInfo? Get(string id)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id, mime, size, width, height, duration_ms, thumb_id, name FROM media WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return new MediaInfo(
            r.GetString(0), r.GetString(1), r.GetInt64(2),
            r.IsDBNull(3) ? null : r.GetInt32(3),
            r.IsDBNull(4) ? null : r.GetInt32(4),
            r.IsDBNull(5) ? null : r.GetInt32(5),
            r.IsDBNull(6) ? null : r.GetString(6),
            r.IsDBNull(7) ? null : r.GetString(7));
    }

    /// <summary>
    /// Uploads older than [olderThanTs] that no message references, neither directly nor as the thumbnail of a media
    /// some message references (oldest first). Removal goes through DeleteUnreferenced, which re-checks per row.
    /// </summary>
    public List<string> Orphans(long olderThanTs, int limit)
    {
        using var c = db.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            SELECT id FROM media
            WHERE created_at < $ts
              AND id NOT IN (SELECT media_id FROM messages WHERE media_id IS NOT NULL)
              AND id NOT IN (SELECT md.thumb_id FROM media md JOIN messages m ON m.media_id = md.id WHERE md.thumb_id IS NOT NULL)
            ORDER BY created_at LIMIT $limit
            """;
        cmd.Parameters.AddWithValue("$ts", olderThanTs);
        cmd.Parameters.AddWithValue("$limit", limit);
        using var r = cmd.ExecuteReader();
        var list = new List<string>();
        while (r.Read()) list.Add(r.GetString(0));
        return list;
    }

    /// <summary>
    /// Deletes the given media rows (and their thumbnails) if no remaining message references them.
    /// Returns the ids whose files should be removed from disk.
    /// </summary>
    public List<string> DeleteUnreferenced(IEnumerable<string> ids)
    {
        using var c = db.Open();
        var removed = new List<string>();
        var queue = new Queue<string>(ids.Distinct());
        while (queue.Count > 0)
        {
            var id = queue.Dequeue();
            using var cmd = c.CreateCommand();
            cmd.CommandText = """
                DELETE FROM media WHERE id = $id
                  AND NOT EXISTS (SELECT 1 FROM messages WHERE media_id = $id)
                  AND NOT EXISTS (SELECT 1 FROM media md JOIN messages m ON m.media_id = md.id WHERE md.thumb_id = $id)
                RETURNING thumb_id
                """;
            cmd.Parameters.AddWithValue("$id", id);
            using var r = cmd.ExecuteReader();
            if (r.Read())
            {
                removed.Add(id);
                if (!r.IsDBNull(0)) queue.Enqueue(r.GetString(0));
            }
        }
        return removed;
    }
}
