package app.yttopc.data

import android.content.Context
import app.yttopc.core.Command
import app.yttopc.core.NotebookJson
import app.yttopc.core.PhoneRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalQueue(context: Context) {
    private val file = File(context.applicationContext.filesDir, "outbox.json")
    private val lock = Any()

    fun read(): List<PhoneRequest> = synchronized(lock) { load() }

    fun add(command: Command) = synchronized(lock) {
        val items = load().toMutableList()
        if (items.none { it.command.id == command.id }) {
            items += PhoneRequest(command, uploaded = false)
        }
        save(items)
    }

    fun markUploaded(id: String) = update(id) { it.copy(uploaded = true, rejected = null) }

    fun reject(id: String, reason: String) = update(id) { it.copy(rejected = reason) }

    fun dropUploaded(present: Set<String>) = synchronized(lock) {
        save(load().filterNot { it.uploaded && it.rejected == null && it.command.id in present })
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun update(id: String, change: (PhoneRequest) -> PhoneRequest) = synchronized(lock) {
        save(load().map { if (it.command.id == id) change(it) else it })
    }

    private fun load(): List<PhoneRequest> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val command = runCatching { NotebookJson.readCommands(item.optString("line")).firstOrNull() }.getOrNull() ?: continue
                add(PhoneRequest(command, item.optBoolean("uploaded"), item.optString("rejected").ifBlank { null }))
            }
        }
    }

    private fun save(items: List<PhoneRequest>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("line", NotebookJson.commandLine(item.command))
                    .put("uploaded", item.uploaded)
                    .put("rejected", item.rejected ?: ""),
            )
        }
        file.writeText(array.toString())
    }
}
