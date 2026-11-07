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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import app.yttopc.data.DriveAuth
import app.yttopc.data.DriveFolder
import app.yttopc.data.YoutubePreview
import app.yttopc.data.YoutubeTitles
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    var pickedSongs by remember { mutableStateOf(emptySet<String>()) }
    var folderSearchOpen by remember { mutableStateOf(false) }
    var shareSearchOpen by remember { mutableStateOf(false) }
    var queueReady by remember { mutableStateOf(false) }
    val deletePicked = remember { SongDelete() }
    val closeFolderSearch = remember { SongDelete() }
    val closeShareSearch = remember { SongDelete() }
    val queueNow = remember { SongDelete() }
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.46f)))
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = if (state.notebookChosen && state.screen == Screen.Home) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.notebookChosen && state.screen != Screen.Home) {
                val barColors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                )
                if (state.screen == Screen.Settings) {
                    CenterAlignedTopAppBar(
                        colors = barColors,
                        title = {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(36.dp))
                        },
                    )
                } else {
                    TopAppBar(
                        colors = barColors,
                        title = {
                            if (state.screen !is Screen.Folder) Text(titleFor(state.screen))
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                when {
                                    state.screen is Screen.Folder && folderSearchOpen -> closeFolderSearch.run()
                                    state.screen is Screen.Share && shareSearchOpen -> closeShareSearch.run()
                                    else -> model.back()
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            if (state.screen is Screen.Share) {
                                Button(
                                    onClick = queueNow.run,
                                    enabled = queueReady,
                                    modifier = Modifier.padding(end = 8.dp).height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SoftPink,
                                        contentColor = Color.Black,
                                        disabledContainerColor = Color.White.copy(alpha = 0.22f),
                                        disabledContentColor = Color.White.copy(alpha = 0.55f),
                                    ),
                                ) {
                                    Text("Queue →", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (state.screen is Screen.Folder && pickedSongs.isNotEmpty()) {
                                IconButton(onClick = deletePicked.run) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete selected songs",
                                        tint = Color.White,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (state.offline) {
            OfflineScreen(padding, model::refresh)
            return@Scaffold
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.72f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "LOADING...",
                        color = SoftPink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 10.sp,
                    )
                    PinkDotLoader(Modifier.fillMaxWidth().height(48.dp))
                }
            }
            return@Scaffold
        }
        if (!state.notebookChosen) {
            SetupScreen(padding, state, model) { request -> signIn.launch(request) }
            return@Scaffold
        }
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(padding, state, model::open, model::makeFolder, model::pasteLink)
            is Screen.Folder -> FolderScreen(
                padding,
                state.library,
                screen.name,
                screen.focusVideoId,
                screen.focusCommandId,
                state.busy,
                model,
                onPicked = { ids, delete ->
                    if (pickedSongs != ids) pickedSongs = ids
                    deletePicked.run = delete
                },
                onSearch = { open, close ->
                    if (folderSearchOpen != open) folderSearchOpen = open
                    closeFolderSearch.run = close
                },
            )
            is Screen.Share -> ShareScreen(
                padding,
                state.library,
                screen.text,
                screen.folder,
                state.busy,
                model,
                onBar = { ready, queue, searching, close ->
                    if (queueReady != ready) queueReady = ready
                    queueNow.run = queue
                    if (shareSearchOpen != searching) shareSearchOpen = searching
                    closeShareSearch.run = close
                },
            )
            Screen.Settings -> SettingsScreen(padding, state, model)
            Screen.Status -> StatusScreen(padding, state.library)
        }
    }
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.Home -> "YT to PC"
    is Screen.Folder -> screen.name
    is Screen.Share -> "Queue"
    Screen.Settings -> "Settings"
    Screen.Status -> "Status"
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

@Composable
private fun HomeTopBar(
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    selecting: Boolean,
    storagePulse: Float,
    downloading: Boolean,
    pending: Int,
    storage: app.yttopc.data.StorageReport?,
    selected: Set<String>,
    open: (Screen) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
    ) {
        AnimatedVisibility(
            visible = !searchOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    storageLabel(storage, selected),
                    modifier = Modifier
                        .padding(start = 12.dp, end = 8.dp)
                        .background(
                            if (selecting) SoftPink.copy(alpha = 0.28f * storagePulse) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (selecting) SoftPink.copy(alpha = 0.55f + 0.45f * storagePulse) else Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(42.dp).clickable { open(Screen.Status) },
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Status",
                        tint = if (downloading) SoftPink.copy(alpha = 0.55f + 0.45f * storagePulse) else Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 2.dp)
                            .size(24.dp)
                            .then(
                                if (downloading) {
                                    Modifier.background(SoftPink.copy(alpha = 0.28f * storagePulse), RoundedCornerShape(20.dp))
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    if (pending > 0) {
                        Text(
                            pending.toString(),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 1.dp, top = 1.dp),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp,
                        )
                    }
                }
                IconButton(onClick = { open(Screen.Settings) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            modifier = Modifier.fillMaxWidth().background(Color.Black),
            enter = expandHorizontally(tween(320), Alignment.CenterHorizontally) + fadeIn(tween(220)),
            exit = shrinkHorizontally(tween(220), Alignment.CenterHorizontally) + fadeOut(tween(140)),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(searchFocus),
                singleLine = true,
                label = { Text("Search songs") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = searchFieldColors(),
            )
        }
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
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = searchOpen) {
        query = ""
        searchOpen = false
        focus.clearFocus()
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50)
            runCatching { searchFocus.requestFocus() }
        }
    }
    BackHandler(enabled = creating) { creating = false }
    val selecting = selected.isNotEmpty()
    val storageGlow = rememberInfiniteTransition(label = "storage")
    val storagePulse by storageGlow.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "storagePulse",
    )
    val needle = query.trim()
    val matchingFolders = if (needle.isEmpty()) library.folders else library.folders.filter { it.name.contains(needle, ignoreCase = true) }
    var playIntro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_600)
        playIntro = false
    }
    val matchingSongs = if (needle.isEmpty()) emptyList() else songsMatching(library, needle)
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                searchOpen,
                query,
                { query = it },
                searchFocus,
                selecting,
                storagePulse,
                state.library.folders.any { folder -> folder.tracks.any { it.status == ItemStatus.DOWNLOADING } },
                state.library.activity.size,
                state.storage,
                selected,
                open,
            )
            if (!searchOpen) {
                val listName = library.libraryName?.takeIf { it.isNotBlank() } ?: "Not set"
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, end = 24.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "folders in $listName",
                        Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected.isNotEmpty()) {
                        Text(
                            "${selected.size} selected",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(matchingFolders, key = { _, folder -> "folder-${folder.name}" }) { index, folder ->
                    val picked = selected.any { it.equals(folder.name, ignoreCase = true) }
                    PopIn(index, playIntro) {
                        Column {
                            FolderTile(
                                folder,
                                picked,
                                onClick = {
                                    if (selecting) selected = toggleFolder(selected, folder.name) else open(Screen.Folder(folder.name))
                                },
                                onLongClick = { selected = toggleFolder(selected, folder.name) },
                            )
                            if (index < matchingFolders.lastIndex) FadeLine()
                        }
                    }
                }
                itemsIndexed(matchingSongs, key = { _, hit -> "song-${hit.folder}-${hit.track.videoId}-${hit.track.commandId}" }) { index, hit ->
                    PopIn(matchingFolders.size + index, playIntro) {
                    Card(
                        onClick = { open(Screen.Folder(hit.folder, hit.track.videoId, hit.track.commandId)) },
                        modifier = Modifier.fillMaxWidth().border(2.dp, TilePink, RoundedCornerShape(16.dp)),
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
        }
        if (!searchOpen) {
            FloatingActionButton(
                onClick = { searchOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                containerColor = SoftPink,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = { if (!busy) creating = true },
                containerColor = SoftPink,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
            FloatingActionButton(
                onClick = { if (!busy) pasteLink() },
                containerColor = SoftPink,
                contentColor = Color.Black,
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
    onPicked: (Set<String>, () -> Unit) -> Unit,
    onSearch: (Boolean, () -> Unit) -> Unit,
) {
    val folder = library.folders.find { it.name.equals(name, ignoreCase = true) }
    var selected by remember(name) { mutableStateOf(emptySet<String>()) }
    var confirmSongs by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var query by rememberSaveable(name) { mutableStateOf("") }
    var searchOpen by rememberSaveable(name) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24
        }
    }
    val collapse by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(340),
        label = "folderTitle",
    )
    val askDelete = { confirmSongs = true }
    val closeSearch = {
        query = ""
        searchOpen = false
        focus.clearFocus()
    }
    SideEffect {
        onPicked(selected, askDelete)
        onSearch(searchOpen, closeSearch)
    }
    DisposableEffect(Unit) {
        onDispose {
            onPicked(emptySet(), {})
            onSearch(false, {})
        }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50)
            runCatching { searchFocus.requestFocus() }
        }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = searchOpen) { closeSearch() }
    BackHandler(enabled = confirmSongs) { confirmSongs = false }
    BackHandler(enabled = confirmRemove) { confirmRemove = false }
    if (folder == null) {
        Text("That folder is not in the queue.", Modifier.padding(padding).padding(20.dp), color = MaterialTheme.colorScheme.onBackground)
        return
    }
    val orderedTracks = folder.tracks.sortedBy { waitingFirst(it.status) }
    var playIntro by remember(name) { mutableStateOf(true) }
    LaunchedEffect(name) {
        delay(1_600)
        playIntro = false
    }
    val needle = query.trim()
    val visibleAlbums = folder.albums.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
        .sortedBy { waitingFirst(it.status) }
    val visibleTracks = orderedTracks.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
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
    Column(Modifier.fillMaxSize().padding(padding)) {
    FolderHeader(folder.name, collapse, searchOpen, query, { query = it }, searchFocus, selected.size)
    Box(Modifier.weight(1f)) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (needle.isNotEmpty() && visibleAlbums.isEmpty() && visibleTracks.isEmpty()) {
            item { Text("No song matches that search.", color = MaterialTheme.colorScheme.onBackground) }
        }
        itemsIndexed(visibleAlbums, key = { _, album -> "album-${album.commandId}" }) { index, album ->
            PopIn(index, playIntro) {
                Column {
                    AlbumRow(album, busy, onRetry = { model.retry(album.commandId) })
                    if (index < visibleAlbums.lastIndex || visibleTracks.isNotEmpty()) FadeLine()
                }
            }
        }
        itemsIndexed(
            visibleTracks,
            key = { _, track -> "track-${track.videoId}-${track.commandId}" },
        ) { index, track ->
            val focused = track.videoId == focusVideoId && (focusCommandId == null || track.commandId == focusCommandId)
            val picked = selected.contains(track.videoId)
            val selecting = selected.isNotEmpty()
            PopIn(visibleAlbums.size + index, playIntro) {
                Column {
                    TrackRow(
                        track,
                        busy,
                        focused,
                        picked,
                        onClick = {
                            if (!track.deletePending && (selecting || picked)) selected = toggleSong(selected, track.videoId)
                        },
                        onLongClick = {
                            if (!track.deletePending) selected = toggleSong(selected, track.videoId)
                        },
                        onRetry = { track.commandId?.let(model::retry) },
                    )
                    if (index < visibleTracks.lastIndex) FadeLine()
                }
            }
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
        if (!searchOpen) {
            FloatingActionButton(
                onClick = { searchOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                containerColor = SoftPink,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        FloatingActionButton(
            onClick = { if (!busy) model.pasteLink(folder.name) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = SoftPink,
            contentColor = Color.Black,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Paste a link into this folder")
        }
    }
    }
    if (confirmSongs) {
        val picks = folder.tracks.filter { selected.contains(it.videoId) && !it.deletePending }
        if (picks.isEmpty()) {
            confirmSongs = false
        } else AlertDialog(
            onDismissRequest = { confirmSongs = false },
            title = { Text(if (picks.size == 1) "Delete this song?" else "Delete ${picks.size} songs?") },
            text = { Text(if (picks.size == 1) picks.first().title else picks.joinToString("\n") { it.title }) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSongs = false
                    val ids = picks.map { it.videoId }
                    selected = emptySet()
                    model.deleteTracks(folder.name, ids)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmSongs = false }) { Text("Keep") } },
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
private fun FolderHeader(
    name: String,
    collapse: Float,
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    selectedCount: Int,
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = !searchOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
        ) {
            val font = 66f + (30f - 66f) * collapse
            val line = 70f + (34f - 70f) * collapse
            Column(Modifier.fillMaxWidth()) {
                Text(
                    name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = with(density) { (-10).dp.toPx() } * collapse }
                        .padding(horizontal = 16.dp),
                    color = SoftPink,
                    fontSize = font.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = line.sp,
                    maxLines = if (collapse > 0.55f) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedCount > 0) {
                    Text(
                        "$selectedCount selected",
                        Modifier.fillMaxWidth().padding(top = 6.dp, end = 16.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.height((8f * (1f - collapse) + 40f * (1f - collapse)).dp))
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            modifier = Modifier.fillMaxWidth().background(Color.Black),
            enter = expandHorizontally(tween(320), Alignment.CenterHorizontally) + fadeIn(tween(220)),
            exit = shrinkHorizontally(tween(220), Alignment.CenterHorizontally) + fadeOut(tween(140)),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(searchFocus),
                singleLine = true,
                label = { Text("Search songs") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = searchFieldColors(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TrackRow(
    track: TrackView,
    busy: Boolean,
    focused: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onRetry: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(3.dp, SoftPink, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
            if (track.status == ItemStatus.FAILED && track.commandId != null && !track.deletePending) {
                TextButton(onClick = onRetry, enabled = !busy) { Text("Retry") }
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
private fun QueueSearchBar(
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
) {
    AnimatedVisibility(
        visible = searchOpen,
        modifier = Modifier.fillMaxWidth(),
        enter = expandHorizontally(tween(320), Alignment.CenterHorizontally) + fadeIn(tween(220)),
        exit = shrinkHorizontally(tween(220), Alignment.CenterHorizontally) + fadeOut(tween(140)),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            singleLine = true,
            label = { Text("Search folders") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            colors = searchFieldColors(),
        )
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
    onBar: (Boolean, () -> Unit, Boolean, () -> Unit) -> Unit,
) {
    val parsed = YoutubeLinks.parse(text)
    val target = (parsed as? ParseResult.Found)?.target
    var choice by rememberSaveable(text) { mutableStateOf(if (target is YoutubeTarget.Playlist) ShareChoice.PLAYLIST else ShareChoice.SONG) }
    var selected by rememberSaveable(text, preferredFolder) {
        mutableStateOf(
            preferredFolder?.takeIf { name -> library.folders.any { it.name == name && !it.removePending } },
        )
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(text) { mutableStateOf("") }
    var searchOpen by rememberSaveable(text) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var playIntro by remember { mutableStateOf(true) }
    val closeSearch = {
        query = ""
        searchOpen = false
        focus.clearFocus()
    }
    val queue: () -> Unit = {
        selected?.let { model.enqueue(text, it, choice) }
        Unit
    }
    SideEffect {
        onBar(selected != null && target != null && !busy, queue, searchOpen, closeSearch)
    }
    DisposableEffect(Unit) {
        onDispose { onBar(false, {}, false, {}) }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50)
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        delay(1_600)
        playIntro = false
    }
    BackHandler(enabled = searchOpen) { closeSearch() }
    BackHandler(enabled = creating) { creating = false }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (target == null) {
            Text(shareHeadline(parsed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        } else {
            LinkPreview(previewId(target, choice))
        }
        if (target is YoutubeTarget.VideoAndPlaylist) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice == ShareChoice.SONG, onClick = { choice = ShareChoice.SONG }, label = { Text("This song") })
                FilterChip(selected = choice == ShareChoice.PLAYLIST, onClick = { choice = ShareChoice.PLAYLIST }, label = { Text("Whole playlist") })
            }
        }
        Text("Choose a folder", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        QueueSearchBar(searchOpen, query, { query = it }, searchFocus)
        Box(Modifier.weight(1f)) {
            val needle = query.trim()
            val folders = library.folders.filter { !it.removePending && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true)) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (needle.isNotEmpty() && folders.isEmpty()) {
                    item { Text("No folder matches that search.", color = MaterialTheme.colorScheme.onBackground) }
                }
                itemsIndexed(folders, key = { _, folder -> folder.name }) { index, folder ->
                    val picked = selected == folder.name
                    PopIn(index, playIntro) {
                    Card(
                        onClick = { selected = if (picked) null else folder.name },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (picked) Modifier.border(3.dp, SoftPink, RoundedCornerShape(16.dp)) else Modifier),
                        shape = RoundedCornerShape(16.dp),
                        colors = if (picked) {
                            CardDefaults.cardColors(
                                containerColor = SoftPink.copy(alpha = 0.22f),
                                contentColor = Color.White,
                            )
                        } else {
                            CardDefaults.cardColors(
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = SoftPink,
                            )
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                folder.name,
                                Modifier.weight(1f),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (picked) {
                                Text("Selected", style = MaterialTheme.typography.labelMedium, color = SoftPink, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    }
                }
            }
            if (!searchOpen) {
                FloatingActionButton(
                    onClick = { searchOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    containerColor = SoftPink,
                    contentColor = Color.Black,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search folders")
                }
            }
            SmallFloatingActionButton(
                onClick = { if (!busy) creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = SoftPink,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
        }
    }
    if (busy) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(96.dp), color = Color.White, strokeWidth = 8.dp)
        }
    }
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
private fun StatusScreen(padding: PaddingValues, library: LibraryView) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (library.activity.isEmpty()) {
            Text("Nothing left.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        } else {
            Text(
                "${library.activity.size} pending",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            library.activity.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.kind, color = SoftPink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (item.folder.isBlank()) item.title else "${item.title} · ${item.folder}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val whenText = statusWhen(item.at)
                    if (whenText.isNotEmpty()) {
                        Text(whenText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
        Text(
            refreshedAt(state.lastRefreshed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledValue("List Name", library.libraryName?.takeIf { it.isNotBlank() } ?: "Not set")
        LabeledValue("Location", musicPath(library))
        LabeledValue("Drive", state.driveFolderName ?: "Not chosen")
        Button(
            onClick = model::changeDriveFolder,
            modifier = Modifier.width(220.dp).align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8), contentColor = Color.White),
        ) {
            Text("Change Drive folder", maxLines = 1)
        }
        Button(
            onClick = model::signOut,
            modifier = Modifier.width(220.dp).align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
        ) {
            Text("Sign out of Google", maxLines = 1)
        }
        val links = LocalUriHandler.current
        Text(
            "https://github.com/swstii/yt-to-pc",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { links.openUri("https://github.com/swstii/yt-to-pc") },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        LoopVideo(
            "settings-dj.mp4",
            Modifier.fillMaxWidth().height(220.dp).padding(bottom = 36.dp),
        )
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

private fun statusWhen(at: String): String {
    if (at.isBlank()) return ""
    return runCatching {
        val local = java.time.LocalDateTime.ofInstant(java.time.Instant.parse(at), java.time.ZoneId.systemDefault())
        val pattern = if (local.toLocalDate() == java.time.LocalDate.now()) "h:mm a" else "MMM d, h:mm a"
        java.time.format.DateTimeFormatter.ofPattern(pattern).format(local)
    }.getOrDefault(at)
}

private fun refreshedAt(millis: Long?): String {
    if (millis == null) return "last refreshed at: —"
    val time = java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(
        java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault()),
    )
    return "last refreshed at: $time"
}

private fun storageLabel(report: app.yttopc.data.StorageReport?, selected: Set<String>): String {
    if (report == null) return "—"
    val bytes = if (selected.isEmpty()) {
        report.totalBytes
    } else {
        report.folders.filter { folder -> selected.any { it.equals(folder.name, ignoreCase = true) } }.sumOf { it.bytes }
    }
    return formatBytes(bytes)
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1) return "%.1f MB".format(mb)
    val kb = bytes / 1024.0
    if (kb >= 1) return "%.0f KB".format(kb)
    return "$bytes B"
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
    if (saved > 0) parts += "$saved tracks"
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

private fun previewId(target: YoutubeTarget, choice: ShareChoice): String = when (target) {
    is YoutubeTarget.Video -> target.videoId
    is YoutubeTarget.Playlist -> target.playlistId
    is YoutubeTarget.VideoAndPlaylist -> if (choice == ShareChoice.PLAYLIST) target.playlistId else target.videoId
}

@Composable
private fun LinkPreview(id: String) {
    var preview by remember(id) { mutableStateOf<YoutubePreview?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val loaded = withContext(Dispatchers.IO) { YoutubeTitles.preview(id) }
        if (loaded == null) failed = true else preview = loaded
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = homeTileColors(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbnail = preview?.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = preview?.title,
                    modifier = Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!failed) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    preview?.title ?: if (failed) "Couldn't load the title" else "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val author = preview?.author?.takeIf { it.isNotBlank() }
                if (author != null) {
                    Text(author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
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

private fun appearDelay(index: Int): Long = index.coerceAtMost(8) * 55L

private fun appearFade(index: Int): Int = 680

private fun toggleSong(selected: Set<String>, videoId: String): Set<String> =
    if (videoId in selected) selected - videoId else selected + videoId

private class SongDelete {
    var run: () -> Unit = {}
}

private fun toggleFolder(selected: Set<String>, name: String): Set<String> {
    val picked = selected.any { it.equals(name, ignoreCase = true) }
    return if (picked) selected.filterNot { it.equals(name, ignoreCase = true) }.toSet() else selected + name
}

@Composable
private fun PopIn(index: Int, play: Boolean, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (!play) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        delay(appearDelay(index))
        alpha.animateTo(1f, tween(appearFade(index)))
    }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: FolderView, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    folder.name,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderSummary(folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun PinkDotLoader(modifier: Modifier = Modifier) {
    val travel = rememberInfiniteTransition(label = "dots")
    val head by travel.animateFloat(
        initialValue = -2f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "head",
    )
    Canvas(modifier) {
        val count = 21
        val step = size.width / count
        val mid = (count - 1) / 2f
        val biggest = 7.2.dp.toPx()
        for (index in 0 until count) {
            val fromMid = kotlin.math.abs(index - mid) / mid
            val shape = kotlin.math.cos(fromMid * Math.PI / 2.0).toFloat()
            val distance = kotlin.math.abs(index - head)
            val glow = if (distance < 4.2f) {
                val near = 1f - distance / 4.2f
                near * near
            } else {
                0f
            }
            val center = Offset(step * index + step / 2f, size.height / 2f)
            val radius = biggest * (0.28f + 0.72f * shape)
            if (glow > 0.2f) {
                drawCircle(SoftPink.copy(alpha = 0.28f * glow), radius * 2.1f, center)
            }
            drawCircle(SoftPink.copy(alpha = 0.28f + 0.72f * glow), radius, center)
        }
    }
}

@Composable
private fun FadeLine() {
    Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.White,
                    0.45f to Color.White.copy(alpha = 0.45f),
                    1f to Color.White.copy(alpha = 0f),
                ),
            ),
    )
}

private val SoftPink = Color(0xFFFF9BE3)
private val TilePink = Color(0xFFFFC8F0)

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
    cursorColor = SoftPink,
    focusedBorderColor = SoftPink,
    unfocusedBorderColor = SoftPink.copy(alpha = 0.7f),
    focusedLeadingIconColor = SoftPink,
    unfocusedLeadingIconColor = SoftPink,
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
