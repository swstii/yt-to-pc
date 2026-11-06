package app.yttopc.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFolder(val id: String, val name: String)

object DriveFiles {
    fun listFolders(token: String): List<DriveFolder> {
        val query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = mutableListOf<DriveFolder>()
        var page: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?pageSize=100")
                append("&fields=files(id,name),nextPageToken")
                append("&q=")
                append(URLEncoder.encode(query, Charsets.UTF_8.name()))
                if (page != null) {
                    append("&pageToken=")
                    append(URLEncoder.encode(page, Charsets.UTF_8.name()))
                }
            }
            val json = JSONObject(request("GET", url, token, null, null))
            val files = json.optJSONArray("files")
            if (files != null) {
                for (index in 0 until files.length()) {
                    val file = files.getJSONObject(index)
                    found += DriveFolder(file.getString("id"), file.getString("name"))
                }
            }
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return found.sortedBy { it.name.lowercase() }
    }

    fun createFolder(token: String, name: String): DriveFolder {
        val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
        val json = JSONObject(
            request(
                "POST",
                "https://www.googleapis.com/drive/v3/files?fields=id,name",
                token,
                body.toString().toByteArray(Charsets.UTF_8),
                "application/json; charset=UTF-8",
            ),
        )
        return DriveFolder(json.getString("id"), json.getString("name"))
    }

    fun readNamed(token: String, parentId: String, name: String): String? {
        val id = findNamed(token, parentId, name) ?: return null
        return request(
            "GET",
            "https://www.googleapis.com/drive/v3/files/${encode(id)}?alt=media",
            token,
            null,
            null,
        )
    }

    fun writeNamed(token: String, parentId: String, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val existing = findNamed(token, parentId, name)
        if (existing == null) {
            createFile(token, parentId, name, bytes)
        } else {
            request(
                "PATCH",
                "https://www.googleapis.com/upload/drive/v3/files/${encode(existing)}?uploadType=media",
                token,
                bytes,
                "text/plain; charset=UTF-8",
            )
        }
    }

    private fun findNamed(token: String, parentId: String, name: String): String? {
        val query = "name = '${name.replace("'", "\\'")}' and '$parentId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?pageSize=1&fields=files(id)&q=" +
            URLEncoder.encode(query, Charsets.UTF_8.name())
        val files = JSONObject(request("GET", url, token, null, null)).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).getString("id")
    }

    private fun createFile(token: String, parentId: String, name: String, bytes: ByteArray) {
        val boundary = "yt_to_pc_boundary"
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId))
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta.toString())
            append("\r\n--").append(boundary).append("\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
        }.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
        request(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            token,
            body,
            "multipart/related; boundary=$boundary",
        )
    }

    private fun request(method: String, url: String, token: String, body: ByteArray?, contentType: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        if (body != null) connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) error(driveError(code, text))
        return text
    }

    private fun driveError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        if (code == 401) return "Google Drive needs you to sign in again."
        if (!message.isNullOrBlank()) return message
        return "Google Drive returned $code."
    }

    private fun encode(id: String): String = URLEncoder.encode(id, Charsets.UTF_8.name())
}
