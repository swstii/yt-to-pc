namespace YtToPc;

sealed class QueueWorker
{
    private readonly AppSettings _settings;
    private readonly AudioDownloader _downloader;
    private int _running;
    private INotebookStore? _notebook;

    private INotebookStore? OpenNotebook()
    {
        if (!string.IsNullOrWhiteSpace(_settings.DriveFolderId)) return new DriveNotebook(_settings);
        if (Directory.Exists(_settings.QueuePath)) return new LocalNotebook(_settings.QueuePath);
        return null;
    }

    private void Save(LibraryState state) => _notebook!.WriteState(state);
    private LibraryState Load() => _notebook!.ReadState();
    private List<JobCommand> LoadCommands() => _notebook!.ReadCommands();

    public QueueWorker(AppSettings settings)
    {
        _settings = settings;
        _downloader = new AudioDownloader(settings.FfmpegPath);
    }

    public string Status { get; private set; } = "Idle";

    public string MissingMusicPath =>
        Directory.Exists(_settings.MusicPath) ? "" : _settings.MusicPath + " is not available. Connect the SSD.";

    public async Task TickAsync(CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref _running, 1) == 1) return;
        try
        {
            await RunAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            Status = "Checks paused";
        }
        catch (Exception error)
        {
            Status = Short(error.Message);
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
            Status = MissingMusicPath;
            _notebook = OpenNotebook();
            WriteUnavailable();
            return;
        }
        _notebook = OpenNotebook();
        if (_notebook == null)
        {
            Status = "Choose the Drive queue folder.";
            return;
        }
        if (!_downloader.FfmpegExists)
        {
            Status = "ffmpeg was not found, so songs cannot be saved yet.";
            PublishReadyShell();
            return;
        }

        var commands = LoadCommands();
        var state = Load();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = true;
        SyncFolders(state, commands);
        ApplyFastCommands(state, commands);
        Save(state);
        cancellationToken.ThrowIfCancellationRequested();

        var video = NextVideo(commands, state);
        if (video != null)
        {
            await DownloadVideoAsync(state, video, cancellationToken);
            return;
        }
        var playlist = commands.FirstOrDefault(command =>
            command.Type == "enqueue-playlist" && !state.Commands.ContainsKey(command.Id));
        if (playlist != null)
        {
            await ExpandPlaylistAsync(state, playlist, cancellationToken);
            return;
        }
        Status = "Idle";
    }

    private void WriteUnavailable()
    {
        if (_notebook == null) return;
        var state = Load();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = false;
        Save(state);
    }

    private void PublishReadyShell()
    {
        var state = Load();
        state.LibraryRoot = _settings.MusicPath;
        state.LibraryReady = true;
        SyncFolders(state, LoadCommands());
        Save(state);
    }

    private void SyncFolders(LibraryState state, List<JobCommand> commands)
    {
        var waiting = commands
            .Where(command => command.Type == "make-folder" && !state.Commands.ContainsKey(command.Id))
            .Select(command => Text(command, "folder"))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var directory in Directory.GetDirectories(_settings.MusicPath))
        {
            var name = Path.GetFileName(directory);
            if (string.IsNullOrWhiteSpace(name) || name.Equals("_queue", StringComparison.OrdinalIgnoreCase)) continue;
            if (state.Folders.All(folder => !folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
                state.Folders.Add(new StateFolder { Name = name });
        }
        state.Folders.RemoveAll(folder =>
            !Directory.Exists(Path.Combine(_settings.MusicPath, folder.Name)) && !waiting.Contains(folder.Name));
        foreach (var folder in state.Folders) SyncDiskTracks(folder);
        state.Folders.Sort((left, right) => string.Compare(left.Name, right.Name, StringComparison.OrdinalIgnoreCase));
    }

    private void SyncDiskTracks(StateFolder folder)
    {
        var directory = Path.Combine(_settings.MusicPath, folder.Name);
        if (!Directory.Exists(directory)) return;
        var files = Directory.EnumerateFiles(directory, "*.mp3")
            .Select(Path.GetFileName)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Cast<string>()
            .ToList();
        var names = files.ToHashSet(StringComparer.OrdinalIgnoreCase);
        folder.Tracks.RemoveAll(track =>
            track.Status is "done" or "skipped" &&
            !string.IsNullOrWhiteSpace(track.FileName) &&
            !names.Contains(track.FileName));
        foreach (var fileName in files)
        {
            if (folder.Tracks.Any(track => string.Equals(track.FileName, fileName, StringComparison.OrdinalIgnoreCase))) continue;
            var title = Path.GetFileNameWithoutExtension(fileName);
            folder.Tracks.Add(new StateTrack
            {
                VideoId = "file:" + fileName,
                Title = string.IsNullOrWhiteSpace(title) ? fileName : title,
                FileName = fileName,
                Status = "done",
            });
        }
        folder.Tracks.Sort((left, right) => string.Compare(left.Title, right.Title, StringComparison.OrdinalIgnoreCase));
    }

    private void ApplyFastCommands(LibraryState state, List<JobCommand> commands)
    {
        foreach (var command in commands)
        {
            if (state.Commands.ContainsKey(command.Id)) continue;
            switch (command.Type)
            {
                case "make-folder":
                    MakeFolder(state, command);
                    break;
                case "remove-folder":
                    RemoveFolder(state, command);
                    break;
                case "delete-track":
                    DeleteTrack(state, command);
                    break;
                case "retry":
                    Retry(state, command);
                    break;
                case "set-library-root":
                    RememberRoot(state, command);
                    break;
            }
        }
    }

    private void MakeFolder(LibraryState state, JobCommand command)
    {
        var name = Text(command, "folder");
        try
        {
            Directory.CreateDirectory(Path.Combine(_settings.MusicPath, name));
            if (state.Folders.All(folder => !folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
                state.Folders.Add(new StateFolder { Name = name });
            Settle(state, command.Id, "done", null);
        }
        catch (Exception error)
        {
            Settle(state, command.Id, "failed", Short(error.Message));
        }
    }

    private void RemoveFolder(LibraryState state, JobCommand command)
    {
        var name = Text(command, "folder");
        var path = Path.Combine(_settings.MusicPath, name);
        if (Directory.Exists(path) && Directory.EnumerateFileSystemEntries(path).Any())
        {
            Settle(state, command.Id, "failed", "That folder still has songs.");
            return;
        }
        if (Directory.Exists(path)) Directory.Delete(path);
        state.Folders.RemoveAll(folder => folder.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        Settle(state, command.Id, "done", null);
    }

    private void DeleteTrack(LibraryState state, JobCommand command)
    {
        var folderName = Text(command, "folder");
        var videoId = Text(command, "videoId");
        var folder = state.Folders.FirstOrDefault(item => item.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase));
        var track = folder?.Tracks.FirstOrDefault(item => item.VideoId == videoId);
        if (track?.FileName is { Length: > 0 })
        {
            var file = Path.Combine(_settings.MusicPath, folderName, track.FileName);
            if (File.Exists(file)) File.Delete(file);
        }
        folder?.Tracks.RemoveAll(item => item.VideoId == videoId);
        Settle(state, command.Id, "done", null);
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
        Save(state);
        Status = "Downloading " + (string.IsNullOrWhiteSpace(track.Title) ? video.VideoId : track.Title);
        try
        {
            var saved = await _downloader.DownloadVideoAsync(
                video.VideoId,
                Path.Combine(_settings.MusicPath, video.Folder),
                cancellationToken);
            track.Title = saved.Title;
            track.FileName = saved.FileName;
            track.Status = "done";
            if (video.CommandId != null) Settle(state, video.CommandId, "done", null);
            Status = "Saved " + saved.Title;
        }
        catch (OperationCanceledException)
        {
            track.Status = "pending";
            Status = "Checks paused";
            throw;
        }
        catch (Exception error)
        {
            track.Status = "failed";
            track.Error = Short(error.Message);
            if (video.CommandId != null) Settle(state, video.CommandId, "failed", track.Error);
            Status = track.Error;
        }
        Save(state);
    }

    private async Task ExpandPlaylistAsync(LibraryState state, JobCommand command, CancellationToken cancellationToken)
    {
        var folderName = Text(command, "folder");
        var playlistId = Text(command, "playlistId");
        Status = "Reading playlist";
        try
        {
            var items = await _downloader.ListPlaylistAsync(playlistId, cancellationToken);
            var folder = state.Folders.FirstOrDefault(item => item.Name.Equals(folderName, StringComparison.OrdinalIgnoreCase))
                ?? new StateFolder { Name = folderName };
            if (!state.Folders.Contains(folder)) state.Folders.Add(folder);
            foreach (var item in items)
            {
                if (folder.Tracks.Any(track => track.VideoId == item.VideoId && track.Status != "failed")) continue;
                folder.Tracks.Add(new StateTrack
                {
                    VideoId = item.VideoId,
                    Title = item.Title,
                    Status = "pending",
                    CommandId = command.Id + ":" + item.VideoId,
                });
            }
            Settle(state, command.Id, "expanded", null);
            Status = "Playlist queued";
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception error)
        {
            Settle(state, command.Id, "failed", Short(error.Message));
            Status = Short(error.Message);
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

    private static string Text(JobCommand command, string name) =>
        command.Body.TryGetProperty(name, out var value) ? value.GetString() ?? "" : "";

    private static string Short(string message)
    {
        var line = message.Replace('\r', ' ').Replace('\n', ' ').Trim();
        return line.Length <= 180 ? line : line[..180];
    }

    private sealed record PendingVideo(string Key, string Folder, string VideoId, string? CommandId);
}
