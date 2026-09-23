using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Chatter.Server.Protocol;
using Chatter.Server.Storage;

namespace Chatter.Server;

/// <summary>
/// Offline phone push for one account: Server酱³ or MeoW. The preference is stored with the user.
/// Pictures are never uploaded; the body is a short text. Sends are best-effort and never block the socket.
/// </summary>
public sealed class PushService(PushRepo repo, ILogger<PushService> log)
{
    public const int MaxIntervalSec = 24 * 3600;
    public const int DefaultIntervalSec = 60;
    public const int MaxBodyChars = 400;

    private static readonly HttpClient Http = new(new SocketsHttpHandler { PooledConnectionLifetime = TimeSpan.FromMinutes(10) })
    {
        Timeout = TimeSpan.FromSeconds(8),
    };

    /// <summary>True while that account has the app open. A push is only sent when this is false.</summary>
    public Func<long, bool>? AppOpen { get; set; }

    private readonly object _gate = new();
    private readonly Dictionary<long, Hold> _holds = new();

    public static bool TryNormalize(string? provider, string? secret, int intervalSec, string? style, out PushPref pref, out string error)
    {
        pref = new PushPref("off", "", DefaultIntervalSec, "text");
        error = "";
        if (intervalSec is < 0 or > MaxIntervalSec)
        {
            error = $"interval must be 0-{MaxIntervalSec} seconds";
            return false;
        }
        var mode = NormalizeStyle(style);
        if (mode is null)
        {
            error = "style must be hint, text, or count";
            return false;
        }
        var kind = (provider ?? "off").Trim().ToLowerInvariant();
        var key = (secret ?? "").Trim();
        switch (kind)
        {
            case "" or "off":
                pref = new PushPref("off", "", intervalSec, mode);
                return true;
            case "serverchan":
                if (!TryServerChanUid(key, out _))
                {
                    error = "Server酱 SendKey looks like sctp<数字>t<字母数字>";
                    return false;
                }
                pref = new PushPref("serverchan", key, intervalSec, mode);
                return true;
            case "meow":
                if (!MeowNameOk(key))
                {
                    error = "MeoW nickname must be 1-32 chars without spaces or slashes";
                    return false;
                }
                pref = new PushPref("meow", key, intervalSec, mode);
                return true;
            default:
                error = "provider must be off, serverchan, or meow";
                return false;
        }
    }

    /// <summary>hint = 给你发了消息, text = 给你发了原文, count = 发送了 N 条. Null when the value is not one of those.</summary>
    public static string? NormalizeStyle(string? style)
    {
        var mode = (style ?? "text").Trim().ToLowerInvariant();
        return mode is "hint" or "text" or "count" ? mode : null;
    }

    public static string Sentence(string style, string sender, string preview, int count)
    {
        var name = string.IsNullOrWhiteSpace(sender) ? "对方" : sender.Trim();
        var n = Math.Max(1, count);
        var body = string.IsNullOrWhiteSpace(preview) ? "一条消息" : preview.Trim();
        return style switch
        {
            "hint" => $"lochatter {name}向您发送了消息",
            "count" => $"lochatter {name}向您发送了{n}条消息",
            _ => $"lochatter {name}向您发送了{body}",
        };
    }

    /// <summary>Queue a push for [userId]. The first one in a quiet period goes out immediately; later ones collapse into one.</summary>
    public void Offer(long userId, string sender, string preview)
    {
        var pref = repo.Get(userId);
        if (pref is null || pref.Provider is not ("serverchan" or "meow") || pref.Secret.Length == 0) return;
        sender = Clip(string.IsNullOrWhiteSpace(sender) ? "对方" : sender.Trim(), 24);
        preview = Clip(preview.Trim(), MaxBodyChars);

        Hold hold;
        var sendNow = false;
        lock (_gate)
        {
            if (!_holds.TryGetValue(userId, out hold!))
            {
                hold = new Hold();
                _holds[userId] = hold;
            }
            var now = Now();
            var gap = Math.Max(0, pref.IntervalSec) * 1000L;
            var quiet = hold.LastSentMs == 0 || gap == 0 || now - hold.LastSentMs >= gap;
            if (quiet && hold.Waiting == 0)
            {
                hold.LastSentMs = now;
                sendNow = true;
            }
            else
            {
                hold.Waiting++;
                hold.Sender = sender;
                hold.Preview = preview;
                if (hold.Timer is null)
                {
                    var waitMs = (int)Math.Clamp(hold.LastSentMs + Math.Max(gap, 1) - now, 1, int.MaxValue);
                    var id = userId;
                    hold.Timer = new Timer(_ => Flush(id), null, waitMs, Timeout.Infinite);
                }
            }
        }
        if (sendNow) _ = Send(pref, Sentence(pref.Style, sender, preview, 1));
    }

