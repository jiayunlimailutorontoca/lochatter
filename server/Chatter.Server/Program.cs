using System.Net.WebSockets;
using Chatter.Server;
using Chatter.Server.Auth;
using Chatter.Server.Protocol;
using Chatter.Server.Storage;
using Chatter.Server.Ws;

// Any command-line argument switches to admin CLI mode (see Cli.cs). The web host uses env vars only.
if (args.Length > 0) return Cli.Run(args);

var builder = WebApplication.CreateSlimBuilder(args);

builder.Logging.AddSimpleConsole(o =>
{
    o.SingleLine = true;
    o.TimestampFormat = "HH:mm:ss ";
});

builder.Services.ConfigureHttpJsonOptions(o =>
{
    o.SerializerOptions.TypeInfoResolverChain.Insert(0, AppJsonContext.Default);
    o.SerializerOptions.Encoder = Json.Options.Encoder;
});

var settings = AppSettings.Load(builder.Configuration);
builder.Services.AddSingleton(settings);
var caps = Capabilities.Load(builder.Configuration);
builder.Services.AddSingleton(caps);
builder.Services.AddSingleton<Chatter.Server.Geo.GeoService>();
builder.Services.AddSingleton<Db>();
builder.Services.AddSingleton<UserRepo>();
builder.Services.AddSingleton<TokenRepo>();
builder.Services.AddSingleton<MessageRepo>();
builder.Services.AddSingleton<MediaRepo>();
builder.Services.AddSingleton<KeyRepo>();
builder.Services.AddSingleton<SettingsRepo>();
builder.Services.AddSingleton<PushRepo>();
builder.Services.AddSingleton<PushService>();
builder.Services.AddSingleton<SharedRepo>();
builder.Services.AddSingleton<AuthService>();
builder.Services.AddSingleton<WebTickets>();
builder.Services.AddSingleton<TurnService>();
builder.Services.AddSingleton<Hub>();

builder.WebHost.ConfigureKestrel(k =>
{
    k.AddServerHeader = false;
    k.Limits.MaxRequestBodySize = settings.MaxUploadBytes > 0 ? settings.MaxUploadBytes : null; // null = unlimited
});

var app = builder.Build();

app.Services.GetRequiredService<Db>().Init();
app.Services.GetRequiredService<UserRepo>().EnsureBot();
app.Services.GetRequiredService<PushService>().AppOpen = app.Services.GetRequiredService<Hub>().IsOnline;

app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

app.MapGet("/healthz", (Hub hub, Db db) =>
    Results.Json(new HealthResponse("ok", AppInfo.Version, AppInfo.UptimeSeconds, hub.ConnectionCount, db.SchemaVersion), Json.HealthResponse));

app.MapPost("/auth/login", (LoginRequest req, HttpContext ctx, AuthService auth) =>
{
    var ip = Http.ClientIp(ctx);
    if (auth.IsLocked(ip)) return Http.Error(429, "too many failed logins; try again in a few minutes");
    if (string.IsNullOrWhiteSpace(req.Name) || string.IsNullOrEmpty(req.Password)) return Http.Error(400, "name and password required");
    var res = auth.Login(req.Name, req.Password, req.Device?[..Math.Min(req.Device.Length, 64)], ip);
    return res is null ? Http.Error(401, "bad credentials") : Results.Json(res, Json.LoginResponse);
});

app.MapPost("/auth/web-ticket", (HttpContext ctx, WebTickets tickets) =>
{
    if (!tickets.AllowCreate(Http.ClientIp(ctx))) return Http.Error(429, "too many tickets");
    var (id, exp) = tickets.Create();
    return Results.Json(new WebTicketCreated(id, exp), Json.WebTicketCreated);
});

app.MapGet("/auth/web-ticket/{id}", (string id, WebTickets tickets) =>
{
    if (!IsTicketId(id)) return Http.Error(400, "bad ticket");
    var (status, box) = tickets.Poll(id);
    return Results.Json(new WebTicketView(status, box), Json.WebTicketView);
});

