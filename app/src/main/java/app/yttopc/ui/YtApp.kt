@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.yttopc.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.yttopc.data.DriveAuth
import app.yttopc.data.DriveFolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yttopc.AppViewModel
import app.yttopc.Screen
import app.yttopc.core.AlbumView
import app.yttopc.core.FolderView
import app.yttopc.core.ItemStatus
import app.yttopc.core.LibraryView
import app.yttopc.core.ParseResult
import app.yttopc.core.ShareChoice
import app.yttopc.core.TrackView
import app.yttopc.core.YoutubeLinks
import app.yttopc.core.YoutubeTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) model.completeSignIn(data)
        else model.signInCancelled()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        model.consumeMessage()
    }
    LaunchedEffect(state.notebookChosen) {
        if (!state.notebookChosen) return@LaunchedEffect
        while (true) {
            delay(30_000)
            if (!model.state.value.busy) model.refresh()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    GifBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.84f)))
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text(titleFor(state.screen)) },
                navigationIcon = {
                    if (state.notebookChosen && state.screen != Screen.Home) {
                        TextButton(onClick = model::back) { Text("Back") }
                    }
                },
                actions = {
                    if (state.notebookChosen && state.screen == Screen.Home) {
                        TextButton(onClick = { model.open(Screen.Settings) }) { Text("Settings") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            return@Scaffold
        }
        if (!state.notebookChosen) {
            SetupScreen(padding, state, model) { request -> signIn.launch(request) }
            return@Scaffold
        }
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(padding, state, model::open, model::makeFolder, model::pasteLink)
            is Screen.Folder -> FolderScreen(padding, state.library, screen.name, state.busy, model)
            is Screen.Share -> ShareScreen(padding, state.library, screen.text, screen.folder, state.busy, model)
            Screen.Settings -> SettingsScreen(padding, state, model)
        }
    }
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.Home -> "YT to PC"
    is Screen.Folder -> screen.name
    is Screen.Share -> "Queue"
    Screen.Settings -> "Settings"
}

@Composable
private fun SetupScreen(
    padding: PaddingValues,
    state: app.yttopc.UiState,
    model: AppViewModel,
    onSignIn: (androidx.activity.result.IntentSenderRequest) -> Unit,
) {
    val sha1 = DriveAuth.certificateSha1(LocalContext.current)
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.signedIn) {
            Text("Sign in to Google Drive", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "One sign-in lets this app update the queue folder in Drive. The laptop opens that same folder with Drive for desktop.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.screen is Screen.Share) {
                Text("A YouTube link is waiting. Pick the Drive folder next.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { model.beginSignIn(onSignIn) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with Google")
            }
            Text(
                "Package app.yttopc · SHA-1 $sha1",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Choose the Drive folder", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Create it here. It shows up in Drive, and on the laptop inside Drive for desktop. MP3s stay on the laptop disk.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.driveFolders.isEmpty()) {
                Text("No queue folder yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.driveFolders, key = { it.id }) { folder ->
                    DriveFolderRow(folder, state.busy) { model.chooseDriveFolder(folder) }
                }
            }
            var name by rememberSaveable { mutableStateOf("YT to PC") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("New Drive folder") },
                colors = fieldColors(),
            )
            Button(onClick = { model.createDriveFolder(name) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Create and use this folder")
            }
        }
    }
}

