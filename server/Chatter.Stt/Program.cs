using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;

namespace Chatter.Stt;

// OpenAI-shaped transcription endpoint. chatter-server proxies POST /stt here.
// systemd socket activation owns 127.0.0.1:5090; after STT_IDLE_SEC with no request
// the process exits 0 so the SenseVoice mapping is released. The next connection starts it again.
static class Program
{
    public static int Main(string[] args)
    {
        var modelDir = Environment.GetEnvironmentVariable("STT_MODEL_DIR");
        if (string.IsNullOrWhiteSpace(modelDir))
            modelDir = "/opt/stt/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";
        var threads = EnvInt("STT_THREADS", 2);
        var idleSeconds = EnvInt("STT_IDLE_SEC", 600);
        var port = EnvInt("STT_PORT", 5090);
        if (port is < 1 or > 65535) port = 5090;

        Engine engine;
        var loadStarted = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            engine = Engine.Load(modelDir, threads);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("stt: " + e.Message);
            return 1;
        }
        var loadMs = loadStarted.ElapsedMilliseconds;

        var builder = WebApplication.CreateSlimBuilder(args);
        builder.Logging.AddSimpleConsole(o =>
        {
            o.SingleLine = true;
            o.TimestampFormat = "HH:mm:ss ";
        });
        builder.Services.ConfigureHttpJsonOptions(o =>
        {
            o.SerializerOptions.TypeInfoResolverChain.Insert(0, SttJsonContext.Default);
            o.SerializerOptions.Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping;
        });

        var activated = !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("LISTEN_FDS"));
        builder.WebHost.ConfigureKestrel(k =>
        {
            k.AddServerHeader = false;
            k.Limits.MaxRequestBodySize = Audio.MaxUploadBytes;
            // LISTEN_FDS is set only when systemd handed us the socket. Otherwise bind loopback for a manual run.
            if (activated) k.UseSystemd();
            else k.ListenLocalhost(port);
        });

        var activity = new ActivityGate();
        builder.Services.AddSingleton(engine);
        builder.Services.AddSingleton(activity);

        var app = builder.Build();
        app.Logger.LogInformation(
            "sense-voice ready in {LoadMs} ms ({Threads} threads, idle exit {Idle}s, {Bind})",
            loadMs, threads, idleSeconds, activated ? "systemd socket" : $"127.0.0.1:{port}");

        if (idleSeconds > 0)
            _ = WatchIdle(activity, app.Lifetime, app.Logger, idleSeconds);

        app.MapGet("/healthz", (ActivityGate gate) =>
        {
            if (!gate.TryEnter()) return Problem(503, "shutting down");
            try { return Results.Json(new HealthBody("ok", "sense-voice"), SttJson.Health); }
            finally { gate.Leave(); }
        });

        app.MapPost("/v1/audio/transcriptions", async (HttpContext ctx, Engine recognizer, ActivityGate gate) =>
        {
            if (!gate.TryEnter()) return Problem(503, "shutting down");
            try { return await Transcribe(ctx, recognizer, app.Logger); }
            finally { gate.Leave(); }
        });

        try
        {
            app.Run();
            return 0;
        }
        finally
        {
            engine.Dispose();
        }
    }

    static async Task<IResult> Transcribe(HttpContext ctx, Engine recognizer, ILogger log)
    {
        if (ctx.Request.ContentLength is > Audio.MaxUploadBytes) return Problem(413, "audio larger than 20 MB");
        if (!ctx.Request.HasFormContentType) return Problem(400, "expected multipart/form-data");
        var size = ctx.Features.Get<IHttpMaxRequestBodySizeFeature>();
        if (size is { IsReadOnly: false }) size.MaxRequestBodySize = Audio.MaxUploadBytes;

        IFormCollection form;
        try { form = await ctx.Request.ReadFormAsync(ctx.RequestAborted); }
        catch (Microsoft.AspNetCore.Http.BadHttpRequestException) { return Problem(413, "audio larger than 20 MB"); }
        catch (InvalidDataException) { return Problem(400, "cannot read form"); }

        var file = form.Files.GetFile("file");
        if (file is null || file.Length == 0) return Problem(400, "missing file");
        if (file.Length > Audio.MaxUploadBytes) return Problem(413, "audio larger than 20 MB");
        var language = form["language"].ToString().Trim();
        if (language.Length == 0) language = "auto";
        if (language.Length > 32) language = language[..32];

        var path = Path.Combine(Path.GetTempPath(), "stt-" + Guid.NewGuid().ToString("n") + SafeSuffix(file.FileName));
        try
        {
            await using (var fs = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, FileOptions.Asynchronous))
                await file.CopyToAsync(fs, ctx.RequestAborted);

            var started = System.Diagnostics.Stopwatch.StartNew();
            var audio = await Audio.DecodeFile(path, ctx.RequestAborted);
            if (audio.Samples is null) return Problem(audio.Status, audio.Detail ?? "cannot decode audio");

            string text;
            try { text = recognizer.Decode(audio.Samples); }
            catch (InvalidOperationException e)
            {
                log.LogWarning("decode failed: {Err}", e.Message);
                return Problem(500, "decode failed");
            }
            var duration = Audio.DurationMs(audio.Samples.Length);
            log.LogInformation("transcribed {AudioMs} ms of audio in {ElapsedMs} ms", duration, started.ElapsedMilliseconds);
            return Results.Json(new TranscriptBody(text, language, duration), SttJson.Transcript);
        }
        catch (OperationCanceledException) when (ctx.RequestAborted.IsCancellationRequested)
        {
            return Results.StatusCode(499);
        }
        finally
        {
            try { File.Delete(path); } catch (IOException) { }
        }
    }

    static async Task WatchIdle(ActivityGate activity, IHostApplicationLifetime life, ILogger log, int idleSeconds)
    {
        var idle = TimeSpan.FromSeconds(idleSeconds);
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(15));
        try
        {
            while (await timer.WaitForNextTickAsync(life.ApplicationStopping))
            {
                if (!activity.TryBeginShutdown(idle)) continue;
                log.LogInformation("idle for {Seconds}s, exiting", idleSeconds);
                life.StopApplication();
                return;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception e) { log.LogError(e, "idle watch failed"); }
    }

    static IResult Problem(int status, string detail) =>
        Results.Json(new ErrorBody(detail), SttJson.Error, statusCode: status);

    static int EnvInt(string name, int fallback) =>
        int.TryParse(Environment.GetEnvironmentVariable(name), out var n) ? n : fallback;

    static string SafeSuffix(string? fileName)
    {
        var ext = Path.GetExtension(fileName ?? "");
        if (ext.Length is < 2 or > 8) return ".bin";
        for (var i = 1; i < ext.Length; i++)
        {
            var c = ext[i];
            if (c is not (>= '0' and <= '9' or >= 'a' and <= 'z' or >= 'A' and <= 'Z')) return ".bin";
        }
        return ext;
    }
}

