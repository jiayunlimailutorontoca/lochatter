using Chatter.Server.Auth;
using Chatter.Server.Storage;
using Microsoft.Extensions.Logging.Abstractions;

namespace Chatter.Server;

/// <summary>Admin sub-commands, run in-process against the data dir (no web host started).</summary>
public static class Cli
{
    private const string Usage = """
        usage:
          chatter-server user add <name> [password]   create an account (max 2); prints a generated password if omitted
          chatter-server user list
          chatter-server user passwd <name> <password>
          chatter-server user rename <old> <new>
          chatter-server user del <name> --force      delete the user AND all their messages/media rows
          chatter-server token revoke <name>          log the user out of all devices
          chatter-server bot token                    print a fresh long-lived token for the in-chat assistant (Hermes)
          chatter-server bot revoke                   log the assistant out everywhere
          chatter-server bot name <name>              rename the assistant
          chatter-server notify list
          chatter-server notify set <name> off [seconds] [hint|text|count]
          chatter-server notify set <name> serverchan <sendkey> [seconds] [hint|text|count]
          chatter-server notify set <name> meow <nickname> [seconds] [hint|text|count]
        env: CHATTER_DATA=<data dir> (default: ./data)
        """;

    public static int Run(string[] args)
    {
        if (args.Length == 0 || args[0] is "--help" or "-h")
        {
            Console.WriteLine(Usage);
            return 0;
        }

        var cfg = new ConfigurationBuilder().AddEnvironmentVariables().Build();
        var settings = AppSettings.Load(cfg);
        var db = new Db(settings, NullLogger<Db>.Instance);
        db.Init();
        var users = new UserRepo(db);
        var tokens = new TokenRepo(db);
        var push = new PushRepo(db);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        switch (args)
        {
            case ["user", "add", var name, ..var rest] when rest.Length <= 1:
            {
                name = name.Trim();
                if (name.Length is < 1 or > 32) return Fail("name must be 1-32 chars");
                if (users.Count() >= UserRepo.MaxUsers) return Fail($"already {UserRepo.MaxUsers} users; this is a two-person chat");
                if (users.FindByName(name) is not null) return Fail($"user '{name}' exists");
                var pw = rest.Length == 1 ? rest[0] : Passwords.Generate();
                if (pw.Length < 6) return Fail("password must be at least 6 chars");
                var id = users.Add(name, Passwords.Hash(pw), now);
                Console.WriteLine($"created user #{id} '{name}'");
                if (rest.Length == 0) Console.WriteLine($"password: {pw}");
                return 0;
            }
            case ["user", "list"]:
            {
                foreach (var u in users.List())
                    Console.WriteLine($"#{u.Id}\t{u.Name}\tcreated {Fmt(u.CreatedAt)}\tlast seen {(u.LastSeen is { } ls ? Fmt(ls) : "never")}");
                return 0;
            }
            case ["user", "passwd", var name, var pw]:
            {
                if (pw.Length < 6) return Fail("password must be at least 6 chars");
                if (!users.SetPassword(name.Trim(), Passwords.Hash(pw))) return Fail($"no user '{name}'");
                Console.WriteLine($"password updated for '{name}' (existing device tokens stay valid; use 'token revoke' to log out devices)");
                return 0;
            }
            case ["user", "rename", var oldName, var newName]:
            {
                newName = newName.Trim();
                if (newName.Length is < 1 or > 32) return Fail("name must be 1-32 chars");
                if (users.FindByName(newName) is not null) return Fail($"user '{newName}' exists");
                if (!users.Rename(oldName.Trim(), newName)) return Fail($"no user '{oldName}'");
                Console.WriteLine($"renamed '{oldName}' -> '{newName}'");
                return 0;
            }
            case ["user", "del", var name, "--force"]:
            {
                var u = users.FindByName(name.Trim());
                if (u is null) return Fail($"no user '{name}'");
                var (msgs, mediaRows) = users.Delete(u.Id);
                Console.WriteLine($"deleted '{u.Name}' with {msgs} message(s) and {mediaRows} media row(s); files under media/ are left on disk");
                return 0;
            }
            case ["bot", "token"]:
            {
                users.EnsureBot();
                var token = System.Buffers.Text.Base64Url.EncodeToString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
                tokens.Insert(UserRepo.BotId, AuthService.HashToken(token), "hermes", now);
                Console.WriteLine(token);
                return 0;
            }
            case ["bot", "revoke"]:
            {
                var n = tokens.RevokeAll(UserRepo.BotId);
                Console.WriteLine($"revoked {n} assistant token(s)");
                return 0;
            }
            case ["bot", "name", var name]:
            {
                users.EnsureBot();
                name = name.Trim();
                if (name.Length is < 1 or > 24) return Fail("name must be 1-24 chars");
                if (users.FindByName(name) is { } clash && clash.Id != UserRepo.BotId) return Fail($"'{name}' is a user");
                if (!users.SetBotName(name)) return Fail("rename failed");
                Console.WriteLine($"assistant is now '{name}' (clients pick it up on next connect)");
                return 0;
            }
            case ["notify", "list"]:
            {
                foreach (var u in users.List())
                {
                    var row = push.Get(u.Id);
                    if (row is null) Console.WriteLine($"#{u.Id}\t{u.Name}\toff");
                    else Console.WriteLine($"#{u.Id}\t{u.Name}\t{row.Provider}\t{row.Style}\t{row.Secret}\tevery {row.IntervalSec}s");
                }
                return 0;
            }
            case ["notify", "set", var name, var provider, .. var rest]:
            {
                var u = users.FindByName(name.Trim());
                if (u is null) return Fail($"no user '{name}'");
                var interval = PushService.DefaultIntervalSec;
                var style = "text";
                string secret = "";
                var words = new List<string>();
                foreach (var tok in rest)
                {
                    if (tok is "hint" or "text" or "count") { style = tok; continue; }
                    words.Add(tok);
                }
                var kind = provider.Trim().ToLowerInvariant();
                if (kind == "off")
                {
                    if (words.Count > 1) return Fail("usage: notify set <name> off [seconds] [hint|text|count]");
                    if (words.Count == 1 && !int.TryParse(words[0], out interval)) return Fail("seconds must be a number");
                }
                else if (kind is "serverchan" or "meow")
                {
                    if (words.Count is < 1 or > 2) return Fail($"usage: notify set <name> {kind} <secret> [seconds] [hint|text|count]");
                    secret = words[0];
                    if (words.Count == 2 && !int.TryParse(words[1], out interval)) return Fail("seconds must be a number");
                }
                else return Fail("provider must be off, serverchan, or meow");
                if (!PushService.TryNormalize(kind, secret, interval, style, out var norm, out var error)) return Fail(error);
                push.Set(u.Id, norm.Provider, norm.Secret, norm.IntervalSec, norm.Style, now);
                Console.WriteLine($"'{u.Name}' push: {norm.Provider} {norm.Style} every {norm.IntervalSec}s");
                return 0;
            }
            case ["token", "revoke", var name]:
            {
                var u = users.FindByName(name.Trim());
                if (u is null) return Fail($"no user '{name}'");
                var n = tokens.RevokeAll(u.Id);
                Console.WriteLine($"revoked {n} token(s) for '{u.Name}' (the running server drops cached tokens within 5 minutes)");
                return 0;
            }
            default:
                Console.Error.WriteLine(Usage);
                return 2;
        }
    }

    private static int Fail(string msg)
    {
        Console.Error.WriteLine("error: " + msg);
        return 1;
    }

    private static string Fmt(long unixMs) =>
        DateTimeOffset.FromUnixTimeMilliseconds(unixMs).ToLocalTime().ToString("yyyy-MM-dd HH:mm");
}
