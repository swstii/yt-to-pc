package app.yttopc.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed class YoutubeTarget {
    abstract val url: String

    data class Video(override val url: String, val videoId: String) : YoutubeTarget()

    data class Playlist(override val url: String, val playlistId: String) : YoutubeTarget()

    data class VideoAndPlaylist(
        override val url: String,
        val videoId: String,
        val playlistId: String,
    ) : YoutubeTarget()
}

sealed class ParseResult {
    data class Found(val target: YoutubeTarget) : ParseResult()
    data object NotALink : ParseResult()
    data object NotYoutube : ParseResult()
}

enum class ShareChoice {
    SONG,
    PLAYLIST,
}

object YoutubeLinks {
    private val urlPattern = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
    private val videoIdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val playlistIdPattern = Regex("""^[A-Za-z0-9_-]{2,80}$""")

    fun parse(sharedText: String): ParseResult {
        val raw = urlPattern.find(sharedText)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
            ?: return ParseResult.NotALink
        val uri = runCatching { URI(raw) }.getOrNull() ?: return ParseResult.NotALink
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return ParseResult.NotALink
        if (!isYoutubeHost(host)) return ParseResult.NotYoutube

        val query = queryMap(uri.rawQuery.orEmpty())
        val videoId = videoId(host, uri.path.orEmpty(), query)
        val playlistId = query["list"]?.takeIf { playlistIdPattern.matches(it) }
        val url = uri.toString()

        return when {
            videoId != null && playlistId != null ->
                ParseResult.Found(YoutubeTarget.VideoAndPlaylist(url, videoId, playlistId))
            videoId != null -> ParseResult.Found(YoutubeTarget.Video(url, videoId))
            playlistId != null -> ParseResult.Found(YoutubeTarget.Playlist(url, playlistId))
            else -> ParseResult.NotYoutube
        }
    }

    private fun isYoutubeHost(host: String): Boolean {
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun videoId(host: String, path: String, query: Map<String, String>): String? {
        query["v"]?.takeIf { videoIdPattern.matches(it) }?.let { return it }
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (host == "youtu.be") {
            return segments.firstOrNull()?.takeIf { videoIdPattern.matches(it) }
        }
        val marker = segments.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
        if (marker >= 0) {
            return segments.getOrNull(marker + 1)?.takeIf { videoIdPattern.matches(it) }
        }
        return null
    }

    private fun queryMap(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            if (key.isEmpty()) null else key to value
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}
