using System.Diagnostics;
using YoutubeExplode;
using YoutubeExplode.Videos.Streams;

namespace YtToPc;

sealed class AudioDownloader
{
    private readonly string _ffmpegPath;

    public AudioDownloader(string ffmpegPath) => _ffmpegPath = ffmpegPath;

    public bool FfmpegExists => File.Exists(_ffmpegPath);

    public async Task<SavedAudio?> DownloadVideoAsync(
        string videoId,
        string folderPath,
        CancellationToken cancellationToken,
        Action<string>? onTitle = null,
        IProgress<double>? progress = null,
        Action? onSaving = null,
        Func<string, bool>? alreadySaved = null)
    {
        var youtube = new YoutubeClient();
        var video = await youtube.Videos.GetAsync(videoId, cancellationToken);
        if (alreadySaved?.Invoke(video.Title) == true) return null;
        if (!string.IsNullOrWhiteSpace(video.Title)) onTitle?.Invoke(video.Title);
        var manifest = await youtube.Videos.Streams.GetManifestAsync(videoId, cancellationToken);
        var audio = manifest.GetAudioOnlyStreams().GetWithHighestBitrate()
            ?? throw new InvalidOperationException("YouTube has no audio stream for this video.");
        Directory.CreateDirectory(folderPath);
        var part = Path.Combine(folderPath, videoId + ".part");
        try
        {
            await youtube.Videos.Streams.DownloadAsync(audio, part, progress, cancellationToken);
            onSaving?.Invoke();
            var fileName = UniqueName(folderPath, SafeTitle(video.Title), videoId);
            var output = Path.Combine(folderPath, fileName);
            await EncodeAsync(part, output, cancellationToken);
            return new SavedAudio(video.Title, fileName);
        }
        finally
        {
            if (File.Exists(part)) File.Delete(part);
        }
    }

    public async Task<IReadOnlyList<PlaylistItem>> ListPlaylistAsync(string playlistId, CancellationToken cancellationToken)
    {
        var youtube = new YoutubeClient();
        var items = new List<PlaylistItem>();
        await foreach (var video in youtube.Playlists.GetVideosAsync(playlistId, cancellationToken))
        {
            items.Add(new PlaylistItem(video.Id.Value, video.Title));
        }
        return items;
    }

    private async Task EncodeAsync(string input, string output, CancellationToken cancellationToken)
    {
        if (!FfmpegExists) throw new InvalidOperationException("ffmpeg was not found.");
        var start = new ProcessStartInfo
        {
            FileName = _ffmpegPath,
            Arguments = $"-y -i \"{input}\" -vn -c:a libmp3lame -q:a 0 \"{output}\"",
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardError = true,
        };
        using var process = Process.Start(start) ?? throw new InvalidOperationException("ffmpeg did not start.");
        await using var kill = cancellationToken.Register(() =>
        {
            try { process.Kill(entireProcessTree: true); } catch { /* already gone */ }
        });
        var error = await process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        if (process.ExitCode != 0) throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "ffmpeg failed." : error);
    }

    public static void DeletePartFiles(string musicPath)
    {
        if (!Directory.Exists(musicPath)) return;
        foreach (var part in Directory.EnumerateFiles(musicPath, "*.part", SearchOption.AllDirectories))
        {
            try { File.Delete(part); } catch { /* a locked partial can wait until the next start */ }
        }
    }

    private static string SafeTitle(string title)
    {
        foreach (var bad in Path.GetInvalidFileNameChars()) title = title.Replace(bad, ' ');
        title = title.Trim().TrimEnd('.', ' ');
        if (title.Length > 80) title = title[..80].Trim();
        return string.IsNullOrWhiteSpace(title) ? "track" : title;
    }

    private static string UniqueName(string folder, string title, string videoId)
    {
        var candidate = title + ".mp3";
        if (!File.Exists(Path.Combine(folder, candidate))) return candidate;
        return title + " " + videoId + ".mp3";
    }
}

sealed record SavedAudio(string Title, string FileName);
sealed record PlaylistItem(string VideoId, string Title);
