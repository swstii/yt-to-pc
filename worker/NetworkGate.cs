namespace YtToPc;

static class NetworkGate
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };

    public static async Task<bool> DriveReachableAsync()
    {
        try
        {
            using var response = await Http.GetAsync("https://www.googleapis.com/drive/v3/about?fields=user");
            return (int)response.StatusCode < 500;
        }
        catch
        {
            return false;
        }
    }
}
