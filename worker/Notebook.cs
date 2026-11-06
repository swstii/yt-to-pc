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

    public LocalNotebook(string queuePath) => _queuePath = queuePath;

    public List<JobCommand> ReadCommands()
    {
        var file = Path.Combine(_queuePath, "commands.jsonl");
        return File.Exists(file) ? Notebook.ParseCommands(File.ReadAllText(file)) : [];
    }

    public LibraryState ReadState()
    {
        var file = Path.Combine(_queuePath, "state.json");
        return File.Exists(file) ? Notebook.ParseState(File.ReadAllText(file)) : new LibraryState();
    }

    public void WriteState(LibraryState state)
    {
        Directory.CreateDirectory(_queuePath);
        var file = Path.Combine(_queuePath, "state.json");
        var temp = file + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(state, Notebook.Json));
        File.Move(temp, file, overwrite: true);
    }
}

sealed record JobCommand(string Type, string Id, string At, JsonElement Body);

sealed class LibraryState
{
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
