package app.yttopc.core

object LibraryRoots {
    fun cleaned(raw: String): String = raw.trim()

    fun problem(raw: String): String? {
        val path = cleaned(raw)
        if (path.isEmpty()) return "Enter the folder on the laptop where MP3s should go."
        if (!path.matches(ABSOLUTE)) return "Use a Windows path like E:\\Music."
        if (path.contains("..")) return "The path cannot contain .."
        if (path.any { it.code < 32 || it in INVALID }) {
            return "That path has a character Windows cannot use."
        }
        return null
    }

    private val ABSOLUTE = Regex("""^[A-Za-z]:\\.*""")
    private const val INVALID = "<>\"|?*"
}
