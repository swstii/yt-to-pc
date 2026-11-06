using System.Text.Json;
using System.Text.Json.Serialization;

namespace YtToPc;

static class Notebook
{
    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = true,
    };

    public static List<JobCommand> ParseCommands(string text)
    {
        var commands = new List<JobCommand>();
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            try
            {
                using var doc = JsonDocument.Parse(line);
                var root = doc.RootElement;
                var type = root.GetProperty("type").GetString();
                var id = root.GetProperty("id").GetString() ?? "";
                var at = root.TryGetProperty("at", out var atValue) ? atValue.GetString() ?? "" : "";
                commands.Add(new JobCommand(type ?? "", id, at, root.Clone()));
            }
            catch
            {
                // The phone skips a bad line too.
            }
        }
        return commands;
    }

    public static LibraryState ParseState(string text)
    {
        var trimmed = text.Trim();
        if (trimmed.Length == 0) return new LibraryState();
        return JsonSerializer.Deserialize<LibraryState>(trimmed, Json) ?? new LibraryState();
    }
}

interface INotebookStore
{
    List<JobCommand> ReadCommands();
    LibraryState ReadState();
    void WriteState(LibraryState state);
}

sealed class LocalNotebook : INotebookStore
{
    private readonly string _queuePath;

    private readonly string _libraryFolder;

    public LocalNotebook(string queuePath, string? libraryName = null)
    {
        _queuePath = queuePath;
        _libraryFolder = LibraryNames.Folder(libraryName);
    }

    public List<JobCommand> ReadCommands()
    {
        var file = Path.Combine(_queuePath, "commands.jsonl");
        return File.Exists(file) ? Notebook.ParseCommands(File.ReadAllText(file)) : [];
    }

    public LibraryState ReadState() => new LibraryBook(new AppSettings { QueuePath = _queuePath, LibraryName = _libraryFolder }).ReadLibrary(_libraryFolder);

    public void WriteState(LibraryState state) =>
        new LibraryBook(new AppSettings { QueuePath = _queuePath }).WriteLibrary(_libraryFolder, state);
}

sealed record JobCommand(string Type, string Id, string At, JsonElement Body);

sealed class LibraryState
{
    public string? LibraryName { get; set; }
    public string? LibraryRoot { get; set; }
    public bool LibraryReady { get; set; }
    public List<StateFolder> Folders { get; set; } = [];
    public Dictionary<string, CommandState> Commands { get; set; } = new(StringComparer.Ordinal);
}

sealed class StateFolder
{
    public string Name { get; set; } = "";
    public List<StateTrack> Tracks { get; set; } = [];
}

sealed class StateTrack
{
    public string VideoId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? FileName { get; set; }
    public string Status { get; set; } = "pending";
    public string? CommandId { get; set; }
    public string? Error { get; set; }
}

sealed class CommandState
{
    public string Status { get; set; } = "done";
    public string? Error { get; set; }
}
