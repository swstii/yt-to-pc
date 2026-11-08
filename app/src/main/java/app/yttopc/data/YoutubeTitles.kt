package app.yttopc.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class YoutubePreview(val title: String, val author: String, val thumbnail: Bitmap?)

data class PlaylistVideo(val videoId: String, val title: String, val thumbnailUrl: String?)

data class PlaylistDetails(val title: String, val videos: List<PlaylistVideo>)

object YoutubeTitles {
    private val cache = ConcurrentHashMap<String, YoutubePreview>()
    private val playlists = ConcurrentHashMap<String, PlaylistDetails>()

    fun lookup(id: String): String? = cache[id]?.title ?: fetch(id, withThumbnail = false)?.title

    fun preview(id: String): YoutubePreview? {
        cache[id]?.takeIf { it.thumbnail != null }?.let { return it }
        return fetch(id, withThumbnail = true)
    }

    fun playlist(id: String): PlaylistDetails? {
        playlists[id]?.let { return it }
        val loaded = readPlaylist(id) ?: return null
        playlists[id] = loaded
        return loaded
    }

    fun image(address: String): Bitmap? = readBitmap(address)

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

    private fun readPlaylist(id: String): PlaylistDetails? {
        val feed = readText("https://www.youtube.com/feeds/videos.xml?playlist_id=" + URLEncoder.encode(id, Charsets.UTF_8.name()), browser = true)
        val parsed = feed?.let { runCatching { parseFeed(it) }.getOrNull() }
        if (parsed != null && (parsed.title.isNotBlank() || parsed.videos.isNotEmpty())) return parsed
        return playlistPage(id)
    }

    private fun parseFeed(xml: String): PlaylistDetails? {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        var event = parser.eventType
        var inEntry = false
        var playlistTitle = ""
        var currentTitle = ""
        var currentId = ""
        var currentThumb: String? = null
        val videos = mutableListOf<PlaylistVideo>()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "entry" -> {
                        inEntry = true
                        currentTitle = ""
                        currentId = ""
                        currentThumb = null
                    }
                    "title" -> {
                        val value = parser.nextText().trim()
                        if (inEntry) currentTitle = value else if (playlistTitle.isEmpty()) playlistTitle = value
                    }
                    "videoId" -> currentId = parser.nextText().trim()
                    "thumbnail" -> if (inEntry && currentThumb == null) currentThumb = parser.getAttributeValue(null, "url")
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "entry") {
                    if (currentId.isNotBlank()) {
                        videos += PlaylistVideo(currentId, currentTitle.ifBlank { currentId }, currentThumb)
                    }
                    inEntry = false
                }
            }
            event = parser.next()
        }
        if (playlistTitle.isBlank() && videos.isEmpty()) return null
        return PlaylistDetails(playlistTitle.ifBlank { "Playlist" }, videos)
    }

    private fun playlistPage(id: String): PlaylistDetails? {
        val html = readText("https://www.youtube.com/playlist?list=$id", browser = true) ?: return null
        val title = Regex("""property="og:title"\s+content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val image = Regex("""property="og:image"\s+content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.let(::unescape)
        if (title.isBlank() && image.isNullOrBlank()) return null
        val thumbVideo = image?.let { Regex("""/vi/([A-Za-z0-9_-]{11})/""").find(it)?.groupValues?.get(1) }
        val videos = if (thumbVideo == null) emptyList() else listOf(PlaylistVideo(thumbVideo, title.ifBlank { thumbVideo }, image))
        return PlaylistDetails(title.ifBlank { "Playlist" }, videos)
    }

    private fun unescape(value: String) = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun readText(address: String, browser: Boolean = false): String? {
        val connection = open(address, browser)
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
        val connection = open(address, browser = true)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else BitmapFactory.decodeStream(connection.inputStream)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, browser: Boolean = false) = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        instanceFollowRedirects = true
        setRequestProperty(
            "User-Agent",
            if (browser) "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" else "YT-to-PC",
        )
    }
}
