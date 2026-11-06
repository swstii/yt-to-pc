namespace YtToPc;

static class Program
{
    internal static readonly EventWaitHandle ShowRequest = new(false, EventResetMode.AutoReset, "Local\\YtToPc.Show");

    [STAThread]
    static void Main()
    {
        using var gate = new Mutex(initiallyOwned: true, "Local\\YtToPc", out var created);
        if (!created)
        {
            ShowRequest.Set();
            return;
        }
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainForm());
    }
}
