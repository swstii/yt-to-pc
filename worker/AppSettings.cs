namespace YtToPc;

sealed class AppSettings
{
    public string MusicPath { get; set; } = @"E:\ripped_tracks";
    public string QueuePath { get; set; } = "";
    public string DriveFolderId { get; set; } = "";
    public string DriveFolderName { get; set; } = "";
    public string GoogleClientId { get; set; } = "";
    public string GoogleClientSecret { get; set; } = "";
    public string GoogleRefreshToken { get; set; } = "";
    public string FfmpegPath { get; set; } = @"C:\Users\gauta\Downloads\YoutubeDownloader.win-x64\ffmpeg.exe";
    public bool ChecksEnabled { get; set; } = true;
    public bool StartWithWindows { get; set; }

    public static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "yt-to-pc",
        "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new AppSettings();
            return System.Text.Json.JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        var json = System.Text.Json.JsonSerializer.Serialize(this, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(FilePath, json);
    }
}
