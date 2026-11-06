package app.yttopc.data

import android.content.Context
import app.yttopc.core.Command
import app.yttopc.core.LibraryState
import app.yttopc.core.NotebookJson
import org.json.JSONObject

sealed class LoadedNotebook {
    data object Missing : LoadedNotebook()
    data class Ready(val commands: List<Command>, val state: LibraryState) : LoadedNotebook()
    data class Failed(val reason: String) : LoadedNotebook()
}

class NotebookRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun folderId(): String? = prefs.getString(KEY_FOLDER_ID, null)

    fun folderName(): String? = prefs.getString(KEY_FOLDER_NAME, null)

    fun saveFolder(id: String, name: String) {
        prefs.edit().putString(KEY_FOLDER_ID, id).putString(KEY_FOLDER_NAME, name).apply()
    }

    fun clearFolder() {
        prefs.edit().remove(KEY_FOLDER_ID).remove(KEY_FOLDER_NAME).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun load(token: String): LoadedNotebook {
        val folderId = folderId() ?: return LoadedNotebook.Missing
        return try {
            synchronized(lock) {
                val commands = NotebookJson.readCommands(DriveFiles.readNamed(token, folderId, COMMANDS).orEmpty())
                val catalog = DriveFiles.readNamed(token, folderId, LIBRARIES)
                val active = activeLibrary(catalog)
                val stateText = readNestedState(token, folderId, active)
                    ?: DriveFiles.readNamed(token, folderId, legacyStateName(active))
                    ?: if (active == null) null else DriveFiles.readNamed(token, folderId, STATE)
                val state = if (stateText.isNullOrBlank()) LibraryState() else NotebookJson.readState(stateText)
                LoadedNotebook.Ready(commands, state)
            }
        } catch (error: Exception) {
            LoadedNotebook.Failed(error.message ?: "Could not read the queue.")
        }
    }

    fun append(token: String, command: Command) {
        val folderId = folderId() ?: error("Choose the Drive folder first.")
        synchronized(lock) {
            val existing = DriveFiles.readNamed(token, folderId, COMMANDS).orEmpty()
            val body = if (existing.isEmpty() || existing.endsWith("\n")) existing else "$existing\n"
            DriveFiles.writeNamed(token, folderId, COMMANDS, body + NotebookJson.commandLine(command) + "\n")
        }
    }

    private companion object {
        const val PREFS = "yt_to_pc"
        const val KEY_FOLDER_ID = "drive_folder_id"
        const val KEY_FOLDER_NAME = "drive_folder_name"
        const val COMMANDS = "commands.jsonl"
        const val STATE = "state.json"
        const val LIBRARIES = "libraries.json"

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
    }
}
