using System.Collections.Concurrent;
using System.Globalization;
using System.Net.WebSockets;
using Chatter.Server.Protocol;
using Chatter.Server.Storage;
using Chatter.Server;

namespace Chatter.Server.Ws;

/// <summary>Registry of live connections plus the message dispatcher for the two-person room.</summary>
public sealed class Hub(
    UserRepo users, MessageRepo messages, MediaRepo media, KeyRepo keys, SettingsRepo store, SharedRepo shared,
    TurnService turn, AppSettings settings, Capabilities caps, PushService push, ILogger<Hub> log)
{
    private const int SyncDefault = 200;
    private const int SyncMax = 500;
    // Generous because end-to-end encrypted text is base64 and ~1.4x larger than the plaintext.
    public const int MaxTextChars = 20_000;
    private const int MaxCaptionChars = 4_000;
    private const int MaxCallLogChars = 160;
    private const int MaxReactChars = 512;
    private const int MaxStickerChars = 512;
    private const int MaxLocationChars = 1024; // v2 ciphertext of a 120-char Chinese address is ~600 chars
    /// <summary>WeChat-style 撤回 window.</summary>
    private const long RecallWindowMs = 120_000;
    private const int MaxPatChars = 64;
    private const int MaxCallEmojiChars = 16;
    private const int MaxLiveCaptionChars = 2_000;
    private const long MaxTtlSeconds = 365L * 24 * 3600;
    private const string TtlKey = "ttl_seconds";
    private const string BotTtlKey = "bot_ttl";

    // Per-connection rate limits: two token buckets refilled continuously (see TakeTokens). The assistant gets BotRateFactor× these.
    private const double FramesPerSecond = 40;
    private const double FrameBurst = 80;
    private const double SendsPerSecond = 60 / 60.0; // msg.send: 60 per minute sustained
    private const double SendBurst = 90;
    private const int BotRateFactor = 3;
    /// <summary>A connection whose frame bucket stays dry this long is not backing off and gets closed.</summary>
    private const int FloodCloseMs = 10_000;

    /// <summary>Orphaned uploads are looked for at most every this many seconds (see SweepLoopAsync), this many per pass.</summary>
    private const int OrphanPassMaxSeconds = 600;
    private const int OrphanBatch = 200;

    private static readonly HashSet<string> ContentKinds = ["text", "image", "album", "audio", "video", "file", "call", "sticker", "pat", "card", "location"];
    private const string KindList = "text | image | album | audio | video | file | call | sticker | pat | card | location | react | edit | ttl | del | clear";

    /// <summary>
    /// The whole role policy for msg.send, per kind: may a human send it, may the assistant send it, may it be addressed
    /// to the assistant (to: "bot"). Content checks live in OnSend; see Allowed for the messages.
    /// </summary>
    private static readonly Dictionary<string, (bool Human, bool Bot, bool ToBot)> KindPolicy = new()
    {
        //              human  bot    to bot
        ["text"] = (true, true, true),
        ["image"] = (true, true, true),
        ["album"] = (true, false, false),
        ["audio"] = (true, true, true),
        ["video"] = (true, true, true),
        ["file"] = (true, true, true),
        ["sticker"] = (true, true, true),
        ["location"] = (true, false, true),
        ["call"] = (true, false, false),
        ["pat"] = (true, false, false),
        ["card"] = (false, true, false),
        ["react"] = (true, true, false),
        ["edit"] = (true, true, false),
        ["ttl"] = (true, false, false),
        ["del"] = (true, true, false),
        ["recall"] = (true, true, false),
        ["clear"] = (true, false, false),
    };

    /// <summary>
    /// Frame types only humans may send. The value is the "unsupported" error the assistant gets back; null drops the
    /// frame silently (emoji and presence, where an error would only be noise). The assistant may answer a call,
    /// exchange SDP/ICE, send captions, and ask for TURN. It still cannot place a call.
    /// </summary>
    private static readonly Dictionary<Type, string?> HumanOnlyFrames = new()
    {
        [typeof(CallInvite)] = "the assistant cannot place calls",
        [typeof(CallEmoji)] = null,
        [typeof(ReadMark)] = null, [typeof(Active)] = null,
    };

    private readonly ConcurrentDictionary<string, WsConnection> _conns = new();
    /// <summary>Last battery / charging report per human user (Active frame). Memory only; lost on restart.</summary>
    private readonly ConcurrentDictionary<long, (int? Battery, bool? Charging)> _power = new();
    /// <summary>Assistant calls in progress: call id → the human who dialed. The other human is not a party.</summary>
    private readonly ConcurrentDictionary<string, BotCall> _botCalls = new();
    /// <summary>Recently finished assistant calls, so a late caption or hangup is swallowed instead of leaking into a human call.</summary>
    private readonly ConcurrentDictionary<string, long> _closedBotCalls = new();

    private sealed record BotCall(long HumanId, long StartedAt);

    public int ConnectionCount => _conns.Count;

    /// <summary>Has a live socket: reachable for messages and calls.</summary>
    public bool IsConnected(long userId)
    {
        foreach (var c in _conns.Values)
            if (c.UserId == userId) return true;
        return false;
    }

    /// <summary>App in the foreground on at least one device: what the peer sees as online.</summary>
    public bool IsOnline(long userId)
    {
        foreach (var c in _conns.Values)
            if (c.UserId == userId && c.Active) return true;
        return false;
    }

    public long TtlSeconds => long.TryParse(store.Get(TtlKey), out var t) && t > 0 ? t : 0;

    /// <summary>Whether the assistant's messages (and those addressed to it) follow disappearing-message expiry. Default on.</summary>
    public bool BotTtl => store.Get(BotTtlKey) is not "0";

    public bool SetBotTtl(bool on)
    {
        store.Set(BotTtlKey, on ? "1" : "0");
        NotifyBot();
        return on;
    }

    /// <summary>Writes one shared key/value entry and tells every human connection (the writer included).</summary>
    public SharedItem SetShared(string key, string value, long uid)
    {
        var item = shared.Set(key, value, Now(), uid);
        SendToHumans(new Shared(item.Key, item.Value, item.UpdatedAt));
        return item;
    }

    public async Task HandleAsync(WebSocket ws, UserInfo user, string? tokenHash, string remoteIp, CancellationToken ct)
    {
        var isBot = UserRepo.IsBot(user.Id);
        var conn = new WsConnection(ws, remoteIp, log) { UserId = user.Id, TokenHash = tokenHash };
        var rate = isBot ? BotRateFactor : 1;
        conn.FrameTokens = FrameBurst * rate;
        conn.SendTokens = SendBurst * rate;
        conn.RateStamp = Environment.TickCount64;
        _conns[conn.Id] = conn;
        log.LogInformation("ws {Id} {User} connected from {Ip} ({Count} online)", conn.Id, user.Name, remoteIp, _conns.Count);

        var peer = isBot ? null : users.PeerOf(user.Id);
        PeerInfo? peerInfo = null;
        if (peer is not null)
        {
            var power = _power.GetValueOrDefault(peer.Id);
            peerInfo = new PeerInfo(peer.Id, peer.Name, IsOnline(peer.Id), users.LastSeen(peer.Id), keys.Get(peer.Id)?.PubKey, power.Battery, power.Charging);
        }
        // The assistant learns the humans' names; humans get the shared key/value snapshot.
        var humans = isBot ? users.List().Select(u => new UserInfo(u.Id, u.Name)).ToArray() : null;
        var sharedAll = isBot ? null : shared.All().ToArray();
        conn.Send(new Hello(conn.Id, AppInfo.Version, Now(), user, peerInfo,
            messages.LastSeq(), messages.ReadUpto(user.Id), peer is null ? 0 : messages.ReadUpto(peer.Id),
            keys.Get(user.Id)?.PubKey, TtlSeconds, BotSnapshot(), humans, sharedAll, caps.ToFeatures()));
        if (isBot) NotifyBot();

        try
        {
            await conn.RunAsync(OnMessageAsync, ct);
        }
        finally
        {
            _conns.TryRemove(conn.Id, out _);
            try
            {
                DropBotCalls(user.Id, isBot);
                if (isBot) NotifyBot();
                else if (conn.Active) SetInactive(conn, user.Id);
            }
            catch (Exception ex) { log.LogWarning(ex, "presence update failed for {User}", user.Name); }
            var secs = (long)(DateTimeOffset.UtcNow - conn.ConnectedAt).TotalSeconds;
            log.LogInformation("ws {Id} {User} closed after {Seconds}s ({Count} online)", conn.Id, user.Name, secs, _conns.Count);
        }
    }

    /// <summary>Drops every socket that authenticated with the given (now revoked) device token.</summary>
    public int KickToken(string tokenHash)
    {
        var n = 0;
        foreach (var c in _conns.Values)
            if (c.TokenHash == tokenHash) { c.Kill(); n++; }
        return n;
    }

    /// <summary>A user published a new end-to-end key: every connection (both users, all devices) hears about it.</summary>
    public void NotifyKey(long userId, string pubKey, long ts)
    {
        var frame = new Keys(userId, pubKey, ts);
        foreach (var c in _conns.Values) c.Send(frame);
    }

    /// <summary>
    /// Disappearing messages: removes expired rows and tells everyone via ordinary del entries. Now and then (half the
    /// media grace period, at most every OrphanPassMaxSeconds) it also drops uploads that never became a message.
    /// </summary>
    public async Task SweepLoopAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(settings.SweepSeconds));
        var orphanEveryMs = Math.Clamp(settings.MediaGraceSeconds / 2, 2, OrphanPassMaxSeconds) * 1000L;
        var lastOrphanPass = Environment.TickCount64;
        try
        {
            while (await timer.WaitForNextTickAsync(ct))
            {
                try { SweepExpired(); }
                catch (Exception ex) { log.LogWarning(ex, "expiry sweep failed"); }
                if (Environment.TickCount64 - lastOrphanPass < orphanEveryMs) continue;
                lastOrphanPass = Environment.TickCount64;
                try { SweepOrphanedMedia(); }
                catch (Exception ex) { log.LogWarning(ex, "orphan media sweep failed"); }
            }
        }
        catch (OperationCanceledException) { }
    }

    private void SweepExpired()
    {
        var now = Now();
        var expired = messages.Expired(now, 200);
        if (expired.Count == 0) return;
        var mediaIds = new List<string>();
        foreach (var (id, from) in expired)
        {
            var (deleted, mediaId) = messages.Delete(id);
            if (!deleted) continue;
            if (mediaId is not null) mediaIds.Add(mediaId);
            // Same entry a manual "delete for both" would produce, attributed to the sender; "exp-" keeps the client id unique.
            var (del, _) = messages.Insert(from, "exp-" + id, "del", id, null, now, null);
            BroadcastNew(del, exceptConnId: "");
        }
        RemoveMedia(mediaIds);
        log.LogInformation("expired {Count} message(s)", expired.Count);
    }

    /// <summary>
    /// Uploads that never became a message (cancelled sends, crashes mid-flow): media older than the grace period that no
    /// message references, directly or as a thumbnail, goes with its file. Younger rows are never touched; a large upload
    /// can take a while before its message arrives.
    /// </summary>
    private void SweepOrphanedMedia()
    {
        var orphans = media.Orphans(Now() - settings.MediaGraceSeconds * 1000L, OrphanBatch);
        if (orphans.Count == 0) return;
        var removed = RemoveMedia(orphans);
        if (removed > 0) log.LogInformation("removed {Count} orphaned upload(s)", removed);
    }

    private ValueTask OnMessageAsync(WsConnection c, WsMessage m)
    {
        var uid = c.UserId!.Value;
        var isBot = UserRepo.IsBot(uid);
        if (!TakeTokens(c, m, isBot)) return ValueTask.CompletedTask;
        if (isBot && HumanOnlyFrames.TryGetValue(m.GetType(), out var why))
        {
            if (why is not null) c.Send(new ErrorMsg("unsupported", why));
            return ValueTask.CompletedTask;
        }
        switch (m)
        {
            case Ping p:
                c.Send(new Pong(p.Ts, Now()));
                break;
            case Echo e:
                c.Send(e);
                break;
            case MsgSend s:
                OnSend(c, uid, isBot, s);
                break;
            case Sync s:
            {
                var limit = Math.Clamp(s.Limit ?? SyncDefault, 1, SyncMax);
                if (s.Before is > 0)
                {
                    // history paging: newest `limit` messages older than Before, ascending
                    var older = messages.Before(s.Before.Value, limit + 1);
                    if (isBot) older = FilterForBot(older);
                    var moreOld = older.Count > limit;
                    if (moreOld) older.RemoveAt(0);
                    c.Send(new MsgBatch(older.ToArray(), moreOld, s.Before));
                    break;
                }
                var list = messages.After(Math.Max(0, s.Since), limit + 1);
                if (isBot) list = FilterForBot(list);
                var more = list.Count > limit;
                if (more) list.RemoveAt(list.Count - 1);
                c.Send(new MsgBatch(list.ToArray(), more));
                break;
            }
            case ReadMark r:
            {
                var effective = messages.SetReadUpto(uid, Math.Max(0, r.Upto), Now());
                SendToHumans(new ReadMark(effective, uid), exceptUserId: uid);
                break;
            }
            case Typing:
                // The assistant's typing goes to every human; a human's to the peer only.
                SendToHumans(new Typing(uid), exceptUserId: isBot ? null : uid);
                break;
            case Active a:
            {
                // Remember the latest battery report; a change alone is worth a presence frame even without an online transition.
                var powerChanged = false;
                if (a.Battery is not null || a.Charging is not null)
                {
                    var old = _power.GetValueOrDefault(uid);
                    var next = (Battery: a.Battery is { } b ? Math.Clamp(b, 0, 100) : old.Battery, Charging: a.Charging ?? old.Charging);
                    powerChanged = next != old;
                    _power[uid] = next;
                }
                var sent = false;
                if (a.Fg && !c.Active)
                {
                    var wasOnline = IsOnline(uid);
                    c.Active = true;
                    users.SetLastSeen(uid, Now());
                    if (!wasOnline) { SendPresence(uid); sent = true; }
                }
                else if (!a.Fg && c.Active)
                {
                    sent = SetInactive(c, uid);
                }
                if (powerChanged && !sent) SendPresence(uid);
                break;
            }

            case CallInvite ci:
            {
                if (ci.Bot == true)
                {
                    if (string.IsNullOrEmpty(ci.CallId) || ci.CallId.Length > 64)
                    {
                        c.Send(new ErrorMsg("bad_request", "callId is required"));
                        break;
                    }
                    if (ci.Video)
                    {
                        c.Send(new CallReject(ci.CallId, "voice_only"));
                        break;
                    }
                    if (!IsConnected(UserRepo.BotId))
                    {
                        c.Send(new CallReject(ci.CallId, "offline", UserRepo.BotId));
                        break;
                    }
                    if (!_botCalls.IsEmpty)
                    {
                        c.Send(new CallReject(ci.CallId, "busy", UserRepo.BotId));
                        break;
                    }
                    _botCalls[ci.CallId] = new BotCall(uid, Now());
                    log.LogInformation("call {CallId} assistant invite from {User}", ci.CallId, uid);
                    SendToBot(ci with { From = uid, Ts = Now(), Bot = true });
                    break;
                }
                var peer = users.PeerOf(uid);
                if (peer is null)
                {
                    c.Send(new CallReject(ci.CallId, "offline"));
                    break;
                }
                if (!IsConnected(peer.Id))
                {
                    c.Send(new CallReject(ci.CallId, "offline", peer.Id));
                    break;
                }
                log.LogInformation("call {CallId} invite from {User} (video={Video})", ci.CallId, uid, ci.Video);
                SendToHumans(ci with { From = uid, Ts = Now() }, exceptUserId: uid);
                break;
            }
            case CallAccept ca:
                log.LogInformation("call {CallId} accepted by {User}", ca.CallId, uid);
                if (RouteBot(ca.CallId, uid, isBot, ca with { From = uid }, closing: false)) break;
                if (isBot) break;
                SendToHumans(ca with { From = uid }, exceptUserId: uid);
                break;
            case CallReject cr:
                if (RouteBot(cr.CallId, uid, isBot, cr with { From = uid }, closing: true)) break;
                if (isBot) break;
                SendToHumans(cr with { From = uid }, exceptUserId: uid);
                break;
            case CallHangup ch:
                log.LogInformation("call {CallId} hangup by {User} ({Reason})", ch.CallId, uid, ch.Reason ?? "-");
                if (RouteBot(ch.CallId, uid, isBot, ch with { From = uid }, closing: true)) break;
                if (isBot) break;
                SendToHumans(ch with { From = uid }, exceptUserId: uid);
                break;
            case CallSdp sdp:
                if (sdp.Type is not ("offer" or "answer") || sdp.Sdp.Length > 120_000)
                {
                    c.Send(new ErrorMsg("bad_request", "sdp type must be offer|answer"));
                    break;
                }
                if (RouteBot(sdp.CallId, uid, isBot, sdp with { From = uid }, closing: false)) break;
                if (isBot) break;
                SendToHumans(sdp with { From = uid }, exceptUserId: uid);
                break;
            case CallIce ice:
                if (RouteBot(ice.CallId, uid, isBot, ice with { From = uid }, closing: false)) break;
                if (isBot) break;
                SendToHumans(ice with { From = uid }, exceptUserId: uid);
                break;
            case CallMedia cm:
                if (RouteBot(cm.CallId, uid, isBot, cm with { From = uid }, closing: false)) break;
                if (isBot) break;
                SendToHumans(cm with { From = uid }, exceptUserId: uid);
                break;
            case CallCaption cap:
            {
                var text = cap.Text ?? "";
                var phaseOk = cap.Phase is null or "listening" or "thinking" or "speaking" or "idle" or "error";
                if (string.IsNullOrEmpty(cap.CallId) || cap.CallId.Length > 64 || text.Length > MaxLiveCaptionChars
                    || cap.Who is not ("user" or "assistant") || cap.State is not ("partial" or "final") || !phaseOk)
                {
                    c.Send(new ErrorMsg("bad_request", "call.caption needs callId, who=user|assistant, state=partial|final"));
                    break;
                }
                var stamped = cap with { Text = text, From = uid, Ts = cap.Ts ?? Now() };
                // Unknown or finished calls are ignored. A bad speaker still gets an error above.
                RouteBot(cap.CallId, uid, isBot, stamped, closing: false);
                break;
            }
            case CallEmoji ce:
                if (string.IsNullOrEmpty(ce.Emoji) || ce.Emoji.Length > MaxCallEmojiChars || string.IsNullOrEmpty(ce.CallId) || ce.CallId.Length > 64)
                {
                    c.Send(new ErrorMsg("bad_request", $"call.emoji needs callId and an emoji of 1-{MaxCallEmojiChars} chars"));
                    break;
                }
                SendToHumans(ce with { From = uid }, exceptUserId: uid);
                break;
            case TurnGet:
                c.Send(turn.Issue(uid));
                break;

            default:
                c.Send(new ErrorMsg("unsupported", $"'{m.GetType().Name}' is not handled by this server version"));
                break;
        }
        return ValueTask.CompletedTask;
    }

    /// <summary>
    /// Per-connection rate limiting. Refills both buckets for the time elapsed, then charges one frame token (plus one
    /// msg.send token for sends). False = the frame is dropped after a rate_limited error. A connection whose frame bucket
    /// stays dry for FloodCloseMs is closed: it is not backing off, and answering it only costs more.
    /// </summary>
    private bool TakeTokens(WsConnection c, WsMessage m, bool isBot)
    {
        var factor = isBot ? BotRateFactor : 1;
        var now = Environment.TickCount64;
        var elapsed = (now - c.RateStamp) / 1000.0;
        c.RateStamp = now;
        c.FrameTokens = Math.Min(FrameBurst * factor, c.FrameTokens + elapsed * FramesPerSecond * factor);
        c.SendTokens = Math.Min(SendBurst * factor, c.SendTokens + elapsed * SendsPerSecond * factor);
        var sendId = (m as MsgSend)?.Id;
        if (c.FrameTokens < 1)
        {
            if (c.FloodSince == 0) c.FloodSince = now;
            else if (now - c.FloodSince > FloodCloseMs)
            {
                log.LogWarning("ws {Id} user {User} kept flooding for over {Secs}s; closing", c.Id, c.UserId, FloodCloseMs / 1000);
                c.Kill();
                return false;
            }
            c.Send(new ErrorMsg("rate_limited", "slow down", sendId));
            return false;
        }
        // Back to half a burst means the client backed off: the flood clock restarts with the next dry spell.
        if (c.FloodSince != 0 && c.FrameTokens >= FrameBurst * factor / 2) c.FloodSince = 0;
        c.FrameTokens -= 1;
        if (sendId is null) return true;
        if (c.SendTokens < 1)
        {
            c.Send(new ErrorMsg("rate_limited", "slow down", sendId));
            return false;
        }
        c.SendTokens -= 1;
        return true;
    }

    private static bool IsE2e(string? t) => t is not null && (t.StartsWith("e2e:", StringComparison.Ordinal) || t.StartsWith("e2e2:", StringComparison.Ordinal));

    /// <summary>Plaintext location "lat,lng|accuracyMeters|address|live": only the coordinates are checked; the rest is free text for the clients and the assistant.</summary>
    private static bool IsLocationText(string text)
    {
        var bar = text.IndexOf('|');
        var coords = bar < 0 ? text.AsSpan() : text.AsSpan(0, bar);
        var comma = coords.IndexOf(',');
        if (comma <= 0) return false;
        return double.TryParse(coords[..comma].Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var lat) && Math.Abs(lat) <= 90
            && double.TryParse(coords[(comma + 1)..].Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var lng) && Math.Abs(lng) <= 180;
    }

    /// <summary>Client-side encrypted media starts with the "LCE1" stream magic; the assistant could never open it.</summary>
    private bool MediaLooksEncrypted(string id)
    {
        try
        {
            using var fs = File.OpenRead(Path.Combine(settings.MediaDir, id[..2], id));
            Span<byte> head = stackalloc byte[4];
            return fs.Read(head) == 4 && head[0] == (byte)'L' && head[1] == (byte)'C' && head[2] == (byte)'E' && head[3] == (byte)'1';
        }
        catch (Exception) { return false; }
    }

    /// <summary>The assistant only ever sees plaintext @mentions and its own replies — never e2e blobs. Del / clear entries pass so it can drop cached media.</summary>
    private static List<ChatMessage> FilterForBot(List<ChatMessage> list)
    {
        var keep = new List<ChatMessage>(list.Count);
        foreach (var m in list)
        {
            if (m.Kind is "del" or "clear" or "recall") { keep.Add(m); continue; }
            if (IsE2e(m.Text)) continue;
            if (UserRepo.IsBot(m.From) || string.Equals(m.To, "bot", StringComparison.Ordinal)) keep.Add(m);
        }
        return keep;
    }

    /// <summary>The role policy of one msg.send (KindPolicy): may this sender post this kind, to this destination? The reason becomes the bad_request message.</summary>
    private static bool Allowed(string? kind, bool isBot, bool toBot, out string why)
    {
        why = "";
        if (kind is null || !KindPolicy.TryGetValue(kind, out var p)) why = "kind must be " + KindList;
        else if (isBot && toBot) why = "the assistant cannot @ itself";
        else if (isBot ? !p.Bot : !p.Human) why = isBot ? $"the assistant cannot send {kind}" : $"only the assistant can send {kind}";
        else if (toBot && !p.ToBot) why = $"{kind} cannot be sent to the assistant";
        return why.Length == 0;
    }

    private void OnSend(WsConnection c, long uid, bool isBot, MsgSend s)
    {
        if (string.IsNullOrEmpty(s.Id) || s.Id.Length is < 8 or > 64)
        {
            c.Send(new ErrorMsg("bad_request", "id must be 8-64 chars", s.Id));
            return;
        }
        if (s.ReplyTo is { Length: > 64 })
        {
            c.Send(new ErrorMsg("bad_request", "replyTo too long", s.Id));
            return;
        }
        var toBot = string.Equals(s.To, "bot", StringComparison.OrdinalIgnoreCase);
        if (!Allowed(s.Kind, isBot, toBot, out var why))
        {
            c.Send(new ErrorMsg("bad_request", why, s.Id));
            return;
        }
        switch (s.Kind)
        {
            case "text":
                if (string.IsNullOrWhiteSpace(s.Text) || s.Text.Length > MaxTextChars)
                {
                    c.Send(new ErrorMsg("bad_request", $"text must be 1-{MaxTextChars} chars", s.Id));
                    return;
                }
                if (s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "text messages carry no mediaId", s.Id));
                    return;
                }
                break;
            case "image" or "album" or "audio" or "file" or "video":
                if (string.IsNullOrEmpty(s.MediaId) || media.Get(s.MediaId) is null)
                {
                    c.Send(new ErrorMsg("bad_request", "mediaId unknown; upload it first via POST /media", s.Id));
                    return;
                }
                if (s.Text?.Length > MaxCaptionChars)
                {
                    c.Send(new ErrorMsg("bad_request", $"caption must be at most {MaxCaptionChars} chars", s.Id));
                    return;
                }
                break;
            case "sticker":
            {
                // Text = "bqb|<library path>|<w>|<h>" (no mediaId) or "media|<mediaId>|<w>|<h>" (custom sticker, mediaId set too), or an e2e blob of either.
                if (string.IsNullOrEmpty(s.Text) || s.Text.Length > MaxStickerChars)
                {
                    c.Send(new ErrorMsg("bad_request", $"sticker text must be 1-{MaxStickerChars} chars", s.Id));
                    return;
                }
                if (s.MediaId is not null && media.Get(s.MediaId) is null)
                {
                    c.Send(new ErrorMsg("bad_request", "mediaId unknown; upload it first via POST /media", s.Id));
                    return;
                }
                if (!IsE2e(s.Text))
                {
                    if (s.Text.StartsWith("media|", StringComparison.Ordinal))
                    {
                        if (s.MediaId is null)
                        {
                            c.Send(new ErrorMsg("bad_request", "custom sticker needs mediaId", s.Id));
                            return;
                        }
                    }
                    else if (s.Text.StartsWith("bqb|", StringComparison.Ordinal))
                    {
                        if (s.MediaId is not null)
                        {
                            c.Send(new ErrorMsg("bad_request", "library sticker carries no mediaId", s.Id));
                            return;
                        }
                    }
                    else
                    {
                        c.Send(new ErrorMsg("bad_request", "sticker text must be bqb|<path>|<w>|<h> or media|<mediaId>|<w>|<h>", s.Id));
                        return;
                    }
                }
                break;
            }
            case "location":
            {
                // Text = "lat,lng|accuracyMeters|address|live" (live = 1 while a live share runs; later positions arrive as edits of this message) or an e2e blob of it.
                if (string.IsNullOrWhiteSpace(s.Text) || s.Text.Length > MaxLocationChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", $"location text must be 1-{MaxLocationChars} chars and carry no media", s.Id));
                    return;
                }
                if (!IsE2e(s.Text) && !IsLocationText(s.Text))
                {
                    c.Send(new ErrorMsg("bad_request", "location text must be lat,lng|accuracy|address|live", s.Id));
                    return;
                }
                break;
            }
            case "pat":
            {
                // Optional short text (可 e2e); clients show "你拍了拍 xx".
                if (s.Text?.Length > MaxPatChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", $"pat text must be at most {MaxPatChars} chars and carry no media", s.Id));
                    return;
                }
                break;
            }
            case "card":
            {
                // Assistant broadcasts (cron weather, reminders): first line is the title, the rest markdown body.
                if (string.IsNullOrWhiteSpace(s.Text) || s.Text.Length > MaxTextChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", $"card text must be 1-{MaxTextChars} chars and carry no media", s.Id));
                    return;
                }
                break;
            }
            case "call":
                // call log entry, text = "<voice|video>:<answered|missed|declined|cancelled>:<seconds>" (or an e2e blob of it)
                if (string.IsNullOrWhiteSpace(s.Text) || s.Text.Length > MaxCallLogChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "call log needs a short text and no media", s.Id));
                    return;
                }
                break;
            case "react":
            {
                // Text = "<target client id>|<emoji>|<1 add / 0 remove>" or an e2e blob of that. Clients aggregate and hide it.
                if (string.IsNullOrEmpty(s.Text) || s.Text.Length > MaxReactChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "react needs a short text and no media", s.Id));
                    return;
                }
                if (!IsE2e(s.Text))
                {
                    var parts = s.Text.Split('|');
                    if (parts.Length != 3 || parts[0].Length is 0 or > 64 || parts[1].Length is 0 or > 16 || parts[2] is not ("1" or "0"))
                    {
                        c.Send(new ErrorMsg("bad_request", "react text must be <id>|<emoji>|<1|0>", s.Id));
                        return;
                    }
                }
                break;
            }
            case "edit":
            {
                // Text = "<target client id>|<new text>". Only the author may edit, only text and location messages (a live share moves its bubble).
                var bar = s.Text?.IndexOf('|') ?? -1;
                if (s.Text is null || bar is < 8 or > 64 || bar == s.Text.Length - 1 || s.Text.Length - bar - 1 > MaxTextChars || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "edit text must be <id>|<new text>", s.Id));
                    return;
                }
                if (messages.Get(s.Id) is null)
                {
                    var target = s.Text[..bar];
                    var newText = s.Text[(bar + 1)..];
                    if (!messages.Edit(target, uid, newText, Now()))
                    {
                        c.Send(new ErrorMsg("not_found", "message not found or not editable", s.Id));
                        return;
                    }
                    // One edit record per message: the previous edit row by this author is dropped (seq gap is expected).
                    messages.SquashEdits(uid, target);
                    log.LogInformation("message {Target} edited by {User}", target, uid);
                }
                break;
            }
            case "ttl":
            {
                // Text = seconds; 0 turns disappearing messages off. Applies to messages sent from now on.
                if (!long.TryParse(s.Text, out var secs) || secs < 0 || secs > MaxTtlSeconds || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "ttl text must be seconds (0 = off, max one year)", s.Id));
                    return;
                }
                if (messages.Get(s.Id) is null)
                {
                    store.Set(TtlKey, secs.ToString());
                    log.LogInformation("disappearing messages set to {Secs}s by {User}", secs, uid);
                }
                break;
            }
            case "recall":
            {
                // 1.7 撤回: text = client id of my own message, at most two minutes old. The row stays as kind "recall"
                // (text and media dropped) so both phones show the placeholder; the control message carries the id.
                if (string.IsNullOrEmpty(s.Text) || s.Text.Length > 64 || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "recall needs the target message id in text", s.Id));
                    return;
                }
                var target = messages.Get(s.Text);
                if (target is null || target.From != uid || !ContentKinds.Contains(target.Kind) || target.Kind is "call" or "pat")
                {
                    c.Send(new ErrorMsg("bad_request", "you can only recall your own messages", s.Id));
                    return;
                }
                if (Now() - target.Ts > RecallWindowMs)
                {
                    c.Send(new ErrorMsg("bad_request", "messages can be recalled within 2 minutes only", s.Id));
                    return;
                }
                if (messages.Get(s.Id) is null)
                {
                    var recalledMedia = messages.Recall(s.Text);
                    if (recalledMedia is not null) RemoveMedia([recalledMedia]);
                    log.LogInformation("message {Target} recalled by {User}", s.Text, uid);
                }
                break;
            }
            case "del":
            {
                // Text = client id of the message to remove for both users. The assistant may only remove its own.
                if (string.IsNullOrEmpty(s.Text) || s.Text.Length > 64 || s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "del needs the target message id in text", s.Id));
                    return;
                }
                if (isBot && messages.Get(s.Text) is { } victim && !UserRepo.IsBot(victim.From))
                {
                    c.Send(new ErrorMsg("bad_request", "the assistant can only delete its own messages", s.Id));
                    return;
                }
                if (messages.Get(s.Id) is null)
                {
                    var (deleted, mediaId) = messages.Delete(s.Text);
                    if (deleted)
                    {
                        log.LogInformation("message {Target} deleted by {User}", s.Text, uid);
                        if (mediaId is not null) RemoveMedia([mediaId]);
                    }
                }
                break;
            }
            case "clear":
            {
                if (s.MediaId is not null)
                {
                    c.Send(new ErrorMsg("bad_request", "clear carries no media", s.Id));
                    return;
                }
                if (messages.Get(s.Id) is null)
                {
                    var last = messages.LastSeq();
                    var mediaIds = messages.DeleteAll();
                    log.LogInformation("history cleared by {User}: up to seq {Seq}, {Media} media refs", uid, last, mediaIds.Count);
                    RemoveMedia(mediaIds);
                    s = s with { Text = last.ToString() };
                }
                break;
            }
            default:
                // Every kind in KindPolicy has a case above; this only guards against the two drifting apart.
                c.Send(new ErrorMsg("bad_request", "kind must be " + KindList, s.Id));
                return;
        }

        if (toBot)
        {
            if (IsE2e(s.Text))
            {
                c.Send(new ErrorMsg("bad_request", "messages to the assistant must be plaintext", s.Id));
                return;
            }
            if (s.MediaId is not null && MediaLooksEncrypted(s.MediaId))
            {
                c.Send(new ErrorMsg("bad_request", "media for the assistant must be uploaded unencrypted", s.Id));
                return;
            }
        }

        var now = Now();
        var dest = toBot ? "bot" : null;
        // View-once only means something for image / video between humans; anything else is stored without it.
        bool? once = s.Once is true && s.Kind is ("image" or "video") && !isBot && !toBot ? true : null;
        var (msg, created) = messages.Insert(uid, s.Id, s.Kind, s.Text, s.MediaId, now, s.ReplyTo, ExpiresAt(s.Kind, isBot, toBot, now), dest, once);
        if (msg.From != uid)
        {
            c.Send(new ErrorMsg("conflict", "message id already used by the other user", s.Id));
            return;
        }
        c.Send(new MsgAck(msg.Id, msg.Seq, msg.Ts));
        if (!created) return;
        BroadcastNew(msg, exceptConnId: c.Id);
        NotifyOffline(msg, s.Notice, uid, isBot);
    }

    /// <summary>When the recipient does not have the app open, hand a short text to their push channel. The picture is not included.</summary>
    private void NotifyOffline(ChatMessage msg, string? notice, long from, bool isBot)
    {
        if (!ContentKinds.Contains(msg.Kind)) return;
        var body = push.Preview(msg.Kind, msg.Text, notice, msg.Once == true, msg.Id);
        var title = users.Get(from)?.Name ?? "消息";
        if (isBot)
        {
            foreach (var u in users.List())
                if (!IsOnline(u.Id)) push.Offer(u.Id, title, body);
            return;
        }
        if (users.PeerOf(from) is { } peer && !IsOnline(peer.Id))
            push.Offer(peer.Id, title, body);
    }

    /// <summary>Disappearing-message deadline for a new message, null when it keeps. With bot_ttl on, the assistant's messages and those addressed to it expire like everything else.</summary>
    private long? ExpiresAt(string kind, bool isBot, bool toBot, long now)
    {
        if (!ContentKinds.Contains(kind) || !(BotTtl || (!isBot && !toBot))) return null;
        var ttl = TtlSeconds;
        return ttl > 0 ? now + ttl * 1000 : null;
    }

    /// <summary>
    /// The assistant's proactive card over HTTP (cron delivery without a live socket): stored as user 0 and broadcast
    /// like a socket-sent card, disappearing-message rule included. Returns the stored message.
    /// </summary>
    public ChatMessage PostCard(string text)
    {
        var now = Now();
        var (msg, created) = messages.Insert(UserRepo.BotId, "card-" + Guid.NewGuid().ToString("N"), "card", text, null, now, null, ExpiresAt("card", isBot: true, toBot: false, now));
        if (created)
        {
            BroadcastNew(msg, exceptConnId: "");
            NotifyOffline(msg, null, UserRepo.BotId, isBot: true);
        }
        return msg;
    }

    /// <summary>Humans renamed the assistant; every connection hears the new name.</summary>
    public bool RenameBot(string name)
    {
        name = name.Trim();
        if (name.Length is < 1 or > 24) return false;
        if (users.FindByName(name) is { } clash && clash.Id != UserRepo.BotId) return false;
        if (!users.SetBotName(name)) return false;
        NotifyBot();
        return true;
    }

    public BotInfo BotSnapshot()
    {
        var bot = users.Get(UserRepo.BotId);
        return new BotInfo(UserRepo.BotId, bot?.Name ?? "助手", IsConnected(UserRepo.BotId), BotTtl);
    }

    private void NotifyBot()
    {
        var b = BotSnapshot();
        var frame = new Bot(b.Id, b.Name, b.Online, b.Ttl);
        foreach (var c in _conns.Values)
            if (!UserRepo.IsBot(c.UserId ?? -1)) c.Send(frame);
    }

    /// <summary>Drops the given media (plus thumbnails) when no remaining message references them, files included. Returns how many rows went.</summary>
    private int RemoveMedia(List<string> ids)
    {
        if (ids.Count == 0) return 0;
        var removed = 0;
        try
        {
            foreach (var id in media.DeleteUnreferenced(ids))
            {
                removed++;
                var path = Path.Combine(settings.MediaDir, id[..2], id);
                try { if (File.Exists(path)) File.Delete(path); }
                catch (IOException ex) { log.LogWarning("could not delete media file {Id}: {Err}", id[..12], ex.Message); }
            }
        }
        catch (Exception ex)
        {
            log.LogWarning(ex, "media cleanup failed");
        }
        return removed;
    }

    /// <summary>Connection left the foreground or closed: record last seen; tell the peer if no other foreground connection remains. Returns whether a presence frame went out.</summary>
    private bool SetInactive(WsConnection c, long uid)
    {
        c.Active = false;
        users.SetLastSeen(uid, Now());
        if (IsOnline(uid)) return false;
        SendPresence(uid);
        return true;
    }

    /// <summary>Current presence of a human (online flag, lastSeen when offline, last battery report) to the peer.</summary>
    private void SendPresence(long uid)
    {
        var online = IsOnline(uid);
        var power = _power.GetValueOrDefault(uid);
        SendToHumans(new Presence(uid, online, online ? null : users.LastSeen(uid), power.Battery, power.Charging), exceptUserId: uid);
    }

    /// <summary>
    /// Relay one frame of an assistant call to the other party. Returns true when the call id belongs to an
    /// assistant call (including one that just ended), so the caller must not also fan it out to both humans.
    /// A frame from someone who is not a party is swallowed.
    /// </summary>
    private bool RouteBot(string callId, long uid, bool isBot, WsMessage stamped, bool closing)
    {
        if (string.IsNullOrEmpty(callId)) return false;
        if (_botCalls.TryGetValue(callId, out var call))
        {
            var party = isBot || uid == call.HumanId;
            if (party)
            {
                if (isBot) SendToUser(call.HumanId, stamped);
                else SendToBot(stamped);
                if (closing && _botCalls.TryRemove(callId, out _)) RememberClosed(callId);
            }
            return true;
        }
        if (_closedBotCalls.ContainsKey(callId)) return true;
        return false;
    }

    private void RememberClosed(string callId)
    {
        var now = Now();
        _closedBotCalls[callId] = now;
        if (_closedBotCalls.Count <= 32) return;
        foreach (var kv in _closedBotCalls)
            if (now - kv.Value > 120_000) _closedBotCalls.TryRemove(kv.Key, out _);
    }

    /// <summary>The socket that owned an assistant call went away: tell the other side, once no other socket of that user remains.</summary>
    private void DropBotCalls(long uid, bool isBot)
    {
        if (isBot)
        {
            if (IsConnected(UserRepo.BotId)) return;
            foreach (var kv in _botCalls.ToArray())
            {
                if (!_botCalls.TryRemove(kv.Key, out var call)) continue;
                RememberClosed(kv.Key);
                SendToUser(call.HumanId, new CallHangup(kv.Key, "unavailable", uid));
            }
            return;
        }
        if (IsConnected(uid)) return;
        foreach (var kv in _botCalls.ToArray())
        {
            if (kv.Value.HumanId != uid) continue;
            if (!_botCalls.TryRemove(kv.Key, out _)) continue;
            RememberClosed(kv.Key);
            SendToBot(new CallHangup(kv.Key, "normal", uid));
        }
    }

    private void SendToBot(WsMessage msg)
    {
        foreach (var c in _conns.Values)
            if (UserRepo.IsBot(c.UserId ?? -1)) c.Send(msg);
    }

    private void SendToUser(long uid, WsMessage msg)
    {
        foreach (var c in _conns.Values)
            if (c.UserId == uid) c.Send(msg);
    }

    private void SendToHumans(WsMessage msg, long? exceptUserId = null)
    {
        foreach (var c in _conns.Values)
            if (!UserRepo.IsBot(c.UserId ?? -1) && c.UserId != exceptUserId) c.Send(msg);
    }

    private void BroadcastNew(ChatMessage msg, string exceptConnId)
    {
        var frame = new MsgNew(msg);
        var forBot = string.Equals(msg.To, "bot", StringComparison.Ordinal) && !IsE2e(msg.Text);
        // del / clear reach the assistant whatever the target so it can drop its local media cache.
        var control = msg.Kind is "del" or "clear" or "recall";
        foreach (var c in _conns.Values)
        {
            if (c.Id == exceptConnId) continue;
            if (UserRepo.IsBot(c.UserId ?? -1))
            {
                if (forBot || control || UserRepo.IsBot(msg.From)) c.Send(frame);
                continue;
            }
            c.Send(frame);
        }
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}