package it.grg.flighttimeapp.aletheia

/**
 * Client-side answer cleanup shared by Online (post-server) and Offline (post-GGUF).
 * Mirrors iOS product rules: no brand prefix spam, no markdown chrome, no system-prompt leaks.
 */
object AletheiaAnswerFormatter {

    private val brandPrefixRegex = Regex(
        """(?im)^\s*(?:sono\s+|i\s+am\s+|i'?m\s+)?\*{0,2}aletheia\s+beta\*{0,2}(?:\s*\((?:offline|online)\))?\s*[.!]?\s*"""
    )
    private val markdownHeading = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
    private val markdownBold = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val markdownItalic = Regex("""(?<!\w)\*(.+?)\*(?!\w)|(?<!\w)_(.+?)_(?!\w)""")
    private val markdownTableSep = Regex("""(?m)^\s*\|.+\|\s*$""")
    private val markdownCodeFence = Regex("""```[\s\S]*?```""")
    private val systemLeakRegex = Regex(
        """(?is)(?:/assistant\.system|example\s+system|\bsystem\s+system\b|\bc_limit\b|<\|.*?\|>)"""
    )
    private val offlineStubWhileOnline = Regex(
        """(?is)(?:aviation/ftl\s+assistant\s+offline|switch\s+to\s+online|place\s+it\s+in\s+documents/models|temporary\s+faq)"""
    )

    /**
     * Strip brand intros, decorative markdown, and leaked system tokens.
     * Keeps plain numbered prose.
     */
    fun sanitize(raw: String, forOnline: Boolean = false): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        // Drop repeated brand / "Sono Aletheia Beta" lines at the start (and after blank lines).
        repeat(4) {
            val next = brandPrefixRegex.replaceFirst(text, "").trimStart()
            if (next == text) return@repeat
            text = next
        }

        if (systemLeakRegex.containsMatchIn(text) || text.contains("C_LIMIT", ignoreCase = true)) {
            return gracefulDegradedMessage(forOnline)
        }

        if (forOnline && offlineStubWhileOnline.containsMatchIn(text)) {
            return onlineModeMismatchMessage()
        }

        text = markdownCodeFence.replace(text, "")
        text = text.lines()
            .filterNot { markdownTableSep.matches(it) }
            .joinToString("\n")
        text = markdownHeading.replace(text, "")
        text = markdownBold.replace(text) { mr -> mr.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: "" }
        text = markdownItalic.replace(text) { mr -> mr.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: "" }

        // Collapse runaway identical short lines (GGUF garbage loops).
        text = collapseRepeatedLines(text)

        return text.trim().ifBlank { gracefulDegradedMessage(forOnline) }
    }

    fun looksLikeSystemLeak(raw: String): Boolean =
        systemLeakRegex.containsMatchIn(raw) || raw.contains("C_LIMIT", ignoreCase = true)

    fun looksLikeOfflineStub(raw: String): Boolean =
        offlineStubWhileOnline.containsMatchIn(raw)

    private fun collapseRepeatedLines(text: String): String {
        val out = ArrayList<String>()
        var prev: String? = null
        var streak = 0
        for (line in text.lines()) {
            val key = line.trim()
            if (key.isNotEmpty() && key.equals(prev, ignoreCase = true)) {
                streak++
                if (streak >= 3) continue
            } else {
                streak = 0
            }
            prev = key
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun gracefulDegradedMessage(forOnline: Boolean): String {
        return if (forOnline) {
            "I could not produce a clean answer for that turn. Please rephrase the aviation question, or ask again in a moment."
        } else {
            "Offline could not answer that cleanly. Ask an aviation / FTL / procedures study question, or switch to Online for general topics."
        }
    }

    private fun onlineModeMismatchMessage(): String =
        "You are already in Online mode. Please rephrase your question — I will answer here without asking you to switch modes."
}
