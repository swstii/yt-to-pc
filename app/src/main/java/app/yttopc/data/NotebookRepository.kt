package app.yttopc.data

import android.content.Context
import app.yttopc.core.Command
import app.yttopc.core.HistoryEntry
import app.yttopc.core.LibraryState
import app.yttopc.core.NotebookJson
import org.json.JSONObject
import java.io.File

sealed class LoadedNotebook {
    data object Missing : LoadedNotebook()
    data class Ready(
        val commands: List<Command>,
        val state: LibraryState,
        val storage: StorageReport? = null,
        val history: List<HistoryEntry> = emptyList(),
    ) : LoadedNotebook()
    data class Failed(val reason: String) : LoadedNotebook()
}

class NotebookRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile = File(context.applicationContext.filesDir, "notebook-cache.json")
    private val lock = Any()

    fun cached(): LoadedNotebook.Ready? {
        val text = runCatching { cacheFile.readText() }.getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(text)
            val commandsText = json.optString("commands")
            val stateText = json.optString("state")
            val historyText = json.optString("history")
            val state = if (stateText.isBlank()) LibraryState() else NotebookJson.readState(stateText)
            LoadedNotebook.Ready(
                NotebookJson.readCommands(commandsText),
                state,
                json.optString("storage").takeIf { it.isNotBlank() }?.let(::parseStorage),
                NotebookJson.readHistory(historyText),
            )
        }.getOrNull()
    }

    fun folderId(): String? = prefs.getString(KEY_FOLDER_ID, null)

    fun folderName(): String? = prefs.getString(KEY_FOLDER_NAME, null)

    fun lastMusicFolder(): String? = prefs.getString(KEY_LAST_MUSIC_FOLDER, null)?.takeIf { it.isNotBlank() }

    fun rememberMusicFolder(name: String) {
        if (name.isBlank()) return
        prefs.edit().putString(KEY_LAST_MUSIC_FOLDER, name).apply()
    }

    fun saveFolder(id: String, name: String) {
        prefs.edit().putString(KEY_FOLDER_ID, id).putString(KEY_FOLDER_NAME, name).apply()
    }

    fun clearFolder() {
        prefs.edit().remove(KEY_FOLDER_ID).remove(KEY_FOLDER_NAME).apply()
    }

    fun wallpaperOn(): Boolean = prefs.getBoolean(KEY_WALLPAPER, false)

    fun setWallpaper(on: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER, on).apply()
    }

    fun accent(): String = prefs.getString(KEY_ACCENT, "pink")?.takeIf { it in AccentKeys } ?: "pink"

    fun setAccent(name: String) {
        if (name !in AccentKeys) return
        prefs.edit().putString(KEY_ACCENT, name).apply()
    }

    fun clear() {
        val wallpaper = wallpaperOn()
        val accent = accent()
        prefs.edit().clear().apply()
        prefs.edit().putBoolean(KEY_WALLPAPER, wallpaper).putString(KEY_ACCENT, accent).apply()
        cacheFile.delete()
    }

    private fun remember(commandsText: String, stateText: String, historyText: String, storageText: String?) {
        runCatching {
            val json = JSONObject()
                .put("commands", commandsText)
                .put("state", stateText)
                .put("history", historyText)
                .put("storage", storageText ?: "")
            cacheFile.writeText(json.toString())
        }
    }

    fun load(token: String): LoadedNotebook {
        val folderId = folderId() ?: return LoadedNotebook.Missing
        return try {
            synchronized(lock) {
                val commandsText = DriveFiles.readNamed(token, folderId, COMMANDS).orEmpty()
                val commands = NotebookJson.readCommands(commandsText)
                val catalog = DriveFiles.readNamed(token, folderId, LIBRARIES)
                val active = activeLibrary(catalog)
                val stateText = readNestedState(token, folderId, active)
                    ?: DriveFiles.readNamed(token, folderId, legacyStateName(active))
                    ?: if (active == null) null else DriveFiles.readNamed(token, folderId, STATE)
                val state = if (stateText.isNullOrBlank()) LibraryState() else NotebookJson.readState(stateText)
                val storageText = readStorage(token, folderId, active)
                val storage = storageText?.let(::parseStorage)
                val historyText = DriveFiles.readNamed(token, folderId, HISTORY).orEmpty()
                val ready = LoadedNotebook.Ready(commands, state, storage, NotebookJson.readHistory(historyText))
                remember(commandsText, stateText.orEmpty(), historyText, storageText)
                ready
            }
        } catch (error: Exception) {
            LoadedNotebook.Failed(error.message ?: "Could not read the queue.")
        }
    }

    fun append(token: String, command: Command) = appendAll(token, listOf(command))

    fun appendAll(token: String, commands: List<Command>) {
        if (commands.isEmpty()) return
        val folderId = folderId() ?: error("Choose the Drive folder first.")
        synchronized(lock) {
            val existing = DriveFiles.readNamed(token, folderId, COMMANDS).orEmpty()
            val present = NotebookJson.readCommands(existing).map { it.id }.toSet()
            val fresh = commands.filter { it.id !in present }
            if (fresh.isEmpty()) return
            val body = if (existing.isEmpty() || existing.endsWith("\n")) existing else "$existing\n"
            val lines = fresh.joinToString("") { NotebookJson.commandLine(it) + "\n" }
            DriveFiles.writeNamed(token, folderId, COMMANDS, body + lines)
        }
    }

    private companion object {
        const val PREFS = "yt_to_pc"
        const val KEY_FOLDER_ID = "drive_folder_id"
        const val KEY_FOLDER_NAME = "drive_folder_name"
        const val KEY_LAST_MUSIC_FOLDER = "last_music_folder"
        const val KEY_WALLPAPER = "default_wallpaper"
        const val KEY_ACCENT = "accent"
        val AccentKeys = setOf("dark-red", "dark-green", "dark-blue", "pink", "purple", "yellow", "orange")
        const val COMMANDS = "commands.jsonl"
        const val STATE = "state.json"
        const val STORAGE = "storage.json"
        const val LIBRARIES = "libraries.json"
        const val HISTORY = "history.jsonl"

        fun activeLibrary(catalog: String?): String? {
            if (catalog.isNullOrBlank()) return null
            return try {
                JSONObject(catalog).optString("active").trim().ifEmpty { null }
            } catch (_: Exception) {
                null
            }
        }

        fun legacyStateName(active: String?) = if (active.isNullOrBlank()) STATE else "$active-state.json"

        fun readNestedState(token: String, folderId: String, active: String?): String? {
            if (active.isNullOrBlank()) return null
            val stateRoot = DriveFiles.findFolder(token, folderId, "state") ?: return null
            val library = DriveFiles.findFolder(token, stateRoot, active) ?: return null
            return DriveFiles.readNamed(token, library, STATE)
        }

        fun readStorage(token: String, folderId: String, active: String?): String? {
            val stateRoot = DriveFiles.findFolder(token, folderId, "state") ?: return null
            val libraryName = active?.takeIf { it.isNotBlank() } ?: "Library"
            val library = DriveFiles.findFolder(token, stateRoot, libraryName) ?: return null
            return DriveFiles.readNamed(token, library, STORAGE)
        }

        fun parseStorage(text: String): StorageReport? = try {
            val json = JSONObject(text)
            val folders = json.optJSONArray("folders")
            val sizes = mutableListOf<FolderSize>()
            if (folders != null) {
                for (index in 0 until folders.length()) {
                    val folder = folders.getJSONObject(index)
                    sizes += FolderSize(folder.optString("name"), folder.optLong("bytes"))
                }
            }
            StorageReport(json.optString("root"), json.optLong("totalBytes"), sizes)
        } catch (_: Exception) {
            null
        }
    }
}

data class FolderSize(val name: String, val bytes: Long)

data class StorageReport(val root: String, val totalBytes: Long, val folders: List<FolderSize>)