app.MapPost("/auth/web-ticket/{id}", (string id, WebBoxRequest req, HttpContext ctx, AuthService auth, WebTickets tickets) =>
{
    if (auth.Authenticate(ctx) is null) return Http.Error(401, "unauthorized");
    if (!IsTicketId(id)) return Http.Error(400, "bad ticket");
    var box = req.Box?.Trim() ?? "";
    if (box.Length is < 32 or > WebTickets.MaxBoxChars || !IsBox(box)) return Http.Error(400, "bad box");
    return tickets.Approve(id, box) ? Results.NoContent() : Http.Error(409, "ticket is not pending");
});

app.MapPost("/auth/web-token", (HttpContext ctx, AuthService auth, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    var (token, revoked) = auth.IssueWebToken(user.Id);
    foreach (var hash in revoked) hub.KickToken(hash);
    app.Logger.LogInformation("web token issued for {User} (replaced {N})", user.Name, revoked.Count);
    return Results.Json(new WebTokenResponse(token), Json.WebTokenResponse);
});

app.MapGet("/ws", async (HttpContext ctx, Hub hub, AuthService auth) =>
{
    if (!ctx.WebSockets.IsWebSocketRequest)
    {
        ctx.Response.StatusCode = StatusCodes.Status426UpgradeRequired;
        await ctx.Response.WriteAsync("websocket upgrade required");
        return;
    }
    var user = auth.Authenticate(ctx);
    if (user is null)
    {
        ctx.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await ctx.Response.WriteAsync("unauthorized");
        return;
    }

    using var ws = await ctx.WebSockets.AcceptWebSocketAsync(new WebSocketAcceptContext
    {
        KeepAliveInterval = TimeSpan.FromSeconds(30),
        KeepAliveTimeout = TimeSpan.FromSeconds(60),
    });
    await hub.HandleAsync(ws, user, AuthService.TokenHashOf(ctx), Http.ClientIp(ctx), ctx.RequestAborted);
});

// ---- end-to-end encryption: each user publishes one public key; the peer fetches it (and hears about changes over the socket) ----
app.MapPost("/keys", (KeyRequest req, HttpContext ctx, AuthService auth, KeyRepo keys, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    // 1.5 publishes a signed epoch-key chain here (forward secrecy); the server stores it opaquely as before.
    if (string.IsNullOrWhiteSpace(req.PubKey) || req.PubKey.Length > 4096) return Http.Error(400, "pubKey required (base64, at most 4096 chars)");
    var ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    keys.Set(user.Id, req.PubKey.Trim(), ts);
    hub.NotifyKey(user.Id, req.PubKey.Trim(), ts);
    app.Logger.LogInformation("e2e key published by {User}", user.Name);
    return Results.Json(new KeyInfo(user.Id, req.PubKey.Trim(), ts), Json.KeyInfo);
});

app.MapGet("/keys/{userId:long}", (long userId, HttpContext ctx, AuthService auth, KeyRepo keys) =>
{
    if (auth.Authenticate(ctx) is null) return Http.Error(401, "unauthorized");
    var k = keys.Get(userId);
    return k is null ? Http.Error(404, "no key") : Results.Json(k, Json.KeyInfo);
});

// ---- account: password change and device (token) management ----
app.MapPost("/auth/password", (PasswordRequest req, HttpContext ctx, AuthService auth, TokenRepo tokens, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (string.IsNullOrEmpty(req.NewPassword) || req.NewPassword.Length < 6 || req.NewPassword.Length > 128) return Http.Error(400, "new password must be 6-128 chars");
    if (!auth.ChangePassword(user.Id, req.OldPassword ?? "", req.NewPassword)) return Http.Error(403, "wrong password");
    var revoked = 0;
    if (req.LogoutOthers && AuthService.TokenHashOf(ctx) is { } mine)
    {
        foreach (var h in tokens.RevokeOthers(user.Id, mine)) { auth.Evict(h); hub.KickToken(h); revoked++; }
    }
    return Results.Json(new PasswordResponse(revoked), Json.PasswordResponse);
});

app.MapGet("/auth/devices", (HttpContext ctx, AuthService auth, TokenRepo tokens) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    var mine = AuthService.TokenHashOf(ctx);
    var list = tokens.List(user.Id).Select(t => new DeviceInfo(t.Id, t.Device, t.CreatedAt, t.LastSeen, t.Hash == mine)).ToList();
    return Results.Json(list, Json.DeviceList);
});

