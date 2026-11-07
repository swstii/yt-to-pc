using System.Text.Json;

namespace YtToPc;

sealed class QueueWorker
{
    private readonly AppSettings _settings;
    private readonly AudioDownloader _downloader;
    private int _running;
    private INotebookStore? _notebook;
    private string _lastUploaded = "";
    private string _lastLocal = "";

    private INotebookStore? OpenNotebook()
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId)) return new DriveNotebook(_settings);
        if (Directory.Exists(_settings.QueuePath)) return new LocalNotebook(_settings.QueuePath, _settings.LibraryName);
        return null;
    }

    private void Save(LibraryState state, bool force = false)
    {
        WriteLocal(state);
        if (force) Publish(state);
    }

    private string LocalStatePath()
    {
        var name = LibraryNames.Folder(_settings.LibraryName);
        foreach (var invalid in Path.GetInvalidFileNameChars()) name = name.Replace(invalid, '_');
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "yt-to-pc",
            "local-state",
            name + ".json");
    }

    private void WriteLocal(LibraryState state)
    {
        var text = JsonSerializer.Serialize(state, Notebook.Json);
        if (text == _lastLocal) return;
        var path = LocalStatePath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        File.WriteAllText(temporary, text);
        File.Move(temporary, path, true);
        _lastLocal = text;
    }

    private LibraryState LoadWorking()
    {
        var path = LocalStatePath();
        if (File.Exists(path))
        {
            try
            {
                var state = Notebook.ParseState(File.ReadAllText(path));
                foreach (var folder in state.Folders)
                {
                    foreach (var track in folder.Tracks)
                    {
                        if (track.Status == "downloading") track.Status = "pending";
                    }
                }
                return state;
            }
            catch
            {
                // A broken local file falls back to the last copy Drive has.
            }
        }
        return _notebook!.ReadState();
    }

    private void Publish(LibraryState state)
    {
        var text = JsonSerializer.Serialize(state, Notebook.Json);
        if (text == _lastUploaded) return;
        if (_lastUploaded.Length == 0)
        {
            var remote = JsonSerializer.Serialize(_notebook!.ReadState(), Notebook.Json);
            if (remote == text)
            {
                _lastUploaded = text;
                return;
            }
        }
        SetStatus("Updating Drive");
        _notebook!.WriteState(state);
        _lastUploaded = text;
    }

    private LibraryState Load() => LoadWorking();
    private List<JobCommand> LoadCommands() => _notebook!.ReadCommands();

    public QueueWorker(AppSettings settings)
    {
        _settings = settings;
        _downloader = new AudioDownloader(settings.FfmpegPath);
    }

    public string Status { get; private set; } = "Idle";
    public string Phase { get; private set; } = "IDLE";
    public string Song { get; private set; } = "";
    public int ProgressPercent { get; private set; } = -1;
    public event Action? StatusChanged;

    private void SetStatus(string status, int progress = -1)
    {
        if (Status == status && ProgressPercent == progress) return;
        Status = status;
        ProgressPercent = progress;
        (Phase, Song) = Describe(status);
        StatusChanged?.Invoke();
    }

    public string MissingMusicPath =>
        Directory.Exists(_settings.MusicPath) ? "" : _settings.MusicPath + " is not available. Check that the drive is connected.";

    public void NoteMissing() => SetStatus(MissingMusicPath);

    public void NoteUnreachable() => SetStatus("Can't reach Google. YT to PC is waiting for the internet.");

    private static (string Phase, string Song) Describe(string status)
    {
        if (status.StartsWith("Downloading ", StringComparison.Ordinal))
        {
            var song = status["Downloading ".Length..];
            var colon = song.LastIndexOf(':');
            if (colon > 0) song = song[..colon];
            return ("DOWNLOAD", song.Trim());
        }
        if (status.StartsWith("Saving ", StringComparison.Ordinal) && status.EndsWith(" as MP3", StringComparison.Ordinal))
            return ("DOWNLOAD", status["Saving ".Length..^" as MP3".Length].Trim());
        if (status.StartsWith("Saved ", StringComparison.Ordinal))
            return ("SAVED", status["Saved ".Length..].Trim());
        if (status.StartsWith("Already in this folder: ", StringComparison.Ordinal))
            return ("SAVED", status["Already in this folder: ".Length..].Trim());
        if (status == "Idle") return ("IDLE", "");
        if (status is "Scanning Drive" or "Checking the music folder" or "Reading playlist" || status.StartsWith("Updating Drive", StringComparison.Ordinal))
            return ("SCAN", "");
        if (status.StartsWith("Can't reach Google", StringComparison.Ordinal)) return ("OFFLINE", "");
        if (status.Contains("is not available", StringComparison.Ordinal)) return ("NO DISK", "");
        if (status is "Checks paused" || status.StartsWith("Checks are off", StringComparison.Ordinal)) return ("PAUSED", "");
        if (status.Contains("failed", StringComparison.OrdinalIgnoreCase) || status.Contains("not found", StringComparison.OrdinalIgnoreCase))
            return ("ERROR", "");
        if (status == "Playlist queued") return ("QUEUED", "");
        return ("BUSY", "");
    }

    public void ForgetSaved()
    {
        _lastUploaded = "";
        _lastLocal = "";
    }

    public async Task TickAsync(CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref _running, 1) == 1) return;
        try
        {
            await RunAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            SetStatus("Checks paused");
        }
        catch (Exception error)
        {
            SetStatus(Short(error.Message));
        }
        finally
        {
            Interlocked.Exchange(ref _running, 0);
        }
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        if (!string.IsNullOrEmpty(MissingMusicPath))
        {
            SetStatus(MissingMusicPath);
            _notebook = OpenNotebook();
            WriteUnavailable();
            return;
        }
        _notebook = OpenNotebook();
        if (_notebook == null)
        {
            SetStatus("Choose the Drive queue folder.");
            return;
        }
        if (!_downloader.FfmpegExists)
        {
            SetStatus("ffmpeg was not found, so songs cannot be saved yet.");
            PublishReadyShell();
            return;
        }

        var state = LoadWorking();
        state.LibraryName = ActiveLibraryName();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = true;

        while (!cancellationToken.IsCancellationRequested)
        {
            SetStatus("Scanning Drive");
            var commands = LoadCommands();
            SetStatus("Checking the music folder");
            var changes = SyncFolders(state, commands);
            RecordStorage();
            var commandNotes = ApplyFastCommands(state, commands);
            var update = Summarize(changes.Concat(commandNotes).Where(note => note.Length > 0).ToList());
            if (update.Length > 0) SetStatus("Updating Drive: " + update);
            Save(state, force: true);
            cancellationToken.ThrowIfCancellationRequested();

            var busy = await DownloadUntilAsync(state, commands, TimeSpan.FromSeconds(60), cancellationToken);
            if (!busy)
            {
                Save(state, force: true);
                SetStatus("Idle");
                return;
            }
        }
    }

    private async Task<bool> DownloadUntilAsync(LibraryState state, List<JobCommand> commands, TimeSpan budget, CancellationToken cancellationToken)
    {
        var until = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < until)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var video = NextVideo(commands, state);
            if (video != null)
            {
                await DownloadVideoAsync(state, video, cancellationToken);
                WriteLocal(state);
                continue;
            }
            var playlist = commands.FirstOrDefault(command =>
                command.Type == "enqueue-playlist" && !state.Commands.ContainsKey(command.Id));
            if (playlist != null)
            {
                await ExpandPlaylistAsync(state, playlist, cancellationToken);
                WriteLocal(state);
                continue;
            }
            return false;
        }
        return true;
    }

    private void WriteUnavailable()
    {
        if (_notebook == null) return;
        var state = Load();
        state.LibraryName = ActiveLibraryName();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = false;
        Save(state, force: true);
    }

    private void PublishReadyShell()
    {
        var state = Load();
        state.LibraryName = ActiveLibraryName();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = true;
        SyncFolders(state, LoadCommands());
        RecordStorage();
        Save(state, force: true);
    }

    private void RecordStorage()
    {
        if (_notebook == null || !Directory.Exists(_settings.MusicPath)) return;
        var folders = new List<StorageFolder>();
        foreach (var directory in Directory.GetDirectories(_settings.MusicPath))
        {
            var name = Path.GetFileName(directory);
            if (string.IsNullOrWhiteSpace(name) || name.Equals("_queue", StringComparison.OrdinalIgnoreCase)) continue;
            folders.Add(new StorageFolder(name, DirectoryBytes(directory)));
        }
        folders.Sort((left, right) => string.Compare(left.Name, right.Name, StringComparison.OrdinalIgnoreCase));
        var report = new StorageFile(_settings.MusicPath, DirectoryBytes(_settings.MusicPath), folders);
        new LibraryBook(_settings).WriteStorage(_settings.LibraryName, JsonSerializer.Serialize(report, Notebook.Json));
    }

    private static long DirectoryBytes(string path)
    {
        long total = 0;
        try
        {
            foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            {
                try { total += new FileInfo(file).Length; }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        return total;
    }

    private sealed class StorageFile(string root, long totalBytes, List<StorageFolder> folders)
    {
        public string Root { get; } = root;
        public long TotalBytes { get; } = totalBytes;
        public List<StorageFolder> Folders { get; } = folders;
    }

    private sealed class StorageFolder(string name, long bytes)
    {
        public string Name { get; } = name;
        public long Bytes { get; } = bytes;
    }

    private List<string> SyncFolders(LibraryState state, List<JobCommand> commands)
    {
        var waiting = commands
            .Where(command => command.Type == "make-folder" && !state.Commands.ContainsKey(command.Id))
            .Select(command => Text(command, "folder"))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var notes = new List<string>();
        foreach (var directory in Directory.GetDirectories(_settings.MusicPath))
        {
            var name = Path.GetFileName(directory);
            if (string.IsNullOrWhiteSpace(name) || name.Equals("_queue", StringComparison.OrdinalIgnoreCase)) continue;
            if (state.Folders.All(folder => !folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
            {
                state.Folders.Add(new StateFolder { Name = name });
                notes.Add("added folder " + name);
            }
        }
        var removedFolders = state.Folders
            .Where(folder => !Directory.Exists(Path.Combine(_settings.MusicPath, folder.Name)) && !waiting.Contains(folder.Name))
            .Select(folder => folder.Name)
            .ToList();
        state.Folders.RemoveAll(folder => removedFolders.Contains(folder.Name, StringComparer.OrdinalIgnoreCase));
        foreach (var name in removedFolders) notes.Add("removed folder " + name);
        var removedFiles = new List<DiskFile>();
        var addedFiles = new List<DiskFile>();
        foreach (var folder in state.Folders) SyncDiskTracks(folder, removedFiles, addedFiles);
        foreach (var folder in state.Folders) RemoveDownloadedDuplicates(folder, notes);
        notes.AddRange(DescribeTrackChanges(removedFiles, addedFiles));
        state.Folders.Sort((left, right) => string.Compare(left.Name, right.Name, StringComparison.OrdinalIgnoreCase));
        return notes;
    }

    private void SyncDiskTracks(StateFolder folder, List<DiskFile> removedFiles, List<DiskFile> addedFiles)
    {
        var directory = Path.Combine(_settings.MusicPath, folder.Name);
        var before = folder.Tracks
            .Where(track => !string.IsNullOrWhiteSpace(track.FileName))
            .Select(track => track.FileName!)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var files = Directory.Exists(directory)
            ? Directory.EnumerateFiles(directory, "*.mp3").Select(Path.GetFileName).Where(name => !string.IsNullOrWhiteSpace(name)).Cast<string>().ToList()
            : [];
        var names = files.ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var fileName in before.Where(name => !names.Contains(name)))
            removedFiles.Add(new DiskFile(folder.Name, fileName));
        folder.Tracks.RemoveAll(track =>
            track.Status is "done" or "skipped" &&
            !string.IsNullOrWhiteSpace(track.FileName) &&
            !names.Contains(track.FileName));
        foreach (var fileName in files)
        {
            if (before.Contains(fileName) || folder.Tracks.Any(track => string.Equals(track.FileName, fileName, StringComparison.OrdinalIgnoreCase))) continue;
            var title = Path.GetFileNameWithoutExtension(fileName);
            folder.Tracks.Add(new StateTrack
            {
                VideoId = "file:" + fileName,
                Title = string.IsNullOrWhiteSpace(title) ? fileName : title,
                FileName = fileName,
                Status = "done",
            });
            addedFiles.Add(new DiskFile(folder.Name, fileName));
        }
        folder.Tracks.Sort((left, right) => string.Compare(left.Title, right.Title, StringComparison.OrdinalIgnoreCase));
    }

    private void RemoveDownloadedDuplicates(StateFolder folder, List<string> notes)
    {
        var saved = folder.Tracks
            .Where(track => track.Status is "done" or "skipped" && !string.IsNullOrWhiteSpace(track.FileName) && !string.IsNullOrWhiteSpace(track.Title))
            .ToList();
        foreach (var group in saved.GroupBy(track => SongKey(track.Title)).Where(group => group.Key.Length > 0 && group.Count() > 1))
        {
            var keeper = group.FirstOrDefault(track => track.VideoId.StartsWith("file:", StringComparison.Ordinal)) ?? group.First();
            foreach (var extra in group)
            {
                if (ReferenceEquals(extra, keeper) || extra.VideoId.StartsWith("file:", StringComparison.Ordinal)) continue;
                var path = Path.Combine(_settings.MusicPath, folder.Name, extra.FileName!);
                if (File.Exists(path)) File.Delete(path);
                folder.Tracks.Remove(extra);
                notes.Add("removed duplicate " + extra.Title + " from " + folder.Name);
            }
        }
    }

    private static string SongKey(string? title) => title?.Trim().ToLowerInvariant() ?? "";

    private static bool SameSong(string? left, string? right)
    {
        var a = SongKey(left);
        var b = SongKey(right);
        return a.Length > 0 && a == b;
    }

    private static bool FolderHasSong(StateFolder folder, string title, string exceptVideoId) =>
        folder.Tracks.Any(track =>
            track.VideoId != exceptVideoId &&
            track.Status != "failed" &&
            SameSong(track.Title, title));


    private static List<string> DescribeTrackChanges(List<DiskFile> removedFiles, List<DiskFile> addedFiles)
    {
        var notes = new List<string>();
        foreach (var added in addedFiles.ToList())
        {
            var moved = removedFiles.FirstOrDefault(file =>
                !file.Folder.Equals(added.Folder, StringComparison.OrdinalIgnoreCase) &&
                file.Name.Equals(added.Name, StringComparison.OrdinalIgnoreCase));
            if (moved == null) continue;
            notes.Add("moved " + DisplayName(added.Name) + " from " + moved.Folder + " to " + added.Folder);
            removedFiles.Remove(moved);
            addedFiles.Remove(added);
        }
        foreach (var folder in removedFiles.Select(file => file.Folder).Distinct(StringComparer.OrdinalIgnoreCase).ToList())
        {
            var removed = removedFiles.Where(file => file.Folder.Equals(folder, StringComparison.OrdinalIgnoreCase)).ToList();
            var added = addedFiles.Where(file => file.Folder.Equals(folder, StringComparison.OrdinalIgnoreCase)).ToList();
            if (removed.Count == 1 && added.Count == 1)
            {
                notes.Add("renamed " + DisplayName(removed[0].Name) + " to " + DisplayName(added[0].Name) + " in " + folder);
                removedFiles.Remove(removed[0]);
                addedFiles.Remove(added[0]);
            }
        }
        foreach (var file in removedFiles) notes.Add("deleted " + DisplayName(file.Name) + " from " + file.Folder);
        foreach (var file in addedFiles) notes.Add("added " + DisplayName(file.Name) + " to " + file.Folder);
        return notes;
    }

    private static string Summarize(List<string> notes)
    {
        if (notes.Count == 0) return "";
        if (notes.Count <= 3) return string.Join(", ", notes);
        return notes.Count + " music folder changes, including " + string.Join(", ", notes.Take(2));
    }

    private static string DisplayName(string fileName) => Path.GetFileNameWithoutExtension(fileName);

    private sealed record DiskFile(string Folder, string Name);

    private List<string> ApplyFastCommands(LibraryState state, List<JobCommand> commands)
    {
        var notes = new List<string>();
        foreach (var command in commands)
        {
            if (state.Commands.ContainsKey(command.Id)) continue;
            switch (command.Type)
            {
                case "make-folder":
                    notes.Add(MakeFolder(state, command));
                    break;
                case "remove-folder":
                    notes.Add(RemoveFolder(state, command));
                    break;
                case "delete-track":
                    notes.Add(DeleteTrack(state, command));
                    break;
                case "retry":
                    Retry(state, command);
                    break;
                case "set-library-root":
                    RememberRoot(state, command);
                    break;
            }
        }
        return notes;
    }

    private string MakeFolder(LibraryState state, JobCommand command)
    {
        var name = Text(command, "folder");
        try
        {
            Directory.CreateDirectory(Path.Combine(_settings.MusicPath, name));
            if (state.Folders.All(folder => !folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
                state.Folders.Add(new StateFolder { Name = name });
            Settle(state, command.Id, "done", null);
            return "added folder " + name;
        }
        catch (Exception error)
        {
            Settle(state, command.Id, "failed", Short(error.Message));
            return "";
        }
    }

    private string RemoveFolder(LibraryState state, JobCommand command)
    {
        var name = Text(command, "folder");
        var path = Path.Combine(_settings.MusicPath, name);
        if (Directory.Exists(path) && Directory.EnumerateFileSystemEntries(path).Any())
        {
            Settle(state, command.Id, "failed", "That folder still has songs.");
            return "";
        }
        if (Directory.Exists(path)) Directory.Delete(path);
        state.Folders.RemoveAll(folder => folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        Settle(state, command.Id, "done", null);
        return "removed folder " + name;
    }

    private string DeleteTrack(LibraryState state, JobCommand command)
    {
        var folderName = Text(command, "folder");
        var videoId = Text(command, "videoId");
        var folder = state.Folders.FirstOrDefault(item => item.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase));
        var track = folder?.Tracks.FirstOrDefault(item => item.VideoId == videoId);
        var title = string.IsNullOrWhiteSpace(track?.Title) ? videoId : track.Title;
        if (track?.FileName is { Length: > 0 })
        {
            var file = Path.Combine(_settings.MusicPath, folderName, track.FileName);
            if (File.Exists(file)) File.Delete(file);
        }
        folder?.Tracks.RemoveAll(item => item.VideoId == videoId);
        Settle(state, command.Id, "done", null);
        return "deleted " + title + " from " + folderName;
    }

    private void Retry(LibraryState state, JobCommand command)
    {
        var commandId = Text(command, "commandId");
        if (state.Commands.TryGetValue(commandId, out var previous) && previous.Status == "failed")
            state.Commands.Remove(commandId);
        foreach (var folder in state.Folders)
        {
            foreach (var track in folder.Tracks.Where(item => item.CommandId == commandId && item.Status == "failed"))
            {
                track.Status = "pending";
                track.Error = null;
            }
        }
        Settle(state, command.Id, "done", null);
    }

    private void RememberRoot(LibraryState state, JobCommand command)
    {
        var path = Text(command, "path");
        if (!Directory.Exists(path))
        {
            Settle(state, command.Id, "failed", "That disk is not connected.");
            return;
        }
        _settings.MusicPath = path;
        _settings.Save();
        state.LibraryRoot = path;
        Settle(state, command.Id, "done", null);
    }

    private PendingVideo? NextVideo(List<JobCommand> commands, LibraryState state)
    {
        foreach (var command in commands.Where(item => item.Type == "enqueue-video" && !state.Commands.ContainsKey(item.Id)))
        {
            var folder = Text(command, "folder");
            var videoId = Text(command, "videoId");
            var existing = FindTrack(state, folder, videoId);
            if (existing != null && existing.Status is "done" or "downloading" or "pending" or "skipped")
            {
                Settle(state, command.Id, "skipped", null);
                Save(state);
                continue;
            }
            return new PendingVideo(command.Id, folder, videoId, command.Id);
        }
        foreach (var folder in state.Folders)
        {
            var track = folder.Tracks.FirstOrDefault(item => item.Status == "pending");
            if (track != null) return new PendingVideo(track.CommandId ?? track.VideoId, folder.Name, track.VideoId, null);
        }
        return null;
    }

    private async Task DownloadVideoAsync(LibraryState state, PendingVideo video, CancellationToken cancellationToken)
    {
        var track = TrackFor(state, video);
        track.Status = "downloading";
        track.Error = null;
        var song = string.IsNullOrWhiteSpace(track.Title) || track.Title == video.VideoId ? video.VideoId : track.Title;
        SetStatus("Downloading " + song + ": 0%", 0);
        Save(state);
        var lastPercent = 0;
        try
        {
            var saved = await _downloader.DownloadVideoAsync(
                video.VideoId,
                Path.Combine(_settings.MusicPath, video.Folder),
                cancellationToken,
                title =>
                {
                    track.Title = title;
                    song = title;
                    SetStatus("Downloading " + song + ": " + lastPercent + "%", lastPercent);
                    Save(state);
                },
                new Progress<double>(fraction =>
                {
                    var percent = Math.Clamp((int)Math.Round(fraction * 100), 0, 100);
                    if (percent == lastPercent) return;
                    lastPercent = percent;
                    SetStatus("Downloading " + song + ": " + percent + "%", percent);
                }),
                () => SetStatus("Saving " + song + " as MP3", 100),
                title =>
                {
                    song = title;
                    var home = state.Folders.FirstOrDefault(item => item.Name.Equals(video.Folder, StringComparison.OrdinalIgnoreCase));
                    return home != null && FolderHasSong(home, title, video.VideoId);
                });
            if (saved == null)
            {
                var home = state.Folders.FirstOrDefault(item => item.Name.Equals(video.Folder, StringComparison.OrdinalIgnoreCase));
                home?.Tracks.RemoveAll(item => item.VideoId == video.VideoId);
                if (video.CommandId != null) Settle(state, video.CommandId, "skipped", null);
                SetStatus("Already in this folder: " + song);
                Save(state);
                return;
            }
            track.Title = saved.Title;
            track.FileName = saved.FileName;
            track.Status = "done";
            if (video.CommandId != null) Settle(state, video.CommandId, "done", null);
            SetStatus("Saved " + saved.Title);
        }
        catch (OperationCanceledException)
        {
            track.Status = "pending";
            WriteLocal(state);
            SetStatus("Checks paused");
            throw;
        }
        catch (Exception error)
        {
            track.Status = "failed";
            track.Error = Short(error.Message);
            if (video.CommandId != null) Settle(state, video.CommandId, "failed", track.Error);
            SetStatus(track.Error ?? "Download failed");
        }
        Save(state);
    }

    private async Task ExpandPlaylistAsync(LibraryState state, JobCommand command, CancellationToken cancellationToken)
    {
        var folderName = Text(command, "folder");
        var playlistId = Text(command, "playlistId");
        SetStatus("Reading playlist");
        try
        {
            var items = await _downloader.ListPlaylistAsync(playlistId, cancellationToken);
            var folder = state.Folders.FirstOrDefault(item => item.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase))
                ?? new StateFolder { Name = folderName };
            if (!state.Folders.Contains(folder)) state.Folders.Add(folder);
            foreach (var item in items)
            {
                if (folder.Tracks.Any(track => track.VideoId == item.VideoId && track.Status != "failed")) continue;
                if (folder.Tracks.Any(track => track.Status != "failed" && SameSong(track.Title, item.Title))) continue;
                folder.Tracks.Add(new StateTrack
                {
                    VideoId = item.VideoId,
                    Title = item.Title,
                    Status = "pending",
                    CommandId = command.Id + ":" + item.VideoId,
                });
            }
            Settle(state, command.Id, "expanded", null);
            SetStatus("Playlist queued");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception error)
        {
            Settle(state, command.Id, "failed", Short(error.Message));
            SetStatus(Short(error.Message));
        }
        Save(state);
    }

    private StateTrack TrackFor(LibraryState state, PendingVideo video)
    {
        var folder = state.Folders.FirstOrDefault(item => item.Name.Equals(video.Folder, StringComparison.OrdinalIgnoreCase));
        if (folder == null)
        {
            folder = new StateFolder { Name = video.Folder };
            state.Folders.Add(folder);
        }
        var track = folder.Tracks.FirstOrDefault(item => item.VideoId == video.VideoId);
        if (track != null) return track;
        track = new StateTrack { VideoId = video.VideoId, Title = video.VideoId, Status = "pending", CommandId = video.CommandId };
        folder.Tracks.Add(track);
        return track;
    }

    private static StateTrack? FindTrack(LibraryState state, string folderName, string videoId) =>
        state.Folders.FirstOrDefault(folder => folder.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase))
            ?.Tracks.FirstOrDefault(track => track.VideoId == videoId);

    private static void Settle(LibraryState state, string id, string status, string? error) =>
        state.Commands[id] = new CommandState { Status = status, Error = error };

    private string? ActiveLibraryName() =>
        string.IsNullOrWhiteSpace(_settings.LibraryName) ? null : _settings.LibraryName.Trim();

    private static string Text(JobCommand command, string name) =>
        command.Body.TryGetProperty(name, out var value) ? value.GetString() ?? "" : "";

    private static string Short(string message)
    {
        var line = message.Replace('\r', ' ').Replace('\n', ' ').Trim();
        return line.Length <= 180 ? line : line[..180];
    }

    private sealed record PendingVideo(string Key, string Folder, string VideoId, string? CommandId);
}
