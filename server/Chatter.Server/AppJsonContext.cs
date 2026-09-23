using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;
using Chatter.Server.Protocol;

namespace Chatter.Server;

public sealed record HealthResponse(string Status, string Version, long UptimeSeconds, int Connections, int Schema);

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    AllowOutOfOrderMetadataProperties = true)]
[JsonSerializable(typeof(WsMessage))]
[JsonSerializable(typeof(HealthResponse))]
[JsonSerializable(typeof(MediaInfo))]
[JsonSerializable(typeof(LoginRequest))]
[JsonSerializable(typeof(LoginResponse))]
[JsonSerializable(typeof(ErrorResponse))]
[JsonSerializable(typeof(KeyRequest))]
[JsonSerializable(typeof(KeyInfo))]
[JsonSerializable(typeof(PasswordRequest))]
[JsonSerializable(typeof(PasswordResponse))]
[JsonSerializable(typeof(DeviceInfo))]
[JsonSerializable(typeof(List<DeviceInfo>))]
[JsonSerializable(typeof(BotNameRequest))]
[JsonSerializable(typeof(BotInfo))]
[JsonSerializable(typeof(BotTtlRequest))]
[JsonSerializable(typeof(SharedItem))]
[JsonSerializable(typeof(List<SharedItem>))]
[JsonSerializable(typeof(SharedValueRequest))]
[JsonSerializable(typeof(CardRequest))]
[JsonSerializable(typeof(CardResponse))]
[JsonSerializable(typeof(FailResponse))]
[JsonSerializable(typeof(Features))]
[JsonSerializable(typeof(Poi))]
[JsonSerializable(typeof(List<Poi>))]
[JsonSerializable(typeof(RegeoResponse))]
[JsonSerializable(typeof(SttResponse))]
[JsonSerializable(typeof(PushPref))]
[JsonSerializable(typeof(ServerChanPayload))]
[JsonSerializable(typeof(MeowPayload))]
[JsonSerializable(typeof(PushKeyUp))]
[JsonSerializable(typeof(List<PushKeyUp>))]
[JsonSerializable(typeof(PushKeysRequest))]
public sealed partial class AppJsonContext : JsonSerializerContext;

/// <summary>Shared serializer options: source-generated metadata plus no escaping of non-ASCII (Chinese text stays readable and compact).</summary>
public static class Json
{
    public static readonly JsonSerializerOptions Options = new(AppJsonContext.Default.Options)
    {
        TypeInfoResolver = AppJsonContext.Default,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static readonly JsonTypeInfo<WsMessage> WsMessage = Info<WsMessage>();
    public static readonly JsonTypeInfo<MediaInfo> MediaInfo = Info<MediaInfo>();
    public static readonly JsonTypeInfo<LoginResponse> LoginResponse = Info<LoginResponse>();
    public static readonly JsonTypeInfo<ErrorResponse> ErrorResponse = Info<ErrorResponse>();
    public static readonly JsonTypeInfo<HealthResponse> HealthResponse = Info<HealthResponse>();
    public static readonly JsonTypeInfo<KeyInfo> KeyInfo = Info<KeyInfo>();
    public static readonly JsonTypeInfo<PasswordResponse> PasswordResponse = Info<PasswordResponse>();
    public static readonly JsonTypeInfo<List<DeviceInfo>> DeviceList = Info<List<DeviceInfo>>();
    public static readonly JsonTypeInfo<BotInfo> BotInfo = Info<BotInfo>();
    public static readonly JsonTypeInfo<SharedItem> SharedItem = Info<SharedItem>();
    public static readonly JsonTypeInfo<List<SharedItem>> SharedList = Info<List<SharedItem>>();
    public static readonly JsonTypeInfo<CardResponse> CardResponse = Info<CardResponse>();
    public static readonly JsonTypeInfo<FailResponse> FailResponse = Info<FailResponse>();
    public static readonly JsonTypeInfo<List<Poi>> PoiList = Info<List<Poi>>();
    public static readonly JsonTypeInfo<RegeoResponse> RegeoResponse = Info<RegeoResponse>();
    public static readonly JsonTypeInfo<SttResponse> SttResponse = Info<SttResponse>();
    public static readonly JsonTypeInfo<PushPref> PushPref = Info<PushPref>();
    public static readonly JsonTypeInfo<ServerChanPayload> ServerChanPayload = Info<ServerChanPayload>();
    public static readonly JsonTypeInfo<MeowPayload> MeowPayload = Info<MeowPayload>();

    private static JsonTypeInfo<T> Info<T>() => (JsonTypeInfo<T>)Options.GetTypeInfo(typeof(T));
}
