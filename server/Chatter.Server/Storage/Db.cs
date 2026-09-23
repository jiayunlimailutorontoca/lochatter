using Microsoft.Data.Sqlite;

namespace Chatter.Server.Storage;

/// <summary>SQLite access. One pooled connection per operation.</summary>
public sealed class Db
{
    public const int CurrentSchema = 9;

    private readonly string _connString;
    private readonly string _path;
    private readonly ILogger<Db> _log;

    public int SchemaVersion { get; private set; }

    public Db(AppSettings settings, ILogger<Db> log)
    {
        _path = settings.DbPath;
        _connString = new SqliteConnectionStringBuilder
        {
            DataSource = _path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true,
        }.ToString();
        _log = log;
    }

    public SqliteConnection Open()
    {
        var c = new SqliteConnection(_connString);
        c.Open();
        return c;
    }

    public void Init()
    {
        using var c = Open();
        Exec(c, "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;");
        Exec(c, """
            CREATE TABLE IF NOT EXISTS meta(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS users(
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              pw_hash TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              last_seen INTEGER);
            CREATE TABLE IF NOT EXISTS tokens(
              id INTEGER PRIMARY KEY,
              user_id INTEGER NOT NULL REFERENCES users(id),
              token_hash TEXT NOT NULL UNIQUE,
              device TEXT,
              created_at INTEGER NOT NULL,
              last_seen INTEGER);
            CREATE TABLE IF NOT EXISTS media(
              id TEXT PRIMARY KEY,
              owner INTEGER NOT NULL REFERENCES users(id),
              mime TEXT NOT NULL,
              size INTEGER NOT NULL,
              width INTEGER,
              height INTEGER,
              duration_ms INTEGER,
              thumb_id TEXT,
              created_at INTEGER NOT NULL,
              name TEXT);
            CREATE TABLE IF NOT EXISTS messages(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              client_id TEXT NOT NULL UNIQUE,
              from_user INTEGER NOT NULL REFERENCES users(id),
              kind TEXT NOT NULL,
              text TEXT,
              media_id TEXT REFERENCES media(id),
              created_at INTEGER NOT NULL,
              reply_to TEXT,
              reply_from INTEGER,
              reply_text TEXT,
              edited_at INTEGER,
              expires_at INTEGER,
              dest TEXT,
              once INTEGER);
            CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at);
            CREATE TABLE IF NOT EXISTS read_marks(
              user_id INTEGER PRIMARY KEY REFERENCES users(id),
              upto_seq INTEGER NOT NULL,
              updated_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS user_keys(
              user_id INTEGER PRIMARY KEY REFERENCES users(id),
              pub_key TEXT NOT NULL,
              updated_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS settings(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS shared(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              updated_by INTEGER);
            INSERT OR IGNORE INTO meta(key, value) VALUES('schema', '7');
            """);

        var v = int.Parse(Scalar(c, "SELECT value FROM meta WHERE key = 'schema'"));
        if (v < 2)
        {
            // v1 -> v2: presence tracking
            if (!ColumnExists(c, "users", "last_seen")) Exec(c, "ALTER TABLE users ADD COLUMN last_seen INTEGER");
            Exec(c, "UPDATE meta SET value = '2' WHERE key = 'schema'");
            v = 2;
        }
        if (v < 3)
        {
            // v2 -> v3: original file names for generic attachments
            if (!ColumnExists(c, "media", "name")) Exec(c, "ALTER TABLE media ADD COLUMN name TEXT");
            Exec(c, "UPDATE meta SET value = '3' WHERE key = 'schema'");
            v = 3;
        }
        if (v < 4)
        {
            // v3 -> v4: quoted replies
            if (!ColumnExists(c, "messages", "reply_to"))
            {
                Exec(c, "ALTER TABLE messages ADD COLUMN reply_to TEXT");
                Exec(c, "ALTER TABLE messages ADD COLUMN reply_from INTEGER");
                Exec(c, "ALTER TABLE messages ADD COLUMN reply_text TEXT");
            }
            Exec(c, "UPDATE meta SET value = '4' WHERE key = 'schema'");
            v = 4;
        }
        if (v < 5)
        {
            // v4 -> v5: message editing, disappearing messages (tables user_keys/settings are created above)
            if (!ColumnExists(c, "messages", "edited_at")) Exec(c, "ALTER TABLE messages ADD COLUMN edited_at INTEGER");
            if (!ColumnExists(c, "messages", "expires_at")) Exec(c, "ALTER TABLE messages ADD COLUMN expires_at INTEGER");
            Exec(c, "CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at)");
            Exec(c, "UPDATE meta SET value = '5' WHERE key = 'schema'");
            v = 5;
        }
        if (v < 6)
        {
            // v5 -> v6: in-chat assistant (reserved user 0) and message dest ("bot" when @mentioned)
            if (!ColumnExists(c, "messages", "dest")) Exec(c, "ALTER TABLE messages ADD COLUMN dest TEXT");
            Exec(c, "INSERT OR IGNORE INTO users(id, name, pw_hash, created_at) VALUES(0, '助手', '!', 0)");
            Exec(c, "UPDATE meta SET value = '6' WHERE key = 'schema'");
            v = 6;
        }
        if (v < 7)
        {
            // v6 -> v7: view-once media (messages.once) and the two-person shared key/value store (table shared is created above)
            if (!ColumnExists(c, "messages", "once")) Exec(c, "ALTER TABLE messages ADD COLUMN once INTEGER");
            Exec(c, "CREATE TABLE IF NOT EXISTS shared(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by INTEGER)");
            Exec(c, "UPDATE meta SET value = '7' WHERE key = 'schema'");
            v = 7;
        }
        if (v < 8)
        {
            // v7 -> v8: per-account offline push (Server酱 / MeoW). Secret is the sendkey or nickname; the server must keep it to call the API.
            Exec(c, """
                CREATE TABLE IF NOT EXISTS push_pref(
                  user_id INTEGER PRIMARY KEY REFERENCES users(id),
                  provider TEXT NOT NULL,
                  secret TEXT NOT NULL,
                  interval_sec INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL)
                """);
            Exec(c, "UPDATE meta SET value = '8' WHERE key = 'schema'");
            v = 8;
        }
        if (v < 9)
        {
            // v8 -> v9: how much of an offline push to show. hint | text | count.
            if (!ColumnExists(c, "push_pref", "style"))
                Exec(c, "ALTER TABLE push_pref ADD COLUMN style TEXT NOT NULL DEFAULT 'text'");
            Exec(c, """
                CREATE TABLE IF NOT EXISTS push_key(
                  s INTEGER NOT NULL,
                  r INTEGER NOT NULL,
                  key TEXT NOT NULL,
                  updated_at INTEGER NOT NULL,
                  PRIMARY KEY(s, r))
                """);
            Exec(c, "UPDATE meta SET value = '9' WHERE key = 'schema'");
            v = 9;
        }
        SchemaVersion = v;
        _log.LogInformation("sqlite ready: {Path} (schema {Schema}, sqlite {Lib})", _path, SchemaVersion, c.ServerVersion);
    }

    private static bool ColumnExists(SqliteConnection c, string table, string column)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT COUNT(*) FROM pragma_table_info('{table}') WHERE name = $col";
        cmd.Parameters.AddWithValue("$col", column);
        return (long)cmd.ExecuteScalar()! > 0;
    }

    private static string Scalar(SqliteConnection c, string sql)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        return (string)cmd.ExecuteScalar()!;
    }

    private static void Exec(SqliteConnection c, string sql)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }
}
