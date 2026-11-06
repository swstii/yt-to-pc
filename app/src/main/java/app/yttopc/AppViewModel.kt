package app.yttopc

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.yttopc.core.LibraryView
import app.yttopc.core.Queue
import app.yttopc.core.QueueResult
import app.yttopc.core.ShareChoice
import app.yttopc.data.DriveAuth
import app.yttopc.data.DriveFiles
import app.yttopc.data.DriveFolder
import app.yttopc.data.LoadedNotebook
import app.yttopc.data.NeedsSignIn
import app.yttopc.data.NotebookRepository
import app.yttopc.data.await
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Folder(val name: String) : Screen
    data class Share(val text: String, val folder: String? = null) : Screen
    data object Settings : Screen
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
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = NotebookRepository(app)
    private val backStack = ArrayDeque<Screen>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val folderId = repository.folderId()
            val folderName = repository.folderName()
            try {
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
                    is LoadedNotebook.Failed -> _state.update {
                        it.copy(
                            loading = false,
                            busy = false,
                            signedIn = true,
                            notebookChosen = true,
                            driveFolderName = folderName,
                            message = loaded.reason,
                        )
                    }
                    is LoadedNotebook.Ready -> _state.update {
                        it.copy(
                            loading = false,
                            busy = it.busy,
                            signedIn = true,
                            notebookChosen = true,
                            driveFolderName = folderName,
                            library = Queue(loaded.commands, loaded.state).view,
                        )
                    }
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        busy = false,
                        signedIn = error !is NeedsSignIn && folderId != null,
                        notebookChosen = error !is NeedsSignIn && folderId != null,
                        driveFolderName = folderName,
                        message = if (DriveAuth.needsCloudSetup(error)) null else DriveAuth.explain(getApplication(), error),
                    )
                }
            }
        }
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

    fun deleteTrack(folder: String, videoId: String) = submit("Delete queued.", { it.deleteTrack(folder, videoId) })

    fun retry(commandId: String) = submit("Retry queued.", { it.retry(commandId) })

    fun enqueue(text: String, folder: String, choice: ShareChoice) {
        submit("Queued for the laptop.", { it.enqueue(text, folder, choice) })
    }

    private fun submit(success: String, action: (Queue) -> QueueResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val token = DriveAuth.authorize(getApplication()) ?: throw NeedsSignIn()
                val loaded = withContext(Dispatchers.IO) { repository.load(token) }
                if (loaded !is LoadedNotebook.Ready) {
                    _state.update { it.copy(busy = false, message = "Choose the Drive folder first.") }
                    return@launch
                }
                when (val result = action(Queue(loaded.commands, loaded.state))) {
                    is QueueResult.Rejected -> _state.update { it.copy(busy = false, message = result.reason) }
                    is QueueResult.Append -> {
                        withContext(Dispatchers.IO) { repository.append(token, result.command) }
                        val again = withContext(Dispatchers.IO) { repository.load(token) }
                        val library = if (again is LoadedNotebook.Ready) {
                            Queue(again.commands, again.state).view
                        } else {
                            _state.value.library
                        }
                        _state.update {
                            it.copy(busy = false, library = library, notebookChosen = true, message = success)
                        }
                    }
                }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, message = error.message ?: "Could not update the queue.") }
            }
        }
    }
}
