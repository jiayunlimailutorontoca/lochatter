using System.Text.Json.Serialization;

namespace Chatter.Server.Protocol;

/// <summary>Base of every WebSocket frame. The "t" property selects the concrete type.</summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "t")]
[JsonDerivedType(typeof(Hello), "hello")]
[JsonDerivedType(typeof(Ping), "ping")]
[JsonDerivedType(typeof(Pong), "pong")]
[JsonDerivedType(typeof(Echo), "echo")]
[JsonDerivedType(typeof(ErrorMsg), "error")]
[JsonDerivedType(typeof(MsgSend), "msg.send")]
[JsonDerivedType(typeof(MsgAck), "msg.ack")]
[JsonDerivedType(typeof(MsgNew), "msg.new")]
[JsonDerivedType(typeof(Sync), "sync")]
[JsonDerivedType(typeof(MsgBatch), "msg.batch")]
[JsonDerivedType(typeof(ReadMark), "read")]
[JsonDerivedType(typeof(Typing), "typing")]
[JsonDerivedType(typeof(Presence), "presence")]
[JsonDerivedType(typeof(Active), "active")]
[JsonDerivedType(typeof(CallInvite), "call.invite")]
[JsonDerivedType(typeof(CallAccept), "call.accept")]
[JsonDerivedType(typeof(CallReject), "call.reject")]
[JsonDerivedType(typeof(CallHangup), "call.hangup")]
[JsonDerivedType(typeof(CallSdp), "call.sdp")]
[JsonDerivedType(typeof(CallIce), "call.ice")]
[JsonDerivedType(typeof(CallMedia), "call.media")]
[JsonDerivedType(typeof(CallEmoji), "call.emoji")]
[JsonDerivedType(typeof(CallCaption), "call.caption")]
[JsonDerivedType(typeof(TurnGet), "turn.get")]
[JsonDerivedType(typeof(TurnCreds), "turn.creds")]
[JsonDerivedType(typeof(Keys), "keys")]
[JsonDerivedType(typeof(Bot), "bot")]
[JsonDerivedType(typeof(Shared), "shared")]
public abstract record WsMessage;

// ---- shared shapes ----

public sealed record UserInfo(long Id, string Name);

/// <summary>Battery / Charging are the peer's last report over the Active frame (in memory only; absent until they report).</summary>
public sealed record PeerInfo(long Id, string Name, bool Online, long? LastSeen, string? PubKey = null, int? Battery = null, bool? Charging = null);

/// <summary>The in-chat assistant (Hermes). Not a third human; does not occupy MaxUsers. Ttl: its messages follow disappearing-message expiry.</summary>
public sealed record BotInfo(long Id, string Name, bool Online, bool Ttl = true);

/// <summary>One entry of the two-person shared key/value store (values are opaque to the server; may be e2e blobs).</summary>
public sealed record SharedItem(string Key, string Value, long UpdatedAt);

public sealed record MediaInfo(string Id, string Mime, long Size, int? Width, int? Height, int? DurationMs, string? ThumbId, string? Name = null);

/// <summary>Snapshot of the quoted message, taken by the server when the reply is stored.</summary>
public sealed record ReplyInfo(string Id, long From, string Text);

/// <summary>
/// Kind: text | image | audio | video | file | call | sticker | pat | card | location | react | edit | ttl | del | clear.
/// "del" (Text = id of the removed message), "clear" (Text = last seq wiped), "react" and "edit" are control
/// entries in the same sequence so offline clients apply them during sync; clients hide them.
/// "ttl" (Text = seconds, 0 = off) switches disappearing messages and is shown as a system line.
/// "sticker" (Text = "bqb|path|w|h" or "media|mediaId|w|h"), "pat" (humans only) and "card" (assistant only; first line is the title) are 1.3 content kinds.
/// "location" (1.5, humans only; Text = "lat,lng|accuracyMeters|address|live", live = 1 while a live share runs) may be edited like text so a live share updates one bubble.
/// Once: view-once image / video between humans; stored verbatim and echoed.
/// Text may be an end-to-end encrypted blob ("e2e:" prefix); the server never inspects those.
/// </summary>
public sealed record ChatMessage(long Seq, string Id, long From, string Kind, string? Text, MediaInfo? Media, long Ts, ReplyInfo? Reply = null, long? EditedAt = null, long? ExpiresAt = null, string? To = null, bool? Once = null);

// ---- session frames ----