@Composable
private fun DriveFolderRow(folder: DriveFolder, busy: Boolean, onPick: () -> Unit) {
    Card(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = panelColors()) {
        Text(folder.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    padding: PaddingValues,
    state: app.yttopc.UiState,
    open: (Screen) -> Unit,
    makeFolder: (String) -> Unit,
    pasteLink: () -> Unit,
) {
    val library = state.library
    val busy = state.busy
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rootLine(library), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            state.driveFolderName?.let { name ->
                Text("Drive · $name", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (library.folders.isEmpty()) {
            Text(
                "No folders yet. Create one, then share a YouTube song or playlist into it.",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(library.folders, key = { it.name }) { folder ->
                Card(onClick = { open(Screen.Folder(folder.name)) }, modifier = Modifier.fillMaxWidth(), colors = panelColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(folder.name, style = MaterialTheme.typography.titleMedium)
                        Text(folderSummary(folder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = pasteLink, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Paste YouTube link")
            }
            OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("New folder")
            }
        }
    }
    if (creating) {
        NameDialog(
            title = "New folder",
            confirm = "Queue folder",
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                makeFolder(it)
            },
        )
    }
}

@Composable
private fun FolderScreen(
    padding: PaddingValues,
    library: LibraryView,
    name: String,
    busy: Boolean,
    model: AppViewModel,
) {
    val folder = library.folders.find { it.name.equals(name, ignoreCase = true) }
    var pendingDelete by remember { mutableStateOf<TrackView?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (folder == null) {
        Text("That folder is not in the queue.", Modifier.padding(padding).padding(20.dp), color = MaterialTheme.colorScheme.onBackground)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Button(
                onClick = { model.pasteLink(folder.name) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Paste a link into this folder") }
        }
        items(folder.albums, key = { "album-${it.commandId}" }) { album ->
            AlbumRow(album, busy, onRetry = { model.retry(album.commandId) })
        }
        items(folder.tracks, key = { "track-${it.videoId}-${it.commandId}" }) { track ->
            TrackRow(track, busy, onDelete = { pendingDelete = track }, onRetry = {
                track.commandId?.let(model::retry)
            })
        }
        item {
            if (folder.canRemove) {
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete empty folder")
                }
            } else {
                Text("A folder can be deleted when it has no songs and nothing queued.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    pendingDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this song?") },
            text = { Text(track.title) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    model.deleteTrack(folder.name, track.videoId)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("The laptop deletes it only while it is empty.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    model.removeFolder(folder.name)
                    model.back()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TrackRow(track: TrackView, busy: Boolean, onDelete: () -> Unit, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = panelColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Text(trackLabel(track), color = MaterialTheme.colorScheme.onSurfaceVariant)
            track.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (track.status == ItemStatus.FAILED && track.commandId != null && !track.deletePending) {
                    TextButton(onClick = onRetry, enabled = !busy) { Text("Retry") }
                }
                if (!track.deletePending) {
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: AlbumView, busy: Boolean, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = panelColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Playlist", style = MaterialTheme.typography.titleMedium)
            Text(album.playlistId, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(album.status.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            album.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (album.status == ItemStatus.FAILED) {
                TextButton(onClick = onRetry, enabled = !busy) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ShareScreen(
    padding: PaddingValues,
    library: LibraryView,
    text: String,
    preferredFolder: String?,
    busy: Boolean,
    model: AppViewModel,
) {
    val parsed = YoutubeLinks.parse(text)
    val target = (parsed as? ParseResult.Found)?.target
    var choice by rememberSaveable(text) { mutableStateOf(if (target is YoutubeTarget.Playlist) ShareChoice.PLAYLIST else ShareChoice.SONG) }
    var selected by rememberSaveable(text, preferredFolder) {
        mutableStateOf(
            preferredFolder?.takeIf { name -> library.folders.any { it.name == name && !it.removePending } }
                ?: library.folders.firstOrNull { !it.removePending }?.name,
        )
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(shareHeadline(parsed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        if (target is YoutubeTarget.VideoAndPlaylist) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice == ShareChoice.SONG, onClick = { choice = ShareChoice.SONG }, label = { Text("This song") })
                FilterChip(selected = choice == ShareChoice.PLAYLIST, onClick = { choice = ShareChoice.PLAYLIST }, label = { Text("Whole playlist") })
            }
        }
        Text("Folder", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(library.folders.filter { !it.removePending }, key = { it.name }) { folder ->
                val picked = selected == folder.name
                Card(
                    onClick = { selected = folder.name },
                    modifier = Modifier.fillMaxWidth().then(
                        if (picked) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier,
                    ),
                    colors = panelColors(),
                ) {
                    Text(
                        folder.name,
                        Modifier.padding(16.dp),
                        color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("New folder")
        }
        Button(
            onClick = { selected?.let { model.enqueue(text, it, choice) } },
            enabled = !busy && selected != null && target != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Queue") }
    }
    if (creating) {
        NameDialog("New folder", "Queue folder", { creating = false }) { name ->
            creating = false
            selected = name.trim()
            model.makeFolder(name)
        }
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    state: app.yttopc.UiState,
    model: AppViewModel,
) {
    val library = state.library
    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Where MP3s are saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(rootLine(library), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Queue folder in Drive: ${state.driveFolderName ?: "not chosen"}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = model::changeDriveFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Change Drive folder")
        }
        OutlinedButton(onClick = model::signOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out of Google")
        }
    }
}

@Composable
private fun NameDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                colors = fieldColors(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun rootLine(library: LibraryView): String {
    library.pendingLibraryRoot?.let { return "Laptop will save MP3s to $it after it checks the queue." }
    val root = library.libraryRoot ?: return "The laptop program chooses this folder. It shows up here after that."
    if (!library.libraryReady) return "Music folder is $root. The laptop has not confirmed that disk is connected."
    return "Saving MP3s to $root"
}

private fun folderSummary(folder: FolderView): String {
    val parts = mutableListOf<String>()
    if (folder.createPending) parts += if (folder.createError == null) "Waiting to be created" else "Could not be created"
    if (folder.removePending) parts += "Waiting to be deleted"
    val waiting = folder.tracks.count { it.status == ItemStatus.PENDING || it.status == ItemStatus.DOWNLOADING } +
        folder.albums.count { it.status == ItemStatus.PENDING }
    val saved = folder.tracks.count { it.status == ItemStatus.DONE || it.status == ItemStatus.SKIPPED }
    if (saved > 0) parts += "$saved on the laptop"
    if (waiting > 0) parts += "$waiting waiting"
    val failed = folder.tracks.count { it.status == ItemStatus.FAILED } + folder.albums.count { it.status == ItemStatus.FAILED }
    if (failed > 0) parts += "$failed failed"
    return parts.joinToString(" · ").ifBlank { "Empty" }
}

private fun trackLabel(track: TrackView): String {
    if (track.deletePending) return "Deleting"
    return track.status.label()
}

private fun ItemStatus.label(): String = when (this) {
    ItemStatus.PENDING -> "Pending"
    ItemStatus.DOWNLOADING -> "Downloading"
    ItemStatus.DONE -> "Done"
    ItemStatus.SKIPPED -> "Skipped"
    ItemStatus.FAILED -> "Failed"
    ItemStatus.EXPANDED -> "Added"
    ItemStatus.UNKNOWN -> "Unknown"
}

private fun shareHeadline(parsed: ParseResult): String = when (parsed) {
    is ParseResult.Found -> when (val target = parsed.target) {
        is YoutubeTarget.Video -> "Save this song"
        is YoutubeTarget.Playlist -> "Save this playlist into one folder. Each video becomes its own MP3."
        is YoutubeTarget.VideoAndPlaylist -> "This link has a song and a playlist."
    }
    ParseResult.NotALink -> "That share does not contain a link."
    ParseResult.NotYoutube -> "Share a YouTube song or playlist."
}

@Composable
private fun panelColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