/// <summary>Counts in-flight requests so an idle exit cannot start while a decode is running.</summary>
sealed class ActivityGate
{
    private readonly object gate = new();
    private int busy;
    private bool stopping;
    private long lastTicks = DateTime.UtcNow.Ticks;

    public bool TryEnter()
    {
        lock (gate)
        {
            if (stopping) return false;
            busy++;
            lastTicks = DateTime.UtcNow.Ticks;
            return true;
        }
    }

    public void Leave()
    {
        lock (gate)
        {
            if (busy > 0) busy--;
            lastTicks = DateTime.UtcNow.Ticks;
        }
    }

    public bool TryBeginShutdown(TimeSpan idle)
    {
        lock (gate)
        {
            if (stopping || busy > 0) return false;
            if (DateTime.UtcNow.Ticks - lastTicks < idle.Ticks) return false;
            stopping = true;
            return true;
        }
    }
}

sealed record HealthBody(string Status, string Model);
sealed record TranscriptBody(string Text, string Language, long DurationMs);
sealed record ErrorBody(string Detail);

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(HealthBody))]
[JsonSerializable(typeof(TranscriptBody))]
[JsonSerializable(typeof(ErrorBody))]
sealed partial class SttJsonContext : JsonSerializerContext;

static class SttJson
{
    private static readonly JsonSerializerOptions Options = new(SttJsonContext.Default.Options)
    {
        TypeInfoResolver = SttJsonContext.Default,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static readonly JsonTypeInfo<HealthBody> Health = Info<HealthBody>();
    public static readonly JsonTypeInfo<TranscriptBody> Transcript = Info<TranscriptBody>();
    public static readonly JsonTypeInfo<ErrorBody> Error = Info<ErrorBody>();

    private static JsonTypeInfo<T> Info<T>() => (JsonTypeInfo<T>)Options.GetTypeInfo(typeof(T))!;
}
