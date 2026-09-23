using Chatter.Server.Protocol;

namespace Chatter.Server;

public static class Http
{
    /// <summary>The server only listens on loopback behind nginx, so X-Real-IP is trustworthy.</summary>
    public static string ClientIp(HttpContext ctx) =>
        ctx.Request.Headers["X-Real-IP"].FirstOrDefault()
        ?? ctx.Connection.RemoteIpAddress?.ToString()
        ?? "?";

    public static IResult Error(int status, string error) =>
        Results.Json(new ErrorResponse(error), Json.ErrorResponse, statusCode: status);

    /// <summary>Like <see cref="Error"/> plus a stable code the client can switch on.</summary>
    public static IResult Fail(int status, string code, string error) =>
        Results.Json(new FailResponse(error, code), Json.FailResponse, statusCode: status);
}
