using System.Buffers.Text;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using Chatter.Server.Protocol;
using Chatter.Server.Storage;

namespace Chatter.Server.Auth;

/// <summary>Login (password → long-lived device token) and bearer-token authentication with a small in-memory cache.</summary>
public sealed class AuthService(UserRepo users, TokenRepo tokens, ILogger<AuthService> log)
{
    private const int MaxFailures = 5;
    private static readonly TimeSpan Lockout = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan CacheTtl = TimeSpan.FromMinutes(5);

    private readonly ConcurrentDictionary<string, (UserInfo User, DateTime Expires)> _cache = new();
    private readonly ConcurrentDictionary<string, (int Fails, DateTime LockUntil)> _failures = new();

    public bool IsLocked(string ip) =>
        _failures.TryGetValue(ip, out var f) && f.LockUntil > DateTime.UtcNow;

    public LoginResponse? Login(string name, string password, string? device, string ip)
    {
        var user = users.FindByName(name.Trim());
        if (user is null || !Passwords.Verify(password, user.PwHash))
        {
            RecordFailure(ip);
            log.LogWarning("login failed for '{Name}' from {Ip}", name, ip);
            return null;
        }
        _failures.TryRemove(ip, out _);

        var token = Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(32));
        tokens.Insert(user.Id, HashToken(token), device, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        log.LogInformation("login ok: {Name} from {Ip} ({Device})", user.Name, ip, device ?? "?");
        return new LoginResponse(token, new UserInfo(user.Id, user.Name), users.PeerOf(user.Id));
    }

    /// <summary>Resolves the bearer token of a request; null when missing or invalid.
    /// Browsers cannot set Authorization on a WebSocket, so the same token is also accepted as cookie "chatter" (1.9 web).</summary>
    public UserInfo? Authenticate(HttpContext ctx)
    {
        var token = ReadToken(ctx);
        if (token is null) return null;

        var hash = HashToken(token);
        var now = DateTime.UtcNow;
        if (_cache.TryGetValue(hash, out var hit) && hit.Expires > now) return hit.User;

        var user = tokens.Lookup(hash);
        if (user is null) return null;
        tokens.Touch(hash, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        _cache[hash] = (user, now + CacheTtl);
        return user;
    }

    /// <summary>Hash of the bearer token on this request (null when absent); used to identify the calling device.</summary>
    public static string? TokenHashOf(HttpContext ctx)
    {
        var token = ReadToken(ctx);
        return token is null ? null : HashToken(token);
    }

    /// <summary>Authorization: Bearer, or cookie "chatter" when the header is absent (the web client).</summary>
    private static string? ReadToken(HttpContext ctx)
    {
        var header = ctx.Request.Headers.Authorization.ToString();
        if (header.StartsWith("Bearer ", StringComparison.Ordinal))
        {
            var token = header.AsSpan(7).Trim().ToString();
            if (token.Length is >= 32 and <= 128) return token;
        }
        if (ctx.Request.Cookies.TryGetValue("chatter", out var cookie))
        {
            cookie = cookie.Trim();
            if (cookie.Length is >= 32 and <= 128) return cookie;
        }
        return null;
    }

    public void Evict(string tokenHash) => _cache.TryRemove(tokenHash, out _);

    /// <summary>Changes the password after checking the old one. Returns false on a wrong old password.</summary>
    public bool ChangePassword(long userId, string oldPassword, string newPassword)
    {
        var row = users.List().FirstOrDefault(u => u.Id == userId);
        if (row is null || !Passwords.Verify(oldPassword, row.PwHash)) return false;
        users.SetPasswordById(userId, Passwords.Hash(newPassword));
        log.LogInformation("password changed for {Name}", row.Name);
        return true;
    }

    public int Revoke(long userId)
    {
        foreach (var kv in _cache)
            if (kv.Value.User.Id == userId) _cache.TryRemove(kv.Key, out _);
        return tokens.RevokeAll(userId);
    }

    private void RecordFailure(string ip) =>
        _failures.AddOrUpdate(ip,
            _ => (1, DateTime.MinValue),
            (_, f) => f.Fails + 1 >= MaxFailures ? (0, DateTime.UtcNow + Lockout) : (f.Fails + 1, f.LockUntil));

    public static string HashToken(ReadOnlySpan<char> token)
    {
        Span<byte> utf8 = stackalloc byte[512]; // 128 chars * 3 bytes max + slack
        var n = Encoding.UTF8.GetBytes(token, utf8);
        return Convert.ToHexStringLower(SHA256.HashData(utf8[..n]));
    }
}