app.MapDelete("/auth/devices/{id:long}", (long id, HttpContext ctx, AuthService auth, TokenRepo tokens, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    var hash = tokens.Revoke(user.Id, id);
    if (hash is null) return Http.Error(404, "no such device");
    auth.Evict(hash);
    var kicked = hub.KickToken(hash);
    app.Logger.LogInformation("device {Id} revoked by {User} ({Kicked} socket(s) closed)", id, user.Name, kicked);
    return Results.NoContent();
});

// ---- in-chat assistant: name shared by both users; the bot itself logs in with a token from `chatterctl bot token` ----
app.MapGet("/bot", (HttpContext ctx, AuthService auth, Hub hub) =>
{
    if (auth.Authenticate(ctx) is null) return Http.Error(401, "unauthorized");
    return Results.Json(hub.BotSnapshot(), Json.BotInfo);
});

app.MapPost("/bot/name", (BotNameRequest req, HttpContext ctx, AuthService auth, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (UserRepo.IsBot(user.Id)) return Http.Error(403, "the assistant cannot rename itself");
    var name = (req.Name ?? "").Trim();
    if (name.Length is < 1 or > 24) return Http.Error(400, "name must be 1-24 chars");
    if (!hub.RenameBot(name)) return Http.Error(409, "name unavailable");
    app.Logger.LogInformation("assistant renamed to '{Name}' by {User}", name, user.Name);
    return Results.Json(hub.BotSnapshot(), Json.BotInfo);
});

app.MapPost("/bot/ttl", (BotTtlRequest req, HttpContext ctx, AuthService auth, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (UserRepo.IsBot(user.Id)) return Http.Error(403, "the assistant cannot change its own expiry");
    hub.SetBotTtl(req.Enabled);
    app.Logger.LogInformation("assistant disappearing messages {State} by {User}", req.Enabled ? "on" : "off", user.Name);
    return Results.Json(hub.BotSnapshot(), Json.BotInfo);
});

// The assistant's proactive cards over HTTP (cron delivery without keeping a socket): stored and broadcast exactly like a socket-sent card.
app.MapPost("/bot/card", (CardRequest req, HttpContext ctx, AuthService auth, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (!UserRepo.IsBot(user.Id)) return Http.Error(403, "only the assistant can post cards");
    var text = req.Text ?? "";
    if (string.IsNullOrWhiteSpace(text) || text.Length > Hub.MaxTextChars) return Http.Error(400, $"text must be 1-{Hub.MaxTextChars} chars");
    var msg = hub.PostCard(text);
    app.Logger.LogInformation("card {Id} posted by the assistant over HTTP ({Len} chars)", msg.Id, text.Length);
    return Results.Json(new CardResponse(msg.Id, msg.Seq), Json.CardResponse);
});

// ---- offline phone push, one preference per account (Server酱³ or MeoW) ----
app.MapGet("/push", (HttpContext ctx, AuthService auth, PushRepo push) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    var row = push.Get(user.Id);
    var body = row is null
        ? new PushPref("off", "", PushService.DefaultIntervalSec, "text")
        : new PushPref(row.Provider, row.Secret, row.IntervalSec, string.IsNullOrEmpty(row.Style) ? "text" : row.Style);
    return Results.Json(body, Json.PushPref);
});

app.MapPut("/push", (PushPref req, HttpContext ctx, AuthService auth, PushRepo push) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (UserRepo.IsBot(user.Id)) return Http.Error(403, "the assistant has no phone push");
    if (!PushService.TryNormalize(req.Provider, req.Secret, req.IntervalSec, req.Style, out var norm, out var error)) return Http.Error(400, error);
    push.Set(user.Id, norm.Provider, norm.Secret, norm.IntervalSec, norm.Style, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    app.Logger.LogInformation("push pref {Provider} {Style} every {Sec}s by {User}", norm.Provider, norm.Style, norm.IntervalSec, user.Name);
    return Results.Json(norm, Json.PushPref);
});

