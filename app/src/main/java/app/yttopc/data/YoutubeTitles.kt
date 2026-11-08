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
        val browsed = browsePlaylist(id)
        if (browsed != null && browsed.videos.size > 1) return browsed
        val feed = readText("https://www.youtube.com/feeds/videos.xml?playlist_id=" + URLEncoder.encode(id, Charsets.UTF_8.name()), browser = true)
        val parsed = feed?.let { runCatching { parseFeed(it) }.getOrNull() }
        if (parsed != null && parsed.videos.isNotEmpty()) return parsed
        return browsed
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

    private fun browsePlaylist(id: String): PlaylistDetails? {
        val first = postBrowse(
            """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20241126.01.00","hl":"en"}},"browseId":"VL$id","params":"wgYCCAA="}""",
        ) ?: return null
        val title = Regex(""""playlistMetadataRenderer":\{"title":"((?:\\.|[^"\\])*)"""")
            .find(first)?.groupValues?.get(1)?.let(::jsonText)?.trim().orEmpty()
        val videos = linkedMapOf<String, PlaylistVideo>()
        fun add(text: String) {
            for (video in lockups(text)) videos.putIfAbsent(video.videoId, video)
        }
        add(first)
        var token = continuation(first)
        var pages = 0
        while (!token.isNullOrBlank() && pages < 40) {
            pages += 1
            val more = postBrowse(
                """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20241126.01.00","hl":"en"}},"continuation":${JSONObject.quote(token)}}""",
            ) ?: break
            val before = videos.size
            add(more)
            if (videos.size == before) break
            val next = continuation(more)
            if (next == null || next == token) break
            token = next
        }
        if (title.isBlank() && videos.isEmpty()) return null
        return PlaylistDetails(title.ifBlank { "Playlist" }, videos.values.toList())
    }

    private fun lockups(text: String): List<PlaylistVideo> {
        val videos = mutableListOf<PlaylistVideo>()
        val parts = text.split(""""lockupViewModel":{"contentImage"""")
        for (part in parts.drop(1)) {
            val id = Regex("""/vi/([A-Za-z0-9_-]{11})/""").find(part)?.groupValues?.get(1) ?: continue
            val raw = Regex(""""lockupMetadataViewModel":\{"title":\{"content":"((?:\\.|[^"\\])*)"""")
                .find(part)?.groupValues?.get(1)
            val title = raw?.let(::jsonText)?.trim().orEmpty().ifBlank { id }
            val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            videos += PlaylistVideo(id, title, thumb)
        }
        return videos
    }

    private fun continuation(text: String): String? =
        Regex(""""continuationCommand":\{"token":"([^"]+)"""").findAll(text)
            .map { it.groupValues[1] }
            .maxByOrNull { it.length }

    private fun postBrowse(body: String): String? {
        val connection = open("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", browser = true)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonText(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'u' -> if (index + 5 < value.length) {
                        out.append(value.substring(index + 2, index + 6).toIntOrNull(16)?.toChar() ?: '?')
                        index += 6
                        continue
                    }
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    else -> out.append(value[index + 1])
                }
                index += 2
            } else {
                out.append(char)
                index += 1
            }
        }
        return out.toString()
    }

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
        connectTimeout = 12_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty(
            "User-Agent",
            if (browser) "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" else "YT-to-PC",
        )
    }
}