/// <summary>First frame after an authenticated connection. Users is present only for the assistant connection; Shared only for humans.</summary>
public sealed record Hello(
    string ConnId, string Version, long ServerTs,
    UserInfo User, PeerInfo? Peer, long LastSeq, long MyReadUpto, long PeerReadUpto,
    string? MyPubKey = null, long TtlSeconds = 0, BotInfo? Bot = null,
    UserInfo[]? Users = null, SharedItem[]? Shared = null, Features? Features = null) : WsMessage;

public sealed record Ping(long? Ts = null) : WsMessage;

public sealed record Pong(long? Ts, long ServerTs) : WsMessage;

public sealed record Echo(string? Text = null, long? N = null) : WsMessage;

/// <summary>Ref carries the client message id when the error concerns a specific msg.send.</summary>
public sealed record ErrorMsg(string Code, string Message, string? Ref = null) : WsMessage;

// ---- messaging ----

/// <summary>
/// C→S. Id is a client-generated UUID used for de-duplication on resend.
/// Kind text|image|audio|file|call as before; kind "del" removes the message whose id is in Text for both users;
/// kind "clear" wipes the whole history for both users. ReplyTo quotes another message by id.
/// To = "bot" addresses the in-chat assistant (must be plaintext; the bot never sees e2e: blobs).
/// Once = view-once; honoured only for image / video from a human, ignored otherwise.
/// Notice is a short plaintext preview for offline phone push only. It is not stored and not relayed.
/// </summary>
public sealed record MsgSend(string Id, string Kind, string? Text = null, string? MediaId = null, string? ReplyTo = null, string? To = null, bool? Once = null, string? Notice = null) : WsMessage;

/// <summary>S→C (sender only). Echoes the client id with the assigned sequence number.</summary>
public sealed record MsgAck(string Id, long Seq, long Ts) : WsMessage;

/// <summary>S→C (everyone except the sending connection).</summary>
public sealed record MsgNew(ChatMessage Msg) : WsMessage;

/// <summary>C→S. Forward: messages with seq &gt; Since. Backward (history paging): Before set → the newest Limit messages with seq &lt; Before.</summary>
public sealed record Sync(long Since, int? Limit = null, long? Before = null) : WsMessage;

/// <summary>Messages in ascending seq order. Before echoes a backward request; HasMore then means older messages exist.</summary>
public sealed record MsgBatch(ChatMessage[] Messages, bool HasMore, long? Before = null) : WsMessage;

/// <summary>C→S: "I have read up to Upto". S→C: forwarded to the peer with User set.</summary>
public sealed record ReadMark(long Upto, long? User = null) : WsMessage;

/// <summary>C→S: empty. S→C: forwarded to the peer with User set.</summary>
public sealed record Typing(long? User = null) : WsMessage;

/// <summary>S→C. Battery / Charging: the user's last report, sent whenever they change (see Active).</summary>
public sealed record Presence(long User, bool Online, long? LastSeen, int? Battery = null, bool? Charging = null) : WsMessage;

/// <summary>
/// C→S. Fg=true while the app is in the foreground. The peer is "online" when at least one of their connections is foreground.
/// Battery (0-100) / Charging are optional and remembered per user in memory.
/// </summary>
public sealed record Active(bool Fg, int? Battery = null, bool? Charging = null) : WsMessage;

// ---- calls: the server only relays; From is filled in by the server ----

/// <summary>Bot = true addresses the assistant instead of the other human. The server then relays this call only between the caller and the assistant.</summary>
public sealed record CallInvite(string CallId, bool Video, long? From = null, long? Ts = null, bool? Bot = null) : WsMessage;

public sealed record CallAccept(string CallId, long? From = null) : WsMessage;

/// <summary>Reason: busy | declined | timeout | offline (offline is generated by the server).</summary>
public sealed record CallReject(string CallId, string? Reason = null, long? From = null) : WsMessage;

public sealed record CallHangup(string CallId, string? Reason = null, long? From = null) : WsMessage;

/// <summary>Type: offer | answer.</summary>
public sealed record CallSdp(string CallId, string Type, string Sdp, long? From = null) : WsMessage;

public sealed record CallIce(string CallId, string Candidate, string? SdpMid = null, int? SdpMLineIndex = null, long? From = null) : WsMessage;

/// <summary>Sender toggled its camera / microphone mid-call; relayed so the peer can show a placeholder. Screen (1.5): the video track is a screen share.</summary>
public sealed record CallMedia(string CallId, bool Video, bool Audio = true, bool? Screen = null, long? From = null) : WsMessage;

/// <summary>In-call emoji overlay (Emoji 1-16 chars); relayed to the peer with From filled in. Humans only.</summary>
public sealed record CallEmoji(string CallId, string Emoji, long? From = null) : WsMessage;