app.MapPost("/push/keys", (PushKeysRequest req, HttpContext ctx, AuthService auth, PushRepo push) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (UserRepo.IsBot(user.Id)) return Http.Error(403, "the assistant has no chat keys");
    var list = req.Keys;
    if (list is null || list.Count is < 1 or > 80) return Http.Error(400, "1-80 keys");
    var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    var n = 0;
    foreach (var k in list)
    {
        if (k.S < 0 || k.R < 0 || k.S > 100000 || k.R > 100000) return Http.Error(400, "bad epoch");
        byte[] raw;
        try { raw = Convert.FromBase64String(k.Key ?? ""); }
        catch (FormatException) { return Http.Error(400, "key must be base64"); }
        if (raw.Length != 32) return Http.Error(400, "key must be 32 bytes");
        push.PutKey(k.S, k.R, Convert.ToBase64String(raw), now);
        n++;
    }
    app.Logger.LogInformation("push keys {Count} from {User}", n, user.Name);
    return Results.NoContent();
});

// ---- shared key/value store (assistant quick commands, anniversaries, sticker favourites): humans write, everyone reads ----
app.MapGet("/shared", (HttpContext ctx, AuthService auth, SharedRepo shared) =>
{
    if (auth.Authenticate(ctx) is null) return Http.Error(401, "unauthorized");
    return Results.Json(shared.All(), Json.SharedList);
});

app.MapPut("/shared/{key}", (string key, SharedValueRequest req, HttpContext ctx, AuthService auth, Hub hub) =>
{
    var user = auth.Authenticate(ctx);
    if (user is null) return Http.Error(401, "unauthorized");
    if (UserRepo.IsBot(user.Id)) return Http.Error(403, "the assistant cannot write shared values");
    if (!IsSharedKey(key)) return Http.Error(400, "key must match ^[a-z0-9_-]{1,32}$");
    if (!OwnsProfileKey(key, user.Id)) return Http.Error(403, "you can only change your own profile");
    if (req.Value is null || req.Value.Length > 65536) return Http.Error(400, "value required (at most 65536 chars)");
    var item = hub.SetShared(key, req.Value, user.Id);
    app.Logger.LogInformation("shared '{Key}' set by {User} ({Len} chars)", key, user.Name, req.Value.Length);
    return Results.Json(item, Json.SharedItem);
});

MediaEndpoints.Map(app);
Chatter.Server.Geo.GeoEndpoints.Map(app);
SttEndpoints.Map(app);
app.Logger.LogInformation("features: stt={Stt} geo={Geo} tiles={Tiles}{Bad}", caps.Stt, caps.Geo, caps.Tiles, caps.SttUrlInvalid ? " (CHATTER_STT_URL is not an absolute http url, stt off)" : "");

// disappearing messages: background sweep for the lifetime of the host
var hubSvc = app.Services.GetRequiredService<Hub>();
var sweepCts = new CancellationTokenSource();
app.Lifetime.ApplicationStopping.Register(() => sweepCts.Cancel());
_ = Task.Run(() => hubSvc.SweepLoopAsync(sweepCts.Token));

app.Logger.LogInformation("chatter-server {Version} starting, data dir {DataDir}, turn {Turn}",
    AppInfo.Version, settings.DataDir,
    settings.TurnHost is null ? "off" : settings.TurnSecret is null ? settings.TurnHost + " (stun only)" : settings.TurnHost);
app.Run();
return 0;

// ^[a-z0-9_-]{1,32}$ without a Regex (Native AOT friendly, and the shape is trivial)
static bool IsSharedKey(string key)
{
    if (key.Length is < 1 or > 32) return false;
    foreach (var ch in key)
        if (!(char.IsAsciiLetterLower(ch) || char.IsAsciiDigit(ch) || ch is '_' or '-')) return false;
    return true;
}

/// <summary>av-&lt;userId&gt; and sg-&lt;userId&gt; are that account's avatar and signature. Other keys stay writable by either person.</summary>
static bool OwnsProfileKey(string key, long userId)
{
    if (!key.StartsWith("av-", StringComparison.Ordinal) && !key.StartsWith("sg-", StringComparison.Ordinal)) return true;
    return long.TryParse(key.AsSpan(3), out var id) && id == userId && id > 0;
}

static bool IsTicketId(string id)
{
    if (id.Length != 22) return false;
    foreach (var ch in id)
        if (!(char.IsAsciiLetterOrDigit(ch) || ch is '-' or '_')) return false;
    return true;
}

static bool IsBox(string box)
{
    foreach (var ch in box)
        if (!(char.IsAsciiLetterOrDigit(ch) || ch is '+' or '/' or '=')) return false;
    return true;
}
