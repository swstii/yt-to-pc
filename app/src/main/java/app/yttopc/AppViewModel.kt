package app.yttopc

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.yttopc.core.Command
import app.yttopc.core.LibraryView
import app.yttopc.core.Queue
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
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = NotebookRepository(app)
    private val backStack = ArrayDeque<Screen>()
    private val knownTitles = mutableMapOf<String, String>()
    private val missedTitles = mutableSetOf<String>()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
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
                    is LoadedNotebook.Ready -> _state.update {
                        it.copy(
                            loading = false,
                            busy = it.busy,
                            signedIn = true,
                            notebookChosen = true,
                            offline = false,
                            driveFolderName = folderName,
                            library = present(Queue(loaded.commands, loaded.state).view),
                            storage = loaded.storage ?: it.storage,
                            lastRefreshed = System.currentTimeMillis(),
                        )
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
            backStack.clear()
            _state.value = UiState(loading = false)
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
        _state.update { it.copy(screen = Screen.Share(text, folder)) }
    }

    fun pasteLink(folder: String? = null) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(getApplication())
            ?.toString()
            ?.trim()
            .orEmpty()
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

    fun makeFolder(name: String) = submit("Folder queued.", { it.makeFolder(name) })

    fun removeFolder(name: String) = submit("Folder will be deleted when it is empty on the laptop.", { it.removeFolder(name) })

    fun deleteTrack(folder: String, videoId: String) = deleteTracks(folder, listOf(videoId))

    fun deleteTracks(folder: String, videoIds: List<String>) {
        val ids = videoIds.distinct()
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val token = DriveAuth.authorize(getApplication()) ?: throw NeedsSignIn()
                val loaded = withContext(Dispatchers.IO) { repository.load(token) }
                if (loaded !is LoadedNotebook.Ready) {
                    _state.update { it.copy(busy = false, message = "Choose the Drive folder first.") }
                    return@launch
                }
                var commands = loaded.commands
                val fresh = mutableListOf<Command>()
                for (id in ids) {
                    when (val result = Queue(commands, loaded.state).deleteTrack(folder, id)) {
                        is QueueResult.Rejected -> {
                            if (fresh.isEmpty()) {
                                _state.update { it.copy(busy = false, message = result.reason) }
                                return@launch
                            }
                        }
                        is QueueResult.Append -> {
                            fresh += result.command
                            commands = commands + result.command
                        }
                    }
                }
                withContext(Dispatchers.IO) { repository.appendAll(token, fresh) }
                if (fresh.size == 1) {
                    announce(fresh.first())
                } else {
                    runCatching {
                        QueueNotifier.show(
                            getApplication(),
                            fresh.first().id.hashCode(),
                            "Delete queued",
                            "${fresh.size} songs · $folder",
                        )
                    }
                }
                val again = withContext(Dispatchers.IO) { repository.load(token) }
                val library = if (again is LoadedNotebook.Ready) {
                    present(Queue(again.commands, again.state).view)
                } else {
                    _state.value.library
                }
                val message = if (fresh.size == 1) "Delete queued." else "${fresh.size} deletes queued."
                _state.update { it.copy(busy = false, library = library, notebookChosen = true, message = message) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(busy = false, message = error.message ?: "Could not update the queue.") }
            }
        }
    }

    fun retry(commandId: String) = submit("Retry queued.", { it.retry(commandId) })

    fun enqueue(text: String, folder: String, choice: ShareChoice) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val resolved = if (choice == ShareChoice.SONG) resolvedTitle(text) else null
            finish("Queued.", goHome = true) { it.enqueue(text, folder, choice, resolved) }
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
            for (id in ids) {
                val title = withContext(Dispatchers.IO) { YoutubeTitles.lookup(id) }
                if (title.isNullOrBlank()) missedTitles += id else knownTitles[id] = title
            }
            _state.update { it.copy(library = it.library.withKnownTitles(knownTitles)) }
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
                        val library = if (again is LoadedNotebook.Ready) {
                            present(Queue(again.commands, again.state).view)
                        } else {
                            _state.value.library
                        }
                        if (goHome) backStack.clear()
                        _state.update {
                            it.copy(
                                busy = false,
                                library = library,
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