/// <summary>
/// Live subtitle for an assistant call. Who is "user" or "assistant"; State is "partial" or "final".
/// Phase, when set, is listening | thinking | speaking | idle | error. Relayed only to the other party
/// of that call. A frame for a call that already ended is ignored.
/// </summary>
public sealed record CallCaption(string CallId, string Who, string? Text = null, string? State = null, string? Phase = null, long? Ts = null, long? From = null) : WsMessage;

public sealed record TurnGet : WsMessage;

/// <summary>S→C: a user published a new end-to-end public key (sent to every connection).</summary>
public sealed record Keys(long User, string PubKey, long UpdatedAt) : WsMessage;

/// <summary>Time-limited TURN credentials (coturn use-auth-secret). Empty username means STUN only.</summary>
public sealed record TurnCreds(string[] Urls, string Username, string Credential, long TtlSeconds) : WsMessage;

// ---- HTTP payloads ----

public sealed record LoginRequest(string Name, string Password, string? Device = null);

public sealed record LoginResponse(string Token, UserInfo User, UserInfo? Peer);

public sealed record ErrorResponse(string Error);

/// <summary>Error with a machine-readable code (1.6 helpers: stt_unavailable, geo_unavailable, geo_upstream, rate_limited, too_large).</summary>
public sealed record FailResponse(string Error, string Code);

/// <summary>Server-side helpers advertised in hello (1.6): POST /stt, /geo/*, /tiles/ (docs/protocol-1.6.md).</summary>
/// <summary>TileDatum: "wgs84" (OpenStreetMap at /tiles/) or "gcj02" (Amap at /amap-tiles/, CHATTER_TILES=amap); null when tiles are off.</summary>
public sealed record Features(bool Stt, bool Geo, bool Tiles, string? TileDatum = null);

/// <summary>One place from Amap, converted to WGS-84. Distance in metres from the query point (0 for text search without a point).</summary>
public sealed record Poi(string Name, string Address, double Lat, double Lng, int Distance, string Type);

/// <summary>GET /geo/regeo: the address of a point, the name of the place under it when known, and nearby places.</summary>
public sealed record RegeoResponse(string Address, string? Name, List<Poi> Pois);

/// <summary>POST /stt.</summary>
public sealed record SttResponse(string Text, string? Language, long? DurationMs);

public sealed record KeyRequest(string PubKey);

public sealed record KeyInfo(long UserId, string PubKey, long UpdatedAt);

public sealed record PasswordRequest(string OldPassword, string NewPassword, bool LogoutOthers = false);

public sealed record PasswordResponse(int Revoked);

public sealed record DeviceInfo(long Id, string? Device, long CreatedAt, long? LastSeen, bool Current);

public sealed record BotNameRequest(string Name);

/// <summary>POST /bot/ttl: whether the assistant's messages (and those addressed to it) follow disappearing-message expiry.</summary>
public sealed record BotTtlRequest(bool Enabled);

/// <summary>
/// GET/PUT /push. Provider is off, serverchan, or meow. Secret is that account's SendKey or MeoW nickname.
/// IntervalSec is the minimum gap between pushes. Style is hint (只说有消息), text (带上内容), or count (只报条数).
/// </summary>
public sealed record PushPref(string Provider, string Secret, int IntervalSec = 60, string Style = "text");

/// <summary>JSON body for Server酱³.</summary>
public sealed record ServerChanPayload(string Title, string Desp, string Short);

/// <summary>JSON body for MeoW.</summary>
public sealed record MeowPayload(string Title, string Msg);

/// <summary>One derived session key the phone uploads so the server can open a ciphertext for a push. s/r are the epoch pair; 0,0 is the legacy key.</summary>
public sealed record PushKeyUp(int S, int R, string Key);

/// <summary>POST /push/keys.</summary>
public sealed record PushKeysRequest(List<PushKeyUp> Keys);

/// <summary>PUT /shared/{key} body.</summary>
public sealed record SharedValueRequest(string Value);

/// <summary>POST /bot/card body (assistant token only): a proactive card without a live socket; first line is the title.</summary>
public sealed record CardRequest(string Text);

/// <summary>POST /bot/card response: the stored card's client id ("card-…") and sequence number.</summary>
public sealed record CardResponse(string Id, long Seq);

/// <summary>S→C: assistant came online, went offline, was renamed, or its ttl setting changed. Also present on hello.</summary>
public sealed record Bot(long Id, string Name, bool Online, bool Ttl = true) : WsMessage;

/// <summary>S→C (every human connection, the writer included): a shared key/value entry changed.</summary>
public sealed record Shared(string Key, string Value, long UpdatedAt) : WsMessage;

