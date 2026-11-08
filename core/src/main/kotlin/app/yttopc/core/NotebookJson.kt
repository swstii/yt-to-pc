package app.yttopc.core

import kotlinx.serialization.json.Json

/**
 * Shared notebook.
 *
 * The phone appends one JSON object per line to commands.jsonl.
 * The laptop writes state.json and is the only writer of that file.
 * A command is still waiting when its id is absent from state.commands.
 */
object NotebookJson {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
    }

    fun commandLine(command: Command): String = json.encodeToString(Command.serializer(), command)

    fun readCommands(text: String): List<Command> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { json.decodeFromString(Command.serializer(), line) }.getOrNull() }
            .toList()
    }

    fun readHistory(text: String): List<HistoryEntry> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { json.decodeFromString(HistoryEntry.serializer(), line) }.getOrNull() }
            .toList()
    }

    fun readState(text: String): LibraryState {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LibraryState()
        return json.decodeFromString(LibraryState.serializer(), trimmed)
    }
}
