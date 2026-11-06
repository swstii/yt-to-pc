package app.yttopc.data

import java.net.HttpURLConnection
import java.net.URL

object Reachability {
    fun driveReachable(): Boolean = try {
        val connection = (URL("https://www.googleapis.com/drive/v3/about?fields=user").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            connection.responseCode < 500
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }
}
