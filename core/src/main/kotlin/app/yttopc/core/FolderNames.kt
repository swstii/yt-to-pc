package app.yttopc.core

object FolderNames {
    const val QUEUE_DIR = "_queue"

    private val reserved = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun cleaned(raw: String): String = raw.trim()

    fun problem(raw: String): String? {
        val name = cleaned(raw)
        if (name.isEmpty()) return "Enter a folder name."
        if (name.length > 80) return "That folder name is too long."
        if (name == "." || name == "..") return "That folder name is not allowed."
        if (name.equals(QUEUE_DIR, ignoreCase = true)) {
            return "That name is reserved for the queue notebook."
        }
        if (name.endsWith('.') || name.endsWith(' ')) {
            return "A folder name cannot end with a space or a dot."
        }
        if (name.any { it.code < 32 || it in INVALID }) {
            return "That name has a character Windows folders cannot use."
        }
        val stem = name.substringBefore('.').lowercase()
        if (stem in reserved) return "That name is reserved by Windows."
        return null
    }

    fun same(left: String, right: String): Boolean = left.equals(right, ignoreCase = true)

    private const val INVALID = "<>:\"/\\|?*"
}
