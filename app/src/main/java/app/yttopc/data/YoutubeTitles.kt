package app.yttopc.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YoutubeTitles {
    fun lookup(id: String): String? {
        val page = if (id.length == 11) {
            "https://www.youtube.com/watch?v=$id"
        } else {
            "https://www.youtube.com/playlist?list=$id"
        }
        val address = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(page, Charsets.UTF_8.name())
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "YT-to-PC")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val title = JSONObject(connection.inputStream.bufferedReader().readText()).optString("title").trim()
            title?.takeIf { it.isNotEmpty() && it != id }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
