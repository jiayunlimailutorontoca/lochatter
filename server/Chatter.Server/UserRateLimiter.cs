using System.Collections.Concurrent;

namespace Chatter.Server;

/// <summary>
/// Per-user token bucket for the HTTP helpers (/geo/*, /stt): perMinute sustained, burst at once, refilled continuously
/// like the per-connection buckets in Hub.TakeTokens. Memory only; buckets idle for a while are dropped now and then so
/// the table cannot grow.
/// </summary>
public sealed class UserRateLimiter(double perMinute, double burst)
{
    private sealed class Bucket
    {
        public double Tokens;
        public long Stamp;
    }

    private const long PruneEveryMs = 10 * 60_000;
    private const long IdleMs = 10 * 60_000;

    private readonly ConcurrentDictionary<long, Bucket> _buckets = new();
    private long _lastPrune = Environment.TickCount64;

    /// <summary>Charges one call. False = over the limit; the caller answers 429.</summary>
    public bool TryTake(long userId)
    {
        var now = Environment.TickCount64;
        var b = _buckets.GetOrAdd(userId, _ => new Bucket { Tokens = burst, Stamp = now });
        bool ok;
        lock (b)
        {
            b.Tokens = Math.Min(burst, b.Tokens + (now - b.Stamp) / 60_000.0 * perMinute);
            b.Stamp = now;
            ok = b.Tokens >= 1;
            if (ok) b.Tokens -= 1;
        }
        if (now - Interlocked.Read(ref _lastPrune) > PruneEveryMs)
        {
            Interlocked.Exchange(ref _lastPrune, now);
            foreach (var kv in _buckets)
                if (now - kv.Value.Stamp > IdleMs) _buckets.TryRemove(kv);
        }
        return ok;
    }
}
