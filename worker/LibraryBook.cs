using System.Text.Json;

namespace YtToPc;

static class LibraryNames
{
    public static string? Problem(string name)
    {
        var cleaned = name.Trim();
        if (cleaned.Length == 0) return "Type a name for this music folder.";
        if (cleaned.Length > 60) return "Use a name of 60 characters or less.";
        if (cleaned.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return "That name cannot be a file name.";
        return null;
    }

    public static string Folder(string? name)
    {
        var cleaned = name?.Trim() ?? "";
        return cleaned.Length == 0 ? "Library" : cleaned;
    }

    public static string StateFile(string? name)
    {
        var cleaned = name?.Trim() ?? "";
        return cleaned.Length == 0 ? "state.json" : cleaned + "-state.json";
    }
}

sealed class LibraryEntry
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
}

sealed class LibraryCatalog
{
    public string? Active { get; set; }
    public List<LibraryEntry> Libraries { get; set; } = [];
}

sealed class LibraryBook
{
    private const string CatalogFile = "libraries.json";
    private readonly AppSettings _settings;

    public LibraryBook(AppSettings settings) => _settings = settings;

    public LibraryCatalog Read()
    {
        var text = ReadText(CatalogFile);
        if (string.IsNullOrWhiteSpace(text)) return new LibraryCatalog();
        return JsonSerializer.Deserialize<LibraryCatalog>(text, Notebook.Json) ?? new LibraryCatalog();
    }

    public void Write(LibraryCatalog catalog) =>
        WriteText(CatalogFile, JsonSerializer.Serialize(catalog, Notebook.Json));

    public LibraryState ReadLibrary(string? name)
    {
        var folder = LibraryNames.Folder(name);
        var text = ReadNested(folder, "state.json");
        if (string.IsNullOrWhiteSpace(text)) text = ReadText(LibraryNames.StateFile(name));
        if (string.IsNullOrWhiteSpace(text) && LibraryNames.StateFile(name) != "state.json") text = ReadText("state.json");
        return string.IsNullOrWhiteSpace(text) ? new LibraryState() : Notebook.ParseState(text);
    }

    public void WriteLibrary(string? name, LibraryState state) =>
        WriteNested(LibraryNames.Folder(name), "state.json", JsonSerializer.Serialize(state, Notebook.Json));

    public void WriteStorage(string? name, string text) =>
        WriteNested(LibraryNames.Folder(name), "storage.json", text);

    public void AppendHistory(string line)
    {
        var existing = ReadText("history.jsonl") ?? "";
        var lines = existing.Split('\n').Select(item => item.Trim()).Where(item => item.Length > 0).ToList();
        lines.Add(line);
        if (lines.Count > 200) lines = lines.Skip(lines.Count - 200).ToList();
        WriteText("history.jsonl", string.Join('\n', lines) + "\n");
    }

    private string? ReadNested(string libraryFolder, string name)
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId))
        {
            var drive = new DriveClient(_settings);
            var stateRoot = Task.Run(() => drive.EnsureFolderAsync(_settings.DriveFolderId, "state")).GetAwaiter().GetResult();
            var library = Task.Run(() => drive.EnsureFolderAsync(stateRoot, libraryFolder)).GetAwaiter().GetResult();
            return Task.Run(() => drive.ReadNamedAsync(library, name)).GetAwaiter().GetResult();
        }
        if (!Directory.Exists(_settings.QueuePath)) return null;
        var file = Path.Combine(_settings.QueuePath, "state", libraryFolder, name);
        return File.Exists(file) ? File.ReadAllText(file) : null;
    }

    private void WriteNested(string libraryFolder, string name, string text)
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId))
        {
            var drive = new DriveClient(_settings);
            var stateRoot = Task.Run(() => drive.EnsureFolderAsync(_settings.DriveFolderId, "state")).GetAwaiter().GetResult();
            var library = Task.Run(() => drive.EnsureFolderAsync(stateRoot, libraryFolder)).GetAwaiter().GetResult();
            Task.Run(() => drive.WriteNamedAsync(library, name, text)).GetAwaiter().GetResult();
            return;
        }
        var directory = Path.Combine(_settings.QueuePath, "state", libraryFolder);
        Directory.CreateDirectory(directory);
        File.WriteAllText(Path.Combine(directory, name), text);
    }

    private string? ReadText(string name)
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId))
        {
            var drive = new DriveClient(_settings);
            return Task.Run(() => drive.ReadNamedAsync(_settings.DriveFolderId, name)).GetAwaiter().GetResult();
        }
        if (!Directory.Exists(_settings.QueuePath)) return null;
        var file = Path.Combine(_settings.QueuePath, name);
        return File.Exists(file) ? File.ReadAllText(file) : null;
    }

    private void WriteText(string name, string text)
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId))
        {
            var drive = new DriveClient(_settings);
            Task.Run(() => drive.WriteNamedAsync(_settings.DriveFolderId, name, text)).GetAwaiter().GetResult();
            return;
        }
        Directory.CreateDirectory(_settings.QueuePath);
        File.WriteAllText(Path.Combine(_settings.QueuePath, name), text);
    }
}
