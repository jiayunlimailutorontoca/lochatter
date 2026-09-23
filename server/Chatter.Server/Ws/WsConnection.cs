using System.Buffers;
using System.Net.WebSockets;
using System.Text.Json;
using System.Threading.Channels;
using Chatter.Server.Protocol;

namespace Chatter.Server.Ws;

/// <summary>One WebSocket client. All sends go through a single-reader outbox so frames never interleave.</summary>
public sealed class WsConnection
{
    public const int MaxMessageBytes = 256 * 1024;

    private readonly WebSocket _ws;
    private readonly ILogger _log;
    private readonly Channel<byte[]> _outbox = Channel.CreateBounded<byte[]>(
        new BoundedChannelOptions(512) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private Task _sendLoop = Task.CompletedTask;

    public string Id { get; } = Guid.NewGuid().ToString("N")[..8];
    public string RemoteIp { get; }
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.UtcNow;
    public long? UserId { get; set; }
    /// <summary>Hash of the device token this socket authenticated with, so a revoked device can be disconnected.</summary>
    public string? TokenHash { get; set; }

    /// <summary>True while the client app is in the foreground (see the Active frame).</summary>
    public volatile bool Active;

    /// <summary>
    /// Rate-limit state, owned by Hub and touched only from the receive loop: tokens left in the frame and msg.send
    /// buckets, the time of the last refill, and when the current frame flood began (0 = none).
    /// </summary>
    public double FrameTokens, SendTokens;
    public long RateStamp, FloodSince;

    public WsConnection(WebSocket ws, string remoteIp, ILogger log)
    {
        _ws = ws;
        RemoteIp = remoteIp;
        _log = log;
    }

    /// <summary>Tears the socket down from another thread (device revoked). The receive loop then unwinds normally.</summary>
    public void Kill()
    {
        try { _ws.Abort(); } catch { }
    }

    /// <summary>Queues a frame. Returns false (and drops it) when the client is too slow to drain 512 frames.</summary>
    public bool Send(WsMessage msg)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(msg, Json.WsMessage);
        if (_outbox.Writer.TryWrite(bytes)) return true;
        _log.LogWarning("ws {Id} outbox full, dropping frame", Id);
        return false;
    }

    public async Task RunAsync(Func<WsConnection, WsMessage, ValueTask> handler, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _sendLoop = SendLoopAsync(cts.Token);
        try
        {
            await ReceiveLoopAsync(handler, cts.Token);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning(ex, "ws {Id} handler failed", Id);
            await CloseAsync(WebSocketCloseStatus.InternalServerError, "internal error", ct);
        }
        finally
        {
            _outbox.Writer.TryComplete();
            cts.Cancel();
            try { await _sendLoop; } catch { /* teardown */ }
        }
    }

    private async Task SendLoopAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var bytes in _outbox.Reader.ReadAllAsync(ct))
                await _ws.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, ct);
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException ex) { _log.LogDebug("ws {Id} send ended: {Err}", Id, ex.WebSocketErrorCode); }
    }

    private async Task ReceiveLoopAsync(Func<WsConnection, WsMessage, ValueTask> handler, CancellationToken ct)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        var acc = new ArrayBufferWriter<byte>(4 * 1024);
        try
        {
            while (_ws.State == WebSocketState.Open)
            {
                acc.ResetWrittenCount();
                ValueWebSocketReceiveResult r;
                do
                {
                    r = await _ws.ReceiveAsync(buffer.AsMemory(), ct);
                    if (r.MessageType == WebSocketMessageType.Close)
                    {
                        await CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", ct);
                        return;
                    }
                    if (acc.WrittenCount + r.Count > MaxMessageBytes)
                    {
                        await CloseAsync(WebSocketCloseStatus.MessageTooBig, "max 256 KiB per message", ct);
                        return;
                    }
                    acc.Write(buffer.AsSpan(0, r.Count));
                } while (!r.EndOfMessage);

                if (r.MessageType != WebSocketMessageType.Text)
                {
                    Send(new ErrorMsg("bad_frame", "binary frames are not supported; use HTTP /media"));
                    continue;
                }

                WsMessage? msg;
                try
                {
                    msg = JsonSerializer.Deserialize(acc.WrittenSpan, Json.WsMessage);
                }
                catch (Exception ex) when (ex is JsonException or NotSupportedException)
                {
                    Send(new ErrorMsg("bad_json", ex.Message));
                    continue;
                }
                if (msg is null)
                {
                    Send(new ErrorMsg("bad_json", "empty message"));
                    continue;
                }

                await handler(this, msg);
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException ex) { _log.LogDebug("ws {Id} receive ended: {Err}", Id, ex.WebSocketErrorCode); }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private async Task CloseAsync(WebSocketCloseStatus status, string reason, CancellationToken ct)
    {
        _outbox.Writer.TryComplete();
        try { await _sendLoop; } catch { /* drained or aborted */ }
        if (_ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            try { await _ws.CloseAsync(status, reason, timeout.Token); }
            catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { }
        }
    }
}
