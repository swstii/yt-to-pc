using Microsoft.Win32;

namespace YtToPc;

sealed class MainForm : Form
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly QueueWorker _worker;
    private readonly System.Windows.Forms.Timer _timer = new();
    private readonly NotifyIcon _tray = new();
    private readonly Label _status = new() { AutoSize = false };
    private readonly ProgressBar _progress = new() { Minimum = 0, Maximum = 100 };
    private readonly Label _music = new();
    private readonly Label _queue = new();
    private readonly Button _toggle = new();
    private readonly CheckBox _startup = new();
    private CancellationTokenSource _run = new();
    private bool _warned;
    private bool _offlineShown;

    public MainForm()
    {
        _worker = new QueueWorker(_settings);
        Text = "YT to PC";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        ClientSize = new Size(480, 340);
        StartPosition = FormStartPosition.CenterScreen;
        _worker.StatusChanged += () =>
        {
            if (IsHandleCreated) BeginInvoke(RefreshLabels);
        };

        _status.SetBounds(20, 16, 440, 72);
        _progress.SetBounds(20, 92, 440, 18);
        _music.SetBounds(20, 118, 440, 36);
        _queue.SetBounds(20, 156, 440, 28);
        _toggle.SetBounds(20, 196, 210, 36);
        _toggle.Click += (_, _) => SetChecks(!_settings.ChecksEnabled);
        var chooseQueue = new Button { Text = "Queue folder", Bounds = new Rectangle(246, 196, 210, 36) };
        chooseQueue.Click += (_, _) => ChooseQueue();
        var chooseMusic = new Button { Text = "Music folder", Bounds = new Rectangle(20, 248, 210, 36) };
        chooseMusic.Click += (_, _) => ChooseMusic();
        _startup.Text = "Start with Windows";
        _startup.SetBounds(246, 252, 210, 28);
        _startup.Checked = _settings.StartWithWindows;
        _startup.CheckedChanged += (_, _) =>
        {
            _settings.StartWithWindows = _startup.Checked;
            _settings.Save();
            SetStartup(_startup.Checked);
        };

        Controls.AddRange([_status, _progress, _music, _queue, _toggle, chooseQueue, chooseMusic, _startup]);

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

        _timer.Interval = 60_000;
        _timer.Tick += async (_, _) => await TickAsync();

        AudioDownloader.DeletePartFiles(_settings.MusicPath);
        RefreshLabels();
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
        if (!await NetworkGate.DriveReachableAsync())
        {
            _worker.NoteUnreachable();
            RefreshLabels();
            if (!_offlineShown)
            {
                _offlineShown = true;
                ShowAgain();
            }
            return;
        }
        _offlineShown = false;
        RefreshLabels();
        if (_settings.ChecksEnabled && _worker.MissingMusicPath.Length > 0)
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
        if (string.IsNullOrWhiteSpace(_settings.DriveFolderId) && !Directory.Exists(_settings.QueuePath))
        {
            MessageBox.Show(this, "Choose the Drive queue folder first.", "YT to PC");
            return;
        }
        LibraryCatalog catalog;
        try
        {
            catalog = new LibraryBook(_settings).Read();
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "YT to PC");
            return;
        }
        using var dialog = new Form
        {
            Text = "Music folder",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            ClientSize = new Size(460, 360),
            MaximizeBox = false,
            MinimizeBox = false,
        };
        var saved = new ListBox { Bounds = new Rectangle(16, 36, 428, 110) };
        foreach (var entry in catalog.Libraries.OrderBy(item => item.Name, StringComparer.OrdinalIgnoreCase))
            saved.Items.Add(entry.Name);
        var nameBox = new TextBox { Bounds = new Rectangle(16, 176, 428, 24), Text = _settings.LibraryName };
        var pathBox = new TextBox { Bounds = new Rectangle(16, 230, 320, 24), Text = _settings.MusicPath };
        var browse = new Button { Text = "Browse", Bounds = new Rectangle(344, 228, 100, 28) };
        browse.Click += (_, _) =>
        {
            using var folders = new FolderBrowserDialog { Description = "Folder where MP3s are saved", UseDescriptionForTitle = true };
            if (Directory.Exists(pathBox.Text)) folders.SelectedPath = pathBox.Text;
            if (folders.ShowDialog(dialog) == DialogResult.OK) pathBox.Text = folders.SelectedPath;
        };
        saved.SelectedIndexChanged += (_, _) =>
        {
            if (saved.SelectedItem is not string picked) return;
            var entry = catalog.Libraries.FirstOrDefault(item => item.Name.Equals(picked, StringComparison.OrdinalIgnoreCase));
            if (entry == null) return;
            nameBox.Text = entry.Name;
            pathBox.Text = entry.Path;
        };
        var save = new Button { Text = "Use this folder", Bounds = new Rectangle(16, 280, 200, 36) };
        var cancel = new Button { Text = "Cancel", Bounds = new Rectangle(228, 280, 120, 36), DialogResult = DialogResult.Cancel };
        save.Click += (_, _) =>
        {
            var problem = LibraryNames.Problem(nameBox.Text);
            if (problem != null)
            {
                MessageBox.Show(dialog, problem);
                return;
            }
            if (!Directory.Exists(pathBox.Text.Trim()))
            {
                MessageBox.Show(dialog, "That folder is not on this PC.");
                return;
            }
            dialog.Tag = true;
            dialog.Close();
        };
        dialog.Controls.AddRange([
            new Label { Text = "Saved libraries", Bounds = new Rectangle(16, 12, 428, 20) },
            saved,
            new Label { Text = "Name", Bounds = new Rectangle(16, 154, 428, 20) },
            nameBox,
            new Label { Text = "Folder", Bounds = new Rectangle(16, 208, 428, 20) },
            pathBox,
            browse,
            save,
            cancel,
        ]);
        dialog.CancelButton = cancel;
        dialog.ShowDialog(this);
        if (dialog.Tag is not true) return;
        var libraryName = nameBox.Text.Trim();
        var folderPath = pathBox.Text.Trim();
        try
        {
            SwitchLibrary(catalog, libraryName, folderPath);
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "YT to PC");
            return;
        }
        _warned = false;
        RefreshLabels();
        if (_settings.ChecksEnabled) _ = TickAsync();
    }

    private void SwitchLibrary(LibraryCatalog catalog, string libraryName, string folderPath)
    {
        var book = new LibraryBook(_settings);
        var previousName = _settings.LibraryName;
        var target = book.ReadLibrary(libraryName);
        var fresh = target.Folders.Count == 0 && target.Commands.Count == 0;
        if (fresh && !string.IsNullOrWhiteSpace(previousName))
        {
            var previous = book.ReadLibrary(previousName);
            if (previous.Commands.Count > 0) target.Commands = previous.Commands;
        }
        target.LibraryName = libraryName;
        target.LibraryRoot = folderPath;
        target.LibraryReady = true;
        book.WriteLibrary(libraryName, target);
        var entry = catalog.Libraries.FirstOrDefault(item => item.Name.Equals(libraryName, StringComparison.OrdinalIgnoreCase));
        if (entry == null) catalog.Libraries.Add(new LibraryEntry { Name = libraryName, Path = folderPath });
        else entry.Path = folderPath;
        catalog.Active = libraryName;
        book.Write(catalog);
        _settings.LibraryName = libraryName;
        _settings.MusicPath = folderPath;
        _settings.Save();
        _worker.ForgetSaved();
    }

    private void RefreshLabels()
    {
        var missing = _worker.MissingMusicPath;
        _toggle.Text = _settings.ChecksEnabled ? "Pause checks" : "Resume checks";
        var status = _settings.ChecksEnabled ? _worker.Status : "Checks are off. Nothing is downloading.";
        _status.Text = status;
        var progress = _settings.ChecksEnabled ? _worker.ProgressPercent : -1;
        _progress.Visible = progress >= 0;
        if (progress >= 0) _progress.Value = progress;
        var musicName = string.IsNullOrWhiteSpace(_settings.LibraryName) ? "Music" : _settings.LibraryName;
        _music.Text = missing.Length == 0 ? musicName + ": " + _settings.MusicPath : missing;
        _music.ForeColor = missing.Length == 0 ? Color.Black : Color.DarkRed;
        _queue.Text = string.IsNullOrWhiteSpace(_settings.DriveFolderName)
            ? "Queue folder: not chosen yet"
            : "Queue: " + _settings.DriveFolderName;
        var tip = status.Length <= 120 ? status : status[..120];
        _tray.Text = tip;
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
