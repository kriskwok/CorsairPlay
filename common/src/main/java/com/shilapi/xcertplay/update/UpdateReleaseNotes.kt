package com.shilapi.xcertplay.update

/** Converts the small Markdown subset used by GitHub release notes into readable dialog text. */
internal object UpdateReleaseNotes {
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*")
    private val BULLET = Regex("^\\s*[-*+]\\s+")
    private val LINK = Regex("\\[([^]]+)]\\([^)]+\\)")

    fun plainText(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .lineSequence()
        .joinToString("\n") { raw ->
            val line = HEADING.replace(raw.trimEnd(), "")
            BULLET.replace(line, "• ")
        }
        .replace(LINK, "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
}
