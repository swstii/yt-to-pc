using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace YtToPc;

sealed class DriveFolder
{
    public required string Id { get; init; }
    public required string Name { get; init; }
}

static class SecretBox
{
    public static string Protect(string value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(bytes);
    }

    public static string Unprotect(string value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(value), null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch
        {
            return "";
        }
    }
}

sealed class DriveClient
{
    private const string Scope = "https://www.googleapis.com/auth/drive";
    private readonly AppSettings _settings;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(30) };
    private string _accessToken = "";
    private DateTime _accessExpires;

    public DriveClient(AppSettings settings) => _settings = settings;

    public async Task<string> AccessTokenAsync()
    {
        if (_accessToken.Length > 0 && DateTime.UtcNow < _accessExpires) return _accessToken;
        var refresh = SecretBox.Unprotect(_settings.GoogleRefreshToken);
        if (refresh.Length == 0) throw new InvalidOperationException("Sign in to Google Drive from Queue folder.");
        var body = new Dictionary<string, string>
        {
            ["client_id"] = _settings.GoogleClientId.Trim(),
            ["refresh_token"] = refresh,
            ["grant_type"] = "refresh_token",
        };
        if (_settings.GoogleClientSecret.Trim().Length > 0)
            body["client_secret"] = _settings.GoogleClientSecret.Trim();
        using var response = await _http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(body));
        var json = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(GoogleError(json, "Google sign-in expired. Choose the queue folder again."));
        var token = JsonDocument.Parse(json).RootElement;
        _accessToken = token.GetProperty("access_token").GetString() ?? "";
        var seconds = token.TryGetProperty("expires_in", out var expires) ? expires.GetInt32() : 3000;
        _accessExpires = DateTime.UtcNow.AddSeconds(Math.Max(60, seconds - 60));
        return _accessToken;
    }

    public async Task<IReadOnlyList<DriveFolder>> ListFoldersAsync(string parentId)
    {
        var query = "mimeType = 'application/vnd.google-apps.folder' and '" + parentId + "' in parents and trashed = false";
        var url = "https://www.googleapis.com/drive/v3/files?pageSize=100&orderBy=name&fields=files(id,name)&q=" +
                  Uri.EscapeDataString(query);
        var json = await GetAsync(url);
        var files = JsonDocument.Parse(json).RootElement.GetProperty("files");
        var folders = new List<DriveFolder>();
        foreach (var file in files.EnumerateArray())
            folders.Add(new DriveFolder { Id = file.GetProperty("id").GetString() ?? "", Name = file.GetProperty("name").GetString() ?? "" });
        return folders;
    }

    public async Task<string?> ReadNamedAsync(string parentId, string name)
    {
        var id = await FindAsync(parentId, name);
        if (id == null) return null;
        return await GetAsync("https://www.googleapis.com/drive/v3/files/" + Uri.EscapeDataString(id) + "?alt=media");
    }

    public async Task WriteNamedAsync(string parentId, string name, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        var id = await FindAsync(parentId, name);
        if (id == null)
        {
            var boundary = "yt_to_pc";
            var meta = JsonSerializer.Serialize(new { name, parents = new[] { parentId } });
            var payload = Encoding.UTF8.GetBytes(
                "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta +
                "\r\n--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n");
            var end = Encoding.UTF8.GetBytes("\r\n--" + boundary + "--");
            var body = payload.Concat(bytes).Concat(end).ToArray();
            await SendAsync(HttpMethod.Post,
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                body,
                "multipart/related; boundary=" + boundary);
            return;
        }
        await SendAsync(HttpMethod.Patch,
            "https://www.googleapis.com/upload/drive/v3/files/" + Uri.EscapeDataString(id) + "?uploadType=media",
            bytes,
            "application/json; charset=UTF-8");
    }

    private async Task<string?> FindAsync(string parentId, string name)
    {
        var query = "name = '" + name.Replace("'", "\\'") + "' and '" + parentId + "' in parents and trashed = false";
        var url = "https://www.googleapis.com/drive/v3/files?pageSize=1&fields=files(id)&q=" + Uri.EscapeDataString(query);
        var files = JsonDocument.Parse(await GetAsync(url)).RootElement.GetProperty("files");
        return files.GetArrayLength() == 0 ? null : files[0].GetProperty("id").GetString();
    }

    private async Task<string> GetAsync(string url)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await AccessTokenAsync());
        using var response = await _http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(GoogleError(text, "Google Drive returned " + (int)response.StatusCode));
        return text;
    }

    private async Task SendAsync(HttpMethod method, string url, byte[] body, string contentType)
    {
        using var request = new HttpRequestMessage(method, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await AccessTokenAsync());
        request.Content = new ByteArrayContent(body);
        request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        using var response = await _http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(GoogleError(text, "Google Drive returned " + (int)response.StatusCode));
    }

    public static async Task<DriveFolder?> PickFolderAsync(AppSettings settings)
    {
        if (settings.GoogleClientId.Trim().Length == 0)
            throw new InvalidOperationException("Add a Desktop OAuth client first.");
        var port = FreePort();
        var redirect = "http://127.0.0.1:" + port + "/";
        using var listener = new HttpListener();
        listener.Prefixes.Add(redirect);
        listener.Start();
        var client = new DriveClient(settings);
        if (SecretBox.Unprotect(settings.GoogleRefreshToken).Length == 0)
            await AuthorizeAsync(settings, listener, redirect, client).ConfigureAwait(false);
        else
            Process.Start(new ProcessStartInfo(redirect) { UseShellExecute = true });
        var chosen = await BrowseAsync(listener, client).ConfigureAwait(false);
        listener.Stop();
        return chosen;
    }

    private static async Task AuthorizeAsync(AppSettings settings, HttpListener listener, string redirect, DriveClient client)
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var url = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent" +
                  "&client_id=" + Uri.EscapeDataString(settings.GoogleClientId.Trim()) +
                  "&redirect_uri=" + Uri.EscapeDataString(redirect) +
                  "&scope=" + Uri.EscapeDataString(Scope) +
                  "&code_challenge=" + challenge +
                  "&code_challenge_method=S256";
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        var context = await listener.GetContextAsync().WaitAsync(TimeSpan.FromMinutes(5));
        var code = QueryValue(context.Request.Url?.Query, "code");
        if (string.IsNullOrWhiteSpace(code))
        {
            await WriteHtmlAsync(context, "<p>Google did not return a sign-in code. Close this tab and try again.</p>");
            throw new InvalidOperationException("Google sign-in was cancelled.");
        }
        var body = new Dictionary<string, string>
        {
            ["client_id"] = settings.GoogleClientId.Trim(),
            ["code"] = code,
            ["code_verifier"] = verifier,
            ["grant_type"] = "authorization_code",
            ["redirect_uri"] = redirect,
        };
        if (settings.GoogleClientSecret.Trim().Length > 0)
            body["client_secret"] = settings.GoogleClientSecret.Trim();
        using var response = await client._http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(body));
        var json = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            await WriteHtmlAsync(context, "<p>Google refused the sign-in. Close this tab and check the Desktop client.</p>");
            throw new InvalidOperationException(GoogleError(json, "Google sign-in failed."));
        }
        var token = JsonDocument.Parse(json).RootElement;
        var refresh = token.TryGetProperty("refresh_token", out var refreshValue) ? refreshValue.GetString() ?? "" : "";
        if (refresh.Length > 0) settings.GoogleRefreshToken = SecretBox.Protect(refresh);
        settings.Save();
        client._accessToken = token.GetProperty("access_token").GetString() ?? "";
        client._accessExpires = DateTime.UtcNow.AddMinutes(40);
        context.Response.Redirect(redirect + "?open=root");
        context.Response.Close();
    }

    private static async Task<DriveFolder?> BrowseAsync(HttpListener listener, DriveClient client)
    {
        while (true)
        {
            var context = await listener.GetContextAsync().WaitAsync(TimeSpan.FromMinutes(5));
            var queryText = context.Request.Url?.Query;
            var path = context.Request.Url?.AbsolutePath ?? "/";
            if (path.Contains("cancel", StringComparison.OrdinalIgnoreCase))
            {
                await WriteHtmlAsync(context, "<p>Closed. You can return to YT to PC.</p>");
                return null;
            }
            var chosenId = QueryValue(queryText, "use");
            if (!string.IsNullOrWhiteSpace(chosenId))
            {
                var chosenName = QueryValue(queryText, "name") ?? "Drive folder";
                await WriteHtmlAsync(context, "<p>Using <b>" + WebUtility.HtmlEncode(chosenName) + "</b>. You can close this tab.</p>");
                return new DriveFolder { Id = chosenId, Name = chosenName };
            }
            var parent = QueryValue(queryText, "open");
            if (string.IsNullOrWhiteSpace(parent)) parent = "root";
            var parentName = QueryValue(queryText, "title");
            if (string.IsNullOrWhiteSpace(parentName)) parentName = "My Drive";
            var folders = await client.ListFoldersAsync(parent);
            await WriteHtmlAsync(context, FolderPage(parent, parentName, folders));
        }
    }

    private static string FolderPage(string parent, string parentName, IReadOnlyList<DriveFolder> folders)
    {
        var rows = new StringBuilder();
        foreach (var folder in folders)
        {
            var open = "/?open=" + Uri.EscapeDataString(folder.Id) + "&title=" + Uri.EscapeDataString(folder.Name);
            rows.Append("<a class=\"row\" href=\"").Append(open).Append("\">").Append(WebUtility.HtmlEncode(folder.Name)).Append("</a>");
        }
        if (folders.Count == 0) rows.Append("<p class=\"empty\">No folders inside this one.</p>");
        var use = "/?use=" + Uri.EscapeDataString(parent) + "&name=" + Uri.EscapeDataString(parentName);
        var page = new StringBuilder();
        page.Append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Choose the queue folder</title><style>");
        page.Append("body{margin:0;background:#12140f;color:#f4f6ec;font:16px Segoe UI,sans-serif}");
        page.Append("main{max-width:560px;margin:32px auto;padding:0 20px}h1{font-size:22px}p{color:#c9cdb8}");
        page.Append("a.row,a.use{display:block;text-decoration:none;color:#f4f6ec;background:#1c1f16;border-radius:12px;padding:14px 16px;margin:8px 0}");
        page.Append("a.use{background:#e4f27a;color:#1a1c10;text-align:center}</style></head><body><main>");
        page.Append("<h1>Choose the queue folder</h1>");
        page.Append("<p>Pick the same folder the phone created. Only this folder is used. Your other Drive files stay in the cloud.</p>");
        page.Append("<a class=\"use\" href=\"").Append(use).Append("\">Use ").Append(WebUtility.HtmlEncode(parentName)).Append("</a>");
        page.Append(rows);
        page.Append("<p><a href=\"/cancel\" style=\"color:#c9cdb8\">Cancel</a></p></main></body></html>");
        return page.ToString();
    }

    private static string? QueryValue(string? query, string key)
    {
        if (string.IsNullOrEmpty(query)) return null;
        var text = query.StartsWith('?') ? query[1..] : query;
        foreach (var part in text.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var split = part.IndexOf('=');
            var name = Uri.UnescapeDataString(split < 0 ? part : part[..split]);
            if (!name.Equals(key, StringComparison.OrdinalIgnoreCase)) continue;
            return Uri.UnescapeDataString(split < 0 ? "" : part[(split + 1)..]);
        }
        return null;
    }

    private static async Task WriteHtmlAsync(HttpListenerContext context, string html)
    {
        var bytes = Encoding.UTF8.GetBytes(html);
        context.Response.ContentType = "text/html; charset=utf-8";
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes);
        context.Response.Close();
    }

    private static int FreePort()
    {
        var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string GoogleError(string json, string fallback)
    {
        try
        {
            var root = JsonDocument.Parse(json).RootElement;
            if (root.TryGetProperty("error_description", out var description)) return description.GetString() ?? fallback;
            if (root.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.Object &&
                error.TryGetProperty("message", out var message)) return message.GetString() ?? fallback;
        }
        catch { /* the body was not JSON */ }
        return fallback;
    }
}

sealed class DriveNotebook : INotebookStore
{
    private readonly DriveClient _drive;
    private readonly string _folderId;

    public DriveNotebook(AppSettings settings)
    {
        _drive = new DriveClient(settings);
        _folderId = settings.DriveFolderId;
    }

    public List<JobCommand> ReadCommands() =>
        Notebook.ParseCommands(Task.Run(() => _drive.ReadNamedAsync(_folderId, "commands.jsonl")).GetAwaiter().GetResult() ?? "");

    public LibraryState ReadState() =>
        Notebook.ParseState(Task.Run(() => _drive.ReadNamedAsync(_folderId, "state.json")).GetAwaiter().GetResult() ?? "");

    public void WriteState(LibraryState state) =>
        Task.Run(() => _drive.WriteNamedAsync(_folderId, "state.json", JsonSerializer.Serialize(state, Notebook.Json))).GetAwaiter().GetResult();
}