    /// <summary>Readable preview. Ciphertext is opened with an uploaded session key, or the sender's plaintext notice. The blob itself is never returned.</summary>
    public string Preview(string kind, string? text, string? notice, bool once, string messageId)
    {
        if (once) return "阅后即焚";
        var opened = OpenPlain(text, messageId);
        if (string.IsNullOrWhiteSpace(opened))
        {
            var hinted = (notice ?? "").Trim();
            if (hinted.Length > 0 && !MessageRepo.IsE2e(hinted)) opened = hinted;
        }
        opened = Clip((opened ?? "").Trim(), MaxBodyChars);
        return kind switch
        {
            "text" or "card" => opened.Length > 0 ? opened : "一条消息",
            "image" or "album" => WithLabel("[图片]", opened),
            "audio" => "[语音]",
            "video" => WithLabel("[视频]", opened),
            "file" => WithLabel("[文件]", opened),
            "sticker" => "[表情]",
            "pat" => "拍了拍你",
            "location" => LocationLine(opened),
            "call" => opened.Length > 0 ? opened : "[通话]",
            _ => opened.Length > 0 ? opened : "一条消息",
        };
    }

    private string? OpenPlain(string? text, string messageId)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (!MessageRepo.IsE2e(text)) return text.Trim();
        return PushCrypto.Open(text, messageId, repo.Key);
    }

    private static string WithLabel(string label, string opened)
    {
        if (opened.Length == 0 || opened == "一条消息") return label;
        if (opened.StartsWith(label, StringComparison.Ordinal)) return opened;
        return label + " " + opened;
    }

    private static string LocationLine(string opened)
    {
        if (opened.Length == 0 || MessageRepo.IsE2e(opened)) return "[位置]";
        var parts = opened.Split('|');
        var address = parts.Length > 2 ? parts[2].Trim() : "";
        return address.Length == 0 ? "[位置]" : "[位置] " + Clip(address, 60);
    }

    private void Flush(long userId)
    {
        PushRow? pref;
        string title;
        lock (_gate)
        {
            if (!_holds.TryGetValue(userId, out var hold)) return;
            hold.Timer?.Dispose();
            hold.Timer = null;
            var n = hold.Waiting;
            hold.Waiting = 0;
            if (n <= 0) return;
            if (AppOpen?.Invoke(userId) == true) return;
            pref = repo.Get(userId);
            if (pref is null || pref.Provider is not ("serverchan" or "meow") || pref.Secret.Length == 0) return;
            var sentence = Sentence(pref.Style, hold.Sender, hold.Preview, n);
            hold.LastSentMs = Now();
            title = sentence;
        }
        _ = Send(pref, title);
    }

    private async Task Send(PushRow pref, string sentence)
    {
        try
        {
            using var res = pref.Provider switch
            {
                "serverchan" => await PostServerChan(pref.Secret, Clip(sentence, 32), sentence),
                "meow" => await PostMeow(pref.Secret, Clip(sentence, 80), sentence),
                _ => null,
            };
            if (res is null) return;
            if (res.IsSuccessStatusCode)
                log.LogInformation("push {Provider} user {User}", pref.Provider, pref.UserId);
            else
                log.LogWarning("push {Provider} user {User} HTTP {Status}", pref.Provider, pref.UserId, (int)res.StatusCode);
        }
        catch (Exception ex)
        {
            log.LogWarning("push {Provider} user {User} failed: {Type}", pref.Provider, pref.UserId, ex.GetType().Name);
        }
    }

    private static async Task<HttpResponseMessage> PostServerChan(string sendKey, string title, string body)
    {
        if (!TryServerChanUid(sendKey, out var uid)) throw new InvalidOperationException("sendkey");
        var url = $"https://{uid}.push.ft07.com/send/{sendKey}.send";
        var json = JsonSerializer.Serialize(new ServerChanPayload(title, body, Clip(body, 80)), Json.ServerChanPayload);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };
        return await Http.SendAsync(req);
    }

    private static async Task<HttpResponseMessage> PostMeow(string nickname, string title, string body)
    {
        var url = "https://api.chuckfang.com/" + Uri.EscapeDataString(nickname);
        var json = JsonSerializer.Serialize(new MeowPayload(title, body), Json.MeowPayload);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return await Http.SendAsync(req);
    }

    private static bool TryServerChanUid(string sendKey, out string uid)
    {
        uid = "";
        if (sendKey.Length is < 8 or > 128 || !sendKey.StartsWith("sctp", StringComparison.Ordinal)) return false;
        var t = sendKey.IndexOf('t', 4);
        if (t <= 4 || t >= sendKey.Length - 1) return false;
        uid = sendKey[4..t];
        if (uid.Length is < 1 or > 12) return false;
        foreach (var ch in uid)
            if (!char.IsDigit(ch)) return false;
        for (var i = t + 1; i < sendKey.Length; i++)
            if (!char.IsAsciiLetterOrDigit(sendKey[i])) return false;
        return true;
    }

    private static bool MeowNameOk(string name)
    {
        if (name.Length is < 1 or > 32) return false;
        foreach (var ch in name)
        {
            if (char.IsWhiteSpace(ch) || ch is '/' or '\\' or '?' or '#' or '%' or '&') return false;
            if (char.IsControl(ch)) return false;
        }
        return true;
    }

    private static string Clip(string s, int max) => s.Length <= max ? s : s[..max];

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private sealed class Hold
    {
        public long LastSentMs;
        public int Waiting;
        public string Sender = "";
        public string Preview = "";
        public Timer? Timer;
    }
}
