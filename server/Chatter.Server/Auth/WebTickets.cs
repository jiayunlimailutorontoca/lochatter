using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Buffers.Text;

namespace Chatter.Server.Auth;

/// <summary>
/// One-time webpage login tickets. The server stores only an opaque box; the private key never arrives in the clear.
/// Tickets live in memory and expire after 90 seconds.
/// </summary>
public sealed class WebTickets
{
    public const int TtlSeconds = 90;
    public const int MaxBoxChars = 65536;

    private sealed class Ticket
    {
        public long ExpiresAt;
        public string? Box;
        public int Taken;
    }

    private readonly ConcurrentDictionary<string, Ticket> _tickets = new();
    private readonly ConcurrentDictionary<string, (int Count, long Window)> _creates = new();

    public bool AllowCreate(string ip)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var slot = _creates.AddOrUpdate(ip,
            _ => (1, now),
            (_, cur) => now - cur.Window > 60_000 ? (1, now) : (cur.Count + 1, cur.Window));
        return slot.Count <= 20;
    }

    public (string Id, long ExpiresAt) Create()
    {
        Sweep();
        var id = Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(16));
        var exp = DateTimeOffset.UtcNow.AddSeconds(TtlSeconds).ToUnixTimeMilliseconds();
        _tickets[id] = new Ticket { ExpiresAt = exp };
        return (id, exp);
    }

    /// <summary>Stores the box once. False when the ticket is missing, expired, or already approved.</summary>
    public bool Approve(string id, string box)
    {
        if (!_tickets.TryGetValue(id, out var t)) return false;
        lock (t)
        {
            if (t.Box is not null || t.ExpiresAt < Now()) return false;
            t.Box = box;
            return true;
        }
    }

    /// <summary>pending, ready (box is returned once), expired, or gone.</summary>
    public (string Status, string? Box) Poll(string id)
    {
        if (!_tickets.TryGetValue(id, out var t)) return ("gone", null);
        lock (t)
        {
            if (t.ExpiresAt < Now() && t.Box is null)
            {
                _tickets.TryRemove(id, out _);
                return ("expired", null);
            }
            if (t.Box is null) return ("pending", null);
            if (Interlocked.Exchange(ref t.Taken, 1) == 1) return ("gone", null);
            var box = t.Box;
            _tickets.TryRemove(id, out _);
            return ("ready", box);
        }
    }

    private void Sweep()
    {
        if (_tickets.Count < 500) return;
        var now = Now();
        foreach (var kv in _tickets)
            if (kv.Value.ExpiresAt < now) _tickets.TryRemove(kv.Key, out _);
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
