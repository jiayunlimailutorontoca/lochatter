using System.Security.Cryptography;
using System.Text;
using Chatter.Server.Protocol;

namespace Chatter.Server;

/// <summary>
/// Issues coturn "use-auth-secret" credentials: username = "&lt;expiry&gt;:&lt;user&gt;",
/// password = base64(HMAC-SHA1(secret, username)). Nothing is stored; coturn recomputes the HMAC.
/// </summary>
public sealed class TurnService(AppSettings settings)
{
    private static readonly TimeSpan Ttl = TimeSpan.FromHours(6);

    public TurnCreds Issue(long userId)
    {
        var host = settings.TurnHost;
        if (string.IsNullOrEmpty(host)) return new TurnCreds([], "", "", 0);

        var stun = $"stun:{host}:3478";
        var secret = settings.TurnSecret;
        if (string.IsNullOrEmpty(secret)) return new TurnCreds([stun], "", "", 0);

        var expiry = DateTimeOffset.UtcNow.Add(Ttl).ToUnixTimeSeconds();
        var username = $"{expiry}:{userId}";
        var mac = HMACSHA1.HashData(Encoding.UTF8.GetBytes(secret), Encoding.UTF8.GetBytes(username));
        return new TurnCreds(
            [stun, $"turn:{host}:3478?transport=udp", $"turn:{host}:3478?transport=tcp"],
            username,
            Convert.ToBase64String(mac),
            (long)Ttl.TotalSeconds);
    }
}
