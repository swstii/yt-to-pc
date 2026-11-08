package app.yttopc

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.yttopc.core.Command
import app.yttopc.core.FolderNames
import app.yttopc.core.LibraryView
import app.yttopc.core.Queue
import app.yttopc.core.SongNames
import app.yttopc.core.including
import app.yttopc.core.withKnownTitles
import app.yttopc.core.QueueResult
import app.yttopc.core.ParseResult
import app.yttopc.core.ShareChoice
import app.yttopc.core.YoutubeLinks
import app.yttopc.core.YoutubeTarget
import app.yttopc.data.DriveAuth
import app.yttopc.data.DriveFiles
import app.yttopc.data.DriveFolder
import app.yttopc.data.LoadedNotebook
import app.yttopc.data.LocalQueue
import app.yttopc.data.NeedsSignIn
import app.yttopc.data.NotebookRepository
import app.yttopc.data.QueueNotifier
import app.yttopc.data.Reachability
import app.yttopc.data.YoutubeTitles
import app.yttopc.data.await
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Screen {
    data object Home : Screen
    data class Folder(val name: String, val focusVideoId: String? = null, val focusCommandId: String? = null) : Screen
    data class Share(val text: String, val folder: String? = null) : Screen
    data object Settings : Screen
    data object Status : Screen
}

