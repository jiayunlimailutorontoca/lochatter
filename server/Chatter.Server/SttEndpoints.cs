using System.Net.Http.Headers;
using System.Text.Json;
using Chatter.Server.Auth;
using Chatter.Server.Protocol;
using Microsoft.AspNetCore.Http.Features;

namespace Chatter.Server;

/// <summary>
/// POST /stt (1.6): optional cloud speech-to-text (docs/protocol-1.6.md §3). The phone runs SenseVoice
/// on device unless the user fills this URL in settings. The server only
/// proxies the multipart upload to an OpenAI-compatible /v1/audio/transcriptions backend (CHATTER_STT_URL, by default
/// the C# Native AOT sherpa-onnx SenseVoice process installed by deploy/stt-setup.sh) and hands the text back.
/// </summary>
public static class SttEndpoints
{
    public const long MaxBytes = 20L * 1024 * 1024;
    private static readonly UserRateLimiter Limiter = new(perMinute: 20, burst: 20);
    private static readonly HttpClient Client = new(new SocketsHttpHandler { PooledConnectionLifetime = TimeSpan.FromMinutes(10) }) { Timeout = TimeSpan.FromSeconds(60) };

    public static void Map(WebApplication app)
    {
        var log = app.Logger;
        app.MapPost("/stt", async (HttpContext ctx, AuthService auth, Capabilities caps) =>
        {
            var user = auth.Authenticate(ctx);
            if (user is null) return Http.Error(401, "unauthorized");
            if (!caps.Stt) return Http.Fail(503, "stt_unavailable", "服务器没有配置语音转文字");
            if (ctx.Request.ContentLength is > MaxBytes) return Http.Fail(413, "too_large", "语音最多 20 MB");
            if (!ctx.Request.HasFormContentType) return Http.Fail(400, "bad_request", "需要 multipart/form-data，字段 file");
            if (!Limiter.TryTake(user.Id)) return Http.Fail(429, "rate_limited", "转文字太频繁，稍后再试");
            var sizeFeature = ctx.Features.Get<IHttpMaxRequestBodySizeFeature>();
            if (sizeFeature is { IsReadOnly: false }) sizeFeature.MaxRequestBodySize = MaxBytes;

            IFormCollection form;
            try { form = await ctx.Request.ReadFormAsync(ctx.RequestAborted); }
            catch (BadHttpRequestException) { return Http.Fail(413, "too_large", "语音最多 20 MB"); }
            catch (InvalidDataException) { return Http.Fail(400, "bad_request", "表单无法读取"); }
            var file = form.Files.GetFile("file");
            if (file is null || file.Length == 0) return Http.Fail(400, "bad_request", "缺少 file");
            if (file.Length > MaxBytes) return Http.Fail(413, "too_large", "语音最多 20 MB");
            var language = form["language"].ToString().Trim();
            if (language.Length == 0) language = "zh";

            using var content = new MultipartFormDataContent();
            await using var stream = file.OpenReadStream();
            var part = new StreamContent(stream);
            part.Headers.ContentType = MediaTypeHeaderValue.TryParse(file.ContentType, out var mt) ? mt : new MediaTypeHeaderValue("application/octet-stream");
            content.Add(part, "file", string.IsNullOrWhiteSpace(file.FileName) ? "voice.m4a" : Path.GetFileName(file.FileName));
            content.Add(new StringContent(caps.SttModel), "model");
            content.Add(new StringContent(language), "language");
            content.Add(new StringContent("json"), "response_format");
            using var req = new HttpRequestMessage(HttpMethod.Post, caps.SttUrl) { Content = content };
            if (caps.SttKey is not null) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", caps.SttKey);

            string body;
            int status;
            try
            {
                using var resp = await Client.SendAsync(req, ctx.RequestAborted);
                status = (int)resp.StatusCode;
                body = await resp.Content.ReadAsStringAsync(ctx.RequestAborted);
            }
            catch (OperationCanceledException) when (!ctx.RequestAborted.IsCancellationRequested)
            {
                log.LogWarning("stt backend timeout");
                return Http.Fail(502, "stt_upstream", "转文字超时");
            }
            catch (HttpRequestException e)
            {
                log.LogWarning("stt backend unreachable: {Err}", e.Message);
                return Http.Fail(502, "stt_upstream", "转文字服务没有响应");
            }
            if (status is < 200 or >= 300)
            {
                log.LogWarning("stt backend {Status}: {Body}", status, body.Length > 200 ? body[..200] : body);
                return Http.Fail(502, "stt_upstream", $"转文字服务出错（{status}）");
            }
            try
            {
                using var doc = JsonDocument.Parse(body);
                var root = doc.RootElement;
                var text = root.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() ?? "" : "";
                var lang = root.TryGetProperty("language", out var l) && l.ValueKind == JsonValueKind.String ? l.GetString() : language;
                long? duration = root.TryGetProperty("durationMs", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetInt64() : null;
                return Results.Json(new SttResponse(text.Trim(), lang, duration), Json.SttResponse);
            }
            catch (JsonException)
            {
                return Http.Fail(502, "stt_upstream", "转文字服务返回了无法解析的内容");
            }
        });
    }
}
