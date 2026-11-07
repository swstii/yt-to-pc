package app.yttopc.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class YoutubePreview(val title: String, val author: String, val thumbnail: Bitmap?)

object YoutubeTitles {
    private val cache = ConcurrentHashMap<String, YoutubePreview>()

    fun lookup(id: String): String? = cache[id]?.title ?: fetch(id, withThumbnail = false)?.title

    fun preview(id: String): YoutubePreview? {
        cache[id]?.takeIf { it.thumbnail != null }?.let { return it }
        return fetch(id, withThumbnail = true)
    }

    private fun fetch(id: String, withThumbnail: Boolean): YoutubePreview? {
        val page = if (id.length == 11) {
            "https://www.youtube.com/watch?v=$id"
        } else {
            "https://www.youtube.com/playlist?list=$id"
        }
        val address = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(page, Charsets.UTF_8.name())
        val json = readText(address)?.let(::JSONObject) ?: return null
        val title = json.optString("title").trim().takeIf { it.isNotEmpty() && it != id } ?: return null
        val author = json.optString("author_name").trim()
        val thumbnail = if (withThumbnail) {
            json.optString("thumbnail_url").trim().takeIf { it.startsWith("https://") }?.let(::readBitmap)
                ?: if (id.length == 11) readBitmap("https://i.ytimg.com/vi/$id/hqdefault.jpg") else null
        } else {
            cache[id]?.thumbnail
        }
        return YoutubePreview(title, author, thumbnail).also { cache[id] = it }
    }

    private fun readText(address: String): String? {
        val connection = open(address)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readBitmap(address: String): Bitmap? {
        val connection = open(address)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else BitmapFactory.decodeStream(connection.inputStream)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String) = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "YT-to-PC")
    }
}