data class UiState(
    val loading: Boolean = true,
    val signedIn: Boolean = false,
    val notebookChosen: Boolean = false,
    val driveFolderName: String? = null,
    val driveFolders: List<DriveFolder> = emptyList(),
    val library: LibraryView = LibraryView(null, null, false, emptyList()),
    val message: String? = null,
    val screen: Screen = Screen.Home,
    val busy: Boolean = false,
    val offline: Boolean = false,
    val storage: app.yttopc.data.StorageReport? = null,
    val lastRefreshed: Long? = null,
    val wallpaper: Boolean = false,
    val accent: String = "pink",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = NotebookRepository(app)
    private val localQueue = LocalQueue(app)
    private var driveLibrary = LibraryView(null, null, false, emptyList())
    private val backStack = ArrayDeque<Screen>()
    private val knownTitles = mutableMapOf<String, String>()
    private val missedTitles = mutableSetOf<String>()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private var primedHistory = false
    private val seenDone = mutableSetOf<String>()
    private val _state = MutableStateFlow(UiState(wallpaper = repository.wallpaperOn(), accent = repository.accent()))
    val state: StateFlow<UiState> = _state

    init {
        runCatching { restoreCachedLibrary() }
        runCatching { refresh() }
        runCatching { flush() }
    }

    private fun restoreCachedLibrary() {
        val cached = repository.cached() ?: return
        if (repository.folderId() == null) return
        localQueue.dropUploaded(cached.commands.map { it.id }.toSet())
        val view = runCatching { Queue(cached.commands, cached.state).view.copy(history = cached.history) }.getOrNull() ?: return
        showLibrary(view)
        _state.value = UiState(
            loading = false,
            signedIn = true,
            notebookChosen = true,
            driveFolderName = repository.folderName(),
            library = shown(),
            storage = cached.storage,
            wallpaper = repository.wallpaperOn(),
            accent = repository.accent(),
        )
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!driveReachable()) {
                showOffline(generation)
                return@launch
            }
            val folderId = repository.folderId()
            val folderName = repository.folderName()
            try {
                _state.update { it.copy(offline = false) }
                val token = DriveAuth.authorize(getApplication())
                if (token == null) {
                    _state.update {
                        it.copy(loading = false, busy = false, signedIn = false, notebookChosen = false, driveFolderName = folderName)
                    }
                    return@launch
                }
                if (folderId == null) {
                    val folders = withContext(Dispatchers.IO) { DriveFiles.listFolders(token) }
                    _state.update {
                        it.copy(
                            loading = false,
                            busy = false,
                            signedIn = true,
                            notebookChosen = false,
                            driveFolders = folders,
                            driveFolderName = null,
                        )
                    }
                    return@launch
                }
                when (val loaded = withContext(Dispatchers.IO) { repository.load(token) }) {
                    LoadedNotebook.Missing -> _state.update {
                        it.copy(loading = false, busy = false, signedIn = true, notebookChosen = false)
                    }
                    is LoadedNotebook.Failed -> if (networkFailure(loaded.reason)) {
                        showOffline(generation)
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                busy = it.busy,
                                signedIn = true,
                                notebookChosen = true,
                                driveFolderName = folderName,
                                message = loaded.reason,
                            )
                        }
                    }
                    is LoadedNotebook.Ready -> {
                        localQueue.dropUploaded(loaded.commands.map { it.id }.toSet())
                        runCatching { showLibrary(Queue(loaded.commands, loaded.state).view.copy(history = loaded.history)) }
                        _state.update {
                            it.copy(
                                loading = false,
                                busy = it.busy,
                                signedIn = true,
                                notebookChosen = true,
                                offline = false,
                                driveFolderName = folderName,
                                library = shown(),
                                storage = loaded.storage ?: it.storage,
                                lastRefreshed = System.currentTimeMillis(),
                            )
                        }
                        flush()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (networkFailure(error)) {
                    showOffline(generation)
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        busy = it.busy,
                        signedIn = error !is NeedsSignIn && folderId != null,
                        notebookChosen = error !is NeedsSignIn && folderId != null,
                        driveFolderName = folderName,
                        message = if (DriveAuth.needsCloudSetup(error)) null else DriveAuth.explain(getApplication(), error),
                    )
                }
            }
        }
    }

    /** The first lookup after the app resumes often fails before the network is ready. */
    private suspend fun driveReachable(): Boolean {
        if (withContext(Dispatchers.IO) { Reachability.driveReachable() }) return true
        delay(800)
        return withContext(Dispatchers.IO) { Reachability.driveReachable() }
    }

    private fun showOffline(generation: Int) {
        if (generation != refreshGeneration) return
        _state.update {
            if (generation != refreshGeneration) return@update it
            it.copy(loading = false, offline = true, message = it.message?.takeUnless { text -> networkFailure(text) })
        }
    }

    private fun networkFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException || current is ConnectException || current is SocketTimeoutException) return true
            if (networkFailure(current.message)) return true
            current = current.cause
        }
        return false
    }

    private fun networkFailure(reason: String?): Boolean {
        val text = reason?.lowercase() ?: return false
        return "unable to resolve host" in text || "failed to connect" in text || "network is unreachable" in text || "timed out" in text
    }

    fun beginSignIn(launch: (IntentSenderRequest) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val client = Identity.getAuthorizationClient(getApplication())
                val result = client.authorize(DriveAuth.request()).await()
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) {
                        _state.update { it.copy(busy = false, message = "Google sign-in could not start.") }
                    } else {
                        _state.update { it.copy(busy = false) }
                        launch(IntentSenderRequest.Builder(pending).build())
                    }
                } else {
                    afterSignIn(result.accessToken)
                }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, message = DriveAuth.explain(getApplication(), error)) }
            }
        }
    }

    fun completeSignIn(intent: Intent) {
        viewModelScope.launch {
            try {
                val result = Identity.getAuthorizationClient(getApplication()).getAuthorizationResultFromIntent(intent)
                afterSignIn(result.accessToken)
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, loading = false, message = DriveAuth.explain(getApplication(), error)) }
            }
        }
    }

    fun signInCancelled() {
        _state.update { it.copy(busy = false, message = "Google sign-in was cancelled.") }
    }

    fun chooseDriveFolder(folder: DriveFolder) {
        if (repository.folderId() != folder.id) localQueue.clear()
        repository.saveFolder(folder.id, folder.name)
        refresh()
    }

    fun createDriveFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = "Type a folder name.") }
            return
        }
        if (trimmed.length > 80 || trimmed.any { it == '/' || it == '\\' }) {
            _state.update { it.copy(message = "Use a short name without slashes.") }
            return
        }
        val existing = _state.value.driveFolders.find { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) {
            chooseDriveFolder(existing)
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val token = DriveAuth.authorize(getApplication()) ?: throw NeedsSignIn()
                val created = withContext(Dispatchers.IO) { DriveFiles.createFolder(token, trimmed) }
                repository.saveFolder(created.id, created.name)
                refresh()
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, message = DriveAuth.explain(getApplication(), error)) }
            }
        }
    }

    fun changeDriveFolder() {
        repository.clearFolder()
        backStack.clear()
        _state.update {
            it.copy(
                screen = Screen.Home,
                notebookChosen = false,
                driveFolderName = null,
                library = LibraryView(null, null, false, emptyList()),
            )
        }
        refresh()
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { DriveAuth.revoke(getApplication()) }
            repository.clear()
            localQueue.clear()
            backStack.clear()
            _state.value = UiState(loading = false, wallpaper = repository.wallpaperOn(), accent = repository.accent())
        }
    }

    private suspend fun afterSignIn(token: String?) {
        if (token.isNullOrBlank()) throw NeedsSignIn()
        if (repository.folderId() == null) {
            val folders = withContext(Dispatchers.IO) { DriveFiles.listFolders(token) }
            _state.update {
                it.copy(loading = false, busy = false, signedIn = true, notebookChosen = false, driveFolders = folders)
            }
        } else {
            refresh()
        }
    }

    fun open(screen: Screen) {
        val current = _state.value.screen
        if (current != screen) backStack.addLast(current)
        _state.update { it.copy(screen = screen) }
    }

    fun openShare(text: String, folder: String? = null) {
        val current = _state.value.screen
        if (current !is Screen.Share) backStack.addLast(current)
        val chosen = folder
            ?: repository.lastMusicFolder()
            ?: _state.value.library.folders.firstOrNull { !it.removePending }?.name
        _state.update { it.copy(screen = Screen.Share(text, chosen)) }
    }

    fun pasteLink(folder: String? = null) {
        val text = runCatching {
            val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
            clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(getApplication())
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        if (text.isEmpty()) {
            _state.update { it.copy(message = "Copy a YouTube link first.") }
            return
        }
        openShare(text, folder)
    }

    fun back() {
        val previous = if (backStack.isEmpty()) Screen.Home else backStack.removeLast()
        _state.update { it.copy(screen = previous) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun toggleWallpaper() {
        val next = !repository.wallpaperOn()
        repository.setWallpaper(next)
        _state.update { it.copy(wallpaper = next) }
    }

    fun setAccent(name: String) {
        repository.setAccent(name)
        _state.update { it.copy(accent = repository.accent()) }
    }

    fun makeFolder(name: String) {
        FolderNames.problem(name)?.let { return rejectEnqueue(it) }
        val cleaned = FolderNames.cleaned(name)
        val existing = _state.value.library.folders.find { FolderNames.same(it.name, cleaned) }
        if (existing != null && existing.createError == null) return rejectEnqueue("That folder already exists.")
        if (existing?.removePending == true) return rejectEnqueue("That folder is waiting to be deleted.")
        queueLocal(listOf(Command.MakeFolder(newId(), now(), cleaned)), "Folder queued.")
    }

    fun removeFolder(name: String) {
        val folder = _state.value.library.folders.find { FolderNames.same(it.name, name) }
            ?: return rejectEnqueue("That folder is not in the queue.")
        if (!folder.canRemove) return rejectEnqueue("That folder still has songs or a queued download.")
        queueLocal(
            listOf(Command.RemoveFolder(newId(), now(), folder.name)),
            "Folder will be deleted when it is empty on the laptop.",
        )
    }

    fun deleteTrack(folder: String, videoId: String) = deleteTracks(folder, listOf(videoId))

    fun deleteTracks(folder: String, videoIds: List<String>) {
        val ids = videoIds.distinct()
        if (ids.isEmpty()) return
        val home = _state.value.library.folders.find { FolderNames.same(it.name, folder) }
            ?: return rejectEnqueue("That folder is not in the queue.")
        val fresh = mutableListOf<Command>()
        var rejection: String? = null
        for (id in ids) {
            val track = home.tracks.find { it.videoId == id }
            when {
                track == null -> if (rejection == null) rejection = "That song is not in this folder."
                track.deletePending -> if (rejection == null) rejection = "That song is already waiting to be deleted."
                else -> fresh += Command.DeleteTrack(newId(), now(), home.name, id)
            }
        }
        if (fresh.isEmpty()) return rejectEnqueue(rejection ?: "That song is not in this folder.")
        val message = if (fresh.size == 1) "Delete queued." else "${fresh.size} deletes queued."
        queueLocal(fresh, message)
    }

    private fun queueLocal(commands: List<Command>, message: String) {
        commands.forEach { localQueue.add(it) }
        _state.update { it.copy(library = shown(), message = message) }
        if (commands.size == 1) {
            announce(commands.first())
        } else {
            val first = commands.first()
            val folder = (first as? Command.DeleteTrack)?.folder ?: ""
            runCatching {
                QueueNotifier.show(getApplication(), first.id.hashCode(), "Delete queued", "${commands.size} songs · $folder")
            }
        }
        flush()
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun now() = Instant.now().toString()

    fun retry(commandId: String) = submit("Retry queued.", { it.retry(commandId) })

    fun enqueue(text: String, folder: String, choice: ShareChoice) {
        val library = _state.value.library
        val target = when (val parsed = YoutubeLinks.parse(text)) {
            is ParseResult.Found -> parsed.target
            ParseResult.NotALink -> return rejectEnqueue("That share does not contain a link.")
            ParseResult.NotYoutube -> return rejectEnqueue("Share a YouTube song or playlist.")
        }
        val destination = library.folders.find { FolderNames.same(it.name, folder) }
        if (destination?.removePending == true) return rejectEnqueue("That folder is waiting to be deleted.")
        if (folder.isBlank()) return rejectEnqueue("Choose a folder first.")
        val command = when (choice) {
            ShareChoice.SONG -> songCommand(target, destination, folder) ?: return
            ShareChoice.PLAYLIST -> playlistCommand(target, destination, folder) ?: return
        }
        rememberFolder(command)
        localQueue.add(command)
        backStack.clear()
        _state.update { it.copy(library = shown(), message = "Queued.", screen = Screen.Home) }
        announce(command)
        flush()
    }

    fun enqueueVideos(folder: String, videos: List<Pair<String, String>>) {
        if (folder.isBlank()) return rejectEnqueue("Choose a folder first.")
        if (videos.isEmpty()) return rejectEnqueue("Choose at least one video.")
        val destination = _state.value.library.folders.find { FolderNames.same(it.name, folder) }
        if (destination?.removePending == true) return rejectEnqueue("That folder is waiting to be deleted.")
        val commands = videos.mapNotNull { (videoId, _) ->
            if (destination != null && destination.tracks.any { it.videoId == videoId && it.blocksRemoval() }) {
                null
            } else {
                Command.EnqueueVideo(newId(), now(), "https://www.youtube.com/watch?v=$videoId", videoId, destination?.name ?: folder)
            }
        }
        if (commands.isEmpty()) return rejectEnqueue("Those songs are already in this folder.")
        commands.forEach { localQueue.add(it) }
        rememberFolder(commands.first())
        backStack.clear()
        val message = if (commands.size == 1) "Queued." else "${commands.size} songs queued."
        commands.forEach { command ->
            val video = command as Command.EnqueueVideo
            val titled = videos.find { it.first == video.videoId }?.second
            if (!titled.isNullOrBlank()) knownTitles[video.videoId] = titled
            announce(command)
        }
        _state.update { it.copy(library = shown(), message = message, screen = Screen.Home) }
        flush()
    }

    private fun rejectEnqueue(reason: String) {
        _state.update { it.copy(message = reason) }
    }

    private fun songCommand(target: YoutubeTarget, folder: app.yttopc.core.FolderView?, folderName: String): Command? {
        val videoId = when (target) {
            is YoutubeTarget.Video -> target.videoId
            is YoutubeTarget.VideoAndPlaylist -> target.videoId
            is YoutubeTarget.Playlist -> return rejectEnqueue("That link is a playlist. Save the whole playlist.").let { null }
        }
        if (folder != null && folder.tracks.any { it.videoId == videoId && it.blocksRemoval() }) {
            return rejectEnqueue("That song is already in this folder.").let { null }
        }
        val known = knownTitles[videoId]
        if (folder != null && !known.isNullOrBlank() && folder.tracks.any { it.videoId != videoId && SongNames.same(it.title, known) && it.blocksRemoval() }) {
            return rejectEnqueue("That song is already in this folder.").let { null }
        }
        return Command.EnqueueVideo(newId(), now(), target.url, videoId, folder?.name ?: folderName)
    }

    private fun playlistCommand(target: YoutubeTarget, folder: app.yttopc.core.FolderView?, folderName: String): Command? {
        val playlistId = when (target) {
            is YoutubeTarget.Playlist -> target.playlistId
            is YoutubeTarget.VideoAndPlaylist -> target.playlistId
            is YoutubeTarget.Video -> return rejectEnqueue("That link is a single song.").let { null }
        }
        if (folder != null && folder.albums.any { it.playlistId == playlistId && it.blocksRemoval() }) {
            return rejectEnqueue("That playlist is already queued for this folder.").let { null }
        }
        return Command.EnqueuePlaylist(newId(), now(), target.url, playlistId, folder?.name ?: folderName)
    }

    private fun rememberFolder(command: Command) {
        val name = when (command) {
            is Command.EnqueueVideo -> command.folder
            is Command.EnqueuePlaylist -> command.folder
            else -> return
        }
        repository.rememberMusicFolder(name)
    }

    private var flushJob: Job? = null

    private fun flush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            try {
                while (true) {
                    val next = localQueue.read().firstOrNull { !it.uploaded && it.rejected == null } ?: break
                    val command = next.command
                    if (command is Command.EnqueueVideo) {
                        val title = resolvedTitle(command.url)
                        val home = driveLibrary.folders.find { FolderNames.same(it.name, command.folder) }
                        if (!title.isNullOrBlank() && home != null && home.tracks.any {
                                it.videoId != command.videoId && SongNames.same(it.title, title) && it.blocksRemoval()
                            }
                        ) {
                            localQueue.reject(command.id, "That song is already in this folder.")
                            _state.update { it.copy(library = shown(), message = "That song is already in this folder.") }
                            continue
                        }
                    }
                    try {
                        val token = DriveAuth.authorize(getApplication()) ?: break
                        withContext(Dispatchers.IO) { repository.append(token, command) }
                        localQueue.markUploaded(command.id)
                        _state.update { it.copy(library = shown()) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        break
                    }
                }
            } finally {
                flushJob = null
            }
        }
    }

    private suspend fun resolvedTitle(text: String): String? {
        val id = when (val parsed = YoutubeLinks.parse(text)) {
            is ParseResult.Found -> when (val target = parsed.target) {
                is YoutubeTarget.Video -> target.videoId
                is YoutubeTarget.VideoAndPlaylist -> target.videoId
                is YoutubeTarget.Playlist -> return null
            }
            else -> return null
        }
        knownTitles[id]?.let { return it }
        val title = withContext(Dispatchers.IO) { YoutubeTitles.lookup(id) }
        if (!title.isNullOrBlank()) knownTitles[id] = title
        return title
    }

    private fun showLibrary(view: LibraryView) {
        runCatching {
            val done = view.history.filter { it.status == "done" }.map { it.commandId }
            val fresh = if (primedHistory) done.filter { it !in seenDone } else emptyList()
            primedHistory = true
            seenDone.addAll(done)
            fresh.forEach { id ->
                val entry = view.history.lastOrNull { it.commandId == id && it.status == "done" } ?: return@forEach
                val name = entry.title.ifBlank { "Song" }
                val text = if (entry.folder.isBlank()) name else "$name · ${entry.folder}"
                runCatching { QueueNotifier.show(getApplication(), id.hashCode(), "Download complete", text) }
            }
            driveLibrary = view
        }
    }

    private fun shown(): LibraryView = runCatching {
        present(driveLibrary.including(localQueue.read()))
    }.getOrDefault(driveLibrary)

    private fun present(library: LibraryView): LibraryView {
        val named = library.withKnownTitles(knownTitles)
        resolveMissingTitles(named)
        return named
    }

    private fun resolveMissingTitles(library: LibraryView) {
        val ids = library.folders.flatMap { folder ->
            folder.tracks.mapNotNull { track ->
                track.videoId.takeIf { !it.startsWith("file:") && (track.title.isBlank() || track.title == it) }
            } + folder.albums.mapNotNull { album ->
                album.playlistId.takeIf { album.title.isBlank() }
            }
        }.distinct().filter { it !in knownTitles && it !in missedTitles }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                for (id in ids) {
                    val title = withContext(Dispatchers.IO) { YoutubeTitles.lookup(id) }
                    if (title.isNullOrBlank()) missedTitles += id else knownTitles[id] = title
                }
                _state.update { it.copy(library = runCatching { it.library.withKnownTitles(knownTitles) }.getOrDefault(it.library)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    private fun announce(command: Command) {
        val (title, text) = when (command) {
            is Command.EnqueueVideo -> "Queued" to "${songName(command.videoId)} · ${command.folder}"
            is Command.EnqueuePlaylist -> "Queued" to command.folder
            is Command.DeleteTrack -> "Delete queued" to "${songName(command.videoId)} · ${command.folder}"
            else -> return
        }
        runCatching { QueueNotifier.show(getApplication(), command.id.hashCode(), title, text) }
    }

    private fun songName(videoId: String): String {
        knownTitles[videoId]?.let { return it }
        val titled = _state.value.library.folders
            .flatMap { it.tracks }
            .find { it.videoId == videoId }
            ?.title
        return titled?.takeIf { it.isNotBlank() && it != videoId } ?: "Song"
    }

    private fun submit(success: String, action: (Queue) -> QueueResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { finish(success, action = action) }
    }

    private suspend fun finish(success: String, goHome: Boolean = false, action: (Queue) -> QueueResult) {
            try {
                val token = DriveAuth.authorize(getApplication()) ?: throw NeedsSignIn()
                val loaded = withContext(Dispatchers.IO) { repository.load(token) }
                if (loaded !is LoadedNotebook.Ready) {
                    _state.update { it.copy(busy = false, message = "Choose the Drive folder first.") }
                    return
                }
                when (val result = action(Queue(loaded.commands, loaded.state))) {
                    is QueueResult.Rejected -> _state.update { it.copy(busy = false, message = result.reason) }
                    is QueueResult.Append -> {
                        withContext(Dispatchers.IO) { repository.append(token, result.command) }
                        announce(result.command)
                        val again = withContext(Dispatchers.IO) { repository.load(token) }
                        if (again is LoadedNotebook.Ready) {
                            runCatching { showLibrary(Queue(again.commands, again.state).view.copy(history = again.history)) }
                        }
                        if (goHome) backStack.clear()
                        _state.update {
                            it.copy(
                                busy = false,
                                library = shown(),
                                notebookChosen = true,
                                message = success,
                                screen = if (goHome) Screen.Home else it.screen,
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, message = error.message ?: "Could not update the queue.") }
            }
    }
}
