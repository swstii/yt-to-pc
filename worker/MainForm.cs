using Microsoft.Win32;

namespace YtToPc;

sealed class MainForm : Form
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly QueueWorker _worker;
    private readonly System.Windows.Forms.Timer _timer = new();
    private readonly NotifyIcon _tray = new();
    private readonly Label _status = new();
    private readonly Label _music = new();
    private readonly Label _queue = new();
    private readonly Button _toggle = new();
    private readonly CheckBox _startup = new();
    private CancellationTokenSource _run = new();
    private bool _warned;

    public MainForm()
    {
        _worker = new QueueWorker(_settings);
        Text = "YT to PC";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        ClientSize = new Size(460, 280);
        StartPosition = FormStartPosition.CenterScreen;

        _status.SetBounds(20, 16, 420, 48);
        _music.SetBounds(20, 72, 420, 36);
        _queue.SetBounds(20, 112, 420, 36);
        _toggle.SetBounds(20, 164, 200, 36);
        _toggle.Click += (_, _) => SetChecks(!_settings.ChecksEnabled);
        var chooseQueue = new Button { Text = "Queue folder", Bounds = new Rectangle(232, 164, 200, 36) };
        chooseQueue.Click += (_, _) => ChooseQueue();
        var chooseMusic = new Button { Text = "Music folder", Bounds = new Rectangle(20, 212, 200, 36) };
        chooseMusic.Click += (_, _) => ChooseMusic();
        _startup.Text = "Start with Windows";
        _startup.SetBounds(232, 216, 200, 28);
        _startup.Checked = _settings.StartWithWindows;
        _startup.CheckedChanged += (_, _) =>
        {
            _settings.StartWithWindows = _startup.Checked;
            _settings.Save();
            SetStartup(_startup.Checked);
        };

        Controls.AddRange([_status, _music, _queue, _toggle, chooseQueue, chooseMusic, _startup]);

        var icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        if (icon != null) Icon = icon;
        _tray.Icon = icon ?? System.Drawing.SystemIcons.Application;
        _tray.Visible = true;
        _tray.MouseClick += (_, args) =>
        {
            if (args.Button == MouseButtons.Left) ShowAgain();
        };
        _tray.DoubleClick += (_, _) => ShowAgain();
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowAgain());
        menu.Items.Add("Pause or resume checks", null, (_, _) => SetChecks(!_settings.ChecksEnabled));
        menu.Items.Add("Exit", null, (_, _) => ExitApplication());
        _tray.ContextMenuStrip = menu;

        _timer.Interval = 30_000;
        _timer.Tick += async (_, _) => await TickAsync();

        AudioDownloader.DeletePartFiles(_settings.MusicPath);
        RefreshLabels();
        var missing = _worker.MissingMusicPath;
        if (missing.Length > 0)
        {
            _warned = true;
            Shown += (_, _) => MessageBox.Show(this, missing, "YT to PC", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        if (string.IsNullOrWhiteSpace(_settings.DriveFolderId) && string.IsNullOrWhiteSpace(_settings.QueuePath))
        {
            Shown += (_, _) => ShowAgain();
        }
        if (_settings.ChecksEnabled) _timer.Start();
        Load += async (_, _) =>
        {
            if (_settings.ChecksEnabled) await TickAsync();
        };
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        ThreadPool.RegisterWaitForSingleObject(
            Program.ShowRequest,
            (_, _) => BeginInvoke(ShowAgain),
            null,
            Timeout.Infinite,
            executeOnlyOnce: false);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        _tray.Visible = false;
        _run.Cancel();
        base.OnFormClosing(e);
    }

    private void ShowAgain()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private async Task TickAsync()
    {
        if (!_settings.ChecksEnabled) return;
        RefreshLabels();
        if (_worker.MissingMusicPath.Length > 0)
        {
            if (!_warned)
            {
                _warned = true;
                _tray.ShowBalloonTip(4000, "YT to PC", _worker.MissingMusicPath, ToolTipIcon.Warning);
            }
        }
        else
        {
            _warned = false;
        }
        await _worker.TickAsync(_run.Token);
        RefreshLabels();
    }

    private void SetChecks(bool enabled)
    {
        _settings.ChecksEnabled = enabled;
        _settings.Save();
        if (enabled)
        {
            _run = new CancellationTokenSource();
            _timer.Start();
            BeginInvoke(async () => await TickAsync());
        }
        else
        {
            _timer.Stop();
            _run.Cancel();
        }
        RefreshLabels();
    }

    private async void ChooseQueue()
    {
        try
        {
            if (!EnsureGoogleClient()) return;
            var picked = await DriveClient.PickFolderAsync(_settings);
            if (picked == null) return;
            _settings.DriveFolderId = picked.Id;
            _settings.DriveFolderName = picked.Name;
            _settings.Save();
            RefreshLabels();
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "YT to PC", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private bool EnsureGoogleClient()
    {
        if (_settings.GoogleClientId.Trim().Length > 0) return true;
        using var dialog = new Form
        {
            Text = "Connect Google Drive",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MaximizeBox = false,
            MinimizeBox = false,
            ClientSize = new Size(520, 280),
        };
        var help = new Label
        {
            Text = "In the same Google Cloud project, create a Desktop OAuth client. Paste it here once. This does not download your Drive.",
            Bounds = new Rectangle(16, 12, 488, 48),
        };
        var open = new Button { Text = "Open Google clients", Bounds = new Rectangle(16, 68, 180, 32) };
        open.Click += (_, _) => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://console.cloud.google.com/auth/clients") { UseShellExecute = true });
        var idBox = new TextBox { Bounds = new Rectangle(16, 116, 488, 24), PlaceholderText = "Client ID" };
        var secretBox = new TextBox { Bounds = new Rectangle(16, 152, 488, 24), PlaceholderText = "Client secret" };
        var save = new Button { Text = "Save and choose folder", Bounds = new Rectangle(16, 200, 200, 36) };
        var cancel = new Button { Text = "Cancel", Bounds = new Rectangle(228, 200, 120, 36), DialogResult = DialogResult.Cancel };
        var accepted = false;
        save.Click += (_, _) =>
        {
            if (idBox.Text.Trim().Length == 0)
            {
                MessageBox.Show(dialog, "Paste the Desktop client ID.");
                return;
            }
            _settings.GoogleClientId = idBox.Text.Trim();
            _settings.GoogleClientSecret = secretBox.Text.Trim();
            _settings.Save();
            accepted = true;
            dialog.Close();
        };
        dialog.Controls.AddRange([help, open, idBox, secretBox, save, cancel]);
        dialog.CancelButton = cancel;
        dialog.ShowDialog(this);
        return accepted;
    }

    private void ChooseMusic()
    {
        using var dialog = new FolderBrowserDialog { Description = "Folder on the SSD where MP3s are saved", UseDescriptionForTitle = true };
        if (Directory.Exists(_settings.MusicPath)) dialog.SelectedPath = _settings.MusicPath;
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        _settings.MusicPath = dialog.SelectedPath;
        _settings.Save();
        _warned = false;
        RefreshLabels();
    }

    private void RefreshLabels()
    {
        var missing = _worker.MissingMusicPath;
        _toggle.Text = _settings.ChecksEnabled ? "Pause checks" : "Resume checks";
        _status.Text = _settings.ChecksEnabled ? _worker.Status : "Checks are off. Nothing is downloading.";
        _music.Text = missing.Length == 0 ? "Music: " + _settings.MusicPath : missing;
        _music.ForeColor = missing.Length == 0 ? Color.Black : Color.DarkRed;
        _queue.Text = string.IsNullOrWhiteSpace(_settings.DriveFolderName)
            ? "Queue folder: not chosen yet"
            : "Queue: " + _settings.DriveFolderName;
        _tray.Text = _settings.ChecksEnabled ? "YT to PC — checks on" : "YT to PC — checks off";
    }

    private void ExitApplication()
    {
        _run.Cancel();
        _tray.Visible = false;
        Application.Exit();
    }

    private static void SetStartup(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
        if (key == null) return;
        if (enabled) key.SetValue("YtToPc", "\"" + Application.ExecutablePath + "\"");
        else key.DeleteValue("YtToPc", throwOnMissingValue: false);
    }
}
