@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.yttopc.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.yttopc.data.DriveAuth
import app.yttopc.data.DriveFolder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val activity = LocalContext.current as? Activity
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.notebookChosen) {
        if (!state.notebookChosen || Build.VERSION.SDK_INT < 33 || activity == null) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler {
        if (state.notebookChosen && state.screen != Screen.Home) model.back()
        else activity?.finish()
    }
    LaunchedEffect(state.notebookChosen) {
        if (!state.notebookChosen) return@LaunchedEffect
        while (true) {
            delay(60_000)
            if (!model.state.value.busy) model.refresh()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    GifBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)))
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = if (state.notebookChosen && state.screen == Screen.Home) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.notebookChosen && state.screen != Screen.Home) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    title = { Text(titleFor(state.screen)) },
                    navigationIcon = {
                        if (state.screen != Screen.Settings) {
                            IconButton(onClick = model::back) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.offline) {
            OfflineScreen(padding, model::refresh)
            return@Scaffold
        }
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
            is Screen.Folder -> FolderScreen(padding, state.library, screen.name, screen.focusVideoId, screen.focusCommandId, state.busy, model)
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
private fun OfflineScreen(padding: PaddingValues, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't reach Google", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "YT to PC stays closed until the internet is back.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    }
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
                "One sign-in lets this app update the queue folder in Drive. The laptop reads that same folder. Songs stay on the laptop.",
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
                "Create it here. The laptop reads this folder from Drive. MP3s stay on the laptop disk.",
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
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = creating) { creating = false }
    val needle = query.trim()
    val matchingFolders = if (needle.isEmpty()) library.folders else library.folders.filter { it.name.contains(needle, ignoreCase = true) }
    val matchingSongs = if (needle.isEmpty()) emptyList() else songsMatching(library, needle)
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Search songs") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    colors = searchFieldColors(),
                )
                IconButton(onClick = { open(Screen.Settings) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
            if (library.folders.isEmpty()) {
                Text(
                    "No folders yet. Use the folder button, then the add button for a YouTube link.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else if (needle.isNotEmpty() && matchingFolders.isEmpty() && matchingSongs.isEmpty()) {
                Text(
                    "No song or folder matches that search.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(matchingFolders, key = { "folder-${it.name}" }) { folder ->
                    FolderTile(folder) { open(Screen.Folder(folder.name)) }
                }
                items(matchingSongs, key = { "song-${it.folder}-${it.track.videoId}-${it.track.commandId}" }) { hit ->
                    Card(
                        onClick = { open(Screen.Folder(hit.folder, hit.track.videoId, hit.track.commandId)) },
                        modifier = Modifier.fillMaxWidth().border(2.dp, Color.White, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = homeTileColors(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(hit.folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = { if (!busy) creating = true },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
            FloatingActionButton(
                onClick = { if (!busy) pasteLink() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Paste YouTube link")
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
    focusVideoId: String?,
    focusCommandId: String?,
    busy: Boolean,
    model: AppViewModel,
) {
    val folder = library.folders.find { it.name.equals(name, ignoreCase = true) }
    var pendingDelete by remember { mutableStateOf<TrackView?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    BackHandler(enabled = pendingDelete != null) { pendingDelete = null }
    BackHandler(enabled = confirmRemove) { confirmRemove = false }
    if (folder == null) {
        Text("That folder is not in the queue.", Modifier.padding(padding).padding(20.dp), color = MaterialTheme.colorScheme.onBackground)
        return
    }
    val orderedTracks = folder.tracks.sortedBy { waitingFirst(it.status) }
    LaunchedEffect(name, focusVideoId, focusCommandId) {
        if (focusVideoId.isNullOrBlank()) return@LaunchedEffect
        val index = orderedTracks.indexOfFirst { track ->
            track.videoId == focusVideoId && (focusCommandId == null || track.commandId == focusCommandId)
        }
        if (index < 0) return@LaunchedEffect
        val target = folder.albums.size + index
        val approach = (target - 1).coerceAtLeast(0)
        listState.scrollToItem(approach)
        if (approach != target) listState.animateScrollToItem(target)
    }
    Box(Modifier.fillMaxSize().padding(padding)) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folder.albums.sortedBy { waitingFirst(it.status) }, key = { "album-${it.commandId}" }) { album ->
            AlbumRow(album, busy, onRetry = { model.retry(album.commandId) })
        }
        items(
            orderedTracks,
            key = { "track-${it.videoId}-${it.commandId}" },
        ) { track ->
            val focused = track.videoId == focusVideoId && (focusCommandId == null || track.commandId == focusCommandId)
            TrackRow(track, busy, focused, onDelete = { pendingDelete = track }, onRetry = {
                track.commandId?.let(model::retry)
            })
        }
        item {
            if (folder.canRemove) {
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete empty folder")
                }
            } else if (folder.tracks.isEmpty() && folder.albums.isEmpty()) {
                Text(
                    "This folder can be deleted when nothing is queued.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
        FloatingActionButton(
            onClick = { if (!busy) model.pasteLink(folder.name) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Paste a link into this folder")
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
private fun TrackRow(track: TrackView, busy: Boolean, focused: Boolean = false, onDelete: () -> Unit, onRetry: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .border(if (focused) 3.dp else 2.dp, Color.White, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = homeTileColors(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(trackLabel(track), track.status)
            }
            track.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if ((track.status == ItemStatus.FAILED && track.commandId != null && !track.deletePending) || !track.deletePending) {
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
}

@Composable
private fun AlbumRow(album: AlbumView, busy: Boolean, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = panelColors()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    album.title.ifBlank { "Playlist" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(album.status.label(), album.status)
            }
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
    BackHandler(enabled = creating) { creating = false }
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (picked) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        panelColors()
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (picked) {
                            Text("Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
        LabeledValue("List Name", library.libraryName?.takeIf { it.isNotBlank() } ?: "Not set")
        LabeledValue("Location", musicPath(library))
        LabeledValue("Drive", state.driveFolderName ?: "Not chosen")
        OutlinedButton(onClick = model::changeDriveFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Change Drive folder")
        }
        OutlinedButton(onClick = model::signOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out of Google")
        }
        Spacer(Modifier.weight(1f))
        AssetGif("settings-dj.gif", Modifier.fillMaxWidth().height(240.dp))
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

@Composable
private fun LabeledValue(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append("$label: ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(value) }
        },
        color = MaterialTheme.colorScheme.onBackground,
    )
}

private fun musicPath(library: LibraryView): String =
    library.pendingLibraryRoot ?: library.libraryRoot ?: "The laptop chooses this folder."

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

private fun waitingFirst(status: ItemStatus) = when (status) {
    ItemStatus.DOWNLOADING -> 0
    ItemStatus.PENDING -> 1
    ItemStatus.FAILED -> 2
    else -> 3
}

@Composable
private fun StatusPill(label: String, status: ItemStatus) {
    val color = when (status) {
        ItemStatus.PENDING, ItemStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        ItemStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
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

private data class SongHit(val folder: String, val track: TrackView)

private fun songsMatching(library: LibraryView, needle: String): List<SongHit> {
    return library.folders.flatMap { folder ->
        folder.tracks
            .filter { it.title.contains(needle, ignoreCase = true) }
            .map { SongHit(folder.name, it) }
    }
}

@Composable
private fun FolderTile(folder: FolderView, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = homeTileColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    folder.name,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderSummary(folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val NeonPink = Color(0xFFFF2BD6)

@Composable
private fun homeTileColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun panelColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = Color.Black,
    unfocusedContainerColor = Color.Black,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color(0xFFBDBDBD),
    cursorColor = NeonPink,
    focusedBorderColor = NeonPink,
    unfocusedBorderColor = Color.White,
    focusedLeadingIconColor = Color.White,
    unfocusedLeadingIconColor = Color.White,
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
