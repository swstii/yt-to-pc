# YT to PC

Queue a YouTube song or playlist on your phone. The laptop saves the MP3 into a folder on your disk. The phone never writes the music files, and the music files never go to Google Drive.

The two apps share one Drive folder. The phone appends requests. The laptop is the only writer of the library state.

## What each side does

The Android app (`app`, package `app.yttopc`) is the queue. You pick a folder, paste a link, and leave. It can also create folders, delete songs, and retry a failed download. Status lists what is still waiting, in the order it was written, with the time each request was added.

The Windows tray app (`worker`) scans that folder about once a minute while checks are on and the music path exists. It creates folders, deletes tracks, and downloads MP3s with ffmpeg. While the music path is missing it only checks the path on this PC. **Reload** checks immediately and, if the disk is back, continues the queue.

## Drive notebook

Both apps use a Drive folder you choose. Scope is `drive.file`, so each app only sees files it created or that you picked.

| File | Who writes it | What it is |
| --- | --- | --- |
| `commands.jsonl` | Phone, append only | One JSON command per line |
| `libraries.json` | Laptop | Named libraries and which one is active |
| `state/<library>/state.json` | Laptop | Folders, tracks, and the outcome of each command |
| `state/<library>/storage.json` | Laptop | Disk usage for the music path |

A command is still waiting when its id is absent from `state.json`. Downloads run in file order. Folder creates and deletes also stay in file order, and the laptop applies those at the start of a scan, before it starts the next waiting download.

A song is a duplicate only when the full title matches an existing track in that folder, after trim, ignoring case. `Official Video`, `Official Audio`, and `[HQ]` are different songs.

MP3s stay on the laptop. The default music path is `E:\ripped_tracks`. The tray app stores its own settings, including the Drive folder, library name, music path, and ffmpeg path, in `%AppData%\yt-to-pc\settings.json`. That file holds the Google refresh token. Do not commit it.

## Laptop

Requires Windows, the .NET 8 SDK, and ffmpeg.

```powershell
dotnet build worker\YtToPc.csproj -c Release
worker\bin\Release\net8.0-windows\YtToPc.exe
```

On first launch, create a Desktop OAuth client in the same Google Cloud project, paste the client id and secret into the tray window, and choose the Drive queue folder. Then choose the music folder. Enable the Drive API on the project before signing in.

Pause checks from the tray window when you do not want downloads. Start with Windows is optional.

## Phone

Requires JDK 17 and an Android SDK. `minSdk` is 26.

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is `app\build\outputs\apk\debug\app-debug.apk`.

Sign in with Google, then choose or create the same Drive folder the laptop uses. If sign-in reports that Cloud does not know the app, add an Android OAuth client for package `app.yttopc` and the SHA-1 printed on the setup screen.

Share a YouTube link into YT to PC, or open Queue from a folder and paste the link there. A successful add returns to the home screen. The count on the status icon is the number of waiting downloads, folder creates, and deletes.

## Layout

- `app` is the Jetpack Compose phone UI.
- `core` is the shared queue model: commands, state, and duplicate titles. Phone and tests use it.
- `worker` is the .NET 8 WinForms tray app.
