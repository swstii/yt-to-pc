package app.yttopc.core

object SongNames {
    fun same(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
    }
}
